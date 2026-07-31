package linxcore.ctu

import chisel3._
import chisel3.util.{Decoupled, MuxCase, PopCount, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface._

/** Mainline CTU owner for template description and retained D1 packetization. */
class CTU(val p: CoreParams) extends Module {
  val io = IO(new CTUIO(p))
  private val packetIndexWidth =
    math.max(1, log2Ceil(p.widths.fetchWidth))

  private def packetIndex(value: UInt): UInt =
    if (value.getWidth > packetIndexWidth)
      value(packetIndexWidth - 1, 0)
    else
      value.pad(packetIndexWidth)

  val packetValid = RegInit(false.B)
  val packet = RegInit(0.U.asTypeOf(new FetchedPacket(p)))
  val parentIndex = RegInit(
    0.U(PrefixPacketContract.countWidth(p.widths.fetchWidth).W))
  val templateOrdinal = RegInit(0.U(8.W))

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  private def terminalMatches(candidate: RecoveryPlan): Bool =
    candidate.transactionId === recoveryPlan.transactionId &&
      candidate.cause === recoveryPlan.cause &&
      candidate.trigger.asUInt === recoveryPlan.trigger.asUInt &&
      candidate.survivingTailValid === recoveryPlan.survivingTailValid &&
      candidate.survivingTail.asUInt === recoveryPlan.survivingTail.asUInt &&
      candidate.redirectPc === recoveryPlan.redirectPc &&
      candidate.newEpoch === recoveryPlan.newEpoch

  val recoveryTxMatch = terminalMatches(io.recovery.apply.bits)
  val abortTxMatch = terminalMatches(io.recovery.abort.bits)
  val preparedCompletes =
    !preparedValid || io.recovery.prepared.fire
  val applyHit = recoveryPending && io.recovery.apply.valid &&
    preparedCompletes && recoveryTxMatch &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply
  val abortHit = recoveryPending && io.recovery.abort.valid &&
    preparedCompletes && abortTxMatch &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort

  io.recovery.prepare.ready := !recoveryPending
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := recoveryPlan
  val prepareFire = io.recovery.prepare.fire

  when(io.recovery.prepare.fire) {
    recoveryPending := true.B
    preparedValid := true.B
    recoveryPlan := io.recovery.prepare.bits
  }.elsewhen(applyHit || abortHit) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  val buffer = Module(new InstructionBuffer(
    p, depth = p.ctu.instructionBufferEntries))
  buffer.io.prune.valid := applyHit
  buffer.io.prune.bits := io.recovery.apply.bits
  val recoveryFence = recoveryPending || prepareFire || applyHit || abortHit
  io.toOoo.valid := buffer.io.deq.valid && !recoveryFence
  io.toOoo.bits := buffer.io.deq.bits
  buffer.io.deq.ready := io.toOoo.ready && !recoveryFence

  val decoders = Seq.fill(p.widths.fetchWidth)(Module(new TemplateDecode(p)))
  val decodeResults = Wire(Vec(
    p.widths.fetchWidth, new TemplateDecodeResult(p)))
  for (lane <- 0 until p.widths.fetchWidth) {
    decoders(lane).io.in := packet.entries(lane)
    decodeResults(lane) := decoders(lane).io.out
  }

  val current = packet.entries(packetIndex(parentIndex))
  val currentDecode = decodeResults(packetIndex(parentIndex))
  val currentTemplate =
    currentDecode.isTemplate && currentDecode.supported

  val ordinaryValid = Wire(Vec(p.widths.ctuOutputWidth, Bool()))
  val ordinaryIndex = Wire(Vec(
    p.widths.ctuOutputWidth,
    UInt(PrefixPacketContract.countWidth(p.widths.fetchWidth).W)))
  var ordinaryPrefix: Bool = true.B
  for (lane <- 0 until p.widths.ctuOutputWidth) {
    val index = parentIndex + lane.U
    ordinaryIndex(lane) := index
    val inRange = index < packet.count
    val laneTemplate = Mux(
      inRange,
      decodeResults(packetIndex(index)).isTemplate &&
        decodeResults(packetIndex(index)).supported,
      false.B)
    ordinaryValid(lane) := ordinaryPrefix && inRange && !laneTemplate
    ordinaryPrefix = ordinaryValid(lane)
  }
  val ordinaryCount = PopCount(ordinaryValid)
  val remainingTemplate = currentDecode.rowCount - templateOrdinal
  val templateCount = Mux(
    remainingTemplate > p.widths.ctuOutputWidth.U,
    p.widths.ctuOutputWidth.U,
    remainingTemplate)

  val expanders =
    Seq.fill(p.widths.ctuOutputWidth)(Module(new TemplateExpand(p)))
  val generated = Wire(Decoupled(new D1Packet(p)))
  generated.bits := 0.U.asTypeOf(generated.bits)
  generated.bits.count :=
    Mux(currentTemplate, templateCount, ordinaryCount)
  for (lane <- 0 until p.widths.ctuOutputWidth) {
    expanders(lane).io.active := currentTemplate && lane.U < templateCount
    expanders(lane).io.parent := current
    expanders(lane).io.decode := currentDecode
    expanders(lane).io.ordinal := templateOrdinal + lane.U
    when(currentTemplate && lane.U < templateCount) {
      generated.bits.entries(lane) := expanders(lane).io.out
    }.elsewhen(!currentTemplate && ordinaryValid(lane)) {
      generated.bits.entries(lane).kind := FrontEndOpKind.Encoded64
      generated.bits.entries(lane).parent :=
        packet.entries(packetIndex(ordinaryIndex(lane)))
    }
  }

  val traceValid = RegInit(false.B)
  val tracePacket = RegInit(0.U.asTypeOf(new TracePacket(p)))
  val firstTemplateChunk = currentTemplate && templateOrdinal === 0.U
  val traceCapacity = !firstTemplateChunk || !traceValid || io.trace.ready
  generated.valid := packetValid && !recoveryPending && !prepareFire &&
    !applyHit && !abortHit &&
    generated.bits.count.orR && traceCapacity
  buffer.io.enq <> generated

  io.trace.valid :=
    traceValid && !recoveryFence
  io.trace.bits := tracePacket

  val traceFires = io.trace.fire
  val newTrace = generated.fire && firstTemplateChunk
  when(traceFires && !newTrace) {
    traceValid := false.B
  }
  when(newTrace) {
    traceValid := true.B
    tracePacket := 0.U.asTypeOf(tracePacket)
    tracePacket.count := 1.U
    tracePacket.entries(0).source := TraceSource.Ctu
    tracePacket.entries(0).kind := TraceKind.Pipeline
    tracePacket.entries(0).instructionValid := true.B
    tracePacket.entries(0).instruction := current.identity
    tracePacket.entries(0).pc := current.pc
    tracePacket.entries(0).opcode := currentDecode.opcode
    tracePacket.entries(0).payload := currentDecode.rowCount
  }

  val inputBlocked = recoveryPending || prepareFire || applyHit || abortHit
  io.fromIfu.ready := !packetValid && !inputBlocked
  when(io.fromIfu.fire) {
    packetValid := io.fromIfu.bits.count.orR
    packet := io.fromIfu.bits
    parentIndex := 0.U
    templateOrdinal := 0.U
  }

  when(generated.fire) {
    when(currentTemplate) {
      val nextOrdinal = templateOrdinal + templateCount
      when(nextOrdinal === currentDecode.rowCount) {
        val nextParent = parentIndex + 1.U
        parentIndex := nextParent
        templateOrdinal := 0.U
        when(nextParent === packet.count) {
          packetValid := false.B
        }
      }.otherwise {
        templateOrdinal := nextOrdinal
      }
    }.otherwise {
      val nextParent = parentIndex + ordinaryCount
      parentIndex := nextParent
      templateOrdinal := 0.U
      when(nextParent === packet.count) {
        packetValid := false.B
      }
    }
  }

  val packetSurvivor = Wire(Vec(p.widths.fetchWidth, Bool()))
  for (lane <- 0 until p.widths.fetchWidth) {
    packetSurvivor(lane) := packetValid && lane.U >= parentIndex &&
      lane.U < packet.count &&
      packet.entries(lane).identity.stid =/=
        io.recovery.apply.bits.trigger.stid
  }
  val packetSurvivorCount = PopCount(packetSurvivor)
  val currentParentSurvives = packetValid &&
    current.identity.stid =/= io.recovery.apply.bits.trigger.stid
  val compactedPacket = Wire(new FetchedPacket(p))
  compactedPacket := 0.U.asTypeOf(compactedPacket)
  compactedPacket.count := packetSurvivorCount
  for (dst <- 0 until p.widths.fetchWidth) {
    val candidates = (0 until p.widths.fetchWidth).map { lane =>
      val rank =
        if (lane == 0) 0.U else PopCount(packetSurvivor.take(lane))
      (packetSurvivor(lane) && rank === dst.U) -> packet.entries(lane)
    }
    compactedPacket.entries(dst) := MuxCase(
      0.U.asTypeOf(new FetchedInstruction(p)),
      candidates)
  }

  when(applyHit) {
    packet := compactedPacket
    packetValid := packetSurvivorCount.orR
    parentIndex := 0.U
    templateOrdinal := Mux(currentParentSurvives, templateOrdinal, 0.U)
    when(
      traceValid &&
        tracePacket.entries(0).instruction.stid ===
          io.recovery.apply.bits.trigger.stid) {
      traceValid := false.B
    }
  }

  when(io.recovery.prepare.fire) {
    assert(io.recovery.prepare.bits.phase === RecoveryPhase.Prepare)
  }
  when(io.recovery.apply.valid && recoveryPending) {
    assert(recoveryTxMatch)
    assert(preparedCompletes)
  }
  when(io.recovery.abort.valid && recoveryPending) {
    assert(abortTxMatch)
    assert(preparedCompletes)
  }
  when(io.fromIfu.fire) {
    assert(io.fromIfu.bits.count <= p.widths.fetchWidth.U)
  }
  assert(!packetValid || parentIndex < packet.count)
}
