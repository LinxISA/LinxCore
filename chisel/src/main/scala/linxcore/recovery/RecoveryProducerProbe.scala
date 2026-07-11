package linxcore.recovery

import chisel3._

import linxcore.rob.ROBID

class RecoveryProducerProbeIO extends Bundle {
  val bccValid = Input(Bool())
  val bccBlockBid = Input(UInt(16.W))
  val bccStid = Input(UInt(1.W))
  val bccExecEngine = Input(ExecEngineType())
  val slowValid = Input(Bool())
  val slowBlockBid = Input(UInt(16.W))
  val slowStid = Input(UInt(1.W))
  val slowFetchTpc = Input(UInt(64.W))
  val peValid = Input(Bool())
  val peBlockBid = Input(UInt(16.W))
  val peStid = Input(UInt(1.W))
  val peId = Input(UInt(1.W))
  val peFetchTpc = Input(UInt(64.W))
  val stall = Input(Bool())
  val stallProgress = Input(Bool())
  val stallOldestValid = Input(Bool())
  val stallOldestComplete = Input(Bool())
  val stallCommitPointer = Input(UInt(16.W))
  val stallStid = Input(UInt(1.W))
  val intentReady = Input(Bool())

  val bccReady = Output(Bool())
  val bccAccepted = Output(Bool())
  val slowReady = Output(Bool())
  val slowAccepted = Output(Bool())
  val peReady = Output(Bool())
  val peAccepted = Output(Bool())
  val stallTriggerCaptured = Output(Bool())
  val stallBlockedByMissingIdentity = Output(Bool())
  val stallCount = Output(UInt(2.W))
  val stallResolvedIdentityValid = Output(Bool())
  val stallResolvedBlockBid = Output(UInt(16.W))
  val producerPendingMask = Output(UInt(4.W))
  val sourcePendingMask = Output(UInt(4.W))
  val sourceResolvedMask = Output(UInt(4.W))
  val consumedPayloadSourceMask = Output(UInt(4.W))
  val classGlobalFlushPendingMask = Output(UInt(2.W))
  val classGlobalReplayPendingMask = Output(UInt(2.W))
  val classPePendingMask = Output(UInt(4.W))
  val intentValid = Output(Bool())
  val intentType = Output(UInt(3.W))
  val intentBlockBid = Output(UInt(3.W))
  val intentBlockPointerValid = Output(Bool())
  val intentBlockPointer = Output(UInt(16.W))
  val intentStid = Output(UInt(1.W))
  val intentPeId = Output(UInt(1.W))
  val intentImmediate = Output(Bool())
  val intentExecEngine = Output(UInt(2.W))
  val intentBaseOnBid = Output(Bool())
  val intentFetchTpcValid = Output(Bool())
  val intentFetchTpc = Output(UInt(64.W))
  val pending = Output(Bool())
}

/** Generated-RTL proof surface for the canonical non-LSU producer families. */
class RecoveryProducerProbe extends Module {
  private val entries = 8
  private val stidCount = 2
  private val peCount = 2
  private val peIdWidth = 1
  private val stidWidth = 1
  private val tidWidth = 1

  val io = IO(new RecoveryProducerProbeIO)
  val producers = Module(new RecoveryNonLsuProducerBank(
    queueEntries = 2,
    stallThreshold = 3,
    entries = entries,
    bidWidth = 16,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val recovery = Module(new RecoveryFabric(
    sourceCount = 4,
    stidCount = stidCount,
    peCount = peCount,
    entries = entries,
    bidWidth = 16,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val stallIdentity = Module(new IexIqStallRecoveryIdentity(
    stidCount = stidCount,
    bidWidth = 16,
    stidWidth = stidWidth
  ))

  private def id(value: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := true.B
    out.wrap := false.B
    out.value := value
    out
  }

  producers.io.bccMiss.valid := io.bccValid
  producers.io.bccMiss.recoveryBlockBid := io.bccBlockBid
  producers.io.bccMiss.stid := io.bccStid
  producers.io.bccMiss.peId := 0.U
  producers.io.bccMiss.tid := 0.U
  producers.io.bccMiss.execEngine := io.bccExecEngine

  producers.io.iexSlow.valid := io.slowValid
  producers.io.iexSlow.recoveryBlockBid := io.slowBlockBid
  producers.io.iexSlow.gid := id(0.U)
  producers.io.iexSlow.rid := id(1.U)
  producers.io.iexSlow.lsId := id(1.U)
  producers.io.iexSlow.stid := io.slowStid
  producers.io.iexSlow.peId := 0.U
  producers.io.iexSlow.tid := 0.U
  producers.io.iexSlow.execEngine := ExecEngineType.Scalar
  producers.io.iexSlow.fetchTpcValid := true.B
  producers.io.iexSlow.fetchTpc := io.slowFetchTpc

  producers.io.peMismatch.valid := io.peValid
  producers.io.peMismatch.recoveryBlockBid := io.peBlockBid
  producers.io.peMismatch.gid := id(0.U)
  producers.io.peMismatch.rid := id(2.U)
  producers.io.peMismatch.lsId := id(2.U)
  producers.io.peMismatch.stid := io.peStid
  producers.io.peMismatch.peId := io.peId
  producers.io.peMismatch.tid := 0.U
  producers.io.peMismatch.execEngine := ExecEngineType.Simt
  producers.io.peMismatch.fetchTpcValid := true.B
  producers.io.peMismatch.fetchTpc := io.peFetchTpc

  producers.io.iexIqStalled := io.stall
  producers.io.iexIqProgress := io.stallProgress
  stallIdentity.io.stid := io.stallStid
  stallIdentity.io.oldestValid := VecInit((0 until stidCount).map(stid =>
    io.stallOldestValid && (io.stallStid === stid.U)))
  stallIdentity.io.oldestBlockComplete := VecInit((0 until stidCount).map(stid =>
    io.stallOldestComplete && (io.stallStid === stid.U)))
  stallIdentity.io.blockCommitPointer := VecInit((0 until stidCount).map(stid =>
    Mux(io.stallStid === stid.U, io.stallCommitPointer, 0.U)))
  producers.io.iexIqOldestBlockComplete := stallIdentity.io.selectedOldestBlockComplete
  producers.io.iexIqIdentityValid := stallIdentity.io.identityValid
  producers.io.iexIqRecoveryBlockBid := stallIdentity.io.recoveryBlockBid
  producers.io.iexIqStid := io.stallStid
  producers.io.iexIqPeId := 0.U
  producers.io.iexIqTid := 0.U

  recovery.io.sources := producers.io.sources
  producers.io.sourceReady := recovery.io.sourceReady
  recovery.io.oldestValid := VecInit(true.B, true.B)
  recovery.io.oldestBid(0) := id(0.U)
  recovery.io.oldestBid(1) := id(0.U)
  recovery.io.oldestBlockComplete := VecInit(false.B, false.B)
  recovery.io.intentReady := io.intentReady

  io.bccReady := producers.io.bccReady
  io.bccAccepted := producers.io.bccAccepted
  io.slowReady := producers.io.iexSlowReady
  io.slowAccepted := producers.io.iexSlowAccepted
  io.peReady := producers.io.peMismatchReady
  io.peAccepted := producers.io.peMismatchAccepted
  io.stallTriggerCaptured := producers.io.iexIqTriggerCaptured
  io.stallBlockedByMissingIdentity := producers.io.iexIqBlockedByMissingIdentity
  io.stallCount := producers.io.iexIqStallCount
  io.stallResolvedIdentityValid := stallIdentity.io.identityValid
  io.stallResolvedBlockBid := stallIdentity.io.recoveryBlockBid
  io.producerPendingMask := producers.io.pendingMask
  io.sourcePendingMask := recovery.io.sourcePendingMask
  io.sourceResolvedMask := recovery.io.sourceResolvedMask
  io.consumedPayloadSourceMask := recovery.io.consumedPayloadSourceMask
  io.classGlobalFlushPendingMask := recovery.io.classGlobalFlushPendingMask
  io.classGlobalReplayPendingMask := recovery.io.classGlobalReplayPendingMask
  io.classPePendingMask := recovery.io.classPePendingMask
  io.intentValid := recovery.io.intent.valid
  io.intentType := recovery.io.intent.flush.req.typ.asUInt
  io.intentBlockBid := recovery.io.intent.blockFlushBid
  io.intentBlockPointerValid := recovery.io.intent.blockFlushPointerValid
  io.intentBlockPointer := recovery.io.intent.blockFlushPointer
  io.intentStid := recovery.io.intent.flush.req.stid
  io.intentPeId := recovery.io.intent.flush.req.peId
  io.intentImmediate := recovery.io.intent.flush.req.immediateFlush
  io.intentExecEngine := recovery.io.intent.flush.req.execEngine.asUInt
  io.intentBaseOnBid := recovery.io.intent.flush.baseOnBid
  io.intentFetchTpcValid := recovery.io.intent.flush.req.fetchTpcValid
  io.intentFetchTpc := recovery.io.intent.flush.req.fetchTpc
  io.pending := recovery.io.pending || producers.io.pendingMask.orR
}

object EmitRecoveryProducerProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new RecoveryProducerProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/recovery-producer-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
