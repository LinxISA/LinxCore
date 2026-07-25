package linxcore.execute

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Enum, is, switch}
import linxcore.common.{DestinationKind, DispatchTarget, InterfaceParams, OperandClass, RenamedUop}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.sys.process._

class ScalarIssueFabricHeadIdentityProbeIO(val p: InterfaceParams) extends Bundle {
  val done = Output(Bool())
  val failure = Output(Bool())
}

class ScalarIssueFabricHeadIdentityProbe extends Module {
  val p = InterfaceParams(robEntries = 16)
  val io = IO(new ScalarIssueFabricHeadIdentityProbeIO(p))

  val fabric = Module(new ScalarIssueFabric(p, depth = 8, bankCount = 2, stidCount = 1))
  val cycle = RegInit(0.U(8.W))
  val producerReady = RegInit(false.B)
  val observedFirstBank = RegInit(false.B)
  val observedSecondBank = RegInit(false.B)
  val failure = RegInit(false.B)
  cycle := cycle + 1.U

  val Seq(sEnqueueFirst, sEnqueueSecond, sObserveFirst, sDrainFirst, sObserveSecond, sDone) = Enum(6)
  val state = RegInit(sEnqueueFirst)

  def row(stid: Int, bidWrap: Boolean, bidValue: Int, ridWrap: Boolean, ridValue: Int, pc: Long): linxcore.common.RenamedUop = {
    val uop = Wire(new linxcore.common.RenamedUop(p))
    uop := 0.U.asTypeOf(uop)
    uop.valid := true.B
    uop.threadId := stid.U
    uop.pc := pc.U
    uop.opcode := FrontendOpcodeDecodeTable.OP_ADDI.U(p.opcodeWidth.W)
    uop.dispatchTarget := DispatchTarget.Alu
    uop.bid.valid := true.B
    uop.bid.wrap := bidWrap.B
    uop.bid.value := bidValue.U
    uop.rid.valid := true.B
    uop.rid.wrap := ridWrap.B
    uop.rid.value := ridValue.U
    uop.src(0).valid := true.B
    uop.src(0).operandClass := OperandClass.P
    uop.src(0).archTag := 8.U
    uop.src(0).relTag := 8.U
    uop.src(0).physTag := 8.U
    uop
  }

  val first = row(stid = 1, bidWrap = true, bidValue = 2, ridWrap = false, ridValue = 3, pc = 0x1000L)
  val second = row(stid = 4, bidWrap = false, bidValue = 5, ridWrap = true, ridValue = 6, pc = 0x2000L)

  fabric.io.inValid := state === sEnqueueFirst || state === sEnqueueSecond
  fabric.io.in := Mux(state === sEnqueueFirst, first, second)
  fabric.io.flushValid := false.B
  fabric.io.releaseValid := fabric.io.issueFire
  fabric.io.releaseBid := fabric.io.issueUop.bid
  fabric.io.releaseRid := fabric.io.issueUop.rid
  fabric.io.releaseStid := fabric.io.issueUop.threadId
  fabric.io.secondaryReleaseValid := false.B
  fabric.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseStid := 0.U
  fabric.io.tertiaryReleaseValid := false.B
  fabric.io.tertiaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.tertiaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.tertiaryReleaseStid := 0.U
  fabric.io.externalControlFenceValid := false.B
  fabric.io.externalControlFenceBid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceRid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceStid := 0.U
  fabric.io.scalarSpHeadValidByStid(0) := false.B
  fabric.io.scalarSpHeadBidByStid(0) := ROBID.disabled(p.robEntries)
  fabric.io.scalarSpHeadRidByStid(0) := ROBID.disabled(p.robEntries)
  fabric.io.scalarSpSnapshotByStid(0) := 0.U
  fabric.io.readyMask := Mux(producerReady, 1.U << 8.U, 0.U)
  fabric.io.pWakeupValid := false.B
  fabric.io.pWakeupTag := 0.U
  fabric.io.localTReadyMask := 0.U
  fabric.io.localUReadyMask := 0.U
  fabric.io.issueReady := true.B
  for (lane <- 0 until 3) {
    fabric.io.readData(lane) := (0x300 + lane).U
  }

  val firstIdentity =
    fabric.io.headValid &&
      fabric.io.headPc === 0x1000.U &&
      fabric.io.headStid === 1.U &&
      fabric.io.headBid.valid &&
      fabric.io.headBid.wrap &&
      fabric.io.headBid.value === 2.U &&
      fabric.io.headRid.valid &&
      !fabric.io.headRid.wrap &&
      fabric.io.headRid.value === 3.U
  val secondIdentity =
    fabric.io.headValid &&
      fabric.io.headPc === 0x2000.U &&
      fabric.io.headStid === 4.U &&
      fabric.io.headBid.valid &&
      !fabric.io.headBid.wrap &&
      fabric.io.headBid.value === 5.U &&
      fabric.io.headRid.valid &&
      fabric.io.headRid.wrap &&
      fabric.io.headRid.value === 6.U

  when(state === sObserveFirst && fabric.io.bankOccupancy(0) === 1.U && fabric.io.bankOccupancy(1) === 1.U) {
    observedFirstBank := firstIdentity
    when(!firstIdentity) {
      failure := true.B
    }
  }
  when(state === sObserveSecond && fabric.io.bankOccupancy(0) === 0.U && fabric.io.bankOccupancy(1) === 1.U) {
    observedSecondBank := secondIdentity
    when(!secondIdentity) {
      failure := true.B
    }
  }
  when(cycle > 80.U && !observedSecondBank) {
    failure := true.B
  }

  switch(state) {
    is(sEnqueueFirst) {
      when(fabric.io.enqueueFire) {
        state := sEnqueueSecond
      }
    }
    is(sEnqueueSecond) {
      when(fabric.io.enqueueFire) {
        state := sObserveFirst
      }
    }
    is(sObserveFirst) {
      when(observedFirstBank) {
        producerReady := true.B
        state := sDrainFirst
      }
    }
    is(sDrainFirst) {
      when(fabric.io.releaseFire) {
        producerReady := false.B
        state := sObserveSecond
      }
    }
    is(sObserveSecond) {
      when(observedSecondBank) {
        state := sDone
      }
    }
  }

  io.done := state === sDone
  io.failure := failure
}

class ScalarIssueFabricRedirectAgeProbeIO extends Bundle {
  val done = Output(Bool())
  val failure = Output(Bool())
  val observedRedirectBlocked = Output(Bool())
}

class ScalarIssueFabricRedirectAgeProbe extends Module {
  val p = InterfaceParams(robEntries = 16)
  val io = IO(new ScalarIssueFabricRedirectAgeProbeIO)

  val fabric = Module(new ScalarIssueFabric(p, depth = 8, bankCount = 2, stidCount = 1))
  val cycle = RegInit(0.U(8.W))
  val enqueueCount = RegInit(0.U(2.W))
  val olderReady = RegInit(false.B)
  val olderIssueSeen = RegInit(false.B)
  val redirectIssueSeen = RegInit(false.B)
  val observedRedirectBlocked = RegInit(false.B)
  val failure = RegInit(false.B)
  val releasePending = RegInit(false.B)
  val releaseBid = Reg(new ROBID(p.robEntries))
  val releaseRid = Reg(new ROBID(p.robEntries))
  val releaseStid = Reg(UInt(p.threadIdWidth.W))
  cycle := cycle + 1.U

  def row(
      opcode: Int,
      dispatch: DispatchTarget.Type,
      bidValue: Int,
      ridWrap: Boolean,
      ridValue: Int,
      pc: Long): linxcore.common.RenamedUop = {
    val uop = Wire(new linxcore.common.RenamedUop(p))
    uop := 0.U.asTypeOf(uop)
    uop.valid := true.B
    uop.threadId := 0.U
    uop.pc := pc.U
    uop.opcode := opcode.U(p.opcodeWidth.W)
    uop.dispatchTarget := dispatch
    uop.bid.valid := true.B
    uop.bid.wrap := false.B
    uop.bid.value := bidValue.U
    uop.rid.valid := true.B
    uop.rid.wrap := ridWrap.B
    uop.rid.value := ridValue.U
    uop
  }

  // RID is globally ordered within one STID even when the rows cross a BID
  // boundary.  With 16 ROB rows, (wrap=1,value=1) is newer than
  // (wrap=0,value=14).
  val older = row(
    FrontendOpcodeDecodeTable.OP_ADDI,
    DispatchTarget.Alu,
    bidValue = 3,
    ridWrap = false,
    ridValue = 14,
    pc = 0x1000L)
  older.src(0).valid := true.B
  older.src(0).operandClass := OperandClass.P
  older.src(0).archTag := 8.U
  older.src(0).relTag := 8.U
  older.src(0).physTag := 8.U
  val redirect =
    row(
      FrontendOpcodeDecodeTable.OP_FRET_STK,
      DispatchTarget.Cmd,
      bidValue = 4,
      ridWrap = true,
      ridValue = 1,
      pc = 0x1004L)

  fabric.io.inValid := enqueueCount =/= 2.U
  fabric.io.in := Mux(enqueueCount === 0.U, older, redirect)
  when(fabric.io.enqueueFire) {
    enqueueCount := enqueueCount + 1.U
  }
  fabric.io.flushValid := false.B
  fabric.io.releaseValid := releasePending
  fabric.io.releaseBid := releaseBid
  fabric.io.releaseRid := releaseRid
  fabric.io.releaseStid := releaseStid
  fabric.io.secondaryReleaseValid := false.B
  fabric.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseStid := 0.U
  fabric.io.tertiaryReleaseValid := false.B
  fabric.io.tertiaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.tertiaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.tertiaryReleaseStid := 0.U
  fabric.io.externalControlFenceValid := false.B
  fabric.io.externalControlFenceBid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceRid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceStid := 0.U
  fabric.io.scalarSpHeadValidByStid(0) := true.B
  fabric.io.scalarSpHeadBidByStid(0) := redirect.bid
  fabric.io.scalarSpHeadRidByStid(0) := redirect.rid
  fabric.io.scalarSpSnapshotByStid(0) := 0.U
  fabric.io.readyMask := Mux(olderReady, 1.U << 8.U, 0.U)
  fabric.io.pWakeupValid := false.B
  fabric.io.pWakeupTag := 0.U
  fabric.io.localTReadyMask := 0.U
  fabric.io.localUReadyMask := 0.U
  fabric.io.issueReady := true.B
  for (lane <- 0 until 3) {
    fabric.io.readData(lane) := (0x400 + lane).U
  }

  releasePending := false.B
  when(fabric.io.issueFire) {
    releasePending := true.B
    releaseBid := fabric.io.issueUop.bid
    releaseRid := fabric.io.issueUop.rid
    releaseStid := fabric.io.issueUop.threadId
    val issuedOlder =
      ROBID.equal(fabric.io.issueUop.bid, older.bid) &&
        ROBID.equal(fabric.io.issueUop.rid, older.rid)
    val issuedRedirect =
      ROBID.equal(fabric.io.issueUop.bid, redirect.bid) &&
        ROBID.equal(fabric.io.issueUop.rid, redirect.rid)
    when(issuedOlder) {
      olderIssueSeen := true.B
    }.elsewhen(issuedRedirect) {
      redirectIssueSeen := true.B
      when(!olderIssueSeen) {
        failure := true.B
      }
    }.otherwise {
      failure := true.B
    }
  }
  when(fabric.io.controlFenceBlocked) {
    observedRedirectBlocked := true.B
  }
  when(enqueueCount === 2.U && cycle > 10.U) {
    olderReady := true.B
  }
  when(cycle > 80.U && !redirectIssueSeen) {
    failure := true.B
  }

  io.done := olderIssueSeen && redirectIssueSeen
  io.failure := failure
  io.observedRedirectBlocked := observedRedirectBlocked
}

class ScalarIssueFabricDifferentStidProbeIO extends Bundle {
  val done = Output(Bool())
  val failure = Output(Bool())
}

class ScalarIssueFabricDifferentStidProbe extends Module {
  val p = InterfaceParams(robEntries = 16)
  val io = IO(new ScalarIssueFabricDifferentStidProbeIO)

  val fabric = Module(new ScalarIssueFabric(p, depth = 8, bankCount = 2, stidCount = 2))
  val cycle = RegInit(0.U(8.W))
  val enqueueCount = RegInit(0.U(2.W))
  val redirectIssueSeen = RegInit(false.B)
  val failure = RegInit(false.B)
  cycle := cycle + 1.U

  def row(
      stid: Int,
      opcode: Int,
      dispatch: DispatchTarget.Type,
      bidValue: Int,
      ridWrap: Boolean,
      ridValue: Int,
      pc: Long): linxcore.common.RenamedUop = {
    val uop = Wire(new linxcore.common.RenamedUop(p))
    uop := 0.U.asTypeOf(uop)
    uop.valid := true.B
    uop.threadId := stid.U
    uop.pc := pc.U
    uop.opcode := opcode.U(p.opcodeWidth.W)
    uop.dispatchTarget := dispatch
    uop.bid.valid := true.B
    uop.bid.wrap := false.B
    uop.bid.value := bidValue.U
    uop.rid.valid := true.B
    uop.rid.wrap := ridWrap.B
    uop.rid.value := ridValue.U
    uop
  }

  val olderOtherStid = row(
    stid = 1,
    opcode = FrontendOpcodeDecodeTable.OP_ADDI,
    dispatch = DispatchTarget.Alu,
    bidValue = 3,
    ridWrap = false,
    ridValue = 14,
    pc = 0x2000L)
  olderOtherStid.src(0).valid := true.B
  olderOtherStid.src(0).operandClass := OperandClass.P
  olderOtherStid.src(0).archTag := 8.U
  olderOtherStid.src(0).relTag := 8.U
  olderOtherStid.src(0).physTag := 8.U
  val redirect = row(
    stid = 0,
    opcode = FrontendOpcodeDecodeTable.OP_FRET_STK,
    dispatch = DispatchTarget.Cmd,
    bidValue = 4,
    ridWrap = true,
    ridValue = 1,
    pc = 0x2004L)

  fabric.io.inValid := enqueueCount =/= 2.U
  fabric.io.in := Mux(enqueueCount === 0.U, olderOtherStid, redirect)
  when(fabric.io.enqueueFire) {
    enqueueCount := enqueueCount + 1.U
  }
  fabric.io.flushValid := false.B
  fabric.io.releaseValid := false.B
  fabric.io.releaseBid := ROBID.disabled(p.robEntries)
  fabric.io.releaseRid := ROBID.disabled(p.robEntries)
  fabric.io.releaseStid := 0.U
  fabric.io.secondaryReleaseValid := false.B
  fabric.io.secondaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.secondaryReleaseStid := 0.U
  fabric.io.tertiaryReleaseValid := false.B
  fabric.io.tertiaryReleaseBid := ROBID.disabled(p.robEntries)
  fabric.io.tertiaryReleaseRid := ROBID.disabled(p.robEntries)
  fabric.io.tertiaryReleaseStid := 0.U
  fabric.io.externalControlFenceValid := false.B
  fabric.io.externalControlFenceBid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceRid := ROBID.disabled(p.robEntries)
  fabric.io.externalControlFenceStid := 0.U
  fabric.io.scalarSpHeadValidByStid(0) := true.B
  fabric.io.scalarSpHeadBidByStid(0) := redirect.bid
  fabric.io.scalarSpHeadRidByStid(0) := redirect.rid
  fabric.io.scalarSpSnapshotByStid(0) := 0.U
  fabric.io.scalarSpHeadValidByStid(1) := false.B
  fabric.io.scalarSpHeadBidByStid(1) := ROBID.disabled(p.robEntries)
  fabric.io.scalarSpHeadRidByStid(1) := ROBID.disabled(p.robEntries)
  fabric.io.scalarSpSnapshotByStid(1) := 0.U
  fabric.io.readyMask := 0.U
  fabric.io.pWakeupValid := false.B
  fabric.io.pWakeupTag := 0.U
  fabric.io.localTReadyMask := 0.U
  fabric.io.localUReadyMask := 0.U
  fabric.io.issueReady := true.B
  for (lane <- 0 until 3) {
    fabric.io.readData(lane) := (0x500 + lane).U
  }

  when(fabric.io.controlFenceBlocked) {
    failure := true.B
  }
  when(fabric.io.issueFire) {
    val isRedirect =
      (fabric.io.issueUop.threadId === redirect.threadId) &&
        ROBID.equal(fabric.io.issueUop.bid, redirect.bid) &&
        ROBID.equal(fabric.io.issueUop.rid, redirect.rid)
    when(isRedirect) {
      redirectIssueSeen := true.B
      when(fabric.io.count =/= 2.U ||
        fabric.io.bankOccupancy(0) =/= 1.U ||
        fabric.io.bankOccupancy(1) =/= 1.U) {
        failure := true.B
      }
    }.otherwise {
      failure := true.B
    }
  }
  when(cycle > 80.U && !redirectIssueSeen) {
    failure := true.B
  }

  io.done := redirectIssueSeen
  io.failure := failure
}

class ScalarIssueFabricSpec extends AnyFunSuite with ChiselSim {
  private val SimP = InterfaceParams(robEntries = 16)
  private val AllReadyMask = (BigInt(1) << (1 << SimP.physRegWidth)) - 1

  private def pokeRobId(id: ROBID, valid: Boolean, wrap: Boolean, value: Int): Unit = {
    id.valid.poke(valid.B)
    id.wrap.poke(wrap.B)
    id.value.poke(value.U)
  }

  private def pokeFabricRow(row: RenamedUop, ridValue: Int, dstTag: Int): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.threadId.poke(0.U)
    row.pc.poke((0x20000L + ridValue).U)
    row.opcode.poke(FrontendOpcodeDecodeTable.OP_ADDI.U(SimP.opcodeWidth.W))
    row.dispatchTarget.poke(DispatchTarget.Alu)
    pokeRobId(row.bid, valid = true, wrap = false, value = 0)
    pokeRobId(row.gid, valid = true, wrap = false, value = 0)
    pokeRobId(row.rid, valid = true, wrap = false, value = ridValue)
    row.src(0).valid.poke(true.B)
    row.src(0).operandClass.poke(OperandClass.P)
    row.src(0).archTag.poke(8.U)
    row.src(0).relTag.poke(8.U)
    row.src(0).physTag.poke(8.U)
    row.dst(0).valid.poke(true.B)
    row.dst(0).kind.poke(DestinationKind.Gpr)
    row.dst(0).archTag.poke(dstTag.U)
    row.dst(0).relTag.poke(dstTag.U)
    row.dst(0).physTag.poke(dstTag.U)
  }

  private def initFabric(dut: ScalarIssueFabric): Unit = {
    dut.io.inValid.poke(false.B)
    pokeFabricRow(dut.io.in, ridValue = 0, dstTag = 9)
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
    dut.io.externalControlFenceValid.poke(false.B)
    pokeRobId(dut.io.externalControlFenceBid, valid = false, wrap = false, value = 0)
    pokeRobId(dut.io.externalControlFenceRid, valid = false, wrap = false, value = 0)
    dut.io.externalControlFenceStid.poke(0.U)
    dut.io.scalarSpHeadValidByStid(0).poke(false.B)
    pokeRobId(dut.io.scalarSpHeadBidByStid(0), valid = false, wrap = false, value = 0)
    pokeRobId(dut.io.scalarSpHeadRidByStid(0), valid = false, wrap = false, value = 0)
    dut.io.scalarSpSnapshotByStid(0).poke(0.U)
    dut.io.readyMask.poke(AllReadyMask.U)
    dut.io.pWakeupValid.poke(false.B)
    dut.io.pWakeupTag.poke(0.U)
    dut.io.localTReadyMask.poke(0.U)
    dut.io.localUReadyMask.poke(0.U)
    dut.io.issueReady.poke(true.B)
    for (lane <- 0 until 3) {
      dut.io.readData(lane).poke((0x700 + lane).U)
    }
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

  private def enqueueFabric(
      dut: ScalarIssueFabric,
      ridValue: Int,
      expectedBankEnqueues: Int,
      expectedMask: Int,
      opcode: Int = FrontendOpcodeDecodeTable.OP_ADDI,
      dispatch: DispatchTarget.Type = DispatchTarget.Alu,
      isStore: Boolean = false,
      expectedBankRid0: Option[Int] = None,
      expectedBankRid1: Option[Int] = None): Unit = {
    dut.io.inValid.poke(true.B)
    pokeFabricRow(dut.io.in, ridValue = ridValue, dstTag = 10 + ridValue)
    dut.io.in.opcode.poke(opcode.U(SimP.opcodeWidth.W))
    dut.io.in.dispatchTarget.poke(dispatch)
    dut.io.in.isStore.poke(isStore.B)
    dut.io.inReady.expect(true.B)
    dut.io.inputAcceptFire.expect(true.B)
    dut.io.inputAcceptUop.rid.value.expect(ridValue.U)
    dut.io.inputAcceptDstValid.expect(true.B)
    dut.io.inputAcceptDstTag.expect((10 + ridValue).U)
    dut.io.enqueueFire.expect(true.B)
    dut.io.enqueueCount.expect(expectedBankEnqueues.U)
    dut.io.bankEnqueueFireMask.expect(expectedMask.U)
    expectedBankRid0.foreach(rid => dut.io.bankEnqueueUop(0).rid.value.expect(rid.U))
    expectedBankRid1.foreach(rid => dut.io.bankEnqueueUop(1).rid.value.expect(rid.U))
    dut.clock.step()
    dut.io.inValid.poke(false.B)
  }

  private def issueCycle(
      dut: ScalarIssueFabric,
      issueRid: Int,
      releaseRid: Option[Int] = None): Unit = {
    releaseRid match {
      case Some(rid) =>
        pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = true, rid)
      case None =>
        pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = false, 0)
    }
    dut.io.inValid.poke(false.B)
    dut.io.issueReady.poke(true.B)
    dut.io.issueFire.expect(true.B)
    dut.io.issueUop.rid.value.expect(issueRid.U)
    dut.clock.step()
    pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = false, 0)
  }

  private def releaseIssuedPair(dut: ScalarIssueFabric, firstRid: Int, secondRid: Int): Unit = {
    dut.io.inValid.poke(false.B)
    dut.io.issueReady.poke(false.B)
    pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = true, firstRid)
    pokeRelease(
      dut.io.secondaryReleaseValid,
      dut.io.secondaryReleaseBid,
      dut.io.secondaryReleaseRid,
      dut.io.secondaryReleaseStid,
      fire = true,
      secondRid)
    dut.io.releaseFire.expect(true.B)
    dut.clock.step()
    pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = false, 0)
    pokeRelease(
      dut.io.secondaryReleaseValid,
      dut.io.secondaryReleaseBid,
      dut.io.secondaryReleaseRid,
      dut.io.secondaryReleaseStid,
      fire = false,
      0)
  }

  private def enqueueFabricWithReleases(
      dut: ScalarIssueFabric,
      ridValue: Int,
      expectedBankEnqueues: Int,
      expectedMask: Int,
      primaryReleaseRid: Option[Int] = None,
      secondaryReleaseRid: Option[Int] = None,
      expectedBankRid0: Option[Int] = None,
      expectedBankRid1: Option[Int] = None): Unit = {
    primaryReleaseRid match {
      case Some(rid) =>
        pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = true, rid)
      case None =>
        pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = false, 0)
    }
    secondaryReleaseRid match {
      case Some(rid) =>
        pokeRelease(
          dut.io.secondaryReleaseValid,
          dut.io.secondaryReleaseBid,
          dut.io.secondaryReleaseRid,
          dut.io.secondaryReleaseStid,
          fire = true,
          rid)
      case None =>
        pokeRelease(
          dut.io.secondaryReleaseValid,
          dut.io.secondaryReleaseBid,
          dut.io.secondaryReleaseRid,
          dut.io.secondaryReleaseStid,
          fire = false,
          0)
    }
    enqueueFabric(
      dut,
      ridValue = ridValue,
      expectedBankEnqueues = expectedBankEnqueues,
      expectedMask = expectedMask,
      expectedBankRid0 = expectedBankRid0,
      expectedBankRid1 = expectedBankRid1)
    pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = false, 0)
    pokeRelease(
      dut.io.secondaryReleaseValid,
      dut.io.secondaryReleaseBid,
      dut.io.secondaryReleaseRid,
      dut.io.secondaryReleaseStid,
      fire = false,
      0)
  }

  test("fabric preserves total capacity while exposing bank arbitration") {
    val p = InterfaceParams()
    val io = new ScalarIssueFabricIO(p, depth = 8, bankCount = 2)

    assert(io.count.getWidth == 4)
    assert(io.inputAcceptUop.pc.getWidth == p.pcWidth)
    assert(io.inputAcceptDstTag.getWidth == p.physRegWidth)
    assert(io.enqueueCount.getWidth == 2)
    assert(io.bankEnqueueFireMask.getWidth == 2)
    assert(io.bankEnqueueUop.length == 2)
    assert(io.selectedIndex.getWidth == 3)
    assert(io.bankOccupancy.length == 2)
    assert(io.bankOccupancy.head.getWidth == 3)
    assert(io.bankPickMask.getWidth == 2)
    assert(io.bankReadAttemptMask.getWidth == 2)
    assert(io.bankReadGrantMask.getWidth == 2)
    assert(io.bankIssueValidMask.getWidth == 2)
    assert(io.bankIssueGrantMask.getWidth == 2)
    assert(io.bankControlBlockedMask.getWidth == 2)
    assert(io.bankStoreOrderBlockedMask.getWidth == 2)
    assert(io.tertiaryReleaseBid.value.getWidth == p.robIndexWidth)
    assert(io.tertiaryReleaseRid.value.getWidth == p.robIndexWidth)
    assert(io.tertiaryReleaseStid.getWidth == p.threadIdWidth)
    assert(io.scalarSpHeadValidByStid.length == 1)
    assert(io.scalarSpHeadBidByStid.head.value.getWidth == p.robIndexWidth)
    assert(io.scalarSpHeadRidByStid.head.value.getWidth == p.robIndexWidth)
    assert(io.bankScalarSpOrderBlockedMask.getWidth == 2)
    assert(io.headStid.getWidth == p.threadIdWidth)
    assert(io.headBid.valid.getWidth == 1)
    assert(io.headBid.wrap.getWidth == 1)
    assert(io.headBid.value.getWidth == p.robIndexWidth)
    assert(io.headRid.valid.getWidth == 1)
    assert(io.headRid.wrap.getWidth == 1)
    assert(io.headRid.value.getWidth == p.robIndexWidth)
  }

  test("fabric elaborates resident banks plus I1 and I2 arbiters") {
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarIssueFabric(InterfaceParams(), depth = 8, bankCount = 2))

    assert(sv.contains("module ScalarIssueFabric"))
    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ScalarIssueIngressSkid2"))
    assert(sv.contains("module ScalarIssueCandidateArbiter"))
    assert(sv.contains("io_inputAcceptFire"))
    assert(sv.contains("io_inputAcceptDstValid"))
    assert(sv.contains("io_enqueueCount"))
    assert(sv.contains("io_bankEnqueueFireMask"))
    assert(sv.contains("io_bankOccupancy_0"))
    assert(sv.contains("io_readContention"))
    assert(sv.contains("io_readArbitrationLoss"))
    assert(sv.contains("io_issueContention"))
    assert(sv.contains("io_controlFenceActive"))
    assert(sv.contains("io_controlFenceBlocked"))
    assert(sv.contains("io_storeOrderBlocked"))
    assert(sv.contains("io_tertiaryReleaseValid"))
    assert(sv.contains("io_scalarSpHeadValidByStid"))
    assert(sv.contains("io_scalarSpOrderBlocked"))
    assert(sv.contains("io_headStid"))
    assert(sv.contains("io_headBid_valid"))
    assert(sv.contains("io_headBid_wrap"))
    assert(sv.contains("io_headBid_value"))
    assert(sv.contains("io_headRid_valid"))
    assert(sv.contains("io_headRid_wrap"))
    assert(sv.contains("io_headRid_value"))
  }

  test("DUT preserves empty zero-bubble enqueue through the ingress skid") {
    simulate(new ScalarIssueFabric(SimP, depth = 4, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)
      dut.io.issueReady.poke(false.B)

      enqueueFabric(dut, ridValue = 1, expectedBankEnqueues = 1, expectedMask = 1)
      dut.io.count.expect(1.U)
      dut.io.bankOccupancy(0).expect(1.U)
      dut.io.bankOccupancy(1).expect(0.U)
      dut.io.empty.expect(false.B)
    }
  }

  test("DUT dual-enqueues a skid resident plus current input to different ready banks") {
    simulate(new ScalarIssueFabric(SimP, depth = 4, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)
      dut.io.issueReady.poke(false.B)

      enqueueFabric(dut, ridValue = 1, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 2, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 3, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 4, expectedBankEnqueues = 1, expectedMask = 2)
      dut.io.bankOccupancy(0).expect(2.U)
      dut.io.bankOccupancy(1).expect(2.U)

      enqueueFabric(dut, ridValue = 5, expectedBankEnqueues = 0, expectedMask = 0)
      dut.io.count.expect(5.U)
      dut.io.bankOccupancy(0).expect(2.U)
      dut.io.bankOccupancy(1).expect(2.U)

      issueCycle(dut, issueRid = 1)
      issueCycle(dut, issueRid = 2)
      enqueueFabricWithReleases(
        dut,
        ridValue = 6,
        expectedBankEnqueues = 2,
        expectedMask = 3,
        primaryReleaseRid = Some(1),
        secondaryReleaseRid = Some(2),
        expectedBankRid0 = Some(5),
        expectedBankRid1 = Some(6))
      dut.io.count.expect(4.U)
      dut.io.bankOccupancy(0).expect(2.U)
      dut.io.bankOccupancy(1).expect(2.U)
    }
  }

  test("DUT does not enqueue the younger input when only the younger bank is ready") {
    simulate(new ScalarIssueFabric(SimP, depth = 4, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)
      dut.io.issueReady.poke(false.B)

      enqueueFabric(dut, ridValue = 1, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 2, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 3, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 4, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 5, expectedBankEnqueues = 0, expectedMask = 0)

      issueCycle(dut, issueRid = 1)
      issueCycle(dut, issueRid = 2)
      issueCycle(dut, issueRid = 3, releaseRid = Some(2))
      dut.io.bankOccupancy(0).expect(2.U)
      dut.io.bankOccupancy(1).expect(1.U)

      enqueueFabric(dut, ridValue = 6, expectedBankEnqueues = 0, expectedMask = 0)
      dut.io.full.expect(true.B)
      dut.io.count.expect(5.U)
    }
  }

  test("DUT separates input accept metadata from later skid resident bank enqueue") {
    simulate(new ScalarIssueFabric(SimP, depth = 4, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)
      dut.io.issueReady.poke(false.B)

      enqueueFabric(dut, ridValue = 1, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 2, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 3, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 4, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 5, expectedBankEnqueues = 0, expectedMask = 0)

      issueCycle(dut, issueRid = 1)
      dut.io.inValid.poke(false.B)
      pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = true, 1)
      dut.io.issueReady.poke(false.B)
      dut.io.inputAcceptFire.expect(false.B)
      dut.io.inputAcceptDstValid.expect(false.B)
      dut.io.enqueueFire.expect(false.B)
      dut.io.enqueueCount.expect(1.U)
      dut.io.bankEnqueueFireMask.expect(1.U)
      dut.io.bankEnqueueUop(0).rid.value.expect(5.U)
      dut.clock.step()
      pokeRelease(dut.io.releaseValid, dut.io.releaseBid, dut.io.releaseRid, dut.io.releaseStid, fire = false, 0)
    }
  }

  test("DUT same-bank current input causes older-only bank enqueue and captures younger") {
    simulate(new ScalarIssueFabric(SimP, depth = 4, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)
      dut.io.issueReady.poke(false.B)

      enqueueFabric(dut, ridValue = 1, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 2, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 3, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 4, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 5, expectedBankEnqueues = 0, expectedMask = 0)

      issueCycle(dut, issueRid = 1)
      enqueueFabricWithReleases(
        dut,
        ridValue = 6,
        expectedBankEnqueues = 1,
        expectedMask = 1,
        primaryReleaseRid = Some(1))
      dut.io.count.expect(5.U)
      dut.io.bankOccupancy(0).expect(2.U)
      dut.io.bankOccupancy(1).expect(2.U)
      dut.io.full.expect(false.B)
    }
  }

  test("DUT flush clears skid residents and full ingress backpressure") {
    simulate(new ScalarIssueFabric(SimP, depth = 4, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)
      dut.io.issueReady.poke(false.B)

      enqueueFabric(dut, ridValue = 1, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 2, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 3, expectedBankEnqueues = 1, expectedMask = 1)
      enqueueFabric(dut, ridValue = 4, expectedBankEnqueues = 1, expectedMask = 2)
      enqueueFabric(dut, ridValue = 5, expectedBankEnqueues = 0, expectedMask = 0)
      enqueueFabric(dut, ridValue = 6, expectedBankEnqueues = 0, expectedMask = 0)
      dut.io.full.expect(true.B)
      dut.io.inReady.expect(false.B)

      dut.io.flushValid.poke(true.B)
      dut.io.inReady.expect(false.B)
      dut.io.enqueueCount.expect(0.U)
      dut.io.bankEnqueueFireMask.expect(0.U)
      dut.clock.step()
      dut.io.flushValid.poke(false.B)

      dut.io.empty.expect(true.B)
      dut.io.count.expect(0.U)
      dut.io.full.expect(false.B)
      dut.io.inReady.expect(true.B)
    }
  }

  test("DUT fans primary, secondary, and tertiary release ports into resident banks") {
    simulate(new ScalarIssueFabric(SimP, depth = 8, bankCount = 2, stidCount = 1)) { dut =>
      initFabric(dut)

      for (rid <- 1 to 3) {
        dut.io.inValid.poke(true.B)
        pokeFabricRow(dut.io.in, ridValue = rid, dstTag = 10 + rid)
        dut.io.inReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.inValid.poke(false.B)
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

  test("DUT muxes passive head transaction identity from selected head bank") {
    val tmp = Files.createTempDirectory("scalar-issue-fabric-head-identity")
    val svPath = tmp.resolve("ScalarIssueFabricHeadIdentityProbe.sv")
    val tbPath = tmp.resolve("tb.cpp")
    val objDir = tmp.resolve("obj")
    Files.createDirectories(objDir)
    ChiselStage.emitSystemVerilogFile(
      new ScalarIssueFabricHeadIdentityProbe,
      args = Array("--target-dir", tmp.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
    Files.writeString(tbPath, """#include "VScalarIssueFabricHeadIdentityProbe.h"
#include "verilated.h"
#include <iostream>

static void tick(VScalarIssueFabricHeadIdentityProbe* top, VerilatedContext* context) {
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
  VScalarIssueFabricHeadIdentityProbe top{&context};
  top.reset = 1;
  for (int i = 0; i < 3; ++i) {
    tick(&top, &context);
  }
  top.reset = 0;
  for (int cycle = 0; cycle < 100; ++cycle) {
    tick(&top, &context);
    if (top.io_failure) {
      std::cerr << "fabric head identity probe failed at cycle " << cycle << "\n";
      return 1;
    }
    if (top.io_done) {
      return 0;
    }
  }
  std::cerr << "fabric head identity probe timed out\n";
  return 1;
}
""")
    val command = Seq(
      "verilator",
      "--cc",
      "--exe",
      "--build",
      "--top-module",
      "ScalarIssueFabricHeadIdentityProbe",
      svPath.toString,
      tbPath.toString,
      "-Mdir",
      objDir.toString
    )
    val compileExit = Process(command, tmp.toFile).!
    assert(compileExit == 0)
    val runExit = Process(Seq(objDir.resolve("VScalarIssueFabricHeadIdentityProbe").toString), tmp.toFile).!
    assert(runExit == 0)
  }

  test("redirecting control waits across BID and RID wrap for an older same-STID resident") {
    val tmp = Files.createTempDirectory("scalar-issue-fabric-redirect-age")
    val svPath = tmp.resolve("ScalarIssueFabricRedirectAgeProbe.sv")
    val tbPath = tmp.resolve("tb.cpp")
    val objDir = tmp.resolve("obj")
    Files.createDirectories(objDir)
    ChiselStage.emitSystemVerilogFile(
      new ScalarIssueFabricRedirectAgeProbe,
      args = Array("--target-dir", tmp.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
    Files.writeString(tbPath, """#include "VScalarIssueFabricRedirectAgeProbe.h"
#include "verilated.h"
#include <iostream>

static void tick(VScalarIssueFabricRedirectAgeProbe* top, VerilatedContext* context) {
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
  VScalarIssueFabricRedirectAgeProbe top{&context};
  top.reset = 1;
  for (int i = 0; i < 3; ++i) {
    tick(&top, &context);
  }
  top.reset = 0;
  for (int cycle = 0; cycle < 100; ++cycle) {
    tick(&top, &context);
    if (top.io_failure) {
      std::cerr << "redirect age probe failed at cycle " << cycle << "\n";
      return 1;
    }
    if (top.io_done) {
      if (!top.io_observedRedirectBlocked) {
        std::cerr << "redirect was ordered but never observed blocked\n";
        return 1;
      }
      return 0;
    }
  }
  std::cerr << "redirect age probe timed out\n";
  return 1;
}
""")
    val command = Seq(
      "verilator",
      "--cc",
      "--exe",
      "--build",
      "--top-module",
      "ScalarIssueFabricRedirectAgeProbe",
      svPath.toString,
      tbPath.toString,
      "-Mdir",
      objDir.toString
    )
    val compileExit = Process(command, tmp.toFile).!
    assert(compileExit == 0)
    val runExit = Process(Seq(objDir.resolve("VScalarIssueFabricRedirectAgeProbe").toString), tmp.toFile).!
    assert(runExit == 0)
  }

  test("redirecting control does not wait for an older resident from another STID") {
    val tmp = Files.createTempDirectory("scalar-issue-fabric-redirect-different-stid")
    val svPath = tmp.resolve("ScalarIssueFabricDifferentStidProbe.sv")
    val tbPath = tmp.resolve("tb.cpp")
    val objDir = tmp.resolve("obj")
    Files.createDirectories(objDir)
    ChiselStage.emitSystemVerilogFile(
      new ScalarIssueFabricDifferentStidProbe,
      args = Array("--target-dir", tmp.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
    Files.writeString(tbPath, """#include "VScalarIssueFabricDifferentStidProbe.h"
#include "verilated.h"
#include <iostream>

static void tick(VScalarIssueFabricDifferentStidProbe* top, VerilatedContext* context) {
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
  VScalarIssueFabricDifferentStidProbe top{&context};
  top.reset = 1;
  for (int i = 0; i < 3; ++i) {
    tick(&top, &context);
  }
  top.reset = 0;
  for (int cycle = 0; cycle < 100; ++cycle) {
    tick(&top, &context);
    if (top.io_failure) {
      std::cerr << "different-STID redirect probe failed at cycle " << cycle << "\n";
      return 1;
    }
    if (top.io_done) {
      return 0;
    }
  }
  std::cerr << "different-STID redirect probe timed out\n";
  return 1;
}
""")
    val command = Seq(
      "verilator",
      "--cc",
      "--exe",
      "--build",
      "--top-module",
      "ScalarIssueFabricDifferentStidProbe",
      svPath.toString,
      tbPath.toString,
      "-Mdir",
      objDir.toString
    )
    val compileExit = Process(command, tmp.toFile).!
    assert(compileExit == 0)
    val runExit = Process(Seq(objDir.resolve("VScalarIssueFabricDifferentStidProbe").toString), tmp.toFile).!
    assert(runExit == 0)
  }
}
