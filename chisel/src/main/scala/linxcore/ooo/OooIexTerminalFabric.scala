package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}

/** Physical terminal topology for the formal scalar/control execution profile.
  *
  * Sources are assigned to terminal lanes by stable modulo ownership.  Each
  * lane first arbitrates fairly within ALU, BRU, and load source families and
  * then reuses `OooIexTerminalPublish` for the atomic cross-sink rendezvous.
  * No source can appear at two terminal lanes, so one retained W-stage owner
  * can be released at most once.
  */
class OooIexTerminalFabricIO(
    val p: OooParams,
    val aluSourceCount: Int,
    val bruSourceCount: Int,
    val loadSourceCount: Int) extends Bundle {
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
  val completion = Vec(p.iexTerminalWidth,
    Decoupled(new OooRobMemberCompletion(p)))

  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val rejected = Output(Vec(p.iexTerminalWidth,
    Vec(3, Valid(new OooIexTerminalReject(p)))))
}

class OooIexTerminalFabric(
    val p: OooParams = OooParams(),
    val aluSourceCount: Int = 6,
    val bruSourceCount: Int = 2,
    val loadSourceCount: Int = 3) extends Module {
  private val width = p.iexTerminalWidth
  private val publicationPorts = width * p.maxDestinationOperands

  require(aluSourceCount >= width && bruSourceCount >= width &&
    loadSourceCount >= width,
    "every terminal lane needs at least one ALU, BRU, and load source owner")
  require(p.iexPWritePorts >= publicationPorts &&
    p.iexTWritePorts >= publicationPorts &&
    p.iexUWritePorts >= publicationPorts,
    "terminal width must fit every physical P/T/U write-port domain")
  require(p.iexWakeupPorts >= publicationPorts,
    "terminal width must fit committed wakeup bandwidth")

  val io = IO(new OooIexTerminalFabricIO(
    p, aluSourceCount, bruSourceCount, loadSourceCount))

  private def ownedSources(sourceCount: Int, lane: Int): Seq[Int] =
    (0 until sourceCount).filter(_ % width == lane)

  val publishers = Seq.fill(width)(Module(new OooIexTerminalPublish(p)))
  val aluArbiters = Seq.tabulate(width) { lane =>
    Module(new RRArbiter(new OooIexAluTerminalTransaction(p),
      ownedSources(aluSourceCount, lane).length))
  }
  val bruArbiters = Seq.tabulate(width) { lane =>
    Module(new RRArbiter(new OooIexBruTerminalTransaction(p),
      ownedSources(bruSourceCount, lane).length))
  }
  val loadArbiters = Seq.tabulate(width) { lane =>
    Module(new RRArbiter(new OooIexLoadResult(p),
      ownedSources(loadSourceCount, lane).length))
  }

  for (lane <- 0 until width) {
    val aluSources = ownedSources(aluSourceCount, lane)
    val bruSources = ownedSources(bruSourceCount, lane)
    val loadSources = ownedSources(loadSourceCount, lane)

    aluSources.zipWithIndex.foreach { case (source, local) =>
      aluArbiters(lane).io.in(local).valid := io.alu(source).valid
      aluArbiters(lane).io.in(local).bits := io.alu(source).bits
      io.alu(source).ready := aluArbiters(lane).io.in(local).ready
    }
    bruSources.zipWithIndex.foreach { case (source, local) =>
      bruArbiters(lane).io.in(local).valid := io.bru(source).valid
      bruArbiters(lane).io.in(local).bits := io.bru(source).bits
      io.bru(source).ready := bruArbiters(lane).io.in(local).ready
    }
    loadSources.zipWithIndex.foreach { case (source, local) =>
      loadArbiters(lane).io.in(local).valid := io.load(source).valid
      loadArbiters(lane).io.in(local).bits := io.load(source).bits
      io.load(source).ready := loadArbiters(lane).io.in(local).ready
    }

    publishers(lane).io.alu <> aluArbiters(lane).io.out
    publishers(lane).io.bru <> bruArbiters(lane).io.out
    publishers(lane).io.load <> loadArbiters(lane).io.out

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
    io.completion(lane).valid := publishers(lane).io.completion.valid
    io.completion(lane).bits := publishers(lane).io.completion.bits
    publishers(lane).io.completion.ready := io.completion(lane).ready
    io.rejected(lane) := publishers(lane).io.rejected
  }

  io.terminalFireMask := VecInit(publishers.map(_.io.terminalFire)).asUInt
}
