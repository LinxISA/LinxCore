package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class ScalarLoadCompletionROBBridgeIO(val entries: Int) extends Bundle {
  private val ptrWidth = log2Ceil(entries)

  val externalCompleteValid = Input(Bool())
  val externalCompleteRobValue = Input(UInt(ptrWidth.W))

  val loadLookupValid = Input(Bool())
  val loadLookupRid = Input(new ROBID(entries))
  val robLookupRowValid = Input(Bool())
  val robLookupRowNeedFlush = Input(Bool())
  val robLookupValid = Output(Bool())
  val robLookupRid = Output(new ROBID(entries))
  val loadRobRowValid = Output(Bool())
  val loadRobRowNeedFlush = Output(Bool())

  val loadCompletionCandidateValid = Input(Bool())
  val loadCompletionRid = Input(new ROBID(entries))
  val loadResolveEnable = Input(Bool())
  val robExactCompleteReady = Input(Bool())
  val loadResolveReady = Output(Bool())
  val loadResolveFire = Input(Bool())

  val robCompleteValid = Output(Bool())
  val robCompleteRobValue = Output(UInt(ptrWidth.W))
  val robExactCompleteValid = Output(Bool())
  val robExactCompleteRid = Output(new ROBID(entries))
  val scalarLoadSelected = Output(Bool())
  val collision = Output(Bool())
  val sameRowCollision = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarLoadCompletionROBBridge(val entries: Int = 16) extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "entries must be a power of two greater than one")

  val io = IO(new ScalarLoadCompletionROBBridgeIO(entries))

  val loadResolveReady =
    io.loadResolveEnable && !io.externalCompleteValid && io.robExactCompleteReady
  val scalarLoadSelected = io.loadResolveFire && loadResolveReady
  val sameRowCollision =
    io.externalCompleteValid && io.loadCompletionCandidateValid &&
      io.loadCompletionRid.valid &&
      (io.externalCompleteRobValue === io.loadCompletionRid.value)
  val protocolError =
    (io.loadResolveFire && !loadResolveReady) ||
      (io.loadResolveFire && !io.loadCompletionRid.valid) ||
      sameRowCollision

  io.robLookupValid := io.loadLookupValid
  io.robLookupRid := io.loadLookupRid
  io.loadRobRowValid := io.robLookupRowValid
  io.loadRobRowNeedFlush := io.robLookupRowNeedFlush
  io.loadResolveReady := loadResolveReady
  io.robCompleteValid := io.externalCompleteValid
  io.robCompleteRobValue := io.externalCompleteRobValue
  io.robExactCompleteValid := scalarLoadSelected
  io.robExactCompleteRid := io.loadCompletionRid
  io.scalarLoadSelected := scalarLoadSelected
  io.collision := io.externalCompleteValid && io.loadCompletionCandidateValid
  io.sameRowCollision := sameRowCollision
  io.protocolError := protocolError

  assert(!protocolError,
    "scalar load completion must have unique source ownership, accepted sinks, and a valid RID")
}
