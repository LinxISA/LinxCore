package linxcore.rename

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.commit.CommitTraceParams
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

class ScalarTURenameBridgeIdentityProbeIO extends Bundle {
  val allocateValid = Input(Bool())
  val allocateKind = Input(DestinationKind())
  val allocateRidValue = Input(UInt(3.W))

  val retireValid = Input(Bool())
  val retireKind = Input(DestinationKind())
  val retireSeqWrap = Input(Bool())
  val retireSeqValue = Input(UInt(3.W))
  val retireDealloc = Input(Bool())
  val retirePeId = Input(UInt(8.W))
  val retireStid = Input(UInt(8.W))

  val accepted = Output(Bool())
  val inReady = Output(Bool())
  val outValid = Output(Bool())
  val tUsedEntries = Output(UInt(6.W))
  val uUsedEntries = Output(UInt(6.W))

  val commandValid = Output(Bool())
  val commandKind = Output(DestinationKind())
  val commandSeqWrap = Output(Bool())
  val commandSeqValue = Output(UInt(3.W))
  val commandDealloc = Output(Bool())
  val commandPeId = Output(UInt(8.W))
  val commandStid = Output(UInt(8.W))
  val tDeallocSeqWrap = Output(Bool())
  val tDeallocSeqValue = Output(UInt(3.W))
  val uDeallocSeqWrap = Output(Bool())
  val uDeallocSeqValue = Output(UInt(3.W))
  val tValidMask = Output(UInt(8.W))
  val uValidMask = Output(UInt(8.W))
  val tRetiredMask = Output(UInt(8.W))
  val uRetiredMask = Output(UInt(8.W))
}

class ScalarTURenameBridgeIdentityProbe extends Module {
  private val p = InterfaceParams(robEntries = 8, commitWidth = 2)
  private val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
  private val mapQDepth = 8

  val io = IO(new ScalarTURenameBridgeIdentityProbeIO)

  private def zeroRobId(entries: Int): ROBID =
    0.U.asTypeOf(new ROBID(entries))

  val bridge = Module(new ScalarTURenameBridge(
    p = p,
    traceParams = trace,
    mapQDepth = mapQDepth,
    gprMapQDepth = 8,
    scalarStidCount = 2))

  bridge.io.in := 0.U.asTypeOf(bridge.io.in)
  bridge.io.in.valid := io.allocateValid
  bridge.io.in.peId := 0.U
  bridge.io.in.threadId := 0.U
  bridge.io.in.rid.valid := true.B
  bridge.io.in.rid.value := io.allocateRidValue
  bridge.io.in.bid.valid := true.B
  bridge.io.in.bid.value := io.allocateRidValue
  bridge.io.in.gid.valid := true.B
  bridge.io.in.gid.value := io.allocateRidValue
  bridge.io.in.dst(0).valid := io.allocateValid
  bridge.io.in.dst(0).kind := io.allocateKind
  bridge.io.activePeId := 0.U
  bridge.io.activeStid := 0.U
  bridge.io.gprQueryStid := 0.U
  bridge.io.outReady := true.B
  bridge.io.robAllocReady := true.B

  bridge.io.checkpointValid := false.B
  bridge.io.checkpointBid := zeroRobId(p.robEntries)
  bridge.io.checkpointStid := 0.U
  bridge.io.commitValid := false.B
  bridge.io.commitBid := zeroRobId(p.robEntries)
  bridge.io.commitBlockBid := 0.U
  bridge.io.commitStid := 0.U
  bridge.io.cleanup := 0.U.asTypeOf(bridge.io.cleanup)
  bridge.io.cleanupOrderValid := false.B
  bridge.io.cleanupOrder := 0.U
  bridge.io.robSource := 0.U.asTypeOf(bridge.io.robSource)
  bridge.io.lsuSource := 0.U.asTypeOf(bridge.io.lsuSource)

  bridge.io.tuRetireValid := io.retireValid
  bridge.io.tuRetireKind := io.retireKind
  bridge.io.tuRetireSeq.valid := true.B
  bridge.io.tuRetireSeq.wrap := io.retireSeqWrap
  bridge.io.tuRetireSeq.value := io.retireSeqValue
  bridge.io.tuRetireDealloc := io.retireDealloc
  bridge.io.tuRetirePeId := io.retirePeId
  bridge.io.tuRetireStid := io.retireStid
  bridge.io.tuLocalBlockCommitValid := false.B
  bridge.io.tuLocalBlockCommitBid := zeroRobId(p.robEntries)
  bridge.io.tuLocalBlockCommitStid := 0.U

  io.accepted := bridge.io.accepted
  io.inReady := bridge.io.inReady
  io.outValid := bridge.io.outValid
  io.tUsedEntries := bridge.io.tuTUsedEntries
  io.uUsedEntries := bridge.io.tuUUsedEntries
  io.commandValid := bridge.io.tuRetireCommandValid
  io.commandKind := bridge.io.tuRetireCommandKind
  io.commandSeqWrap := bridge.io.tuRetireCommandSeq.wrap
  io.commandSeqValue := bridge.io.tuRetireCommandSeq.value
  io.commandDealloc := bridge.io.tuRetireCommandDealloc
  io.commandPeId := bridge.io.tuRetireCommandPeId
  io.commandStid := bridge.io.tuRetireCommandStid
  io.tDeallocSeqWrap := bridge.io.tuRetireSelectedTDeallocSeq.wrap
  io.tDeallocSeqValue := bridge.io.tuRetireSelectedTDeallocSeq.value
  io.uDeallocSeqWrap := bridge.io.tuRetireSelectedUDeallocSeq.wrap
  io.uDeallocSeqValue := bridge.io.tuRetireSelectedUDeallocSeq.value
  io.tValidMask := bridge.io.tuRetireSelectedTValidMask
  io.uValidMask := bridge.io.tuRetireSelectedUValidMask
  io.tRetiredMask := bridge.io.tuRetireSelectedTRetiredMask
  io.uRetiredMask := bridge.io.tuRetireSelectedURetiredMask
}

object ScalarTURenameBridgeReference {
  def accepts(
      inputValid: Boolean,
      scalarReady: Boolean,
      tuReady: Boolean,
      robReady: Boolean,
      outReady: Boolean,
      localUnsupported: Boolean,
      splitStoreTuBypass: Boolean = false): Boolean =
    inputValid && scalarReady && (tuReady || splitStoreTuBypass) && robReady && outReady && !localUnsupported

  def scalarSourcePresentedToGpr(operandClass: String): Boolean =
    operandClass == "P"

  def preservesTUSource(operandClass: String): Boolean =
    operandClass == "T" || operandClass == "U"

  def tuSourcePresentedForSequenceLookup(
      operandClass: String,
      isStore: Boolean,
      storeSplitIntent: Boolean,
      isLoadStorePair: Boolean = false,
      cacheMaintainNoSplit: Boolean = false): Boolean =
    preservesTUSource(operandClass) &&
      !(isStore && storeSplitIntent && !isLoadStorePair && !cacheMaintainNoSplit)

  def localBlockCommitReady(
      externalCommitValid: Boolean,
      recoveryActive: Boolean,
      eventStid: Int = 0,
      localStid: Int = 0): Boolean =
    eventStid == localStid && !externalCommitValid && !recoveryActive

  def activeBankValid(activePe: Int, activeStid: Int, peCount: Int, stidCount: Int): Boolean =
    activePe >= 0 && activePe < peCount && activeStid >= 0 && activeStid < stidCount

  def activeTuBankPe(peId: Int): Int =
    peId
}

class ScalarTURenameBridgeSpec extends AnyFunSuite with ChiselSim {
  import ScalarTURenameBridgeReference._

  test("reference accepts scalar and T/U rename atomically") {
    assert(accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = true, outReady = true, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = false, robReady = true, outReady = true, localUnsupported = false))
    assert(accepts(inputValid = true, scalarReady = true, tuReady = false, robReady = true, outReady = true, localUnsupported = false, splitStoreTuBypass = true))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = false, outReady = true, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = true, outReady = false, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = true, outReady = true, localUnsupported = true))
  }

  test("reference sanitizes non-GPR operands away from scalar rename while preserving T/U overlay ownership") {
    assert(scalarSourcePresentedToGpr("P"))
    assert(!scalarSourcePresentedToGpr("T"))
    assert(!scalarSourcePresentedToGpr("U"))
    assert(preservesTUSource("T"))
    assert(preservesTUSource("U"))
    assert(!preservesTUSource("CArg"))
  }

  test("reference bypasses split-store local sources only for T/U sequence lookup") {
    assert(tuSourcePresentedForSequenceLookup("T", isStore = false, storeSplitIntent = false))
    assert(!tuSourcePresentedForSequenceLookup("T", isStore = true, storeSplitIntent = true))
    assert(!tuSourcePresentedForSequenceLookup("U", isStore = true, storeSplitIntent = true))
    assert(tuSourcePresentedForSequenceLookup("T", isStore = true, storeSplitIntent = false))
    assert(tuSourcePresentedForSequenceLookup("T", isStore = true, storeSplitIntent = true, isLoadStorePair = true))
    assert(tuSourcePresentedForSequenceLookup("T", isStore = true, storeSplitIntent = true, cacheMaintainNoSplit = true))
  }

  test("reference keeps local block commit behind external maintenance") {
    assert(localBlockCommitReady(externalCommitValid = false, recoveryActive = false))
    assert(!localBlockCommitReady(externalCommitValid = true, recoveryActive = false))
    assert(!localBlockCommitReady(externalCommitValid = false, recoveryActive = true))
    assert(!localBlockCommitReady(externalCommitValid = false, recoveryActive = false, eventStid = 1, localStid = 0))
  }

  test("reference routes active T/U bank selection from explicit PE/STID sidebands") {
    assert(activeTuBankPe(peId = 0) == 0)
    assert(activeTuBankPe(peId = 2) == 2)
    assert(activeBankValid(activePe = 0, activeStid = 1, peCount = 1, stidCount = 2))
    assert(!activeBankValid(activePe = 1, activeStid = 1, peCount = 1, stidCount = 2))
    assert(!activeBankValid(activePe = 0, activeStid = 2, peCount = 1, stidCount = 2))
  }

  test("sim forwards exact retire identity and distinct T/U queue state without side effects") {
    simulate(new ScalarTURenameBridgeIdentityProbe) { dut =>
      def idle(): Unit = {
        dut.io.allocateValid.poke(false.B)
        dut.io.allocateKind.poke(DestinationKind.None)
        dut.io.allocateRidValue.poke(0.U)
        dut.io.retireValid.poke(false.B)
        dut.io.retireKind.poke(DestinationKind.None)
        dut.io.retireSeqWrap.poke(false.B)
        dut.io.retireSeqValue.poke(0.U)
        dut.io.retireDealloc.poke(false.B)
        dut.io.retirePeId.poke(0.U)
        dut.io.retireStid.poke(0.U)
      }

      def allocate(kind: DestinationKind.Type, rid: Int): Unit = {
        idle()
        dut.io.allocateValid.poke(true.B)
        dut.io.allocateKind.poke(kind)
        dut.io.allocateRidValue.poke(rid.U)
        dut.io.inReady.expect(true.B)
        dut.io.accepted.expect(true.B)
        dut.io.outValid.expect(true.B)
        dut.clock.step()
      }

      def retire(kind: DestinationKind.Type, value: Int, dealloc: Boolean): Unit = {
        idle()
        dut.io.retireValid.poke(true.B)
        dut.io.retireKind.poke(kind)
        dut.io.retireSeqWrap.poke(false.B)
        dut.io.retireSeqValue.poke(value.U)
        dut.io.retireDealloc.poke(dealloc.B)
        dut.io.retirePeId.poke(0.U)
        dut.io.retireStid.poke(0.U)
        dut.clock.step()
      }

      def expectQueueState(): Unit = {
        dut.io.tDeallocSeqWrap.expect(false.B)
        dut.io.tDeallocSeqValue.expect(2.U)
        dut.io.uDeallocSeqWrap.expect(false.B)
        dut.io.uDeallocSeqValue.expect(1.U)
        dut.io.tValidMask.expect("h0c".U)
        dut.io.uValidMask.expect("h0e".U)
        dut.io.tRetiredMask.expect("h04".U)
        dut.io.uRetiredMask.expect("h08".U)
        dut.io.tUsedEntries.expect(2.U)
        dut.io.uUsedEntries.expect(3.U)
      }

      idle()
      dut.clock.step()

      (0 until 4).foreach(rid => allocate(DestinationKind.T, rid))
      (4 until 8).foreach(rid => allocate(DestinationKind.U, rid))
      retire(DestinationKind.T, value = 2, dealloc = false)
      retire(DestinationKind.T, value = 0, dealloc = true)
      retire(DestinationKind.T, value = 1, dealloc = true)
      retire(DestinationKind.U, value = 3, dealloc = false)
      retire(DestinationKind.U, value = 0, dealloc = true)
      idle()
      expectQueueState()

      dut.io.retireValid.poke(true.B)
      dut.io.retireKind.poke(DestinationKind.U)
      dut.io.retireSeqWrap.poke(true.B)
      dut.io.retireSeqValue.poke(5.U)
      dut.io.retireDealloc.poke(true.B)
      dut.io.retirePeId.poke("ha5".U)
      dut.io.retireStid.poke("h5a".U)
      dut.io.commandValid.expect(true.B)
      dut.io.commandKind.expect(DestinationKind.U)
      dut.io.commandSeqWrap.expect(true.B)
      dut.io.commandSeqValue.expect(5.U)
      dut.io.commandDealloc.expect(true.B)
      dut.io.commandPeId.expect("ha5".U)
      dut.io.commandStid.expect("h5a".U)
      expectQueueState()
      dut.clock.step()

      dut.io.retireKind.poke(DestinationKind.T)
      dut.io.retireSeqWrap.poke(false.B)
      dut.io.retireSeqValue.poke(6.U)
      dut.io.retireDealloc.poke(false.B)
      dut.io.retirePeId.poke("h3c".U)
      dut.io.retireStid.poke("hc3".U)
      dut.io.commandValid.expect(true.B)
      dut.io.commandKind.expect(DestinationKind.T)
      dut.io.commandSeqWrap.expect(false.B)
      dut.io.commandSeqValue.expect(6.U)
      dut.io.commandDealloc.expect(false.B)
      dut.io.commandPeId.expect("h3c".U)
      dut.io.commandStid.expect("hc3".U)
      expectQueueState()
      dut.clock.step()

      idle()
      dut.io.commandValid.expect(false.B)
      expectQueueState()
    }
  }

  test("IO exposes scalar, T/U rename, ROB allocation, and cleanup surfaces") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val io = new ScalarTURenameBridgeIO(p, trace, mapQDepth = 8, scalarStidCount = 2)

    assert(io.in.src.length == 3)
    assert(io.in.peId.getWidth == 8)
    assert(io.activePeId.getWidth == 8)
    assert(io.activeStid.getWidth == 8)
    assert(io.out.peId.getWidth == 8)
    assert(io.out.threadId.getWidth == 8)
    assert(io.out.dst.length == 1)
    assert(io.robAllocRow.identity.rid.getWidth == 32)
    assert(io.commitBlockBid.getWidth == 64)
    assert(io.tuSrc.length == 3)
    assert(io.tuActivePeInRange.getWidth == 1)
    assert(io.tuActiveStidInRange.getWidth == 1)
    assert(io.tuActiveBankValid.getWidth == 1)
    assert(io.tuActivePeOH.getWidth == 1)
    assert(io.tuActiveStidOH.getWidth == 2)
    assert(io.tuTSeq.value.getWidth == 3)
    assert(io.tuUSeq.value.getWidth == 3)
    assert(io.tuDstValid.getWidth == 1)
    assert(io.tuSourceUnderflowMask.getWidth == 3)
    assert(io.gprCommittedMapQCount.getWidth == 6)
    assert(io.gprReleasedPhysCount.getWidth == 7)
    assert(io.tuRetirePeId.getWidth == 8)
    assert(io.tuRetireStid.getWidth == 8)
    assert(io.tuRetireAccepted.getWidth == 1)
    assert(io.tuRetireReleaseMismatch.getWidth == 1)
    assert(io.tuRetirePeInRange.getWidth == 1)
    assert(io.tuRetireStidInRange.getWidth == 1)
    assert(io.tuRetireBankValid.getWidth == 1)
    assert(io.tuRetirePeOH.getWidth == 1)
    assert(io.tuRetireStidOH.getWidth == 2)
    assert(io.tuLocalBlockCommitBid.value.getWidth == 3)
    assert(io.tuLocalBlockCommitStid.getWidth == 8)
    assert(io.tuLocalBlockCommitReady.getWidth == 1)
    assert(io.tuLocalBlockCommitAccepted.getWidth == 1)
    assert(io.tuLocalBlockCommitStidMatch.getWidth == 1)
    assert(io.tuLocalBlockCommitBlockedByStid.getWidth == 1)
    assert(io.tuLocalBlockCommitBlockedByBankReady.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutStidInRange.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutBlockedByStidRange.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutBlockedByBankReady.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutTargetPeMask.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutReadyPeMask.getWidth == 1)
    assert(io.tuCleanupSelectedFlushSource.tSeq.value.getWidth == 3)
    assert(io.tuCleanupSourceConflict.getWidth == 1)
  }

  test("IO keeps scalar GPR mapQ depth independent of local T/U mapQ depth") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val io = new ScalarTURenameBridgeIO(p, trace, mapQDepth = 8, gprMapQDepth = 256, scalarStidCount = 2)

    assert(io.gprMapQFreeCount.getWidth == 9)
    assert(io.tuTSeq.value.getWidth == 3)
    assert(io.tuUSeq.value.getWidth == 3)
  }

  test("ScalarTURenameBridge elaborates as scalar GPR rename plus T/U cleanup composition owner") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarTURenameBridge(p = p, traceParams = trace, mapQDepth = 8, scalarStidCount = 2)
    )

    assert(sv.contains("module ScalarTURenameBridge"))
    assert(sv.contains("io_activePeId"))
    assert(sv.contains("io_activeStid"))
    assert(sv.contains("io_out_peId"))
    assert(sv.contains("module ScalarDecodeRenameBridge"))
    assert(sv.contains("module TULinkLocalBankArray"))
    assert(sv.contains("module TULinkRecoveryCleanupPath"))
    assert(sv.contains("module TULinkLocalBlockCommitFanout"))
    assert(sv.contains("module TULinkRename"))
    assert(sv.contains("io_tuTSeq_value"))
    assert(sv.contains("io_tuUSeq_value"))
    assert(sv.contains("io_tuActiveBankValid"))
    assert(sv.contains("io_tuActiveStidOH"))
    assert(sv.contains("io_tuDstValid"))
    assert(sv.contains("io_tuRetirePeId"))
    assert(sv.contains("io_tuRetireStid"))
    assert(sv.contains("io_tuRetireBankValid"))
    assert(sv.contains("io_tuRetireAccepted"))
    assert(sv.contains("io_tuRetireReleaseMismatch"))
    assert(sv.contains("io_tuLocalBlockCommitStid"))
    assert(sv.contains("io_tuLocalBlockCommitReady"))
    assert(sv.contains("io_tuLocalBlockCommitAccepted"))
    assert(sv.contains("io_tuLocalBlockCommitStidMatch"))
    assert(sv.contains("io_tuLocalBlockCommitBlockedByStid"))
    assert(sv.contains("io_tuLocalBlockCommitFanoutBlockedByBankReady"))
    assert(sv.contains("io_tuCleanupSourceConflict"))
  }
}
