package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, switch, is}
import linxcore.common.{BoundaryKind, DestinationKind, OperandClass,
  TemplateRowKind}
import linxcore.params.CoreParams
import linxcore.top.interface._

/** Typed precise-trap intent attached to the canonical D1 decode result. */
class DecodeTrapIntent(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val cause = UInt(p.trapCauseWidth.W)
}

/** One normalized D1 result. Encoded instructions and CTU template children
  * deliberately share this record.
  */
class DecodedLane(val p: CoreParams) extends Bundle {
  val uop = new DecodedUop(p)
  val trap = new DecodeTrapIntent(p)
}

class DecodedPacket(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.decodeWidth).W)
  val entries = Vec(p.widths.decodeWidth, new DecodedLane(p))
}

class DECIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new D1Packet(p)))
  val out = Decoupled(new DecodedPacket(p))
}

/** Canonical D1 decoder.
  *
  * This module owns no state. Length-qualified encoded decode reuses the
  * generated OOO recipe table through the validated legacy decode leaf;
  * CTU template rows bypass encoded decode and are converted directly.
  */
class DEC(val p: CoreParams) extends Module {
  val io = IO(new DECIO(p))

  private val legacyP = OooParams.fromCoreParams(p)
  private val width = p.widths.decodeWidth
  require(width == p.widths.ctuOutputWidth)
  require(width == legacyP.instructionDecodeWidth)
  require(width <= legacyP.decodedUopWidth)

  private def denseMask(count: UInt): UInt =
    ((1.U((width + 1).W) << count) - 1.U)(width - 1, 0)

  val inputCountValid = io.in.bits.count.orR && io.in.bits.count <= width.U
  val active = Wire(Vec(width, Bool()))
  val template = Wire(Vec(width, Bool()))
  for (lane <- 0 until width) {
    active(lane) := lane.U < io.in.bits.count
    template(lane) := active(lane) &&
      io.in.bits.entries(lane).kind === FrontEndOpKind.TemplateUop
  }
  val allTemplate = inputCountValid &&
    (0 until width).map(lane => !active(lane) || template(lane)).reduce(_ && _)
  val allEncoded = inputCountValid &&
    (0 until width).map(lane => !active(lane) || !template(lane)).reduce(_ && _)
  val shapeValid = allTemplate || allEncoded

  val legacy = Module(new OooD1Decode(legacyP))
  legacy.io.in.valid := io.in.valid && allEncoded
  legacy.io.in.bits := 0.U.asTypeOf(legacy.io.in.bits)
  legacy.io.in.bits.validMask := denseMask(io.in.bits.count)
  legacy.io.in.bits.peId := io.in.bits.entries(0).parent.identity.peId
  legacy.io.in.bits.stid := io.in.bits.entries(0).parent.identity.stid
  legacy.io.in.bits.epoch := io.in.bits.entries(0).parent.identity.epoch
  for (lane <- 0 until width) {
    val source = io.in.bits.entries(lane).parent
    val target = legacy.io.in.bits.entries(lane)
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
  val encodedCount = PopCount(legacy.io.out.bits.uopMask)
  result.count := Mux(allTemplate, io.in.bits.count, encodedCount)

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

  for (slot <- 0 until width) {
    val output = result.entries(slot)
    val legacyUop = legacy.io.out.bits.uops(slot)
    when(allEncoded) {
      output.uop.valid := legacy.io.out.bits.uopMask(slot)
      output.uop.opcode := legacyUop.opcode
      mapClass(legacyUop.recipe.dispatchClass, output.uop.uopClass)
      for (source <- 0 until p.maxSourceOperands) {
        mapSource(output.uop.sources(source), legacyUop.sources(source))
      }
      for (destination <- 0 until p.maxDestinationOperands) {
        mapDestination(
          output.uop.destinations(destination),
          legacyUop.destinations(destination))
      }
      output.uop.immediateValid := legacyUop.immediateValid
      output.uop.immediate := legacyUop.immediate
      output.uop.earlyComplete := legacyUop.preciseTrap ||
        legacyUop.recipe.dispatchClass === OooDispatchClass.Boundary.U
      output.uop.blockBoundary := legacyUop.identity.boundary.explicit
      output.trap.valid := legacyUop.preciseTrap
      output.trap.cause := legacyUop.trapCause

      // Recover the exact canonical parent rather than narrowing and then
      // reconstructing identity/prediction fields through a legacy bundle.
      for (inputLane <- 0 until width) {
        when(active(inputLane) &&
          io.in.bits.entries(inputLane).parent.identity.instructionId ===
            legacyUop.identity.key.primaryParent.instructionId) {
          output.uop.instruction := io.in.bits.entries(inputLane)
        }
      }
    }

    when(allTemplate && active(slot)) {
      val input = io.in.bits.entries(slot)
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
      output.uop.immediateValid := true.B
      output.uop.immediate := input.templateImmediate
      output.uop.earlyComplete :=
        input.parent.fetchFault ||
          input.templateOpcode === TemplateRowKind.FINAL.asUInt
      output.uop.blockBoundary :=
        input.templateOpcode === TemplateRowKind.FINAL.asUInt
      output.trap.valid := input.parent.fetchFault
      output.trap.cause := input.parent.fetchFaultCause
    }
  }

  val encodedHasOutput = legacy.io.out.valid && encodedCount.orR
  io.out.valid := io.in.valid && shapeValid &&
    Mux(allTemplate, inputCountValid, encodedHasOutput)
  io.out.bits := result
  legacy.io.out.ready := io.out.ready && allEncoded
  io.in.ready := shapeValid &&
    Mux(allTemplate, io.out.ready, encodedHasOutput && io.out.ready)

  when(io.in.valid && shapeValid) {
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
