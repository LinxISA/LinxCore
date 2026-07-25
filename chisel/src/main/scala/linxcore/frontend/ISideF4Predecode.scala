package linxcore.frontend

import chisel3._
import chisel3.util.Decoupled
import linxcore.common.InterfaceParams

class ISideF4PredecodeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val in = Flipped(Decoupled(new ISideAssembledGroup(p)))
  val out = Decoupled(new InstructionBufferEnqueueGroup(p))
  val flush = Input(new IfuInnerFlush(p))
}

class ISideF4Predecode(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.fetchWidth == 4)
  require(p.insnWidth == 64)

  private val boundaryRules =
    FrontendOpcodeDecodeTable.Rules.filter(rule => rule.isBlockBoundary || rule.isBlockStop)

  val io = IO(new ISideF4PredecodeIO(p))

  val inputThreadId = io.in.bits.entries(0).identity.threadId
  val killed = io.flush.valid && inputThreadId === io.flush.threadId

  io.in.ready := io.out.ready && !killed
  io.out.valid := io.in.valid && !killed
  io.out.bits := 0.U.asTypeOf(io.out.bits)

  val allowLane = Wire(Vec(p.fetchWidth, Bool()))
  val stopLane = Wire(Vec(p.fetchWidth, Bool()))
  val outputValid = Wire(Vec(p.fetchWidth, Bool()))
  allowLane(0) := true.B

  for (lane <- 0 until p.fetchWidth) {
    val candidate = io.in.bits.entries(lane)
    val laneInputValid = io.in.bits.validMask(lane)
    val startMatches = boundaryRules.filter(_.isBlockBoundary).map { rule =>
      candidate.lenBytes === rule.lenBytes.U &&
      (candidate.insn & rule.mask.U(p.insnWidth.W)) === rule.value.U(p.insnWidth.W)
    }
    val stopMatches = boundaryRules.filter(_.isBlockStop).map { rule =>
      candidate.lenBytes === rule.lenBytes.U &&
      (candidate.insn & rule.mask.U(p.insnWidth.W)) === rule.value.U(p.insnWidth.W)
    }
    val isStart = if (startMatches.isEmpty) false.B else startMatches.reduce(_ || _)
    val isStop = if (stopMatches.isEmpty) false.B else stopMatches.reduce(_ || _)
    val laneValid = laneInputValid && allowLane(lane)

    outputValid(lane) := laneValid
    stopLane(lane) := laneValid && isStop
    io.out.bits.entries(lane).pc := candidate.pc
    io.out.bits.entries(lane).insn := candidate.insn
    io.out.bits.entries(lane).lenBytes := candidate.lenBytes
    io.out.bits.entries(lane).isBlockStart := laneValid && isStart
    io.out.bits.entries(lane).isBlockStop := laneValid && isStop
    io.out.bits.entries(lane).identity := candidate.identity
    io.out.bits.entries(lane).prediction := candidate.prediction

    if (lane + 1 < p.fetchWidth) {
      allowLane(lane + 1) := allowLane(lane) && laneInputValid && !stopLane(lane)
    }
  }

  io.out.bits.validMask := outputValid.asUInt
}
