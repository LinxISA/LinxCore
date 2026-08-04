package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}
import linxcore.params.CoreParams
import linxcore.top.interface.{RecoveryTargetIO, RecoveryPlanContract,
  RobResolveTxn, RecoveryEvent}

/** Physical terminal topology for the formal scalar/control execution profile.
  *
  * Sources are assigned to terminal lanes by stable modulo ownership.  Each
  * lane first arbitrates fairly within ALU, BRU, and load source families and
  * then reuses `OooIexTerminalPublish` for the atomic cross-sink rendezvous.
  * No source can appear at two terminal lanes, so one retained W-stage owner
  * can be released at most once.
  */
class OooIexTerminalFabricIO(
    val core: CoreParams,
    val aluSourceCount: Int,
    val bruSourceCount: Int,
    val loadSourceCount: Int) extends Bundle {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  private val publicationPorts = p.iexTerminalWidth * p.maxDestinationOperands

  val alu = Flipped(Vec(aluSourceCount,
    Decoupled(new OooIexAluTerminalTransaction(p))))
  val bru = Flipped(Vec(bruSourceCount,
    Decoupled(new OooIexBruTerminalTransaction(p))))
  val load = Flipped(Vec(loadSourceCount,
    Decoupled(new OooIexLoadResult(p))))

  val pWrite = Vec(publicationPorts,
    Decoupled(new OooIexPFileWrite(p)))
  val tWrite = Vec(publicationPorts,
    Decoupled(new OooIexLocalFileWrite(p)))
  val uWrite = Vec(publicationPorts,
    Decoupled(new OooIexLocalFileWrite(p)))
  val wakeup = Vec(publicationPorts,
    Decoupled(new OooIexWakeup(p)))
  val bctrl = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalBctrl(p)))
  val trace = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalTrace(p)))
  val robResolve = Vec(p.iexTerminalWidth,
    Decoupled(new RobResolveTxn(core)))
  val recoveryEvent = Vec(p.iexTerminalWidth,
    Decoupled(new RecoveryEvent(core)))
  val recovery = Flipped(new RecoveryTargetIO(core))

  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val architecturalAccepted = Output(Vec(p.iexTerminalWidth,
    Valid(new OooIexTerminalRequest(p))))
  val rejected = Output(Vec(p.iexTerminalWidth,
    Vec(3, Valid(new OooIexTerminalReject(p)))))
}

class OooIexTerminalFabric(
    val core: CoreParams,
    val aluSourceCount: Int,
    val bruSourceCount: Int,
    val loadSourceCount: Int) extends Module {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  private val width = p.iexTerminalWidth
  private val publicationPorts = width * p.maxDestinationOperands

  require(aluSourceCount > 0 && bruSourceCount > 0 && loadSourceCount > 0,
    "terminal topology needs at least one ALU, BRU, and load source owner")
  require(p.iexPWritePorts >= publicationPorts &&
    p.iexTWritePorts >= publicationPorts &&
    p.iexUWritePorts >= publicationPorts,
    "terminal width must fit every physical P/T/U write-port domain")
  require(p.iexWakeupPorts >= publicationPorts,
    "terminal width must fit committed wakeup bandwidth")

  val io = IO(new OooIexTerminalFabricIO(
    core, aluSourceCount, bruSourceCount, loadSourceCount))

  private def ownedSources(sourceCount: Int, lane: Int): Seq[Int] =
    (0 until sourceCount).filter(_ % width == lane)

  val publishers = Seq.fill(width)(Module(new OooIexTerminalPublish(core)))
  val aluArbiters = Seq.tabulate(width) { lane =>
    val count = ownedSources(aluSourceCount, lane).length
    Option.when(count > 0)(Module(new RRArbiter(
      new OooIexAluTerminalTransaction(p), count)))
  }
  val bruArbiters = Seq.tabulate(width) { lane =>
    val count = ownedSources(bruSourceCount, lane).length
    Option.when(count > 0)(Module(new RRArbiter(
      new OooIexBruTerminalTransaction(p), count)))
  }
  val loadArbiters = Seq.tabulate(width) { lane =>
    val count = ownedSources(loadSourceCount, lane).length
    Option.when(count > 0)(Module(new RRArbiter(
      new OooIexLoadResult(p), count)))
  }

  for (lane <- 0 until width) {
    val aluSources = ownedSources(aluSourceCount, lane)
    val bruSources = ownedSources(bruSourceCount, lane)
    val loadSources = ownedSources(loadSourceCount, lane)

    aluSources.zipWithIndex.foreach { case (source, local) =>
      aluArbiters(lane).get.io.in(local).valid := io.alu(source).valid
      aluArbiters(lane).get.io.in(local).bits := io.alu(source).bits
      io.alu(source).ready := aluArbiters(lane).get.io.in(local).ready
    }
    bruSources.zipWithIndex.foreach { case (source, local) =>
      bruArbiters(lane).get.io.in(local).valid := io.bru(source).valid
      bruArbiters(lane).get.io.in(local).bits := io.bru(source).bits
      io.bru(source).ready := bruArbiters(lane).get.io.in(local).ready
    }
    loadSources.zipWithIndex.foreach { case (source, local) =>
      loadArbiters(lane).get.io.in(local).valid := io.load(source).valid
      loadArbiters(lane).get.io.in(local).bits := io.load(source).bits
      io.load(source).ready := loadArbiters(lane).get.io.in(local).ready
    }

    aluArbiters(lane) match {
      case Some(arbiter) => publishers(lane).io.alu <> arbiter.io.out
      case None =>
        publishers(lane).io.alu.valid := false.B
        publishers(lane).io.alu.bits :=
          0.U.asTypeOf(publishers(lane).io.alu.bits)
    }
    bruArbiters(lane) match {
      case Some(arbiter) => publishers(lane).io.bru <> arbiter.io.out
      case None =>
        publishers(lane).io.bru.valid := false.B
        publishers(lane).io.bru.bits :=
          0.U.asTypeOf(publishers(lane).io.bru.bits)
    }
    loadArbiters(lane) match {
      case Some(arbiter) => publishers(lane).io.load <> arbiter.io.out
      case None =>
        publishers(lane).io.load.valid := false.B
        publishers(lane).io.load.bits :=
          0.U.asTypeOf(publishers(lane).io.load.bits)
    }

    for (destination <- 0 until p.maxDestinationOperands) {
      val port = lane * p.maxDestinationOperands + destination

      io.pWrite(port).valid := publishers(lane).io.pWrite(destination).valid
      io.pWrite(port).bits := publishers(lane).io.pWrite(destination).bits
      publishers(lane).io.pWrite(destination).ready := io.pWrite(port).ready

      io.tWrite(port).valid := publishers(lane).io.tWrite(destination).valid
      io.tWrite(port).bits := publishers(lane).io.tWrite(destination).bits
      publishers(lane).io.tWrite(destination).ready := io.tWrite(port).ready

      io.uWrite(port).valid := publishers(lane).io.uWrite(destination).valid
      io.uWrite(port).bits := publishers(lane).io.uWrite(destination).bits
      publishers(lane).io.uWrite(destination).ready := io.uWrite(port).ready

      io.wakeup(port).valid := publishers(lane).io.wakeup(destination).valid
      io.wakeup(port).bits := publishers(lane).io.wakeup(destination).bits
      publishers(lane).io.wakeup(destination).ready := io.wakeup(port).ready
    }

    io.bctrl(lane).valid := publishers(lane).io.bctrl.valid
    io.bctrl(lane).bits := publishers(lane).io.bctrl.bits
    publishers(lane).io.bctrl.ready := io.bctrl(lane).ready
    io.trace(lane).valid := publishers(lane).io.trace.valid
    io.trace(lane).bits := publishers(lane).io.trace.bits
    publishers(lane).io.trace.ready := io.trace(lane).ready
    io.robResolve(lane).valid := publishers(lane).io.robResolve.valid
    io.robResolve(lane).bits := publishers(lane).io.robResolve.bits
    publishers(lane).io.robResolve.ready := io.robResolve(lane).ready
    io.recoveryEvent(lane).valid := publishers(lane).io.recoveryEvent.valid
    io.recoveryEvent(lane).bits := publishers(lane).io.recoveryEvent.bits
    publishers(lane).io.recoveryEvent.ready := io.recoveryEvent(lane).ready
    io.architecturalAccepted(lane) :=
      publishers(lane).io.architecturalAccepted
    io.rejected(lane) := publishers(lane).io.rejected
  }

  val everyPrepareReady = publishers.map(_.io.recovery.prepare.ready)
    .reduce(_ && _)
  io.recovery.prepare.ready := everyPrepareReady
  publishers.foreach { publisher =>
    publisher.io.recovery.prepare.valid := io.recovery.prepare.valid &&
      everyPrepareReady
    publisher.io.recovery.prepare.bits := io.recovery.prepare.bits
    publisher.io.recovery.apply := io.recovery.apply
    publisher.io.recovery.abort := io.recovery.abort
  }

  val everyPreparedValid = publishers.map(_.io.recovery.prepared.valid)
    .reduce(_ && _)
  io.recovery.prepared.valid := everyPreparedValid
  io.recovery.prepared.bits := publishers.head.io.recovery.prepared.bits
  publishers.zipWithIndex.foreach { case (publisher, lane) =>
    val peersValid = publishers.zipWithIndex.filter(_._2 != lane)
      .map(_._1.io.recovery.prepared.valid).foldLeft(true.B)(_ && _)
    publisher.io.recovery.prepared.ready := io.recovery.prepared.ready &&
      peersValid
  }

  when(io.recovery.prepared.fire) {
    publishers.tail.foreach { publisher =>
      assert(RecoveryPlanContract.sameTransactionIgnoringPhase(
        publisher.io.recovery.prepared.bits,
        publishers.head.io.recovery.prepared.bits),
        "terminal publishers must echo one exact canonical recovery plan")
    }
  }

  io.terminalFireMask := VecInit(publishers.map(_.io.terminalFire)).asUInt
}
