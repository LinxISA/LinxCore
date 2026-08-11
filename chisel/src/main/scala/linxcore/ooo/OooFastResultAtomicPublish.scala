package linxcore.ooo

import chisel3._
import chisel3.util.Decoupled
import linxcore.params.CoreParams
import linxcore.top.interface.{FastResultTxn, FastWritebackTxn, RobResolveTxn}

class OooFastResultAtomicPublishIO(val p: CoreParams) extends Bundle {
  val source = Flipped(Decoupled(new FastWritebackTxn(p)))
  val iex = Decoupled(new FastResultTxn(p))
  val rob = Decoupled(new RobResolveTxn(p))
}

/** Stateless atomic fork for one retained fast result.
  *
  * The upstream OooD3FastResultQueue remains the only owner. Payload is always
  * projected from that retained transaction; each branch is offered only when
  * its peer can accept, so P-write/wakeup and ROB completion share one fire.
  */
class OooFastResultAtomicPublish(val p: CoreParams) extends Module {
  val io = IO(new OooFastResultAtomicPublishIO(p))

  io.iex.valid := io.source.valid && io.rob.ready
  io.iex.bits.writeback := io.source.bits
  io.rob.valid := io.source.valid && io.iex.ready
  io.rob.bits := 0.U.asTypeOf(io.rob.bits)
  io.rob.bits.rob := io.source.bits.rob
  io.rob.bits.destinationValid := true.B
  io.rob.bits.destinationIndex := io.source.bits.destinationIndex
  io.rob.bits.value := io.source.bits.value
  io.source.ready := io.iex.ready && io.rob.ready

  when(io.source.valid) {
    assert(io.source.fire === io.iex.fire &&
      io.source.fire === io.rob.fire,
      "fast result branches must publish on one common fire")
  }
}
