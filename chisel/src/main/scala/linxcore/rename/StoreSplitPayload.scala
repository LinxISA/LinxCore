package linxcore.rename

import chisel3._

import linxcore.common._
import linxcore.rob.ROBID

object StoreSplitStoreType extends ChiselEnum {
  val All, Addr, Data = Value
}

class StoreSplitIssuePayload(val p: InterfaceParams = InterfaceParams(), val mapQDepth: Int = 32) extends Bundle {
  val valid = Bool()
  val uop = new RenamedUop(p)
  val storeType = StoreSplitStoreType()
  val dataSrcIndex = UInt(2.W)
  val staSrc0Zeroed = Bool()
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val tuDstValid = Bool()
  val tuDstKind = DestinationKind()
}

class StoreSplitPayloadIO(val p: InterfaceParams = InterfaceParams(), val mapQDepth: Int = 32) extends Bundle {
  val in = Input(new RenamedUop(p))
  val tSeq = Input(new ROBID(mapQDepth))
  val uSeq = Input(new ROBID(mapQDepth))
  val tuDstValid = Input(Bool())
  val tuDstKind = Input(DestinationKind())
  val staReady = Input(Bool())
  val stdReady = Input(Bool())

  val inReady = Output(Bool())
  val fire = Output(Bool())
  val storeActive = Output(Bool())
  val split = Output(Bool())
  val blockedBySta = Output(Bool())
  val blockedByStd = Output(Bool())
  val sta = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val std = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val unsplit = Output(new StoreSplitIssuePayload(p, mapQDepth))
}

class StoreSplitPayload(val p: InterfaceParams = InterfaceParams(), val mapQDepth: Int = 32) extends Module {
  val io = IO(new StoreSplitPayloadIO(p, mapQDepth))

  private def zeroStoreAddressOperand: RenamedOperand = {
    val op = Wire(new RenamedOperand(p))
    op := 0.U.asTypeOf(op)
    op.valid := false.B
    op.operandClass := OperandClass.Invalid
    op.archTag := LinxCommonConstants.regInvalid(p.archRegWidth)
    op.relTag := LinxCommonConstants.regInvalid(p.archRegWidth)
    op.physTag := LinxCommonConstants.regInvalid(p.physRegWidth)
    op.ready := true.B
    op.literalValid := true.B
    op.literal := 0.U
    op
  }

  val storeActive = io.in.valid && io.in.isStore
  val split =
    storeActive && io.in.storeSplitIntent && !io.in.isLoadStorePair && !io.in.cacheMaintainNoSplit
  val readyForStore = Mux(split, io.staReady && io.stdReady, io.staReady)
  val fire = storeActive && readyForStore
  val dataSrcIndex = Mux(io.in.isStorePcr, 1.U(2.W), 0.U(2.W))

  io.storeActive := storeActive
  io.split := split
  io.inReady := !storeActive || readyForStore
  io.fire := fire
  io.blockedBySta := storeActive && !io.staReady
  io.blockedByStd := split && !io.stdReady

  private def attachTUSidecar(payload: StoreSplitIssuePayload): Unit = {
    payload.tSeq := Mux(payload.valid, io.tSeq, ROBID.disabled(mapQDepth))
    payload.uSeq := Mux(payload.valid, io.uSeq, ROBID.disabled(mapQDepth))
    payload.tuDstValid := payload.valid && io.tuDstValid
    payload.tuDstKind := Mux(payload.valid && io.tuDstValid, io.tuDstKind, DestinationKind.None)
  }

  val sta = Wire(new StoreSplitIssuePayload(p, mapQDepth))
  sta := 0.U.asTypeOf(sta)
  sta.valid := fire && split
  sta.uop := io.in
  sta.uop.src(0) := Mux(io.in.isStorePcr, io.in.src(0), zeroStoreAddressOperand)
  sta.storeType := StoreSplitStoreType.Addr
  sta.dataSrcIndex := dataSrcIndex
  sta.staSrc0Zeroed := !io.in.isStorePcr
  attachTUSidecar(sta)

  val std = Wire(new StoreSplitIssuePayload(p, mapQDepth))
  std := 0.U.asTypeOf(std)
  std.valid := fire && split
  std.uop := io.in
  std.storeType := StoreSplitStoreType.Data
  std.dataSrcIndex := dataSrcIndex
  std.staSrc0Zeroed := false.B
  attachTUSidecar(std)

  val unsplit = Wire(new StoreSplitIssuePayload(p, mapQDepth))
  unsplit := 0.U.asTypeOf(unsplit)
  unsplit.valid := fire && !split
  unsplit.uop := io.in
  unsplit.storeType := StoreSplitStoreType.All
  unsplit.dataSrcIndex := dataSrcIndex
  unsplit.staSrc0Zeroed := false.B
  attachTUSidecar(unsplit)

  io.sta := sta
  io.std := std
  io.unsplit := unsplit
}
