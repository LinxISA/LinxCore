package linxcore.rename

import chisel3._

import linxcore.common._

object StoreSplitStoreType extends ChiselEnum {
  val All, Addr, Data = Value
}

class StoreSplitIssuePayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val uop = new RenamedUop(p)
  val storeType = StoreSplitStoreType()
  val dataSrcIndex = UInt(2.W)
  val staSrc0Zeroed = Bool()
}

class StoreSplitPayloadIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val in = Input(new RenamedUop(p))
  val staReady = Input(Bool())
  val stdReady = Input(Bool())

  val inReady = Output(Bool())
  val fire = Output(Bool())
  val storeActive = Output(Bool())
  val split = Output(Bool())
  val blockedBySta = Output(Bool())
  val blockedByStd = Output(Bool())
  val sta = Output(new StoreSplitIssuePayload(p))
  val std = Output(new StoreSplitIssuePayload(p))
  val unsplit = Output(new StoreSplitIssuePayload(p))
}

class StoreSplitPayload(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new StoreSplitPayloadIO(p))

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

  val sta = Wire(new StoreSplitIssuePayload(p))
  sta := 0.U.asTypeOf(sta)
  sta.valid := fire && split
  sta.uop := io.in
  sta.uop.src(0) := Mux(io.in.isStorePcr, io.in.src(0), zeroStoreAddressOperand)
  sta.storeType := StoreSplitStoreType.Addr
  sta.dataSrcIndex := dataSrcIndex
  sta.staSrc0Zeroed := !io.in.isStorePcr

  val std = Wire(new StoreSplitIssuePayload(p))
  std := 0.U.asTypeOf(std)
  std.valid := fire && split
  std.uop := io.in
  std.storeType := StoreSplitStoreType.Data
  std.dataSrcIndex := dataSrcIndex
  std.staSrc0Zeroed := false.B

  val unsplit = Wire(new StoreSplitIssuePayload(p))
  unsplit := 0.U.asTypeOf(unsplit)
  unsplit.valid := fire && !split
  unsplit.uop := io.in
  unsplit.storeType := StoreSplitStoreType.All
  unsplit.dataSrcIndex := dataSrcIndex
  unsplit.staSrc0Zeroed := false.B

  io.sta := sta
  io.std := std
  io.unsplit := unsplit
}
