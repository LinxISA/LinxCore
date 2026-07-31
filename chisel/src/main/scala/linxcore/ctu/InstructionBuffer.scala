package linxcore.ctu

import chisel3._
import chisel3.util.{Decoupled, PriorityEncoder, Valid, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.{D1Packet, FrontEndOp, RecoveryPlan}

class InstructionBufferIO(val p: CoreParams, val depth: Int) extends Bundle {
  val enq = Flipped(Decoupled(new D1Packet(p)))
  val deq = Decoupled(new D1Packet(p))
  val prune = Flipped(Valid(new RecoveryPlan(p)))
  val occupancy = Output(UInt(log2Ceil(depth + 1).W))
}

/** Retained CTU-to-D1 boundary with no downstream-ready bypass to IFU. */
class InstructionBuffer(val p: CoreParams, val depth: Int) extends Module {
  require(depth >= p.widths.ctuOutputWidth)

  val io = IO(new InstructionBufferIO(p, depth))
  private val countWidth = log2Ceil(depth + 1)
  private val entryIndexWidth = math.max(1, log2Ceil(depth))
  private val orderWidth = 64

  val valid = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val entries = Reg(Vec(depth, new FrontEndOp(p)))
  val order = Reg(Vec(depth, UInt(orderWidth.W)))
  val count = RegInit(0.U(countWidth.W))
  val nextOrder = RegInit(0.U(orderWidth.W))

  val outputCount = Mux(
    count > p.widths.ctuOutputWidth.U,
    p.widths.ctuOutputWidth.U,
    count)
  io.deq.valid := count.orR && !io.prune.valid
  io.deq.bits := 0.U.asTypeOf(io.deq.bits)
  io.deq.bits.count := outputCount

  val selectedValid = Wire(Vec(p.widths.ctuOutputWidth, Bool()))
  val selectedIndex = Wire(Vec(
    p.widths.ctuOutputWidth, UInt(entryIndexWidth.W)))
  var remainingMask: UInt = valid.asUInt
  for (lane <- 0 until p.widths.ctuOutputWidth) {
    var found: Bool = false.B
    var oldestIndex: UInt = 0.U(entryIndexWidth.W)
    var oldestOrder: UInt = 0.U(orderWidth.W)
    for (slot <- 0 until depth) {
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
      (1.U(depth.W) << oldestIndex)(depth - 1, 0),
      0.U(depth.W))
    remainingMask = remainingMask & ~selectedBit
  }

  val enqCount = io.enq.bits.count
  val freeCount = depth.U - count
  io.enq.ready := !io.prune.valid && enqCount <= freeCount
  io.occupancy := count

  val insertValid = Wire(Vec(p.widths.ctuOutputWidth, Bool()))
  val insertIndex = Wire(Vec(
    p.widths.ctuOutputWidth, UInt(entryIndexWidth.W)))
  var freeMask: UInt = ~valid.asUInt
  for (lane <- 0 until p.widths.ctuOutputWidth) {
    val found = freeMask.orR
    val index = PriorityEncoder(freeMask)
    insertValid(lane) := found && lane.U < enqCount
    insertIndex(lane) := index
    val claimedBit = Mux(
      found,
      (1.U(depth.W) << index)(depth - 1, 0),
      0.U(depth.W))
    freeMask = freeMask & ~claimedBit
  }

  val killed = Wire(Vec(depth, Bool()))
  for (slot <- 0 until depth) {
    killed(slot) := valid(slot) &&
      entries(slot).parent.identity.stid === io.prune.bits.trigger.stid
  }
  val killedCount = chisel3.util.PopCount(killed)

  when(io.prune.valid) {
    for (slot <- 0 until depth) {
      when(killed(slot)) {
        valid(slot) := false.B
      }
    }
    count := count - killedCount
  }.otherwise {
    when(io.deq.fire) {
      for (lane <- 0 until p.widths.ctuOutputWidth) {
        when(selectedValid(lane) && lane.U < outputCount) {
          valid(selectedIndex(lane)) := false.B
        }
      }
    }
    when(io.enq.fire) {
      for (lane <- 0 until p.widths.ctuOutputWidth) {
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

  assert(count <= depth.U)
  assert(count === chisel3.util.PopCount(valid))
  assert(!io.enq.valid || io.enq.bits.count <= p.widths.ctuOutputWidth.U)
  assert(!io.enq.fire || insertValid.asUInt ===
    ((1.U << enqCount) - 1.U))
  when(io.enq.fire) {
    assert(nextOrder + enqCount >= nextOrder)
  }
  for (lane <- 0 until p.widths.ctuOutputWidth) {
    assert(!selectedValid(lane) || lane.U < count)
  }
}
