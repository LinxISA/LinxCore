package linxcore.backend

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount}

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.rob.ROBID

class ExecuteCompletionKey(
    val robEntries: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val pc = UInt(traceParams.pcWidth.W)
  val bid = new ROBID(robEntries)
  val gid = new ROBID(robEntries)
  val rid = new ROBID(robEntries)
  val rob = new ROBID(robEntries)
}

class ExecuteCompletionRetainerLane(
    val ptrWidth: Int,
    val robEntries: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val key = new ExecuteCompletionKey(robEntries, traceParams, peIdWidth, stidWidth, tidWidth)
  val rowValid = Bool()
  val row = new CommitTraceRow(traceParams)
}

class ExecuteCompletionRetainerClear(
    val robEntries: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val nuke = Bool()
  val key = new ExecuteCompletionKey(robEntries, traceParams, peIdWidth, stidWidth, tidWidth)
}

class ExecuteCompletionRetainerIO(
    val ptrWidth: Int,
    val robEntries: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val lanes = Input(Vec(2, new ExecuteCompletionRetainerLane(
    ptrWidth,
    robEntries,
    traceParams,
    peIdWidth,
    stidWidth,
    tidWidth)))
  val laneReady = Output(Vec(2, Bool()))
  val laneAccepted = Output(Vec(2, Bool()))

  val clear = Input(new ExecuteCompletionRetainerClear(robEntries, traceParams, peIdWidth, stidWidth, tidWidth))
  val outputReady = Input(Bool())
  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completeRowValid = Output(Bool())
  val completeRow = Output(new CommitTraceRow(traceParams))

  val residentCount = Output(UInt(2.W))
  val duplicateFullIdentity = Output(Bool())
  val invalidCompletionIdentity = Output(Bool())
  val overflowBlocked = Output(Bool())
  val protocolError = Output(Bool())
}

class ExecuteCompletionRetainerEntry(
    val ptrWidth: Int,
    val robEntries: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val key = new ExecuteCompletionKey(robEntries, traceParams, peIdWidth, stidWidth, tidWidth)
  val rowValid = Bool()
  val row = new CommitTraceRow(traceParams)
}

class ExecuteCompletionRetainer(
    val ptrWidth: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val robEntries: Int = -1,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(ptrWidth > 0, "ptrWidth must be positive")
  private val effectiveRobEntries = if (robEntries < 0) 1 << ptrWidth else robEntries
  require(effectiveRobEntries > 1, "robEntries must be greater than one")
  require((effectiveRobEntries & (effectiveRobEntries - 1)) == 0, "robEntries must be a power of two")
  require(effectiveRobEntries <= (1 << ptrWidth), "robEntries must fit in ptrWidth")
  require(traceParams.robValueWidth >= ptrWidth, "trace ROB value width must hold ptrWidth")

  val io = IO(new ExecuteCompletionRetainerIO(
    ptrWidth,
    effectiveRobEntries,
    traceParams,
    peIdWidth,
    stidWidth,
    tidWidth))

  private def laneToEntry(lane: ExecuteCompletionRetainerLane): ExecuteCompletionRetainerEntry = {
    val entry = Wire(new ExecuteCompletionRetainerEntry(
      ptrWidth,
      effectiveRobEntries,
      traceParams,
      peIdWidth,
      stidWidth,
      tidWidth))
    entry.key := lane.key
    entry.rowValid := lane.rowValid
    entry.row := lane.row
    entry
  }

  private def sameRobId(lhs: ROBID, rhs: ROBID): Bool =
    lhs.valid && rhs.valid && (lhs.wrap === rhs.wrap) && (lhs.value === rhs.value)

  private def keyValid(key: ExecuteCompletionKey): Bool =
    key.valid && key.bid.valid && key.gid.valid && key.rid.valid && key.rob.valid

  private def sameKey(lhs: ExecuteCompletionKey, rhs: ExecuteCompletionKey): Bool =
    keyValid(lhs) && keyValid(rhs) &&
      (lhs.peId === rhs.peId) &&
      (lhs.stid === rhs.stid) &&
      (lhs.tid === rhs.tid) &&
      (lhs.pc === rhs.pc) &&
      sameRobId(lhs.bid, rhs.bid) &&
      sameRobId(lhs.gid, rhs.gid) &&
      sameRobId(lhs.rid, rhs.rid) &&
      sameRobId(lhs.rob, rhs.rob)

  private def clearMatches(entry: ExecuteCompletionRetainerEntry): Bool =
    io.clear.valid && (io.clear.nuke || sameKey(entry.key, io.clear.key))

  private val slots = Reg(Vec(2, new ExecuteCompletionRetainerEntry(
    ptrWidth,
    effectiveRobEntries,
    traceParams,
    peIdWidth,
    stidWidth,
    tidWidth)))
  private val slotValid = RegInit(VecInit(Seq.fill(2)(false.B)))

  private val storedLive = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    storedLive(idx) := slotValid(idx) && !clearMatches(slots(idx))
  }

  private val firstValid = storedLive(0) || storedLive(1)
  private val firstEntry = Mux(storedLive(0), slots(0), slots(1))
  private val secondValid = storedLive(0) && storedLive(1)
  private val secondEntry = slots(1)

  private val laneEntry = Wire(Vec(2, new ExecuteCompletionRetainerEntry(
    ptrWidth,
    effectiveRobEntries,
    traceParams,
    peIdWidth,
    stidWidth,
    tidWidth)))
  private val laneCleared = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    laneEntry(idx) := laneToEntry(io.lanes(idx))
    laneCleared(idx) := clearMatches(laneEntry(idx))
  }

  private val laneActive = Wire(Vec(2, Bool()))
  private val laneInvalid = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    laneActive(idx) := io.lanes(idx).valid && !laneCleared(idx)
    laneInvalid(idx) := laneActive(idx) && !keyValid(io.lanes(idx).key)
  }

  private val incomingDuplicate =
    laneActive(0) && laneActive(1) && sameKey(laneEntry(0).key, laneEntry(1).key)
  private val residentDuplicate = Wire(Vec(2, Bool()))
  for (lane <- 0 until 2) {
    residentDuplicate(lane) :=
      laneActive(lane) &&
        ((storedLive(0) && sameKey(slots(0).key, laneEntry(lane).key)) ||
          (storedLive(1) && sameKey(slots(1).key, laneEntry(lane).key)))
  }

  private val duplicateFullIdentity = incomingDuplicate || residentDuplicate.asUInt.orR
  private val invalidCompletionIdentity = laneInvalid.asUInt.orR
  private val protocolError = duplicateFullIdentity || invalidCompletionIdentity

  io.duplicateFullIdentity := duplicateFullIdentity
  io.invalidCompletionIdentity := invalidCompletionIdentity
  io.protocolError := protocolError

  private val laneCanAccept = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    laneCanAccept(idx) := laneActive(idx) && !protocolError
  }

  private val flowLane0 = !firstValid && laneCanAccept(0)

  // A matching kill suppresses the output combinationally. Any top-level
  // connector that generates this exact kill must assert it in the same cycle
  // that downstream ROB/flush logic suppresses the matching completion.
  io.completeValid := firstValid || flowLane0
  io.completeRobValue := Mux(firstValid, firstEntry.key.rob.value, Mux(flowLane0, laneEntry(0).key.rob.value, 0.U))
  io.completeRowValid := Mux(firstValid, firstEntry.rowValid, flowLane0 && laneEntry(0).rowValid)
  io.completeRow := Mux(
    firstValid,
    firstEntry.row,
    Mux(flowLane0, laneEntry(0).row, 0.U.asTypeOf(new CommitTraceRow(traceParams))))

  private val dequeueStored = firstValid && io.outputReady
  private val flowLane0Consumed = flowLane0 && io.outputReady
  private val base0Valid = Mux(dequeueStored, secondValid, firstValid)
  private val base0Entry = Mux(dequeueStored, secondEntry, firstEntry)
  private val base1Valid = !dequeueStored && secondValid
  private val base1Entry = secondEntry
  private val baseCount = PopCount(Seq(base0Valid, base1Valid))
  private val freeSlots = 2.U(2.W) - baseCount
  private val lane0NeedsStore = laneCanAccept(0) && !flowLane0Consumed
  private val lane1NeedsStore = laneCanAccept(1)

  private val laneReady = Wire(Vec(2, Bool()))
  private val laneAccepted = Wire(Vec(2, Bool()))
  laneReady(0) := !protocolError && !laneCleared(0) &&
    Mux(flowLane0, io.outputReady || freeSlots >= 1.U, freeSlots >= 1.U)
  laneReady(1) := !protocolError && !laneCleared(1) &&
    Mux(lane0NeedsStore, freeSlots >= 2.U, freeSlots >= 1.U)
  for (idx <- 0 until 2) {
    laneAccepted(idx) := io.lanes(idx).valid && laneReady(idx)
    io.laneReady(idx) := laneReady(idx)
    io.laneAccepted(idx) := laneAccepted(idx)
  }

  private val lane0StoreFire = laneAccepted(0) && !flowLane0Consumed
  private val lane1StoreFire = laneAccepted(1)
  io.overflowBlocked :=
    !protocolError && ((laneActive(0) && !laneReady(0)) || (laneActive(1) && !laneReady(1)))
  io.residentCount := baseCount + lane0StoreFire.asUInt + lane1StoreFire.asUInt

  private val nextEntries = Wire(Vec(2, new ExecuteCompletionRetainerEntry(
    ptrWidth,
    effectiveRobEntries,
    traceParams,
    peIdWidth,
    stidWidth,
    tidWidth)))
  private val nextValid = Wire(Vec(2, Bool()))
  private val candidateValid = Wire(Vec(4, Bool()))
  private val candidateEntry = Wire(Vec(4, new ExecuteCompletionRetainerEntry(
    ptrWidth,
    effectiveRobEntries,
    traceParams,
    peIdWidth,
    stidWidth,
    tidWidth)))
  candidateValid(0) := base0Valid
  candidateEntry(0) := base0Entry
  candidateValid(1) := base1Valid
  candidateEntry(1) := base1Entry
  candidateValid(2) := lane0StoreFire
  candidateEntry(2) := laneEntry(0)
  candidateValid(3) := lane1StoreFire
  candidateEntry(3) := laneEntry(1)

  private val firstSelect = Wire(Vec(4, Bool()))
  private val secondSelect = Wire(Vec(4, Bool()))
  for (idx <- 0 until 4) {
    val priorCount =
      if (idx == 0) 0.U(3.W) else PopCount((0 until idx).map(candidateValid(_)))
    firstSelect(idx) := candidateValid(idx) && priorCount === 0.U
    secondSelect(idx) := candidateValid(idx) && priorCount === 1.U
  }

  nextValid(0) := candidateValid.asUInt.orR
  nextEntries(0) := Mux(
    nextValid(0),
    Mux1H(firstSelect, candidateEntry),
    0.U.asTypeOf(nextEntries(0)))
  nextValid(1) := secondSelect.asUInt.orR
  nextEntries(1) := Mux(
    nextValid(1),
    Mux1H(secondSelect, candidateEntry),
    0.U.asTypeOf(nextEntries(1)))

  for (idx <- 0 until 2) {
    slotValid(idx) := nextValid(idx)
    slots(idx) := nextEntries(idx)
  }
}
