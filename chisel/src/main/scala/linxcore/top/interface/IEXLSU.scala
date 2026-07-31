package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class LoadRequestTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val address = UInt(p.physicalAddressWidth.W)
  val sizeBytes = UInt(4.W)
  val signed = Bool()
  val destination = new RenamedDestination(p)
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
    Vec(p.lsu.loadPipes, Decoupled(new LoadRequestTxn(p)))
  val storeAddress =
    Vec(p.lsu.storePipes, Decoupled(new StoreAddressTxn(p)))
  val storeData =
    Vec(p.lsu.storePipes, Decoupled(new StoreDataTxn(p)))
  val loadResult =
    Flipped(Vec(p.lsu.loadPipes, Decoupled(new LoadResultTxn(p))))
  val recoveryEvent = Flipped(Decoupled(new RecoveryEvent(p)))
}
