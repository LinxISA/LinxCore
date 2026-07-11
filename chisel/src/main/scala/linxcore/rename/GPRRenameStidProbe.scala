package linxcore.rename

import chisel3._

import linxcore.backend.GPRReservationTracker
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class GPRRenameStidProbeIO extends Bundle {
  val renameValid = Input(Bool())
  val renameStid = Input(UInt(2.W))
  val renameArch = Input(UInt(5.W))
  val renameFullBid = Input(UInt(16.W))
  val renameRid = Input(UInt(3.W))
  val renameReady = Output(Bool())
  val renameAccepted = Output(Bool())
  val renamePhys = Output(UInt(6.W))
  val renameOldPhys = Output(UInt(6.W))

  val commitValid = Input(Bool())
  val commitStid = Input(UInt(2.W))
  val commitFullBid = Input(UInt(16.W))
  val commitAccepted = Output(Bool())

  val flushValid = Input(Bool())
  val flushStid = Input(UInt(2.W))
  val flushFullBid = Input(UInt(16.W))
  val flushApplied = Output(Bool())

  val queryStid = Input(UInt(2.W))
  val queryArch = Input(UInt(5.W))
  val queryStidInRange = Output(Bool())
  val querySmap = Output(UInt(6.W))
  val queryCmap = Output(UInt(6.W))
  val queryMapQCount = Output(UInt(4.W))
  val freeCount = Output(UInt(7.W))
  val stateError = Output(Bool())

  val reservationFlush = Input(Bool())
  val reservationPushValid = Input(Bool())
  val reservationPushStid = Input(UInt(2.W))
  val reservationPopValid = Input(Bool())
  val reservationPopStid = Input(UInt(2.W))
  val reservationSelectedValid = Input(Bool())
  val reservationSelectedStid = Input(UInt(2.W))
  val reservationSelectedNeedsGpr = Input(Bool())
  val reservationFreePhys = Input(UInt(7.W))
  val reservationSelectedMapQFree = Input(UInt(4.W))
  val reservationReady = Output(Bool())
  val reservationPhysCount = Output(UInt(4.W))
  val reservationMapQCount = Output(UInt(4.W))
  val reservationStateError = Output(Bool())
}

/** Generated-RTL proof for shared-free-list, per-STID scalar GPR rename state. */
class GPRRenameStidProbe extends Module {
  private val entries = 8
  private val bidWidth = 16
  private val stidWidth = 2

  val io = IO(new GPRRenameStidProbeIO)
  val rename = Module(new GPRRenameCheckpoint(
    entries = entries,
    archRegs = 24,
    physRegs = 64,
    mapQDepth = 8,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    stidCount = 2,
    peIdWidth = 1,
    tidWidth = 2,
    orderWidth = 16
  ))

  private def robId(value: UInt): ROBID = {
    val id = Wire(new ROBID(entries))
    id.valid := true.B
    id.wrap := false.B
    id.value := value
    id
  }

  val cleanup = Wire(new RecoveryCleanupIntent(
    entries = entries,
    bidWidth = bidWidth,
    peIdWidth = 1,
    stidWidth = stidWidth,
    tidWidth = 2
  ))
  cleanup := 0.U.asTypeOf(cleanup)
  cleanup.valid := io.flushValid
  cleanup.flush.req.valid := io.flushValid
  cleanup.flush.req.stid := io.flushStid
  cleanup.flush.req.bid := robId(io.flushFullBid(2, 0))
  cleanup.flush.baseOnBid := true.B
  cleanup.blockFlushValid := io.flushValid
  cleanup.blockFlushBid := io.flushFullBid
  cleanup.renameFlushValid := io.flushValid

  rename.io.srcArchTags := VecInit(Seq.fill(3)(io.queryArch))
  rename.io.renameValid := io.renameValid
  rename.io.renameArchTag := io.renameArch
  rename.io.renameBid := robId(io.renameFullBid(2, 0))
  rename.io.renameBlockBid := io.renameFullBid
  rename.io.renameStid := io.renameStid
  rename.io.renameRid := robId(io.renameRid)
  rename.io.renameGid := robId(0.U)
  rename.io.renameOrder := io.renameRid
  rename.io.checkpointValid := false.B
  rename.io.checkpointBid := robId(0.U)
  rename.io.checkpointStid := 0.U
  rename.io.postRenameCheckpointValid := false.B
  rename.io.postRenameCheckpointBid := robId(0.U)
  rename.io.postRenameCheckpointStid := 0.U
  rename.io.commitValid := io.commitValid
  rename.io.commitBid := robId(io.commitFullBid(2, 0))
  rename.io.commitBlockBid := io.commitFullBid
  rename.io.commitStid := io.commitStid
  rename.io.queryStid := io.queryStid
  rename.io.cleanup := cleanup
  rename.io.cleanupOrderValid := false.B
  rename.io.cleanupOrder := 0.U

  io.renameReady := rename.io.renameReady
  io.renameAccepted := rename.io.renameAccepted
  io.renamePhys := rename.io.renamePhysTag
  io.renameOldPhys := rename.io.renameOldPhysTag
  io.commitAccepted := rename.io.commitAccepted
  io.flushApplied := rename.io.cleanupFlushApplied
  io.queryStidInRange := rename.io.queryStidInRange
  io.querySmap := rename.io.smap(io.queryArch)
  io.queryCmap := rename.io.cmap(io.queryArch)
  io.queryMapQCount := rename.io.mapQValidCount
  io.freeCount := rename.io.freeCount
  io.stateError := rename.io.stateError

  val reservations = Module(new GPRReservationTracker(
    queueDepth = 8,
    physRegs = 64,
    mapQDepth = 8,
    stidWidth = stidWidth,
    stidCount = 2
  ))
  reservations.io.flush := io.reservationFlush
  reservations.io.pushValid := io.reservationPushValid
  reservations.io.pushStid := io.reservationPushStid
  reservations.io.popValid := io.reservationPopValid
  reservations.io.popStid := io.reservationPopStid
  reservations.io.selectedValid := io.reservationSelectedValid
  reservations.io.selectedStid := io.reservationSelectedStid
  reservations.io.selectedNeedsGpr := io.reservationSelectedNeedsGpr
  reservations.io.freePhysCount := io.reservationFreePhys
  reservations.io.selectedMapQFreeCount := io.reservationSelectedMapQFree
  io.reservationReady := reservations.io.ready
  io.reservationPhysCount := reservations.io.physReservationCount
  io.reservationMapQCount := reservations.io.selectedMapQReservationCount
  io.reservationStateError := reservations.io.stateError
}

object EmitGPRRenameStidProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new GPRRenameStidProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/gpr-rename-stid-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
