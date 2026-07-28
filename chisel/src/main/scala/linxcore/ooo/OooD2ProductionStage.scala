package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder}

class OooD2ThreadStageBufferIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val fence = Input(Vec(p.stidCount, Bool()))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooD2GroupedTransaction(p))
  val occupancy = Output(UInt(p.countWidth(p.stidCount).W))
  val selectedStid = Output(UInt(p.stidWidth.W))
}

/** One immutable D2 preview transaction per STID with a retained fair grant. */
class OooD2ThreadStageBuffer(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD2ThreadStageBufferIO(p))

  val valid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val rows = Reg(Vec(p.stidCount, new OooD2GroupedTransaction(p)))
  val rrStart = RegInit(0.U(p.stidWidth.W))
  val heldGrantValid = RegInit(false.B)
  val heldGrantStid = RegInit(0.U(p.stidWidth.W))

  val rotated = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val index =
      if (p.stidCount == 1) 0.U
      else (rrStart + offset.U)(p.stidWidth - 1, 0)
    rotated(offset) := valid(index) && !io.cancel(index) && !io.fence(index)
  }
  val rrValid = rotated.asUInt.orR
  val rrOffset = if (p.stidCount == 1) 0.U else PriorityEncoder(rotated.asUInt)
  val rrSelected =
    if (p.stidCount == 1) 0.U(p.stidWidth.W)
    else (rrStart + rrOffset)(p.stidWidth - 1, 0)
  val heldGrantBlocked =
    if (p.stidCount == 1) io.cancel(0) || io.fence(0)
    else io.cancel(heldGrantStid) || io.fence(heldGrantStid)
  val heldGrantUsable = heldGrantValid && !heldGrantBlocked
  val selected = Mux(heldGrantUsable, heldGrantStid, rrSelected)
  val selectedLive =
    if (p.stidCount == 1) valid(0) && !io.cancel(0) && !io.fence(0)
    else valid(selected) && !io.cancel(selected) && !io.fence(selected)

  io.out.valid := Mux(heldGrantUsable, selectedLive, rrValid)
  io.out.bits := (if (p.stidCount == 1) rows(0) else rows(selected))
  io.selectedStid := selected
  io.occupancy := PopCount(valid)

  val incomingStid = io.in.bits.plan.stid
  val incomingInRange = incomingStid < p.stidCount.U
  val incomingCancelled =
    if (p.stidCount == 1) io.cancel(0) else io.cancel(incomingStid)
  val incomingFenced =
    if (p.stidCount == 1) io.fence(0) else io.fence(incomingStid)
  val incomingOccupied =
    if (p.stidCount == 1) valid(0) else valid(incomingStid)
  val replacingSelected = io.out.fire && selected === incomingStid
  io.in.ready := incomingInRange && !incomingCancelled && !incomingFenced &&
    (!incomingOccupied || replacingSelected)

  val captureHeldGrant = io.out.valid && !io.out.ready && !heldGrantUsable
  when(captureHeldGrant) {
    heldGrantValid := true.B
    heldGrantStid := selected
  }
  when(heldGrantValid && heldGrantBlocked && !captureHeldGrant) {
    heldGrantValid := false.B
  }
  when(io.out.fire) {
    heldGrantValid := false.B
    rrStart :=
      (if (p.stidCount == 1) 0.U else (selected + 1.U)(p.stidWidth - 1, 0))
  }

  for (stid <- 0 until p.stidCount) {
    when(io.cancel(stid)) {
      valid(stid) := false.B
    }.elsewhen(io.out.fire && selected === stid.U) {
      valid(stid) := false.B
    }
    when(io.in.fire && incomingStid === stid.U) {
      rows(stid) := io.in.bits
      valid(stid) := true.B
    }
  }

  when(io.in.fire) {
    assert(io.in.bits.plan.stid === io.in.bits.decoded.stid)
    assert(io.in.bits.plan.peId === io.in.bits.decoded.peId)
    assert(io.in.bits.plan.epoch === io.in.bits.decoded.epoch)
  }
  when(heldGrantUsable && io.out.valid && !io.out.ready) {
    assert(io.out.bits.plan.stid === heldGrantStid)
  }
}

class OooD2ProductionStageIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooD1DecodedPacket(p)))
  val tailSlot = Input(Vec(p.stidCount, UInt(p.ridSlotWidth.W)))
  val tailGeneration = Input(Vec(p.stidCount, UInt(p.ridGenerationWidth.W)))
  val tailEpoch = Input(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
  val nextTransactionId = Input(Vec(p.stidCount, UInt(p.transactionIdWidth.W)))
  val fence = Input(Vec(p.stidCount, Bool()))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooD2GroupedTransaction(p))
  val occupancy = Output(UInt(p.countWidth(p.stidCount).W))
  val selectedStid = Output(UInt(p.stidWidth.W))
}

/** D2 virtual planning plus private per-STID retention. */
class OooD2ProductionStage(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD2ProductionStageIO(p))
  val planner = Module(new OooD2GroupPlanner(p))
  val stage = Module(new OooD2ThreadStageBuffer(p))

  planner.io.in <> io.in
  planner.io.tailSlot := io.tailSlot
  planner.io.tailGeneration := io.tailGeneration
  planner.io.tailEpoch := io.tailEpoch
  planner.io.nextTransactionId := io.nextTransactionId
  stage.io.in <> planner.io.out
  stage.io.fence := io.fence
  stage.io.cancel := io.cancel
  io.out <> stage.io.out
  io.occupancy := stage.io.occupancy
  io.selectedStid := stage.io.selectedStid
}
