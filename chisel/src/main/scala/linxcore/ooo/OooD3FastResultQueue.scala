package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.{FastWritebackTxn, RecoveryPhase, RecoveryPlan,
  RecoveryPlanContract, RecoveryTargetIO}

private[ooo] class OooD3FastResultBatch(val p: CoreParams) extends Bundle {
  val count = UInt(log2Ceil(p.ooo.d3PrefixWidth + 1).W)
  val entries = Vec(p.ooo.d3PrefixWidth, new FastWritebackTxn(p))
}

private[ooo] class OooD3FastResultQueueIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Valid(new OooD3FastResultBatch(p)))
  val prepareReady = Output(Bool())
  val publishFire = Input(Bool())
  val out = Decoupled(new FastWritebackTxn(p))
  val recovery = Flipped(new RecoveryTargetIO(p))
}

/** Retains canonical D3 fast-result producers until their committed
  * writeback/wakeup fork fires. Recovery removes only exact killed ROB
  * members; preparation and publication remain split by the common D3 fire.
  */
private[ooo] class OooD3FastResultQueue(val p: CoreParams) extends Module {
  val io = IO(new OooD3FastResultQueueIO(p))
  private val depth = p.ooo.d3PrefixWidth * 2
  private val countWidth = log2Ceil(depth + 1)
  private val indexWidth = log2Ceil(depth)

  val entries = Reg(Vec(depth, new FastWritebackTxn(p)))
  val count = RegInit(0.U(countWidth.W))
  val recoveryPending = RegInit(false.B)
  val retainedRecovery = Reg(new RecoveryPlan(p))

  val offeredCountExact = io.prepare.bits.count <= p.ooo.d3PrefixWidth.U
  val free = depth.U - count
  io.prepareReady := offeredCountExact &&
    io.prepare.bits.count <= free && !recoveryPending
  when(io.publishFire) {
    assert(io.prepare.valid && io.prepareReady,
      "fast-result publication requires capacity for the exact D3 batch")
  }

  io.out.valid := count.orR && !recoveryPending
  io.out.bits := entries(0)
  val dequeue = io.out.fire
  val baseCount = count - dequeue.asUInt

  when(!recoveryPending) {
    when(dequeue) {
      for (slot <- 0 until depth - 1) {
        entries(slot) := entries(slot + 1)
      }
    }
    when(io.publishFire) {
      for (lane <- 0 until p.ooo.d3PrefixWidth) {
        when(lane.U < io.prepare.bits.count) {
          entries((baseCount + lane.U)(indexWidth - 1, 0)) :=
            io.prepare.bits.entries(lane)
        }
      }
    }
    when(dequeue || io.publishFire) {
      count := baseCount + Mux(io.publishFire, io.prepare.bits.count, 0.U)
    }
  }

  io.recovery.prepare.ready := !recoveryPending
  io.recovery.prepared.valid := recoveryPending
  io.recovery.prepared.bits := retainedRecovery
  when(io.recovery.prepare.fire) {
    retainedRecovery := io.recovery.prepare.bits
    recoveryPending := true.B
  }

  val applyHit = recoveryPending && io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val abortHit = recoveryPending && io.recovery.abort.valid &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)
  when(io.recovery.apply.valid) {
    assert(applyHit,
      "fast-result recovery apply must match the retained canonical plan")
  }
  when(io.recovery.abort.valid) {
    assert(abortHit,
      "fast-result recovery abort must match the retained canonical plan")
  }

  val survivors = Wire(Vec(depth, Bool()))
  val survivorPrefix = Wire(Vec(depth + 1, UInt(countWidth.W)))
  survivorPrefix(0) := 0.U
  for (slot <- 0 until depth) {
    survivors(slot) := slot.U < count &&
      !RecoveryPlanContract.suffixMember(retainedRecovery, entries(slot).rob)
    survivorPrefix(slot + 1) := survivorPrefix(slot) + survivors(slot).asUInt
  }
  when(applyHit) {
    val compacted = Wire(Vec(depth, new FastWritebackTxn(p)))
    compacted := 0.U.asTypeOf(compacted)
    for (slot <- 0 until depth) {
      when(survivors(slot)) {
        compacted(survivorPrefix(slot)(indexWidth - 1, 0)) := entries(slot)
      }
    }
    entries := compacted
    count := PopCount(survivors)
    recoveryPending := false.B
  }.elsewhen(abortHit) {
    recoveryPending := false.B
  }
}
