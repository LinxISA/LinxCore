package linxcore.ifu

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.{FetchedInstruction, FetchedPacket, RecoveryPlan}

class FetchBufferIngress(val p: CoreParams, val ingressWidth: Int) extends Bundle {
  val count = UInt(log2Ceil(ingressWidth + 1).W)
  val entries = Vec(ingressWidth, new FetchedInstruction(p))
}

class FetchBufferIO(
    val p: CoreParams,
    val ingressWidth: Int,
    val depth: Int)
    extends Bundle {
  val enq = Flipped(Decoupled(new FetchBufferIngress(p, ingressWidth)))
  val deq = Decoupled(new FetchedPacket(p))
  val prune = Flipped(Valid(new RecoveryPlan(p)))
  val occupancy = Output(UInt(log2Ceil(depth + 1).W))
}

/** Retained width converter between the I-SIDE fetch geometry and CTU.
  *
  * Each accepted instruction receives a monotonic local order. The configured
  * IFU width controls only the oldest continuous prefix presented to CTU; it
  * does not leak into cache-line lookup or instruction assembly geometry.
  */
class FetchBuffer(
    val p: CoreParams,
    val ingressWidth: Int = 4,
    val depth: Int = -1)
    extends Module {
  private val resolvedDepth = if (depth > 0) depth else p.ifu.fetchBufferEntries
  require(ingressWidth > 0)
  require(resolvedDepth >= math.max(ingressWidth, p.widths.fetchWidth))

  val io = IO(new FetchBufferIO(p, ingressWidth, resolvedDepth))

  private val countWidth = log2Ceil(resolvedDepth + 1)
  private val indexWidth = math.max(1, log2Ceil(resolvedDepth))
  private val orderWidth = 64

  val valid = RegInit(VecInit(Seq.fill(resolvedDepth)(false.B)))
  val entries = Reg(Vec(resolvedDepth, new FetchedInstruction(p)))
  val order = Reg(Vec(resolvedDepth, UInt(orderWidth.W)))
  val count = RegInit(0.U(countWidth.W))
  val nextOrder = RegInit(0.U(orderWidth.W))

  val outputCount = Mux(
    count > p.widths.fetchWidth.U,
    p.widths.fetchWidth.U,
    count)
  io.deq.valid := count.orR && !io.prune.valid
  io.deq.bits := 0.U.asTypeOf(io.deq.bits)
  io.deq.bits.count := outputCount

  val selectedValid = Wire(Vec(p.widths.fetchWidth, Bool()))
  val selectedIndex =
    Wire(Vec(p.widths.fetchWidth, UInt(indexWidth.W)))
  var remainingMask: UInt = valid.asUInt
  for (lane <- 0 until p.widths.fetchWidth) {
    var found: Bool = false.B
    var oldestIndex: UInt = 0.U(indexWidth.W)
    var oldestOrder: UInt = 0.U(orderWidth.W)
    for (slot <- 0 until resolvedDepth) {
      val choose = remainingMask(slot) &&
        (!found || order(slot) < oldestOrder)
      oldestIndex = Mux(choose, slot.U, oldestIndex)
      oldestOrder = Mux(choose, order(slot), oldestOrder)
      found = found || remainingMask(slot)
    }
    selectedValid(lane) := found
    selectedIndex(lane) := oldestIndex
    when(found) {
      io.deq.bits.entries(lane) := entries(oldestIndex)
    }
    val selectedBit = Mux(
      found,
      (1.U(resolvedDepth.W) << oldestIndex)(resolvedDepth - 1, 0),
      0.U(resolvedDepth.W))
    remainingMask = remainingMask & ~selectedBit
  }

  val enqCount = io.enq.bits.count
  val freeCount = resolvedDepth.U - count
  io.enq.ready := !io.prune.valid && enqCount.orR && enqCount <= freeCount
  io.occupancy := count

  val insertValid = Wire(Vec(ingressWidth, Bool()))
  val insertIndex = Wire(Vec(ingressWidth, UInt(indexWidth.W)))
  var freeMask: UInt = ~valid.asUInt
  for (lane <- 0 until ingressWidth) {
    val found = freeMask.orR
    val index = PriorityEncoder(freeMask)
    insertValid(lane) := found && lane.U < enqCount
    insertIndex(lane) := index
    val claimedBit = Mux(
      found,
      (1.U(resolvedDepth.W) << index)(resolvedDepth - 1, 0),
      0.U(resolvedDepth.W))
    freeMask = freeMask & ~claimedBit
  }

  val killed = Wire(Vec(resolvedDepth, Bool()))
  for (slot <- 0 until resolvedDepth) {
    killed(slot) := valid(slot) &&
      entries(slot).identity.stid === io.prune.bits.trigger.stid
  }
  val killedCount = PopCount(killed)

  when(io.prune.valid) {
    for (slot <- 0 until resolvedDepth) {
      when(killed(slot)) {
        valid(slot) := false.B
      }
    }
    count := count - killedCount
  }.otherwise {
    when(io.deq.fire) {
      for (lane <- 0 until p.widths.fetchWidth) {
        when(selectedValid(lane) && lane.U < outputCount) {
          valid(selectedIndex(lane)) := false.B
        }
      }
    }
    when(io.enq.fire) {
      for (lane <- 0 until ingressWidth) {
        when(insertValid(lane)) {
          valid(insertIndex(lane)) := true.B
          entries(insertIndex(lane)) := io.enq.bits.entries(lane)
          order(insertIndex(lane)) := nextOrder + lane.U
        }
      }
      nextOrder := nextOrder + enqCount
    }
    count := count -
      Mux(io.deq.fire, outputCount, 0.U) +
      Mux(io.enq.fire, enqCount, 0.U)
  }

  assert(count === PopCount(valid))
  assert(count <= resolvedDepth.U)
  assert(!io.enq.valid || io.enq.bits.count <= ingressWidth.U)
  when(io.enq.fire) {
    assert(nextOrder + enqCount >= nextOrder)
    assert(insertValid.asUInt === ((1.U << enqCount) - 1.U))
  }
}
