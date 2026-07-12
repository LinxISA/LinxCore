package linxcore.execute

import chisel3._

import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

class ScalarIssueExternalControlFenceIO(val p: InterfaceParams) extends Bundle {
  val captureValid = Input(Bool())
  val captureBid = Input(new ROBID(p.robEntries))
  val captureRid = Input(new ROBID(p.robEntries))
  val captureStid = Input(UInt(p.threadIdWidth.W))
  val clear = Input(Bool())

  val valid = Output(Bool())
  val bid = Output(new ROBID(p.robEntries))
  val rid = Output(new ROBID(p.robEntries))
  val stid = Output(UInt(p.threadIdWidth.W))
}

/** Retains a resolved redirect identity until central recovery accepts cleanup. */
class ScalarIssueExternalControlFence(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ScalarIssueExternalControlFenceIO(p))

  val valid = RegInit(false.B)
  val bid = RegInit(0.U.asTypeOf(new ROBID(p.robEntries)))
  val rid = RegInit(0.U.asTypeOf(new ROBID(p.robEntries)))
  val stid = RegInit(0.U(p.threadIdWidth.W))

  when(io.clear) {
    valid := false.B
  }.elsewhen(io.captureValid) {
    valid := true.B
    bid := io.captureBid
    rid := io.captureRid
    stid := io.captureStid
  }

  io.valid := valid
  io.bid := bid
  io.rid := rid
  io.stid := stid

  assert(!valid || (bid.valid && rid.valid),
    "retained scalar issue control fence requires exact BID/RID identity")
}
