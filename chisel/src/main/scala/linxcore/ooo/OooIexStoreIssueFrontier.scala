package linxcore.ooo

import chisel3._
import chisel3.util.{PopCount, Valid}

/** One compact canonical-IQ row projected into the store issue frontier. */
class OooIexStoreFrontierCandidate(val p: OooParams = OooParams())
    extends Bundle {
  val resident = Bool()
  val isStore = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val order = new OooIexStoreOrderState(p)
}

class OooIexStoreIssueFrontierIO(
    val p: OooParams,
    val candidateCount: Int)
    extends Bundle {
  val candidates = Input(Vec(candidateCount,
    new OooIexStoreFrontierCandidate(p)))
  val allowed = Output(Vec(candidateCount, Bool()))
  val blocked = Output(Vec(candidateCount, Bool()))
  val malformed = Output(Vec(candidateCount, Bool()))
  val frontiers = Output(Vec(p.stidCount,
    Valid(new OooIexStoreOrderState(p))))
  val blockedCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(candidateCount).W)))
}

/** Oldest-logical-store admission frontier across every physical IQ bank.
  *
  * A store may enter P1/I1 only when no older logical store remains resident
  * in the same STID.  The address and data children of one store share an
  * exact logical key and are therefore both admitted.  Loads, non-memory
  * work, and peer STIDs are unaffected.
  *
  * The implementation is a per-STID reduction over compact scheduling state,
  * not a second queue.  IQ release, retry, and recovery remain the only
  * residency mutations; this combinational projection simply recomputes the
  * current frontier from that canonical state.
  */
class OooIexStoreIssueFrontier(
    val p: OooParams = OooParams(),
    val candidateCount: Int)
    extends Module {
  require(candidateCount > 0, "store frontier needs at least one candidate")
  require(BigInt(candidateCount) * p.maxMemoryRequestsPerInstruction <
    (BigInt(1) << (p.lsidWidth - 1)),
    "live store population must fit within half of the serial namespace")

  val io = IO(new OooIexStoreIssueFrontierIO(p, candidateCount))

  private def serialOlder(left: UInt, right: UInt): Bool = {
    val delta = (right - left)(p.lsidWidth - 1, 0)
    delta.orR && !delta(p.lsidWidth - 1)
  }

  private def sameLogical(
      left: OooIexStoreOrderState,
      right: OooIexStoreOrderState): Bool =
    left.asUInt === right.asUInt

  private def chooseOldest(
      left: (Bool, OooIexStoreOrderState),
      right: (Bool, OooIexStoreOrderState)):
      (Bool, OooIexStoreOrderState) = {
    val rightWins = right._1 && (!left._1 ||
      serialOlder(right._2.firstStoreId, left._2.firstStoreId))
    val result = Wire(new OooIexStoreOrderState(p))
    result := Mux(rightWins, right._2, left._2)
    (left._1 || right._1, result)
  }

  private def selectTree(
      values: Seq[(Bool, OooIexStoreOrderState)]):
      (Bool, OooIexStoreOrderState) = {
    require(values.nonEmpty)
    if (values.size == 1) values.head
    else {
      val (left, right) = values.splitAt(values.size / 2)
      chooseOldest(selectTree(left), selectTree(right))
    }
  }

  val candidateExact = Wire(Vec(candidateCount, Bool()))
  val activeStore = Wire(Vec(candidateCount, Bool()))
  for (index <- 0 until candidateCount) {
    val candidate = io.candidates(index)
    val order = candidate.order
    activeStore(index) := candidate.resident && candidate.isStore
    candidateExact(index) := !activeStore(index) || (
      order.valid && candidate.stid < p.stidCount.U &&
        order.logicalMember.group.valid &&
        order.logicalMember.bid.valid &&
        order.logicalMember.group.peId === candidate.peId &&
        order.logicalMember.group.stid === candidate.stid &&
        order.requestCount > 0.U &&
        order.requestCount <= p.maxMemoryRequestsPerInstruction.U)
  }

  val selected = Wire(Vec(p.stidCount,
    Valid(new OooIexStoreOrderState(p))))
  for (stid <- 0 until p.stidCount) {
    val values = (0 until candidateCount).map { index =>
      val candidate = io.candidates(index)
      (activeStore(index) && candidateExact(index) &&
        candidate.stid === stid.U, candidate.order)
    }
    val winner = selectTree(values)
    selected(stid).valid := winner._1
    selected(stid).bits := winner._2
    io.frontiers(stid) := selected(stid)
  }

  val stidMalformed = Wire(Vec(p.stidCount, Bool()))
  for (stid <- 0 until p.stidCount) {
    val malformedShape = (0 until candidateCount).map { index =>
      activeStore(index) && io.candidates(index).stid === stid.U &&
        !candidateExact(index)
    }.reduce(_ || _)
    val serialCollisionOrDrift = (0 until candidateCount).map { index =>
      val candidate = io.candidates(index)
      val order = candidate.order
      val atStid = activeStore(index) && candidateExact(index) &&
        candidate.stid === stid.U && selected(stid).valid
      val sameStoreId = order.firstStoreId ===
        selected(stid).bits.firstStoreId
      val sameOwner = sameLogical(order, selected(stid).bits)
      val youngerByStore = serialOlder(
        selected(stid).bits.firstStoreId, order.firstStoreId)
      val youngerByLsid = serialOlder(
        selected(stid).bits.firstLsid, order.firstLsid)
      atStid && ((sameStoreId && !sameOwner) ||
        (!sameStoreId && (youngerByStore =/= youngerByLsid)))
    }.reduce(_ || _)
    stidMalformed(stid) := malformedShape || serialCollisionOrDrift
  }

  for (index <- 0 until candidateCount) {
    val candidate = io.candidates(index)
    val safeStid = Mux(candidate.stid < p.stidCount.U, candidate.stid, 0.U)
    val frontier = if (p.stidCount == 1) selected(0) else selected(safeStid)
    val malformed = if (p.stidCount == 1)
      stidMalformed(0)
    else stidMalformed(safeStid)
    val exactFrontier = frontier.valid &&
      sameLogical(candidate.order, frontier.bits)
    val storeAllowed = activeStore(index) && candidateExact(index) &&
      !malformed && exactFrontier
    io.allowed(index) := !activeStore(index) || storeAllowed
    io.blocked(index) := activeStore(index) && !storeAllowed
    io.malformed(index) := activeStore(index) &&
      (!candidateExact(index) || malformed)
  }

  for (stid <- 0 until p.stidCount) {
    io.blockedCount(stid) := PopCount(VecInit(
      (0 until candidateCount).map { index =>
        io.blocked(index) && io.candidates(index).stid === stid.U
      }).asUInt)
  }
}
