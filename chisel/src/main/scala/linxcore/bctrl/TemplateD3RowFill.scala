package linxcore.bctrl

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}
import linxcore.commit.CommitTraceParams
import linxcore.common._
import linxcore.rob.ROBID

class TemplateD3RowFillIO(
    val p: InterfaceParams,
    val tp: TemplateD3InterfaceParams,
    val traceParams: CommitTraceParams)
    extends Bundle {
  val reserveResp = Flipped(Decoupled(new TemplateReserveResponse(p, tp)))
  val rowFill = Flipped(Decoupled(new TemplateRowFill(p, tp, traceParams)))
  val rowFillAck = Valid(new TemplateOwnerID(p))
  val cancel = Flipped(Valid(new TemplateCancelRequest(p)))
  val recovery = Flipped(Valid(new TemplateRecoveryRequest(p)))
  val recoveryAck = Valid(new TemplateCancelRecoveryAck)
  val trace = Output(new TemplateTrace(p))
  val fatal = Output(new TemplateFatal(p))
  val live = Output(Bool())
  val nextOrdinal = Output(UInt(5.W))
  val consumedMask = Output(UInt(TemplateD3Constants.MaxRows.W))
  val unfilledCount = Output(UInt(5.W))
}

class TemplateD3RowFill(
    val p: InterfaceParams = InterfaceParams(robEntries = 32),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(p.robEntries > 1, "template D3 row fill ROB entries must be greater than one")
  require((p.robEntries & (p.robEntries - 1)) == 0, "template D3 row fill ROB entries must be a power of two")
  tp.requireFullProfileRob(p)

  val io = IO(new TemplateD3RowFillIO(p, tp, traceParams))

  private val cycle = RegInit(0.U(64.W))
  cycle := cycle + 1.U

  private def robidEq(lhs: ROBID, rhs: ROBID): Bool =
    lhs.valid === rhs.valid && lhs.wrap === rhs.wrap && lhs.value === rhs.value

  private def descriptorGroupEq(lhs: TemplateGroupDescriptor, rhs: TemplateGroupDescriptor): Bool =
    lhs.valid === rhs.valid &&
      lhs.lxcpuId === rhs.lxcpuId &&
      lhs.lxcpuContextGeneration === rhs.lxcpuContextGeneration &&
      lhs.peId === rhs.peId &&
      lhs.stid === rhs.stid &&
      lhs.engineLocalTid === rhs.engineLocalTid &&
      lhs.bid === rhs.bid &&
      robidEq(lhs.bidRobid, rhs.bidRobid) &&
      lhs.gid === rhs.gid &&
      robidEq(lhs.gidRobid, rhs.gidRobid) &&
      lhs.groupBaseRid === rhs.groupBaseRid &&
      robidEq(lhs.groupBaseRidRobid, rhs.groupBaseRidRobid) &&
      lhs.groupBaseRobSlot === rhs.groupBaseRobSlot &&
      lhs.groupRowCount === rhs.groupRowCount &&
      lhs.checkpointId === rhs.checkpointId &&
      lhs.templateGeneration === rhs.templateGeneration &&
      lhs.sourcePc === rhs.sourcePc &&
      lhs.sourceRaw === rhs.sourceRaw &&
      lhs.formId === rhs.formId &&
      lhs.encodedN === rhs.encodedN &&
      lhs.firstRid === rhs.firstRid &&
      robidEq(lhs.firstRidRobid, rhs.firstRidRobid) &&
      lhs.firstRobSlot === rhs.firstRobSlot &&
      lhs.rowCount === rhs.rowCount &&
      lhs.lastRid === rhs.lastRid &&
      robidEq(lhs.lastRidRobid, rhs.lastRidRobid) &&
      lhs.leaseValid === rhs.leaseValid &&
      lhs.fatalPoisoned === rhs.fatalPoisoned

  private def ownerMatchesDescriptor(owner: TemplateOwnerID, descriptor: TemplateGroupDescriptor): Bool =
    owner.lxcpuId === descriptor.lxcpuId &&
      owner.lxcpuContextGeneration === descriptor.lxcpuContextGeneration &&
      owner.peId === descriptor.peId &&
      owner.stid === descriptor.stid &&
      owner.engineLocalTid === descriptor.engineLocalTid &&
      owner.bid === descriptor.bid &&
      robidEq(owner.bidRobid, descriptor.bidRobid) &&
      owner.gid === descriptor.gid &&
      robidEq(owner.gidRobid, descriptor.gidRobid) &&
      owner.groupBaseRid === descriptor.groupBaseRid &&
      robidEq(owner.groupBaseRidRobid, descriptor.groupBaseRidRobid) &&
      owner.groupBaseRobSlot === descriptor.groupBaseRobSlot &&
      owner.groupRowCount === descriptor.groupRowCount &&
      owner.checkpointId === descriptor.checkpointId &&
      owner.templateGeneration === descriptor.templateGeneration &&
      owner.sourcePc === descriptor.sourcePc &&
      owner.sourceRaw === descriptor.sourceRaw &&
      owner.formId === descriptor.formId &&
      owner.encodedN === descriptor.encodedN

  private def tokenEq(lhs: TemplateFillToken, rhs: TemplateFillToken): Bool =
    lhs.asUInt === rhs.asUInt

  private def compositeHandleEq(lhs: TemplateCreditCompositeHandle, rhs: TemplateCreditCompositeHandle): Bool =
    lhs.asUInt === rhs.asUInt

  private def memoryShapeOk(owner: TemplateOwnerID): Bool = {
    val noLoad = !owner.loadIdValid && owner.loadIdValue === 0.U && owner.loadIdGeneration === 0.U &&
      owner.loadReplayGeneration === 0.U
    val noStore = !owner.storeIdValid && owner.storeIdValue === 0.U && owner.storeIdGeneration === 0.U
    val noMemory = !owner.lsidValid && owner.lsidValue === 0.U && owner.lsidWrapOrGeneration === 0.U &&
      noLoad && noStore
    val loadMemory = owner.lsidValid && owner.loadIdValid && !owner.storeIdValid && noStore
    val storeMemory = owner.lsidValid && !owner.loadIdValid && owner.storeIdValid && noLoad

    Mux(
      owner.rowKind === TemplateRowKind.VLOAD.asUInt || owner.rowKind === TemplateRowKind.LOAD.asUInt,
      loadMemory,
      Mux(owner.rowKind === TemplateRowKind.STORE.asUInt, storeMemory, noMemory))
  }

  private def ordinalMask(rowCount: UInt, consumed: UInt): UInt = {
    val bits = Wire(Vec(TemplateD3Constants.MaxRows, Bool()))
    for (idx <- 0 until TemplateD3Constants.MaxRows) {
      bits(idx) := idx.U < rowCount && !consumed(idx)
    }
    bits.asUInt
  }

  val liveReg = RegInit(false.B)
  val descriptorReg = RegInit(0.U.asTypeOf(new TemplateGroupDescriptor(p)))
  val tokensReg = RegInit(VecInit(Seq.fill(TemplateD3Constants.MaxRows)(0.U.asTypeOf(new TemplateFillToken(p, tp)))))
  val nextOrdinalReg = RegInit(0.U(5.W))
  val consumedMaskReg = RegInit(0.U(TemplateD3Constants.MaxRows.W))
  val stallWatchdogReg = RegInit(0.U(log2Ceil(tp.templateFillMaxCycles + 2).W))
  val fatalReg = RegInit(0.U.asTypeOf(new TemplateFatal(p)))

  val unfilledMask = ordinalMask(descriptorReg.rowCount, consumedMaskReg)
  val unfilledCount = PopCount(unfilledMask.asBools)
  val groupComplete = liveReg && nextOrdinalReg === descriptorReg.rowCount
  val acceptingGroup = !liveReg && !fatalReg.valid
  val reserveFire = io.reserveResp.valid && io.reserveResp.ready
  val acceptedReserve = reserveFire && io.reserveResp.bits.accepted && !io.reserveResp.bits.rejected

  val cancelHit = liveReg && io.cancel.valid && descriptorGroupEq(io.cancel.bits.owner, descriptorReg)
  val recoveryHit = liveReg && io.recovery.valid &&
    io.recovery.bits.stid === descriptorReg.stid &&
    Mux(
      io.recovery.bits.inclusive,
      ROBID.lessEqual(io.recovery.bits.firstKilledBid, descriptorReg.bidRobid),
      ROBID.less(io.recovery.bits.firstKilledBid, descriptorReg.bidRobid))

  val expectedToken = tokensReg(nextOrdinalReg)
  val expectedRid = TemplateD3Constants.advanceROBID(descriptorReg.groupBaseRidRobid, nextOrdinalReg)
  val expectedRowKind =
    TemplateD3Constants.rowKind(descriptorReg.formId, descriptorReg.encodedN, nextOrdinalReg)
  val fillOwner = io.rowFill.bits.owner
  val fillToken = io.rowFill.bits.token

  val rowInOrder = fillOwner.childOrdinal === nextOrdinalReg &&
    fillToken.childOrdinal === nextOrdinalReg &&
    nextOrdinalReg < descriptorReg.rowCount
  val identityOk = ownerMatchesDescriptor(fillOwner, descriptorReg) &&
    TemplateD3Constants.ownerProjectionMatches(fillOwner) &&
    fillOwner.rowPresent &&
    fillOwner.groupRowCount === descriptorReg.rowCount &&
    fillOwner.childOrdinal === nextOrdinalReg &&
    fillOwner.rid === expectedRid.value &&
    robidEq(fillOwner.ridRobid, expectedRid) &&
    fillOwner.robSlot === expectedRid.value
  val tokenOk = fillToken.valid &&
    tokenEq(fillToken, expectedToken) &&
    fillToken.descriptorGeneration === descriptorReg.templateGeneration &&
    fillToken.childOrdinal === nextOrdinalReg &&
    robidEq(fillToken.rid, expectedRid) &&
    fillToken.rowKind === expectedRowKind &&
    compositeHandleEq(io.rowFill.bits.creditTokenHandle, fillToken.compositeHandle)
  val rowPlanOk = fillOwner.rowKind === expectedRowKind &&
    io.rowFill.bits.isFinal === (expectedRowKind === TemplateRowKind.FINAL.asUInt)
  val memoryOk = memoryShapeOk(fillOwner)
  val fillValid = liveReg && !fatalReg.valid && !cancelHit && !recoveryHit &&
    rowInOrder && identityOk && tokenOk && rowPlanOk && memoryOk

  val badOwner = liveReg && io.rowFill.valid && (!identityOk || !tokenOk)
  val badPlan = liveReg && io.rowFill.valid && (rowInOrder && identityOk && tokenOk && !rowPlanOk)
  val badMemory = liveReg && io.rowFill.valid && (rowInOrder && identityOk && tokenOk && rowPlanOk && !memoryOk)
  val badOrder = liveReg && io.rowFill.valid && (!rowInOrder)

  io.reserveResp.ready := acceptingGroup
  io.rowFill.ready := liveReg && !fatalReg.valid && !cancelHit && !recoveryHit

  val fillFire = io.rowFill.valid && io.rowFill.ready
  val fillAccepted = fillFire && fillValid

  io.rowFillAck.valid := fillAccepted
  io.rowFillAck.bits := fillOwner

  io.recoveryAck.valid := cancelHit || recoveryHit
  io.recoveryAck.bits.valid := cancelHit || recoveryHit
  io.recoveryAck.bits.killedMask := unfilledMask
  io.recoveryAck.bits.retainedMask := consumedMaskReg

  io.trace := 0.U.asTypeOf(io.trace)
  io.trace.reserveAccepted := acceptedReserve
  io.trace.fillAccepted := fillAccepted
  io.trace.reservedUnfilledCount := unfilledCount
  io.trace.owner := Mux(fillAccepted, fillOwner, 0.U.asTypeOf(io.trace.owner))
  io.trace.descriptor := descriptorReg
  io.trace.fatalValid := fatalReg.valid

  io.fatal := fatalReg
  io.live := liveReg
  io.nextOrdinal := nextOrdinalReg
  io.consumedMask := consumedMaskReg
  io.unfilledCount := unfilledCount

  private def latchFatal(reason: TemplateFatalReason.Type, code: TemplateFatalCode.Type, owner: TemplateOwnerID): Unit = {
    fatalReg.valid := true.B
    fatalReg.owner := owner
    fatalReg.descriptorKey := descriptorReg
    fatalReg.reason := reason
    fatalReg.code := code
    fatalReg.sourceContext.valid := true.B
    fatalReg.sourceContext.sourceOwner := owner
    fatalReg.sourceContext.sourceDescriptor := descriptorReg
    fatalReg.sourceContext.sourcePort := TemplateFatalSourcePort.rowFill
    fatalReg.sourceContext.sourceGeneration := cycle
    fatalReg.sourceContext.sourceCycle := cycle
    fatalReg.sourceContext.sourcePc := owner.sourcePc
    fatalReg.sourceContext.sourceRaw := owner.sourceRaw
    fatalReg.sourceContext.observed.valid := true.B
    fatalReg.sourceContext.observed.owner := owner
    fatalReg.sourceContext.observed.descriptor := descriptorReg
    fatalReg.sourceContext.expected.valid := true.B
    fatalReg.sourceContext.expected.owner := 0.U.asTypeOf(fatalReg.sourceContext.expected.owner)
    fatalReg.sourceContext.expected.descriptor := descriptorReg
    fatalReg.ackBitmap := 0.U
    fatalReg.quiescent := true.B
    fatalReg.teardownAck := false.B
  }

  when(reset.asBool) {
    liveReg := false.B
  }.elsewhen(acceptedReserve) {
    liveReg := true.B
    descriptorReg := io.reserveResp.bits.descriptor
    tokensReg := io.reserveResp.bits.tokens
    nextOrdinalReg := 0.U
    consumedMaskReg := 0.U
    stallWatchdogReg := 0.U
    fatalReg := 0.U.asTypeOf(fatalReg)
  }.elsewhen(cancelHit || recoveryHit) {
    liveReg := false.B
    descriptorReg := 0.U.asTypeOf(descriptorReg)
    tokensReg := VecInit(Seq.fill(TemplateD3Constants.MaxRows)(0.U.asTypeOf(new TemplateFillToken(p, tp))))
    nextOrdinalReg := 0.U
    consumedMaskReg := 0.U
    stallWatchdogReg := 0.U
  }.elsewhen(fillFire && fillAccepted) {
    consumedMaskReg := consumedMaskReg | (1.U(TemplateD3Constants.MaxRows.W) << nextOrdinalReg)
    nextOrdinalReg := nextOrdinalReg + 1.U
    stallWatchdogReg := 0.U
    when(io.rowFill.bits.isFinal) {
      liveReg := false.B
    }
  }.elsewhen(fillFire && !fillAccepted) {
    when(badMemory) {
      latchFatal(TemplateFatalReason.BadMemoryIdentity, TemplateFatalCode.BadMemoryShape, fillOwner)
    }.elsewhen(badPlan) {
      latchFatal(TemplateFatalReason.BadRowPlan, TemplateFatalCode.BadRowKind, fillOwner)
    }.elsewhen(badOrder) {
      latchFatal(TemplateFatalReason.ProtocolViolation, TemplateFatalCode.OutOfOrderFill, fillOwner)
    }.otherwise {
      latchFatal(TemplateFatalReason.BadOwnerIdentity, TemplateFatalCode.BadOwner, fillOwner)
    }
  }.elsewhen(liveReg && !groupComplete && !io.rowFill.valid && !fatalReg.valid) {
    when(stallWatchdogReg === tp.templateFillMaxCycles.U) {
      latchFatal(TemplateFatalReason.TimeoutOrNestedFatal, TemplateFatalCode.FillTimeout, 0.U.asTypeOf(fillOwner))
    }.otherwise {
      stallWatchdogReg := stallWatchdogReg + 1.U
    }
  }

  assert(!acceptedReserve || io.reserveResp.bits.descriptor.rowCount <= TemplateD3Constants.MaxRows.U)
  assert(!acceptedReserve || io.reserveResp.bits.descriptor.rowCount === io.reserveResp.bits.descriptor.groupRowCount)
  assert(!fillAccepted || io.rowFill.bits.owner.childOrdinal === nextOrdinalReg)
  assert(!fillAccepted || ROBID.equal(io.rowFill.bits.owner.ridRobid, expectedRid))
  assert(!fillAccepted || io.rowFill.bits.token.rowKind === expectedRowKind)
}
