package linxcore.lsu

import chisel3._

class LoadReplayReturnReducedScalarShapeControlIO(val countWidth: Int = 8)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())

  val active = Output(Bool())
  val reducedScalarShapeValid = Output(Bool())
  val scalarLoadPair = Output(Bool())
  val vectorOrMemMultiLane = Output(Bool())
  val retLaneBefore = Output(UInt(countWidth.W))
  val returnedLaneCount = Output(UInt(countWidth.W))
  val realReqCnt = Output(UInt(countWidth.W))
  val isMemIex = Output(Bool())
  val isTload = Output(Bool())
  val subInstCntBefore = Output(UInt(countWidth.W))
  val isVectorMachine = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
}

class LoadReplayReturnReducedScalarShapeControl(val countWidth: Int = 8)
    extends Module {
  require(countWidth > 0, "countWidth must be positive")

  val io = IO(new LoadReplayReturnReducedScalarShapeControlIO(countWidth))

  val active = io.enable && !io.flush

  io.active := active
  io.reducedScalarShapeValid := active
  io.scalarLoadPair := false.B
  io.vectorOrMemMultiLane := false.B
  io.retLaneBefore := 0.U
  io.returnedLaneCount := 1.U
  io.realReqCnt := 1.U
  io.isMemIex := false.B
  io.isTload := false.B
  io.subInstCntBefore := 0.U
  io.isVectorMachine := false.B
  io.blockedByDisabled := !io.enable
  io.blockedByFlush := io.enable && io.flush
}
