package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, log2Ceil}

/** One ROB-authorized physical beat of a logical store.
  *
  * The ROB owns semantic retirement and therefore supplies the complete
  * generation-qualified logical identity.  The physical STQ index and lease
  * generation are deliberately absent: the LSU ingress must rediscover the
  * unique still-live row rather than trusting a stale storage pointer.
  */
class STQRobCommitToken(
    val robEntries: Int,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8)
    extends Bundle {
  val logicalFirstLsid = UInt(lsidWidth.W)
  val logicalFirstStoreId = UInt(lsidWidth.W)
  val logicalRequestCount = UInt(2.W)
  val logicalBeat = UInt(1.W)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, log2Ceil(robEntries),
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
}

class STQRobCommitIngressIO(
    val entries: Int,
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
  private val ptrWidth = log2Ceil(entries)

  val commit = Flipped(Decoupled(new STQRobCommitToken(
    robEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)))
  val rows = Input(Vec(entries, new STQEntryBankRow(
    robEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val memoryAttributes = Input(Vec(entries, new STQMemoryAttribute))
  val recoveryActive = Input(Bool())
  val drainEnqueueReady = Input(Bool())

  val markValid = Output(Bool())
  val markIndex = Output(UInt(ptrWidth.W))
  val accepted = Output(Bool())
  val missing = Output(Bool())
  val multiple = Output(Bool())
  val notReady = Output(Bool())
  val classificationMissing = Output(Bool())
  val classificationFault = Output(Bool())
}

/** Exact ROB-token to physical-STQ ingress.
  *
  * A token is accepted only when exactly one live WAIT row matches every
  * semantic field, that row has converged STA+STD state, and the CommitQ can
  * accept the same row in the same cycle.  Missing, duplicate, stale, or
  * half-filled rows fail closed while peer LSU activity remains independent.
  */
class STQRobCommitIngress(
    val entries: Int = 16,
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
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "STQ ROB-commit ingress requires a power-of-two physical STQ")
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "STQ ROB-commit ingress requires a power-of-two ROB identity space")

  val io = IO(new STQRobCommitIngressIO(
    entries, robEntries, addrWidth, dataWidth, peIdWidth, stidWidth,
    tidWidth, sizeWidth, simtLaneWidth, mapQDepth, lsidWidth,
    nativeBidWidth, ridGenerationWidth, brobGenerationWidth,
    memberIndexWidth, residentGenerationWidth, leaseGenerationWidth))

  val token = io.commit.bits
  val tokenShapeExact = token.exactOwner.valid &&
    token.exactOwner.nativeBidValid &&
    (token.logicalRequestCount === 1.U ||
      token.logicalRequestCount === 2.U) &&
    token.logicalBeat < token.logicalRequestCount
  val fullLsid = token.logicalFirstLsid + token.logicalBeat
  val fullStoreId = token.logicalFirstStoreId + token.logicalBeat

  val identityMatch = Wire(Vec(entries, Bool()))
  val physicalReadyMatch = Wire(Vec(entries, Bool()))
  val readyMatch = Wire(Vec(entries, Bool()))
  for (index <- 0 until entries) {
    val row = io.rows(index)
    val attribute = io.memoryAttributes(index)
    identityMatch(index) := tokenShapeExact && row.valid &&
      row.exactOwner.valid &&
      row.exactOwner.asUInt === token.exactOwner.asUInt &&
      row.peId === token.exactOwner.peId &&
      row.stid === token.exactOwner.stid && row.bid.valid &&
      row.logicalStoreValid &&
      row.logicalFirstLsid === token.logicalFirstLsid &&
      row.logicalFirstStoreId === token.logicalFirstStoreId &&
      row.logicalRequestCount === token.logicalRequestCount &&
      row.logicalBeat === token.logicalBeat &&
      row.lsIdFull === fullLsid && row.storeIdFullValid &&
      row.storeIdFull === fullStoreId
    physicalReadyMatch(index) := identityMatch(index) &&
      row.status === STQEntryStatus.Wait &&
      row.storeType === STQStoreType.All && row.addrReady && row.dataReady
    readyMatch(index) := physicalReadyMatch(index) && attribute.valid &&
      attribute.memoryClass =/= STQMemoryClass.Unknown &&
      attribute.memoryClass =/= STQMemoryClass.Fault
  }

  val identityCount = PopCount(identityMatch)
  val physicalReadyCount = PopCount(physicalReadyMatch)
  val readyCount = PopCount(readyMatch)
  val uniqueReady = identityCount === 1.U && readyCount === 1.U
  val selectedIndex = PriorityEncoder(readyMatch.asUInt)

  io.markIndex := selectedIndex
  io.markValid := io.commit.valid && uniqueReady &&
    !io.recoveryActive && io.drainEnqueueReady
  io.commit.ready := uniqueReady && !io.recoveryActive &&
    io.drainEnqueueReady
  io.accepted := io.commit.fire
  io.missing := io.commit.valid && identityCount === 0.U
  io.multiple := io.commit.valid && identityCount > 1.U
  io.notReady := io.commit.valid && identityCount === 1.U &&
    (readyCount =/= 1.U || io.recoveryActive || !io.drainEnqueueReady)
  io.classificationMissing := io.commit.valid && identityCount === 1.U &&
    physicalReadyCount === 1.U &&
    (0 until entries).map { index =>
      physicalReadyMatch(index) &&
        (!io.memoryAttributes(index).valid ||
          io.memoryAttributes(index).memoryClass === STQMemoryClass.Unknown)
    }.reduce(_ || _)
  io.classificationFault := io.commit.valid && identityCount === 1.U &&
    physicalReadyCount === 1.U &&
    (0 until entries).map { index =>
      physicalReadyMatch(index) && io.memoryAttributes(index).valid &&
        io.memoryAttributes(index).memoryClass === STQMemoryClass.Fault
    }.reduce(_ || _)

  when(io.commit.fire) {
    assert(io.markValid,
      "an accepted ROB store token must mark its unique exact STQ row")
  }
}
