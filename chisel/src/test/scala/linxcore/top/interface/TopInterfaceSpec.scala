package linxcore.top.interface

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import linxcore.params.{CoreParams, ParamProfiles}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters._

class InterfaceHoldProbeIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new FetchedPacket(p)))
  val out = Decoupled(new FetchedPacket(p))
}

class InterfaceHoldProbe(val p: CoreParams) extends Module {
  val io = IO(new InterfaceHoldProbeIO(p))

  val occupied = RegInit(false.B)
  val payload = Reg(new FetchedPacket(p))

  io.in.ready := !occupied || io.out.ready
  io.out.valid := occupied
  io.out.bits := payload

  val stalled = io.out.valid && !io.out.ready
  val previousStalled = RegNext(stalled, false.B)
  val previousPayload = RegEnable(io.out.bits.asUInt, stalled)
  when(previousStalled) {
    assert(io.out.valid)
    assert(io.out.bits.asUInt === previousPayload)
  }

  when(io.in.fire) {
    payload := io.in.bits
    occupied := true.B
  }.elsewhen(io.out.fire) {
    occupied := false.B
  }
}

class BoxIOElaborationProbeIO(val p: CoreParams) extends Bundle {
  val ifu = new IFUIO(p)
  val ctu = new CTUIO(p)
  val ooo = new OOOIO(p)
  val iex = new IEXIO(p)
  val lsu = new LSUIO(p)
  val dtu = new DTUIO(p)
  val top = new TOPIO(p)
}

class BoxIOElaborationProbe(val p: CoreParams) extends Module {
  val io = IO(new BoxIOElaborationProbeIO(p))
  io := DontCare
}

class InterfaceDirectionProbe(val p: CoreParams) extends RawModule {
  val iex = IO(new IEXIO(p))
  val top = IO(new TOPIO(p))

  require(DataMirror.directionOf(iex.ooo.aluDispatch.head.valid) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(iex.ooo.aluDispatch.head.bits.trap.valid) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(iex.ooo.aluDispatch.head.bits.trap.cause) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(
    iex.ooo.storeDispatch.head.bits.sta.trap.valid) == ActualDirection.Input)
  require(DataMirror.directionOf(
    iex.ooo.storeDispatch.head.bits.std.trap.valid) == ActualDirection.Input)
  require(DataMirror.directionOf(iex.ooo.aluDispatch.head.ready) ==
    ActualDirection.Output)
  require(DataMirror.directionOf(iex.ooo.robResolve.head.valid) ==
    ActualDirection.Output)
  require(DataMirror.directionOf(iex.ooo.robResolve.head.ready) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(iex.ooo.pcBufferReadAddress.head.valid) ==
    ActualDirection.Output)
  require(DataMirror.directionOf(
    iex.ooo.pcBufferReadAddress.head.stid) == ActualDirection.Output)
  require(DataMirror.directionOf(
    iex.ooo.pcBufferReadPcBase.head.valid) == ActualDirection.Input)
  require(DataMirror.directionOf(
    iex.ooo.pcBufferReadPcBase.head.bits) == ActualDirection.Input)
  require(DataMirror.directionOf(iex.lsu.loadAddress.head.valid) ==
    ActualDirection.Output)
  require(DataMirror.directionOf(iex.lsu.loadAddress.head.ready) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(iex.lsu.loadReissue.head.valid) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(iex.lsu.loadReissue.head.ready) ==
    ActualDirection.Output)
  require(DataMirror.directionOf(iex.cmdIssue.valid) == ActualDirection.Output)
  require(DataMirror.directionOf(iex.cmdIssue.ready) == ActualDirection.Input)
  require(DataMirror.directionOf(iex.branchResolve.valid) ==
    ActualDirection.Output)
  require(DataMirror.directionOf(iex.branchResolve.ready) ==
    ActualDirection.Input)
  require(DataMirror.directionOf(top.cmdIssue.valid) == ActualDirection.Output)
  require(DataMirror.directionOf(top.cmdIssue.ready) == ActualDirection.Input)
}

class TopInterfaceSpec extends AnyFunSuite {
  private val repoRoot = Paths.get("..").toAbsolutePath.normalize
  private val profiles = Seq(
    2 -> ParamProfiles.W2,
    4 -> ParamProfiles.W4,
    6 -> ParamProfiles.W6,
    8 -> ParamProfiles.W8)

  test("all principal profiles elaborate continuous-prefix front-end packets") {
    profiles.foreach { case (width, p) =>
      val fetched = new FetchedPacket(p)
      val d1 = new D1Packet(p)
      val commit = new CommitTxn(p)

      assert(fetched.count.getWidth == log2Ceil(width + 1))
      assert(fetched.entries.length == width)
      assert(fetched.entries.head.instruction.getWidth == 64)
      assert(fetched.entries.head.lengthBytes.getWidth == 4)
      assert(!fetched.elements.contains("validMask"))

      assert(d1.count.getWidth == log2Ceil(width + 1))
      assert(d1.entries.length == width)
      assert(!d1.elements.contains("validMask"))

      assert(commit.count.getWidth == log2Ceil(p.widths.retireWidth + 1))
      assert(commit.entries.length == p.widths.retireWidth)
      assert(!commit.elements.contains("validMask"))
    }
  }

  test("instruction ROB and memory identities keep every generation domain separate") {
    val p = ParamProfiles.W4
    val instruction = new InstructionIdentity(p)
    val rob = new RobIdentity(p)
    val memory = new MemoryIdentity(p)

    assert(instruction.instructionId.getWidth == p.instructionIdWidth)
    assert(instruction.epoch.getWidth == p.epochWidth)
    assert(rob.ridSlot.getWidth == log2Ceil(p.ooo.robGroupsPerStid))
    assert(rob.ridGeneration.getWidth == p.ridGenerationWidth)
    assert(rob.residentGeneration.getWidth == p.residentGenerationWidth)
    assert(rob.bid.getWidth == p.nativeBidWidth)
    assert(rob.brobGeneration.getWidth == p.brobGenerationWidth)
    assert(memory.transaction.value.getWidth == p.memoryTransactionIdWidth)
    assert(memory.transaction.generation.getWidth == p.memoryTransactionGenerationWidth)
    assert(memory.lsid.getWidth == p.lsidWidth)
    assert(memory.attemptGeneration.getWidth == p.memoryAttemptGenerationWidth)
  }

  test("native BID width follows the configured per-STID BROB identity count") {
    val base = ParamProfiles.W4
    val p64 = base.copy(ooo = base.ooo.copy(
      brobEntriesPerStid = 64,
      brobIdentityEntriesPerStid = 64))
    val p512 = base.copy(ooo = base.ooo.copy(
      brobEntriesPerStid = 512,
      brobIdentityEntriesPerStid = 512))

    assert(new RobIdentity(p64).bid.getWidth == 6)
    assert(new RobIdentity(p512).bid.getWidth == 9)
    assert(new RobIdentity(p64).brobGeneration.getWidth ==
      base.brobGenerationWidth)
    assert(new RobIdentity(p512).brobGeneration.getWidth ==
      base.brobGenerationWidth)
  }

  test("decoded and renamed uops preserve architectural and physical tag domains") {
    val p = ParamProfiles.W4
    val decoded = new DecodedUop(p)
    val renamed = new RenamedUop(p)

    assert(decoded.instruction.parent.instruction.getWidth == 64)
    assert(decoded.opcode.getWidth == p.opcodeWidth)
    assert(decoded.sources.length == p.maxSourceOperands)
    assert(decoded.destinations.length == p.maxDestinationOperands)
    assert(decoded.sources.head.atag.getWidth == p.archRegWidth)
    assert(renamed.sources.head.atag.getWidth == p.archRegWidth)
    assert(renamed.sources.head.ptag.getWidth == log2Ceil(p.ooo.gprPhysRegs))
    assert(renamed.sources.head.ttag.getWidth == log2Ceil(p.ooo.tPhysRegs))
    assert(renamed.sources.head.utag.getWidth == log2Ceil(p.ooo.uPhysRegs))
    assert(renamed.sources.head.tSeqIndex.getWidth ==
      log2Ceil(p.ooo.tuMapQDepthPerStid))
    assert(renamed.sources.head.tSeqGeneration.getWidth ==
      p.ooo.localSeqGenerationWidth)
    assert(renamed.sources.head.uSeqGeneration.getWidth ==
      p.ooo.localSeqGenerationWidth)
    assert(renamed.destinations.head.previousPtag.getWidth ==
      log2Ceil(p.ooo.gprPhysRegs))
  }

  test("decoded memory controls and dispatch order metadata are canonical logical payloads") {
    val p = ParamProfiles.W4
    val decoded = new DecodedUop(p)
    val memory = decoded.memory
    val dispatch = new DispatchTxn(p)
    val order = dispatch.memoryOrder

    assert(dispatch.elements.keySet == Set(
      "transactionId", "uop", "memoryOrder", "pcBufferIndexOffset", "trap"))
    assert(dispatch.trap.elements.keySet == Set("valid", "cause"))
    assert(dispatch.trap.cause.getWidth == p.trapCauseWidth)

    assert(memory.elements.keySet == Set(
      "valid", "isLoad", "isStore", "addressMode", "accessBytes",
      "signExtend", "offset", "indexMode", "indexShift",
      "addressSourceMask", "dataSourceMask", "writebackValid",
      "writebackPreIndex", "requestCount"))
    assert(memory.addressMode.getWidth == MemoryAddressMode.getWidth)
    assert(memory.accessBytes.getWidth == 4)
    assert(memory.offset.getWidth == p.pcWidth)
    assert(memory.indexMode.getWidth == MemoryIndexMode.getWidth)
    assert(memory.indexShift.getWidth == 5)
    assert(memory.addressSourceMask.getWidth == p.maxSourceOperands)
    assert(memory.dataSourceMask.getWidth == p.maxSourceOperands)
    assert(memory.requestCount.getWidth == order.requestCount.getWidth)

    assert(order.elements.keySet == Set(
      "requestCount", "firstLsid", "firstLid", "firstSid",
      "yostValid", "yostLsid", "yostSid",
      "yoldValid", "yoldLsid", "yoldLid"))
    assert(order.requestCount.getWidth ==
      log2Ceil(p.maxMemoryRequestsPerInstruction + 1))
    assert(order.firstLsid.getWidth == p.lsidWidth)
    assert(order.firstLid.getWidth == p.lsidWidth)
    assert(order.firstSid.getWidth == p.lsidWidth)
    assert(order.yostLsid.getWidth == p.lsidWidth)
    assert(order.yostSid.getWidth == p.lsidWidth)
    assert(order.yoldLsid.getWidth == p.lsidWidth)
    assert(order.yoldLid.getWidth == p.lsidWidth)
    assert(!order.elements.keySet.exists(name =>
      name.toLowerCase.contains("transaction") ||
        name.toLowerCase.contains("attempt") ||
        name.toLowerCase.contains("pipe") ||
        name.toLowerCase.contains("slot") ||
        name.toLowerCase.contains("lane")))

    val wider = p.copy(maxMemoryRequestsPerInstruction = 7)
    assert(new MemoryOrderMeta(wider).requestCount.getWidth == log2Ceil(7 + 1))
    assert(new DecodedMemoryControl(wider).requestCount.getWidth ==
      new MemoryOrderMeta(wider).requestCount.getWidth)
  }

  test("PC buffer index and offset metadata stays generation qualified across OOO") {
    val p = ParamProfiles.W4
    val indexOffset = new PcBufferIndexOffset(p)
    val readAddress = new PcBufferReadAddress(p)
    val prepared = new PcBufferD3Prepared(p)
    val d3 = new D3RenameLane(p)
    val dispatch = new DispatchTxn(p)
    val commit = new CommitEntry(p)

    assert(indexOffset.elements.keySet == Set(
      "valid", "pcBufferIndex", "pcOffset", "allocationEpoch"))
    assert(indexOffset.pcBufferIndex.getWidth ==
      log2Ceil(p.ooo.pcBufferEntries))
    assert(indexOffset.pcOffset.getWidth == p.ooo.pcOffsetWidth)
    assert(indexOffset.allocationEpoch.getWidth ==
      p.ooo.pcAllocationEpochWidth)

    assert(readAddress.elements.keySet == Set(
      "valid", "stid", "pcBufferIndex", "allocationEpoch"))
    assert(readAddress.stid.getWidth == math.max(1, log2Ceil(p.ooo.stidCount)))
    assert(readAddress.pcBufferIndex.getWidth ==
      indexOffset.pcBufferIndex.getWidth)
    assert(readAddress.allocationEpoch.getWidth ==
      indexOffset.allocationEpoch.getWidth)

    assert(prepared.count.getWidth ==
      log2Ceil(p.ooo.d3PrefixWidth + 1))
    assert(prepared.lanes.length == p.ooo.d3PrefixWidth)
    assert(prepared.lanes.head.getClass == indexOffset.getClass)
    assert(d3.pcBufferIndexOffset.getClass == indexOffset.getClass)
    assert(dispatch.pcBufferIndexOffset.getClass == indexOffset.getClass)
    assert(commit.pcBufferIndexOffset.getClass == indexOffset.getClass)
    assert(commit.robGroupLast.getWidth == 1)
  }

  test("resolve load lifecycle system and CMD transactions use exact canonical identities") {
    val base = ParamProfiles.W4
    val p = base.copy(
      ooo = base.ooo.copy(robGroupsPerStid = 32),
      lsu = base.lsu.copy(
        loadPipes = 8,
        loadQueueEntries = 16,
        loadReturnQueueEntries = 8),
      lsidWidth = 37,
      transactionIdWidth = 29,
      ridGenerationWidth = 7,
      residentGenerationWidth = 9,
      memoryTransactionIdWidth = 41,
      memoryTransactionGenerationWidth = 11,
      memoryAttemptGenerationWidth = 13)
    val resolve = new RobResolveTxn(p)
    val issue = new LoadIssueTxn(p)
    val reissue = new LoadReissueTxn(p)
    val repick = new LoadRepickTxn(p)
    val cancel = new LoadCancelTxn(p)
    val noflush = new RobNoflushTxn(p)
    val noflushReady = new RobNoflushReadyTxn(p)
    val system = new SystemIssueTxn(p)
    val cmd = new CmdIssueTxn(p)

    val expectedRobFields = Set(
      "peId", "stid", "ridSlot", "ridGeneration", "memberIndex",
      "residentGeneration", "bid", "brobGeneration")
    val expectedMemoryFields = Set(
      "rob", "transaction", "lsid", "attemptGeneration", "pipeId")

    assert(resolve.elements.keySet == Set(
      "transactionId", "rob", "destinationValid", "destinationIndex",
      "value", "trap"))
    assert(resolve.rob.elements.keySet == expectedRobFields)
    assert(resolve.destinationIndex.getWidth ==
      InterfaceWidth.index(p.maxDestinationOperands))
    assert(resolve.value.getWidth == p.dataWidth)

    def checkTransition(current: MemoryIdentity, next: MemoryIdentity): Unit = {
      Seq(current, next).foreach { identity =>
        assert(identity.elements.keySet == expectedMemoryFields)
        assert(identity.rob.elements.keySet == expectedRobFields)
        assert(identity.transaction.elements.keySet == Set("value", "generation"))
        assert(identity.rob.ridSlot.getWidth == p.ooo.ridSlotWidth)
        assert(identity.rob.ridGeneration.getWidth == 7)
        assert(identity.rob.residentGeneration.getWidth == 9)
        assert(identity.transaction.value.getWidth == 41)
        assert(identity.transaction.generation.getWidth == 11)
        assert(identity.lsid.getWidth == 37)
        assert(identity.attemptGeneration.getWidth == 13)
        assert(identity.pipeId.getWidth == 3)
      }
    }
    assert(issue.elements.keySet == Set(
      "identity", "pc", "address", "sizeBytes", "signed", "destination",
      "destinationRelativeIndex", "youngestStoreValid", "youngestStoreLsid",
      "youngestStoreId"))
    checkTransition(issue.identity, issue.identity)
    checkTransition(reissue.currentIdentity, reissue.nextIdentity)
    checkTransition(repick.currentIdentity, repick.nextIdentity)
    val expectedStructuralWaitFields = Set(
      "valid", "storeIndex", "storeIdValid", "storeIdValue", "storeIdWrap",
      "storeLsidValid", "storeLsidValue", "storeLsidWrap",
      "storeLsidFullValid", "storeLsidFull", "pc")
    assert(reissue.elements.keySet == Set(
      "allocationId", "currentIdentity", "nextIdentity", "address",
      "structural", "waitStore"))
    assert(repick.elements.keySet == Set(
      "allocationId", "currentIdentity", "nextIdentity", "structural",
      "waitStore"))
    assert(reissue.waitStore.elements.keySet == expectedStructuralWaitFields)
    assert(repick.waitStore.elements.keySet == expectedStructuralWaitFields)
    Seq(reissue.waitStore, repick.waitStore).foreach { waitStore =>
      assert(waitStore.storeIndex.getWidth ==
        InterfaceWidth.index(p.lsu.storeQueueEntries))
      assert(waitStore.storeIdValue.getWidth == p.ooo.ridSlotWidth)
      assert(waitStore.storeLsidValue.getWidth == p.ooo.ridSlotWidth)
      assert(waitStore.storeLsidFull.getWidth == p.lsidWidth)
      assert(waitStore.pc.getWidth == p.pcWidth)
    }
    assert(repick.allocationId.value.getWidth == 41)
    assert(repick.allocationId.generation.getWidth == 11)
    assert(reissue.address.getWidth == p.physicalAddressWidth)
    assert(cancel.elements.keySet == Set("currentIdentity"))
    assert(cancel.currentIdentity.elements.keySet == expectedMemoryFields)
    checkTransition(cancel.currentIdentity, cancel.currentIdentity)

    Seq(noflush, noflushReady, system, cmd).foreach { transaction =>
      assert(transaction.elements.contains("instruction"))
      assert(transaction.elements.contains("rob"))
    }
    assert(noflush.elements.keySet == Set("transactionId", "instruction", "rob"))
    assert(noflushReady.elements.keySet ==
      Set("transactionId", "instruction", "rob"))
    assert(system.opcode.getWidth == p.opcodeWidth)
    assert(system.immediate.getWidth == p.dataWidth)
    assert(cmd.opcode.getWidth == p.opcodeWidth)
    assert(cmd.sourceValid.getWidth == p.maxSourceOperands)
    assert(cmd.sourceValues.length == p.maxSourceOperands)
    assert(cmd.sourceValues.forall(_.getWidth == p.dataWidth))
  }

  test("the public OOO D1 D2 slice contract lives with the TOP interfaces") {
    profiles.foreach { case (width, p) =>
      val slice = new OOOD1D2IO(p)
      val rename = new RENUD2D3IO(p)
      assert(slice.fromCtu.bits.getClass == new D1Packet(p).getClass)
      assert(slice.d2.bits.entries.length == width)
      assert(slice.d2.bits.groups.length == width)
      assert(rename.toD3.bits.entries.length == width)
      assert(rename.toD3.bits.groups.length == width)
      assert(slice.d2.bits.count.getWidth == log2Ceil(width + 1))
      assert(rename.toD3.bits.count.getWidth == log2Ceil(width + 1))
      assert(slice.d2.bits.entries.head.uop.rob.ridGeneration.getWidth ==
        p.ridGenerationWidth)
      assert(rename.toD3.bits.entries.head.history.head.pGeneration.getWidth ==
        p.ooo.gprTagGenerationWidth)
      assert(rename.toD3.bits.entries.head.history.head.previousPGeneration.getWidth ==
        p.ooo.gprTagGenerationWidth)
      assert(rename.toD3.bits.entries.head.history.head.pMapQIndex.getWidth ==
        log2Ceil(p.ooo.gprMapQDepthPerStid))
      assert(rename.toD3.bits.entries.head.history.head.pMapQGeneration.getWidth ==
        p.ooo.gprTagGenerationWidth)
      assert(rename.toD3.bits.entries.head.history.head.tMapQIndex.getWidth ==
        log2Ceil(p.ooo.tuMapQDepthPerStid))
      assert(rename.toD3.bits.entries.head.history.head.tMapQGeneration.getWidth ==
        p.ooo.localSeqGenerationWidth)
      assert(rename.toD3.bits.entries.head.history.head.tGeneration.getWidth ==
        p.ooo.localSeqGenerationWidth)
      assert(rename.toD3.bits.entries.head.uop.destinations.head.previousPGeneration.getWidth ==
        p.ooo.gprTagGenerationWidth)
      assert(rename.toD3.bits.entries.head.tSeqBefore.generation.getWidth ==
        p.ooo.localSeqGenerationWidth)
      assert(rename.toD3.bits.entries.head.tSeqBefore.tag.getWidth ==
        log2Ceil(p.ooo.tuMapQDepthPerStid))
      assert(slice.ridTailGeneration.head.getWidth == p.ridGenerationWidth)
      assert(!slice.d2.bits.elements.contains("validMask"))
      assert(!rename.toD3.bits.elements.contains("validMask"))
    }
  }

  test("OOO IEX and LSU boundaries preserve independent resource channels") {
    val p = ParamProfiles.W4
    val oooIex = new OOOIEXIO(p)
    val iexLsu = new IEXLSUIO(p)

    assert(oooIex.aluDispatch.length == p.iex.aluPipes)
    assert(oooIex.bruDispatch.length == p.iex.bruPipes)
    assert(oooIex.aguDispatch.length == p.iex.aguPipes)
    assert(oooIex.storeDispatch.length == p.iex.stdPipes)
    assert(oooIex.storeDispatch.head.bits.sta.getClass ==
      oooIex.storeDispatch.head.bits.std.getClass)
    assert(oooIex.systemDispatch.length == p.iex.systemMulticycleQueues)
    assert(oooIex.cmdDispatch.length == p.iex.cmdIssueQueues)
    assert(oooIex.cmdDispatch.head.bits.getClass !=
      oooIex.systemDispatch.head.bits.getClass ||
      (oooIex.cmdDispatch ne oooIex.systemDispatch))

    assert(oooIex.robNoflush.bits.getClass == new RobNoflushTxn(p).getClass)
    assert(oooIex.robNoflushReady.bits.getClass ==
      new RobNoflushReadyTxn(p).getClass)
    assert(oooIex.robResolve.length == p.widths.issueWidth)
    assert(oooIex.robResolve.head.bits.getClass == new RobResolveTxn(p).getClass)
    assert(oooIex.systemIssue.length == p.iex.systemMulticycleQueues)
    assert(oooIex.systemIssue.head.bits.getClass == new SystemIssueTxn(p).getClass)
    assert(oooIex.pcBufferReadAddress.length == p.ooo.pcReadPorts)
    assert(oooIex.pcBufferReadAddress.head.getClass ==
      new PcBufferReadAddress(p).getClass)
    assert(oooIex.pcBufferReadPcBase.length == p.ooo.pcReadPorts)
    assert(oooIex.pcBufferReadPcBase.head.bits.getWidth == p.pcWidth)

    assert(iexLsu.loadAddress.length == 2)
    assert(iexLsu.storeAddress.length == 2)
    assert(iexLsu.storeData.length == 2)
    assert(iexLsu.loadResult.length == 2)
    assert(iexLsu.loadAddress.head.bits.getClass == new LoadIssueTxn(p).getClass)
    assert(iexLsu.loadReissue.length == 2)
    assert(iexLsu.loadRepick.length == 2)
    assert(iexLsu.loadCancel.length == 2)
    assert(iexLsu.loadReissue.head.bits.getClass == new LoadReissueTxn(p).getClass)
    assert(iexLsu.loadRepick.head.bits.getClass == new LoadRepickTxn(p).getClass)
    assert(iexLsu.loadCancel.head.bits.getClass == new LoadCancelTxn(p).getClass)
  }

  test("box IOs share typed payloads and expose prepare then apply recovery") {
    val p = ParamProfiles.W4
    val ifu = new IFUIO(p)
    val ctu = new CTUIO(p)
    val ooo = new OOOIO(p)
    val iex = new IEXIO(p)
    val lsu = new LSUIO(p)
    val dtu = new DTUIO(p)
    val top = new TOPIO(p)

    assert(ifu.toCtu.bits.getClass == ctu.fromIfu.bits.getClass)
    assert(ctu.toOoo.bits.getClass == ooo.fromCtu.bits.getClass)
    assert(ooo.iex.aluDispatch.head.bits.getClass ==
      iex.ooo.aluDispatch.head.bits.getClass)
    assert(iex.lsu.loadAddress.head.bits.getClass ==
      lsu.iex.loadAddress.head.bits.getClass)
    assert(iex.cmdIssue.bits.getClass == top.cmdIssue.bits.getClass)
    assert(ooo.storeCommit.bits.getClass == lsu.storeCommit.bits.getClass)
    assert(!top.elements.contains("storeCommit"))
    assert(!top.elements.contains("storeClassify"))

    Seq(
      ifu.recovery,
      ctu.recovery,
      iex.ooo.recovery,
      lsu.recovery).foreach { recovery =>
      assert(recovery.prepare.bits.phase.getWidth > 0)
      assert(recovery.prepared.bits.transactionId.getWidth == p.transactionIdWidth)
      assert(recovery.apply.bits.transactionId.getWidth == p.transactionIdWidth)
      assert(recovery.abort.bits.transactionId.getWidth == p.transactionIdWidth)
    }

    assert(dtu.traceIn.bits.entries.length == p.dtu.traceWidth)
    assert(top.instructionMemoryRequest.bits.identity.generation.getWidth ==
      p.memoryTransactionGenerationWidth)
    assert(top.dataMemoryResponse.head.bits.identity.generation.getWidth ==
      p.memoryTransactionGenerationWidth)
    assert(top.dataMemoryRequest.length ==
      p.lsu.loadPipes + p.lsu.storePipes)
    assert(top.dataMemoryResponse.length == top.dataMemoryRequest.length)
    assert(top.instructionMemoryRequest.bits.size.getWidth == MemorySize.getWidth)
    assert(MemorySize.Bytes64.litValue == 6)
  }

  test("a retained decoupled interface payload remains stable under backpressure") {
    val p = ParamProfiles.W4
    val sv = ChiselStage.emitSystemVerilog(new InterfaceHoldProbe(p))

    assert(sv.contains("module InterfaceHoldProbe"))
    assert(sv.contains("previousPayload"))
    assert(sv.contains("Assertion failed"))
  }

  test("all box IO aggregates elaborate with their declared directions") {
    profiles.foreach { case (width, p) =>
      val chirrtl = ChiselStage.emitCHIRRTL(new BoxIOElaborationProbe(p))
      val directionChirrtl = ChiselStage.emitCHIRRTL(new InterfaceDirectionProbe(p))
      assert(chirrtl.contains("module BoxIOElaborationProbe"))
      assert(directionChirrtl.contains("module InterfaceDirectionProbe"))
      assert(chirrtl.contains(s"UInt<${p.instructionWidth}>"))
      assert(width == p.widths.decodeWidth)
    }
  }

  test("canonical public transaction names displace old transaction names") {
    val scalaRoots = Seq(
      repoRoot.resolve("chisel/src/main"),
      repoRoot.resolve("chisel/src/test"))
    val sources = scalaRoots.flatMap { root =>
      val paths = Files.walk(root)
      try {
        paths.iterator.asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
          .map(path => Files.readString(path))
          .toSeq
      } finally {
        paths.close()
      }
    }
      .mkString("\n")

    assert(!sources.contains("Completion" + "Txn"))
    assert(!sources.contains("Load" + "RequestTxn"))
  }
}
