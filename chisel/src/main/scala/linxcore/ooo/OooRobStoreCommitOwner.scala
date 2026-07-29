package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}

import linxcore.lsu.STQRobCommitToken

class OooRobStoreCommitOwnerIO(val p: OooParams = OooParams())
    extends Bundle {
  val commitPrepare = Flipped(Valid(new OooRobCommitBatch(p)))
  val commitStartReady = Output(Bool())
  val commitFire = Input(Bool())

  val storeCommit = Decoupled(new STQRobCommitToken(
    p.robGroupsPerStid, p.lsidWidth, p.peIdWidth, p.stidWidth,
    p.nativeBidWidth, p.ridGenerationWidth, p.brobGenerationWidth,
    p.robMemberIndexWidth, p.residentGenerationWidth))
  val used = Output(UInt(p.storeCommitBufferCountWidth.W))
  val free = Output(UInt(p.storeCommitBufferCountWidth.W))
  val preparedTokenCount = Output(UInt(
    p.countWidth(p.maxCommitStoreTokens).W))
  val commitRejected = Valid(new OooRobCommitBatch(p))
}

/** ROB-owned committed-store token buffer.
  *
  * The grouped ROB retains memory-order tails rather than physical STQ
  * pointers.  This owner reconstructs every logical store range, expands it
  * into one/two exact beats, and atomically captures the complete batch only
  * on the common architectural commit fire.  Tokens drain later through a
  * decoupled LSU ingress, so LSU backpressure cannot split ROB/BROB/rename
  * retirement and no store becomes non-flush before that retirement.
  */
class OooRobStoreCommitOwner(val p: OooParams = OooParams()) extends Module {
  require(p.maxMemoryRequestsPerInstruction == 2,
    "canonical scalar store commit expansion currently supports one/two beats")

  val io = IO(new OooRobStoreCommitOwnerIO(p))

  private val capacity = p.storeCommitBufferEntries
  private val ptrWidth = log2Ceil(capacity)
  private val tokenCountWidth = p.countWidth(p.maxCommitStoreTokens)

  private def tokenType: STQRobCommitToken = new STQRobCommitToken(
    p.robGroupsPerStid, p.lsidWidth, p.peIdWidth, p.stidWidth,
    p.nativeBidWidth, p.ridGenerationWidth, p.brobGenerationWidth,
    p.robMemberIndexWidth, p.residentGenerationWidth)

  private def zeroToken: STQRobCommitToken = {
    val token = Wire(tokenType)
    token := 0.U.asTypeOf(token)
    token
  }

  private def addPtr(pointer: UInt, amount: UInt): UInt =
    (pointer + amount)(ptrWidth - 1, 0)

  val rows = Reg(Vec(capacity, tokenType))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(p.storeCommitBufferCountWidth.W))

  io.storeCommit.valid := count.orR
  io.storeCommit.bits := rows(head)
  val dequeue = io.storeCommit.fire

  val batch = io.commitPrepare.bits
  val groupCount = batch.release.groupCount
  val commitStid = batch.release.firstGroup.stid
  val commitStidInRange = commitStid < p.stidCount.U
  val groupCountInRange = groupCount.orR &&
    groupCount <= p.retireGroupWidth.U

  val groupExact = Wire(Vec(p.retireGroupWidth, Bool()))
  val groupMemoryExact = Wire(Vec(p.retireGroupWidth, Bool()))
  val state = Wire(Vec(p.retireGroupWidth,
    Vec(p.decodedUopWidth + 1, new OooMemoryIdState(p))))
  val logicalStore = Wire(Vec(p.retireGroupWidth,
    Vec(p.decodedUopWidth, Bool())))
  val logicalRequestCount = Wire(Vec(p.retireGroupWidth,
    Vec(p.decodedUopWidth, UInt(p.memoryDemandWidth.W))))

  for (groupIndex <- 0 until p.retireGroupWidth) {
    val active = groupIndex.U < groupCount
    val group = batch.groups(groupIndex)
    val slotSum = batch.release.firstGroup.ridSlot +& groupIndex.U
    val wraps = slotSum >= p.robGroupsPerStid.U
    val completedMask =
      ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) <<
        group.physicalMemberCount) - 1.U)(
        p.maxOrdinaryUopsPerGroup - 1, 0)
    val groupIdentityExact = group.valid === active && (!active || (
      group.key.valid &&
      group.key.peId === batch.release.firstGroup.peId &&
      group.key.stid === commitStid &&
      group.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
      group.key.ridGeneration ===
        batch.release.firstGroup.ridGeneration + wraps.asUInt &&
      group.brob.valid && group.brob.bid.valid &&
      group.physicalMemberCount.orR &&
      group.completedMembers === completedMask))

    state(groupIndex)(0) := group.memoryBefore
    val logicalExact = Wire(Vec(p.decodedUopWidth, Bool()))
    for (uopIndex <- 0 until p.decodedUopWidth) {
      val logical = active && group.logicalUopMask(uopIndex)
      val before = state(groupIndex)(uopIndex)
      val recordedAfter = group.logicalMemoryAfter(uopIndex)
      state(groupIndex)(uopIndex + 1) :=
        Mux(logical, recordedAfter, before)

      val lsidDelta = recordedAfter.lsid - before.lsid
      val loadDelta = recordedAfter.loadId - before.loadId
      val storeDelta = recordedAfter.storeId - before.storeId
      val typedDeltaExact = lsidDelta === loadDelta + storeDelta &&
        !(loadDelta.orR && storeDelta.orR) &&
        loadDelta <= p.maxMemoryRequestsPerInstruction.U &&
        storeDelta <= p.maxMemoryRequestsPerInstruction.U
      val memberEnd = group.logicalMemberBase(uopIndex) +&
        group.logicalMemberCount(uopIndex)
      val memberExact = group.logicalMemberCount(uopIndex).orR &&
        memberEnd <= group.physicalMemberCount
      val inactiveMetadataZero =
        group.logicalMemberBase(uopIndex) === 0.U &&
          group.logicalMemberCount(uopIndex) === 0.U &&
          recordedAfter.asUInt === 0.U

      logicalExact(uopIndex) := Mux(logical,
        memberExact && typedDeltaExact,
        inactiveMetadataZero)
      logicalStore(groupIndex)(uopIndex) := logical &&
        group.memoryOrderValid && storeDelta.orR && typedDeltaExact
      logicalRequestCount(groupIndex)(uopIndex) := storeDelta
    }

    val canonicalMemoryExact = group.memoryOrderValid &&
      state(groupIndex)(p.decodedUopWidth).asUInt ===
        group.memoryAfter.asUInt && logicalExact.asUInt.andR
    val legacyNoMemory = !group.memoryOrderValid &&
      group.memoryBefore.asUInt === 0.U && group.memoryAfter.asUInt === 0.U &&
      group.logicalMemoryAfter.asUInt === 0.U
    val priorGroupChainExact = if (groupIndex == 0) true.B else
      !active || group.memoryBefore.asUInt ===
        batch.groups(groupIndex - 1).memoryAfter.asUInt
    groupMemoryExact(groupIndex) := (!active ||
      canonicalMemoryExact || legacyNoMemory) && priorGroupChainExact
    groupExact(groupIndex) := groupIdentityExact &&
      groupMemoryExact(groupIndex)
  }

  val rawValid = Wire(Vec(p.maxCommitStoreTokens, Bool()))
  val rawToken = Wire(Vec(p.maxCommitStoreTokens, tokenType))
  for (groupIndex <- 0 until p.retireGroupWidth) {
    for (uopIndex <- 0 until p.decodedUopWidth) {
      for (beat <- 0 until p.maxMemoryRequestsPerInstruction) {
        val rawIndex =
          (groupIndex * p.decodedUopWidth + uopIndex) *
            p.maxMemoryRequestsPerInstruction + beat
        val group = batch.groups(groupIndex)
        val token = rawToken(rawIndex)
        token := zeroToken
        token.logicalFirstLsid := state(groupIndex)(uopIndex).lsid
        token.logicalFirstStoreId := state(groupIndex)(uopIndex).storeId
        token.logicalRequestCount :=
          logicalRequestCount(groupIndex)(uopIndex)
        token.logicalBeat := beat.U
        token.exactOwner.valid := group.key.valid
        token.exactOwner.peId := group.key.peId
        token.exactOwner.stid := group.key.stid
        token.exactOwner.nativeBidValid := group.brob.bid.valid
        token.exactOwner.nativeBid := group.brob.bid.value
        token.exactOwner.brobGeneration := group.brob.generation
        token.exactOwner.ridSlot := group.key.ridSlot
        token.exactOwner.ridGeneration := group.key.ridGeneration
        token.exactOwner.memberIndex := group.logicalMemberBase(uopIndex)
        token.exactOwner.residentGeneration := group.residentGeneration
        rawValid(rawIndex) := logicalStore(groupIndex)(uopIndex) &&
          beat.U < logicalRequestCount(groupIndex)(uopIndex)
      }
    }
  }

  val preparedTokenCount = PopCount(rawValid)
  val freeWithDequeue = capacity.U - count + dequeue.asUInt
  val incomingExact = commitStidInRange && groupCountInRange &&
    batch.release.firstGroup.valid && groupExact.asUInt.andR
  val hasCredit = preparedTokenCount <= freeWithDequeue
  io.commitStartReady := incomingExact && hasCredit
  io.preparedTokenCount := preparedTokenCount
  io.commitRejected.valid := io.commitPrepare.valid &&
    (!incomingExact || !hasCredit)
  io.commitRejected.bits := batch

  val enqueue = io.commitFire
  when(enqueue) {
    assert(io.commitPrepare.valid && io.commitStartReady,
      "store commit capture requires one exact credit-qualified ROB batch")
    for (rawIndex <- 0 until p.maxCommitStoreTokens) {
      val rank = if (rawIndex == 0) 0.U
        else PopCount(rawValid.take(rawIndex))
      when(rawValid(rawIndex)) {
        rows(addPtr(tail, rank)) := rawToken(rawIndex)
      }
    }
    tail := addPtr(tail, preparedTokenCount)
  }

  when(dequeue) {
    head := addPtr(head, 1.U)
  }
  when(enqueue && !dequeue) {
    count := count + preparedTokenCount
  }.elsewhen(!enqueue && dequeue) {
    count := count - 1.U
  }.elsewhen(enqueue && dequeue) {
    count := count + preparedTokenCount - 1.U
  }

  io.used := count
  io.free := capacity.U - count

  when(count.orR) {
    assert(io.storeCommit.bits.exactOwner.valid &&
      io.storeCommit.bits.exactOwner.nativeBidValid &&
      io.storeCommit.bits.logicalRequestCount.orR &&
      io.storeCommit.bits.logicalBeat <
        io.storeCommit.bits.logicalRequestCount,
      "resident store commit tokens must retain exact logical ownership")
  }
}
