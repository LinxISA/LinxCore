package linxcore.bctrl

import chisel3._

class BlockScalarDoneSequencerIO(val bidWidth: Int = BID.DefaultWidth) extends Bundle {
  val flushValid = Input(Bool())
  val inValid = Input(Bool())
  val inBid = Input(UInt(bidWidth.W))

  val scalarDoneValid = Output(Bool())
  val scalarDoneBid = Output(UInt(bidWidth.W))
  val retireValid = Output(Bool())
  val retireBid = Output(UInt(bidWidth.W))
  val retirePending = Output(Bool())
}

class BlockScalarDoneSequencer(val bidWidth: Int = BID.DefaultWidth) extends Module {
  val io = IO(new BlockScalarDoneSequencerIO(bidWidth))

  val pending = RegInit(false.B)
  val pendingBid = RegInit(0.U(bidWidth.W))

  io.scalarDoneValid := io.inValid
  io.scalarDoneBid := io.inBid
  io.retireValid := pending
  io.retireBid := pendingBid
  io.retirePending := pending

  when(io.flushValid) {
    pending := false.B
    pendingBid := 0.U
  }.elsewhen(io.inValid) {
    pending := true.B
    pendingBid := io.inBid
  }.elsewhen(pending) {
    pending := false.B
    pendingBid := 0.U
  }
}
