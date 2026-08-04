package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Decoupled, PopCount, PriorityEncoder, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.{MemoryAccessKind, MemoryCommand,
  MemoryRequestTxn, MemoryResponseTxn}

class DSideTranslationRefill(
    val addrWidth: Int,
    val pageBytes: Int)
    extends Bundle {
  private val pageOffsetWidth = log2Ceil(pageBytes)
  private val pageNumberWidth = addrWidth - pageOffsetWidth

  val vpn = UInt(pageNumberWidth.W)
  val ppn = UInt(pageNumberWidth.W)
  val readable = Bool()
  val writable = Bool()
  val cacheable = Bool()
  val device = Bool()
}

class DSideTranslationIO(
    val p: CoreParams,
    val entries: Int,
    val pageBytes: Int)
    extends Bundle {
  val lookupValid = Input(Bool())
  val virtualAddress = Input(UInt(p.physicalAddressWidth.W))
  val sizeBytes = Input(UInt(4.W))
  val write = Input(Bool())
  val invalidate = Input(Bool())

  val lookupReady = Output(Bool())
  val hit = Output(Bool())
  val miss = Output(Bool())
  val physicalAddress = Output(UInt(p.physicalAddressWidth.W))
  val alignmentFault = Output(Bool())
  val accessFault = Output(Bool())
  val cacheable = Output(Bool())
  val device = Output(Bool())

  val memoryRequest = Decoupled(new MemoryRequestTxn(p))
  val memoryResponse = Flipped(Decoupled(new MemoryResponseTxn(p)))
  val responseOwned = Output(Bool())
  val responseMatch = Output(Bool())
  val staleResponse = Output(Bool())
  val outstanding = Output(Bool())
  val quiescent = Output(Bool())
}

/** Retained D-side translation owner.
  *
  * Low canonical addresses remain identity mapped while the high-half virtual
  * range exercises the explicit DTLB/PTW path.  This preserves the existing
  * physical-address producers during cutover without creating a second load
  * or cache owner.  A lower response refills one direct-mapped DTLB entry only
  * on the complete memory transaction identity.
  */
class DSideTranslation(
    val p: CoreParams,
    val entries: Int = 4,
    val pageBytes: Int = 4096)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0)
  require(pageBytes > 1 && (pageBytes & (pageBytes - 1)) == 0)
  require(p.physicalAddressWidth > log2Ceil(pageBytes))

  private val addrWidth = p.physicalAddressWidth
  private val pageOffsetWidth = log2Ceil(pageBytes)
  private val pageNumberWidth = addrWidth - pageOffsetWidth
  private val indexWidth = log2Ceil(entries)

  val io = IO(new DSideTranslationIO(p, entries, pageBytes))

  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val vpn = Reg(Vec(entries, UInt(pageNumberWidth.W)))
  val ppn = Reg(Vec(entries, UInt(pageNumberWidth.W)))
  val readable = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val writable = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val cacheable = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val device = RegInit(VecInit(Seq.fill(entries)(false.B)))

  val lookupVpn = io.virtualAddress(addrWidth - 1, pageOffsetWidth)
  val lookupIndex = lookupVpn(indexWidth - 1, 0)
  val explicitTranslation = io.virtualAddress(addrWidth - 1)
  val entryHit = valid(lookupIndex) && vpn(lookupIndex) === lookupVpn
  val translatedAddress = Cat(
    ppn(lookupIndex), io.virtualAddress(pageOffsetWidth - 1, 0))
  val hit = !explicitTranslation || entryHit
  val physicalAddress = Mux(explicitTranslation,
    translatedAddress, io.virtualAddress)

  val supportedSize = io.sizeBytes === 1.U || io.sizeBytes === 2.U ||
    io.sizeBytes === 4.U || io.sizeBytes === 8.U
  val alignmentMask = Mux(io.sizeBytes === 0.U, 0.U,
    io.sizeBytes - 1.U)
  val alignmentFault = io.lookupValid &&
    (!supportedSize || (io.virtualAddress & alignmentMask) =/= 0.U)
  val permissionFault = explicitTranslation && entryHit &&
    Mux(io.write, !writable(lookupIndex), !readable(lookupIndex))

  val active = RegInit(false.B)
  val activeVpn = Reg(UInt(pageNumberWidth.W))
  val requestOutstanding = RegInit(false.B)
  val requestIdentity = RegInit(
    0.U.asTypeOf(io.memoryRequest.bits.identity))
  val nextValue = RegInit(
    (BigInt(1) << (p.memoryTransactionIdWidth - 1)).U(
      p.memoryTransactionIdWidth.W))
  val nextGeneration = RegInit(
    (BigInt(1) << (p.memoryTransactionGenerationWidth - 1)).U(
      p.memoryTransactionGenerationWidth.W))

  val miss = io.lookupValid && explicitTranslation && !entryHit
  when(miss && !active) {
    active := true.B
    activeVpn := lookupVpn
  }

  io.memoryRequest.valid := active && !requestOutstanding
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.identity.value := nextValue
  io.memoryRequest.bits.identity.generation := nextGeneration
  io.memoryRequest.bits.command := MemoryCommand.Read
  io.memoryRequest.bits.accessKind := MemoryAccessKind.Data
  io.memoryRequest.bits.address := activeVpn << pageOffsetWidth
  io.memoryRequest.bits.sizeBytes := (p.dataWidth / 8).U
  io.memoryRequest.bits.instructionSide := false.B

  when(io.memoryRequest.fire) {
    requestOutstanding := true.B
    requestIdentity := io.memoryRequest.bits.identity
    val valueWrap = nextValue.andR
    nextValue := nextValue + 1.U
    when(valueWrap) {
      nextGeneration := nextGeneration + 1.U
    }
  }

  val responseMatch = requestOutstanding &&
    io.memoryResponse.bits.identity.value === requestIdentity.value &&
      io.memoryResponse.bits.identity.generation === requestIdentity.generation
  val responseOwned =
    io.memoryResponse.bits.identity.value(p.memoryTransactionIdWidth - 1) &&
      io.memoryResponse.bits.identity.generation(
        p.memoryTransactionGenerationWidth - 1)
  io.memoryResponse.ready := true.B
  io.responseOwned := responseOwned
  io.responseMatch := responseMatch
  io.staleResponse := io.memoryResponse.valid && responseOwned && !responseMatch

  when(io.memoryResponse.fire && responseMatch) {
    val refillIndex = activeVpn(indexWidth - 1, 0)
    val refillAllowed = !io.memoryResponse.bits.denied &&
      !io.memoryResponse.bits.corrupt
    val refillPpn =
      io.memoryResponse.bits.data(pageNumberWidth - 1, 0)
    val refillDevice = refillPpn(pageNumberWidth - 1,
      pageNumberWidth - 8).andR
    valid(refillIndex) := true.B
    vpn(refillIndex) := activeVpn
    ppn(refillIndex) := refillPpn
    readable(refillIndex) := refillAllowed
    writable(refillIndex) := refillAllowed
    cacheable(refillIndex) := refillAllowed && !refillDevice
    device(refillIndex) := refillAllowed && refillDevice
    requestOutstanding := false.B
    active := false.B
  }

  when(io.invalidate) {
    valid.foreach(_ := false.B)
  }

  io.lookupReady := io.lookupValid && hit && !alignmentFault &&
    !permissionFault
  io.hit := io.lookupValid && hit
  io.miss := miss
  io.physicalAddress := physicalAddress
  io.alignmentFault := alignmentFault
  io.accessFault := io.lookupValid && permissionFault
  io.cacheable := !explicitTranslation ||
    (entryHit && cacheable(lookupIndex))
  io.device := explicitTranslation && entryHit && device(lookupIndex)
  io.outstanding := active || requestOutstanding
  io.quiescent := !active && !requestOutstanding

  assert(PopCount(valid) <= entries.U)
}
