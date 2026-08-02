package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class LoadIssueTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val address = UInt(p.physicalAddressWidth.W)
  val sizeBytes = UInt(4.W)
  val signed = Bool()
  val destination = new RenamedDestination(p)
}

/** LIQ attempt transition which returns to address translation. */
class LoadReissueTxn(val p: CoreParams) extends Bundle {
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
  val address = UInt(p.physicalAddressWidth.W)
  val sizeBytes = UInt(4.W)
}

class StoreDataTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val data = UInt(p.dataWidth.W)
  val byteMask = UInt((p.dataWidth / 8).W)
}

class LoadResultTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val data = UInt(p.dataWidth.W)
  val destination = new RenamedDestination(p)
  val trap = new TrapEvent(p)
}

/** IEX-facing view of the IEX/LSU boundary. */
class IEXLSUIO(val p: CoreParams) extends Bundle {
  val loadAddress =
    Vec(p.lsu.loadPipes, Decoupled(new LoadIssueTxn(p)))
  val storeAddress =
    Vec(p.lsu.storePipes, Decoupled(new StoreAddressTxn(p)))
  val storeData =
    Vec(p.lsu.storePipes, Decoupled(new StoreDataTxn(p)))
  val loadResult =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadResultTxn(p))))
  val loadReissue =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadReissueTxn(p))))
  val loadRepick =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadRepickTxn(p))))
  val loadCancel =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadCancelTxn(p))))
  val recoveryEvent = Flipped(Decoupled(new RecoveryEvent(p)))
}
