package linxcore.execute

import chisel3._
import chisel3.util.{Mux1H, PopCount, log2Ceil}

import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common.{InterfaceParams, ScalarSpAccess, ScalarSpTransaction}
import linxcore.rob.ROBID

class ScalarSpOrderOwnerIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val depth: Int = 64,
    val stidCount: Int = 1)
    extends Bundle {
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))

  val flushValid = Input(Bool())
  val initValid = Input(Bool())
  val initData = Input(UInt(p.immWidth.W))
  val recoveryRestoreValid = Input(Bool())
  val recoveryRestoreStid = Input(UInt(p.threadIdWidth.W))
  val recoveryRestoreData = Input(UInt(p.immWidth.W))

  val reserveValid = Input(Bool())
  val reserve = Input(new ScalarSpTransaction(p))
  val reserveReady = Output(Bool())

  val issueHeadValid = Output(Bool())
  val issueHeadValidByStid = Output(Vec(stidCount, Bool()))
  val issueHeadBidByStid = Output(Vec(stidCount, new ROBID(p.robEntries)))
  val issueHeadRidByStid = Output(Vec(stidCount, new ROBID(p.robEntries)))
  val issueSnapshot = Output(UInt(p.immWidth.W))
  val issueSnapshotByStid = Output(Vec(stidCount, UInt(p.immWidth.W)))

  val terminalValid = Input(Bool())
  val terminal = Input(new ScalarSpTransaction(p))
  val terminalProducedValid = Input(Bool())
  val terminalProducedData = Input(UInt(p.immWidth.W))

  val commit = Input(new CommitTracePort(traceParams))
  val commitValidMask = Input(UInt(traceParams.commitWidth.W))

  val currentSp = Output(UInt(p.immWidth.W))
  val currentSpByStid = Output(Vec(stidCount, UInt(p.immWidth.W)))
  val publishValid = Output(Bool())
  val publishData = Output(UInt(p.immWidth.W))
  val protocolError = Output(Bool())
}

class ScalarSpOrderOwner(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val depth: Int = 64,
    val stidCount: Int = 1)
    extends Module {
  require(depth > 1, "scalar SP owner reservation depth must hold at least two transactions")
  require(stidCount > 0, "scalar SP owner must expose at least one STID")

  private val countWidth = log2Ceil(depth + 1)
  private val ptrWidth = log2Ceil(depth)
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))

  val io = IO(new ScalarSpOrderOwnerIO(p, traceParams, depth, stidCount))

  private def stidIndex(stid: UInt): UInt =
    if (stidCount == 1) 0.U(stidIndexWidth.W) else stid(stidIndexWidth - 1, 0)

  private def sameRobId(lhs: ROBID, rhs: ROBID): Bool =
    ROBID.equal(lhs, rhs)

  private def sameTransaction(lhs: ScalarSpTransaction, rhs: ScalarSpTransaction): Bool =
    lhs.access.valid && rhs.access.valid &&
      lhs.stid === rhs.stid &&
      lhs.epoch === rhs.epoch &&
      sameRobId(lhs.bid, rhs.bid) &&
      sameRobId(lhs.rid, rhs.rid)

  val committedSp = RegInit(VecInit(Seq.fill(stidCount)(0.U(p.immWidth.W))))
  val queue = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(depth)(0.U.asTypeOf(new ScalarSpTransaction(p)))))))
  val head = RegInit(VecInit(Seq.fill(stidCount)(0.U(ptrWidth.W))))
  val tail = RegInit(VecInit(Seq.fill(stidCount)(0.U(ptrWidth.W))))
  val count = RegInit(VecInit(Seq.fill(stidCount)(0.U(countWidth.W))))
  val completedValid = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val completedData = RegInit(VecInit(Seq.fill(stidCount)(0.U(p.immWidth.W))))

  val reserveStid = stidIndex(io.reserve.stid)
  val reserveReady = !io.reserve.access.valid || count(reserveStid) =/= depth.U
  io.reserveReady := reserveReady
  val reserveFire = io.reserveValid && io.reserve.access.valid && reserveReady

  val headTxn = Wire(Vec(stidCount, new ScalarSpTransaction(p)))
  for (stid <- 0 until stidCount) {
    headTxn(stid) := queue(stid)(head(stid))
  }
  val anyHead = VecInit((0 until stidCount).map(stid => count(stid) =/= 0.U)).asUInt.orR
  val firstHead = chisel3.util.PriorityEncoder(VecInit((0 until stidCount).map(stid => count(stid) =/= 0.U)).asUInt)
  val headSelect = VecInit((0 until stidCount).map(stid => anyHead && firstHead === stid.U))
  io.issueHeadValid := anyHead
  io.issueHeadValidByStid := VecInit((0 until stidCount).map(stid => count(stid) =/= 0.U))
  io.issueHeadBidByStid := VecInit(headTxn.map(_.bid))
  io.issueHeadRidByStid := VecInit(headTxn.map(_.rid))
  io.issueSnapshotByStid := committedSp
  io.issueSnapshot := Mux1H(headSelect, committedSp)
  io.currentSpByStid := committedSp
  io.currentSp := committedSp(0)

  val terminalStid = stidIndex(io.terminal.stid)
  val terminalHeadValid = count(terminalStid) =/= 0.U
  val terminalMatchesHead =
    io.terminalValid && terminalHeadValid && sameTransaction(io.terminal, headTxn(terminalStid))
  val terminalReadOnly = terminalMatchesHead && !headTxn(terminalStid).access.write
  val terminalWriter = terminalMatchesHead && headTxn(terminalStid).access.write
  val terminalProtocolError =
    io.terminalValid && (!terminalMatchesHead || (terminalWriter && (!io.terminalProducedValid || completedValid(terminalStid))))

  val commitMatches = Wire(Vec(stidCount, Bool()))
  val commitBeforeComplete = Wire(Vec(stidCount, Bool()))
  for (stid <- 0 until stidCount) {
    val rowMatches = Wire(Vec(traceParams.commitWidth, Bool()))
    for (slot <- 0 until traceParams.commitWidth) {
      val row = io.commit.rows(slot)
      rowMatches(slot) :=
        io.commitValidMask(slot) &&
          row.valid &&
          count(stid) =/= 0.U &&
          headTxn(stid).access.write &&
          row.rob.valid === headTxn(stid).rid.valid &&
          row.rob.wrap === headTxn(stid).rid.wrap &&
          row.rob.value(p.robIndexWidth - 1, 0) === headTxn(stid).rid.value &&
          row.identity.bid === headTxn(stid).bid.value &&
          row.identity.rid === headTxn(stid).rid.value
    }
    commitMatches(stid) := rowMatches.asUInt.orR && completedValid(stid)
    commitBeforeComplete(stid) := rowMatches.asUInt.orR && !completedValid(stid)
  }
  val publishAny = commitMatches.asUInt.orR
  io.publishValid := publishAny
  io.publishData := Mux1H(commitMatches, completedData)

  val protocolErrorReg = RegInit(false.B)
  io.protocolError := protocolErrorReg || terminalProtocolError || commitBeforeComplete.asUInt.orR

  when(io.flushValid || io.initValid) {
    for (stid <- 0 until stidCount) {
      head(stid) := 0.U
      tail(stid) := 0.U
      count(stid) := 0.U
      completedValid(stid) := false.B
      for (idx <- 0 until depth) {
        queue(stid)(idx) := 0.U.asTypeOf(new ScalarSpTransaction(p))
      }
    }
    when(io.initValid) {
      for (stid <- 0 until stidCount) {
        committedSp(stid) := io.initData
      }
    }.elsewhen(io.recoveryRestoreValid) {
      // A returning FRET.STK produces its architectural SP value one cycle
      // before redirect recovery flushes younger reservations.  Preserve that
      // exact redirect-owner value while discarding the speculative queue.
      committedSp(stidIndex(io.recoveryRestoreStid)) := io.recoveryRestoreData
    }
    protocolErrorReg := false.B
  }.otherwise {
    when(terminalProtocolError || commitBeforeComplete.asUInt.orR) {
      protocolErrorReg := true.B
    }

    for (stid <- 0 until stidCount) {
      val doTerminalReadOnly = terminalReadOnly && terminalStid === stid.U
      val doTerminalWriter = terminalWriter && terminalStid === stid.U
      val doCommit = commitMatches(stid)
      val doReserve = reserveFire && reserveStid === stid.U
      val pop = doTerminalReadOnly || doCommit
      val nextHead = head(stid) + pop.asUInt
      val nextTail = tail(stid) + doReserve.asUInt
      val nextCount = count(stid) + doReserve.asUInt - pop.asUInt

      when(doTerminalWriter) {
        completedValid(stid) := true.B
        completedData(stid) := io.terminalProducedData
      }
      when(doCommit) {
        committedSp(stid) := completedData(stid)
        completedValid(stid) := false.B
      }
      when(doReserve) {
        queue(stid)(tail(stid)) := io.reserve
      }
      when(pop) {
        queue(stid)(head(stid)) := 0.U.asTypeOf(new ScalarSpTransaction(p))
      }
      head(stid) := nextHead
      tail(stid) := nextTail
      count(stid) := nextCount
    }
  }
}
