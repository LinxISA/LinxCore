package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, OHToUInt, PopCount, PriorityEncoder,
  PriorityEncoderOH, UIntToOH, Valid}
import linxcore.common.{DestinationKind, OperandClass}

class OooProductionFastResolveIO(val p: OooParams = OooParams())
    extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))

  val boundary = Decoupled(new OooFastResolveBoundaryRequest(p))
  val writeback = Decoupled(new OooFastResolveWriteback(p))
  val wakeup = Decoupled(new OooIexWakeup(p))
  val trace = Decoupled(new OooFastResolveTrace(p))
  val completion = Decoupled(new OooRobMemberCompletion(p))

  val pendingByStid = Output(Vec(p.stidCount,
    UInt(p.decodedUopCountWidth.W)))
  val terminalFire = Output(Bool())
  val s1Rejected = Valid(new OooFastResolveS1Reject(p))
}

/** Retained typed OOO fast-resolve terminal owner.
  *
  * Every common-S1 transaction is observed, but only generated fast-resolve
  * members are retained.  Boundary validation, optional PRF writeback and
  * wakeup, trace publication, and exact ROB member completion share one
  * terminal fire.  No operand-count heuristic participates in classification.
  */
class OooProductionFastResolve(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooProductionFastResolveIO(p))

  val pendingMask = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.decodedUopWidth.W))))
  val entries = Reg(Vec(p.stidCount,
    Vec(p.decodedUopWidth, new OooFastResolveEntry(p))))
  val rrStart = RegInit(0.U(p.stidWidth.W))

  val request = io.s1.bits
  val transaction = request.o3.request.reservation.transaction
  val plan = transaction.plan
  val decoded = transaction.decoded
  val requestStidInRange = plan.stid < p.stidCount.U
  val safeRequestStid = Mux(requestStidInRange, plan.stid, 0.U)

  val identityExact = request.pRename.valid && request.tuRename.valid &&
    request.dispatch.valid && request.pRename.peId === plan.peId &&
    request.tuRename.peId === plan.peId && request.dispatch.peId === plan.peId &&
    request.pRename.stid === plan.stid && request.tuRename.stid === plan.stid &&
    request.dispatch.stid === plan.stid &&
    request.pRename.epoch === plan.epoch && request.tuRename.epoch === plan.epoch &&
    request.dispatch.epoch === plan.epoch &&
    request.pRename.transactionId === plan.transactionId &&
    request.tuRename.transactionId === plan.transactionId &&
    request.dispatch.transactionId === plan.transactionId &&
    decoded.peId === plan.peId && decoded.stid === plan.stid &&
    decoded.epoch === plan.epoch && request.pRename.uopMask === decoded.uopMask &&
    request.tuRename.uopMask === decoded.uopMask

  val fast = Wire(Vec(p.decodedUopWidth, Bool()))
  val laneExact = Wire(Vec(p.decodedUopWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth) {
    val active = decoded.uopMask(uopIndex)
    val decodedUop = decoded.uops(uopIndex)
    val pUop = request.pRename.uops(uopIndex)
    val fastDisposition =
      decodedUop.recipe.disposition === OooOpcodeDisposition.FastResolve.U
    val preciseTrap = decodedUop.preciseTrap &&
      decodedUop.recipe.fastResolveClass ===
        OooFastResolveClass.PreciseTrapRecord.U
    fast(uopIndex) := active && (fastDisposition || preciseTrap)
    val ordinaryDispatch = decodedUop.recipe.disposition ===
      OooOpcodeDisposition.Dispatch.U
    val supportedClass =
      decodedUop.recipe.fastResolveClass >= OooFastResolveClass.BoundaryMetadata.U &&
        decodedUop.recipe.fastResolveClass <= OooFastResolveClass.NoEffect.U
    val pDestinationMask = VecInit(pUop.destinations.map(
      _.currentPMapping.valid)).asUInt
    val pDestinationCount = PopCount(pDestinationMask)
    val resultProducer = decodedUop.recipe.fastResolveClass ===
      OooFastResolveClass.ImmediateProducer.U ||
      decodedUop.recipe.fastResolveClass ===
        OooFastResolveClass.ControlValueProducer.U
    val boundaryProducer = decodedUop.recipe.fastResolveClass ===
      OooFastResolveClass.BoundaryMetadata.U
    val immediateProducer = decodedUop.recipe.fastResolveClass ===
      OooFastResolveClass.ImmediateProducer.U
    val controlProducer = decodedUop.recipe.fastResolveClass ===
      OooFastResolveClass.ControlValueProducer.U
    val trapRecord = decodedUop.recipe.fastResolveClass ===
      OooFastResolveClass.PreciseTrapRecord.U
    val noEffect = decodedUop.recipe.fastResolveClass ===
      OooFastResolveClass.NoEffect.U
    val destinationExact = !resultProducer || (
      pDestinationCount === 1.U &&
        pUop.destinations(0).decoded.valid &&
        pUop.destinations(0).decoded.kind === DestinationKind.Gpr &&
        pUop.destinations(0).currentPMapping.valid &&
        pUop.destinations(0).currentPMapping.stid === plan.stid &&
        pUop.destinations(0).currentPMapping.epoch === plan.epoch)
    val classExact =
      (boundaryProducer && pDestinationCount === 0.U &&
        !decodedUop.preciseTrap &&
        (decodedUop.identity.boundary.start ||
          decodedUop.identity.boundary.stop) &&
        (!decodedUop.recipe.requiresTargetValidation ||
          decodedUop.boundaryTargetValid)) ||
      (immediateProducer && destinationExact && decodedUop.immediateValid &&
        !decodedUop.preciseTrap && !decodedUop.boundaryTargetValid) ||
      (controlProducer && destinationExact && !decodedUop.preciseTrap &&
        decodedUop.identity.boundary.start &&
        (!decodedUop.recipe.requiresTargetValidation ||
          decodedUop.boundaryTargetValid)) ||
      (trapRecord && decodedUop.preciseTrap && pDestinationCount === 0.U &&
        !decodedUop.boundaryTargetValid) ||
      (noEffect && pDestinationCount === 0.U &&
        !decodedUop.preciseTrap && !decodedUop.boundaryTargetValid &&
        decodedUop.recipe.memoryRequestCount === 0.U &&
        decodedUop.recipe.dispatchWrites === 0.U &&
        decodedUop.recipe.sideEffectOwner === OooSideEffectOwner.None.U)
    laneExact(uopIndex) := !active || (
      decodedUop.valid && pUop.valid &&
        pUop.decoded.asUInt === decodedUop.asUInt &&
        (ordinaryDispatch || (fast(uopIndex) && supportedClass &&
          decodedUop.plannedChildCount === 1.U && classExact)))
  }
  val fastMask = fast.asUInt
  val shapeExact = requestStidInRange && identityExact &&
    laneExact.reduce(_ && _)
  val hasFast = fastMask.orR
  val targetFree = !hasFast || !pendingMask(safeRequestStid).orR
  io.s1.ready := shapeExact && targetFree
  io.s1Rejected.valid := io.s1.valid && !shapeExact
  io.s1Rejected.bits.peId := plan.peId
  io.s1Rejected.bits.stid := plan.stid
  io.s1Rejected.bits.epoch := plan.epoch
  io.s1Rejected.bits.transactionId := plan.transactionId
  io.s1Rejected.bits.fastMask := fastMask
  io.s1Rejected.bits.shapeExact := shapeExact

  when(io.s1.fire && hasFast) {
    pendingMask(safeRequestStid) := fastMask
    for (uopIndex <- 0 until p.decodedUopWidth) {
      when(fast(uopIndex)) {
        val decodedUop = decoded.uops(uopIndex)
        val pUop = request.pRename.uops(uopIndex)
        val entry = Wire(new OooFastResolveEntry(p))
        entry := 0.U.asTypeOf(entry)
        entry.valid := true.B
        entry.peId := plan.peId
        entry.stid := plan.stid
        entry.epoch := plan.epoch
        entry.transactionId := plan.transactionId
        entry.uopIndex := uopIndex.U
        entry.member := pUop.member
        entry.uopKey := decodedUop.identity.key
        entry.opcode := decodedUop.opcode
        entry.fastResolveClass := decodedUop.recipe.fastResolveClass
        entry.boundary := decodedUop.identity.boundary
        entry.targetValid := decodedUop.boundaryTargetValid
        entry.target := decodedUop.boundaryTarget
        entry.trapValid := decodedUop.preciseTrap
        entry.trapCause := decodedUop.trapCause
        entry.destination := pUop.destinations(0).currentPMapping

        val primaryParent = Wire(new ArchitecturalParentRef(p))
        primaryParent := decodedUop.identity.parents(0)
        for (parentIndex <- 0 until p.maxArchitecturalParentRefs) {
          val parent = decodedUop.identity.parents(parentIndex)
          when(parent.key.valid && parent.key.asUInt ===
              decodedUop.identity.key.primaryParent.asUInt) {
            primaryParent := parent
          }
        }
        entry.prediction := primaryParent.prediction
        val immediateResult = primaryParent.pc + decodedUop.immediate
        val callResult = primaryParent.pc + primaryParent.lengthBytes
        entry.resultValid := decodedUop.recipe.fastResolveClass ===
          OooFastResolveClass.ImmediateProducer.U ||
          decodedUop.recipe.fastResolveClass ===
            OooFastResolveClass.ControlValueProducer.U
        entry.result := Mux(decodedUop.recipe.fastResolveClass ===
          OooFastResolveClass.ControlValueProducer.U,
          callResult, immediateResult)
        entries(safeRequestStid)(uopIndex) := entry
      }
    }
  }

  val rotatedEligible = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val candidate = (rrStart + offset.U)(p.stidWidth - 1, 0)
    rotatedEligible(offset) := pendingMask(candidate).orR
  }
  val selectedStidValid = rotatedEligible.asUInt.orR
  val selectedStidOh = PriorityEncoderOH(rotatedEligible.asUInt)
  val selectedStid = Mux(selectedStidValid,
    (rrStart + OHToUInt(selectedStidOh))(p.stidWidth - 1, 0), 0.U)
  val selectedUop = PriorityEncoder(pendingMask(selectedStid))
  val selected = entries(selectedStid)(selectedUop)

  val boundaryRequired = selected.fastResolveClass ===
    OooFastResolveClass.BoundaryMetadata.U ||
    selected.fastResolveClass === OooFastResolveClass.ControlValueProducer.U
  val resultRequired = selected.fastResolveClass ===
    OooFastResolveClass.ImmediateProducer.U ||
    selected.fastResolveClass === OooFastResolveClass.ControlValueProducer.U
  val selectedValid = selectedStidValid && selected.valid
  // This is an atomic Decoupled fork.  A sink's valid may depend on the other
  // required sinks' ready, but never on its own ready.  Therefore no sink can
  // consume early and no direct valid/ready self-loop is created.
  io.completion.valid := selectedValid && io.trace.ready &&
    (!boundaryRequired || io.boundary.ready) &&
    (!resultRequired || (io.writeback.ready && io.wakeup.ready))
  io.completion.bits.key := selected.member

  io.boundary.valid := selectedValid && boundaryRequired &&
    io.completion.ready && io.trace.ready &&
    (!resultRequired || (io.writeback.ready && io.wakeup.ready))
  io.boundary.bits.member := selected.member
  io.boundary.bits.uopKey := selected.uopKey
  io.boundary.bits.opcode := selected.opcode
  io.boundary.bits.boundary := selected.boundary
  io.boundary.bits.prediction := selected.prediction
  io.boundary.bits.targetValid := selected.targetValid
  io.boundary.bits.target := selected.target

  io.writeback.valid := selectedValid && resultRequired &&
    io.completion.ready && io.trace.ready && io.wakeup.ready &&
    (!boundaryRequired || io.boundary.ready)
  io.writeback.bits.member := selected.member
  io.writeback.bits.stid := selected.stid
  io.writeback.bits.epoch := selected.epoch
  io.writeback.bits.ptag := selected.destination.ptag
  io.writeback.bits.ptagGeneration := selected.destination.ptagGeneration
  io.writeback.bits.data := selected.result

  io.wakeup.valid := selectedValid && resultRequired &&
    io.completion.ready && io.trace.ready && io.writeback.ready &&
    (!boundaryRequired || io.boundary.ready)
  io.wakeup.bits := 0.U.asTypeOf(io.wakeup.bits)
  io.wakeup.bits.stid := selected.stid
  io.wakeup.bits.epoch := selected.epoch
  io.wakeup.bits.operandClass := OperandClass.P
  io.wakeup.bits.ptag := selected.destination.ptag
  io.wakeup.bits.ptagGeneration := selected.destination.ptagGeneration

  io.trace.valid := selectedValid && io.completion.ready &&
    (!boundaryRequired || io.boundary.ready) &&
    (!resultRequired || (io.writeback.ready && io.wakeup.ready))
  io.trace.bits.member := selected.member
  io.trace.bits.uopKey := selected.uopKey
  io.trace.bits.opcode := selected.opcode
  io.trace.bits.fastResolveClass := selected.fastResolveClass
  io.trace.bits.trapValid := selected.trapValid
  io.trace.bits.trapCause := selected.trapCause
  io.trace.bits.resultValid := selected.resultValid
  io.trace.bits.result := selected.result

  io.terminalFire := io.completion.fire
  when(io.completion.fire) {
    assert(io.trace.fire,
      "fast resolve completion requires its exact trace terminal")
    when(boundaryRequired) {
      assert(io.boundary.fire,
        "boundary fast resolve requires BCTRL validation before completion")
    }
    when(resultRequired) {
      assert(selected.destination.valid && selected.resultValid,
        "value-producing fast resolve requires one exact P destination")
      assert(io.writeback.fire && io.wakeup.fire,
        "fast result writeback and wakeup must share completion fire")
    }
    pendingMask(selectedStid) := pendingMask(selectedStid) &
      ~UIntToOH(selectedUop, p.decodedUopWidth)
    entries(selectedStid)(selectedUop) :=
      0.U.asTypeOf(new OooFastResolveEntry(p))
    rrStart := selectedStid + 1.U
  }

  for (stid <- 0 until p.stidCount) {
    io.pendingByStid(stid) :=
      chisel3.util.PopCount(pendingMask(stid))
  }
}
