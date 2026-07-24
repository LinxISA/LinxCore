package linxcore.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import org.scalatest.funsuite.AnyFunSuite

class ExecuteCompletionRetainerProbeIO extends Bundle {
  val laneValid = Input(Vec(2, Bool()))
  val laneKeyValid = Input(Vec(2, Bool()))
  val lanePeId = Input(Vec(2, UInt(8.W)))
  val laneStid = Input(Vec(2, UInt(8.W)))
  val laneTid = Input(Vec(2, UInt(8.W)))
  val lanePc = Input(Vec(2, UInt(64.W)))
  val laneBidValid = Input(Vec(2, Bool()))
  val laneBidWrap = Input(Vec(2, Bool()))
  val laneBidValue = Input(Vec(2, UInt(3.W)))
  val laneGidValid = Input(Vec(2, Bool()))
  val laneGidWrap = Input(Vec(2, Bool()))
  val laneGidValue = Input(Vec(2, UInt(3.W)))
  val laneRidValid = Input(Vec(2, Bool()))
  val laneRidWrap = Input(Vec(2, Bool()))
  val laneRidValue = Input(Vec(2, UInt(3.W)))
  val laneRobValid = Input(Vec(2, Bool()))
  val laneRobWrap = Input(Vec(2, Bool()))
  val laneRobValue = Input(Vec(2, UInt(3.W)))
  val laneRowValid = Input(Vec(2, Bool()))
  val laneToken = Input(Vec(2, UInt(64.W)))

  val outputReady = Input(Bool())
  val clearValid = Input(Bool())
  val clearNuke = Input(Bool())
  val clearKeyValid = Input(Bool())
  val clearPeId = Input(UInt(8.W))
  val clearStid = Input(UInt(8.W))
  val clearTid = Input(UInt(8.W))
  val clearPc = Input(UInt(64.W))
  val clearBidValid = Input(Bool())
  val clearBidWrap = Input(Bool())
  val clearBidValue = Input(UInt(3.W))
  val clearGidValid = Input(Bool())
  val clearGidWrap = Input(Bool())
  val clearGidValue = Input(UInt(3.W))
  val clearRidValid = Input(Bool())
  val clearRidWrap = Input(Bool())
  val clearRidValue = Input(UInt(3.W))
  val clearRobValid = Input(Bool())
  val clearRobWrap = Input(Bool())
  val clearRobValue = Input(UInt(3.W))

  val laneReady = Output(Vec(2, Bool()))
  val laneAccepted = Output(Vec(2, Bool()))
  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(3.W))
  val completeRowValid = Output(Bool())
  val completeToken = Output(UInt(64.W))
  val residentCount = Output(UInt(2.W))
  val duplicateFullIdentity = Output(Bool())
  val invalidCompletionIdentity = Output(Bool())
  val overflowBlocked = Output(Bool())
  val protocolError = Output(Bool())
}

class ExecuteCompletionRetainerProbe extends Module {
  private val traceParams = CommitTraceParams(robValueWidth = 3)
  val io = IO(new ExecuteCompletionRetainerProbeIO)

  private val retainer = Module(
    new ExecuteCompletionRetainer(
      ptrWidth = 3,
      traceParams = traceParams,
      robEntries = 8))

  private def driveRow(row: CommitTraceRow, idx: Int): Unit = {
    row := 0.U.asTypeOf(new CommitTraceRow(traceParams))
    row.valid := io.laneRowValid(idx)
    row.seq := io.laneToken(idx)
    row.pc := io.laneToken(idx)
    row.insn := io.laneToken(idx) ^ "h55aa55aa55aa55aa".U
    row.nextPc := io.laneToken(idx) + 4.U
  }

  private def driveLaneKey(idx: Int): Unit = {
    val key = retainer.io.lanes(idx).key
    key.valid := io.laneKeyValid(idx)
    key.peId := io.lanePeId(idx)
    key.stid := io.laneStid(idx)
    key.tid := io.laneTid(idx)
    key.pc := io.lanePc(idx)
    key.bid.valid := io.laneBidValid(idx)
    key.bid.wrap := io.laneBidWrap(idx)
    key.bid.value := io.laneBidValue(idx)
    key.gid.valid := io.laneGidValid(idx)
    key.gid.wrap := io.laneGidWrap(idx)
    key.gid.value := io.laneGidValue(idx)
    key.rid.valid := io.laneRidValid(idx)
    key.rid.wrap := io.laneRidWrap(idx)
    key.rid.value := io.laneRidValue(idx)
    key.rob.valid := io.laneRobValid(idx)
    key.rob.wrap := io.laneRobWrap(idx)
    key.rob.value := io.laneRobValue(idx)
  }

  for (idx <- 0 until 2) {
    retainer.io.lanes(idx).valid := io.laneValid(idx)
    driveLaneKey(idx)
    retainer.io.lanes(idx).rowValid := io.laneRowValid(idx)
    driveRow(retainer.io.lanes(idx).row, idx)
    io.laneReady(idx) := retainer.io.laneReady(idx)
    io.laneAccepted(idx) := retainer.io.laneAccepted(idx)
  }

  retainer.io.outputReady := io.outputReady
  retainer.io.clear.valid := io.clearValid
  retainer.io.clear.nuke := io.clearNuke
  retainer.io.clear.key.valid := io.clearKeyValid
  retainer.io.clear.key.peId := io.clearPeId
  retainer.io.clear.key.stid := io.clearStid
  retainer.io.clear.key.tid := io.clearTid
  retainer.io.clear.key.pc := io.clearPc
  retainer.io.clear.key.bid.valid := io.clearBidValid
  retainer.io.clear.key.bid.wrap := io.clearBidWrap
  retainer.io.clear.key.bid.value := io.clearBidValue
  retainer.io.clear.key.gid.valid := io.clearGidValid
  retainer.io.clear.key.gid.wrap := io.clearGidWrap
  retainer.io.clear.key.gid.value := io.clearGidValue
  retainer.io.clear.key.rid.valid := io.clearRidValid
  retainer.io.clear.key.rid.wrap := io.clearRidWrap
  retainer.io.clear.key.rid.value := io.clearRidValue
  retainer.io.clear.key.rob.valid := io.clearRobValid
  retainer.io.clear.key.rob.wrap := io.clearRobWrap
  retainer.io.clear.key.rob.value := io.clearRobValue

  io.completeValid := retainer.io.completeValid
  io.completeRobValue := retainer.io.completeRobValue
  io.completeRowValid := retainer.io.completeRowValid
  io.completeToken := retainer.io.completeRow.pc
  io.residentCount := retainer.io.residentCount
  io.duplicateFullIdentity := retainer.io.duplicateFullIdentity
  io.invalidCompletionIdentity := retainer.io.invalidCompletionIdentity
  io.overflowBlocked := retainer.io.overflowBlocked
  io.protocolError := retainer.io.protocolError
}

final case class KeyCase(
    peId: Int,
    stid: Int,
    tid: Int,
    pc: BigInt,
    bidValue: Int,
    gidValue: Int,
    ridValue: Int,
    robValue: Int,
    wrap: Boolean = false)

class ExecuteCompletionRetainerSpec extends AnyFunSuite with ChiselSim {
  private def idle(dut: ExecuteCompletionRetainerProbe): Unit = {
    for (idx <- 0 until 2) {
      dut.io.laneValid(idx).poke(false.B)
      dut.io.laneKeyValid(idx).poke(false.B)
      dut.io.lanePeId(idx).poke(0.U)
      dut.io.laneStid(idx).poke(0.U)
      dut.io.laneTid(idx).poke(0.U)
      dut.io.lanePc(idx).poke(0.U)
      dut.io.laneBidValid(idx).poke(false.B)
      dut.io.laneBidWrap(idx).poke(false.B)
      dut.io.laneBidValue(idx).poke(0.U)
      dut.io.laneGidValid(idx).poke(false.B)
      dut.io.laneGidWrap(idx).poke(false.B)
      dut.io.laneGidValue(idx).poke(0.U)
      dut.io.laneRidValid(idx).poke(false.B)
      dut.io.laneRidWrap(idx).poke(false.B)
      dut.io.laneRidValue(idx).poke(0.U)
      dut.io.laneRobValid(idx).poke(false.B)
      dut.io.laneRobWrap(idx).poke(false.B)
      dut.io.laneRobValue(idx).poke(0.U)
      dut.io.laneRowValid(idx).poke(false.B)
      dut.io.laneToken(idx).poke(0.U)
    }
    dut.io.outputReady.poke(false.B)
    dut.io.clearValid.poke(false.B)
    dut.io.clearNuke.poke(false.B)
    dut.io.clearKeyValid.poke(false.B)
    dut.io.clearPeId.poke(0.U)
    dut.io.clearStid.poke(0.U)
    dut.io.clearTid.poke(0.U)
    dut.io.clearPc.poke(0.U)
    dut.io.clearBidValid.poke(false.B)
    dut.io.clearBidWrap.poke(false.B)
    dut.io.clearBidValue.poke(0.U)
    dut.io.clearGidValid.poke(false.B)
    dut.io.clearGidWrap.poke(false.B)
    dut.io.clearGidValue.poke(0.U)
    dut.io.clearRidValid.poke(false.B)
    dut.io.clearRidWrap.poke(false.B)
    dut.io.clearRidValue.poke(0.U)
    dut.io.clearRobValid.poke(false.B)
    dut.io.clearRobWrap.poke(false.B)
    dut.io.clearRobValue.poke(0.U)
  }

  private def pokeLane(dut: ExecuteCompletionRetainerProbe, lane: Int, key: KeyCase, token: BigInt): Unit = {
    dut.io.laneValid(lane).poke(true.B)
    dut.io.laneKeyValid(lane).poke(true.B)
    dut.io.lanePeId(lane).poke(key.peId.U)
    dut.io.laneStid(lane).poke(key.stid.U)
    dut.io.laneTid(lane).poke(key.tid.U)
    dut.io.lanePc(lane).poke(key.pc.U)
    dut.io.laneBidValid(lane).poke(true.B)
    dut.io.laneBidWrap(lane).poke(key.wrap.B)
    dut.io.laneBidValue(lane).poke(key.bidValue.U)
    dut.io.laneGidValid(lane).poke(true.B)
    dut.io.laneGidWrap(lane).poke(key.wrap.B)
    dut.io.laneGidValue(lane).poke(key.gidValue.U)
    dut.io.laneRidValid(lane).poke(true.B)
    dut.io.laneRidWrap(lane).poke(key.wrap.B)
    dut.io.laneRidValue(lane).poke(key.ridValue.U)
    dut.io.laneRobValid(lane).poke(true.B)
    dut.io.laneRobWrap(lane).poke(key.wrap.B)
    dut.io.laneRobValue(lane).poke(key.robValue.U)
    dut.io.laneRowValid(lane).poke(true.B)
    dut.io.laneToken(lane).poke(token.U)
  }

  private def pokeClearKey(dut: ExecuteCompletionRetainerProbe, key: KeyCase): Unit = {
    dut.io.clearValid.poke(true.B)
    dut.io.clearNuke.poke(false.B)
    dut.io.clearKeyValid.poke(true.B)
    dut.io.clearPeId.poke(key.peId.U)
    dut.io.clearStid.poke(key.stid.U)
    dut.io.clearTid.poke(key.tid.U)
    dut.io.clearPc.poke(key.pc.U)
    dut.io.clearBidValid.poke(true.B)
    dut.io.clearBidWrap.poke(key.wrap.B)
    dut.io.clearBidValue.poke(key.bidValue.U)
    dut.io.clearGidValid.poke(true.B)
    dut.io.clearGidWrap.poke(key.wrap.B)
    dut.io.clearGidValue.poke(key.gidValue.U)
    dut.io.clearRidValid.poke(true.B)
    dut.io.clearRidWrap.poke(key.wrap.B)
    dut.io.clearRidValue.poke(key.ridValue.U)
    dut.io.clearRobValid.poke(true.B)
    dut.io.clearRobWrap.poke(key.wrap.B)
    dut.io.clearRobValue.poke(key.robValue.U)
  }

  private def expectComplete(
      dut: ExecuteCompletionRetainerProbe,
      robValue: Int,
      token: BigInt): Unit = {
    dut.io.completeValid.expect(true.B)
    dut.io.completeRobValue.expect(robValue.U)
    dut.io.completeRowValid.expect(true.B)
    dut.io.completeToken.expect(token.U)
  }

  private val keyA = KeyCase(0, 0, 0, 0x1000, bidValue = 1, gidValue = 1, ridValue = 1, robValue = 1)
  private val keyB = KeyCase(0, 0, 0, 0x1004, bidValue = 1, gidValue = 1, ridValue = 2, robValue = 2)

  test("flows empty lane0 to output in the same cycle and stores nothing when ready") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      dut.io.outputReady.poke(true.B)
      pokeLane(dut, lane = 0, keyA, token = 0x41)
      expectComplete(dut, robValue = 1, token = 0x41)
      dut.io.laneAccepted(0).expect(true.B)
      dut.io.residentCount.expect(0.U)
      dut.clock.step()

      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("captures empty lane0 flow-through when downstream is blocked") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      dut.io.outputReady.poke(false.B)
      pokeLane(dut, lane = 0, keyA, token = 0x42)
      expectComplete(dut, robValue = 1, token = 0x42)
      dut.io.laneAccepted(0).expect(true.B)
      dut.io.residentCount.expect(1.U)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(false.B)
      expectComplete(dut, robValue = 1, token = 0x42)
      dut.clock.step()
      expectComplete(dut, robValue = 1, token = 0x42)
    }
  }

  test("flows lane0 and retains lane1 when both arrive to an empty ready retainer") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      dut.io.outputReady.poke(true.B)
      pokeLane(dut, lane = 0, keyA, token = 0x43)
      pokeLane(dut, lane = 1, keyB, token = 0x44)
      expectComplete(dut, robValue = 1, token = 0x43)
      dut.io.laneAccepted(0).expect(true.B)
      dut.io.laneAccepted(1).expect(true.B)
      dut.io.residentCount.expect(1.U)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      expectComplete(dut, robValue = 2, token = 0x44)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("stored completion has priority over incoming lane0 flow-through") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      dut.io.outputReady.poke(false.B)
      pokeLane(dut, lane = 0, keyA, token = 0x45)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      pokeLane(dut, lane = 0, keyB, token = 0x46)
      expectComplete(dut, robValue = 1, token = 0x45)
      dut.io.laneAccepted(0).expect(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      expectComplete(dut, robValue = 2, token = 0x46)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("nuke kill suppresses invalid incoming before protocol checks") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      dut.io.clearValid.poke(true.B)
      dut.io.clearNuke.poke(true.B)
      dut.io.laneValid(0).poke(true.B)
      dut.io.laneKeyValid(0).poke(false.B)
      dut.io.laneRowValid(0).poke(true.B)
      dut.io.laneToken(0).poke(0x47.U)
      dut.io.completeValid.expect(false.B)
      dut.io.invalidCompletionIdentity.expect(false.B)
      dut.io.protocolError.expect(false.B)
      dut.io.laneAccepted(0).expect(false.B)
      dut.clock.step()

      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("exact kill suppresses duplicate incoming before duplicate checks") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeClearKey(dut, keyA)
      pokeLane(dut, lane = 0, keyA, token = 0x48)
      pokeLane(dut, lane = 1, keyA, token = 0x49)
      dut.io.completeValid.expect(false.B)
      dut.io.duplicateFullIdentity.expect(false.B)
      dut.io.protocolError.expect(false.B)
      dut.io.laneAccepted(0).expect(false.B)
      dut.io.laneAccepted(1).expect(false.B)
      dut.clock.step()

      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("accepts simultaneous different exact identities and drains lane order over two cycles") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0x101)
      pokeLane(dut, lane = 1, keyB, token = 0x202)
      dut.io.laneReady(0).expect(true.B)
      dut.io.laneReady(1).expect(true.B)
      dut.io.laneAccepted(0).expect(true.B)
      dut.io.laneAccepted(1).expect(true.B)
      dut.io.protocolError.expect(false.B)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      expectComplete(dut, robValue = 1, token = 0x101)
      dut.clock.step()
      expectComplete(dut, robValue = 2, token = 0x202)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("flags duplicate full key and retains no duplicate entry") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0x303)
      pokeLane(dut, lane = 1, keyA, token = 0x404)
      dut.io.duplicateFullIdentity.expect(true.B)
      dut.io.protocolError.expect(true.B)
      dut.io.laneReady(0).expect(false.B)
      dut.io.laneReady(1).expect(false.B)
      dut.clock.step()

      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("rejects dequeue plus same-key reaccept against the pre-dequeue resident set") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0x505)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      pokeLane(dut, lane = 0, keyA, token = 0x606)
      expectComplete(dut, robValue = 1, token = 0x505)
      dut.io.duplicateFullIdentity.expect(true.B)
      dut.io.protocolError.expect(true.B)
      dut.io.laneReady(0).expect(false.B)
      dut.clock.step()

      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("treats same RID in different PE STID TID contexts as independent keys") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      val keyOtherContext = keyA.copy(peId = 1, stid = 2, tid = 3)
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0x707)
      pokeLane(dut, lane = 1, keyOtherContext, token = 0x808)
      dut.io.duplicateFullIdentity.expect(false.B)
      dut.io.protocolError.expect(false.B)
      dut.io.laneReady(0).expect(true.B)
      dut.io.laneReady(1).expect(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      expectComplete(dut, robValue = 1, token = 0x707)
      dut.clock.step()
      expectComplete(dut, robValue = 1, token = 0x808)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("holds an output stable across downstream backpressure") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0x909)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(false.B)
      expectComplete(dut, robValue = 1, token = 0x909)
      dut.clock.step()
      expectComplete(dut, robValue = 1, token = 0x909)
      dut.clock.step()
      expectComplete(dut, robValue = 1, token = 0x909)

      dut.io.outputReady.poke(true.B)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("exact key kill suppresses a retained output under backpressure") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0xa0a)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(false.B)
      expectComplete(dut, robValue = 1, token = 0xa0a)
      pokeClearKey(dut, keyA)
      dut.io.completeValid.expect(false.B)
      dut.clock.step()

      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("exact key kill clears one retained entry and nuke clears both") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0xb01)
      pokeLane(dut, lane = 1, keyB, token = 0xb02)
      dut.clock.step()

      idle(dut)
      pokeClearKey(dut, keyB)
      dut.io.outputReady.poke(true.B)
      expectComplete(dut, robValue = 1, token = 0xb01)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)

      idle(dut)
      pokeLane(dut, lane = 0, keyA.copy(ridValue = 5, robValue = 5, pc = 0x5000), token = 0xb05)
      pokeLane(dut, lane = 1, keyB.copy(ridValue = 6, robValue = 6, pc = 0x6000), token = 0xb06)
      dut.clock.step()

      idle(dut)
      dut.io.clearValid.poke(true.B)
      dut.io.clearNuke.poke(true.B)
      dut.io.completeValid.expect(false.B)
      dut.clock.step()
      idle(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("uses wrap identity so same slot across wraps drains without duplicate error") {
    simulate(new ExecuteCompletionRetainerProbe) { dut =>
      val wrapped = keyA.copy(wrap = true)
      idle(dut)
      pokeLane(dut, lane = 0, keyA, token = 0xc07)
      pokeLane(dut, lane = 1, wrapped, token = 0xc17)
      dut.io.duplicateFullIdentity.expect(false.B)
      dut.io.protocolError.expect(false.B)
      dut.io.laneReady(0).expect(true.B)
      dut.io.laneReady(1).expect(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.outputReady.poke(true.B)
      expectComplete(dut, robValue = 1, token = 0xc07)
      dut.clock.step()
      expectComplete(dut, robValue = 1, token = 0xc17)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("elaborates with explicit key ports and without range flush service or replay ports") {
    val sv = ChiselStage.emitSystemVerilog(
      new ExecuteCompletionRetainer(
        ptrWidth = 3,
        traceParams = CommitTraceParams(robValueWidth = 3),
        robEntries = 8))

    assert(sv.contains("module ExecuteCompletionRetainer"))
    assert(sv.contains("io_lanes_0_key_valid"))
    assert(sv.contains("io_lanes_0_key_peId"))
    assert(sv.contains("io_lanes_0_key_stid"))
    assert(sv.contains("io_lanes_0_key_tid"))
    assert(sv.contains("io_lanes_0_key_rob_wrap"))
    assert(sv.contains("io_clear_key_valid"))
    assert(!sv.contains("baseOnBid"))
    assert(!sv.contains("serviceComplete"))
    assert(!sv.contains("replayComplete"))
  }
}
