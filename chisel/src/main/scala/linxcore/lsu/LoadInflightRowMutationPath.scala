package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadInflightRowMutationPathIO(
    val liqEntries: Int,
    val idEntries: Int,
    val sourceStoreEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val returnPipeCount: Int)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val nativeStoreIndexWidth = log2Ceil(storeEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestValid = Input(Bool())
  val requestTargetMask = Input(UInt(liqEntries.W))
  val requestTargetIndex = Input(UInt(liqPtrWidth.W))
  val row = Input(new LoadInflightRow(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount
  ))
  val setWaitStatus = Input(Bool())
  val keepRepickStatus = Input(Bool())
  val clearReturnState = Input(Bool())
  val lineWrite = Input(Bool())
  val waitStoreWrite = Input(Bool())
  val nextWaitStore = Input(Bool())
  val nextWaitStoreInfo = Input(new LoadStoreForwardWait(idEntries, sourceStoreEntries, pcWidth))
  val nextLineData = Input(UInt((lineBytes * 8).W))
  val nextValidMask = Input(UInt(lineBytes.W))
  val nextDataComplete = Input(Bool())
  val nextScbReturned = Input(Bool())
  val nextStqReturned = Input(Bool())
  val nextStoreSourceReturned = Input(Bool())
  val allowWaitTarget = Input(Bool())
  val requireScbReturned = Input(Bool())
  val e4UpdateConflict = Input(Bool())
  val clearResolvedConflict = Input(Bool())
  val replayWakeConflict = Input(Bool())
  val refillConflict = Input(Bool())
  val launchConflict = Input(Bool())
  val allocationConflict = Input(Bool())

  val active = Output(Bool())
  val bridgeValid = Output(Bool())
  val requestTargetMaskOut = Output(UInt(liqEntries.W))
  val requestTargetIndexOut = Output(UInt(liqPtrWidth.W))
  val nativeStoreIndexOut = Output(UInt(nativeStoreIndexWidth.W))
  val sourceStoreIndexFits = Output(Bool())
  val targetRowValid = Output(Bool())
  val targetRowRepick = Output(Bool())
  val targetScbReturned = Output(Bool())
  val targetEvidenceValid = Output(Bool())
  val writeConflict = Output(Bool())
  val writeEnable = Output(Bool())
  val applyValid = Output(Bool())
  val nextRow = Output(new LoadInflightRow(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount
  ))

  val blockedByBridge = Output(Bool())
  val blockedByControl = Output(Bool())
  val blockedByApply = Output(Bool())
  val bridgeBlockedByDisabled = Output(Bool())
  val bridgeBlockedByFlush = Output(Bool())
  val bridgeBlockedByNoRequest = Output(Bool())
  val bridgeInvalidStoreIndexOutOfRange = Output(Bool())
  val bridgeInvalidConflictingStatusWrite = Output(Bool())
  val bridgeInvalidWaitStoreWithoutWaitStatus = Output(Bool())
  val bridgeInvalidReturnWithoutSplitSources = Output(Bool())
  val controlBlockedByInvalidRow = Output(Bool())
  val controlBlockedByNotRepick = Output(Bool())
  val controlBlockedByScbNotReturned = Output(Bool())
  val controlBlockedByE4UpdateConflict = Output(Bool())
  val controlBlockedByClearResolvedConflict = Output(Bool())
  val controlBlockedByReplayWakeConflict = Output(Bool())
  val controlBlockedByRefillConflict = Output(Bool())
  val controlBlockedByLaunchConflict = Output(Bool())
  val controlBlockedByAllocationConflict = Output(Bool())
  val applyBlockedByInvalidRow = Output(Bool())
  val applyBlockedByNotRepick = Output(Bool())
  val applyInvalidConflictingStatusWrite = Output(Bool())
  val applyInvalidWaitStoreWithoutWaitStatus = Output(Bool())
  val applyInvalidReturnWithoutSplitSources = Output(Bool())
}

class LoadInflightRowMutationPath(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val sourceStoreEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val returnPipeCount: Int = 1)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(sourceStoreEntries > 1, "sourceStoreEntries must be greater than one")
  require((sourceStoreEntries & (sourceStoreEntries - 1)) == 0, "sourceStoreEntries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "LoadInflightRowMutationPath currently models 64-byte scalar cachelines")

  val io = IO(new LoadInflightRowMutationPathIO(
    liqEntries,
    idEntries,
    sourceStoreEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount
  ))

  val bridge = Module(new LoadInflightRowMutationRequestBridge(
    liqEntries = liqEntries,
    idEntries = idEntries,
    sourceStoreEntries = sourceStoreEntries,
    storeEntries = storeEntries,
    pcWidth = pcWidth,
    lineBytes = lineBytes
  ))
  bridge.io.enable := io.enable
  bridge.io.flush := io.flush
  bridge.io.requestValid := io.requestValid
  bridge.io.requestTargetMask := io.requestTargetMask
  bridge.io.requestTargetIndex := io.requestTargetIndex
  bridge.io.setWaitStatus := io.setWaitStatus
  bridge.io.keepRepickStatus := io.keepRepickStatus
  bridge.io.clearReturnState := io.clearReturnState
  bridge.io.lineWrite := io.lineWrite
  bridge.io.waitStoreWrite := io.waitStoreWrite
  bridge.io.nextWaitStore := io.nextWaitStore
  bridge.io.nextWaitStoreInfo := io.nextWaitStoreInfo
  bridge.io.nextLineData := io.nextLineData
  bridge.io.nextValidMask := io.nextValidMask
  bridge.io.nextDataComplete := io.nextDataComplete
  bridge.io.nextScbReturned := io.nextScbReturned
  bridge.io.nextStqReturned := io.nextStqReturned
  bridge.io.nextStoreSourceReturned := io.nextStoreSourceReturned

  val control = Module(new LoadInflightRowMutationWriteControl)
  control.io.enable := io.enable
  control.io.flush := io.flush
  control.io.requestValid := bridge.io.bridgeValid
  control.io.targetRowValid := io.row.valid
  control.io.targetRowRepick := io.row.status === LoadInflightStatus.Repick
  control.io.targetRowWait := io.row.status === LoadInflightStatus.Wait
  control.io.targetScbReturned := io.row.scbReturned
  control.io.allowWaitTarget := io.allowWaitTarget
  control.io.requireScbReturned := io.requireScbReturned
  control.io.e4UpdateConflict := io.e4UpdateConflict
  control.io.clearResolvedConflict := io.clearResolvedConflict
  control.io.replayWakeConflict := io.replayWakeConflict
  control.io.refillConflict := io.refillConflict
  control.io.launchConflict := io.launchConflict
  control.io.allocationConflict := io.allocationConflict

  val apply = Module(new LoadInflightRowMutationApply(
    liqEntries = liqEntries,
    idEntries = idEntries,
    storeEntries = storeEntries,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth,
    archRegWidth = archRegWidth,
    physRegWidth = physRegWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    returnPipeCount = returnPipeCount
  ))
  apply.io.enable := io.enable
  apply.io.flush := io.flush
  apply.io.requestValid := control.io.writeEnable
  apply.io.row := io.row
  apply.io.setWaitStatus := bridge.io.setWaitStatusOut
  apply.io.keepRepickStatus := bridge.io.keepRepickStatusOut
  apply.io.clearReturnState := bridge.io.clearReturnStateOut
  apply.io.lineWrite := bridge.io.lineWriteOut
  apply.io.waitStoreWrite := bridge.io.waitStoreWriteOut
  apply.io.nextWaitStore := bridge.io.nextWaitStoreOut
  apply.io.nextWaitStoreInfo := bridge.io.nativeNextWaitStoreInfoOut
  apply.io.nextLineData := bridge.io.nextLineDataOut
  apply.io.nextValidMask := bridge.io.nextValidMaskOut
  apply.io.nextDataComplete := bridge.io.nextDataCompleteOut
  apply.io.nextScbReturned := bridge.io.nextScbReturnedOut
  apply.io.nextStqReturned := bridge.io.nextStqReturnedOut
  apply.io.nextStoreSourceReturned := bridge.io.nextStoreSourceReturnedOut
  apply.io.allowWaitTarget := io.allowWaitTarget

  io.active := bridge.io.active
  io.bridgeValid := bridge.io.bridgeValid
  io.requestTargetMaskOut := bridge.io.requestTargetMaskOut
  io.requestTargetIndexOut := bridge.io.requestTargetIndexOut
  io.nativeStoreIndexOut := bridge.io.nativeStoreIndexOut
  io.sourceStoreIndexFits := bridge.io.sourceStoreIndexFits
  io.targetRowValid := io.row.valid
  io.targetRowRepick := io.row.status === LoadInflightStatus.Repick
  io.targetScbReturned := io.row.scbReturned
  io.targetEvidenceValid := control.io.targetEvidenceValid
  io.writeConflict := control.io.writeConflict
  io.writeEnable := control.io.writeEnable
  io.applyValid := apply.io.applyValid
  io.nextRow := apply.io.nextRow

  io.blockedByBridge := io.requestValid && !bridge.io.bridgeValid
  io.blockedByControl := bridge.io.bridgeValid && !control.io.writeEnable
  io.blockedByApply := control.io.writeEnable && !apply.io.applyValid
  io.bridgeBlockedByDisabled := bridge.io.blockedByDisabled
  io.bridgeBlockedByFlush := bridge.io.blockedByFlush
  io.bridgeBlockedByNoRequest := bridge.io.blockedByNoRequest
  io.bridgeInvalidStoreIndexOutOfRange := bridge.io.invalidStoreIndexOutOfRange
  io.bridgeInvalidConflictingStatusWrite := bridge.io.invalidConflictingStatusWrite
  io.bridgeInvalidWaitStoreWithoutWaitStatus := bridge.io.invalidWaitStoreWithoutWaitStatus
  io.bridgeInvalidReturnWithoutSplitSources := bridge.io.invalidReturnWithoutSplitSources
  io.controlBlockedByInvalidRow := control.io.blockedByInvalidRow
  io.controlBlockedByNotRepick := control.io.blockedByNotRepick
  io.controlBlockedByScbNotReturned := control.io.blockedByScbNotReturned
  io.controlBlockedByE4UpdateConflict := control.io.blockedByE4UpdateConflict
  io.controlBlockedByClearResolvedConflict := control.io.blockedByClearResolvedConflict
  io.controlBlockedByReplayWakeConflict := control.io.blockedByReplayWakeConflict
  io.controlBlockedByRefillConflict := control.io.blockedByRefillConflict
  io.controlBlockedByLaunchConflict := control.io.blockedByLaunchConflict
  io.controlBlockedByAllocationConflict := control.io.blockedByAllocationConflict
  io.applyBlockedByInvalidRow := apply.io.blockedByInvalidRow
  io.applyBlockedByNotRepick := apply.io.blockedByNotRepick
  io.applyInvalidConflictingStatusWrite := apply.io.invalidConflictingStatusWrite
  io.applyInvalidWaitStoreWithoutWaitStatus := apply.io.invalidWaitStoreWithoutWaitStatus
  io.applyInvalidReturnWithoutSplitSources := apply.io.invalidReturnWithoutSplitSources
}
