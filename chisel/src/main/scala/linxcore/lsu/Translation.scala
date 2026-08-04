package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Decoupled, PopCount, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.{LoadIssueTxn, MemoryAccessKind, MemoryCommand,
  MemoryRequestTxn, MemoryResponseTxn, StoreAddressTxn}

class DSideTranslationRequest(val p: CoreParams) extends Bundle {
  val isStore = Bool()
  val load = new LoadIssueTxn(p)
  val store = new StoreAddressTxn(p)
}

class DSideTranslationResult(val p: CoreParams) extends Bundle {
  val request = new DSideTranslationRequest(p)
  val physicalAddress = UInt(p.physicalAddressWidth.W)
  val alignmentFault = Bool()
  val accessFault = Bool()
  val cacheable = Bool()
  val device = Bool()
}

class DSideTranslationIO(
    val p: CoreParams,
    val entries: Int,
    val pageBytes: Int)
    extends Bundle {
  val lookup = Flipped(Decoupled(new DSideTranslationRequest(p)))
  val result = Decoupled(new DSideTranslationResult(p))
  val invalidate = Input(Bool())

  val memoryRequest = Decoupled(new MemoryRequestTxn(p))
  val memoryResponse = Flipped(Decoupled(new MemoryResponseTxn(p)))
  val responseOwned = Output(Bool())
  val responseMatch = Output(Bool())
  val staleResponse = Output(Bool())
  val outstanding = Output(Bool())
  val quiescent = Output(Bool())
}

/** The sole retained D-side translation owner. It accepts a complete load or
  * STA transaction through Decoupled, retains it across a PTW miss, and
  * publishes exactly one retained result. Physical protection and memory
  * attributes are classified after either mapping through one canonical
  * region source.
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

  private def requestAddress(request: DSideTranslationRequest): UInt =
    Mux(request.isStore, request.store.address, request.load.address)
  private def requestSize(request: DSideTranslationRequest): UInt =
    Mux(request.isStore, request.store.sizeBytes, request.load.sizeBytes)

  private def physicalAttributes(address: UInt): (Bool, Bool, Bool, Bool) = {
    val default = p.lsu.defaultMemoryAttributes
    p.lsu.physicalMemoryRegions.foldRight((
        default.readable.B, default.writable.B,
        default.cacheable.B, default.device.B)) { (region, fallback) =>
      val selected = (address & region.mask.U(addrWidth.W)) ===
        (region.base & region.mask).U(addrWidth.W)
      val attributes = region.attributes
      (
        Mux(selected, attributes.readable.B, fallback._1),
        Mux(selected, attributes.writable.B, fallback._2),
        Mux(selected, attributes.cacheable.B, fallback._3),
        Mux(selected, attributes.device.B, fallback._4))
    }
  }

  val lookupAddress = requestAddress(io.lookup.bits)
  val lookupSize = requestSize(io.lookup.bits)
  val lookupVpn = lookupAddress(addrWidth - 1, pageOffsetWidth)
  val lookupIndex = lookupVpn(indexWidth - 1, 0)
  val explicitTranslation = lookupAddress(addrWidth - 1)
  val entryHit = valid(lookupIndex) && vpn(lookupIndex) === lookupVpn
  val translatedAddress = Cat(
    ppn(lookupIndex), lookupAddress(pageOffsetWidth - 1, 0))
  val mappedAddress = Mux(explicitTranslation && entryHit,
    translatedAddress, lookupAddress)
  val supportedSize = lookupSize === 1.U || lookupSize === 2.U ||
    lookupSize === 4.U || lookupSize === 8.U
  val alignmentMask = Mux(lookupSize === 0.U, 0.U, lookupSize - 1.U)
  val alignmentFault = !supportedSize ||
    (lookupAddress & alignmentMask) =/= 0.U
  val physical = physicalAttributes(mappedAddress)
  val mappingReadable = Mux(explicitTranslation, entryHit && readable(lookupIndex), true.B)
  val mappingWritable = Mux(explicitTranslation, entryHit && writable(lookupIndex), true.B)
  val selectedReadable = mappingReadable && physical._1
  val selectedWritable = mappingWritable && physical._2
  val permissionFault = Mux(io.lookup.bits.isStore,
    !selectedWritable, !selectedReadable)
  val selectedDevice = Mux(explicitTranslation,
    entryHit && device(lookupIndex), false.B) || physical._4
  val selectedCacheable = Mux(explicitTranslation,
    entryHit && cacheable(lookupIndex), true.B) && physical._3 && !selectedDevice

  val active = RegInit(false.B)
  val activeRequest = Reg(new DSideTranslationRequest(p))
  val activeVpn = Reg(UInt(pageNumberWidth.W))
  val requestOutstanding = RegInit(false.B)
  val requestIdentity = RegInit(0.U.asTypeOf(io.memoryRequest.bits.identity))
  val resultValid = RegInit(false.B)
  val resultBits = Reg(new DSideTranslationResult(p))

  require(p.memoryTransactionIdWidth > 1 &&
    p.memoryTransactionGenerationWidth > 1,
    "translation namespace needs owner and counter bits")
  private val counterBits = p.lsu.dTranslationCounterBits
  require(counterBits > 0 && counterBits < p.memoryTransactionIdWidth &&
    counterBits < p.memoryTransactionGenerationWidth)
  val nextValueCounter = RegInit(0.U(counterBits.W))
  val nextGenerationCounter = RegInit(0.U(counterBits.W))

  io.lookup.ready := !active && !resultValid
  when(io.lookup.fire) {
    when(explicitTranslation && !entryHit && !alignmentFault) {
      active := true.B
      activeRequest := io.lookup.bits
      activeVpn := lookupVpn
    }.otherwise {
      resultValid := true.B
      resultBits.request := io.lookup.bits
      resultBits.physicalAddress := mappedAddress
      resultBits.alignmentFault := alignmentFault
      resultBits.accessFault := !alignmentFault && permissionFault
      resultBits.cacheable := selectedCacheable
      resultBits.device := selectedDevice
    }
  }

  io.result.valid := resultValid
  io.result.bits := resultBits
  when(io.result.fire) { resultValid := false.B }

  io.memoryRequest.valid := active && !requestOutstanding
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.identity.value := Cat(1.U(1.W),
    0.U((p.memoryTransactionIdWidth - counterBits - 1).W), nextValueCounter)
  io.memoryRequest.bits.identity.generation := Cat(1.U(1.W),
    0.U((p.memoryTransactionGenerationWidth - counterBits - 1).W),
    nextGenerationCounter)
  io.memoryRequest.bits.command := MemoryCommand.Read
  io.memoryRequest.bits.accessKind := MemoryAccessKind.Data
  io.memoryRequest.bits.address := activeVpn << pageOffsetWidth
  io.memoryRequest.bits.sizeBytes := (p.dataWidth / 8).U
  io.memoryRequest.bits.instructionSide := false.B

  when(io.memoryRequest.fire) {
    requestOutstanding := true.B
    requestIdentity := io.memoryRequest.bits.identity
    val valueWrap = nextValueCounter.andR
    nextValueCounter := nextValueCounter + 1.U
    when(valueWrap) { nextGenerationCounter := nextGenerationCounter + 1.U }
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
      !io.memoryResponse.bits.corrupt && io.memoryResponse.bits.attributesValid
    val refillPpn = io.memoryResponse.bits.data(pageNumberWidth - 1, 0)
    val refillPhysicalAddress = Cat(refillPpn,
      requestAddress(activeRequest)(pageOffsetWidth - 1, 0))
    val refillPhysical = physicalAttributes(refillPhysicalAddress)
    val refillReadable = refillAllowed && io.memoryResponse.bits.readable
    val refillWritable = refillAllowed && io.memoryResponse.bits.writable
    val refillDevice = refillAllowed && io.memoryResponse.bits.device
    val refillCacheable = refillAllowed && io.memoryResponse.bits.cacheable &&
      !io.memoryResponse.bits.device
    valid(refillIndex) := true.B
    vpn(refillIndex) := activeVpn
    ppn(refillIndex) := refillPpn
    readable(refillIndex) := refillReadable
    writable(refillIndex) := refillWritable
    cacheable(refillIndex) := refillCacheable
    device(refillIndex) := refillDevice

    val finalDevice = refillDevice || refillPhysical._4
    val finalReadable = refillReadable && refillPhysical._1
    val finalWritable = refillWritable && refillPhysical._2
    resultValid := true.B
    resultBits.request := activeRequest
    resultBits.physicalAddress := refillPhysicalAddress
    resultBits.alignmentFault := false.B
    resultBits.accessFault := Mux(activeRequest.isStore,
      !finalWritable, !finalReadable)
    resultBits.cacheable := refillCacheable && refillPhysical._3 && !finalDevice
    resultBits.device := finalDevice
    requestOutstanding := false.B
    active := false.B
  }

  when(io.invalidate) { valid.foreach(_ := false.B) }

  io.outstanding := active || requestOutstanding || resultValid
  io.quiescent := !active && !requestOutstanding && !resultValid

  assert(PopCount(valid) <= entries.U)
}
