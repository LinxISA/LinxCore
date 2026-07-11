package linxcore.recovery

import chisel3._

import linxcore.rob.ROBID

class RecoveryClassMergeProbeIO extends Bundle {
  val inValid = Input(Bool())
  val inType = Input(FlushType())
  val inBlockBid = Input(UInt(16.W))
  val inStid = Input(UInt(8.W))
  val inPe = Input(UInt(8.W))
  val inRid = Input(UInt(3.W))
  val inExecEngine = Input(ExecEngineType())
  val inFetchTpcValid = Input(Bool())
  val inSource = Input(UInt(2.W))
  val oldestBid0 = Input(UInt(3.W))
  val oldestBid1 = Input(UInt(3.W))
  val oldestBlockComplete = Input(UInt(2.W))
  val outReady = Input(Bool())

  val inReady = Output(Bool())
  val inAccepted = Output(Bool())
  val inBlockedByStid = Output(Bool())
  val inBlockedByPe = Output(Bool())
  val inDroppedByOlder = Output(Bool())
  val inDroppedByComplete = Output(Bool())
  val inMerged = Output(Bool())
  val outValid = Output(Bool())
  val outAccepted = Output(Bool())
  val outType = Output(FlushType())
  val outBlockBid = Output(UInt(16.W))
  val outStid = Output(UInt(8.W))
  val outPe = Output(UInt(8.W))
  val outRid = Output(UInt(3.W))
  val outClass = Output(RecoveryActionClass())
  val outCauseMask = Output(UInt(4.W))
  val outPayloadSourceValid = Output(Bool())
  val outPayloadSource = Output(UInt(2.W))
  val resolvedMask = Output(UInt(4.W))
  val globalFlushPendingMask = Output(UInt(2.W))
  val globalReplayPendingMask = Output(UInt(2.W))
  val pePendingMask = Output(UInt(4.W))
  val pending = Output(Bool())
}

class RecoveryClassMergeProbe extends Module {
  private val entries = 8
  val io = IO(new RecoveryClassMergeProbeIO)
  val merge = Module(new RecoveryClassMerge(
    stidCount = 2,
    peCount = 2,
    entries = entries,
    bidWidth = 16,
    sourceCount = 4
  ))

  private def id(value: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := true.B
    out.wrap := false.B
    out.value := value
    out
  }

  val request = Wire(chiselTypeOf(merge.io.in))
  request := 0.U.asTypeOf(request)
  request.valid := io.inValid
  request.typ := io.inType
  request.blockBid := io.inBlockBid
  request.stid := io.inStid
  request.peId := io.inPe
  request.rid := id(io.inRid)
  request.gid := id(0.U)
  request.lsId := id(io.inRid)
  request.execEngine := io.inExecEngine
  request.fetchTpcValid := io.inFetchTpcValid
  merge.io.in := request
  merge.io.inProvenance := RecoveryProvenance.single(io.inSource, io.inValid, 4)
  merge.io.oldestValid := VecInit(true.B, true.B)
  merge.io.oldestBid(0) := id(io.oldestBid0)
  merge.io.oldestBid(1) := id(io.oldestBid1)
  merge.io.oldestBlockComplete := io.oldestBlockComplete.asBools
  merge.io.outReady := io.outReady

  io.inReady := merge.io.inReady
  io.inAccepted := merge.io.inAccepted
  io.inBlockedByStid := merge.io.inBlockedByStid
  io.inBlockedByPe := merge.io.inBlockedByPe
  io.inDroppedByOlder := merge.io.inDroppedByOlder
  io.inDroppedByComplete := merge.io.inDroppedByComplete
  io.inMerged := merge.io.inMerged
  io.outValid := merge.io.out.valid
  io.outAccepted := merge.io.outAccepted
  io.outType := merge.io.out.typ
  io.outBlockBid := merge.io.out.blockBid
  io.outStid := merge.io.out.stid
  io.outPe := merge.io.out.peId
  io.outRid := merge.io.out.rid.value
  io.outClass := merge.io.selectedClass
  io.outCauseMask := merge.io.outProvenance.causeMask
  io.outPayloadSourceValid := merge.io.outProvenance.payloadSourceValid
  io.outPayloadSource := merge.io.outProvenance.payloadSource
  io.resolvedMask := merge.io.resolvedMask
  io.globalFlushPendingMask := merge.io.globalFlushPendingMask
  io.globalReplayPendingMask := merge.io.globalReplayPendingMask
  io.pePendingMask := merge.io.pePendingMask
  io.pending := merge.io.pending
}

object EmitRecoveryClassMergeProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new RecoveryClassMergeProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/recovery-class-merge-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
