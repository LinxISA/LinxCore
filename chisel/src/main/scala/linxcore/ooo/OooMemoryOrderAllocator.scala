package linxcore.ooo

import chisel3._
import chisel3.util.Valid
import linxcore.params.CoreParams
import linxcore.top.interface._

class OooMemoryOrderAllocatorIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Valid(new D2AdmissionGroup(p)))
  val prepareReady = Output(Bool())
  val prepared = Output(new MemoryOrderReservation(p))
  val preparedLanes = Output(Vec(p.ooo.d3PrefixWidth, new MemoryOrderMeta(p)))
  val reserveFire = Input(Bool())
  val publishPrepare = Flipped(Valid(new D3RenameGroup(p)))
  val publishReady = Output(Bool())
  val publishFire = Input(Bool())
  val cancel = Input(Vec(p.ooo.stidCount, Bool()))
  val recoveryPrepare = Flipped(Valid(new OOORobMemoryRecovery(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryFire = Input(Bool())
  val next = Output(Vec(p.ooo.stidCount, new MemoryOrderState(p)))
  val provisional = Output(Vec(p.ooo.stidCount, new MemoryOrderReservation(p)))
  val provisionalLanes = Output(Vec(p.ooo.stidCount,
    Vec(p.ooo.d3PrefixWidth, new MemoryOrderMeta(p))))
}

/** Sole OOO owner of full LSID/LID/SID program-order tails. */
class OooMemoryOrderAllocator(val p: CoreParams) extends Module {
  val io = IO(new OooMemoryOrderAllocatorIO(p))
  private val width = p.ooo.d3PrefixWidth
  private def select[T <: Data](values: Vec[T], index: UInt): T =
    if (p.ooo.stidCount == 1) values(0) else values(index)

  private def sameState(a: MemoryOrderState, b: MemoryOrderState): Bool =
    a.asUInt === b.asUInt

  val nextState = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U.asTypeOf(new MemoryOrderState(p)))))
  val provisionalValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  val provisionalRows = Reg(Vec(p.ooo.stidCount, new MemoryOrderReservation(p)))
  val provisionalMetas = Reg(Vec(p.ooo.stidCount,
    Vec(width, new MemoryOrderMeta(p))))

  val prepareStidRaw = io.prepare.bits.entries(0).uop.rob.stid
  val prepareStidInRange = prepareStidRaw < p.ooo.stidCount.U
  val prepareStid = Mux(prepareStidInRange, prepareStidRaw, 0.U)
  val states = Wire(Vec(width + 1, new MemoryOrderState(p)))
  val metas = Wire(Vec(width, new MemoryOrderMeta(p)))
  val laneExact = Wire(Vec(width, Bool()))
  states(0) := select(nextState, prepareStid)
  for (lane <- 0 until width) {
    val active = lane.U < io.prepare.bits.count
    val uop = io.prepare.bits.entries(lane).uop
    val requestCount = uop.memory.requestCount
    val typedMemory = uop.memory.valid && (uop.memory.isLoad ^ uop.memory.isStore)
    val memoryActive = active && typedMemory && requestCount.orR
    val loadActive = memoryActive && uop.memory.isLoad
    val storeActive = memoryActive && uop.memory.isStore
    metas(lane) := 0.U.asTypeOf(metas(lane))
    metas(lane).requestCount := Mux(memoryActive, requestCount, 0.U)
    metas(lane).firstLsid := states(lane).lsid
    metas(lane).firstLid := states(lane).lid
    metas(lane).firstSid := states(lane).sid
    metas(lane).yostValid := states(lane).yostValid
    metas(lane).yostLsid := states(lane).yostLsid
    metas(lane).yostSid := states(lane).yostSid
    metas(lane).yoldValid := states(lane).yoldValid
    metas(lane).yoldLsid := states(lane).yoldLsid
    metas(lane).yoldLid := states(lane).yoldLid
    states(lane + 1) := states(lane)
    states(lane + 1).lsid := states(lane).lsid +
      Mux(memoryActive, requestCount, 0.U)
    states(lane + 1).lid := states(lane).lid +
      Mux(loadActive, requestCount, 0.U)
    states(lane + 1).sid := states(lane).sid +
      Mux(storeActive, requestCount, 0.U)
    when(loadActive) {
      states(lane + 1).yoldValid := true.B
      states(lane + 1).yoldLsid := states(lane).lsid + requestCount - 1.U
      states(lane + 1).yoldLid := states(lane).lid + requestCount - 1.U
    }
    when(storeActive) {
      states(lane + 1).yostValid := true.B
      states(lane + 1).yostLsid := states(lane).lsid + requestCount - 1.U
      states(lane + 1).yostSid := states(lane).sid + requestCount - 1.U
    }
    laneExact(lane) := !active || (uop.valid &&
      (typedMemory === requestCount.orR) &&
      requestCount <= p.maxMemoryRequestsPerInstruction.U)
  }
  val countExact = io.prepare.bits.count.orR &&
    io.prepare.bits.count <= width.U
  val commonStidExact = (0 until width).map { lane =>
    lane.U >= io.prepare.bits.count ||
      io.prepare.bits.entries(lane).uop.rob.stid === prepareStidRaw
  }.reduce(_ && _)
  val publishStidRaw = io.publishPrepare.bits.entries(0).uop.decoded.rob.stid
  val publishStid = Mux(publishStidRaw < p.ooo.stidCount.U, publishStidRaw, 0.U)
  val replacing = io.publishFire && io.publishPrepare.valid &&
    publishStidRaw === prepareStidRaw
  val slotAvailable = !select(provisionalValid, prepareStid) || replacing
  io.prepareReady := prepareStidInRange && countExact && commonStidExact &&
    laneExact.asUInt.andR && slotAvailable
  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.valid := io.prepare.valid && io.prepareReady
  io.prepared.stid := prepareStidRaw
  io.prepared.count := io.prepare.bits.count
  io.prepared.before := states(0)
  io.prepared.after := states(width)
  io.preparedLanes := metas

  val livePublish = select(provisionalRows, publishStid)
  val publishExact = publishStidRaw < p.ooo.stidCount.U &&
    select(provisionalValid, publishStid) && livePublish.valid &&
    livePublish.stid === publishStidRaw &&
    livePublish.count === io.publishPrepare.bits.count &&
    io.publishPrepare.bits.memoryOrder.asUInt === livePublish.asUInt &&
    (0 until width).map { lane =>
      lane.U >= livePublish.count ||
        io.publishPrepare.bits.entries(lane).memoryOrder.asUInt ===
          select(provisionalMetas, publishStid)(lane).asUInt
    }.reduce(_ && _) &&
    sameState(livePublish.after, select(nextState, publishStid))
  io.publishReady := io.publishPrepare.valid && publishExact

  val recoveryStidRaw = io.recoveryPrepare.bits.stid
  val recoveryStid = Mux(recoveryStidRaw < p.ooo.stidCount.U,
    recoveryStidRaw, 0.U)
  val recoveryLiveExact = Mux(select(provisionalValid, recoveryStid),
    sameState(select(provisionalRows, recoveryStid).before,
      io.recoveryPrepare.bits.oldTail) &&
      sameState(select(provisionalRows, recoveryStid).after,
        select(nextState, recoveryStid)),
    sameState(select(nextState, recoveryStid), io.recoveryPrepare.bits.oldTail))
  io.recoveryPrepareReady := io.recoveryPrepare.valid &&
    io.recoveryPrepare.bits.valid && recoveryStidRaw < p.ooo.stidCount.U &&
    recoveryLiveExact

  when(io.reserveFire) {
    assert(io.prepare.valid && io.prepareReady)
    select(provisionalValid, prepareStid) := true.B
    select(provisionalRows, prepareStid) := io.prepared
    select(provisionalMetas, prepareStid) := io.preparedLanes
    select(nextState, prepareStid) := io.prepared.after
  }
  when(io.publishFire) {
    assert(io.publishReady)
    when(!(io.reserveFire && prepareStid === publishStid)) {
      select(provisionalValid, publishStid) := false.B
      select(provisionalRows, publishStid) := 0.U.asTypeOf(livePublish)
    }
  }
  for (stid <- 0 until p.ooo.stidCount) {
    when(io.cancel(stid) && provisionalValid(stid)) {
      nextState(stid) := provisionalRows(stid).before
      provisionalValid(stid) := false.B
      provisionalRows(stid) := 0.U.asTypeOf(provisionalRows(stid))
    }
  }
  when(io.recoveryFire) {
    assert(io.recoveryPrepareReady)
    select(nextState, recoveryStid) := io.recoveryPrepare.bits.newTail
    select(provisionalValid, recoveryStid) := false.B
    select(provisionalRows, recoveryStid) := 0.U.asTypeOf(
      select(provisionalRows, recoveryStid))
  }
  io.next := nextState
  for (stid <- 0 until p.ooo.stidCount) {
    io.provisional(stid) := Mux(provisionalValid(stid), provisionalRows(stid),
      0.U.asTypeOf(provisionalRows(stid)))
    io.provisionalLanes(stid) := Mux(provisionalValid(stid),
      provisionalMetas(stid),
      0.U.asTypeOf(provisionalMetas(stid)))
  }
}
