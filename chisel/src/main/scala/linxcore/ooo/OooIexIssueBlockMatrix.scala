package linxcore.ooo

import chisel3._

/** Stable bit assignments for physical issue-policy diagnostics. */
object OooIexIssueBlockReason {
  val GlobalQuiesce = 0
  val PowerThrottle = 1
  val ClassPressure = 2
  val LoadQueuePressure = 3
  val StoreWindowPressure = 4
  val DomainStructural = 5
  val LatencyReservation = 6
  val ReflowReservation = 7
  val SideDoorConflict = 8
  val ResultBusReservation = 9
  val Count = 10
}

/** Typed physical conditions which may suppress a picker candidate.
  *
  * Every mask is indexed by STID. Class pressure is shared by all domains
  * projecting that uop class; the remaining domain masks describe resources
  * private to one physical picker/pipe. These are scheduling conditions, not
  * IQ mutation commands.
  */
class OooIexIssuePolicy(val p: OooParams = OooParams()) extends Bundle {
  val globalQuiesce = Bool()
  val powerThrottle = Bool()
  val classPressure = Vec(p.iqClassCount, UInt(p.stidCount.W))
  val loadQueuePressure = UInt(p.stidCount.W)
  val storeWindowPressure = UInt(p.stidCount.W)
  val domainStructural = Vec(p.iexIssueDomainCount, UInt(p.stidCount.W))
  val latencyReservation = Vec(p.iexIssueDomainCount, UInt(p.stidCount.W))
  val reflowReservation = Vec(p.iexIssueDomainCount, UInt(p.stidCount.W))
  val sideDoorConflict = Vec(p.iexIssueDomainCount, UInt(p.stidCount.W))
  val resultBusReservation = Vec(p.iexIssueDomainCount, UInt(p.stidCount.W))
}

class OooIexIssueBlockQuery(val p: OooParams = OooParams()) extends Bundle {
  val uopClass = OooUopClass()
  val stid = UInt(p.stidWidth.W)
  val isStore = Bool()
}

class OooIexIssuePolicyBlockEvent(val p: OooParams = OooParams())
    extends Bundle {
  val token = new OooIexPickToken(p)
  val reasonMask = UInt(OooIexIssueBlockReason.Count.W)
}

object OooIexIssueBlockMatrix {
  /** Compute the complete reason vector for one candidate and physical domain. */
  def reasons(
      p: OooParams,
      policy: OooIexIssuePolicy,
      domain: Int,
      query: OooIexIssueBlockQuery): UInt = {
    require(domain >= 0 && domain < p.iexIssueDomainCount)
    val result = Wire(Vec(OooIexIssueBlockReason.Count, Bool()))
    result := VecInit(Seq.fill(OooIexIssueBlockReason.Count)(false.B))

    val stidInRange = query.stid < p.stidCount.U
    val safeStid = Mux(stidInRange, query.stid, 0.U)
    val uopClass = query.uopClass
    val isLoadAddress = uopClass === OooUopClass.Agu && !query.isStore
    val isStorePath = query.isStore &&
      (uopClass === OooUopClass.Agu || uopClass === OooUopClass.Std)

    result(OooIexIssueBlockReason.GlobalQuiesce) := policy.globalQuiesce
    result(OooIexIssueBlockReason.PowerThrottle) := policy.powerThrottle
    result(OooIexIssueBlockReason.ClassPressure) := stidInRange &&
      policy.classPressure(uopClass.asUInt)(safeStid)
    result(OooIexIssueBlockReason.LoadQueuePressure) := stidInRange &&
      isLoadAddress && policy.loadQueuePressure(safeStid)
    result(OooIexIssueBlockReason.StoreWindowPressure) := stidInRange &&
      isStorePath && policy.storeWindowPressure(safeStid)
    result(OooIexIssueBlockReason.DomainStructural) := stidInRange &&
      policy.domainStructural(domain)(safeStid)
    result(OooIexIssueBlockReason.LatencyReservation) := stidInRange &&
      policy.latencyReservation(domain)(safeStid)
    result(OooIexIssueBlockReason.ReflowReservation) := stidInRange &&
      policy.reflowReservation(domain)(safeStid)
    result(OooIexIssueBlockReason.SideDoorConflict) := stidInRange &&
      policy.sideDoorConflict(domain)(safeStid)
    result(OooIexIssueBlockReason.ResultBusReservation) := stidInRange &&
      policy.resultBusReservation(domain)(safeStid)
    result.asUInt
  }
}

class OooIexIssueBlockMatrixIO(val p: OooParams = OooParams())
    extends Bundle {
  val policy = Input(new OooIexIssuePolicy(p))
  val queries = Input(Vec(p.iexIssueDomainCount,
    new OooIexIssueBlockQuery(p)))
  val reasonMasks = Output(Vec(p.iexIssueDomainCount,
    UInt(OooIexIssueBlockReason.Count.W)))
  val blocked = Output(UInt(p.iexIssueDomainCount.W))
}

/** Combinational reference owner for typed class/domain issue blocking.
  *
  * The canonical IQ uses the same `reasons` function at each physical row.
  * This module provides a compact independently testable boundary for policy
  * construction and integration diagnostics.
  */
class OooIexIssueBlockMatrix(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexIssueBlockMatrixIO(p))

  for (domain <- 0 until p.iexIssueDomainCount) {
    io.reasonMasks(domain) := OooIexIssueBlockMatrix.reasons(
      p, io.policy, domain, io.queries(domain))
  }
  io.blocked := VecInit(io.reasonMasks.map(_.orR)).asUInt
}
