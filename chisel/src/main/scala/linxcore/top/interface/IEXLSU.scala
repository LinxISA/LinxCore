package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class LoadIssueTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val pc = UInt(p.pcWidth.W)
  val address = UInt(p.physicalAddressWidth.W)
  val sizeBytes = UInt(4.W)
  val signed = Bool()
  val destination = new RenamedDestination(p)
  val destinationRelativeIndex = UInt(p.archRegWidth.W)
  val youngestStoreValid = Bool()
  val youngestStoreLsid = UInt(p.lsidWidth.W)
  val youngestStoreId = UInt(p.lsidWidth.W)
}

/** Combinational LIQ allocation preview returned beside load-issue ready.
  * The allocation becomes owned only when the matching load issue fires.
  */
class LoadAllocationPreview(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val allocationId = new MemoryTransactionIdentity(p)
}

class LoadLaunchTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val allocationId = new MemoryTransactionIdentity(p)
}

/** Atomic logical STQ reservation published before either STA or STD executes.
  * Physical STQ placement and lease generation remain LSU-private.
  */
class StoreReservationTxn(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val rob = new RobIdentity(p)
  val memoryOrder = new MemoryOrderMeta(p)
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val pair = Bool()
  val sizeBytes = UInt(4.W)
  val aguPipe = UInt(InterfaceWidth.index(p.iex.aguPipes).W)
  val stdPipe = UInt(InterfaceWidth.index(p.iex.stdPipes).W)
}

/** Exact semantic store-beat authorization; no physical STQ index is exposed. */
class StoreCommitAuthorizationTxn(val p: CoreParams) extends Bundle {
  val rob = new RobIdentity(p)
  val transaction = new MemoryTransactionIdentity(p)
  val logicalFirstLsid = UInt(p.lsidWidth.W)
  val logicalFirstStoreId = UInt(p.lsidWidth.W)
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val beat = UInt(InterfaceWidth.index(
    p.maxMemoryRequestsPerInstruction).W)
}

object StoreMemoryClass extends ChiselEnum {
  val NormalCacheable, NormalNonCacheable, Device, Fault = Value
}

/** Independently retained translation/protection classification for one
  * semantic store beat. LSU resolves it against the current exact lease.
  */
class StoreMemoryClassifyTxn(val p: CoreParams) extends Bundle {
  val rob = new RobIdentity(p)
  val transaction = new MemoryTransactionIdentity(p)
  val logicalFirstLsid = UInt(p.lsidWidth.W)
  val logicalFirstStoreId = UInt(p.lsidWidth.W)
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val beat = UInt(InterfaceWidth.index(
    p.maxMemoryRequestsPerInstruction).W)
  val memoryClass = StoreMemoryClass()
  val faultCause = UInt(p.trapCauseWidth.W)
}

/** LIQ attempt transition which returns to address translation. */
class LoadReissueTxn(val p: CoreParams) extends Bundle {
  val allocationId = new MemoryTransactionIdentity(p)
  val currentIdentity = new MemoryIdentity(p)
  val nextIdentity = new MemoryIdentity(p)
  val address = UInt(p.physicalAddressWidth.W)
}

/** LIQ attempt transition which reuses an available physical address. */
class LoadRepickTxn(val p: CoreParams) extends Bundle {
  val currentIdentity = new MemoryIdentity(p)
  val nextIdentity = new MemoryIdentity(p)
}

/** Speculative-dependent cancellation for one exact current attempt. */
class LoadCancelTxn(val p: CoreParams) extends Bundle {
  val currentIdentity = new MemoryIdentity(p)
}

class StoreAddressTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val memoryOrder = new MemoryOrderMeta(p)
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val pair = Bool()
  val address = UInt(p.physicalAddressWidth.W)
  val sizeBytes = UInt(4.W)
}

class StoreDataTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val memoryOrder = new MemoryOrderMeta(p)
  val requestCount = UInt(
    PrefixPacketContract.countWidth(p.maxMemoryRequestsPerInstruction).W)
  val pair = Bool()
  val sizeBytes = UInt(4.W)
  val data = Vec(p.maxMemoryRequestsPerInstruction, UInt(p.dataWidth.W))
  val byteMask = Vec(p.maxMemoryRequestsPerInstruction,
    UInt((p.dataWidth / 8).W))
}

class LoadResultTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val allocationId = new MemoryTransactionIdentity(p)
  val data = UInt(p.dataWidth.W)
  val destination = new RenamedDestination(p)
  val destinationRelativeIndex = UInt(p.archRegWidth.W)
  val trap = new TrapEvent(p)
}

/** IEX-facing view of the IEX/LSU boundary. */
class IEXLSUIO(val p: CoreParams) extends Bundle {
  val storeReservation = Vec(p.lsu.storePipes,
    Decoupled(new StoreReservationTxn(p)))
  val loadAddress =
    Vec(p.lsu.loadPipes, Decoupled(new LoadIssueTxn(p)))
  val loadAllocation = Flipped(Vec(p.lsu.loadPipes,
    Valid(new LoadAllocationPreview(p))))
  val loadLaunch = Flipped(Vec(p.lsu.loadPipes,
    Valid(new LoadLaunchTxn(p))))
  val storeAddress =
    Vec(p.lsu.storePipes, Decoupled(new StoreAddressTxn(p)))
  val storeData =
    Vec(p.lsu.storePipes, Decoupled(new StoreDataTxn(p)))
  val loadResult =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadResultTxn(p))))
  val loadReissue =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadReissueTxn(p))))
  val loadRebindApply =
    Vec(p.lsu.loadPipes, Decoupled(new LoadReissueTxn(p)))
  val loadRepick =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadRepickTxn(p))))
  val loadCancel =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadCancelTxn(p))))
  val recoveryEvent = Flipped(Decoupled(new RecoveryEvent(p)))
}
