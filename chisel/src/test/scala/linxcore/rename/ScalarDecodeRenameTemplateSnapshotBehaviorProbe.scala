package linxcore.rename

import circt.stage.ChiselStage
import chisel3._
import org.scalatest.funsuite.AnyFunSuite

import linxcore.commit.CommitTraceParams
import linxcore.common._
import linxcore.rob.ROBID

class ScalarDecodeRenameTemplateSnapshotBehaviorProbeIO extends Bundle {
  val inputValid = Input(Bool())
  val opcode = Input(UInt(12.W))
  val threadId = Input(UInt(8.W))
  val activeStid = Input(UInt(8.W))
  val outReady = Input(Bool())
  val robAllocReady = Input(Bool())
  val dstValid = Input(Bool())
  val dstArch = Input(UInt(6.W))
  val bidValue = Input(UInt(6.W))
  val gidValue = Input(UInt(6.W))
  val ridValue = Input(UInt(6.W))
  val blockBid = Input(UInt(64.W))
  val uid = Input(UInt(64.W))

  val commitValid = Input(Bool())
  val commitBidValue = Input(UInt(6.W))
  val commitBlockBid = Input(UInt(64.W))
  val commitStid = Input(UInt(8.W))
  val cleanupReplayValid = Input(Bool())
  val cleanupStid = Input(UInt(8.W))

  val accepted = Output(Bool())
  val dstPhysTag = Output(UInt(6.W))
  val commitAccepted = Output(Bool())
  val cleanupReplayObserved = Output(Bool())

  val templateSnapshotValid = Output(Bool())
  val templateSnapshotGeneration = Output(UInt(16.W))
  val templateSmapSnapshot = Output(Vec(24, UInt(6.W)))
  val templateCmapSnapshot = Output(Vec(24, UInt(6.W)))
}

class ScalarDecodeRenameTemplateSnapshotBehaviorProbe extends Module {
  private val P = InterfaceParams()
  private val TraceP = CommitTraceParams()
  private val ScalarArchRegs = 24
  private val PhysRegs = 64
  private val ScalarStidCount = 2

  val io = IO(new ScalarDecodeRenameTemplateSnapshotBehaviorProbeIO)

  private val bridge = Module(new ScalarDecodeRenameBridge(
    p = P,
    traceParams = TraceP,
    scalarArchRegs = ScalarArchRegs,
    physRegs = PhysRegs,
    mapQDepth = 32,
    stidWidth = P.threadIdWidth,
    scalarStidCount = ScalarStidCount,
    peIdWidth = P.peIdWidth,
    tidWidth = P.threadIdWidth
  ))

  bridge.io.in := 0.U.asTypeOf(new DecodedUop(P))
  bridge.io.in.valid := io.inputValid
  bridge.io.in.threadId := io.threadId
  bridge.io.in.opcode := io.opcode
  bridge.io.in.dst(0).valid := io.dstValid
  bridge.io.in.dst(0).kind := DestinationKind.Gpr
  bridge.io.in.dst(0).archTag := io.dstArch
  bridge.io.in.bid.valid := true.B
  bridge.io.in.bid.wrap := false.B
  bridge.io.in.bid.value := io.bidValue
  bridge.io.in.gid.valid := true.B
  bridge.io.in.gid.wrap := false.B
  bridge.io.in.gid.value := io.gidValue
  bridge.io.in.rid.valid := true.B
  bridge.io.in.rid.wrap := false.B
  bridge.io.in.rid.value := io.ridValue
  bridge.io.in.blockBidValid := true.B
  bridge.io.in.blockBid := io.blockBid
  bridge.io.in.uid.uid := io.uid

  bridge.io.activeStid := io.activeStid
  bridge.io.outReady := io.outReady
  bridge.io.robAllocReady := io.robAllocReady

  bridge.io.checkpointValid := false.B
  bridge.io.checkpointBid := 0.U.asTypeOf(new ROBID(P.robEntries))
  bridge.io.checkpointStid := 0.U
  bridge.io.commitValid := io.commitValid
  bridge.io.commitBid.valid := true.B
  bridge.io.commitBid.wrap := false.B
  bridge.io.commitBid.value := io.commitBidValue
  bridge.io.commitBlockBid := io.commitBlockBid
  bridge.io.commitStid := io.commitStid
  bridge.io.cleanup := 0.U.asTypeOf(bridge.io.cleanup)
  bridge.io.cleanup.valid := io.cleanupReplayValid
  bridge.io.cleanup.renameReplayValid := io.cleanupReplayValid
  bridge.io.cleanup.flush.req.stid := io.cleanupStid
  bridge.io.cleanupOrderValid := false.B
  bridge.io.cleanupOrder := 0.U

  private val snapshotValid = bridge.io.templateSnapshotValid
  private val snapshotGeneration = bridge.io.templateSnapshotGeneration
  private val smapSnapshot = bridge.io.templateSmapSnapshot
  private val cmapSnapshot = bridge.io.templateCmapSnapshot

  require(snapshotGeneration.getWidth == 16, "template snapshot generation must be exactly 16 bits")
  require(smapSnapshot.length == ScalarArchRegs, "template SMAP snapshot must cover every scalar architectural register")
  require(cmapSnapshot.length == ScalarArchRegs, "template CMAP snapshot must cover every scalar architectural register")
  require(smapSnapshot.head.getWidth == P.physRegWidth, "template SMAP tags must use the physical tag width")
  require(cmapSnapshot.head.getWidth == P.physRegWidth, "template CMAP tags must use the physical tag width")

  io.accepted := bridge.io.accepted
  io.dstPhysTag := bridge.io.dstPhysTag
  io.commitAccepted := bridge.io.commitAccepted
  io.cleanupReplayObserved := bridge.io.cleanupReplayObserved
  io.templateSnapshotValid := snapshotValid
  io.templateSnapshotGeneration := snapshotGeneration
  io.templateSmapSnapshot := smapSnapshot
  io.templateCmapSnapshot := cmapSnapshot
}

object EmitScalarDecodeRenameTemplateSnapshotBehaviorProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/backend-rename-template-snapshot-probe")

  ChiselStage.emitSystemVerilogFile(
    new ScalarDecodeRenameTemplateSnapshotBehaviorProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}

class ScalarDecodeRenameTemplateSnapshotBehaviorProbeSpec extends AnyFunSuite {
  test("elaborates a public wrapper around the real scalar decode rename bridge") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarDecodeRenameTemplateSnapshotBehaviorProbe)

    assert(sv.contains("module ScalarDecodeRenameTemplateSnapshotBehaviorProbe"))
    assert(sv.contains("module ScalarDecodeRenameBridge"))
    assert(sv.contains("io_templateSnapshotValid"))
    assert(sv.contains("io_templateSnapshotGeneration"))
    assert(sv.contains("io_templateSmapSnapshot_23"))
    assert(sv.contains("io_templateCmapSnapshot_23"))
  }
}
