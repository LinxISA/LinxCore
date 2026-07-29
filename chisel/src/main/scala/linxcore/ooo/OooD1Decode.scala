package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, is, PopCount, switch}
import linxcore.common.{
  BranchPredictionSidecar,
  DestinationKind,
  InterfaceParams,
  OperandClass
}
import linxcore.frontend.{FrontendInstructionDecodeLane,
  FrontendOpcodeDecodeTable, FrontendRegAliasClassify}

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

  private def opcodeIs(values: Int*): Bool =
    values.map(value => recipe.opcode === value.U(p.opcodeWidth.W))
      .reduce(_ || _)

  val scalarLoad = recipe.valid &&
    recipe.recipeKind === OooOpcodeRecipeKind.ScalarLoad.U &&
    recipe.dispatchClass === OooDispatchClass.Agu.U &&
    recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U
  val scalarStore = recipe.valid &&
    recipe.recipeKind === OooOpcodeRecipeKind.ScalarStore.U &&
    recipe.lateSplitKind === OooLateSplitKind.StoreAddressData.U &&
    recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U
  val loadPcr = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LB_PCR,
    FrontendOpcodeDecodeTable.OP_LBU_PCR,
    FrontendOpcodeDecodeTable.OP_LD_PCR,
    FrontendOpcodeDecodeTable.OP_LH_PCR,
    FrontendOpcodeDecodeTable.OP_LHU_PCR,
    FrontendOpcodeDecodeTable.OP_LW_PCR,
    FrontendOpcodeDecodeTable.OP_LWU_PCR)
  val loadRegister = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LB,
    FrontendOpcodeDecodeTable.OP_LBU,
    FrontendOpcodeDecodeTable.OP_LD,
    FrontendOpcodeDecodeTable.OP_LH,
    FrontendOpcodeDecodeTable.OP_LHU,
    FrontendOpcodeDecodeTable.OP_LW,
    FrontendOpcodeDecodeTable.OP_LWU)
  val storePcr = opcodeIs(
    FrontendOpcodeDecodeTable.OP_SB_PCR,
    FrontendOpcodeDecodeTable.OP_SD_PCR,
    FrontendOpcodeDecodeTable.OP_SH_PCR,
    FrontendOpcodeDecodeTable.OP_SW_PCR)
  val storeRegister = opcodeIs(
    FrontendOpcodeDecodeTable.OP_SB,
    FrontendOpcodeDecodeTable.OP_SD,
    FrontendOpcodeDecodeTable.OP_SD_U,
    FrontendOpcodeDecodeTable.OP_SH,
    FrontendOpcodeDecodeTable.OP_SH_U,
    FrontendOpcodeDecodeTable.OP_SW,
    FrontendOpcodeDecodeTable.OP_SW_U)
  val pairStoreRegister = opcodeIs(
    FrontendOpcodeDecodeTable.OP_HL_SBP,
    FrontendOpcodeDecodeTable.OP_HL_SDP,
    FrontendOpcodeDecodeTable.OP_HL_SDP_U,
    FrontendOpcodeDecodeTable.OP_HL_SHP,
    FrontendOpcodeDecodeTable.OP_HL_SHP_U,
    FrontendOpcodeDecodeTable.OP_HL_SWP,
    FrontendOpcodeDecodeTable.OP_HL_SWP_U)
  val byteLoad = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LB,
    FrontendOpcodeDecodeTable.OP_LBI,
    FrontendOpcodeDecodeTable.OP_LB_PCR,
    FrontendOpcodeDecodeTable.OP_LBU,
    FrontendOpcodeDecodeTable.OP_LBUI,
    FrontendOpcodeDecodeTable.OP_LBU_PCR)
  val byteStore = opcodeIs(
    FrontendOpcodeDecodeTable.OP_SB,
    FrontendOpcodeDecodeTable.OP_SBI,
    FrontendOpcodeDecodeTable.OP_SB_PCR,
    FrontendOpcodeDecodeTable.OP_HL_SBIP,
    FrontendOpcodeDecodeTable.OP_HL_SBP)
  val halfLoad = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LH,
    FrontendOpcodeDecodeTable.OP_LHI,
    FrontendOpcodeDecodeTable.OP_LHI_U,
    FrontendOpcodeDecodeTable.OP_LH_PCR,
    FrontendOpcodeDecodeTable.OP_LHU,
    FrontendOpcodeDecodeTable.OP_LHUI,
    FrontendOpcodeDecodeTable.OP_LHUI_U,
    FrontendOpcodeDecodeTable.OP_LHU_PCR)
  val halfStore = opcodeIs(
    FrontendOpcodeDecodeTable.OP_SH,
    FrontendOpcodeDecodeTable.OP_SH_U,
    FrontendOpcodeDecodeTable.OP_SHI,
    FrontendOpcodeDecodeTable.OP_SHI_U,
    FrontendOpcodeDecodeTable.OP_SH_PCR,
    FrontendOpcodeDecodeTable.OP_HL_SHIP,
    FrontendOpcodeDecodeTable.OP_HL_SHIP_U,
    FrontendOpcodeDecodeTable.OP_HL_SHP,
    FrontendOpcodeDecodeTable.OP_HL_SHP_U)
  val wordLoad = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LW,
    FrontendOpcodeDecodeTable.OP_LWI,
    FrontendOpcodeDecodeTable.OP_LWI_U,
    FrontendOpcodeDecodeTable.OP_LW_PCR,
    FrontendOpcodeDecodeTable.OP_LWU,
    FrontendOpcodeDecodeTable.OP_LWUI,
    FrontendOpcodeDecodeTable.OP_LWUI_U,
    FrontendOpcodeDecodeTable.OP_LWU_PCR)
  val wordStore = opcodeIs(
    FrontendOpcodeDecodeTable.OP_SW,
    FrontendOpcodeDecodeTable.OP_SW_U,
    FrontendOpcodeDecodeTable.OP_SWI,
    FrontendOpcodeDecodeTable.OP_SWI_U,
    FrontendOpcodeDecodeTable.OP_SW_PCR,
    FrontendOpcodeDecodeTable.OP_HL_SWIP,
    FrontendOpcodeDecodeTable.OP_HL_SWIP_U,
    FrontendOpcodeDecodeTable.OP_HL_SWP,
    FrontendOpcodeDecodeTable.OP_HL_SWP_U)
  val unsignedLoad = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LBU,
    FrontendOpcodeDecodeTable.OP_LBUI,
    FrontendOpcodeDecodeTable.OP_LBU_PCR,
    FrontendOpcodeDecodeTable.OP_LHU,
    FrontendOpcodeDecodeTable.OP_LHUI,
    FrontendOpcodeDecodeTable.OP_LHUI_U,
    FrontendOpcodeDecodeTable.OP_LHU_PCR,
    FrontendOpcodeDecodeTable.OP_LWU,
    FrontendOpcodeDecodeTable.OP_LWUI,
    FrontendOpcodeDecodeTable.OP_LWUI_U,
    FrontendOpcodeDecodeTable.OP_LWU_PCR)
  val signedNarrowLoad = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LB,
    FrontendOpcodeDecodeTable.OP_LBI,
    FrontendOpcodeDecodeTable.OP_LB_PCR,
    FrontendOpcodeDecodeTable.OP_LH,
    FrontendOpcodeDecodeTable.OP_LHI,
    FrontendOpcodeDecodeTable.OP_LHI_U,
    FrontendOpcodeDecodeTable.OP_LH_PCR,
    FrontendOpcodeDecodeTable.OP_LW,
    FrontendOpcodeDecodeTable.OP_LWI,
    FrontendOpcodeDecodeTable.OP_LWI_U,
    FrontendOpcodeDecodeTable.OP_LW_PCR)
  val unscaledLoad = opcodeIs(
    FrontendOpcodeDecodeTable.OP_LHI_U,
    FrontendOpcodeDecodeTable.OP_LHUI_U,
    FrontendOpcodeDecodeTable.OP_LWI_U,
    FrontendOpcodeDecodeTable.OP_LWUI_U,
    FrontendOpcodeDecodeTable.OP_LDI_U)
  val unscaledStore = opcodeIs(
    FrontendOpcodeDecodeTable.OP_SD_U,
    FrontendOpcodeDecodeTable.OP_SH_U,
    FrontendOpcodeDecodeTable.OP_SW_U,
    FrontendOpcodeDecodeTable.OP_SDI_U,
    FrontendOpcodeDecodeTable.OP_SHI_U,
    FrontendOpcodeDecodeTable.OP_SWI_U,
    FrontendOpcodeDecodeTable.OP_HL_SDI_U,
    FrontendOpcodeDecodeTable.OP_HL_SDI_UPO,
    FrontendOpcodeDecodeTable.OP_HL_SDI_UPR,
    FrontendOpcodeDecodeTable.OP_HL_SDIP_U,
    FrontendOpcodeDecodeTable.OP_HL_SDP_U,
    FrontendOpcodeDecodeTable.OP_HL_SHIP_U,
    FrontendOpcodeDecodeTable.OP_HL_SHP_U,
    FrontendOpcodeDecodeTable.OP_HL_SWIP_U,
    FrontendOpcodeDecodeTable.OP_HL_SWP_U)
  val storeWriteback = opcodeIs(
    FrontendOpcodeDecodeTable.OP_HL_SDI_PO,
    FrontendOpcodeDecodeTable.OP_HL_SDI_PR,
    FrontendOpcodeDecodeTable.OP_HL_SDI_UPO,
    FrontendOpcodeDecodeTable.OP_HL_SDI_UPR)
  val storeWritebackPreIndex = opcodeIs(
    FrontendOpcodeDecodeTable.OP_HL_SDI_PR,
    FrontendOpcodeDecodeTable.OP_HL_SDI_UPR)
  val memoryPcr = loadPcr || storePcr
  val memoryRegister = loadRegister || storeRegister || pairStoreRegister
  val memoryUnscaled = unscaledLoad || unscaledStore
  val byteMemory = byteLoad || byteStore
  val halfMemory = halfLoad || halfStore
  val wordMemory = wordLoad || wordStore
  val accessBytes = Mux(byteMemory, 1.U, Mux(halfMemory, 2.U,
    Mux(wordMemory, 4.U, 8.U)))
  val accessShift = Mux(byteMemory, 0.U, Mux(halfMemory, 1.U,
    Mux(wordMemory, 2.U, 3.U)))
  val storePcrOffset = Cat(
    Fill(p.pcWidth - 17, parent.rawInstruction(11)),
    parent.rawInstruction(11, 7),
    parent.rawInstruction(31, 20))
  when(storePcr) {
    uop.immediateValid := true.B
    uop.immediate := storePcrOffset
  }
  val decodedOffset = Mux(storePcr, storePcrOffset, legacy.io.out.imm)
  val normalizedOffset = Mux(memoryPcr || memoryUnscaled,
    decodedOffset,
    (decodedOffset << accessShift)(p.pcWidth - 1, 0))

  uop.memory.valid := scalarLoad || scalarStore || pairStore
  uop.memory.isLoad := scalarLoad
  uop.memory.isStore := scalarStore || pairStore
  uop.memory.addressMode := Mux(memoryPcr, OooMemoryAddressMode.PcOffset,
    Mux(memoryRegister, OooMemoryAddressMode.BaseIndex,
      OooMemoryAddressMode.BaseOffset))
  uop.memory.accessBytes := accessBytes
  uop.memory.signExtend := scalarLoad && signedNarrowLoad && !unsignedLoad
  uop.memory.offset := normalizedOffset
  uop.memory.indexMode := OooMemoryIndexMode.Identity
  val indexModeBits = Mux(pairStoreRegister,
    parent.rawInstruction(42, 41), parent.rawInstruction(26, 25))
  switch(indexModeBits) {
    is(0.U) { uop.memory.indexMode := OooMemoryIndexMode.SignExtend32 }
    is(1.U) { uop.memory.indexMode := OooMemoryIndexMode.ZeroExtend32 }
    is(2.U) { uop.memory.indexMode := OooMemoryIndexMode.Negate }
  }
  uop.memory.indexShift := Mux(pairStoreRegister,
    parent.rawInstruction(47, 43),
    Mux(loadRegister, parent.rawInstruction(31, 27),
      Mux(storeRegister, accessShift, 0.U)))
  uop.memory.addressSourceMask := Mux(memoryPcr, 0.U,
    Mux(pairStoreRegister, 12.U,
      Mux(storeRegister, 6.U,
        Mux(pairStore, 4.U,
          Mux(scalarStore, 2.U,
            Mux(loadRegister, 3.U, 1.U))))))
  uop.memory.dataSourceMask := Mux(pairStore, 3.U,
    Mux(scalarStore, 1.U, 0.U))
  uop.memory.writebackValid := storeWriteback
  uop.memory.writebackPreIndex := storeWritebackPreIndex
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

  val decodedSourceMask = VecInit(uop.sources.map(_.valid)).asUInt
  when(io.active && (scalarStore || pairStore) && !effectiveIllegal) {
    assert(
      (uop.memory.addressSourceMask & uop.memory.dataSourceMask) === 0.U,
      "store address and data source projections must be disjoint")
    assert(
      (uop.memory.addressSourceMask | uop.memory.dataSourceMask) === decodedSourceMask,
      "store child projections must cover every decoded source exactly once")
    assert(
      recipe.pSourceCount === PopCount(decodedSourceMask),
      "store recipe source count must match normalized decoded operands")
    assert(
      uop.plannedChildCount === 2.U,
      "store address/data split must reserve exactly two execution children")
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
    uop.memory.valid := false.B
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
  for (lane <- 0 until p.instructionDecodeWidth) {
    when(laneResults(lane).ctuParent) {
      packet.ctuParents(lane) := io.in.bits.entries(lane)
    }
    when(laneResults(lane).complexParent) {
      packet.complexParents(lane) := io.in.bits.entries(lane)
    }
  }

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
