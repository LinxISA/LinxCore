package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

object FrontEndOpKind extends ChiselEnum {
  val Encoded64, TemplateUop = Value
}

object OperandKind extends ChiselEnum {
  val None, Gpr, T, U, Immediate = Value
}

object UopClass extends ChiselEnum {
  val Alu, Bru, Agu, Std, System, Cmd, Boundary = Value
}

/** Encoding-independent scalar memory address form emitted by D1. */
object MemoryAddressMode extends ChiselEnum {
  val None, BaseIndex, BaseOffset, PcOffset = Value
}

/** D1-normalized transformation applied to a register index before addition. */
object MemoryIndexMode extends ChiselEnum {
  val Identity, SignExtend32, ZeroExtend32, Negate = Value
}

/** Canonical decoded scalar memory controls. */
class DecodedMemoryControl(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val addressMode = MemoryAddressMode()
  val accessBytes = UInt(4.W)
  val signExtend = Bool()
  val offset = UInt(p.pcWidth.W)
  val indexMode = MemoryIndexMode()
  val indexShift = UInt(5.W)
  val addressSourceMask = UInt(p.maxSourceOperands.W)
  val dataSourceMask = UInt(p.maxSourceOperands.W)
  val writebackValid = Bool()
  val writebackPreIndex = Bool()
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
}

class FrontEndOp(val p: CoreParams) extends Bundle {
  val kind = FrontEndOpKind()
  val parent = new FetchedInstruction(p)
  val templateOrdinal = UInt(8.W)
  val templateCount = UInt(8.W)
  val templateOpcode = UInt(p.opcodeWidth.W)
  val templateImmediate = UInt(p.dataWidth.W)
}

class D1Packet(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.ctuOutputWidth).W)
  val entries = Vec(p.widths.ctuOutputWidth, new FrontEndOp(p))
}

class DecodedSource(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
}

class DecodedDestination(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
}

/** Encoding-independent uop classification produced once by D1/D2.
  *
  * IEX consumes this payload directly when it binds an Issue Queue row.  The
  * classification therefore preserves Execution-pipe capability, splitting,
  * speculation and side-effect information; later stages do not reconstruct
  * those decisions from opcode or queue placement.
  */
class UopClassification(val p: CoreParams) extends Bundle {
  private val uopCountWidth = p.ooo.recipeUopCountWidth
  private val dispatchCountWidth =
    PrefixPacketContract.countWidth(p.widths.dispatchWidth)
  private val sourceCountWidth =
    PrefixPacketContract.countWidth(p.maxSourceOperands)
  private val destinationCountWidth =
    PrefixPacketContract.countWidth(p.maxDestinationOperands)

  val valid = Bool()
  val disposition = UInt(2.W)
  val kind = UInt(4.W)
  val uopCountMin = UInt(uopCountWidth.W)
  val uopCountMax = UInt(uopCountWidth.W)
  val complexBreak = Bool()
  val splitKind = UInt(2.W)
  val fusionHeadClass = UInt(2.W)
  val fusionTailClass = UInt(2.W)
  val fastResolveClass = UInt(3.W)
  val implicitSourceMask = UInt(p.maxSourceOperands.W)
  val implicitDestination = UInt(2.W)
  val sideEffectOwner = UInt(3.W)
  val requiresTargetValidation = Bool()
  val mayTrap = Bool()
  val mayTrapLate = Bool()
  val mayRedirect = Bool()
  val nonspeculative = Bool()
  val pcReadRequired = Bool()
  val pcReadClass = UInt(4.W)
  val dispatchClass = UInt(4.W)
  val dispatchWrites = UInt(dispatchCountWidth.W)
  val dispatchDemand =
    Vec(p.iex.issueQueueClasses, UInt(dispatchCountWidth.W))
  val executionPipeCapability =
    Vec(p.iex.issueQueueClasses, UInt(p.iex.executionPipeKinds.W))
  val memoryRequestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val pSourceCount = UInt(sourceCountWidth.W)
  val pDestinationCount = UInt(destinationCountWidth.W)
  val tAllocationCount = UInt(destinationCountWidth.W)
  val uAllocationCount = UInt(destinationCountWidth.W)
}

class DecodedUop(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val instruction = new FrontEndOp(p)
  val rob = new RobIdentity(p)
  val opcode = UInt(p.opcodeWidth.W)
  val uopClass = UopClass()
  val classification = new UopClassification(p)
  val sources = Vec(p.maxSourceOperands, new DecodedSource(p))
  val destinations =
    Vec(p.maxDestinationOperands, new DecodedDestination(p))
  val memory = new DecodedMemoryControl(p)
  val immediateValid = Bool()
  val immediate = UInt(p.dataWidth.W)
  val earlyComplete = Bool()
  val blockStart = Bool()
  val blockStop = Bool()
  val blockBoundary = Bool()
}
