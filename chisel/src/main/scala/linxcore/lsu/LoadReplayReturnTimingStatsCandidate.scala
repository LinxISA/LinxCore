package linxcore.lsu

import chisel3._

class LoadReplayReturnTimingStatsCandidateIO(val cycleWidth: Int = 64) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val finalMetadataValid = Input(Bool())
  val currentCycle = Input(UInt(cycleWidth.W))
  val memLsuRecvCycle = Input(UInt(cycleWidth.W))
  val memLdqPickCycle = Input(UInt(cycleWidth.W))
  val memLdqIssueCycle = Input(UInt(cycleWidth.W))
  val memL1MissCycle = Input(UInt(cycleWidth.W))
  val memL2MissCycle = Input(UInt(cycleWidth.W))
  val memMemRntCycle = Input(UInt(cycleWidth.W))
  val memL2RntCycle = Input(UInt(cycleWidth.W))
  val memL1RntCycle = Input(UInt(cycleWidth.W))

  val candidateValid = Output(Bool())
  val timingSidebandValid = Output(Bool())
  val iqNameSidebandValid = Output(Bool())
  val ldRntCycleValid = Output(Bool())
  val statsUpdateValid = Output(Bool())
  val lsuRecvCycle = Output(UInt(cycleWidth.W))
  val ldqPickCycle = Output(UInt(cycleWidth.W))
  val ldqIssueCycle = Output(UInt(cycleWidth.W))
  val l1MissCycle = Output(UInt(cycleWidth.W))
  val l2MissCycle = Output(UInt(cycleWidth.W))
  val memRntCycle = Output(UInt(cycleWidth.W))
  val l2RntCycle = Output(UInt(cycleWidth.W))
  val l1RntCycle = Output(UInt(cycleWidth.W))
  val ldRntCycle = Output(UInt(cycleWidth.W))
  val statsLatencyIncrement = Output(UInt(cycleWidth.W))
  val latencyUnderflow = Output(Bool())
  val readyForPipeInsert = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoFinalMetadata = Output(Bool())
}

class LoadReplayReturnTimingStatsCandidate(val cycleWidth: Int = 64) extends Module {
  require(cycleWidth > 0, "cycleWidth must be positive")

  val io = IO(new LoadReplayReturnTimingStatsCandidateIO(cycleWidth))

  val candidateValid = io.enable && !io.flush && io.finalMetadataValid
  val latencyIncrement = io.currentCycle - io.memLsuRecvCycle

  io.candidateValid := candidateValid
  io.timingSidebandValid := candidateValid
  io.iqNameSidebandValid := candidateValid
  io.ldRntCycleValid := candidateValid
  io.statsUpdateValid := candidateValid
  io.lsuRecvCycle := 0.U
  io.ldqPickCycle := 0.U
  io.ldqIssueCycle := 0.U
  io.l1MissCycle := 0.U
  io.l2MissCycle := 0.U
  io.memRntCycle := 0.U
  io.l2RntCycle := 0.U
  io.l1RntCycle := 0.U
  io.ldRntCycle := 0.U
  io.statsLatencyIncrement := 0.U
  io.latencyUnderflow := candidateValid && io.currentCycle < io.memLsuRecvCycle
  io.readyForPipeInsert := candidateValid
  io.blockedByDisabled := !io.enable && io.finalMetadataValid
  io.blockedByFlush := io.enable && io.flush && io.finalMetadataValid
  io.blockedByNoFinalMetadata := io.enable && !io.flush && !io.finalMetadataValid

  when(candidateValid) {
    io.lsuRecvCycle := io.memLsuRecvCycle
    io.ldqPickCycle := io.memLdqPickCycle
    io.ldqIssueCycle := io.memLdqIssueCycle
    io.l1MissCycle := io.memL1MissCycle
    io.l2MissCycle := io.memL2MissCycle
    io.memRntCycle := io.memMemRntCycle
    io.l2RntCycle := io.memL2RntCycle
    io.l1RntCycle := io.memL1RntCycle
    io.ldRntCycle := io.currentCycle
    io.statsLatencyIncrement := latencyIncrement
  }
}
