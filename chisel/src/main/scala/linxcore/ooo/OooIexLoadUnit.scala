package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, PopCount, PriorityEncoder, RRArbiter,
  Valid}
import linxcore.common.{DestinationKind, OperandClass}

object OooIexLoadResponseKind extends ChiselEnum {
  val Hit, Miss, Fault = Value
}

object OooIexLoadTrackState extends ChiselEnum {
  val Free, RequestPending, AwaitingResponse, ResultPending = Value
}

class OooIexLoadMemoryRequest(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val load = new OooIexLoadGeneration(p)
  val address = UInt(p.pcWidth.W)
  val accessBytes = UInt(4.W)
}

class OooIexLoadMemoryResponse(val p: OooParams = OooParams()) extends Bundle {
  val load = new OooIexLoadGeneration(p)
  val kind = OooIexLoadResponseKind()
  val data = UInt(p.pcWidth.W)
  val faultCause = UInt(p.trapCauseWidth.W)
}

/** Retained load return before atomic RF/wakeup/ROB publication. */
class OooIexLoadResult(val p: OooParams = OooParams()) extends Bundle {
  val agu = new OooIexAguLoadRequest(p)
  val load = new OooIexLoadGeneration(p)
  val data = UInt(p.pcWidth.W)
  val faultValid = Bool()
  val faultCause = UInt(p.trapCauseWidth.W)
}

class OooIexLoadAcceptReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val noFreeEntry = Bool()
  val duplicateProducer = Bool()
  val identityExact = Bool()
  val accessExact = Bool()
  val destinationExact = Bool()
}

class OooIexLoadResponseReject(val p: OooParams = OooParams()) extends Bundle {
  val response = new OooIexLoadMemoryResponse(p)
  val matchCount = UInt(p.countWidth(p.iexLoadTrackEntries).W)
}

class OooIexLoadTrackEntry(val p: OooParams = OooParams()) extends Bundle {
  val state = OooIexLoadTrackState()
  val agu = new OooIexAguLoadRequest(p)
  val load = new OooIexLoadGeneration(p)
  val data = UInt(p.pcWidth.W)
  val faultValid = Bool()
  val faultCause = UInt(p.trapCauseWidth.W)
}

class OooIexLoadUnitIO(val p: OooParams = OooParams()) extends Bundle {
  val agu = Flipped(Decoupled(new OooIexAguLoadRequest(p)))
  val memoryRequest = Decoupled(new OooIexLoadMemoryRequest(p))
  val memoryResponse = Flipped(Decoupled(new OooIexLoadMemoryResponse(p)))
  val result = Decoupled(new OooIexLoadResult(p))

  val speculativeWakeup = Valid(new OooIexWakeup(p))
  val bypass = Valid(new OooIexBypassCandidate(p))
  val cancel = Valid(new OooIexLoadCancel(p))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))

  val acceptRejected = Valid(new OooIexLoadAcceptReject(p))
  val responseRejected = Valid(new OooIexLoadResponseReject(p))
  val occupied = Output(UInt(p.countWidth(p.iexLoadTrackEntries).W))
}

/** Multi-entry scalar-load tracker with exact generation replay.
  *
  * AGU acceptance allocates one tracking entry and a producer-qualified load
  * generation. Every memory-attempt fire emits one speculative wakeup. A hit
  * retains extended data for bypass/result; a miss cancels that generation
  * and retries under the same entry with a new generation; a fault cancels
  * speculation and retains a precise terminal result. RF write, committed
  * wakeup, ROB completion, and trace remain a later atomic sink.
  */
class OooIexLoadUnit(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexLoadUnitIO(p))

  private val entries = RegInit(VecInit(Seq.fill(p.iexLoadTrackEntries)(
    0.U.asTypeOf(new OooIexLoadTrackEntry(p)))))
  private val generation = RegInit(VecInit(Seq.fill(p.iexLoadTrackEntries)(
    0.U(p.loadGenerationWidth.W))))

  private def memberKilled(member: RobMemberKey): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits, member)

  private def destinationExact(destination: OooIexDestinationState): Bool =
    destination.valid && (destination.kind === DestinationKind.Gpr ||
      destination.kind === DestinationKind.T ||
      destination.kind === DestinationKind.U)

  private def operandClass(destination: OooIexDestinationState): OperandClass.Type =
    Mux(destination.kind === DestinationKind.Gpr, OperandClass.P,
      Mux(destination.kind === DestinationKind.T, OperandClass.T,
        Mux(destination.kind === DestinationKind.U, OperandClass.U,
          OperandClass.Invalid)))

  private def extendedData(entry: OooIexLoadTrackEntry, raw: UInt): UInt = {
    val bytes = entry.agu.accessBytes
    val signed = entry.agu.signExtend
    Mux(bytes === 1.U,
      Mux(signed, Cat(Fill(p.pcWidth - 8, raw(7)), raw(7, 0)),
        raw(7, 0).pad(p.pcWidth)),
      Mux(bytes === 2.U,
        Mux(signed, Cat(Fill(p.pcWidth - 16, raw(15)), raw(15, 0)),
          raw(15, 0).pad(p.pcWidth)),
        Mux(bytes === 4.U,
          Mux(signed, Cat(Fill(p.pcWidth - 32, raw(31)), raw(31, 0)),
            raw(31, 0).pad(p.pcWidth)), raw)))
  }

  val validEntry = VecInit(entries.map(_.state =/= OooIexLoadTrackState.Free))
  val killedEntry = VecInit(entries.zip(validEntry).map { case (entry, valid) =>
    valid && memberKilled(entry.agu.execute.i2.row.member)
  })
  val freeMask = VecInit(entries.map(
    _.state === OooIexLoadTrackState.Free)).asUInt
  val freeAvailable = freeMask.orR
  val freeIndex = PriorityEncoder(freeMask)
  val incomingMember = io.agu.bits.execute.i2.row.member
  val duplicateProducer = entries.zip(validEntry).map { case (entry, valid) =>
    valid && !memberKilled(entry.agu.execute.i2.row.member) &&
      entry.load.producer.asUInt === incomingMember.asUInt
  }.reduce(_ || _)
  val incomingRow = io.agu.bits.execute.i2.row
  val incomingIdentityExact = incomingRow.valid &&
    incomingMember.group.valid && incomingMember.bid.valid &&
    incomingRow.stid < p.stidCount.U &&
    incomingMember.group.stid === incomingRow.stid &&
    incomingMember.group.peId === incomingRow.peId
  val incomingAccessExact = io.agu.bits.accessBytes === 1.U ||
    io.agu.bits.accessBytes === 2.U || io.agu.bits.accessBytes === 4.U ||
    io.agu.bits.accessBytes === 8.U
  val incomingDestinationExact = destinationExact(io.agu.bits.destination)
  io.agu.ready := freeAvailable && !duplicateProducer &&
    incomingIdentityExact && incomingAccessExact && incomingDestinationExact &&
    !memberKilled(incomingMember)
  io.acceptRejected.valid := io.agu.valid && !io.agu.ready
  io.acceptRejected.bits.member := incomingMember
  io.acceptRejected.bits.noFreeEntry := !freeAvailable
  io.acceptRejected.bits.duplicateProducer := duplicateProducer
  io.acceptRejected.bits.identityExact := incomingIdentityExact
  io.acceptRejected.bits.accessExact := incomingAccessExact
  io.acceptRejected.bits.destinationExact := incomingDestinationExact

  val requestArbiter = Module(new RRArbiter(
    new OooIexLoadMemoryRequest(p), p.iexLoadTrackEntries))
  for (index <- 0 until p.iexLoadTrackEntries) {
    val entry = entries(index)
    val request = requestArbiter.io.in(index)
    request.valid := entry.state === OooIexLoadTrackState.RequestPending &&
      !killedEntry(index)
    request.bits.stid := entry.agu.execute.i2.row.stid
    request.bits.epoch := entry.agu.execute.i2.row.epoch
    request.bits.load := entry.load
    request.bits.address := entry.agu.address
    request.bits.accessBytes := entry.agu.accessBytes
  }
  io.memoryRequest <> requestArbiter.io.out

  val responseMatch = VecInit(entries.zip(validEntry).map {
    case (entry, valid) => valid && !memberKilled(entry.load.producer) &&
      entry.state === OooIexLoadTrackState.AwaitingResponse &&
      entry.load.asUInt === io.memoryResponse.bits.load.asUInt
  })
  val responseMatchCount = PopCount(responseMatch)
  val responseExact = responseMatchCount === 1.U
  val responseIndex = PriorityEncoder(responseMatch)
  io.memoryResponse.ready := true.B
  io.responseRejected.valid := io.memoryResponse.fire && !responseExact
  io.responseRejected.bits.response := io.memoryResponse.bits
  io.responseRejected.bits.matchCount := responseMatchCount

  io.cancel.valid := io.memoryResponse.fire && responseExact &&
    io.memoryResponse.bits.kind =/= OooIexLoadResponseKind.Hit
  io.cancel.bits.stid := entries(responseIndex).agu.execute.i2.row.stid
  io.cancel.bits.epoch := entries(responseIndex).agu.execute.i2.row.epoch
  io.cancel.bits.load := io.memoryResponse.bits.load

  io.speculativeWakeup := 0.U.asTypeOf(io.speculativeWakeup)
  when(io.memoryRequest.fire) {
    val entry = entries(requestArbiter.io.chosen)
    val destination = entry.agu.destination
    io.speculativeWakeup.valid := true.B
    io.speculativeWakeup.bits.kind := OooIexWakeupKind.SpeculativeLoad
    io.speculativeWakeup.bits.stid := entry.agu.execute.i2.row.stid
    io.speculativeWakeup.bits.epoch := entry.agu.execute.i2.row.epoch
    io.speculativeWakeup.bits.operandClass := operandClass(destination)
    io.speculativeWakeup.bits.ptag := destination.ptag
    io.speculativeWakeup.bits.ptagGeneration := destination.ptagGeneration
    io.speculativeWakeup.bits.localTag := destination.localTag
    io.speculativeWakeup.bits.localSequence := destination.localSequence
    io.speculativeWakeup.bits.load := entry.load
  }

  val resultArbiter = Module(new RRArbiter(
    new OooIexLoadResult(p), p.iexLoadTrackEntries))
  for (index <- 0 until p.iexLoadTrackEntries) {
    val entry = entries(index)
    val result = resultArbiter.io.in(index)
    result.valid := entry.state === OooIexLoadTrackState.ResultPending &&
      !killedEntry(index)
    result.bits.agu := entry.agu
    result.bits.load := entry.load
    result.bits.data := entry.data
    result.bits.faultValid := entry.faultValid
    result.bits.faultCause := entry.faultCause
  }
  io.result <> resultArbiter.io.out

  io.bypass := 0.U.asTypeOf(io.bypass)
  when(io.result.valid && !io.result.bits.faultValid) {
    val result = io.result.bits
    val destination = result.agu.destination
    io.bypass.valid := true.B
    io.bypass.bits.stid := result.agu.execute.i2.row.stid
    io.bypass.bits.epoch := result.agu.execute.i2.row.epoch
    io.bypass.bits.producer := result.load.producer
    io.bypass.bits.operandClass := operandClass(destination)
    io.bypass.bits.ptag := destination.ptag
    io.bypass.bits.ptagGeneration := destination.ptagGeneration
    io.bypass.bits.localTag := destination.localTag
    io.bypass.bits.localSequence := destination.localSequence
    io.bypass.bits.load := result.load
    io.bypass.bits.stage := OooIexBypassStage.W1
    io.bypass.bits.data := result.data
  }

  when(io.agu.fire) {
    val nextGeneration = generation(freeIndex) + 1.U
    val entry = entries(freeIndex)
    entry := 0.U.asTypeOf(entry)
    entry.state := OooIexLoadTrackState.RequestPending
    entry.agu := io.agu.bits
    entry.load.valid := true.B
    entry.load.producer := incomingMember
    entry.load.generation := nextGeneration
    generation(freeIndex) := nextGeneration
  }

  when(io.memoryRequest.fire) {
    entries(requestArbiter.io.chosen).state :=
      OooIexLoadTrackState.AwaitingResponse
  }

  when(io.memoryResponse.fire && responseExact) {
    val entry = entries(responseIndex)
    when(io.memoryResponse.bits.kind === OooIexLoadResponseKind.Miss) {
      val nextGeneration = generation(responseIndex) + 1.U
      entry.state := OooIexLoadTrackState.RequestPending
      entry.load.generation := nextGeneration
      generation(responseIndex) := nextGeneration
    }.otherwise {
      entry.state := OooIexLoadTrackState.ResultPending
      entry.data := Mux(
        io.memoryResponse.bits.kind === OooIexLoadResponseKind.Hit,
        extendedData(entry, io.memoryResponse.bits.data), 0.U)
      entry.faultValid :=
        io.memoryResponse.bits.kind === OooIexLoadResponseKind.Fault
      entry.faultCause := io.memoryResponse.bits.faultCause
    }
  }

  when(io.result.fire) {
    entries(resultArbiter.io.chosen).state := OooIexLoadTrackState.Free
  }

  for (index <- 0 until p.iexLoadTrackEntries) {
    when(killedEntry(index)) {
      entries(index).state := OooIexLoadTrackState.Free
    }
  }

  assert(responseMatchCount <= 1.U,
    "one memory response must match at most one exact load generation")
  io.occupied := PopCount(validEntry)
}
