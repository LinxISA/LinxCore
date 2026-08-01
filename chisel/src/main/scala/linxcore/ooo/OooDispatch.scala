package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, MuxLookup, PopCount, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface._

class OOODispatchChannels(val p: CoreParams) extends Bundle {
  val aluDispatch = Vec(p.iex.aluPipes, Decoupled(new DispatchTxn(p)))
  val bruDispatch = Vec(p.iex.bruPipes, Decoupled(new DispatchTxn(p)))
  val aguDispatch = Vec(p.iex.aguPipes, Decoupled(new DispatchTxn(p)))
  val stdDispatch = Vec(p.iex.stdPipes, Decoupled(new DispatchTxn(p)))
  val systemDispatch = Vec(p.iex.systemMulticycleQueues,
    Decoupled(new DispatchTxn(p)))
  val cmdDispatch = Vec(p.iex.cmdIssueQueues, Decoupled(new DispatchTxn(p)))
}

class OooDispatchIO(val p: CoreParams) extends Bundle {
  val valid = Input(Bool())
  val group = Input(new D3RenameGroup(p))
  val cursor = Input(UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W))
  val transactionBase = Input(UInt(p.transactionIdWidth.W))
  val suppress = Input(Bool())
  val iex = new OOODispatchChannels(p)
  val advance = Output(UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W))
}

/** Stateless canonical class/credit mapper for one retained D3 suffix. */
class OooDispatch(val p: CoreParams) extends Module {
  val io = IO(new OooDispatchIO(p))
  private val width = p.ooo.d3PrefixWidth
  private def fitIndex(value: UInt, entries: Int): UInt =
    if (entries == 1) 0.U else value(log2Ceil(entries) - 1, 0)
  private def laneAt(value: UInt): D3RenameLane =
    io.group.entries(fitIndex(Mux(value < width.U, value, 0.U), width))
  private def readyAt(values: Vec[DecoupledIO[DispatchTxn]], value: UInt): Bool =
    if (values.length == 1) values.head.ready
    else values(fitIndex(value, values.length)).ready

  io.iex.aluDispatch.foreach { out =>
    out.valid := false.B; out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.bruDispatch.foreach { out =>
    out.valid := false.B; out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.aguDispatch.foreach { out =>
    out.valid := false.B; out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.stdDispatch.foreach { out =>
    out.valid := false.B; out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.systemDispatch.foreach { out =>
    out.valid := false.B; out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.cmdDispatch.foreach { out =>
    out.valid := false.B; out.bits := 0.U.asTypeOf(out.bits)
  }
  val laneActive = Wire(Vec(width, Bool()))
  val laneNeedsOutput = Wire(Vec(width, Bool()))
  val laneReady = Wire(Vec(width, Bool()))
  val prefixReady = Wire(Vec(width + 1, Bool()))
  val nextActiveLane = Module(new OooHierarchicalFreeSlotSelect(width, 1))
  nextActiveLane.io.available := laneActive.asUInt
  prefixReady(0) := io.valid && !io.suppress &&
    nextActiveLane.io.selectedValid && nextActiveLane.io.selectedIndex === 0.U

  for (offset <- 0 until width) {
    val index = io.cursor + offset.U
    val inRange = index < io.group.count
    val lane = laneAt(index)
    laneActive(offset) := inRange
    val early = lane.earlyRobComplete || lane.uop.decoded.uopClass === UopClass.Boundary
    laneNeedsOutput(offset) := inRange && !early

    val olderAlu = if (offset == 0) 0.U else PopCount((0 until offset).map { older =>
      laneActive(older) && laneNeedsOutput(older) &&
        laneAt(io.cursor + older.U).uop.decoded.uopClass === UopClass.Alu
    })
    val olderBru = if (offset == 0) 0.U else PopCount((0 until offset).map { older =>
      laneActive(older) && laneNeedsOutput(older) &&
        laneAt(io.cursor + older.U).uop.decoded.uopClass === UopClass.Bru
    })
    val olderAgu = if (offset == 0) 0.U else PopCount((0 until offset).map { older =>
      val cls = laneAt(io.cursor + older.U).uop.decoded.uopClass
      laneActive(older) && laneNeedsOutput(older) &&
        (cls === UopClass.Agu || cls === UopClass.Std)
    })
    val olderStd = if (offset == 0) 0.U else PopCount((0 until offset).map { older =>
      laneActive(older) && laneNeedsOutput(older) &&
        laneAt(io.cursor + older.U).uop.decoded.uopClass === UopClass.Std
    })
    val olderSystem = if (offset == 0) 0.U else PopCount((0 until offset).map { older =>
      laneActive(older) && laneNeedsOutput(older) &&
        laneAt(io.cursor + older.U).uop.decoded.uopClass === UopClass.System
    })
    val olderCmd = if (offset == 0) 0.U else PopCount((0 until offset).map { older =>
      laneActive(older) && laneNeedsOutput(older) &&
        laneAt(io.cursor + older.U).uop.decoded.uopClass === UopClass.Cmd
    })

    val txn = Wire(new DispatchTxn(p))
    txn.transactionId := io.transactionBase + index
    txn.uop := lane.uop
    val allowed = prefixReady(offset) && inRange && !early
    val cls = lane.uop.decoded.uopClass
    val aluReady = olderAlu < p.iex.aluPipes.U &&
      readyAt(io.iex.aluDispatch, olderAlu)
    val bruReady = olderBru < p.iex.bruPipes.U &&
      readyAt(io.iex.bruDispatch, olderBru)
    val aguReady = olderAgu < p.iex.aguPipes.U &&
      readyAt(io.iex.aguDispatch, olderAgu)
    val stdReady = olderStd < p.iex.stdPipes.U &&
      readyAt(io.iex.stdDispatch, olderStd)
    val sysReady = olderSystem < p.iex.systemMulticycleQueues.U &&
      readyAt(io.iex.systemDispatch, olderSystem)
    val cmdReady = olderCmd < p.iex.cmdIssueQueues.U &&
      readyAt(io.iex.cmdDispatch, olderCmd)
    laneReady(offset) := !inRange || early || MuxLookup(cls.asUInt, false.B)(Seq(
      UopClass.Alu.asUInt -> aluReady,
      UopClass.Bru.asUInt -> bruReady,
      UopClass.Agu.asUInt -> aguReady,
      UopClass.Std.asUInt -> (aguReady && stdReady),
      UopClass.System.asUInt -> sysReady,
      UopClass.Cmd.asUInt -> cmdReady,
      UopClass.Boundary.asUInt -> true.B))

    for (pipe <- 0 until p.iex.aluPipes) when(allowed && cls === UopClass.Alu && olderAlu === pipe.U && aluReady) {
      io.iex.aluDispatch(pipe).valid := true.B; io.iex.aluDispatch(pipe).bits := txn
    }
    for (pipe <- 0 until p.iex.bruPipes) when(allowed && cls === UopClass.Bru && olderBru === pipe.U && bruReady) {
      io.iex.bruDispatch(pipe).valid := true.B; io.iex.bruDispatch(pipe).bits := txn
    }
    for (pipe <- 0 until p.iex.aguPipes) when(allowed &&
      (cls === UopClass.Agu || cls === UopClass.Std) && olderAgu === pipe.U &&
      (cls =/= UopClass.Std || stdReady) && aguReady) {
      io.iex.aguDispatch(pipe).valid := true.B; io.iex.aguDispatch(pipe).bits := txn
    }
    for (pipe <- 0 until p.iex.stdPipes) when(allowed && cls === UopClass.Std &&
      olderStd === pipe.U && aguReady && stdReady) {
      io.iex.stdDispatch(pipe).valid := true.B; io.iex.stdDispatch(pipe).bits := txn
    }
    for (queue <- 0 until p.iex.systemMulticycleQueues) when(allowed &&
      cls === UopClass.System && olderSystem === queue.U && sysReady) {
      io.iex.systemDispatch(queue).valid := true.B; io.iex.systemDispatch(queue).bits := txn
    }
    for (queue <- 0 until p.iex.cmdIssueQueues) when(allowed &&
      cls === UopClass.Cmd && olderCmd === queue.U && cmdReady) {
      io.iex.cmdDispatch(queue).valid := true.B; io.iex.cmdDispatch(queue).bits := txn
    }
    prefixReady(offset + 1) := prefixReady(offset) && laneReady(offset)
  }

  io.advance := PopCount((0 until width).map { offset =>
    laneActive(offset) && prefixReady(offset) && laneReady(offset)
  })
}
