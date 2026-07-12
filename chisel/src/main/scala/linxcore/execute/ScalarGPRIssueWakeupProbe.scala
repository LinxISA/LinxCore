package linxcore.execute

import chisel3._
import circt.stage.ChiselStage

import linxcore.common.{DestinationKind, InterfaceParams, OperandClass, RenamedUop}
import linxcore.rob.ROBID

class ScalarGPRIssueWakeupProbeIO extends Bundle {
  val enqueueValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val sourceTag = Input(UInt(6.W))

  val writeRequest = Input(Bool())
  val writeCommit = Input(Bool())
  val writeTag = Input(UInt(6.W))
  val writeData = Input(UInt(64.W))
  val writeReady = Output(Bool())
  val writeFire = Output(Bool())

  val issueReady = Input(Bool())
  val sourceReady = Output(Bool())
  val wakeupMatched = Output(Bool())
  val wakeupMatchCount = Output(UInt(4.W))
  val selectedValid = Output(Bool())
  val pickFire = Output(Bool())
  val issueValid = Output(Bool())
  val issueFire = Output(Bool())
  val issueData = Output(UInt(64.W))
  val readyMask = Output(UInt(64.W))
  val protocolError = Output(Bool())
}

class ScalarGPRIssueWakeupProbe extends Module {
  private val p = InterfaceParams(robEntries = 8, iqEntries = 4)
  val io = IO(new ScalarGPRIssueWakeupProbeIO)

  val gpr = Module(new ScalarGPRFile(
    archRegs = 24,
    physRegs = 64,
    dataWidth = 64,
    readPorts = 3,
    writePorts = 1
  ))
  val issue = Module(new ReducedScalarIssueQueue(p, depth = 4))

  val uop = Wire(new RenamedUop(p))
  uop := 0.U.asTypeOf(uop)
  uop.valid := true.B
  uop.src(0).valid := true.B
  uop.src(0).operandClass := OperandClass.P
  uop.src(0).relTag := 2.U
  uop.src(0).physTag := io.sourceTag
  uop.dst(0).valid := false.B
  uop.dst(0).kind := DestinationKind.None
  uop.rid := ROBID.zero(p.robEntries)
  uop.bid := ROBID.zero(p.robEntries)
  uop.gid := ROBID.zero(p.robEntries)

  issue.io.inValid := io.enqueueValid
  issue.io.in := uop
  issue.io.flushValid := false.B
  issue.io.releaseValid := false.B
  issue.io.releaseBid := ROBID.disabled(p.robEntries)
  issue.io.releaseRid := ROBID.disabled(p.robEntries)
  issue.io.releaseStid := 0.U
  issue.io.secondaryReleaseValid := false.B
  issue.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  issue.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  issue.io.secondaryReleaseStid := 0.U
  issue.io.readyMask := gpr.io.readyMask
  issue.io.pWakeupValid := gpr.io.write(0).fire
  issue.io.pWakeupTag := gpr.io.write(0).tag
  issue.io.localTReadyMask := 0.U
  issue.io.localUReadyMask := 0.U
  issue.io.issueReady := io.issueReady

  gpr.io.initValid := false.B
  gpr.io.initTag := 0.U
  gpr.io.initData := 0.U
  gpr.io.clearValid := false.B
  gpr.io.clearTag := 0.U
  gpr.io.write(0).requestValid := io.writeRequest
  gpr.io.write(0).commit := io.writeCommit
  gpr.io.write(0).tag := io.writeTag
  gpr.io.write(0).data := io.writeData

  for (lane <- 0 until 3) {
    gpr.io.readValid(lane) := issue.io.readValid(lane)
    gpr.io.readTag(lane) := issue.io.readTags(lane)
    issue.io.readReady(lane) := gpr.io.readReady(lane)
    issue.io.readData(lane) := gpr.io.readData(lane)
  }

  io.enqueueReady := issue.io.inReady
  io.writeReady := gpr.io.write(0).ready
  io.writeFire := gpr.io.write(0).fire
  io.sourceReady := issue.io.allSourcesReady
  io.wakeupMatched := issue.io.pWakeupMatched
  io.wakeupMatchCount := issue.io.pWakeupMatchCount
  io.selectedValid := issue.io.selectedValid
  io.pickFire := issue.io.pickFire
  io.issueValid := issue.io.issueValid
  io.issueFire := issue.io.issueFire
  io.issueData := issue.io.issueSrcData(0)
  io.readyMask := gpr.io.readyMask
  io.protocolError := gpr.io.protocolError
}

object EmitScalarGPRIssueWakeupProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ScalarGPRIssueWakeupProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
