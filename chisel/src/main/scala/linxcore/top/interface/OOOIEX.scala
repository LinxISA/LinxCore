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

/** Stable program-order allocation metadata; never a physical residency ID. */
class MemoryOrderMeta(val p: CoreParams) extends Bundle {
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val firstLsid = UInt(p.lsidWidth.W)
  val firstLid = UInt(p.lsidWidth.W)
  val firstSid = UInt(p.lsidWidth.W)
  val yostValid = Bool()
  val yostLsid = UInt(p.lsidWidth.W)
  val yostSid = UInt(p.lsidWidth.W)
  val yoldValid = Bool()
  val yoldLsid = UInt(p.lsidWidth.W)
  val yoldLid = UInt(p.lsidWidth.W)
}

class DispatchTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val uop = new RenamedUop(p)
  val memoryOrder = new MemoryOrderMeta(p)
}

/** One atomic store issue request. IEX accepts this beat once, then performs
  * the internal STA/STD fork without exposing partial side effects to OOO.
  */
class StoreDispatchTxn(val p: CoreParams) extends Bundle {
  val sta = new DispatchTxn(p)
  val std = new DispatchTxn(p)
  val aguPipe = UInt(InterfaceWidth.index(p.iex.aguPipes).W)
  val stdPipe = UInt(InterfaceWidth.index(p.iex.stdPipes).W)
}

class RobResolveTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val rob = new RobIdentity(p)
  val destinationValid = Bool()
  val destinationIndex =
    UInt(InterfaceWidth.index(p.maxDestinationOperands).W)
  val value = UInt(p.dataWidth.W)
  val trap = new TrapEvent(p)
}

/** Exact ROB-head authorization for one resident system or CMD side effect. */
class RobNoflushTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val instruction = new InstructionIdentity(p)
  val rob = new RobIdentity(p)
}

/** Exact NFRDY proof: legality is complete and every older effect is drained. */
class RobNoflushReadyTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val instruction = new InstructionIdentity(p)
  val rob = new RobIdentity(p)
}

/** Commit-control-owned no-destination system side effect. */
class SystemIssueTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val instruction = new InstructionIdentity(p)
  val rob = new RobIdentity(p)
  val opcode = UInt(p.opcodeWidth.W)
  val immediate = UInt(p.dataWidth.W)
}

/** Independently backpressured external CMD transaction. */
class CmdIssueTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val instruction = new InstructionIdentity(p)
  val rob = new RobIdentity(p)
  val opcode = UInt(p.opcodeWidth.W)
  val sourceValid = UInt(p.maxSourceOperands.W)
  val sourceValues = Vec(p.maxSourceOperands, UInt(p.dataWidth.W))
}

/** OOO-facing view of the OOO/IEX boundary. */
class OOOIEXIO(val p: CoreParams) extends Bundle {
  val aluDispatch =
    Vec(p.iex.aluPipes, Decoupled(new DispatchTxn(p)))
  val bruDispatch =
    Vec(p.iex.bruPipes, Decoupled(new DispatchTxn(p)))
  val aguDispatch =
    Vec(p.iex.aguPipes, Decoupled(new DispatchTxn(p)))
  val storeDispatch =
    Vec(p.iex.stdPipes, Decoupled(new StoreDispatchTxn(p)))
  val systemDispatch =
    Vec(p.iex.systemMulticycleQueues, Decoupled(new DispatchTxn(p)))
  val cmdDispatch =
    Vec(p.iex.cmdIssueQueues, Decoupled(new DispatchTxn(p)))
  val robNoflushReady = Flipped(Decoupled(new RobNoflushReadyTxn(p)))
  val robNoflush = Decoupled(new RobNoflushTxn(p))
  val robResolve =
    Flipped(Vec(p.widths.issueWidth, Decoupled(new RobResolveTxn(p))))
  val systemIssue = Flipped(Vec(p.iex.systemMulticycleQueues,
    Decoupled(new SystemIssueTxn(p))))
  val recoveryEvent = Flipped(Decoupled(new RecoveryEvent(p)))
  val recovery = new RecoveryTargetIO(p)
}
