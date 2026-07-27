package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount}
import linxcore.common.DestinationKind

class OooD1FusionHistoryIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooD1DecodedPacket(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooD1DecodedPacket(p))
  val held = Output(Vec(p.stidCount, Bool()))
}

/** Per-STID canonical-uop lookbehind for cross-cycle boundary fusion. */
class OooD1FusionHistory(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD1FusionHistoryIO(p))

  val holdValid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val holdUop = Reg(Vec(p.stidCount, new OooDecodedUop(p)))
  io.held := holdValid

  val stid = io.in.bits.stid
  val selectedHoldValid = holdValid(stid)
  val selectedHold = holdUop(stid)
  val inputCount = PopCount(io.in.bits.uopMask)
  val firstInput = io.in.bits.uops(0)

  private def isPureStart(uop: OooDecodedUop): Bool =
    uop.valid && !uop.preciseTrap &&
      uop.recipe.recipeKind === OooOpcodeRecipeKind.Boundary.U &&
      uop.recipe.fusionHeadClass === OooFusionClass.StartMarker.U

  private def isPureStop(uop: OooDecodedUop): Bool =
    uop.valid && !uop.preciseTrap &&
      uop.recipe.recipeKind === OooOpcodeRecipeKind.Boundary.U &&
      uop.recipe.fusionTailClass === OooFusionClass.StopMarker.U

  private def isCarrier(uop: OooDecodedUop): Bool =
    uop.valid && !uop.preciseTrap &&
      uop.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      uop.recipe.recipeKind =/= OooOpcodeRecipeKind.EngineCmd.U

  private def sameContext(left: OooDecodedUop, right: OooDecodedUop): Bool = {
    val leftTailIndex = Mux(left.identity.parentCount.orR, left.identity.parentCount - 1.U, 0.U)
    val leftTail = left.identity.parents(leftTailIndex)
    val rightHead = right.identity.parents(0)
    leftTail.key.valid && rightHead.key.valid &&
      leftTail.key.peId === rightHead.key.peId &&
      leftTail.key.stid === rightHead.key.stid &&
      leftTail.key.epoch === rightHead.key.epoch &&
      leftTail.prediction.epoch === rightHead.prediction.epoch
  }

  val hasFirstInput = inputCount.orR
  val fuseStart =
    selectedHoldValid && hasFirstInput && isPureStart(selectedHold) && isCarrier(firstInput) &&
      !firstInput.identity.boundary.start && sameContext(selectedHold, firstInput)
  val fuseStop =
    selectedHoldValid && hasFirstInput && isCarrier(selectedHold) && isPureStop(firstInput) &&
      !selectedHold.identity.boundary.stop && sameContext(selectedHold, firstInput)

  val startMerged = Wire(new OooDecodedUop(p))
  startMerged := firstInput
  startMerged.identity.parentCount := selectedHold.identity.parentCount +& firstInput.identity.parentCount
  startMerged.identity.parents(0) := selectedHold.identity.parents(0)
  for (index <- 0 until p.maxArchitecturalParentRefs - 1) {
    startMerged.identity.parents(index + 1) := firstInput.identity.parents(index)
  }
  startMerged.identity.boundary.start := true.B
  startMerged.identity.boundary.explicit := true.B
  startMerged.boundaryTargetValid := selectedHold.boundaryTargetValid
  startMerged.boundaryTarget := selectedHold.boundaryTarget

  val stopMerged = Wire(new OooDecodedUop(p))
  stopMerged := selectedHold
  stopMerged.identity.parentCount := selectedHold.identity.parentCount +& 1.U
  for (index <- 0 until p.maxArchitecturalParentRefs) {
    when(selectedHold.identity.parentCount === index.U) {
      stopMerged.identity.parents(index) := firstInput.identity.parents(0)
    }
  }
  stopMerged.identity.boundary.stop := true.B
  stopMerged.identity.boundary.explicit := true.B

  val combined = Wire(Vec(p.decodedUopWidth + 1, new OooDecodedUop(p)))
  val combinedValid = Wire(Vec(p.decodedUopWidth + 1, Bool()))
  combined.foreach(_ := 0.U.asTypeOf(new OooDecodedUop(p)))
  combinedValid.foreach(_ := false.B)

  when(fuseStart) {
    combined(0) := startMerged
    combinedValid(0) := true.B
    for (index <- 1 until p.decodedUopWidth) {
      combined(index) := io.in.bits.uops(index)
      combinedValid(index) := io.in.bits.uopMask(index)
    }
  }.elsewhen(fuseStop) {
    combined(0) := stopMerged
    combinedValid(0) := true.B
    for (index <- 1 until p.decodedUopWidth) {
      combined(index) := io.in.bits.uops(index)
      combinedValid(index) := io.in.bits.uopMask(index)
    }
  }.elsewhen(selectedHoldValid) {
    combined(0) := selectedHold
    combinedValid(0) := true.B
    for (index <- 0 until p.decodedUopWidth) {
      combined(index + 1) := io.in.bits.uops(index)
      combinedValid(index + 1) := io.in.bits.uopMask(index)
    }
  }.otherwise {
    for (index <- 0 until p.decodedUopWidth) {
      combined(index) := io.in.bits.uops(index)
      combinedValid(index) := io.in.bits.uopMask(index)
    }
  }

  val combinedCount = PopCount(combinedValid)
  val inputInstructionCount = PopCount(io.in.bits.acceptedInstructionMask)
  val combinedLastIndex = Mux(combinedCount.orR, combinedCount - 1.U, 0.U)
  val combinedLast = combined(combinedLastIndex)
  val hasNonUopOwner = io.in.bits.ctuParentMask.orR || io.in.bits.complexParentMask.orR
  val shouldHoldLast =
    !io.in.bits.endOfStream && !hasNonUopOwner && combinedCount.orR &&
      (isPureStart(combinedLast) || (isCarrier(combinedLast) && !combinedLast.identity.boundary.stop))
  val outputCount = combinedCount - shouldHoldLast.asUInt
  val overflowDrain =
    selectedHoldValid && (
      hasNonUopOwner ||
        (inputInstructionCount === p.instructionDecodeWidth.U && !shouldHoldLast) ||
        (!fuseStart && !fuseStop && combinedCount > p.decodedUopWidth.U && !shouldHoldLast))

  val normalPacket = Wire(new OooD1DecodedPacket(p))
  normalPacket := 0.U.asTypeOf(normalPacket)
  normalPacket.peId := io.in.bits.peId
  normalPacket.stid := io.in.bits.stid
  normalPacket.epoch := io.in.bits.epoch
  normalPacket.endOfStream := io.in.bits.endOfStream
  normalPacket.ctuParentMask := io.in.bits.ctuParentMask
  normalPacket.complexParentMask := io.in.bits.complexParentMask
  normalPacket.illegalParentMask := io.in.bits.illegalParentMask
  normalPacket.fusedStartMask := io.in.bits.fusedStartMask
  normalPacket.fusedStopMask := io.in.bits.fusedStopMask
  normalPacket.uopMask :=
    ((1.U((p.decodedUopWidth + 1).W) << outputCount) - 1.U)(p.decodedUopWidth - 1, 0)
  for (index <- 0 until p.decodedUopWidth) {
    when(index.U < outputCount) {
      normalPacket.uops(index) := combined(index)
    }
  }

  val drainPacket = Wire(new OooD1DecodedPacket(p))
  drainPacket := 0.U.asTypeOf(drainPacket)
  drainPacket.peId := selectedHold.identity.key.primaryParent.peId
  drainPacket.stid := selectedHold.identity.key.primaryParent.stid
  drainPacket.epoch := selectedHold.identity.key.primaryParent.epoch
  drainPacket.uopMask := 1.U
  drainPacket.uops(0) := selectedHold

  private def populateDemand(packet: OooD1DecodedPacket): Unit = {
    val parentRows = PopCount(packet.uops.flatMap(_.identity.parents).map { parent =>
      parent.key.valid && parent.traceOwner
    })
    val divertedRows = PopCount(packet.ctuParentMask) +& PopCount(packet.complexParentMask)
    packet.demand.instructionRows := parentRows +& divertedRows
    packet.acceptedInstructionMask :=
      ((1.U((p.instructionDecodeWidth + 1).W) << packet.demand.instructionRows) - 1.U)(
        p.instructionDecodeWidth - 1, 0)
    packet.demand.decodedUops := PopCount(packet.uopMask)
    packet.demand.robGroups := 0.U
    packet.demand.brobSlots := PopCount(packet.uops.map { uop =>
      uop.valid && uop.identity.boundary.start
    })
    packet.demand.pcBaseWrites := PopCount(packet.uops.flatMap(_.identity.parents).map { parent =>
      parent.key.valid && parent.traceOwner && parent.prediction.valid && parent.prediction.taken
    })
    packet.demand.pDestinations := PopCount(packet.uops.flatMap(_.destinations).map { destination =>
      destination.valid && destination.kind === DestinationKind.Gpr
    })
    packet.demand.tAllocations := PopCount(packet.uops.flatMap(_.destinations).map { destination =>
      destination.valid && destination.kind === DestinationKind.T
    })
    packet.demand.uAllocations := PopCount(packet.uops.flatMap(_.destinations).map { destination =>
      destination.valid && destination.kind === DestinationKind.U
    })
    packet.demand.mapQRows := packet.demand.pDestinations
    for (dispatchClass <- 0 until p.iqClassCount) {
      packet.demand.dispatchWritesByClass(dispatchClass) := packet.uops.map { uop =>
        Mux(uop.valid, uop.recipe.dispatchDemand(dispatchClass), 0.U)
      }.reduce(_ +& _)
    }
    packet.demand.dispatchWritesByBank.foreach(_ := 0.U)
    packet.demand.loadIds := packet.uops.map { uop =>
      val load = uop.recipe.recipeKind === OooOpcodeRecipeKind.ScalarLoad.U ||
        uop.recipe.recipeKind === OooOpcodeRecipeKind.PairLoad.U
      Mux(uop.valid && load, uop.recipe.memoryRequestCount, 0.U)
    }.reduce(_ +& _)
    packet.demand.storeIds := packet.uops.map { uop =>
      val store = uop.recipe.recipeKind === OooOpcodeRecipeKind.ScalarStore.U ||
        uop.recipe.recipeKind === OooOpcodeRecipeKind.PairStore.U
      Mux(uop.valid && store, uop.recipe.memoryRequestCount, 0.U)
    }.reduce(_ +& _)
  }

  populateDemand(normalPacket)
  populateDemand(drainPacket)

  val normalHasSideband =
    normalPacket.ctuParentMask.orR || normalPacket.complexParentMask.orR ||
      normalPacket.illegalParentMask.orR
  val normalProducesOutput = io.in.valid && (outputCount.orR || normalHasSideband)
  io.out.valid := !io.cancel(stid) && Mux(overflowDrain, io.in.valid, normalProducesOutput)
  io.out.bits := Mux(overflowDrain, drainPacket, normalPacket)
  io.in.ready :=
    !io.cancel(stid) && Mux(
      overflowDrain,
      false.B,
      Mux(normalProducesOutput, io.out.ready, true.B))

  val inputFire = io.in.valid && io.in.ready
  val drainFire = io.out.valid && io.out.ready && overflowDrain
  for (index <- 0 until p.stidCount) {
    when(io.cancel(index)) {
      holdValid(index) := false.B
    }.elsewhen(drainFire && stid === index.U) {
      holdValid(index) := false.B
    }.elsewhen(inputFire && stid === index.U) {
      when(shouldHoldLast) {
        holdValid(index) := true.B
        holdUop(index) := combinedLast
      }.otherwise {
        holdValid(index) := false.B
      }
    }
  }

  when(io.in.valid) {
    val denseInput =
      ((1.U((p.decodedUopWidth + 1).W) << inputCount) - 1.U)(p.decodedUopWidth - 1, 0)
    assert(io.in.bits.uopMask === denseInput, "D1 fusion history accepts only dense uops")
    assert(combinedCount <= (p.decodedUopWidth + 1).U)
    when(fuseStart) {
      assert(startMerged.identity.parentCount <= p.maxArchitecturalParentRefs.U)
    }
    when(fuseStop) {
      assert(stopMerged.identity.parentCount <= p.maxArchitecturalParentRefs.U)
    }
  }
}

class OooD1ProductionDecodeIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooRawInstructionGroup(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooD1DecodedPacket(p))
  val held = Output(Vec(p.stidCount, Bool()))
}

/** Production D1: canonical decode followed by per-STID cross-cycle fusion. */
class OooD1ProductionDecode(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD1ProductionDecodeIO(p))
  val decode = Module(new OooD1Decode(p))
  val fusion = Module(new OooD1FusionHistory(p))

  decode.io.in <> io.in
  fusion.io.in <> decode.io.out
  fusion.io.cancel := io.cancel
  io.out <> fusion.io.out
  io.held := fusion.io.held
}
