package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, is, MuxLookup, PopCount, switch,
  Valid}

/** Retained scalar-load address request before LSU acceptance. */
class OooIexAguLoadRequest(val p: OooParams = OooParams()) extends Bundle {
  val execute = new OooIexExecuteTransaction(p)
  // Canonical LSU prediction/replay state is PC-indexed even for base/index
  // addressing.  The issue fabric therefore reads the architectural parent
  // PC for every scalar load, not only for PC-relative address generation.
  val pcValid = Bool()
  val pc = UInt(p.pcWidth.W)
  val address = UInt(p.pcWidth.W)
  val accessBytes = UInt(4.W)
  val signExtend = Bool()
  val destination = new OooIexDestinationState(p)
}

class OooIexAguReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val classExact = Bool()
  val recipeExact = Bool()
  val memoryExact = Bool()
  val operandShapeExact = Bool()
  val supportedOpcode = Bool()
  val incomingKilled = Bool()
}

class OooIexAguPipelineIO(val p: OooParams = OooParams()) extends Bundle {
  val e1 = Flipped(Decoupled(new OooIexExecuteTransaction(p)))
  val request = Decoupled(new OooIexAguLoadRequest(p))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val rejected = Valid(new OooIexAguReject(p))
  val killedRequest = Valid(new OooIexAguLoadRequest(p))
  val occupied = Output(Bool())
}

object OooIexAguPipeline {
  val SupportedOpcodes: Seq[Int] = OooOpcodeRecipeTable.Rules
    .filter(rule =>
      rule.disposition == OooOpcodeDisposition.Dispatch &&
      rule.recipeKind == OooOpcodeRecipeKind.ScalarLoad &&
      rule.dispatchClass == OooDispatchClass.Agu &&
      rule.sideEffectOwner == OooSideEffectOwner.Lsu &&
      rule.memoryRequestCount == 1)
    .map(_.opcode).distinct
}

/** Typed scalar-load AGU with one retained request boundary.
  *
  * D1 owns encoding normalization. E1 consumes only `OooMemoryControl`,
  * computes one byte address, and retains the complete request until an LSU
  * accepts it. This module does not allocate a load generation, access memory,
  * publish speculative wakeup, write RF, or complete ROB.
  */
class OooIexAguPipeline(val p: OooParams = OooParams()) extends Module {
  import OooIexAguPipeline._

  val io = IO(new OooIexAguPipelineIO(p))

  private def opcode(value: Int): UInt = value.U(p.opcodeWidth.W)

  private def supported(op: UInt): Bool =
    SupportedOpcodes.map(value => op === opcode(value)).reduce(_ || _)

  private def killedByRecovery(execute: OooIexExecuteTransaction): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits,
        execute.i2.row.member)

  private def canceledByLoad(execute: OooIexExecuteTransaction): Bool =
    io.loadCancel.map { cancel =>
      val sourceMatch = execute.i2.row.sources.map { source =>
        source.valid && source.specReady && source.load.valid &&
          cancel.bits.load.valid &&
          source.load.asUInt === cancel.bits.load.asUInt
      }.reduce(_ || _)
      cancel.valid && cancel.bits.stid === execute.i2.row.stid &&
        cancel.bits.epoch === execute.i2.row.epoch && sourceMatch
    }.reduce(_ || _)

  private def transformedIndex(execute: OooIexExecuteTransaction): UInt = {
    val memory = execute.i2.row.memory
    val raw = execute.i2.sourceData(1)
    val transformed = WireDefault(raw)
    switch(memory.indexMode) {
      is(OooMemoryIndexMode.SignExtend32) {
        transformed := Cat(Fill(p.pcWidth - 32, raw(31)), raw(31, 0))
      }
      is(OooMemoryIndexMode.ZeroExtend32) {
        transformed := raw(31, 0).pad(p.pcWidth)
      }
      is(OooMemoryIndexMode.Negate) {
        transformed := 0.U(p.pcWidth.W) - raw
      }
    }
    (transformed << memory.indexShift)(p.pcWidth - 1, 0)
  }

  private def makeRequest(
      execute: OooIexExecuteTransaction): OooIexAguLoadRequest = {
    val result = Wire(new OooIexAguLoadRequest(p))
    result := 0.U.asTypeOf(result)
    result.execute := execute
    result.pcValid := execute.i2.pcValid
    result.pc := execute.i2.pc
    val memory = execute.i2.row.memory
    result.address := MuxLookup(memory.addressMode.asUInt, 0.U)(Seq(
      OooMemoryAddressMode.BaseIndex.asUInt ->
        (execute.i2.sourceData(0) + transformedIndex(execute)),
      OooMemoryAddressMode.BaseOffset.asUInt ->
        (execute.i2.sourceData(0) + memory.offset),
      OooMemoryAddressMode.PcOffset.asUInt ->
        (execute.i2.pc + memory.offset)))
    result.accessBytes := memory.accessBytes
    result.signExtend := memory.signExtend
    result.destination := execute.i2.row.destinations(0)
    result
  }

  val incoming = io.e1.bits
  val row = incoming.i2.row
  val memory = row.memory
  val classExact = incoming.ownerClass === OooUopClass.Agu &&
    row.reservation.uopClass === OooUopClass.Agu
  val recipeExact = row.recipe.valid &&
    row.recipe.opcode === row.opcode &&
    row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
    row.recipe.recipeKind === OooOpcodeRecipeKind.ScalarLoad.U &&
    row.recipe.dispatchClass === OooDispatchClass.Agu.U &&
    row.recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U &&
    row.recipe.memoryRequestCount === 1.U
  val accessBytesExact = memory.accessBytes === 1.U ||
    memory.accessBytes === 2.U || memory.accessBytes === 4.U ||
    memory.accessBytes === 8.U
  val addressModeExact = memory.addressMode === OooMemoryAddressMode.BaseIndex ||
    memory.addressMode === OooMemoryAddressMode.BaseOffset ||
    memory.addressMode === OooMemoryAddressMode.PcOffset
  val memoryExact = memory.valid && memory.isLoad && !memory.isStore &&
    accessBytesExact && addressModeExact && memory.dataSourceMask === 0.U
  val logicalSourceMask = VecInit(row.sources.map(_.valid)).asUInt
  val destinationMask = VecInit(row.destinations.map(_.valid)).asUInt
  val pcRequired = memory.addressMode === OooMemoryAddressMode.PcOffset
  val immediateRequired = memory.addressMode === OooMemoryAddressMode.BaseOffset ||
    pcRequired
  val operandShapeExact = row.valid && row.member.group.valid &&
    row.member.bid.valid && row.reservation.valid &&
    incoming.i2.pcValid &&
    incoming.i2.sourceMask === logicalSourceMask &&
    incoming.i2.sourceMask === memory.addressSourceMask &&
    PopCount(incoming.i2.sourceMask) === row.recipe.pSourceCount &&
    PopCount(destinationMask) === 1.U &&
    row.recipe.pDestinationCount === 1.U &&
    row.recipe.pcReadRequired === pcRequired &&
    (!pcRequired || incoming.i2.pcValid) &&
    (!immediateRequired || row.immediateValid)
  val supportedOpcode = supported(row.opcode)
  val incomingKilled = killedByRecovery(incoming) || canceledByLoad(incoming)
  val incomingExact = classExact && recipeExact && memoryExact &&
    operandShapeExact && supportedOpcode

  val requestValid = RegInit(false.B)
  val request = Reg(new OooIexAguLoadRequest(p))
  val requestKilled = requestValid &&
    (killedByRecovery(request.execute) || canceledByLoad(request.execute))
  val canAccept = !requestValid || requestKilled || io.request.ready

  io.e1.ready := canAccept && incomingExact && !incomingKilled
  io.request.valid := requestValid && !requestKilled
  io.request.bits := request
  io.rejected.valid := io.e1.valid && (!incomingExact || incomingKilled)
  io.rejected.bits.member := row.member
  io.rejected.bits.opcode := row.opcode
  io.rejected.bits.classExact := classExact
  io.rejected.bits.recipeExact := recipeExact
  io.rejected.bits.memoryExact := memoryExact
  io.rejected.bits.operandShapeExact := operandShapeExact
  io.rejected.bits.supportedOpcode := supportedOpcode
  io.rejected.bits.incomingKilled := incomingKilled
  io.killedRequest.valid := requestKilled
  io.killedRequest.bits := request
  io.occupied := requestValid && !requestKilled

  when(requestKilled || io.request.fire) {
    requestValid := false.B
  }
  when(io.e1.fire) {
    requestValid := true.B
    request := makeRequest(incoming)
  }
}
