package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder}

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotRowMutationRequestIO(
    val liqEntries: Int,
    val idEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val storeEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val targetCountWidth = log2Ceil(liqEntries + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val planValid = Input(Bool())
  val targetMask = Input(UInt(liqEntries.W))
  val setWaitStatus = Input(Bool())
  val keepRepickStatus = Input(Bool())
  val clearReturnState = Input(Bool())
  val lineWrite = Input(Bool())
  val waitStoreWrite = Input(Bool())
  val nextWaitStore = Input(Bool())
  val nextWaitStoreInfo = Input(new LoadStoreForwardWait(idEntries, physicalStoreEntries, pcWidth, lsidWidth))
  val nextWaitStoreRid = Input(new ROBID(idEntries))
  val nextLineData = Input(UInt((lineBytes * 8).W))
  val nextValidMask = Input(UInt(lineBytes.W))
  val nextDataComplete = Input(Bool())
  val nextScbReturned = Input(Bool())
  val nextStqReturned = Input(Bool())
  val nextStoreSourceReturned = Input(Bool())

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val candidateTargetMask = Output(UInt(liqEntries.W))
  val candidateTargetCount = Output(UInt(targetCountWidth.W))
  val candidateTargetIndex = Output(UInt(liqPtrWidth.W))
  val targetReady = Output(Bool())
  val requestValid = Output(Bool())
  val requestTargetMask = Output(UInt(liqEntries.W))
  val requestTargetIndex = Output(UInt(liqPtrWidth.W))
  val statusWrite = Output(Bool())
  val setWaitStatusOut = Output(Bool())
  val keepRepickStatusOut = Output(Bool())
  val returnStateWrite = Output(Bool())
  val clearReturnStateOut = Output(Bool())
  val lineWriteOut = Output(Bool())
  val waitStoreWriteOut = Output(Bool())
  val nextWaitStoreOut = Output(Bool())
  val nextWaitStoreInfoOut = Output(new LoadStoreForwardWait(idEntries, physicalStoreEntries, pcWidth, lsidWidth))
  val nextWaitStoreRidOut = Output(new ROBID(idEntries))
  val nextLineDataOut = Output(UInt((lineBytes * 8).W))
  val nextValidMaskOut = Output(UInt(lineBytes.W))
  val nextDataCompleteOut = Output(Bool())
  val nextScbReturnedOut = Output(Bool())
  val nextStqReturnedOut = Output(Bool())
  val nextStoreSourceReturnedOut = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoPlan = Output(Bool())
  val blockedByNoTarget = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
  val invalidMultiTarget = Output(Bool())
  val invalidWriteWithoutPlan = Output(Bool())
  val invalidWaitStoreWithoutWaitStatus = Output(Bool())
  val invalidReturnWithoutSplitSources = Output(Bool())
  val invalidConflictingStatusWrite = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRowMutationRequest(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val storeEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Module {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(physicalStoreEntries > 1 && (physicalStoreEntries & (physicalStoreEntries - 1)) == 0,
    "store entries must be a power of two greater than one")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "row mutation request currently carries 64-byte scalar line data")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  private val liqPtrWidth = log2Ceil(liqEntries)
  private val targetCountWidth = log2Ceil(liqEntries + 1)

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRowMutationRequestIO(
    liqEntries,
    idEntries,
    pcWidth,
    lineBytes,
    physicalStoreEntries,
    lsidWidth
  ))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, physicalStoreEntries, pcWidth, lsidWidth))

  val active = io.enable && !io.flush
  val mutationIntent =
    io.planValid ||
      io.targetMask.orR ||
      io.setWaitStatus ||
      io.keepRepickStatus ||
      io.clearReturnState ||
      io.lineWrite ||
      io.waitStoreWrite
  val candidateValid = active && io.planValid
  val targetCount = PopCount(io.targetMask)
  val oneTarget = targetCount === 1.U
  val targetReady = candidateValid && io.targetMask.orR && oneTarget
  val requestValid = targetReady && io.liveEnable

  io.active := active
  io.candidateValid := candidateValid
  io.candidateTargetMask := Mux(candidateValid, io.targetMask, 0.U(liqEntries.W))
  io.candidateTargetCount := Mux(candidateValid, targetCount, 0.U(targetCountWidth.W))
  io.candidateTargetIndex := Mux(targetReady, PriorityEncoder(io.targetMask), 0.U(liqPtrWidth.W))
  io.targetReady := targetReady
  io.requestValid := requestValid
  io.requestTargetMask := Mux(requestValid, io.targetMask, 0.U(liqEntries.W))
  io.requestTargetIndex := Mux(requestValid, PriorityEncoder(io.targetMask), 0.U(liqPtrWidth.W))
  io.statusWrite := requestValid && (io.setWaitStatus || io.keepRepickStatus)
  io.setWaitStatusOut := requestValid && io.setWaitStatus
  io.keepRepickStatusOut := requestValid && io.keepRepickStatus
  io.returnStateWrite := requestValid
  io.clearReturnStateOut := requestValid && io.clearReturnState
  io.lineWriteOut := requestValid && io.lineWrite
  io.waitStoreWriteOut := requestValid && io.waitStoreWrite
  io.nextWaitStoreOut := requestValid && io.nextWaitStore
  io.nextWaitStoreInfoOut := Mux(requestValid, io.nextWaitStoreInfo, zeroWait)
  io.nextWaitStoreRidOut := Mux(requestValid, io.nextWaitStoreRid, ROBID.disabled(idEntries))
  io.nextLineDataOut := Mux(requestValid, io.nextLineData, 0.U((lineBytes * 8).W))
  io.nextValidMaskOut := Mux(requestValid, io.nextValidMask, 0.U(lineBytes.W))
  io.nextDataCompleteOut := requestValid && io.nextDataComplete
  io.nextScbReturnedOut := requestValid && io.nextScbReturned
  io.nextStqReturnedOut := requestValid && io.nextStqReturned
  io.nextStoreSourceReturnedOut := requestValid && io.nextStoreSourceReturned
  io.blockedByDisabled := !io.enable && mutationIntent
  io.blockedByFlush := io.enable && io.flush && mutationIntent
  io.blockedByNoPlan := active && mutationIntent && !io.planValid
  io.blockedByNoTarget := candidateValid && !io.targetMask.orR
  io.blockedByLiveDisabled := targetReady && !io.liveEnable
  io.invalidMultiTarget := candidateValid && (targetCount > 1.U)
  io.invalidWriteWithoutPlan := active && !io.planValid && mutationIntent
  io.invalidWaitStoreWithoutWaitStatus := candidateValid && io.nextWaitStore && !io.setWaitStatus
  io.invalidReturnWithoutSplitSources :=
    candidateValid && io.nextStoreSourceReturned && !(io.nextScbReturned && io.nextStqReturned)
  io.invalidConflictingStatusWrite := candidateValid && io.setWaitStatus && io.keepRepickStatus
}
