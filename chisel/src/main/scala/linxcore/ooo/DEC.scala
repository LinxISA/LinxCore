package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, MuxLookup, PopCount, switch, is}
import linxcore.common.{BoundaryKind, DestinationKind, OperandClass,
  TemplateRowKind}
import linxcore.params.CoreParams
import linxcore.top.interface._

class DECIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new D1Packet(p)))
  val out = Decoupled(new DecodedPacket(p))
}

/** Canonical D1 decoder.
  *
  * This module owns no state. Length-qualified encoded decode reuses the
  * generated OOO recipe table through the validated instruction decode leaf;
  * CTU template rows bypass encoded decode and are converted directly.
  */
class DEC(val p: CoreParams) extends Module {
  val io = IO(new DECIO(p))

  private val decodeParams = OooParams.fromCoreParams(p)
  private val width = p.widths.decodeWidth
  require(width == p.widths.ctuOutputWidth)
  require(width == decodeParams.instructionDecodeWidth)
  require(width <= decodeParams.decodedUopWidth)

  val inputCountValid = io.in.bits.count.orR && io.in.bits.count <= width.U
  val active = Wire(Vec(width, Bool()))
  val template = Wire(Vec(width, Bool()))
  val encoded = Wire(Vec(width, Bool()))
  for (lane <- 0 until width) {
    active(lane) := lane.U < io.in.bits.count
    template(lane) := active(lane) &&
      io.in.bits.entries(lane).kind === FrontEndOpKind.TemplateUop
    encoded(lane) := active(lane) && !template(lane)
  }
  val encodedMask = encoded.asUInt
  val encodedAny = encodedMask.orR

  val decoder = Module(new OooD1Decode(decodeParams, allowSparseInput = true))
  decoder.io.in.valid := io.in.valid && inputCountValid && encodedAny
  decoder.io.in.bits := 0.U.asTypeOf(decoder.io.in.bits)
  decoder.io.in.bits.validMask := encodedMask
  decoder.io.in.bits.peId := io.in.bits.entries(0).parent.identity.peId
  decoder.io.in.bits.stid := io.in.bits.entries(0).parent.identity.stid
  decoder.io.in.bits.epoch := io.in.bits.entries(0).parent.identity.epoch
  for (lane <- 0 until width) {
    val source = io.in.bits.entries(lane).parent
    val target = decoder.io.in.bits.entries(lane)
    target.parent.key.valid := active(lane)
    target.parent.key.peId := source.identity.peId
    target.parent.key.stid := source.identity.stid
    target.parent.key.instructionId := source.identity.instructionId
    target.parent.key.epoch := source.identity.epoch
    target.parent.pc := source.pc
    target.parent.rawInstruction := source.instruction
    target.parent.lengthBytes := source.lengthBytes
    target.parent.traceOwner := active(lane)
    target.parent.preciseExceptionOwner := active(lane)
    target.parent.prediction.valid := source.prediction.valid
    target.parent.prediction.predictionTag := source.prediction.predictionTag
    target.parent.prediction.transactionId := source.prediction.transactionId
    target.parent.prediction.fetchPacketUid := source.identity.instructionId
    target.parent.prediction.fetchSeq := source.identity.instructionId
    target.parent.prediction.requestPc := source.prediction.requestPc
    target.parent.prediction.taken := source.prediction.taken
    target.parent.prediction.branchPc := source.pc
    target.parent.prediction.target := source.prediction.target
    target.parent.prediction.fallthroughPc := source.prediction.fallthroughPc
    target.parent.prediction.kind := BoundaryKind.Fall
    switch(source.prediction.kind) {
      is(PredictionKind.Conditional) {
        target.parent.prediction.kind := BoundaryKind.Cond
      }
      is(PredictionKind.Call) {
        target.parent.prediction.kind := BoundaryKind.Call
      }
      is(PredictionKind.Return) {
        target.parent.prediction.kind := BoundaryKind.Ret
      }
      is(PredictionKind.Direct) {
        target.parent.prediction.kind := BoundaryKind.Direct
      }
      is(PredictionKind.Indirect) {
        target.parent.prediction.kind := BoundaryKind.Ind
      }
    }
    target.parent.prediction.provider := source.prediction.provider
    target.parent.prediction.stage := 0.U
    target.parent.prediction.confidence := source.prediction.confidence
    target.parent.prediction.checkpointId := source.prediction.checkpointId
    target.parent.prediction.epoch := source.prediction.epoch
    target.fetchFaultValid := source.fetchFault
    target.fetchFaultCause := source.fetchFaultCause
  }

  val result = Wire(new DecodedPacket(p))
  result := 0.U.asTypeOf(result)
  val encodedCount = PopCount(decoder.io.out.bits.uopMask)

  private def mapSource(
      target: DecodedSource,
      source: OooDecodedOperand): Unit = {
    target.valid := source.valid
    target.kind := OperandKind.None
    switch(source.operandClass) {
      is(OperandClass.P) { target.kind := OperandKind.Gpr }
      is(OperandClass.T) { target.kind := OperandKind.T }
      is(OperandClass.U) { target.kind := OperandKind.U }
      is(OperandClass.CArg) { target.kind := OperandKind.Immediate }
    }
    target.atag := source.atag
    target.relativeIndex := source.relativeIndex
  }

  private def mapDestination(
      target: DecodedDestination,
      source: OooDecodedDestination): Unit = {
    target.valid := source.valid
    target.kind := OperandKind.None
    switch(source.kind) {
      is(DestinationKind.Gpr) { target.kind := OperandKind.Gpr }
      is(DestinationKind.T) { target.kind := OperandKind.T }
      is(DestinationKind.U) { target.kind := OperandKind.U }
    }
    target.atag := source.atag
    target.relativeIndex := source.relativeIndex
  }

  private def mapClass(dispatchClass: UInt, target: UopClass.Type): Unit = {
    target := UopClass.System
    switch(dispatchClass) {
      is(OooDispatchClass.Alu.U) { target := UopClass.Alu }
      is(OooDispatchClass.Bru.U) { target := UopClass.Bru }
      is(OooDispatchClass.Agu.U) { target := UopClass.Agu }
      is(OooDispatchClass.Std.U) { target := UopClass.Std }
      is(OooDispatchClass.Sys.U) { target := UopClass.System }
      is(OooDispatchClass.Cmd.U) { target := UopClass.Cmd }
      is(OooDispatchClass.Boundary.U) { target := UopClass.Boundary }
    }
  }

  private def mapDecoded(
      output: DecodedLane,
      decodedUop: OooDecodedUop,
      input: FrontEndOp): Unit = {
    output.uop.valid := true.B
    output.uop.instruction := input
    output.uop.opcode := decodedUop.opcode
    mapClass(decodedUop.recipe.dispatchClass, output.uop.uopClass)
    when(decodedUop.recipe.recipeKind === OooOpcodeRecipeKind.Boundary.U) {
      output.uop.uopClass := UopClass.Boundary
    }
    output.uop.classification.valid := decodedUop.recipe.valid
    output.uop.classification.disposition := decodedUop.recipe.disposition
    output.uop.classification.kind := decodedUop.recipe.recipeKind
    output.uop.classification.uopCountMin := decodedUop.recipe.uopCountMin
    output.uop.classification.uopCountMax := decodedUop.recipe.uopCountMax
    output.uop.classification.complexBreak := decodedUop.recipe.complexBreak
    output.uop.classification.splitKind := decodedUop.recipe.lateSplitKind
    output.uop.classification.fusionHeadClass :=
      decodedUop.recipe.fusionHeadClass
    output.uop.classification.fusionTailClass :=
      decodedUop.recipe.fusionTailClass
    output.uop.classification.fastResolveClass :=
      decodedUop.recipe.fastResolveClass
    output.uop.classification.implicitSourceMask :=
      decodedUop.recipe.implicitSourceMask
    output.uop.classification.implicitDestination :=
      decodedUop.recipe.implicitDestination
    output.uop.classification.sideEffectOwner :=
      decodedUop.recipe.sideEffectOwner
    output.uop.classification.requiresTargetValidation :=
      decodedUop.recipe.requiresTargetValidation
    output.uop.classification.mayTrap := decodedUop.recipe.mayTrap
    output.uop.classification.mayTrapLate := decodedUop.recipe.mayTrapLate
    output.uop.classification.mayRedirect := decodedUop.recipe.mayRedirect
    output.uop.classification.nonspeculative :=
      decodedUop.recipe.nonspeculative
    output.uop.classification.pcReadRequired :=
      decodedUop.recipe.pcReadRequired
    output.uop.classification.pcReadClass := decodedUop.recipe.pcReadClass
    output.uop.classification.dispatchClass :=
      decodedUop.recipe.dispatchClass
    output.uop.classification.dispatchWrites :=
      decodedUop.recipe.dispatchWrites
    for (issueClass <- 0 until p.iex.issueQueueClasses) {
      output.uop.classification.dispatchDemand(issueClass) :=
        decodedUop.recipe.dispatchDemand(issueClass)
      output.uop.classification.executionPipeCapability(issueClass) :=
        decodedUop.recipe.dispatchCapabilities(issueClass)
    }
    output.uop.classification.memoryRequestCount :=
      decodedUop.recipe.memoryRequestCount
    output.uop.classification.pSourceCount := decodedUop.recipe.pSourceCount
    output.uop.classification.pDestinationCount :=
      decodedUop.recipe.pDestinationCount
    output.uop.classification.tAllocationCount :=
      decodedUop.recipe.tAllocationCount
    output.uop.classification.uAllocationCount :=
      decodedUop.recipe.uAllocationCount
    for (source <- 0 until p.maxSourceOperands) {
      mapSource(output.uop.sources(source), decodedUop.sources(source))
    }
    for (destination <- 0 until p.maxDestinationOperands) {
      mapDestination(
        output.uop.destinations(destination),
        decodedUop.destinations(destination))
    }
    output.uop.memory.valid := decodedUop.memory.valid
    output.uop.memory.isLoad := decodedUop.memory.isLoad
    output.uop.memory.isStore := decodedUop.memory.isStore
    output.uop.memory.addressMode := MemoryAddressMode.None
    switch(decodedUop.memory.addressMode) {
      is(OooMemoryAddressMode.BaseIndex) {
        output.uop.memory.addressMode := MemoryAddressMode.BaseIndex
      }
      is(OooMemoryAddressMode.BaseOffset) {
        output.uop.memory.addressMode := MemoryAddressMode.BaseOffset
      }
      is(OooMemoryAddressMode.PcOffset) {
        output.uop.memory.addressMode := MemoryAddressMode.PcOffset
      }
    }
    output.uop.memory.accessBytes := decodedUop.memory.accessBytes
    output.uop.memory.signExtend := decodedUop.memory.signExtend
    output.uop.memory.offset := decodedUop.memory.offset
    output.uop.memory.indexMode := MemoryIndexMode.Identity
    switch(decodedUop.memory.indexMode) {
      is(OooMemoryIndexMode.SignExtend32) {
        output.uop.memory.indexMode := MemoryIndexMode.SignExtend32
      }
      is(OooMemoryIndexMode.ZeroExtend32) {
        output.uop.memory.indexMode := MemoryIndexMode.ZeroExtend32
      }
      is(OooMemoryIndexMode.Negate) {
        output.uop.memory.indexMode := MemoryIndexMode.Negate
      }
    }
    output.uop.memory.indexShift := decodedUop.memory.indexShift
    output.uop.memory.addressSourceMask := decodedUop.memory.addressSourceMask
    output.uop.memory.dataSourceMask := decodedUop.memory.dataSourceMask
    output.uop.memory.writebackValid := decodedUop.memory.writebackValid
    output.uop.memory.writebackPreIndex := decodedUop.memory.writebackPreIndex
    output.uop.memory.requestCount := Mux(decodedUop.memory.valid,
      decodedUop.recipe.memoryRequestCount, 0.U)
    output.uop.immediateValid := decodedUop.immediateValid
    output.uop.immediate := decodedUop.immediate
    output.uop.earlyComplete := decodedUop.preciseTrap ||
      output.uop.uopClass === UopClass.Boundary
    output.uop.blockStart := decodedUop.identity.boundary.start
    output.uop.blockStop := decodedUop.identity.boundary.stop
    output.uop.blockBoundary := decodedUop.identity.boundary.explicit
    output.trap.valid := decodedUop.preciseTrap
    output.trap.cause := decodedUop.trapCause
  }

  private def mapTemplate(output: DecodedLane, input: FrontEndOp): Unit = {
    output.uop.valid := true.B
    output.uop.instruction := input
    output.uop.opcode := input.templateOpcode
    output.uop.uopClass := UopClass.System
    switch(input.templateOpcode) {
      is(TemplateRowKind.SP_SUB.asUInt) { output.uop.uopClass := UopClass.Alu }
      is(TemplateRowKind.SP_ADD.asUInt) { output.uop.uopClass := UopClass.Alu }
      is(TemplateRowKind.RESTORE_R10.asUInt) { output.uop.uopClass := UopClass.Alu }
      is(TemplateRowKind.STORE.asUInt) { output.uop.uopClass := UopClass.Std }
      is(TemplateRowKind.LOAD.asUInt) { output.uop.uopClass := UopClass.Agu }
      is(TemplateRowKind.VLOAD.asUInt) { output.uop.uopClass := UopClass.Agu }
      is(TemplateRowKind.VTGT.asUInt) { output.uop.uopClass := UopClass.Bru }
      is(TemplateRowKind.TARGET_PUBLISH.asUInt) {
        output.uop.uopClass := UopClass.Bru
      }
      is(TemplateRowKind.FINAL.asUInt) {
        output.uop.uopClass := UopClass.Boundary
      }
    }
    val templateStore = input.templateOpcode === TemplateRowKind.STORE.asUInt
    val templateLoad = input.templateOpcode === TemplateRowKind.LOAD.asUInt ||
      input.templateOpcode === TemplateRowKind.VLOAD.asUInt
    val templateBranch = output.uop.uopClass === UopClass.Bru
    val templateBoundary = output.uop.uopClass === UopClass.Boundary
    output.uop.classification.valid := true.B
    output.uop.classification.disposition := Mux(templateBoundary,
      OooOpcodeDisposition.FastResolve.U, OooOpcodeDisposition.Dispatch.U)
    output.uop.classification.kind := Mux(templateStore,
      OooOpcodeRecipeKind.ScalarStore.U,
      Mux(templateLoad, OooOpcodeRecipeKind.ScalarLoad.U,
        Mux(templateBoundary, OooOpcodeRecipeKind.Boundary.U,
          OooOpcodeRecipeKind.CtuTemplate.U)))
    output.uop.classification.uopCountMin := 1.U
    output.uop.classification.uopCountMax := Mux(templateStore, 2.U, 1.U)
    output.uop.classification.splitKind := Mux(templateStore,
      OooLateSplitKind.StoreAddressData.U, OooLateSplitKind.None.U)
    output.uop.classification.fastResolveClass := Mux(templateBoundary,
      OooFastResolveClass.BoundaryMetadata.U,
      OooFastResolveClass.None.U)
    output.uop.classification.sideEffectOwner := Mux(
      templateStore || templateLoad, OooSideEffectOwner.Lsu.U,
      Mux(templateBranch, OooSideEffectOwner.Bctrl.U,
        Mux(templateBoundary, OooSideEffectOwner.Ctu.U,
          OooSideEffectOwner.Iex.U)))
    output.uop.classification.mayTrap := input.parent.fetchFault ||
      templateStore || templateLoad
    output.uop.classification.mayTrapLate := templateStore || templateLoad
    output.uop.classification.mayRedirect := templateBranch
    output.uop.classification.nonspeculative :=
      templateBranch || templateBoundary
    output.uop.classification.pcReadRequired := templateBranch
    output.uop.classification.pcReadClass := Mux(templateBranch,
      OooDispatchClass.Bru.U, 0.U)
    output.uop.classification.dispatchClass := MuxLookup(
      output.uop.uopClass.asUInt, OooDispatchClass.None.U)(Seq(
        UopClass.Alu.asUInt -> OooDispatchClass.Alu.U,
        UopClass.Bru.asUInt -> OooDispatchClass.Bru.U,
        UopClass.Agu.asUInt -> OooDispatchClass.Agu.U,
        UopClass.Std.asUInt -> OooDispatchClass.Std.U,
        UopClass.System.asUInt -> OooDispatchClass.Sys.U,
        UopClass.Cmd.asUInt -> OooDispatchClass.Cmd.U,
        UopClass.Boundary.asUInt -> OooDispatchClass.None.U))
    output.uop.classification.dispatchWrites := Mux(
      templateBoundary, 0.U, Mux(templateStore, 2.U, 1.U))
    for (issueClass <- 0 until p.iex.issueQueueClasses) {
      output.uop.classification.dispatchDemand(issueClass) := 0.U
      output.uop.classification.executionPipeCapability(issueClass) := 0.U
    }
    val aluClass = OooDispatchClass.Alu - 1
    val bruClass = OooDispatchClass.Bru - 1
    val aguClass = OooDispatchClass.Agu - 1
    val stdClass = OooDispatchClass.Std - 1
    when(output.uop.uopClass === UopClass.Alu) {
      output.uop.classification.dispatchDemand(aluClass) := 1.U
      output.uop.classification.executionPipeCapability(aluClass) :=
        OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu).U
    }
    when(templateBranch) {
      output.uop.classification.dispatchDemand(bruClass) := 1.U
      output.uop.classification.executionPipeCapability(bruClass) :=
        OooIexDomainCapability.mask(OooIexDomainCapability.Branch).U
    }
    when(templateLoad) {
      output.uop.classification.dispatchDemand(aguClass) := 1.U
      output.uop.classification.executionPipeCapability(aguClass) :=
        OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress).U
    }
    when(templateStore) {
      output.uop.classification.dispatchDemand(aguClass) := 1.U
      output.uop.classification.dispatchDemand(stdClass) := 1.U
      output.uop.classification.executionPipeCapability(aguClass) :=
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress).U
      output.uop.classification.executionPipeCapability(stdClass) :=
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreData).U
    }
    output.uop.classification.memoryRequestCount :=
      Mux(templateStore || templateLoad, 1.U, 0.U)
    output.uop.memory.valid := templateStore || templateLoad
    output.uop.memory.isStore := templateStore
    output.uop.memory.isLoad := templateLoad
    output.uop.memory.addressMode := MemoryAddressMode.BaseOffset
    output.uop.memory.accessBytes := 8.U
    output.uop.memory.offset := input.templateImmediate
    output.uop.memory.addressSourceMask := Mux(templateStore, 2.U, 1.U)
    output.uop.memory.dataSourceMask := Mux(templateStore, 1.U, 0.U)
    output.uop.memory.writebackValid := templateLoad
    output.uop.memory.requestCount := Mux(templateStore || templateLoad, 1.U, 0.U)
    output.uop.immediateValid := true.B
    output.uop.immediate := input.templateImmediate
    output.uop.blockStart := input.templateOpcode === TemplateRowKind.VFORM.asUInt
    output.uop.blockStop := input.templateOpcode === TemplateRowKind.FINAL.asUInt
    output.uop.earlyComplete :=
      input.parent.fetchFault ||
        input.templateOpcode === TemplateRowKind.FINAL.asUInt
    output.uop.blockBoundary :=
      output.uop.blockStart || output.uop.blockStop
    output.trap.valid := input.parent.fetchFault
    output.trap.cause := input.parent.fetchFaultCause
  }

  val decodedMatch = Wire(Vec(width, Vec(width, Bool())))
  val encodedEmit = Wire(Vec(width, Bool()))
  val emit = Wire(Vec(width, Bool()))
  for (inputLane <- 0 until width) {
    for (decodedSlot <- 0 until width) {
      decodedMatch(inputLane)(decodedSlot) := encoded(inputLane) &&
        decoder.io.out.bits.uopMask(decodedSlot) &&
        io.in.bits.entries(inputLane).parent.identity.instructionId ===
          decoder.io.out.bits.uops(decodedSlot).identity.key.primaryParent.instructionId
    }
    encodedEmit(inputLane) := decodedMatch(inputLane).asUInt.orR
    emit(inputLane) := template(inputLane) || encodedEmit(inputLane)
  }
  val outputCount = PopCount(emit)
  result.count := outputCount
  for (inputLane <- 0 until width) {
    val outputIndex = if (inputLane == 0) 0.U else PopCount(emit.take(inputLane))
    for (outputSlot <- 0 until width) {
      when(emit(inputLane) && outputIndex === outputSlot.U) {
        when(template(inputLane)) {
          mapTemplate(result.entries(outputSlot), io.in.bits.entries(inputLane))
        }
        for (decodedSlot <- 0 until width) {
          when(decodedMatch(inputLane)(decodedSlot)) {
            mapDecoded(
              result.entries(outputSlot),
              decoder.io.out.bits.uops(decodedSlot),
              io.in.bits.entries(inputLane))
          }
        }
      }
    }
  }

  val encodedShapeValid = !encodedAny ||
    (decoder.io.out.valid && !decoder.io.out.bits.ctuParentMask.orR &&
      !decoder.io.out.bits.complexParentMask.orR &&
      encodedCount === PopCount(encodedEmit))
  io.out.valid := io.in.valid && inputCountValid && encodedShapeValid &&
    outputCount.orR
  io.out.bits := result
  decoder.io.out.ready := io.out.ready
  io.in.ready := inputCountValid && encodedShapeValid && io.out.ready

  when(io.in.valid && inputCountValid) {
    for (lane <- 0 until width) {
      when(active(lane)) {
        assert(io.in.bits.entries(lane).parent.identity.peId ===
          io.in.bits.entries(0).parent.identity.peId)
        assert(io.in.bits.entries(lane).parent.identity.stid ===
          io.in.bits.entries(0).parent.identity.stid)
        assert(io.in.bits.entries(lane).parent.identity.epoch ===
          io.in.bits.entries(0).parent.identity.epoch)
      }
    }
  }
}
