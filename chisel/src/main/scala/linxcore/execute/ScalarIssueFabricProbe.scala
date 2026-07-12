package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{DestinationKind, DispatchTarget, InterfaceParams, OperandClass, RenamedUop}
import linxcore.rob.ROBID

class ScalarIssueFabricProbeIO(val p: InterfaceParams, val depth: Int, val banks: Int) extends Bundle {
  val enqueueValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val enqueueStid = Input(UInt(p.threadIdWidth.W))
  val enqueueRid = Input(UInt(log2Ceil(p.robEntries).W))
  val enqueuePc = Input(UInt(p.pcWidth.W))
  val enqueueBru = Input(Bool())
  val enqueueStore = Input(Bool())
  val sourceTag = Input(UInt(p.physRegWidth.W))
  val wakeupValid = Input(Bool())
  val issueReady = Input(Bool())
  val flush = Input(Bool())
  val releaseValid = Input(Bool())
  val releaseStid = Input(UInt(p.threadIdWidth.W))
  val releaseRid = Input(UInt(log2Ceil(p.robEntries).W))

  val count = Output(UInt(log2Ceil(depth + 1).W))
  val enqueueBank = Output(UInt(log2Ceil(banks).W))
  val bankOccupancy = Output(Vec(banks, UInt(log2Ceil(depth / banks + 1).W)))
  val bankPickMask = Output(UInt(banks.W))
  val bankReadAttemptMask = Output(UInt(banks.W))
  val bankReadGrantMask = Output(UInt(banks.W))
  val bankIssueValidMask = Output(UInt(banks.W))
  val bankIssueGrantMask = Output(UInt(banks.W))
  val simultaneousPick = Output(Bool())
  val readContention = Output(Bool())
  val readArbitrationLoss = Output(Bool())
  val cancelFire = Output(Bool())
  val issueContention = Output(Bool())
  val controlFenceActive = Output(Bool())
  val controlFenceBlocked = Output(Bool())
  val bankControlBlockedMask = Output(UInt(banks.W))
  val storeOrderBlocked = Output(Bool())
  val bankStoreOrderBlockedMask = Output(UInt(banks.W))
  val issueFire = Output(Bool())
  val issueStid = Output(UInt(p.threadIdWidth.W))
  val issueRid = Output(UInt(log2Ceil(p.robEntries).W))
  val issueData = Output(UInt(p.immWidth.W))
  val protocolError = Output(Bool())

  val policyValidMask = Input(UInt(banks.W))
  val policyStid = Input(Vec(banks, UInt(p.threadIdWidth.W)))
  val policyRid = Input(Vec(banks, UInt(log2Ceil(p.robEntries).W)))
  val policyAdvance = Input(Bool())
  val policyGrantMask = Output(UInt(banks.W))
  val policySelectedIndex = Output(UInt(log2Ceil(banks).W))
  val policyContended = Output(Bool())
  val policyRrBase = Output(UInt(log2Ceil(banks).W))
}

class ScalarIssueFabricProbe(
    val p: InterfaceParams = InterfaceParams(robEntries = 8),
    val depth: Int = 4,
    val banks: Int = 2)
    extends Module {
  val io = IO(new ScalarIssueFabricProbeIO(p, depth, banks))
  val fabric = Module(new ScalarIssueFabric(p, depth, banks))
  val policy = Module(new ScalarIssueCandidateArbiter(p, banks))

  val enqueue = Wire(new RenamedUop(p))
  enqueue := 0.U.asTypeOf(enqueue)
  enqueue.valid := true.B
  enqueue.pc := io.enqueuePc
  enqueue.threadId := io.enqueueStid
  enqueue.dispatchTarget := Mux(io.enqueueBru, DispatchTarget.Bru, DispatchTarget.Alu)
  enqueue.isStore := io.enqueueStore
  enqueue.bid.valid := true.B
  enqueue.bid.wrap := false.B
  enqueue.bid.value := 0.U
  enqueue.rid.valid := true.B
  enqueue.rid.wrap := false.B
  enqueue.rid.value := io.enqueueRid
  enqueue.src(0).valid := true.B
  enqueue.src(0).operandClass := OperandClass.P
  enqueue.src(0).relTag := 2.U
  enqueue.src(0).physTag := io.sourceTag
  enqueue.dst(0).valid := false.B
  enqueue.dst(0).kind := DestinationKind.None

  fabric.io.inValid := io.enqueueValid
  fabric.io.in := enqueue
  fabric.io.flushValid := io.flush
  fabric.io.releaseValid := io.releaseValid
  fabric.io.releaseBid.valid := true.B
  fabric.io.releaseBid.wrap := false.B
  fabric.io.releaseBid.value := 0.U
  fabric.io.releaseRid.valid := io.releaseValid
  fabric.io.releaseRid.wrap := false.B
  fabric.io.releaseRid.value := io.releaseRid
  fabric.io.releaseStid := io.releaseStid
  fabric.io.secondaryReleaseValid := false.B
  fabric.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseStid := 0.U
  fabric.io.externalControlFenceValid := false.B
  fabric.io.externalControlFenceBid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceRid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceStid := 0.U
  fabric.io.readyMask := 0.U
  fabric.io.pWakeupValid := io.wakeupValid
  fabric.io.pWakeupTag := io.sourceTag
  fabric.io.localTReadyMask := 0.U
  fabric.io.localUReadyMask := 0.U
  for (lane <- 0 until 3) {
    fabric.io.readData(lane) := fabric.io.readTags(lane) + (0x100 + lane).U
  }
  fabric.io.issueReady := io.issueReady

  for (idx <- 0 until banks) {
    policy.io.valid(idx) := io.policyValidMask(idx)
    policy.io.stid(idx) := io.policyStid(idx)
    policy.io.rid(idx).valid := true.B
    policy.io.rid(idx).wrap := false.B
    policy.io.rid(idx).value := io.policyRid(idx)
  }
  policy.io.advance := io.policyAdvance

  io.enqueueReady := fabric.io.inReady
  io.count := fabric.io.count
  io.enqueueBank := fabric.io.enqueueBank
  io.bankOccupancy := fabric.io.bankOccupancy
  io.bankPickMask := fabric.io.bankPickMask
  io.bankReadAttemptMask := fabric.io.bankReadAttemptMask
  io.bankReadGrantMask := fabric.io.bankReadGrantMask
  io.bankIssueValidMask := fabric.io.bankIssueValidMask
  io.bankIssueGrantMask := fabric.io.bankIssueGrantMask
  io.simultaneousPick := fabric.io.simultaneousPick
  io.readContention := fabric.io.readContention
  io.readArbitrationLoss := fabric.io.readArbitrationLoss
  io.cancelFire := fabric.io.cancelFire
  io.issueContention := fabric.io.issueContention
  io.controlFenceActive := fabric.io.controlFenceActive
  io.controlFenceBlocked := fabric.io.controlFenceBlocked
  io.bankControlBlockedMask := fabric.io.bankControlBlockedMask
  io.storeOrderBlocked := fabric.io.storeOrderBlocked
  io.bankStoreOrderBlockedMask := fabric.io.bankStoreOrderBlockedMask
  io.issueFire := fabric.io.issueFire
  io.issueStid := fabric.io.issueUop.threadId
  io.issueRid := fabric.io.issueUop.rid.value
  io.issueData := fabric.io.issueSrcData(0)
  io.protocolError := fabric.io.protocolError
  io.policyGrantMask := policy.io.grant.asUInt
  io.policySelectedIndex := policy.io.selectedIndex
  io.policyContended := policy.io.contended
  io.policyRrBase := policy.io.rrBase
}

object ElaborateScalarIssueFabricProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new ScalarIssueFabricProbe(),
    args,
    Array("--strip-debug-info", "--disable-all-randomization")
  )
}
