package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount}
import linxcore.common.{
  BranchPredictionSidecar,
  DestinationKind,
  InterfaceParams,
  OperandClass
}
import linxcore.frontend.{FrontendInstructionDecodeLane, FrontendRegAliasClassify}

object OooD1TrapCause {
  val IllegalEncoding: BigInt = 2
  val IllegalOperandClass: BigInt = 3
}

private[ooo] class OooD1LaneResult(val p: OooParams = OooParams()) extends Bundle {
  val emitsUop = Bool()
  val ctuParent = Bool()
  val complexParent = Bool()
  val illegalParent = Bool()
  val pureStartMarker = Bool()
  val pureStopMarker = Bool()
  val fusionCarrier = Bool()
  val uop = new OooDecodedUop(p)
}

private[ooo] class OooD1DecodeLaneIO(val p: OooParams = OooParams()) extends Bundle {
  val active = Input(Bool())
  val in = Input(new OooRawInstruction(p))
  val out = Output(new OooD1LaneResult(p))
}

/** Exact decode and operand-normalization leaf for one architectural parent. */
private[ooo] class OooD1DecodeLane(val p: OooParams = OooParams()) extends Module {
  private val legacyP = InterfaceParams()
  require(legacyP.insnWidth == p.instructionWidth)
  require(legacyP.opcodeWidth == p.opcodeWidth)
  require(legacyP.archRegWidth == p.archRegWidth)
  require(legacyP.pcWidth == p.pcWidth)

  val io = IO(new OooD1DecodeLaneIO(p))
  val parent = io.in.parent
  val recipe = OooOpcodeRecipeTable.decode(p, parent.rawInstruction, parent.lengthBytes)

  val prediction = Wire(new BranchPredictionSidecar(legacyP))
  prediction.valid := parent.prediction.valid
  prediction.predictionTag := parent.prediction.predictionTag
  prediction.transactionId := parent.prediction.transactionId
  prediction.fetchPacketUid := parent.prediction.fetchPacketUid
  prediction.fetchSeq := parent.prediction.fetchSeq
  prediction.requestPc := parent.prediction.requestPc
  prediction.taken := parent.prediction.taken
  prediction.branchPc := parent.prediction.branchPc
  prediction.target := parent.prediction.target
  prediction.fallthroughPc := parent.prediction.fallthroughPc
  prediction.kind := parent.prediction.kind
  prediction.provider := parent.prediction.provider
  prediction.stage := parent.prediction.stage
  prediction.confidence := parent.prediction.confidence
  prediction.checkpointId := parent.prediction.checkpointId
  prediction.epoch := parent.prediction.epoch

  val legacy = Module(new FrontendInstructionDecodeLane(legacyP))
  legacy.io.active := io.active
  legacy.io.peId := parent.key.peId
  legacy.io.threadId := parent.key.stid
  legacy.io.pc := parent.pc
  legacy.io.insn := parent.rawInstruction
  legacy.io.lenBytes := parent.lengthBytes
  legacy.io.isLastInBlock := false.B
  legacy.io.checkpointId := parent.prediction.checkpointId
  legacy.io.instructionUid := parent.key.instructionId
  legacy.io.parentUid := parent.key.instructionId
  legacy.io.fetchPacketUid := parent.prediction.fetchPacketUid
  legacy.io.fetchSlot := 0.U
  legacy.io.prediction := prediction

  private def copySource(
      target: OooDecodedOperand,
      source: linxcore.common.DecodedOperand): Unit = {
    target.valid := source.valid
    target.operandClass := source.operandClass
    target.atag := source.archTag
    target.relativeIndex := source.relTag
  }

  private def copyDestination(
      target: OooDecodedDestination,
      source: linxcore.common.DecodedDestination): Unit = {
    target.valid := source.valid
    target.kind := source.kind
    target.atag := source.archTag
    target.relativeIndex := source.relTag
  }

  val pairDst0 = FrontendRegAliasClassify.destination(
    legacyP, true.B, parent.rawInstruction(27, 23))
  val pairDst1 = FrontendRegAliasClassify.destination(
    legacyP, true.B, parent.rawInstruction(15, 11))
  val pairDataLow = FrontendRegAliasClassify.source(
    legacyP, true.B, parent.rawInstruction(35, 31))
  val pairDataHigh = FrontendRegAliasClassify.source(
    legacyP, true.B, parent.rawInstruction(10, 6))
  val pairWideData = FrontendRegAliasClassify.source(
    legacyP, true.B, parent.rawInstruction(47, 43))
  val pairBase = FrontendRegAliasClassify.source(
    legacyP, true.B, parent.rawInstruction(35, 31))
  val pairIndex = FrontendRegAliasClassify.source(
    legacyP, true.B, parent.rawInstruction(40, 36))

  val pairLoad = recipe.recipeKind === OooOpcodeRecipeKind.PairLoad.U
  val pairStore = recipe.recipeKind === OooOpcodeRecipeKind.PairStore.U
  val pairLoadDestinationIllegal =
    pairLoad && (pairDst0.kind =/= DestinationKind.Gpr || pairDst1.kind =/= DestinationKind.Gpr)
  val recipeIllegal = !recipe.valid || recipe.disposition === OooOpcodeDisposition.Illegal.U
  val effectiveIllegal = io.in.fetchFaultValid || recipeIllegal || pairLoadDestinationIllegal
  val ctuParent = recipe.valid && recipe.disposition === OooOpcodeDisposition.Ctu.U && !effectiveIllegal
  val complexParent =
    recipe.valid && recipe.complexBreak && recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      !effectiveIllegal

  val uop = Wire(new OooDecodedUop(p))
  uop := 0.U.asTypeOf(uop)
  uop.valid := io.active && !ctuParent && !complexParent
  uop.identity.key.primaryParent := parent.key
  uop.identity.key.uopOrdinal := 0.U
  uop.identity.key.uopCount := 1.U
  uop.identity.parentCount := 1.U
  uop.identity.parents(0) := parent
  uop.opcode := recipe.opcode
  uop.recipe := recipe
  uop.plannedChildCount := recipe.uopCountMax
  uop.immediateValid := legacy.io.out.immValid
  uop.immediate := legacy.io.out.imm
  uop.boundaryTargetValid := recipe.requiresTargetValidation && legacy.io.out.immValid
  uop.boundaryTarget := legacy.io.out.boundaryTarget
  uop.preciseTrap := effectiveIllegal
  uop.trapCause := Mux(
    io.in.fetchFaultValid,
    io.in.fetchFaultCause,
    Mux(
      pairLoadDestinationIllegal,
      OooD1TrapCause.IllegalOperandClass.U(p.trapCauseWidth.W),
      OooD1TrapCause.IllegalEncoding.U(p.trapCauseWidth.W)))

  for (idx <- 0 until p.maxSourceOperands) {
    uop.sources(idx).valid := false.B
    uop.sources(idx).operandClass := OperandClass.Invalid
    uop.sources(idx).atag := 0.U
    uop.sources(idx).relativeIndex := 0.U
  }
  for (idx <- 0 until p.maxDestinationOperands) {
    uop.destinations(idx).valid := false.B
    uop.destinations(idx).kind := DestinationKind.None
    uop.destinations(idx).atag := 0.U
    uop.destinations(idx).relativeIndex := 0.U
  }
  for (idx <- 0 until math.min(3, p.maxSourceOperands)) {
    copySource(uop.sources(idx), legacy.io.out.src(idx))
  }
  copyDestination(uop.destinations(0), legacy.io.out.dst(0))

  when(pairLoad) {
    copyDestination(uop.destinations(0), pairDst0)
    copyDestination(uop.destinations(1), pairDst1)
    copySource(uop.sources(0), pairBase)
    when(recipe.pSourceCount === 2.U) {
      copySource(uop.sources(1), pairIndex)
    }
  }

  when(pairStore) {
    when(recipe.pSourceCount === 3.U) {
      // Immediate pair store: SrcD, SrcD1, SrcR(base), immediate.
      copySource(uop.sources(0), pairDataLow)
      copySource(uop.sources(1), pairDataHigh)
      copySource(uop.sources(2), pairIndex)
    }.otherwise {
      // Register-indexed pair store: SrcD[47:43], SrcD1, SrcL(base), SrcR(index).
      copySource(uop.sources(0), pairWideData)
      copySource(uop.sources(1), pairDataHigh)
      copySource(uop.sources(2), pairBase)
      copySource(uop.sources(3), pairIndex)
    }
  }

  when(effectiveIllegal) {
    uop.recipe.valid := true.B
    uop.recipe.disposition := OooOpcodeDisposition.Illegal.U
    uop.recipe.recipeKind := OooOpcodeRecipeKind.PreciseTrap.U
    uop.recipe.uopCountMin := 1.U
    uop.recipe.uopCountMax := 1.U
    uop.recipe.complexBreak := false.B
    uop.recipe.lateSplitKind := OooLateSplitKind.None.U
    uop.recipe.fastResolveClass := OooFastResolveClass.PreciseTrapRecord.U
    uop.recipe.sideEffectOwner := OooSideEffectOwner.Commit.U
    uop.recipe.dispatchClass := OooDispatchClass.None.U
    uop.recipe.dispatchWrites := 0.U
    uop.recipe.dispatchDemand.foreach(_ := 0.U)
    uop.recipe.memoryRequestCount := 0.U
    uop.recipe.pSourceCount := 0.U
    uop.recipe.pDestinationCount := 0.U
    uop.recipe.tAllocationCount := 0.U
    uop.recipe.uAllocationCount := 0.U
    uop.plannedChildCount := 1.U
    uop.sources.foreach(_.valid := false.B)
    uop.destinations.foreach(_.valid := false.B)
    uop.immediateValid := false.B
    uop.boundaryTargetValid := false.B
  }

  val pureStartMarker =
    recipe.valid && recipe.recipeKind === OooOpcodeRecipeKind.Boundary.U &&
      recipe.fusionHeadClass === OooFusionClass.StartMarker.U && !effectiveIllegal
  val pureStopMarker =
    recipe.valid && recipe.recipeKind === OooOpcodeRecipeKind.Boundary.U &&
      recipe.fusionTailClass === OooFusionClass.StopMarker.U && !effectiveIllegal
  uop.identity.boundary.start := pureStartMarker
  uop.identity.boundary.stop := pureStopMarker
  uop.identity.boundary.explicit := pureStartMarker || pureStopMarker

  io.out := 0.U.asTypeOf(io.out)
  io.out.emitsUop := io.active && !ctuParent && !complexParent
  io.out.ctuParent := io.active && ctuParent
  io.out.complexParent := io.active && complexParent
  io.out.illegalParent := io.active && effectiveIllegal
  io.out.pureStartMarker := io.active && pureStartMarker
  io.out.pureStopMarker := io.active && pureStopMarker
  io.out.fusionCarrier :=
    io.active && !effectiveIllegal && recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      recipe.recipeKind =/= OooOpcodeRecipeKind.EngineCmd.U
  io.out.uop := uop

  when(io.active && recipe.valid && legacy.io.meta.valid) {
    assert(recipe.opcode === legacy.io.meta.opcode, "OOO and frontend opcode decode must agree")
  }
}

class OooD1DecodeIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooRawInstructionGroup(p)))
  val out = Decoupled(new OooD1DecodedPacket(p))
}

/** Parameterized same-cycle D1 decode, operand normalization, fusion, and compaction. */
class OooD1Decode(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD1DecodeIO(p))
  val laneResults = Wire(Vec(p.instructionDecodeWidth, new OooD1LaneResult(p)))

  for (lane <- 0 until p.instructionDecodeWidth) {
    val decode = Module(new OooD1DecodeLane(p))
    decode.io.active := io.in.valid && io.in.bits.validMask(lane)
    decode.io.in := io.in.bits.entries(lane)
    laneResults(lane) := decode.io.out
  }

  val fuseStart = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val fuseStop = Wire(Vec(p.instructionDecodeWidth, Bool()))
  fuseStart.foreach(_ := false.B)
  fuseStop.foreach(_ := false.B)

  private def adjacentContextMatches(left: Int, right: Int): Bool = {
    val lhs = io.in.bits.entries(left).parent
    val rhs = io.in.bits.entries(right).parent
    lhs.key.peId === rhs.key.peId &&
      lhs.key.stid === rhs.key.stid &&
      lhs.key.epoch === rhs.key.epoch &&
      lhs.prediction.epoch === rhs.prediction.epoch
  }

  for (lane <- 0 until p.instructionDecodeWidth - 1) {
    fuseStart(lane) :=
      laneResults(lane).pureStartMarker && laneResults(lane + 1).fusionCarrier &&
        adjacentContextMatches(lane, lane + 1)
  }
  for (lane <- 1 until p.instructionDecodeWidth) {
    fuseStop(lane) :=
      laneResults(lane).pureStopMarker && laneResults(lane - 1).fusionCarrier &&
        adjacentContextMatches(lane - 1, lane)
  }

  val fusedUops = Wire(Vec(p.instructionDecodeWidth, new OooDecodedUop(p)))
  val emit = Wire(Vec(p.instructionDecodeWidth, Bool()))
  for (lane <- 0 until p.instructionDecodeWidth) {
    fusedUops(lane) := laneResults(lane).uop
    val hasStart = if (lane > 0) fuseStart(lane - 1) else false.B
    val hasStop = if (lane + 1 < p.instructionDecodeWidth) fuseStop(lane + 1) else false.B
    if (lane > 0) {
      when(hasStart) {
        fusedUops(lane).identity.parentCount := Mux(hasStop, 3.U, 2.U)
        fusedUops(lane).identity.parents(0) := io.in.bits.entries(lane - 1).parent
        fusedUops(lane).identity.parents(1) := io.in.bits.entries(lane).parent
        fusedUops(lane).identity.boundary.start := true.B
        fusedUops(lane).identity.boundary.explicit := true.B
      }
    }
    if (lane + 1 < p.instructionDecodeWidth) {
      when(hasStop) {
        fusedUops(lane).identity.parentCount := Mux(hasStart, 3.U, 2.U)
        if (lane > 0) {
          when(hasStart) {
            fusedUops(lane).identity.parents(2) := io.in.bits.entries(lane + 1).parent
          }.otherwise {
            fusedUops(lane).identity.parents(1) := io.in.bits.entries(lane + 1).parent
          }
        } else {
          fusedUops(lane).identity.parents(1) := io.in.bits.entries(lane + 1).parent
        }
        fusedUops(lane).identity.boundary.stop := true.B
        fusedUops(lane).identity.boundary.explicit := true.B
      }
    }
    emit(lane) := laneResults(lane).emitsUop && !fuseStart(lane) && !fuseStop(lane)
  }

  val packet = Wire(new OooD1DecodedPacket(p))
  packet := 0.U.asTypeOf(packet)
  packet.peId := io.in.bits.peId
  packet.stid := io.in.bits.stid
  packet.epoch := io.in.bits.epoch
  packet.endOfStream := io.in.bits.endOfStream
  packet.acceptedInstructionMask := io.in.bits.validMask
  packet.ctuParentMask := VecInit(laneResults.map(_.ctuParent)).asUInt
  packet.complexParentMask := VecInit(laneResults.map(_.complexParent)).asUInt
  packet.illegalParentMask := VecInit(laneResults.map(_.illegalParent)).asUInt
  packet.fusedStartMask := fuseStart.asUInt
  packet.fusedStopMask := fuseStop.asUInt

  val uopCount = PopCount(emit)
  packet.uopMask := ((1.U((p.decodedUopWidth + 1).W) << uopCount) - 1.U)(p.decodedUopWidth - 1, 0)
  for (lane <- 0 until p.instructionDecodeWidth) {
    val outputIndex = if (lane == 0) 0.U else PopCount(emit.take(lane))
    for (slot <- 0 until p.decodedUopWidth) {
      when(emit(lane) && outputIndex === slot.U) {
        packet.uops(slot) := fusedUops(lane)
      }
    }
  }

  packet.demand.instructionRows := PopCount(io.in.bits.validMask)
  packet.demand.decodedUops := uopCount
  packet.demand.robGroups := 0.U
  packet.demand.brobSlots := PopCount(packet.uops.map(uop => uop.valid && uop.identity.boundary.start))
  packet.demand.pcBaseWrites := PopCount(io.in.bits.entries.zipWithIndex.map { case (entry, lane) =>
    io.in.bits.validMask(lane) && entry.parent.prediction.valid && entry.parent.prediction.taken
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
    packet.demand.dispatchWritesByClass(dispatchClass) :=
      packet.uops.map { uop =>
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

  io.out.valid := io.in.valid && (io.in.bits.validMask.orR || io.in.bits.endOfStream)
  io.out.bits := packet
  io.in.ready := Mux(io.in.bits.validMask.orR || io.in.bits.endOfStream, io.out.ready, true.B)

  when(io.in.valid) {
    val denseInput =
      ((1.U((p.instructionDecodeWidth + 1).W) << PopCount(io.in.bits.validMask)) - 1.U)(
        p.instructionDecodeWidth - 1, 0)
    assert(io.in.bits.validMask === denseInput, "OOO D1 accepts only a dense instruction prefix")
    for (lane <- 0 until p.instructionDecodeWidth) {
      when(io.in.bits.validMask(lane)) {
        assert(
          io.in.bits.entries(lane).parent.key.valid &&
            io.in.bits.entries(lane).parent.key.peId === io.in.bits.peId &&
            io.in.bits.entries(lane).parent.key.stid === io.in.bits.stid &&
            io.in.bits.entries(lane).parent.key.epoch === io.in.bits.epoch,
          "every OOO D1 parent must be valid and belong to one PE/STID/epoch")
      }
    }
    assert(uopCount <= p.decodedUopWidth.U, "D1 compaction exceeded decodedUopWidth")
  }
}
