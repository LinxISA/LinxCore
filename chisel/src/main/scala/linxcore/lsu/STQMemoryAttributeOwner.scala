package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, PopCount, log2Ceil}

/** Translation/PMA classification retained independently from decode.
  *
  * Unknown is never a routable memory type. Fault is retained so the LSU can
  * fail closed while the precise exception owner reports the architectural
  * fault through the ROB path.
  */
object STQMemoryClass extends ChiselEnum {
  val Unknown, NormalCacheable, NormalNonCacheable, DeviceMmio, Fault = Value
}

class STQMemoryAttribute extends Bundle {
  val valid = Bool()
  val memoryClass = STQMemoryClass()
}

/** Exact translation/PMA result for one physical STQ residency.
  *
  * A physical index alone is not authority.  The lease generation and the
  * complete semantic owner must both match the still-live WAIT row.
  */
class STQMemoryClassifyToken(
    val stqEntries: Int,
    val robEntries: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  val lease = new STQPhysicalLease(stqEntries, leaseGenerationWidth)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, log2Ceil(robEntries),
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
  val logicalBeat = UInt(1.W)
  val memoryClass = STQMemoryClass()
}

class STQMemoryAttributeOwnerIO(
    val stqEntries: Int,
    val robEntries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  val classify = Flipped(Decoupled(new STQMemoryClassifyToken(
    stqEntries, robEntries, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val rows = Input(Vec(stqEntries, new STQEntryBankRow(
    robEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val recoveryActive = Input(Bool())

  val attributes = Output(Vec(stqEntries, new STQMemoryAttribute))
  val accepted = Output(Bool())
  val missing = Output(Bool())
  val multiple = Output(Bool())
  val duplicate = Output(Bool())
  val conflict = Output(Bool())
  val malformed = Output(Bool())
}

/** Canonical memory-class sidecar owner.
  *
  * The sidecar deliberately stores no address policy.  A future translation
  * or PMA adapter supplies the typed result.  Stored state is reachable only
  * while its lease generation and semantic owner still name the live row, so
  * physical-slot reuse cannot inherit an old classification.
  */
class STQMemoryAttributeOwner(
    val stqEntries: Int = 16,
    val robEntries: Int = 64,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Module {
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "memory attributes require a power-of-two physical STQ")
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "memory attributes require a power-of-two ROB identity space")

  val io = IO(new STQMemoryAttributeOwnerIO(
    stqEntries, robEntries, addrWidth, dataWidth, peIdWidth, stidWidth,
    tidWidth, sizeWidth, simtLaneWidth, mapQDepth, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth))

  val storedValid = RegInit(VecInit(Seq.fill(stqEntries)(false.B)))
  val storedLeaseGeneration = Reg(Vec(stqEntries,
    UInt(leaseGenerationWidth.W)))
  val storedOwner = Reg(Vec(stqEntries, new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, log2Ceil(robEntries),
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)))
  val storedClass = Reg(Vec(stqEntries, STQMemoryClass()))

  val token = io.classify.bits
  val selectedRow = io.rows(token.lease.index)
  val tokenShapeExact = token.lease.valid && token.exactOwner.valid &&
    token.exactOwner.nativeBidValid &&
    token.memoryClass =/= STQMemoryClass.Unknown
  val ownerMatch = Wire(Vec(stqEntries, Bool()))
  for (index <- 0 until stqEntries) {
    val row = io.rows(index)
    ownerMatch(index) := tokenShapeExact && row.valid &&
      row.exactOwner.valid &&
      row.exactOwner.asUInt === token.exactOwner.asUInt &&
      row.logicalBeat === token.logicalBeat

    val storedExact = storedValid(index) && row.valid &&
      (row.status === STQEntryStatus.Wait ||
        row.status === STQEntryStatus.Commit) &&
      row.leaseGeneration === storedLeaseGeneration(index) &&
      row.exactOwner.asUInt === storedOwner(index).asUInt
    io.attributes(index).valid := storedExact
    io.attributes(index).memoryClass := Mux(storedExact,
      storedClass(index), STQMemoryClass.Unknown)
  }

  val ownerCount = PopCount(ownerMatch)
  val selectedExact = tokenShapeExact && ownerCount === 1.U &&
    selectedRow.valid && selectedRow.status === STQEntryStatus.Wait &&
    selectedRow.addrReady &&
    selectedRow.logicalBeat === token.logicalBeat &&
    selectedRow.leaseGeneration === token.lease.generation &&
    selectedRow.exactOwner.asUInt === token.exactOwner.asUInt
  val existingExact = storedValid(token.lease.index) &&
    selectedRow.valid &&
    selectedRow.leaseGeneration === storedLeaseGeneration(token.lease.index) &&
    selectedRow.exactOwner.asUInt === storedOwner(token.lease.index).asUInt
  val sameExisting = existingExact &&
    storedClass(token.lease.index) === token.memoryClass

  io.classify.ready := selectedExact && !existingExact &&
    !io.recoveryActive
  io.accepted := io.classify.fire
  io.malformed := io.classify.valid && !tokenShapeExact
  io.missing := io.classify.valid && tokenShapeExact && ownerCount === 0.U
  io.multiple := io.classify.valid && tokenShapeExact && ownerCount > 1.U
  io.duplicate := io.classify.valid && selectedExact && sameExisting
  io.conflict := io.classify.valid && selectedExact && existingExact &&
    !sameExisting

  when(io.classify.fire) {
    storedValid(token.lease.index) := true.B
    storedLeaseGeneration(token.lease.index) := token.lease.generation
    storedOwner(token.lease.index) := token.exactOwner
    storedClass(token.lease.index) := token.memoryClass
  }
}
