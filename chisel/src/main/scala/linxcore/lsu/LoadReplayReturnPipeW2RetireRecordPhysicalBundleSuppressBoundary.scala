package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundaryIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val selected = Input(Bool())
  val selectedMask = Input(UInt(4.W))
  val allOrNoneInputMask = Input(Bool())

  val capture = Output(Bool())
  val registeredValid = Output(Bool())
  val registeredMask = Output(UInt(4.W))
  val registeredFullMask = Output(Bool())
  val blockedByNoSelection = Output(Bool())
  val blockedByPartialMask = Output(Bool())
  val clearedByFlush = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundaryIO)

  val active = io.enable && !io.flush
  val selectedFullMask =
    active &&
      io.selected &&
      io.allOrNoneInputMask &&
      io.selectedMask === "b1111".U

  val registeredValid = RegInit(false.B)
  val registeredMask = RegInit(0.U(4.W))

  when(!io.enable || io.flush) {
    registeredValid := false.B
    registeredMask := 0.U
  }.otherwise {
    registeredValid := selectedFullMask
    registeredMask := Mux(selectedFullMask, io.selectedMask, 0.U)
  }

  io.capture := selectedFullMask
  io.registeredValid := registeredValid
  io.registeredMask := registeredMask
  io.registeredFullMask := registeredValid && registeredMask === "b1111".U
  io.blockedByNoSelection := active && !io.selected
  io.blockedByPartialMask := active && io.selected && !io.allOrNoneInputMask
  io.clearedByFlush := io.flush && registeredValid
}
