package linxcore.bctrl

import chisel3._

class BlockScalarDoneSequencerIO(val bidWidth: Int = BID.DefaultWidth, val stidWidth: Int = 8) extends Bundle {
  val flushValid = Input(Bool())
  val inValid = Input(Bool())
  val inBid = Input(UInt(bidWidth.W))
  val inStid = Input(UInt(stidWidth.W))

  val scalarDoneValid = Output(Bool())
  val scalarDoneBid = Output(UInt(bidWidth.W))
  val scalarDoneStid = Output(UInt(stidWidth.W))
  val retireValid = Output(Bool())
  val retireBid = Output(UInt(bidWidth.W))
  val retireStid = Output(UInt(stidWidth.W))
  val retirePending = Output(Bool())
}

class BlockScalarDoneSequencer(val bidWidth: Int = BID.DefaultWidth, val stidWidth: Int = 8) extends Module {
  val io = IO(new BlockScalarDoneSequencerIO(bidWidth, stidWidth))

  val pending = RegInit(false.B)
  val pendingBid = RegInit(0.U(bidWidth.W))
  val pendingStid = RegInit(0.U(stidWidth.W))

  io.scalarDoneValid := io.inValid
  io.scalarDoneBid := io.inBid
  io.scalarDoneStid := io.inStid
  io.retireValid := pending
  io.retireBid := pendingBid
  io.retireStid := pendingStid
  io.retirePending := pending

  when(io.flushValid) {
    pending := false.B
    pendingBid := 0.U
    pendingStid := 0.U
  }.elsewhen(io.inValid) {
    pending := true.B
    pendingBid := io.inBid
    pendingStid := io.inStid
  }.elsewhen(pending) {
    pending := false.B
    pendingBid := 0.U
    pendingStid := 0.U
  }
}
