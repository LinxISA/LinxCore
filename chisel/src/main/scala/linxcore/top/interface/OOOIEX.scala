package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class RenamedSource(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)
  val ptag = UInt(InterfaceWidth.index(p.ooo.gprPhysRegs).W)
  val ttag = UInt(InterfaceWidth.index(p.ooo.tPhysRegs).W)
  val utag = UInt(InterfaceWidth.index(p.ooo.uPhysRegs).W)
  val pGeneration = UInt(p.ooo.gprTagGenerationWidth.W)
  val tGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val uGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val tSeqIndex = UInt(InterfaceWidth.index(p.ooo.tuMapQDepthPerStid).W)
  val tSeqGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val uSeqIndex = UInt(InterfaceWidth.index(p.ooo.tuMapQDepthPerStid).W)
  val uSeqGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val ptagValid = Bool()
  val ttagValid = Bool()
  val utagValid = Bool()
  val ready = Bool()
}

class RenamedDestination(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)
  val ptag = UInt(InterfaceWidth.index(p.ooo.gprPhysRegs).W)
  val previousPtag = UInt(InterfaceWidth.index(p.ooo.gprPhysRegs).W)
  val ttag = UInt(InterfaceWidth.index(p.ooo.tPhysRegs).W)
  val previousTtag = UInt(InterfaceWidth.index(p.ooo.tPhysRegs).W)
  val utag = UInt(InterfaceWidth.index(p.ooo.uPhysRegs).W)
  val previousUtag = UInt(InterfaceWidth.index(p.ooo.uPhysRegs).W)
  val pGeneration = UInt(p.ooo.gprTagGenerationWidth.W)
  val previousPtagValid = Bool()
  val tGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val previousTtagValid = Bool()
  val uGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val previousUtagValid = Bool()
  val previousPGeneration = UInt(p.ooo.gprTagGenerationWidth.W)
  val tSeqIndex = UInt(InterfaceWidth.index(p.ooo.tuMapQDepthPerStid).W)
  val tSeqGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val uSeqIndex = UInt(InterfaceWidth.index(p.ooo.tuMapQDepthPerStid).W)
  val uSeqGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val ptagValid = Bool()
  val ttagValid = Bool()
  val utagValid = Bool()
}

class RenamedUop(val p: CoreParams) extends Bundle {
  val decoded = new DecodedUop(p)
  val sources = Vec(p.maxSourceOperands, new RenamedSource(p))
  val destinations =
    Vec(p.maxDestinationOperands, new RenamedDestination(p))
}

class DispatchTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val uop = new RenamedUop(p)
}

class CompletionTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val rob = new RobIdentity(p)
  val destinationValid = Bool()
  val destinationIndex =
    UInt(InterfaceWidth.index(p.maxDestinationOperands).W)
  val value = UInt(p.dataWidth.W)
  val trap = new TrapEvent(p)
}

/** OOO-facing view of the OOO/IEX boundary. */
class OOOIEXIO(val p: CoreParams) extends Bundle {
  val aluDispatch =
    Vec(p.iex.aluPipes, Decoupled(new DispatchTxn(p)))
  val bruDispatch =
    Vec(p.iex.bruPipes, Decoupled(new DispatchTxn(p)))
  val aguDispatch =
    Vec(p.iex.aguPipes, Decoupled(new DispatchTxn(p)))
  val stdDispatch =
    Vec(p.iex.stdPipes, Decoupled(new DispatchTxn(p)))
  val systemDispatch =
    Vec(p.iex.systemMulticycleQueues, Decoupled(new DispatchTxn(p)))
  val cmdDispatch =
    Vec(p.iex.cmdIssueQueues, Decoupled(new DispatchTxn(p)))
  val completion =
    Flipped(Vec(p.widths.issueWidth, Decoupled(new CompletionTxn(p))))
  val recoveryEvent = Flipped(Decoupled(new RecoveryEvent(p)))
  val recovery = new RecoveryTargetIO(p)
}
