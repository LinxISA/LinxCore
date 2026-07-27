package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder}

class OooThreadStageBufferIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooPipelineToken(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooPipelineToken(p))
  val occupancy = Output(UInt(p.countWidth(p.stidCount).W))
  val selectedValid = Output(Bool())
  val selectedStid = Output(UInt(p.stidWidth.W))
}

/** One private retained row per STID with one fair shared stage grant. */
class OooThreadStageBuffer(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooThreadStageBufferIO(p))

  val valid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val row = Reg(Vec(p.stidCount, new OooPipelineToken(p)))
  val rrStart = RegInit(0.U(p.stidWidth.W))
  val heldGrantValid = RegInit(false.B)
  val heldGrantStid = RegInit(0.U(p.stidWidth.W))

  val rotatedCandidates = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val index = if (p.stidCount == 1) 0.U else (rrStart + offset.U)(p.stidWidth - 1, 0)
    rotatedCandidates(offset) := valid(index) && !io.cancel(index)
  }
  val rotatedValid = rotatedCandidates.asUInt.orR
  val rotatedOffset = if (p.stidCount == 1) 0.U else PriorityEncoder(rotatedCandidates.asUInt)
  val rrSelected =
    if (p.stidCount == 1) 0.U(p.stidWidth.W)
    else (rrStart + rotatedOffset)(p.stidWidth - 1, 0)

  val selected = Mux(heldGrantValid, heldGrantStid, rrSelected)
  val selectedLive =
    if (p.stidCount == 1) valid(0) && !io.cancel(0)
    else valid(selected) && !io.cancel(selected)
  io.out.valid := Mux(heldGrantValid, selectedLive, rotatedValid)
  io.out.bits := (if (p.stidCount == 1) row(0) else row(selected))
  io.selectedValid := io.out.valid
  io.selectedStid := selected
  io.occupancy := PopCount(valid)

  val incomingInRange = io.in.bits.stid < p.stidCount.U
  val replacingSelected = io.out.fire && selected === io.in.bits.stid
  val incomingCancelled =
    if (p.stidCount == 1) io.cancel(0) else io.cancel(io.in.bits.stid)
  val incomingOccupied =
    if (p.stidCount == 1) valid(0) else valid(io.in.bits.stid)
  io.in.ready := incomingInRange && !incomingCancelled &&
    (!incomingOccupied || replacingSelected)

  when(io.out.valid && !io.out.ready && !heldGrantValid) {
    heldGrantValid := true.B
    heldGrantStid := selected
  }
  val heldGrantCancelled =
    if (p.stidCount == 1) io.cancel(0) else io.cancel(heldGrantStid)
  when(heldGrantValid && heldGrantCancelled) {
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
    when(io.in.fire && io.in.bits.stid === stid.U) {
      row(stid) := io.in.bits
      valid(stid) := true.B
    }
  }

  when(io.in.fire) {
    assert(incomingInRange, "OOO stage input STID must be in range")
  }
  when(heldGrantValid && io.out.valid && !io.out.ready) {
    assert(io.out.bits.stid === heldGrantStid,
      "OOO stage grant and payload must remain stable under backpressure")
  }
}

class LinxCoreOooShellIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooPipelineToken(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooPipelineToken(p))
  val d2Occupancy = Output(UInt(p.countWidth(p.stidCount).W))
  val d3Occupancy = Output(UInt(p.countWidth(p.stidCount).W))
  val s1Occupancy = Output(UInt(p.countWidth(p.stidCount).W))
  val d2SelectedStid = Output(UInt(p.stidWidth.W))
  val d3SelectedStid = Output(UInt(p.stidWidth.W))
  val s1SelectedStid = Output(UInt(p.stidWidth.W))
  val d2SelectedValid = Output(Bool())
  val d3SelectedValid = Output(Bool())
  val s1SelectedValid = Output(Bool())
}

/** O1 production shell: independent per-STID D2/D3/S1 retained staging. */
class LinxCoreOooShell(val p: OooParams = OooParams()) extends Module {
  val io = IO(new LinxCoreOooShellIO(p))

  val d2 = Module(new OooThreadStageBuffer(p))
  val d3 = Module(new OooThreadStageBuffer(p))
  val s1 = Module(new OooThreadStageBuffer(p))

  d2.io.in <> io.in
  d2.io.cancel := io.cancel
  d3.io.in <> d2.io.out
  d3.io.cancel := io.cancel
  s1.io.in <> d3.io.out
  s1.io.cancel := io.cancel
  io.out <> s1.io.out

  io.d2Occupancy := d2.io.occupancy
  io.d3Occupancy := d3.io.occupancy
  io.s1Occupancy := s1.io.occupancy
  io.d2SelectedStid := d2.io.selectedStid
  io.d3SelectedStid := d3.io.selectedStid
  io.s1SelectedStid := s1.io.selectedStid
  io.d2SelectedValid := d2.io.selectedValid
  io.d3SelectedValid := d3.io.selectedValid
  io.s1SelectedValid := s1.io.selectedValid

  assert(d2.io.occupancy <= p.stidCount.U)
  assert(d3.io.occupancy <= p.stidCount.U)
  assert(s1.io.occupancy <= p.stidCount.U)
}
