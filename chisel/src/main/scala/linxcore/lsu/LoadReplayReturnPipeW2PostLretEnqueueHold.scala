package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeW2PostLretEnqueueHoldIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val completionClearSlot = Input(Bool())
  val liveClear = Input(Bool())
  val lretEnqueueAccepted = Input(Bool())

  val suppressCurrentClear = Output(Bool())
  val releaseClear = Output(Bool())
  val completionReady = Output(Bool())
  val holdActive = Output(Bool())
  val holdStart = Output(Bool())
  val holdRelease = Output(Bool())
}

class LoadReplayReturnPipeW2PostLretEnqueueHold(val holdCycles: Int = 0) extends Module {
  require(holdCycles >= 0, "holdCycles must be nonnegative")

  val io = IO(new LoadReplayReturnPipeW2PostLretEnqueueHoldIO)

  if (holdCycles > 0) {
    val holdWidth = math.max(1, log2Ceil(holdCycles + 1))
    val holdActiveReg = RegInit(false.B)
    val holdCountReg = RegInit(0.U(holdWidth.W))
    val holdStart =
      io.enable && !io.flush &&
        !holdActiveReg &&
        io.slotOccupied &&
        io.completionClearSlot &&
        io.liveClear &&
        io.lretEnqueueAccepted
    val holdRelease = holdActiveReg && holdCountReg === 0.U

    when(io.flush || !io.enable) {
      holdActiveReg := false.B
      holdCountReg := 0.U
    }.elsewhen(holdStart) {
      holdActiveReg := true.B
      holdCountReg := holdCycles.U
    }.elsewhen(holdRelease) {
      holdActiveReg := false.B
      holdCountReg := 0.U
    }.elsewhen(holdActiveReg) {
      holdCountReg := holdCountReg - 1.U
    }

    io.suppressCurrentClear := holdStart
    io.releaseClear := io.enable && !io.flush && holdRelease
    io.completionReady := !holdActiveReg
    io.holdActive := holdActiveReg
    io.holdStart := holdStart
    io.holdRelease := io.enable && !io.flush && holdRelease
  } else {
    io.suppressCurrentClear := false.B
    io.releaseClear := false.B
    io.completionReady := true.B
    io.holdActive := false.B
    io.holdStart := false.B
    io.holdRelease := false.B
  }
}
