package linxcore.backend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams

class DecodeLoadStoreIdAssignProbeIO extends Bundle {
  val valid = Input(Bool())
  val stid = Input(UInt(2.W))
  val isLoad = Input(Bool())
  val isStore = Input(Bool())
  val accept = Input(Bool())
  val flushValid = Input(Bool())
  val flushAll = Input(Bool())
  val flushStid = Input(UInt(2.W))
  val restoreValid = Input(Bool())
  val restoreStid = Input(UInt(2.W))
  val restoreLsId = Input(UInt(32.W))
  val restoreLoadId = Input(UInt(64.W))
  val restoreStoreId = Input(UInt(64.W))

  val assignFire = Output(Bool())
  val selectedStidInRange = Output(Bool())
  val assignedLsId = Output(UInt(32.W))
  val assignedLoadId = Output(UInt(64.W))
  val assignedStoreId = Output(UInt(64.W))
  val nextLsIdByStid = Output(Vec(2, UInt(32.W)))
  val nextLoadIdByStid = Output(Vec(2, UInt(64.W)))
  val nextStoreIdByStid = Output(Vec(2, UInt(64.W)))
}

class DecodeLoadStoreIdAssignProbe extends Module {
  private val p = InterfaceParams(threadIdWidth = 2)
  val io = IO(new DecodeLoadStoreIdAssignProbeIO)
  val ids = Module(new DecodeLoadStoreIdAssign(p, serialWidth = 64, stidCount = 2))

  ids.io.in := 0.U.asTypeOf(ids.io.in)
  ids.io.in.valid := io.valid
  ids.io.in.threadId := io.stid
  ids.io.isLoad := io.isLoad
  ids.io.isStore := io.isStore
  ids.io.isDczva := false.B
  ids.io.isLoadStorePair := false.B
  ids.io.isStorePcr := false.B
  ids.io.cacheMaintainNoSplit := false.B
  ids.io.storeSplitRequest := false.B
  ids.io.stackSetRequest := false.B
  ids.io.accept := io.accept
  ids.io.flushValid := io.flushValid
  ids.io.flushAll := io.flushAll
  ids.io.flushStid := io.flushStid
  ids.io.restoreValid := io.restoreValid
  ids.io.restoreStid := io.restoreStid
  ids.io.restoreLsId := io.restoreLsId
  ids.io.restoreLoadId := io.restoreLoadId
  ids.io.restoreStoreId := io.restoreStoreId

  io.assignFire := ids.io.assignFire
  io.selectedStidInRange := ids.io.selectedStidInRange
  io.assignedLsId := ids.io.assignedLsId
  io.assignedLoadId := ids.io.assignedLoadId
  io.assignedStoreId := ids.io.assignedStoreId
  io.nextLsIdByStid := ids.io.nextLsIdByStid
  io.nextLoadIdByStid := ids.io.nextLoadIdByStid
  io.nextStoreIdByStid := ids.io.nextStoreIdByStid
}

object EmitDecodeLoadStoreIdAssignProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new DecodeLoadStoreIdAssignProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
