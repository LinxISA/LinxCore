package linxcore.bctrl

import circt.stage.ChiselStage
import chisel3._
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class TemplateBehaviorProbeIdentity(val p: InterfaceParams) extends Bundle {
  val generation = UInt(16.W)
  val stid = UInt(p.threadIdWidth.W)
  val peId = UInt(p.peIdWidth.W)
  val pc = UInt(p.pcWidth.W)
  val raw = UInt(p.insnWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val bidValid = Bool()
  val bidWrap = Bool()
  val bidValue = UInt(p.robIndexWidth.W)
  val gidValid = Bool()
  val gidWrap = Bool()
  val gidValue = UInt(p.robIndexWidth.W)
  val ridValid = Bool()
  val ridWrap = Bool()
  val ridValue = UInt(p.robIndexWidth.W)
  val robSlot = UInt(p.robIndexWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
  val commitIdentityBid = UInt(32.W)
  val commitIdentityGid = UInt(32.W)
  val commitIdentityRid = UInt(32.W)
}

class BlockControlTemplateSequencerBehaviorProbeIO(
    val p: InterfaceParams,
    val archRegs: Int,
    val physTagWidth: Int)
    extends Bundle {
  val enqueueValid = Input(Bool())
  val enqueueIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val src0Imm = Input(UInt(p.immWidth.W))
  val rangeM = Input(UInt(p.archRegWidth.W))
  val rangeN = Input(UInt(p.archRegWidth.W))
  val mapSeed = Input(UInt(physTagWidth.W))

  val issueValid = Input(Bool())
  val issueIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val oldSp = Input(UInt(p.immWidth.W))
  val srcData0 = Input(UInt(p.immWidth.W))
  val srcData1 = Input(UInt(p.immWidth.W))
  val srcData2 = Input(UInt(p.immWidth.W))

  val cancelValid = Input(Bool())
  val cancelIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val globalClear = Input(Bool())

  val rfReadReady = Input(Bool())
  val rfReadResponseValid = Input(Bool())
  val rfReadResponseIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val rfReadResponseChildIndex = Input(UInt(5.W))
  val rfReadResponsePhysTag = Input(UInt(physTagWidth.W))
  val rfReadResponseData = Input(UInt(p.immWidth.W))

  val loadReady = Input(Bool())
  val loadResponseValid = Input(Bool())
  val loadResponseIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val loadResponseChildIndex = Input(UInt(5.W))
  val loadResponseAddr = Input(UInt(p.immWidth.W))
  val loadResponseData = Input(UInt(p.immWidth.W))

  val selectedTemplate = Input(Bool())
  val parentCommitValid = Input(Bool())
  val parentCommitIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val storeGrant = Input(Bool())
  val recoveryValid = Input(Bool())
  val recoveryIdentity = Input(new TemplateBehaviorProbeIdentity(p))
  val recoveryKillsActive = Input(Bool())
  val storeReady = Input(Bool())
  val rfWriteReady = Input(Bool())

  val enqueueReady = Output(Bool())
  val issueReady = Output(Bool())
  val parentRequestValid = Output(Bool())
  val parentRequestReady = Output(Bool())
  val parentTransfer = Output(Bool())
  val sidecarOccupiedMask = Output(UInt(2.W))
  val sidecarDecodeFenceMask = Output(UInt(2.W))
  val sidecarIssueFenceMask = Output(UInt(2.W))
  val sidecarMemoryFenceMask = Output(UInt(2.W))
  val enqueueCount = Output(UInt(32.W))
  val transferCount = Output(UInt(32.W))
  val mismatchDropCount = Output(UInt(32.W))
  val staleGenerationDropCount = Output(UInt(32.W))

  val rfReadRequestValid = Output(Bool())
  val rfReadRequestReady = Output(Bool())
  val rfReadRequestChildIndex = Output(UInt(5.W))
  val rfReadRequestArchReg = Output(UInt(p.archRegWidth.W))
  val rfReadRequestPhysTag = Output(UInt(physTagWidth.W))
  val loadRequestValid = Output(Bool())
  val loadRequestReady = Output(Bool())
  val loadRequestChildIndex = Output(UInt(5.W))
  val loadRequestArchReg = Output(UInt(p.archRegWidth.W))
  val loadRequestAddr = Output(UInt(p.immWidth.W))

  val completionValid = Output(Bool())
  val completionParentSlot = Output(UInt(p.robIndexWidth.W))
  val completionResult = Output(UInt(p.immWidth.W))
  val completionNewSp = Output(UInt(p.immWidth.W))
  val completionNextPc = Output(UInt(p.pcWidth.W))
  val completionRedirectValid = Output(Bool())
  val completionRetainedStoreCount = Output(UInt(5.W))
  val completionRetainedRfCount = Output(UInt(5.W))

  val storeRequestValid = Output(Bool())
  val storeRequestReady = Output(Bool())
  val storeRequestChildIndex = Output(UInt(5.W))
  val storeRequestChildLast = Output(Bool())
  val storeRequestOwnsStqRow = Output(Bool())
  val storeRequestAddr = Output(UInt(p.immWidth.W))
  val storeRequestData = Output(UInt(p.immWidth.W))
  val rfWriteRequestValid = Output(Bool())
  val rfWriteRequestReady = Output(Bool())
  val rfWriteRequestChildIndex = Output(UInt(5.W))
  val rfWriteRequestChildLast = Output(Bool())
  val rfWriteRequestArchReg = Output(UInt(p.archRegWidth.W))
  val rfWriteRequestPhysTag = Output(UInt(physTagWidth.W))
  val rfWriteRequestData = Output(UInt(p.immWidth.W))

  val storeObservationValid = Output(Bool())
  val storeObservationChildIndex = Output(UInt(5.W))
  val storeObservationAddr = Output(UInt(p.immWidth.W))
  val storeObservationData = Output(UInt(p.immWidth.W))
  val lookupObservationValid = Output(Bool())
  val lookupObservationChildIndex = Output(UInt(5.W))
  val lookupObservationAddr = Output(UInt(p.immWidth.W))
  val lookupObservationData = Output(UInt(p.immWidth.W))

  val activeValid = Output(Bool())
  val activeGeneration = Output(UInt(16.W))
  val activeStid = Output(UInt(p.threadIdWidth.W))
  val activeRobSlot = Output(UInt(p.robIndexWidth.W))
  val activeMapReg22 = Output(UInt(physTagWidth.W))
  val activeOldSp = Output(UInt(p.immWidth.W))
  val activeSrcData0 = Output(UInt(p.immWidth.W))
  val state = Output(UInt(4.W))
  val decodeFence = Output(Bool())
  val issueFence = Output(Bool())
  val memoryFence = Output(Bool())
  val parentIssueRelease = Output(Bool())
  val terminal = Output(Bool())
  val committed = Output(Bool())
  val spPublishValid = Output(Bool())
  val spPublishValue = Output(UInt(p.immWidth.W))
  val nextPc = Output(UInt(p.pcWidth.W))
  val selfRestartObserved = Output(Bool())
  val illegalDiscardAttempt = Output(Bool())
  val cancelObserved = Output(Bool())
  val retainedStoreCount = Output(UInt(5.W))
  val retainedRfCount = Output(UInt(5.W))
  val tailZero = Output(Bool())
  val staleResponseDropCount = Output(UInt(32.W))
  val acceptedStoreCount = Output(UInt(32.W))
  val acceptedRfWriteCount = Output(UInt(32.W))
}

class BlockControlTemplateSequencerBehaviorProbe extends Module {
  private val p = InterfaceParams()
  private val archRegs = 32
  private val physTagWidth = 6
  val io = IO(new BlockControlTemplateSequencerBehaviorProbeIO(p, archRegs, physTagWidth))

  private def assignIdentity(
      destination: TemplateParentIdentity,
      source: TemplateBehaviorProbeIdentity): Unit = {
    destination.generation := source.generation
    destination.stid := source.stid
    destination.peId := source.peId
    destination.pc := source.pc
    destination.raw := source.raw
    destination.opcode := source.opcode
    destination.bid.valid := source.bidValid
    destination.bid.wrap := source.bidWrap
    destination.bid.value := source.bidValue
    destination.gid.valid := source.gidValid
    destination.gid.wrap := source.gidWrap
    destination.gid.value := source.gidValue
    destination.rid.valid := source.ridValid
    destination.rid.wrap := source.ridWrap
    destination.rid.value := source.ridValue
    destination.robSlot := source.robSlot
    destination.blockBidValid := source.blockBidValid
    destination.blockBid := source.blockBid
    destination.commitIdentityBid := source.commitIdentityBid
    destination.commitIdentityGid := source.commitIdentityGid
    destination.commitIdentityRid := source.commitIdentityRid
  }

  val sidecar = Module(new TemplateRenameSidecarTable(p, archRegs, physTagWidth, stidCount = 2))
  val sequencer = Module(new BlockControlTemplateSequencer(p, archRegs, physTagWidth))

  sidecar.io.enqueue.valid := io.enqueueValid
  assignIdentity(sidecar.io.enqueue.bits.identity, io.enqueueIdentity)
  sidecar.io.enqueue.bits.src0Imm := io.src0Imm
  sidecar.io.enqueue.bits.rangeM := io.rangeM
  sidecar.io.enqueue.bits.rangeN := io.rangeN
  for (reg <- 0 until archRegs) {
    sidecar.io.enqueue.bits.map.smap(reg) := io.mapSeed + reg.U
    sidecar.io.enqueue.bits.map.cmap(reg) := io.mapSeed + (archRegs - reg).U
  }

  sidecar.io.issue.valid := io.issueValid
  assignIdentity(sidecar.io.issue.bits.identity, io.issueIdentity)
  sidecar.io.issue.bits.oldSp := io.oldSp
  sidecar.io.issue.bits.srcData0 := io.srcData0
  sidecar.io.issue.bits.srcData1 := io.srcData1
  sidecar.io.issue.bits.srcData2 := io.srcData2
  sidecar.io.cancel.valid := io.cancelValid
  assignIdentity(sidecar.io.cancel.bits, io.cancelIdentity)
  sidecar.io.releaseDecodeFence.valid := false.B
  sidecar.io.releaseDecodeFence.bits := 0.U.asTypeOf(sidecar.io.releaseDecodeFence.bits)
  sidecar.io.releaseIssueFence.valid := false.B
  sidecar.io.releaseIssueFence.bits := 0.U.asTypeOf(sidecar.io.releaseIssueFence.bits)
  sidecar.io.releaseMemoryFence.valid := false.B
  sidecar.io.releaseMemoryFence.bits := 0.U.asTypeOf(sidecar.io.releaseMemoryFence.bits)
  sidecar.io.globalClear := io.globalClear

  sequencer.io.parentRequest <> sidecar.io.parentRequest
  sequencer.io.rfReadRequest.ready := io.rfReadReady
  sequencer.io.rfReadResponse.valid := io.rfReadResponseValid
  assignIdentity(sequencer.io.rfReadResponse.bits.parent, io.rfReadResponseIdentity)
  sequencer.io.rfReadResponse.bits.childIndex := io.rfReadResponseChildIndex
  sequencer.io.rfReadResponse.bits.physTag := io.rfReadResponsePhysTag
  sequencer.io.rfReadResponse.bits.data := io.rfReadResponseData
  sequencer.io.loadRequest.ready := io.loadReady
  sequencer.io.loadResponse.valid := io.loadResponseValid
  assignIdentity(sequencer.io.loadResponse.bits.parent, io.loadResponseIdentity)
  sequencer.io.loadResponse.bits.childIndex := io.loadResponseChildIndex
  sequencer.io.loadResponse.bits.addr := io.loadResponseAddr
  sequencer.io.loadResponse.bits.data := io.loadResponseData
  sequencer.io.selectedTemplate := io.selectedTemplate
  sequencer.io.parentCommit.valid := io.parentCommitValid
  assignIdentity(sequencer.io.parentCommit.bits, io.parentCommitIdentity)
  sequencer.io.storeGrant := io.storeGrant
  sequencer.io.recovery.valid := io.recoveryValid
  assignIdentity(sequencer.io.recovery.bits, io.recoveryIdentity)
  sequencer.io.recoveryKillsActive := io.recoveryKillsActive
  sequencer.io.globalClear := io.globalClear
  sequencer.io.storeRequest.ready := io.storeReady
  sequencer.io.rfWriteRequest.ready := io.rfWriteReady

  io.enqueueReady := sidecar.io.enqueue.ready
  io.issueReady := sidecar.io.issue.ready
  io.parentRequestValid := sidecar.io.parentRequest.valid
  io.parentRequestReady := sidecar.io.parentRequest.ready
  io.parentTransfer := sidecar.io.parentRequest.fire
  io.sidecarOccupiedMask := sidecar.io.occupiedMask
  io.sidecarDecodeFenceMask := sidecar.io.decodeFenceMask
  io.sidecarIssueFenceMask := sidecar.io.issueFenceMask
  io.sidecarMemoryFenceMask := sidecar.io.memoryFenceMask
  io.enqueueCount := sidecar.io.enqueueCount
  io.transferCount := sidecar.io.transferCount
  io.mismatchDropCount := sidecar.io.mismatchDropCount
  io.staleGenerationDropCount := sidecar.io.staleGenerationDropCount

  io.rfReadRequestValid := sequencer.io.rfReadRequest.valid
  io.rfReadRequestReady := sequencer.io.rfReadRequest.ready
  io.rfReadRequestChildIndex := sequencer.io.rfReadRequest.bits.childIndex
  io.rfReadRequestArchReg := sequencer.io.rfReadRequest.bits.archReg
  io.rfReadRequestPhysTag := sequencer.io.rfReadRequest.bits.physTag
  io.loadRequestValid := sequencer.io.loadRequest.valid
  io.loadRequestReady := sequencer.io.loadRequest.ready
  io.loadRequestChildIndex := sequencer.io.loadRequest.bits.childIndex
  io.loadRequestArchReg := sequencer.io.loadRequest.bits.archReg
  io.loadRequestAddr := sequencer.io.loadRequest.bits.addr

  io.completionValid := sequencer.io.completion.valid
  io.completionParentSlot := sequencer.io.completion.bits.parentSlot
  io.completionResult := sequencer.io.completion.bits.result
  io.completionNewSp := sequencer.io.completion.bits.newSp
  io.completionNextPc := sequencer.io.completion.bits.nextPc
  io.completionRedirectValid := sequencer.io.completion.bits.redirectValid
  io.completionRetainedStoreCount := sequencer.io.completion.bits.retainedStoreCount
  io.completionRetainedRfCount := sequencer.io.completion.bits.retainedRfCount

  io.storeRequestValid := sequencer.io.storeRequest.valid
  io.storeRequestReady := sequencer.io.storeRequest.ready
  io.storeRequestChildIndex := sequencer.io.storeRequest.bits.childIndex
  io.storeRequestChildLast := sequencer.io.storeRequest.bits.childLast
  io.storeRequestOwnsStqRow := sequencer.io.storeRequest.bits.ownsStqRow
  io.storeRequestAddr := sequencer.io.storeRequest.bits.addr
  io.storeRequestData := sequencer.io.storeRequest.bits.data
  io.rfWriteRequestValid := sequencer.io.rfWriteRequest.valid
  io.rfWriteRequestReady := sequencer.io.rfWriteRequest.ready
  io.rfWriteRequestChildIndex := sequencer.io.rfWriteRequest.bits.childIndex
  io.rfWriteRequestChildLast := sequencer.io.rfWriteRequest.bits.childLast
  io.rfWriteRequestArchReg := sequencer.io.rfWriteRequest.bits.archReg
  io.rfWriteRequestPhysTag := sequencer.io.rfWriteRequest.bits.physTag
  io.rfWriteRequestData := sequencer.io.rfWriteRequest.bits.data

  io.storeObservationValid := sequencer.io.storeObservation.valid
  io.storeObservationChildIndex := sequencer.io.storeObservation.bits.childIndex
  io.storeObservationAddr := sequencer.io.storeObservation.bits.addr
  io.storeObservationData := sequencer.io.storeObservation.bits.data
  io.lookupObservationValid := sequencer.io.lookupObservation.valid
  io.lookupObservationChildIndex := sequencer.io.lookupObservation.bits.childIndex
  io.lookupObservationAddr := sequencer.io.lookupObservation.bits.addr
  io.lookupObservationData := sequencer.io.lookupObservation.bits.data

  io.activeValid := sequencer.io.activeValid
  io.activeGeneration := sequencer.io.activeIdentity.generation
  io.activeStid := sequencer.io.activeIdentity.stid
  io.activeRobSlot := sequencer.io.activeIdentity.robSlot
  io.activeMapReg22 := sequencer.io.activeMap.smap(22)
  io.activeOldSp := sequencer.io.activeOldSp
  io.activeSrcData0 := sequencer.io.activeSrcData(0)
  io.state := sequencer.io.state.asUInt
  io.decodeFence := sequencer.io.decodeFence
  io.issueFence := sequencer.io.issueFence
  io.memoryFence := sequencer.io.memoryFence
  io.parentIssueRelease := sequencer.io.parentIssueRelease
  io.terminal := sequencer.io.terminal
  io.committed := sequencer.io.committed
  io.spPublishValid := sequencer.io.spPublishValid
  io.spPublishValue := sequencer.io.spPublishValue
  io.nextPc := sequencer.io.nextPc
  io.selfRestartObserved := sequencer.io.selfRestartObserved
  io.illegalDiscardAttempt := sequencer.io.illegalDiscardAttempt
  io.cancelObserved := sequencer.io.cancelObserved
  io.retainedStoreCount := sequencer.io.retainedStoreCount
  io.retainedRfCount := sequencer.io.retainedRfCount
  io.tailZero := sequencer.io.tailZero
  io.staleResponseDropCount := sequencer.io.staleResponseDropCount
  io.acceptedStoreCount := sequencer.io.acceptedStoreCount
  io.acceptedRfWriteCount := sequencer.io.acceptedRfWriteCount
}

object EmitBlockControlTemplateSequencerBehaviorProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/bctrl-template-sequencer-behavior-probe")

  ChiselStage.emitSystemVerilogFile(
    new BlockControlTemplateSequencerBehaviorProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}

class BlockControlTemplateSequencerBehaviorProbeSpec extends AnyFunSuite {
  test("probe public interface binds the real sidecar and sequencer widths") {
    val p = InterfaceParams()
    val io = new BlockControlTemplateSequencerBehaviorProbeIO(p, archRegs = 32, physTagWidth = 6)
    assert(io.enqueueIdentity.generation.getWidth == 16)
    assert(io.enqueueIdentity.ridValue.getWidth == p.robIndexWidth)
    assert(io.sidecarOccupiedMask.getWidth == 2)
    assert(io.rfReadRequestChildIndex.getWidth == 5)
    assert(io.completionRetainedStoreCount.getWidth == 5)
    assert(io.activeMapReg22.getWidth == 6)
    assert(io.storeRequestAddr.getWidth == 64)
    assert(io.lookupObservationData.getWidth == 64)
  }
}
