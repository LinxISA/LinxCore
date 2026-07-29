package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Queue, Valid}
import linxcore.common.OperandClass

class OooIexP1I2LaneIO(val p: OooParams = OooParams()) extends Bundle {
  val p1 = Flipped(Decoupled(new OooIexP1Request(p)))

  /** One whole-uop request. The shared arbiter grants every requested P/T/U
    * source and the optional PC read together or denies the attempt together.
    */
  val readAttempt = Valid(new OooIexI1ReadAttempt(p))
  val readCapability = Output(UInt(OooIexDomainCapability.Count.W))
  val readDecisionValid = Input(Bool())
  val readGrant = Input(Bool())
  val sourceDataValid = Input(UInt(p.maxSourceOperands.W))
  val sourceData = Input(Vec(p.maxSourceOperands, UInt(p.pcWidth.W)))
  val pcDataValid = Input(Bool())
  val pcData = Input(UInt(p.pcWidth.W))
  val bypass = Input(Vec(p.iexBypassPorts,
    Valid(new OooIexBypassCandidate(p))))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  /** Exact post-P1 resource conflicts. Port 0 addresses I1 and port 1 I2.
    * A producer must hold valid until ready; cancellation does not mutate the
    * retained stage until the corresponding IQ retry has storage.
    */
  val stageCancel = Flipped(Vec(2, Decoupled(new OooIexStageCancel(p))))

  val i2 = Decoupled(new OooIexI2Transaction(p))
  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))

  val repick = Decoupled(new OooIexReadRepick(p))
  val p1Rejected = Valid(new OooIexP1Reject(p))
  val readRejected = Valid(new OooIexReadReject(p))
  // Index 0 reports I1 cancellation and index 1 reports retained I2
  // cancellation. Both can be valid when one recovery kills both stages.
  val recoveryCanceled = Output(Vec(2, Valid(new OooIexReadRepick(p))))
  // P1, retained I1, and retained I2 cancellation diagnostics. Canonical IQ
  // inFlight/specReady mutation is driven directly by the same cancel event.
  val loadCanceled = Output(Vec(3, Valid(new OooIexReadRepick(p))))
  val stageCanceled = Output(Vec(2, Valid(new OooIexStageCancel(p))))
  val stageCancelRejected = Output(Vec(2,
    Valid(new OooIexStageCancelReject(p))))

  val i1Occupied = Output(Bool())
  val i2Occupied = Output(Bool())
  val empty = Output(Bool())
}

/** One reusable canonical P1/I1/I2 issue lane.
  *
  * P1 accepts one exact resident IQ row. I1 selects exact W1/W2/W3 bypass
  * values, then exposes one atomic read attempt covering every remaining
  * RF-needed P/T/U source plus the optional PC token. A
  * shared read arbiter returns one explicit decision: denial releases the
  * attempt for exact repick, while a grant may advance only when every
  * requested readyless read is valid in that cycle. I2 retains the complete
  * row, merged operand data, bypass provenance, and reconstructed PC under
  * downstream backpressure.
  *
  * This lane owns only pipeline residency. The physical IQ, RFs, PC buffer,
  * picker, and execution pipe remain separate single owners.
  */
class OooIexP1I2Lane(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexP1I2LaneIO(p))

  val i1Valid = RegInit(false.B)
  val i1Request = Reg(new OooIexP1Request(p))
  val i2Valid = RegInit(false.B)
  val i2Transaction = Reg(new OooIexI2Transaction(p))
  val retryQueue = Module(new Queue(new OooIexReadRepick(p), 2,
    pipe = true, flow = true))
  io.repick <> retryQueue.io.deq

  private def killedByRecovery(row: OooIexIssueRow): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits, row.member)

  private def repickFrom(row: OooIexIssueRow): OooIexReadRepick = {
    val result = Wire(new OooIexReadRepick(p))
    result.member := row.member
    result.reservation := row.reservation
    result
  }

  private def canceledByLoad(row: OooIexIssueRow): Bool =
    io.loadCancel.map { cancel =>
      val sourceMatch = row.sources.map { source =>
        source.valid && source.specReady && source.load.valid &&
          cancel.bits.load.valid &&
          source.load.asUInt === cancel.bits.load.asUInt
      }.reduce(_ || _)
      cancel.valid && cancel.bits.stid === row.stid &&
        cancel.bits.epoch === row.epoch && sourceMatch
    }.reduce(_ || _)

  val p1Row = io.p1.bits.row
  val p1Member = p1Row.member
  val p1Reservation = p1Row.reservation
  val p1ClassInRange = p1Reservation.uopClass.asUInt < p.iqClassCount.U
  val p1BankInRange = p1Reservation.bank < p.iqBankCount.U
  val p1EntryInRange =
    p1Reservation.speculativeSlot < p.iqEntriesPerBank.U
  val p1IdentityExact = p1Row.valid && p1Member.group.valid &&
    p1Member.bid.valid && p1Reservation.valid && p1ClassInRange &&
    p1BankInRange && p1EntryInRange &&
    p1Row.peId === p1Member.group.peId &&
    p1Row.stid === p1Member.group.stid &&
    p1Row.uopKey.primaryParent.valid &&
    p1Row.uopKey.primaryParent.peId === p1Row.peId &&
    p1Row.uopKey.primaryParent.stid === p1Row.stid
  val p1SourcesReady = p1Row.sources.map(source =>
    !source.valid || source.ready || source.specReady).reduce(_ && _)
  val p1ParentIndexInRange =
    io.p1.bits.pcParentIndex < p.maxArchitecturalParentRefs.U
  val safeP1ParentIndex = Mux(
    p1ParentIndexInRange, io.p1.bits.pcParentIndex, 0.U)
  val selectedP1PcToken = p1Row.parentPcTokens(safeP1ParentIndex)
  val p1PcTokenExact = !io.p1.bits.pcReadRequired ||
    (p1ParentIndexInRange &&
      io.p1.bits.pcParentIndex < p1Row.parentCount &&
      selectedP1PcToken.valid)
  val p1ShapeExact = p1IdentityExact && p1SourcesReady && p1PcTokenExact
  val p1Killed = killedByRecovery(p1Row)
  val p1LoadCanceled = canceledByLoad(p1Row)

  val i1Killed = i1Valid && killedByRecovery(i1Request.row)
  val i2Killed = i2Valid && killedByRecovery(i2Transaction.row)
  val i1LoadCanceled = i1Valid && canceledByLoad(i1Request.row)
  val i2LoadCanceled = i2Valid && canceledByLoad(i2Transaction.row)

  private def stageCancelIdentityExact(
      request: OooIexStageCancel,
      row: OooIexIssueRow): Bool =
    request.member.asUInt === row.member.asUInt &&
      request.reservation.asUInt === row.reservation.asUInt

  val i1StageRequest = io.stageCancel(0).bits
  val i2StageRequest = io.stageCancel(1).bits
  val i1StageExact = i1Valid && !i1Killed && !i1LoadCanceled &&
    i1StageRequest.stage === OooIexStageCancelPoint.I1 &&
    stageCancelIdentityExact(i1StageRequest, i1Request.row) &&
    OooIexStageCancelReason.legal(i1StageRequest.reasonMask)
  val i2StageExact = i2Valid && !i2Killed && !i2LoadCanceled &&
    i2StageRequest.stage === OooIexStageCancelPoint.I2 &&
    stageCancelIdentityExact(i2StageRequest, i2Transaction.row) &&
    OooIexStageCancelReason.legal(i2StageRequest.reasonMask)
  val i1StagePending = io.stageCancel(0).valid && i1StageExact
  val i2StagePending = io.stageCancel(1).valid && i2StageExact
  // The older I2 transaction wins the single exact IQ retry path. I1 remains
  // retained and its producer observes ready low until the following cycle.
  val selectI2StageCancel = i2StagePending
  val selectI1StageCancel = i1StagePending && !selectI2StageCancel
  val stageCancelFreeze = i1StagePending || i2StagePending

  val i2WillDrain = i2Valid && !i2Killed && !i2LoadCanceled &&
    !i2StagePending && io.i2.ready
  val i2CanAccept = !i2Valid || i2WillDrain || i2Killed || i2LoadCanceled

  val i1LogicalSourceMask = VecInit(
    i1Request.row.sources.map(_.valid)).asUInt
  val i1BypassValid = Wire(Vec(p.maxSourceOperands, Bool()))
  val i1Bypass = Wire(Vec(p.maxSourceOperands,
    new OooIexBypassCandidate(p)))
  for (port <- 0 until p.iexLoadCancelPorts) {
    when(io.loadCancel(port).valid) {
      assert(io.loadCancel(port).bits.load.valid &&
        io.loadCancel(port).bits.load.producer.group.valid &&
        io.loadCancel(port).bits.load.producer.bid.valid &&
        io.loadCancel(port).bits.stid < p.stidCount.U &&
        io.loadCancel(port).bits.load.producer.group.stid ===
          io.loadCancel(port).bits.stid,
        "IEX load cancel requires an exact same-STID producer")
    }
  }
  for (port <- 0 until p.iexBypassPorts) {
    when(io.bypass(port).valid) {
      assert(io.bypass(port).bits.producer.group.valid &&
        io.bypass(port).bits.producer.bid.valid &&
        io.bypass(port).bits.producer.group.stid ===
          io.bypass(port).bits.stid,
        "IEX bypass candidates require an exact same-STID producer")
      when(io.bypass(port).bits.load.valid) {
        assert(io.bypass(port).bits.load.producer.asUInt ===
          io.bypass(port).bits.producer.asUInt,
          "load bypass provenance must name the data producer exactly")
      }
    }
  }
  for (sourceIndex <- 0 until p.maxSourceOperands) {
    val source = i1Request.row.sources(sourceIndex)
    val matches = VecInit(io.bypass.map { candidate =>
      val pMatch = source.operandClass === OperandClass.P &&
        candidate.bits.operandClass === OperandClass.P &&
        source.ptag === candidate.bits.ptag &&
        source.ptagGeneration === candidate.bits.ptagGeneration
      val tMatch = source.operandClass === OperandClass.T &&
        candidate.bits.operandClass === OperandClass.T &&
        source.localTag === candidate.bits.localTag &&
        source.localSequence.asUInt === candidate.bits.localSequence.asUInt
      val uMatch = source.operandClass === OperandClass.U &&
        candidate.bits.operandClass === OperandClass.U &&
        source.localTag === candidate.bits.localTag &&
        source.localSequence.asUInt === candidate.bits.localSequence.asUInt
      val requiresSpecBypass = source.specReady && !source.ready
      val speculativeExact = !requiresSpecBypass ||
        (source.load.valid && candidate.bits.load.valid &&
          source.load.asUInt === candidate.bits.load.asUInt)
      candidate.valid && source.valid &&
        candidate.bits.stid === i1Request.row.stid &&
        candidate.bits.epoch === i1Request.row.epoch &&
        candidate.bits.producer.group.stid === i1Request.row.stid &&
        speculativeExact && (pMatch || tMatch || uMatch)
    })
    val w1Matches = VecInit(matches.zip(io.bypass).map {
      case (matchesSource, candidate) => matchesSource &&
        candidate.bits.stage === OooIexBypassStage.W1
    })
    val w2Matches = VecInit(matches.zip(io.bypass).map {
      case (matchesSource, candidate) => matchesSource &&
        candidate.bits.stage === OooIexBypassStage.W2
    })
    val w3Matches = VecInit(matches.zip(io.bypass).map {
      case (matchesSource, candidate) => matchesSource &&
        candidate.bits.stage === OooIexBypassStage.W3
    })
    val preferred = Wire(Vec(p.iexBypassPorts, Bool()))
    preferred := Mux(w1Matches.asUInt.orR, w1Matches,
      Mux(w2Matches.asUInt.orR, w2Matches, w3Matches))
    assert(PopCount(preferred) <= 1.U,
      "one source cannot select duplicate candidates at the same bypass age")
    i1BypassValid(sourceIndex) := preferred.asUInt.orR
    i1Bypass(sourceIndex) := 0.U.asTypeOf(i1Bypass(sourceIndex))
    for (port <- (0 until p.iexBypassPorts).reverse) {
      when(preferred(port)) {
        i1Bypass(sourceIndex) := io.bypass(port).bits
      }
    }
  }
  val i1SpecSourcesCovered = i1Request.row.sources.zipWithIndex.map {
    case (source, sourceIndex) =>
      !source.valid || !source.specReady || source.ready ||
        i1BypassValid(sourceIndex)
  }.reduce(_ && _)
  val i1RfSourceMask = VecInit(i1Request.row.sources.zipWithIndex.map {
    case (source, sourceIndex) =>
      val requiresSpecBypass = source.specReady && !source.ready
      source.valid && !i1BypassValid(sourceIndex) && !requiresSpecBypass
  }).asUInt
  val i1ParentIndexInRange =
    i1Request.pcParentIndex < p.maxArchitecturalParentRefs.U
  val safeI1ParentIndex = Mux(
    i1ParentIndexInRange, i1Request.pcParentIndex, 0.U)
  val i1PcToken = i1Request.row.parentPcTokens(safeI1ParentIndex)

  io.readAttempt.valid := i1Valid && !i1Killed && !i1LoadCanceled &&
    !stageCancelFreeze && retryQueue.io.enq.ready &&
    i2CanAccept &&
    i1SpecSourcesCovered
  io.readAttempt.bits.member := i1Request.row.member
  io.readAttempt.bits.reservation := i1Request.row.reservation
  io.readAttempt.bits.stid := i1Request.row.stid
  io.readAttempt.bits.epoch := i1Request.row.epoch
  io.readAttempt.bits.transactionId := i1Request.row.transactionId
  io.readAttempt.bits.sourceMask := i1RfSourceMask
  io.readAttempt.bits.sources := i1Request.row.sources
  io.readAttempt.bits.pcRequired := i1Request.pcReadRequired
  io.readAttempt.bits.pcToken := i1PcToken
  val i1ClassInRange =
    i1Request.row.reservation.uopClass.asUInt < p.iqClassCount.U
  val safeI1Class = Mux(i1ClassInRange,
    i1Request.row.reservation.uopClass.asUInt, 0.U)
  io.readCapability := Mux(i1Valid && i1ClassInRange,
    i1Request.row.recipe.dispatchCapabilities(safeI1Class), 0.U)

  val readDecision = io.readAttempt.valid && io.readDecisionValid
  val readDenied = readDecision && !io.readGrant
  val readResponseExact = io.sourceDataValid === i1RfSourceMask &&
    (!i1Request.pcReadRequired || io.pcDataValid)
  val readAccepted = readDecision && io.readGrant && readResponseExact
  val readInvalid = readDecision && io.readGrant && !readResponseExact
  val i1StageCancelFire = io.stageCancel(0).fire && i1StageExact
  val i2StageCancelFire = io.stageCancel(1).fire && i2StageExact
  val i1WillClear = i1Killed || i1LoadCanceled || i1StageCancelFire ||
    readDenied || readAccepted || readInvalid
  // Do not replace a denied/invalid attempt on the same edge: the single
  // exact retry channel must never need to return two different members.
  val i1CanAccept = !i1Valid || readAccepted || i1Killed || i1LoadCanceled ||
    i1StageCancelFire

  // Invalid P1 payloads are consumed as typed rejects when the lane has room;
  // a malformed producer cannot wedge the ready/valid boundary forever.
  // A canceled P1 copy carries no state into I1 and can be consumed even when
  // an unrelated I1 row is resident; never retain a one-cycle cancel pulse.
  io.p1.ready := (i1CanAccept || p1LoadCanceled) && !p1Killed &&
    !stageCancelFreeze && retryQueue.io.enq.ready &&
    !retryQueue.io.count.orR
  io.p1Rejected.valid := io.p1.fire && !p1ShapeExact && !p1LoadCanceled
  io.p1Rejected.bits.member := p1Member
  io.p1Rejected.bits.reservation := p1Reservation
  io.p1Rejected.bits.identityExact := p1IdentityExact
  io.p1Rejected.bits.sourcesReady := p1SourcesReady
  io.p1Rejected.bits.pcTokenExact := p1PcTokenExact

  val p1Failed = io.p1.fire && !p1ShapeExact && !p1LoadCanceled
  val ordinaryRepick = readDenied || readInvalid || p1Failed
  val stageRepick = selectI1StageCancel || selectI2StageCancel
  retryQueue.io.enq.valid := ordinaryRepick || stageRepick
  retryQueue.io.enq.bits := Mux(ordinaryRepick,
    Mux(p1Failed, repickFrom(p1Row), repickFrom(i1Request.row)),
    Mux(selectI2StageCancel, repickFrom(i2Transaction.row),
      repickFrom(i1Request.row)))
  when(ordinaryRepick) {
    assert(retryQueue.io.enq.ready,
      "non-backpressurable P1/I1 retry requires reserved queue capacity")
  }

  for (port <- 0 until 2) {
    val expectedStage = if (port == 0) OooIexStageCancelPoint.I1
      else OooIexStageCancelPoint.I2
    val occupied = if (port == 0) i1Valid && !i1Killed && !i1LoadCanceled
      else i2Valid && !i2Killed && !i2LoadCanceled
    val row = if (port == 0) i1Request.row else i2Transaction.row
    val exact = if (port == 0) i1StageExact else i2StageExact
    val selected = if (port == 0) selectI1StageCancel
      else selectI2StageCancel
    io.stageCancel(port).ready := Mux(exact,
      selected && retryQueue.io.enq.ready, true.B)
    io.stageCanceled(port).valid := io.stageCancel(port).fire && exact
    io.stageCanceled(port).bits := io.stageCancel(port).bits
    io.stageCancelRejected(port).valid :=
      io.stageCancel(port).fire && !exact
    io.stageCancelRejected(port).bits.request := io.stageCancel(port).bits
    io.stageCancelRejected(port).bits.stageExact :=
      io.stageCancel(port).bits.stage === expectedStage
    io.stageCancelRejected(port).bits.occupied := occupied
    io.stageCancelRejected(port).bits.identityExact := occupied &&
      stageCancelIdentityExact(io.stageCancel(port).bits, row)
    io.stageCancelRejected(port).bits.reasonExact :=
      OooIexStageCancelReason.legal(io.stageCancel(port).bits.reasonMask)
  }
  io.readRejected.valid := readInvalid
  io.readRejected.bits.member := i1Request.row.member
  io.readRejected.bits.reservation := i1Request.row.reservation
  io.readRejected.bits.sourceMask := i1RfSourceMask
  io.readRejected.bits.sourceDataValid := io.sourceDataValid
  io.readRejected.bits.pcRequired := i1Request.pcReadRequired
  io.readRejected.bits.pcDataValid := io.pcDataValid

  io.recoveryCanceled(0).valid := i1Killed
  io.recoveryCanceled(0).bits := repickFrom(i1Request.row)
  io.recoveryCanceled(1).valid := i2Killed
  io.recoveryCanceled(1).bits := repickFrom(i2Transaction.row)
  io.loadCanceled(0).valid := io.p1.fire && p1LoadCanceled
  io.loadCanceled(0).bits := repickFrom(p1Row)
  io.loadCanceled(1).valid := i1LoadCanceled
  io.loadCanceled(1).bits := repickFrom(i1Request.row)
  io.loadCanceled(2).valid := i2LoadCanceled
  io.loadCanceled(2).bits := repickFrom(i2Transaction.row)

  io.i2.valid := i2Valid && !i2Killed && !i2LoadCanceled && !i2StagePending
  io.i2.bits := i2Transaction
  io.i1Occupied := i1Valid && !i1Killed && !i1LoadCanceled
  io.i2Occupied := i2Valid && !i2Killed && !i2LoadCanceled
  io.empty := !io.i1Occupied && !io.i2Occupied && !io.repick.valid

  when(i2WillDrain || i2Killed || i2LoadCanceled || i2StageCancelFire) {
    i2Valid := false.B
  }
  when(readAccepted) {
    i2Valid := true.B
    i2Transaction.row := i1Request.row
    i2Transaction.sourceMask := i1LogicalSourceMask
    for (sourceIndex <- 0 until p.maxSourceOperands) {
      i2Transaction.sourceData(sourceIndex) := Mux(
        i1BypassValid(sourceIndex), i1Bypass(sourceIndex).data,
        io.sourceData(sourceIndex))
      i2Transaction.bypass(sourceIndex) := i1Bypass(sourceIndex)
    }
    i2Transaction.bypassMask := i1BypassValid.asUInt
    i2Transaction.pcValid := i1Request.pcReadRequired
    i2Transaction.pc := Mux(i1Request.pcReadRequired, io.pcData, 0.U)
  }

  when(i1WillClear) {
    i1Valid := false.B
  }
  when(io.p1.fire && p1ShapeExact && !p1LoadCanceled) {
    i1Valid := true.B
    i1Request := io.p1.bits
  }

}
