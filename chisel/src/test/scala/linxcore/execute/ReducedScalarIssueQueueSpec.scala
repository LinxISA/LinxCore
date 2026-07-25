package linxcore.execute

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Enum, is, switch}
import linxcore.common.{BoundaryKind, DestinationKind, DispatchTarget, InterfaceParams, OperandClass, RenamedDestination, RenamedUop}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.sys.process._

class ReducedScalarIssueQueueSameBankSpOrderProbeIO(val p: InterfaceParams) extends Bundle {
  val done = Output(Bool())
  val failure = Output(Bool())
  val firstIssueRid = Output(UInt(p.robIndexWidth.W))
  val secondIssueRid = Output(UInt(p.robIndexWidth.W))
}

class ReducedScalarIssueQueueSameBankSpOrderProbe extends Module {
  val p = InterfaceParams(robEntries = 16)
  val io = IO(new ReducedScalarIssueQueueSameBankSpOrderProbeIO(p))

  val queue = Module(new ReducedScalarIssueQueue(p, depth = 4, stidCount = 1))
  val cycle = RegInit(0.U(8.W))
  cycle := cycle + 1.U

  val producerReady = RegInit(false.B)
  val olderIssueSeen = RegInit(false.B)
  val youngerIssueSeen = RegInit(false.B)
  val firstIssueRid = RegInit(0.U(p.robIndexWidth.W))
  val secondIssueRid = RegInit(0.U(p.robIndexWidth.W))
  val failure = RegInit(false.B)

  val Seq(
    sEnqueueOlder,
    sEnqueueYounger,
    sHoldOlderBlocked,
    sWakeOlder,
    sWaitOlder,
    sWaitYounger,
    sDone) = Enum(7)
  val state = RegInit(sEnqueueOlder)

  def uop(ridValue: Int, opcode: Int, src0Valid: Boolean, dstSp: Boolean): linxcore.common.RenamedUop = {
    val row = Wire(new linxcore.common.RenamedUop(p))
    row := 0.U.asTypeOf(row)
    row.valid := true.B
    row.threadId := 0.U
    row.pc := (0x10000L + ridValue).U
    row.opcode := opcode.U(p.opcodeWidth.W)
    row.dispatchTarget := DispatchTarget.Alu
    row.bid.valid := true.B
    row.bid.wrap := false.B
    row.bid.value := 0.U
    row.rid.valid := true.B
    row.rid.wrap := false.B
    row.rid.value := ridValue.U
    row.src(0).valid := src0Valid.B
    row.src(0).operandClass := OperandClass.P
    row.src(0).archTag := 11.U
    row.src(0).relTag := 11.U
    row.src(0).physTag := 9.U
    row.src(1).valid := true.B
    row.src(1).operandClass := OperandClass.P
    row.src(1).archTag := 1.U
    row.src(1).relTag := 1.U
    row.src(1).physTag := 1.U
    row.dst(0).valid := dstSp.B
    row.dst(0).kind := Mux(dstSp.B, DestinationKind.Gpr, DestinationKind.None)
    row.dst(0).archTag := 1.U
    row.dst(0).relTag := 1.U
    row.dst(0).physTag := 2.U
    row
  }

  val older = uop(
    ridValue = 10,
    opcode = FrontendOpcodeDecodeTable.OP_HL_SDI_PR,
    src0Valid = true,
    dstSp = true)
  val younger = uop(
    ridValue = 11,
    opcode = FrontendOpcodeDecodeTable.OP_FENTRY,
    src0Valid = false,
    dstSp = true)

  queue.io.inValid := state === sEnqueueOlder || state === sEnqueueYounger
  queue.io.in := Mux(state === sEnqueueOlder, older, younger)
  queue.io.flushValid := false.B
  queue.io.releaseValid := queue.io.issueFire
  queue.io.releaseBid.valid := true.B
  queue.io.releaseBid.wrap := false.B
  queue.io.releaseBid.value := 0.U
  queue.io.releaseRid.valid := queue.io.issueFire
  queue.io.releaseRid.wrap := false.B
  queue.io.releaseRid.value := queue.io.issueUop.rid.value
  queue.io.releaseStid := 0.U
  queue.io.secondaryReleaseValid := false.B
  queue.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  queue.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  queue.io.secondaryReleaseStid := 0.U
  queue.io.tertiaryReleaseValid := false.B
  queue.io.tertiaryReleaseBid := ROBID.disabled(p.robEntries)
  queue.io.tertiaryReleaseRid := ROBID.disabled(p.robEntries)
  queue.io.tertiaryReleaseStid := 0.U
  queue.io.scalarSpHeadValidByStid(0) := true.B
  queue.io.scalarSpHeadBidByStid(0).valid := true.B
  queue.io.scalarSpHeadBidByStid(0).wrap := false.B
  queue.io.scalarSpHeadBidByStid(0).value := 0.U
  queue.io.scalarSpHeadRidByStid(0).valid := true.B
  queue.io.scalarSpHeadRidByStid(0).wrap := false.B
  queue.io.scalarSpHeadRidByStid(0).value := Mux(olderIssueSeen, 11.U, 10.U)
  queue.io.readyMask := Mux(producerReady, 1.U << 9.U, 0.U)
  queue.io.pWakeupValid := state === sWakeOlder
  queue.io.pWakeupTag := 9.U
  queue.io.localTReadyMask := 0.U
  queue.io.localUReadyMask := 0.U
  queue.io.readGrant := true.B
  queue.io.issueReady := true.B
  for (lane <- 0 until 3) {
    queue.io.readData(lane) := (0x200 + lane).U
  }

  when(queue.io.readAttemptValid && !olderIssueSeen && queue.io.readUop.rid.value === 11.U) {
    failure := true.B
  }
  when(queue.io.issueFire) {
    when(!olderIssueSeen && queue.io.issueUop.rid.value =/= 10.U) {
      failure := true.B
    }
    when(olderIssueSeen && !youngerIssueSeen && queue.io.issueUop.rid.value =/= 11.U) {
      failure := true.B
    }
    when(!olderIssueSeen) {
      olderIssueSeen := true.B
      firstIssueRid := queue.io.issueUop.rid.value
    }.elsewhen(!youngerIssueSeen) {
      youngerIssueSeen := true.B
      secondIssueRid := queue.io.issueUop.rid.value
    }
  }
  when(cycle > 60.U && !youngerIssueSeen) {
    failure := true.B
  }

  switch(state) {
    is(sEnqueueOlder) {
      when(queue.io.enqueueFire) {
        state := sEnqueueYounger
      }
    }
    is(sEnqueueYounger) {
      when(queue.io.enqueueFire) {
        state := sHoldOlderBlocked
      }
    }
    is(sHoldOlderBlocked) {
      when(cycle > 8.U) {
        state := sWakeOlder
      }
    }
    is(sWakeOlder) {
      producerReady := true.B
      state := sWaitOlder
    }
    is(sWaitOlder) {
      when(olderIssueSeen) {
        state := sWaitYounger
      }
    }
    is(sWaitYounger) {
      when(youngerIssueSeen) {
        state := sDone
      }
    }
  }

  io.done := state === sDone
  io.failure := failure
  io.firstIssueRid := firstIssueRid
  io.secondIssueRid := secondIssueRid
}

class ReducedScalarIssueQueueHeadIdentityProbeIO(val p: InterfaceParams) extends Bundle {
  val done = Output(Bool())
  val failure = Output(Bool())
}

class ReducedScalarIssueQueueHeadIdentityProbe extends Module {
  val p = InterfaceParams(robEntries = 16)
  val io = IO(new ReducedScalarIssueQueueHeadIdentityProbeIO(p))

  val queue = Module(new ReducedScalarIssueQueue(p, depth = 4, stidCount = 1))
  val cycle = RegInit(0.U(8.W))
  val observedEmpty = RegInit(false.B)
  val observedHead = RegInit(false.B)
  val failure = RegInit(false.B)
  cycle := cycle + 1.U

  val row = Wire(new linxcore.common.RenamedUop(p))
  row := 0.U.asTypeOf(row)
  row.valid := true.B
  row.threadId := 5.U
  row.pc := "h1234".U
  row.opcode := FrontendOpcodeDecodeTable.OP_ADDI.U(p.opcodeWidth.W)
  row.dispatchTarget := DispatchTarget.Alu
  row.bid.valid := true.B
  row.bid.wrap := true.B
  row.bid.value := 6.U
  row.rid.valid := true.B
  row.rid.wrap := false.B
  row.rid.value := 7.U

  queue.io.inValid := cycle === 1.U
  queue.io.in := row
  queue.io.flushValid := false.B
  queue.io.releaseValid := false.B
  queue.io.releaseBid := ROBID.disabled(p.robEntries)
  queue.io.releaseRid := ROBID.disabled(p.robEntries)
  queue.io.releaseStid := 0.U
  queue.io.secondaryReleaseValid := false.B
  queue.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  queue.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  queue.io.secondaryReleaseStid := 0.U
  queue.io.tertiaryReleaseValid := false.B
  queue.io.tertiaryReleaseBid := ROBID.disabled(p.robEntries)
  queue.io.tertiaryReleaseRid := ROBID.disabled(p.robEntries)
  queue.io.tertiaryReleaseStid := 0.U
  queue.io.scalarSpHeadValidByStid(0) := false.B
  queue.io.scalarSpHeadBidByStid(0) := ROBID.disabled(p.robEntries)
  queue.io.scalarSpHeadRidByStid(0) := ROBID.disabled(p.robEntries)
  queue.io.readyMask := 0.U
  queue.io.pWakeupValid := false.B
  queue.io.pWakeupTag := 0.U
  queue.io.localTReadyMask := 0.U
  queue.io.localUReadyMask := 0.U
  queue.io.readGrant := false.B
  queue.io.issueReady := false.B
  for (lane <- 0 until 3) {
    queue.io.readData(lane) := 0.U
  }

  when(cycle === 0.U) {
    observedEmpty := !queue.io.headValid &&
      queue.io.headStid === 0.U &&
      !queue.io.headBid.valid &&
      !queue.io.headBid.wrap &&
      queue.io.headBid.value === 0.U &&
      !queue.io.headRid.valid &&
      !queue.io.headRid.wrap &&
      queue.io.headRid.value === 0.U
  }
  when(cycle >= 2.U && queue.io.headValid) {
    observedHead := queue.io.headStid === 5.U &&
      queue.io.headBid.valid &&
      queue.io.headBid.wrap &&
      queue.io.headBid.value === 6.U &&
      queue.io.headRid.valid &&
      !queue.io.headRid.wrap &&
      queue.io.headRid.value === 7.U
  }
  when(cycle > 20.U && !(observedEmpty && observedHead)) {
    failure := true.B
  }

  io.done := observedEmpty && observedHead
  io.failure := failure
}

class ReducedScalarIssueQueueSpec extends AnyFunSuite with ChiselSim {
  private val SimP = InterfaceParams(robEntries = 16)
  private val AllReadyMask = (BigInt(1) << (1 << SimP.physRegWidth)) - 1

  private def pokeRobId(id: ROBID, valid: Boolean, wrap: Boolean, value: Int): Unit = {
    id.valid.poke(valid.B)
    id.wrap.poke(wrap.B)
    id.value.poke(value.U)
  }

  private def pokeDst(dst: RenamedDestination, valid: Boolean, archTag: Int, physTag: Int): Unit = {
    dst.valid.poke(valid.B)
    dst.kind.poke(if (valid) DestinationKind.Gpr else DestinationKind.None)
    dst.archTag.poke(archTag.U)
    dst.relTag.poke(archTag.U)
    dst.physTag.poke(physTag.U)
    dst.oldPhysTag.poke(0.U)
  }

  private def pokeRow(
      row: RenamedUop,
      ridValue: Int,
      opcode: Int = FrontendOpcodeDecodeTable.OP_ADDI,
      dispatch: DispatchTarget.Type = DispatchTarget.Alu,
      srcTags: Seq[Int] = Seq.empty,
      dstTag: Option[Int] = Some(8),
      dstArch: Int = 8,
      isLoad: Boolean = false,
      isStore: Boolean = false,
      storeSplitIntent: Boolean = false,
      isLoadStorePair: Boolean = false,
      isStorePcr: Boolean = false,
      cacheMaintainNoSplit: Boolean = false,
      pairFirstDstTag: Option[Int] = None,
      fretStkContextValid: Boolean = false,
      fretStkConditionValid: Boolean = false,
      fretStkFallbackTargetValid: Boolean = false): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.peId.poke(0.U)
    row.threadId.poke(0.U)
    row.pc.poke((0x10000L + ridValue).U)
    row.opcode.poke(opcode.U(SimP.opcodeWidth.W))
    row.dispatchTarget.poke(dispatch)
    for (lane <- 0 until 3) {
      val valid = lane < srcTags.length
      val tag = if (valid) srcTags(lane) else 0
      row.src(lane).valid.poke(valid.B)
      row.src(lane).operandClass.poke(OperandClass.P)
      row.src(lane).archTag.poke(tag.U)
      row.src(lane).relTag.poke(tag.U)
      row.src(lane).physTag.poke(tag.U)
      row.src(lane).ready.poke(valid.B)
      row.src(lane).producer.poke(0.U)
      row.src(lane).literalValid.poke(false.B)
      row.src(lane).literal.poke(0.U)
    }
    pokeDst(row.dst(0), dstTag.isDefined, dstArch, dstTag.getOrElse(0))
    pokeDst(row.pairFirstDst, pairFirstDstTag.isDefined, dstArch + 1, pairFirstDstTag.getOrElse(0))
    row.imm.poke(0.U)
    row.immType.poke(0.U)
    row.immValid.poke(false.B)
    pokeRobId(row.bid, valid = true, wrap = false, value = 0)
    pokeRobId(row.gid, valid = true, wrap = false, value = 0)
    pokeRobId(row.rid, valid = true, wrap = false, value = ridValue)
    row.lsid.poke(0.U)
    row.isLoad.poke(isLoad.B)
    row.isStore.poke(isStore.B)
    row.storeSplitIntent.poke(storeSplitIntent.B)
    row.isLoadStorePair.poke(isLoadStorePair.B)
    row.isStorePcr.poke(isStorePcr.B)
    row.cacheMaintainNoSplit.poke(cacheMaintainNoSplit.B)
    row.spAccess.valid.poke(false.B)
    row.spAccess.read.poke(false.B)
    row.spAccess.write.poke(false.B)
    row.sob.poke(false.B)
    row.eob.poke(false.B)
    row.isLastInBlock.poke(false.B)
    row.boundaryKind.poke(BoundaryKind.Fall)
    row.boundaryTarget.poke(0.U)
    row.predTaken.poke(false.B)
    row.fretStkContextValid.poke(fretStkContextValid.B)
    row.fretStkConditionValid.poke(fretStkConditionValid.B)
    row.fretStkFallbackTargetValid.poke(fretStkFallbackTargetValid.B)
    row.fretStkFallbackTarget.poke(0.U)
    row.resolvedD2.poke(false.B)
    row.insnLen.poke(4.U)
    row.insnRaw.poke(0.U)
    row.checkpointId.poke(0.U)
    row.blockUid.poke(0.U)
    row.blockBidValid.poke(false.B)
    row.blockBid.poke(0.U)
    row.uid.uid.poke(ridValue.U)
    row.uid.parentUid.poke(0.U)
    row.uid.kind.poke(0.U)
    row.uid.fetchPacketUid.poke(0.U)
    row.uid.fetchSlot.poke(0.U)
    row.uid.replayDepth.poke(0.U)
    row.uid.templateKind.poke(0.U)
  }

  private def initQueue(dut: ReducedScalarIssueQueue): Unit = {
    dut.io.inValid.poke(false.B)
    pokeRow(dut.io.in, ridValue = 0, dstTag = None)
    dut.io.flushValid.poke(false.B)
    dut.io.releaseValid.poke(false.B)
    pokeRobId(dut.io.releaseBid, valid = false, wrap = false, value = 0)
    pokeRobId(dut.io.releaseRid, valid = false, wrap = false, value = 0)
    dut.io.releaseStid.poke(0.U)
    dut.io.secondaryReleaseValid.poke(false.B)
    pokeRobId(dut.io.secondaryReleaseBid, valid = false, wrap = false, value = 0)
    pokeRobId(dut.io.secondaryReleaseRid, valid = false, wrap = false, value = 0)
    dut.io.secondaryReleaseStid.poke(0.U)
    dut.io.tertiaryReleaseValid.poke(false.B)
    pokeRobId(dut.io.tertiaryReleaseBid, valid = false, wrap = false, value = 0)
    pokeRobId(dut.io.tertiaryReleaseRid, valid = false, wrap = false, value = 0)
    dut.io.tertiaryReleaseStid.poke(0.U)
    dut.io.scalarSpHeadValidByStid(0).poke(false.B)
    pokeRobId(dut.io.scalarSpHeadBidByStid(0), valid = false, wrap = false, value = 0)
    pokeRobId(dut.io.scalarSpHeadRidByStid(0), valid = false, wrap = false, value = 0)
    dut.io.readyMask.poke(AllReadyMask.U)
    dut.io.pWakeupValid.poke(false.B)
    dut.io.pWakeupTag.poke(0.U)
    dut.io.localTReadyMask.poke(0.U)
    dut.io.localUReadyMask.poke(0.U)
    dut.io.readGrant.poke(true.B)
    dut.io.issueReady.poke(true.B)
    for (lane <- 0 until 3) {
      dut.io.readData(lane).poke((0x600 + lane).U)
    }
  }

  private def setScalarSpHead(dut: ReducedScalarIssueQueue, valid: Boolean, ridValue: Int): Unit = {
    dut.io.scalarSpHeadValidByStid(0).poke(valid.B)
    pokeRobId(dut.io.scalarSpHeadBidByStid(0), valid = valid, wrap = false, value = 0)
    pokeRobId(dut.io.scalarSpHeadRidByStid(0), valid = valid, wrap = false, value = ridValue)
  }

  private def enqueue(dut: ReducedScalarIssueQueue, ridValue: Int, configure: RenamedUop => Unit): Unit = {
    dut.io.inValid.poke(true.B)
    configure(dut.io.in)
    dut.io.inReady.expect(true.B)
    dut.clock.step()
    dut.io.inValid.poke(false.B)
  }

  private def pokeRelease(
      valid: Bool,
      bid: ROBID,
      rid: ROBID,
      stid: UInt,
      fire: Boolean,
      ridValue: Int): Unit = {
    valid.poke(fire.B)
    pokeRobId(bid, valid = fire, wrap = false, value = 0)
    pokeRobId(rid, valid = fire, wrap = false, value = ridValue)
    stid.poke(0.U)
  }

  test("interface exposes enqueue, RF-read query, issue, and release handshakes") {
    val p = InterfaceParams()
    val io = new ReducedScalarIssueQueueIO(p, depth = 4, stidCount = 1)

    assert(io.in.getWidth > 0)
    assert(io.releaseBid.value.getWidth == p.robIndexWidth)
    assert(io.releaseRid.value.getWidth == p.robIndexWidth)
    assert(io.releaseStid.getWidth == p.threadIdWidth)
    assert(io.secondaryReleaseBid.value.getWidth == p.robIndexWidth)
    assert(io.secondaryReleaseRid.value.getWidth == p.robIndexWidth)
    assert(io.secondaryReleaseStid.getWidth == p.threadIdWidth)
    assert(io.tertiaryReleaseBid.value.getWidth == p.robIndexWidth)
    assert(io.tertiaryReleaseRid.value.getWidth == p.robIndexWidth)
    assert(io.tertiaryReleaseStid.getWidth == p.threadIdWidth)
    assert(io.scalarSpHeadValidByStid.length == 1)
    assert(io.scalarSpHeadBidByStid.head.value.getWidth == p.robIndexWidth)
    assert(io.scalarSpHeadRidByStid.head.value.getWidth == p.robIndexWidth)
    assert(io.readyMask.getWidth == 64)
    assert(io.pWakeupTag.getWidth == 6)
    assert(io.pWakeupMatchCount.getWidth == 4)
    assert(io.localTReadyMask.getWidth == 4)
    assert(io.localUReadyMask.getWidth == 4)
    assert(io.readValid.length == 3)
    assert(io.readTags.head.getWidth == 6)
    assert(io.readRelTag.head.getWidth == 6)
    assert(io.readData.head.getWidth == 64)
    assert(io.readGrant.getWidth == 1)
    assert(io.readAttemptValid.getWidth == 1)
    assert(io.readUop.getWidth == io.in.getWidth)
    assert(io.issueUop.getWidth == io.in.getWidth)
    assert(io.issueSrcData.head.getWidth == 64)
    assert(io.count.getWidth == 3)
    assert(io.issuedCount.getWidth == 3)
    assert(io.notIssuedCount.getWidth == 3)
    assert(io.headPc.getWidth == 64)
    assert(io.headOpcode.getWidth == 12)
    assert(io.headStid.getWidth == p.threadIdWidth)
    assert(io.headBid.valid.getWidth == 1)
    assert(io.headBid.wrap.getWidth == 1)
    assert(io.headBid.value.getWidth == p.robIndexWidth)
    assert(io.headRid.valid.getWidth == 1)
    assert(io.headRid.wrap.getWidth == 1)
    assert(io.headRid.value.getWidth == p.robIndexWidth)
    assert(io.headSrcValidMask.getWidth == 3)
    assert(io.headSrcOperandClass.length == 3)
    assert(io.headSrcPhysTag.head.getWidth == 6)
    assert(io.headSrcRelTag.head.getWidth == 6)
    assert(io.sourceReadyMask.getWidth == 3)
    assert(io.selectedIndex.getWidth == 2)
    assert(io.selectedReadReady.getWidth == 1)
    assert(io.pickFire.getWidth == 1)
    assert(io.cancelFire.getWidth == 1)
    assert(io.i1Valid.getWidth == 1)
    assert(io.i2Valid.getWidth == 1)
    assert(io.stageBusy.getWidth == 1)
    assert(io.enqueueDstTag.getWidth == 6)
  }

  test("ReducedScalarIssueQueue elaborates capacity, oldest-ready selection, and read-confirm diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarIssueQueue(InterfaceParams(), depth = 4, stidCount = 1))

    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ReducedScalarIssuePick"))
    assert(sv.contains("io_inReady"))
    assert(sv.contains("io_pWakeupValid"))
    assert(sv.contains("io_pWakeupMatched"))
    assert(sv.contains("io_pWakeupMatchCount"))
    assert(sv.contains("io_readValid"))
    assert(sv.contains("io_readOperandClass"))
    assert(sv.contains("io_readGrant"))
    assert(sv.contains("io_readAttemptValid"))
    assert(sv.contains("io_issueValid"))
    assert(sv.contains("io_enqueueFire"))
    assert(sv.contains("io_pickFire"))
    assert(sv.contains("io_issueFire"))
    assert(sv.contains("io_cancelFire"))
    assert(sv.contains("io_releaseFire"))
    assert(sv.contains("io_secondaryReleaseValid"))
    assert(sv.contains("io_tertiaryReleaseValid"))
    assert(sv.contains("io_scalarSpHeadValidByStid"))
    assert(sv.contains("io_headIssued"))
    assert(sv.contains("io_headPc"))
    assert(sv.contains("io_headStid"))
    assert(sv.contains("io_headBid_valid"))
    assert(sv.contains("io_headBid_wrap"))
    assert(sv.contains("io_headBid_value"))
    assert(sv.contains("io_headRid_valid"))
    assert(sv.contains("io_headRid_wrap"))
    assert(sv.contains("io_headRid_value"))
    assert(sv.contains("io_headSrcValidMask"))
    assert(sv.contains("io_headSrcPhysTag"))
    assert(sv.contains("io_sourceReadyMask"))
    assert(sv.contains("io_selectedValid"))
    assert(sv.contains("io_selectedIndex"))
    assert(sv.contains("io_selectedReadReady"))
    assert(sv.contains("io_i1Valid"))
    assert(sv.contains("io_i2Valid"))
    assert(sv.contains("io_stageBusy"))
    assert(sv.contains("io_blockedByRead"))
    assert(sv.contains("io_blockedByIssued"))
    assert(sv.contains("io_blockedBySource"))
    assert(sv.contains("io_blockedByOutput"))
  }

  test("committed P wakeup matches only global P operands with the same physical tag") {
    def matches(valid: Boolean, issued: Boolean, operandClass: String, tag: Int, wakeTag: Int): Boolean =
      valid && !issued && operandClass == "P" && tag == wakeTag

    assert(matches(valid = true, issued = false, operandClass = "P", tag = 40, wakeTag = 40))
    assert(!matches(valid = true, issued = true, operandClass = "P", tag = 40, wakeTag = 40))
    assert(!matches(valid = true, issued = false, operandClass = "T", tag = 40, wakeTag = 40))
    assert(!matches(valid = true, issued = false, operandClass = "P", tag = 41, wakeTag = 40))
  }

  test("same-bank scalar SP rows require owner-head eligibility before pick") {
    final case class Row(
        rid: Int,
        scalarSp: Boolean,
        sourceReady: Boolean,
        issued: Boolean = false)

    def selectable(row: Row, headRid: Int): Boolean =
      !row.issued && row.sourceReady && (!row.scalarSp || row.rid == headRid)

    def firstSelectable(rows: Seq[Row], headRid: Int): Option[Int] =
      rows.indexWhere(selectable(_, headRid)) match {
        case -1 => None
        case idx => Some(idx)
      }

    val olderBlocked = Row(rid = 10, scalarSp = true, sourceReady = false)
    val youngerFrame = Row(rid = 11, scalarSp = true, sourceReady = true)

    assert(firstSelectable(Seq(olderBlocked, youngerFrame), headRid = 10).isEmpty)

    val olderWoken = olderBlocked.copy(sourceReady = true)
    val first = firstSelectable(Seq(olderWoken, youngerFrame), headRid = 10)
    assert(first.contains(0))
    assert(!selectable(youngerFrame, headRid = 10))

    val afterOlderIssued = Seq(olderWoken.copy(issued = true), youngerFrame)
    assert(firstSelectable(afterOlderIssued, headRid = 11).contains(1))
  }

  test("DUT issues older same-bank scalar SP head before younger ready frame row") {
    val tmp = Files.createTempDirectory("reduced-scalar-iq-sp-order")
    val svPath = tmp.resolve("ReducedScalarIssueQueueSameBankSpOrderProbe.sv")
    val tbPath = tmp.resolve("tb.cpp")
    val objDir = tmp.resolve("obj")
    Files.createDirectories(objDir)
    ChiselStage.emitSystemVerilogFile(
      new ReducedScalarIssueQueueSameBankSpOrderProbe,
      args = Array("--target-dir", tmp.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
    Files.writeString(tbPath, """#include "VReducedScalarIssueQueueSameBankSpOrderProbe.h"
#include "verilated.h"
#include <iostream>

static void tick(VReducedScalarIssueQueueSameBankSpOrderProbe* top, VerilatedContext* context) {
  top->clock = 0;
  top->eval();
  context->timeInc(1);
  top->clock = 1;
  top->eval();
  context->timeInc(1);
}

int main(int argc, char** argv) {
  VerilatedContext context;
  context.commandArgs(argc, argv);
  VReducedScalarIssueQueueSameBankSpOrderProbe top{&context};
  top.reset = 1;
  for (int i = 0; i < 3; ++i) {
    tick(&top, &context);
  }

  top.reset = 0;
  for (int cycle = 0; cycle < 100; ++cycle) {
    tick(&top, &context);
    if (top.io_failure) {
      std::cerr << "same-bank scalar-SP issue-order probe failed at cycle " << cycle
                << " first=" << static_cast<int>(top.io_firstIssueRid)
                << " second=" << static_cast<int>(top.io_secondIssueRid) << "\n";
      return 1;
    }
    if (top.io_done) {
      if (top.io_firstIssueRid != 10 || top.io_secondIssueRid != 11) {
        std::cerr << "unexpected issue order first=" << static_cast<int>(top.io_firstIssueRid)
                  << " second=" << static_cast<int>(top.io_secondIssueRid) << "\n";
        return 1;
      }
      return 0;
    }
  }
  std::cerr << "same-bank scalar-SP issue-order probe timed out\n";
  return 1;
}
""")
    val command = Seq(
      "verilator",
      "--cc",
      "--exe",
      "--build",
      "--top-module",
      "ReducedScalarIssueQueueSameBankSpOrderProbe",
      svPath.toString,
      tbPath.toString,
      "-Mdir",
      objDir.toString
    )
    val compileExit = Process(command, tmp.toFile).!
    assert(compileExit == 0)
    val runExit = Process(Seq(objDir.resolve("VReducedScalarIssueQueueSameBankSpOrderProbe").toString), tmp.toFile).!
    assert(runExit == 0)
  }

  test("DUT releases primary, secondary, and tertiary identities in one cycle") {
    simulate(new ReducedScalarIssueQueue(SimP, depth = 4, stidCount = 1)) { dut =>
      initQueue(dut)

      enqueue(dut, 1, pokeRow(_, ridValue = 1, dstTag = Some(9)))
      enqueue(dut, 2, pokeRow(_, ridValue = 2, dstTag = Some(10)))
      enqueue(dut, 3, pokeRow(_, ridValue = 3, dstTag = Some(11)))
      dut.io.count.expect(3.U)

      for (rid <- 1 to 3) {
        dut.io.issueFire.expect(true.B)
        dut.io.issueUop.rid.value.expect(rid.U)
        dut.clock.step()
      }
      dut.io.issuedCount.expect(3.U)

      pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = true, 1)
      pokeRelease(
        dut.io.secondaryReleaseValid,
        dut.io.secondaryReleaseBid,
        dut.io.secondaryReleaseRid,
        dut.io.secondaryReleaseStid,
        fire = true,
        2)
      pokeRelease(
        dut.io.tertiaryReleaseValid,
        dut.io.tertiaryReleaseBid,
        dut.io.tertiaryReleaseRid,
        dut.io.tertiaryReleaseStid,
        fire = true,
        3)
      dut.io.releaseFire.expect(true.B)
      dut.clock.step()
      dut.io.count.expect(0.U)
      dut.io.empty.expect(true.B)
    }
  }

  test("DUT de-duplicates same-identity release across primary and tertiary ports") {
    simulate(new ReducedScalarIssueQueue(SimP, depth = 4, stidCount = 1)) { dut =>
      initQueue(dut)

      enqueue(dut, 5, pokeRow(_, ridValue = 5, dstTag = Some(12)))
      enqueue(dut, 6, pokeRow(_, ridValue = 6, dstTag = Some(13)))
      enqueue(dut, 7, pokeRow(_, ridValue = 7, dstTag = Some(14)))
      dut.io.count.expect(3.U)

      for (rid <- 5 to 7) {
        dut.io.issueFire.expect(true.B)
        dut.io.issueUop.rid.value.expect(rid.U)
        dut.clock.step()
      }
      dut.io.issuedCount.expect(3.U)

      pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = true, 5)
      pokeRelease(
        dut.io.secondaryReleaseValid,
        dut.io.secondaryReleaseBid,
        dut.io.secondaryReleaseRid,
        dut.io.secondaryReleaseStid,
        fire = true,
        5)
      pokeRelease(
        dut.io.tertiaryReleaseValid,
        dut.io.tertiaryReleaseBid,
        dut.io.tertiaryReleaseRid,
        dut.io.tertiaryReleaseStid,
        fire = true,
        5)
      dut.io.releaseFire.expect(true.B)
      dut.clock.step()
      dut.io.count.expect(2.U)
      dut.io.headRid.value.expect(6.U)
    }
  }

  test("DUT exposes empty-invalid and resident head transaction identity") {
    val tmp = Files.createTempDirectory("reduced-scalar-iq-head-identity")
    val svPath = tmp.resolve("ReducedScalarIssueQueueHeadIdentityProbe.sv")
    val tbPath = tmp.resolve("tb.cpp")
    val objDir = tmp.resolve("obj")
    Files.createDirectories(objDir)
    ChiselStage.emitSystemVerilogFile(
      new ReducedScalarIssueQueueHeadIdentityProbe,
      args = Array("--target-dir", tmp.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
    Files.writeString(tbPath, """#include "VReducedScalarIssueQueueHeadIdentityProbe.h"
#include "verilated.h"
#include <iostream>

static void tick(VReducedScalarIssueQueueHeadIdentityProbe* top, VerilatedContext* context) {
  top->clock = 0;
  top->eval();
  context->timeInc(1);
  top->clock = 1;
  top->eval();
  context->timeInc(1);
}

int main(int argc, char** argv) {
  VerilatedContext context;
  context.commandArgs(argc, argv);
  VReducedScalarIssueQueueHeadIdentityProbe top{&context};
  top.reset = 1;
  for (int i = 0; i < 3; ++i) {
    tick(&top, &context);
  }
  top.reset = 0;
  for (int cycle = 0; cycle < 40; ++cycle) {
    tick(&top, &context);
    if (top.io_failure) {
      std::cerr << "head identity probe failed at cycle " << cycle << "\n";
      return 1;
    }
    if (top.io_done) {
      return 0;
    }
  }
  std::cerr << "head identity probe timed out\n";
  return 1;
}
""")
    val command = Seq(
      "verilator",
      "--cc",
      "--exe",
      "--build",
      "--top-module",
      "ReducedScalarIssueQueueHeadIdentityProbe",
      svPath.toString,
      tbPath.toString,
      "-Mdir",
      objDir.toString
    )
    val compileExit = Process(command, tmp.toFile).!
    assert(compileExit == 0)
    val runExit = Process(Seq(objDir.resolve("VReducedScalarIssueQueueHeadIdentityProbe").toString), tmp.toFile).!
    assert(runExit == 0)
  }
}
