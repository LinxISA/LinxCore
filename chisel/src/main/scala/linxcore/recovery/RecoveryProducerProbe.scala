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
  val stallIdentityValid = Input(Bool())
  val stallBlockBid = Input(UInt(16.W))
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
  val sourcePendingMask = Output(UInt(4.W))
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
  val bcc = Module(new BccRecoverySource(
    queueEntries = 2,
    entries = entries,
    bidWidth = 16,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val slow = Module(new IexSlowInsertRecoverySource(
    queueEntries = 2,
    entries = entries,
    bidWidth = 16,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val stall = Module(new IexIqStallRecoverySource(
    stallThreshold = 3,
    queueEntries = 2,
    entries = entries,
    bidWidth = 16,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val pe = Module(new PeMismatchRecoverySource(
    queueEntries = 2,
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

  private def id(value: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := true.B
    out.wrap := false.B
    out.value := value
    out
  }

  bcc.io.miss.valid := io.bccValid
  bcc.io.miss.recoveryBlockBid := io.bccBlockBid
  bcc.io.miss.stid := io.bccStid
  bcc.io.miss.peId := 0.U
  bcc.io.miss.tid := 0.U
  bcc.io.miss.execEngine := io.bccExecEngine

  slow.io.event.valid := io.slowValid
  slow.io.event.recoveryBlockBid := io.slowBlockBid
  slow.io.event.gid := id(0.U)
  slow.io.event.rid := id(1.U)
  slow.io.event.lsId := id(1.U)
  slow.io.event.stid := io.slowStid
  slow.io.event.peId := 0.U
  slow.io.event.tid := 0.U
  slow.io.event.execEngine := ExecEngineType.Scalar
  slow.io.event.fetchTpcValid := true.B
  slow.io.event.fetchTpc := io.slowFetchTpc

  pe.io.mismatch.valid := io.peValid
  pe.io.mismatch.recoveryBlockBid := io.peBlockBid
  pe.io.mismatch.gid := id(0.U)
  pe.io.mismatch.rid := id(2.U)
  pe.io.mismatch.lsId := id(2.U)
  pe.io.mismatch.stid := io.peStid
  pe.io.mismatch.peId := io.peId
  pe.io.mismatch.tid := 0.U
  pe.io.mismatch.execEngine := ExecEngineType.Simt
  pe.io.mismatch.fetchTpcValid := true.B
  pe.io.mismatch.fetchTpc := io.peFetchTpc

  stall.io.stalled := io.stall
  stall.io.progress := io.stallProgress
  stall.io.oldestBlockComplete := false.B
  stall.io.identityValid := io.stallIdentityValid
  stall.io.recoveryBlockBid := io.stallBlockBid
  stall.io.stid := io.stallStid
  stall.io.peId := 0.U
  stall.io.tid := 0.U

  recovery.io.sources(0) := bcc.io.source
  recovery.io.sources(1) := slow.io.source
  recovery.io.sources(2) := stall.io.source
  recovery.io.sources(3) := pe.io.source
  bcc.io.sourceReady := recovery.io.sourceReady(0)
  slow.io.sourceReady := recovery.io.sourceReady(1)
  stall.io.sourceReady := recovery.io.sourceReady(2)
  pe.io.sourceReady := recovery.io.sourceReady(3)
  recovery.io.oldestValid := VecInit(true.B, true.B)
  recovery.io.oldestBid(0) := id(0.U)
  recovery.io.oldestBid(1) := id(0.U)
  recovery.io.oldestBlockComplete := VecInit(false.B, false.B)
  recovery.io.intentReady := io.intentReady

  io.bccReady := bcc.io.missReady
  io.bccAccepted := bcc.io.missAccepted
  io.slowReady := slow.io.eventReady
  io.slowAccepted := slow.io.eventAccepted
  io.peReady := pe.io.mismatchReady
  io.peAccepted := pe.io.mismatchAccepted
  io.stallTriggerCaptured := stall.io.triggerCaptured
  io.stallBlockedByMissingIdentity := stall.io.blockedByMissingIdentity
  io.stallCount := stall.io.stallCount
  io.sourcePendingMask := recovery.io.sourcePendingMask
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
  io.pending := recovery.io.pending || bcc.io.pendingCount.orR || slow.io.pendingCount.orR ||
    stall.io.pendingCount.orR || pe.io.pendingCount.orR
}

object EmitRecoveryProducerProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new RecoveryProducerProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/recovery-producer-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
