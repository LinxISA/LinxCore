package linxcore.ooo

import chisel3._
import chisel3.util.Decoupled
import linxcore.params.CoreParams
import linxcore.top.interface._

class DispatchIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new D3RenameGroup(p)))
  val robPrepared = Input(new OOORobPrepared(p))
  val brobPrepared = Input(new BROBPrepared(p))
  val iex = new OOODispatchChannels(p)
  val recovery = Flipped(new RecoveryTargetIO(p))
  val pending = Output(Bool())
}

/** Canonical D3/S1 composition boundary. */
class Dispatch(val p: CoreParams) extends Module {
  val io = IO(new DispatchIO(p))
  val allocator = Module(new OooD3ReservationAllocator(p))
  val classify = Module(new OooDispatch(p))

  allocator.io.in <> io.in
  allocator.io.robPrepared := io.robPrepared
  allocator.io.brobPrepared := io.brobPrepared
  allocator.io.advance := classify.io.advance
  allocator.io.recovery.prepare.valid := io.recovery.prepare.valid
  allocator.io.recovery.prepare.bits := io.recovery.prepare.bits
  io.recovery.prepare.ready := allocator.io.recovery.prepare.ready
  io.recovery.prepared.valid := allocator.io.recovery.prepared.valid
  io.recovery.prepared.bits := allocator.io.recovery.prepared.bits
  allocator.io.recovery.prepared.ready := io.recovery.prepared.ready
  allocator.io.recovery.apply.valid := io.recovery.apply.valid
  allocator.io.recovery.apply.bits := io.recovery.apply.bits
  allocator.io.recovery.abort.valid := io.recovery.abort.valid
  allocator.io.recovery.abort.bits := io.recovery.abort.bits

  classify.io.valid := allocator.io.pendingValid
  classify.io.group := allocator.io.pending
  classify.io.cursor := allocator.io.cursor
  classify.io.transactionBase := allocator.io.transactionBase
  classify.io.suppress := io.recovery.apply.valid
  io.iex <> classify.io.iex
  io.pending := allocator.io.pendingValid
}
