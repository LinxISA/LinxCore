package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

object LSUMemoryFaultCause extends ChiselEnum {
  val Alignment, Access = Value
}

class LSUMemoryFaultTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryIdentity(p)
  val address = UInt(p.physicalAddressWidth.W)
  val write = Bool()
  val cause = LSUMemoryFaultCause()
}

object LSUMaintenanceCommand extends ChiselEnum {
  val Fence, InvalidateTranslation, InvalidateLine, InvalidateAll = Value
}

class LSUMaintenanceTxn(val p: CoreParams) extends Bundle {
  val command = LSUMaintenanceCommand()
  val address = UInt(p.physicalAddressWidth.W)
}

class LSUMaintenanceResult(val p: CoreParams) extends Bundle {
  val command = LSUMaintenanceCommand()
  val address = UInt(p.physicalAddressWidth.W)
  val success = Bool()
}

class LSUIO(val p: CoreParams) extends Bundle {
  val iex = Flipped(new IEXLSUIO(p))
  val storeCommit = Flipped(Decoupled(
    new StoreCommitAuthorizationTxn(p)))
  val storeClassify = Flipped(Decoupled(
    new StoreMemoryClassifyTxn(p)))
  val loadReissueRequest = Flipped(Decoupled(new LoadReplayRequestTxn(p)))
  val memoryRequest = Vec(p.lsu.loadPipes + p.lsu.storePipes,
    Decoupled(new MemoryRequestTxn(p)))
  val memoryResponse = Flipped(Vec(p.lsu.loadPipes + p.lsu.storePipes,
    Decoupled(new MemoryResponseTxn(p))))
  val recovery = Flipped(new RecoveryTargetIO(p))
  val memoryFault = Decoupled(new LSUMemoryFaultTxn(p))
  val maintenance = Flipped(Decoupled(new LSUMaintenanceTxn(p)))
  val maintenanceResult = Decoupled(new LSUMaintenanceResult(p))
  val trace = Decoupled(new TracePacket(p))
  val quiescent = Output(Bool())
  val protocolError = Output(Bool())
}
