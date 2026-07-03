package linxcore.lsu

import chisel3._
import chisel3.util.{PriorityEncoder, log2Ceil}

class LoadReplayReturnIexDrainPermitIO(val returnPipeCount: Int = 1) extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val sinkValid = Input(Bool())
  val pipeOccupiedMask = Input(UInt(returnPipeCount.W))

  val pipeFreeMask = Output(UInt(returnPipeCount.W))
  val anyPipeFree = Output(Bool())
  val selectedPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val drainReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoEntry = Output(Bool())
  val blockedByPipeFull = Output(Bool())
}

class LoadReplayReturnIexDrainPermit(val returnPipeCount: Int = 1) extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))
  private val allPipeMask = ((BigInt(1) << returnPipeCount) - 1).U(returnPipeCount.W)

  val io = IO(new LoadReplayReturnIexDrainPermitIO(returnPipeCount))

  val pipeFreeMask = (~io.pipeOccupiedMask).asUInt & allPipeMask
  val anyPipeFree = pipeFreeMask.orR
  val selectedPipeIndex =
    if (returnPipeCount == 1) 0.U(returnPipeIndexWidth.W)
    else PriorityEncoder(pipeFreeMask)
  val candidateValid = io.enable && !io.flush && io.sinkValid
  val drainReady = candidateValid && anyPipeFree

  io.pipeFreeMask := pipeFreeMask
  io.anyPipeFree := anyPipeFree
  io.selectedPipeIndex := Mux(drainReady, selectedPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.drainReady := drainReady
  io.blockedByDisabled := !io.enable && io.sinkValid
  io.blockedByFlush := io.enable && io.flush && io.sinkValid
  io.blockedByNoEntry := io.enable && !io.flush && !io.sinkValid
  io.blockedByPipeFull := candidateValid && !anyPipeFree
}
