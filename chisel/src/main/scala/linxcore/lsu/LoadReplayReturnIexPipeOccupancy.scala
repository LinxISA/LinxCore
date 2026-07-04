package linxcore.lsu

import chisel3._

class LoadReplayReturnIexPipeOccupancyIO(val returnPipeCount: Int = 1) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveRequested = Input(Bool())
  val livePipeOccupiedMask = Input(UInt(returnPipeCount.W))

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val allPipeMask = Output(UInt(returnPipeCount.W))
  val maskedLivePipeOccupiedMask = Output(UInt(returnPipeCount.W))
  val pipeOccupiedMask = Output(UInt(returnPipeCount.W))
  val forcedFull = Output(Bool())
  val anyPipeOccupied = Output(Bool())
  val allPipesOccupied = Output(Bool())
  val anyPipeFree = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnIexPipeOccupancy(val returnPipeCount: Int = 1) extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val allPipeMask = ((BigInt(1) << returnPipeCount) - 1).U(returnPipeCount.W)

  val io = IO(new LoadReplayReturnIexPipeOccupancyIO(returnPipeCount))

  val active = io.enable && !io.flush
  val requestActive = active && io.liveRequested
  val maskedLivePipeOccupiedMask = io.livePipeOccupiedMask & allPipeMask
  val pipeOccupiedMask = Mux(requestActive, maskedLivePipeOccupiedMask, allPipeMask)

  io.active := active
  io.requestActive := requestActive
  io.allPipeMask := allPipeMask
  io.maskedLivePipeOccupiedMask := maskedLivePipeOccupiedMask
  io.pipeOccupiedMask := pipeOccupiedMask
  io.forcedFull := !requestActive
  io.anyPipeOccupied := pipeOccupiedMask.orR
  io.allPipesOccupied := pipeOccupiedMask === allPipeMask
  io.anyPipeFree := (pipeOccupiedMask ^ allPipeMask).orR
  io.blockedByDisabled := !io.enable && io.liveRequested
  io.blockedByFlush := io.enable && io.flush && io.liveRequested
  io.blockedByLiveDisabled := active && !io.liveRequested
}
