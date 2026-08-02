package linxcore.top.interface

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import linxcore.params.{CoreParams, ParamProfiles}
import org.scalatest.funsuite.AnyFunSuite

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

  test("native BID width follows the configured per-STID BROB slot count") {
    val base = ParamProfiles.W4
    val p64 = base.copy(ooo = base.ooo.copy(brobEntriesPerStid = 64))
    val p512 = base.copy(ooo = base.ooo.copy(brobEntriesPerStid = 512))

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
    val order = new DispatchTxn(p).memoryOrder

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
      "requestCount", "firstLsid", "firstTypeId",
      "youngestStoreValid", "youngestStoreLsid", "youngestStoreId"))
    assert(order.requestCount.getWidth ==
      log2Ceil(p.maxMemoryRequestsPerInstruction + 1))
    assert(order.firstLsid.getWidth == p.lsidWidth)
    assert(order.firstTypeId.getWidth == p.lsidWidth)
    assert(order.youngestStoreLsid.getWidth == p.lsidWidth)
    assert(order.youngestStoreId.getWidth == p.lsidWidth)
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

  test("resolve load lifecycle system and CMD transactions use exact canonical identities") {
    val p = ParamProfiles.W4
    val resolve = new RobResolveTxn(p)
    val reissue = new LoadReissueTxn(p)
    val repick = new LoadRepickTxn(p)
    val cancel = new LoadCancelTxn(p)
    val noflush = new RobNoflushTxn(p)
    val system = new SystemIssueTxn(p)
    val cmd = new CmdIssueTxn(p)

    assert(resolve.rob.getClass == new RobIdentity(p).getClass)
    assert(resolve.destinationIndex.getWidth ==
      InterfaceWidth.index(p.maxDestinationOperands))
    assert(resolve.value.getWidth == p.dataWidth)

    def checkTransition(current: MemoryIdentity, next: MemoryIdentity): Unit = {
      assert(current.getClass == new MemoryIdentity(p).getClass)
      assert(next.getClass == new MemoryIdentity(p).getClass)
      assert(current.rob.getClass == next.rob.getClass)
      assert(current.transaction.getClass == next.transaction.getClass)
      assert(current.lsid.getWidth == p.lsidWidth)
      assert(next.attemptGeneration.getWidth ==
        p.memoryAttemptGenerationWidth)
    }
    checkTransition(reissue.currentIdentity, reissue.nextIdentity)
    checkTransition(repick.currentIdentity, repick.nextIdentity)
    assert(reissue.address.getWidth == p.physicalAddressWidth)
    assert(cancel.elements.keySet == Set("currentIdentity"))
    assert(cancel.currentIdentity.getClass == new MemoryIdentity(p).getClass)

    Seq(noflush, system, cmd).foreach { transaction =>
      assert(transaction.elements.contains("instruction"))
      assert(transaction.elements.contains("rob"))
    }
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
    assert(oooIex.robResolve.length == p.widths.issueWidth)
    assert(oooIex.robResolve.head.bits.getClass == new RobResolveTxn(p).getClass)
    assert(oooIex.systemIssue.length == p.iex.systemMulticycleQueues)
    assert(oooIex.systemIssue.head.bits.getClass == new SystemIssueTxn(p).getClass)

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
    assert(top.dataMemoryResponse.bits.identity.generation.getWidth ==
      p.memoryTransactionGenerationWidth)
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
      assert(chirrtl.contains("module BoxIOElaborationProbe"))
      assert(chirrtl.contains(s"UInt<${p.instructionWidth}>"))
      assert(width == p.widths.decodeWidth)
    }
  }

  test("canonical public transaction names displace the legacy names") {
    val interfaceRoot = repoRoot.resolve(
      "chisel/src/main/scala/linxcore/top/interface")
    val sources = Seq("OOOIEX.scala", "IEXLSU.scala", "EmitInterfaceManifest.scala")
      .map(name => Files.readString(interfaceRoot.resolve(name)))
      .mkString("\n")

    assert(!sources.contains("Completion" + "Txn"))
    assert(!sources.contains("Load" + "RequestTxn"))
  }
}
