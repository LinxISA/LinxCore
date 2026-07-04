package linxcore.lsu

import chisel3._

class LoadReplayReturnIexPipeOccupancyLiveControlIO(val returnPipeCount: Int = 1)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val sourceValid = Input(Bool())
  val livePipeOccupiedMaskIn = Input(UInt(returnPipeCount.W))

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val occupancyEvidenceValid = Output(Bool())
  val allPipeMask = Output(UInt(returnPipeCount.W))
  val maskedLivePipeOccupiedMask = Output(UInt(returnPipeCount.W))
  val liveRequested = Output(Bool())
  val livePipeOccupiedMask = Output(UInt(returnPipeCount.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoSource = Output(Bool())
}

class LoadReplayReturnIexPipeOccupancyLiveControl(val returnPipeCount: Int = 1)
    extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val allPipeMask = ((BigInt(1) << returnPipeCount) - 1).U(returnPipeCount.W)

  val io = IO(new LoadReplayReturnIexPipeOccupancyLiveControlIO(returnPipeCount))

  val active = io.enable && !io.flush
  val requestActive = active && io.requestEnable
  val maskedLivePipeOccupiedMask = io.livePipeOccupiedMaskIn & allPipeMask
  val occupancyEvidenceValid = active && io.sourceValid
  val liveRequested = requestActive && io.sourceValid

  io.active := active
  io.requestActive := requestActive
  io.occupancyEvidenceValid := occupancyEvidenceValid
  io.allPipeMask := allPipeMask
  io.maskedLivePipeOccupiedMask := maskedLivePipeOccupiedMask
  io.liveRequested := liveRequested
  io.livePipeOccupiedMask := Mux(liveRequested, maskedLivePipeOccupiedMask, 0.U(returnPipeCount.W))
  io.blockedByDisabled := !io.enable && (io.requestEnable || io.sourceValid)
  io.blockedByFlush := io.enable && io.flush && (io.requestEnable || io.sourceValid)
  io.blockedByRequestDisabled := active && !io.requestEnable && io.sourceValid
  io.blockedByNoSource := requestActive && !io.sourceValid
}
