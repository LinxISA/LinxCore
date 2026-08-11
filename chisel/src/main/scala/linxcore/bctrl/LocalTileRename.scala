package linxcore.bctrl

import chisel3._
import chisel3.util._

object LocalTileAllocateStatus {
  val width = 2
  val Applied = 0.U(width.W)
  val Noop = 1.U(width.W)
  val InvalidSize = 2.U(width.W)
  val NoCredit = 3.U(width.W)
}

class LocalTileAllocateRequest extends Bundle {
  val peMask = UInt(4.W)
  val hand = UInt(2.W)
  val sizeCode = UInt(3.W)
}

class LocalTileAllocation extends Bundle {
  val peMask = UInt(4.W)
  val logicalTags = Vec(4, UInt(6.W))
  val producerTags = Vec(4, UInt(16.W))
  val perPeCapacity = UInt(14.W)
  val allocatedBytes = UInt(16.W)
}

class LocalTileLookup extends Bundle {
  val valid = Bool()
  val ready = Bool()
  val producerTag = UInt(16.W)
  val capacity = UInt(14.W)
}

class LocalTileRenameIO extends Bundle {
  val allocate = Flipped(Decoupled(new LocalTileAllocateRequest))
  val status = Output(UInt(LocalTileAllocateStatus.width.W))
  val allocation = Valid(new LocalTileAllocation)

  val lookupLogical = Input(UInt(6.W))
  val lookup = Output(Vec(4, new LocalTileLookup))
  val markReady = Flipped(Vec(4, Valid(UInt(16.W))))
  val release = Flipped(Vec(4, Valid(UInt(16.W))))
}

/**
  * Per-PE Local Tile rename owner.
  *
  * The two-bit destination selects one of the architectural T/U/M/N hands;
  * each PE advances its own sixteen-entry hand ring. A producer identity also
  * carries PE and generation, so consumers cannot accidentally observe a
  * different PE's producer or a recycled logical entry.
  */
class LocalTileRename extends Module {
  val io = IO(new LocalTileRenameIO)

  private val tails = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(4)(0.U(4.W))))))
  private val live = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(64)(false.B)))))
  private val ready = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(64)(false.B)))))
  private val generations = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(64)(0.U(8.W))))))
  private val capacities = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(64)(0.U(14.W))))))

  private def selected(mask: UInt, pe: Int): Bool = mask(3 - pe)

  private def producerTag(pe: Int, logical: UInt, generation: UInt): UInt =
    Cat(generation, pe.U(2.W), logical)

  val request = io.allocate.bits
  val logicalTags = Wire(Vec(4, UInt(6.W)))
  val nextProducerTags = Wire(Vec(4, UInt(16.W)))
  val currentProducerTags = Wire(Vec(4, UInt(16.W)))
  val releasedCurrent = Wire(Vec(4, Bool()))
  val selectedCredit = Wire(Vec(4, Bool()))

  for (pe <- 0 until 4) {
    logicalTags(pe) := Cat(request.hand, tails(pe)(request.hand))
    currentProducerTags(pe) :=
      producerTag(pe, logicalTags(pe), generations(pe)(logicalTags(pe)))
    nextProducerTags(pe) :=
      producerTag(pe, logicalTags(pe), generations(pe)(logicalTags(pe)) + 1.U)
    releasedCurrent(pe) :=
      io.release(pe).valid && io.release(pe).bits === currentProducerTags(pe)
    selectedCredit(pe) :=
      !selected(request.peMask, pe) || !live(pe)(logicalTags(pe)) || releasedCurrent(pe)
  }

  val allCredit = selectedCredit.asUInt.andR
  val sizeValid = request.sizeCode >= 1.U && request.sizeCode <= 7.U
  val perPeCapacity = WireDefault(0.U(14.W))
  when(sizeValid) {
    perPeCapacity := (128.U(14.W) << (request.sizeCode - 1.U))(13, 0)
  }

  io.status := Mux(
    request.peMask === 0.U,
    LocalTileAllocateStatus.Noop,
    Mux(
      !sizeValid,
      LocalTileAllocateStatus.InvalidSize,
      Mux(allCredit, LocalTileAllocateStatus.Applied, LocalTileAllocateStatus.NoCredit)
    )
  )
  io.allocate.ready := io.status =/= LocalTileAllocateStatus.NoCredit

  io.allocation.valid := io.allocate.fire && io.status === LocalTileAllocateStatus.Applied
  io.allocation.bits.peMask := request.peMask
  io.allocation.bits.logicalTags := logicalTags
  io.allocation.bits.producerTags := nextProducerTags
  io.allocation.bits.perPeCapacity := perPeCapacity
  io.allocation.bits.allocatedBytes :=
    (perPeCapacity * PopCount(request.peMask))(15, 0)

  for (pe <- 0 until 4) {
    val lookupProducer =
      producerTag(pe, io.lookupLogical, generations(pe)(io.lookupLogical))
    io.lookup(pe).valid := live(pe)(io.lookupLogical)
    io.lookup(pe).ready := ready(pe)(io.lookupLogical)
    io.lookup(pe).producerTag := lookupProducer
    io.lookup(pe).capacity := capacities(pe)(io.lookupLogical)

    val releaseLogical = io.release(pe).bits(5, 0)
    val releaseMatches =
      io.release(pe).valid &&
        live(pe)(releaseLogical) &&
        io.release(pe).bits ===
          producerTag(pe, releaseLogical, generations(pe)(releaseLogical))
    when(releaseMatches) {
      live(pe)(releaseLogical) := false.B
      ready(pe)(releaseLogical) := false.B
    }

    val readyLogical = io.markReady(pe).bits(5, 0)
    val readyMatches =
      io.markReady(pe).valid &&
        live(pe)(readyLogical) &&
        io.markReady(pe).bits ===
          producerTag(pe, readyLogical, generations(pe)(readyLogical))
    when(readyMatches) {
      ready(pe)(readyLogical) := true.B
    }

    when(io.allocation.valid && selected(request.peMask, pe)) {
      val logical = logicalTags(pe)
      live(pe)(logical) := true.B
      ready(pe)(logical) := false.B
      generations(pe)(logical) := generations(pe)(logical) + 1.U
      capacities(pe)(logical) := perPeCapacity
      tails(pe)(request.hand) := tails(pe)(request.hand) + 1.U
    }
  }

  assert(!io.allocation.valid || allCredit)
  assert(!io.allocation.valid || sizeValid)
}
