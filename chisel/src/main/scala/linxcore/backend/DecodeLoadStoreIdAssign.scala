package linxcore.backend

import chisel3._
import linxcore.common.{DecodedUop, InterfaceParams}

class DecodeLoadStoreIdAssignIO(
    val p: InterfaceParams = InterfaceParams(),
    val serialWidth: Int = 64)
    extends Bundle {
  require(serialWidth >= 64, "load/store serial counters must preserve the model uint64 counter contract")

  val in = Input(new DecodedUop(p))
  val isLoad = Input(Bool())
  val isStore = Input(Bool())
  val isDczva = Input(Bool())
  val isLoadStorePair = Input(Bool())
  val isStorePcr = Input(Bool())
  val cacheMaintainNoSplit = Input(Bool())
  val storeSplitRequest = Input(Bool())
  val stackSetRequest = Input(Bool())
  val accept = Input(Bool())
  val flushValid = Input(Bool())
  val restoreValid = Input(Bool())
  val restoreLsId = Input(UInt(p.lsidWidth.W))
  val restoreLoadId = Input(UInt(serialWidth.W))
  val restoreStoreId = Input(UInt(serialWidth.W))

  val out = Output(new DecodedUop(p))
  val memoryValid = Output(Bool())
  val loadIdValid = Output(Bool())
  val storeIdValid = Output(Bool())
  val assignFire = Output(Bool())
  val assignedLsId = Output(UInt(p.lsidWidth.W))
  val assignedLoadId = Output(UInt(serialWidth.W))
  val assignedStoreId = Output(UInt(serialWidth.W))
  val nextLsId = Output(UInt(p.lsidWidth.W))
  val nextLoadId = Output(UInt(serialWidth.W))
  val nextStoreId = Output(UInt(serialWidth.W))
  val storeSplitIntent = Output(Bool())
}

class DecodeLoadStoreIdAssign(
    val p: InterfaceParams = InterfaceParams(),
    val serialWidth: Int = 64)
    extends Module {
  require(serialWidth >= 64, "load/store serial counters must preserve the model uint64 counter contract")

  val io = IO(new DecodeLoadStoreIdAssignIO(p, serialWidth))

  val nextLsId = RegInit(0.U(p.lsidWidth.W))
  val nextLoadId = RegInit(0.U(serialWidth.W))
  val nextStoreId = RegInit(0.U(serialWidth.W))

  val storeLike = io.in.valid && (io.isStore || io.isDczva)
  val loadLike = io.in.valid && io.isLoad && !storeLike
  val memoryValid = loadLike || storeLike
  val splitIntent =
    io.in.valid && io.isStore && (io.storeSplitRequest || io.stackSetRequest) &&
      !io.isLoadStorePair && !io.cacheMaintainNoSplit

  io.out := io.in
  io.out.lsid := Mux(memoryValid, nextLsId, io.in.lsid)
  io.out.isLoad := io.isLoad
  io.out.isStore := io.isStore
  io.out.storeSplitIntent := splitIntent
  io.out.isLoadStorePair := io.isLoadStorePair
  io.out.isStorePcr := io.isStorePcr
  io.out.cacheMaintainNoSplit := io.cacheMaintainNoSplit

  io.memoryValid := memoryValid
  io.loadIdValid := loadLike
  io.storeIdValid := storeLike
  io.assignFire := io.accept && memoryValid
  io.assignedLsId := Mux(memoryValid, nextLsId, 0.U)
  io.assignedLoadId := Mux(loadLike, nextLoadId, 0.U)
  io.assignedStoreId := Mux(storeLike, nextStoreId, 0.U)
  io.nextLsId := nextLsId
  io.nextLoadId := nextLoadId
  io.nextStoreId := nextStoreId
  io.storeSplitIntent := splitIntent

  when(io.flushValid) {
    when(io.restoreValid) {
      nextLsId := io.restoreLsId
      nextLoadId := io.restoreLoadId
      nextStoreId := io.restoreStoreId
    }.otherwise {
      nextLsId := 0.U
      nextLoadId := 0.U
      nextStoreId := 0.U
    }
  }.elsewhen(io.assignFire) {
    nextLsId := nextLsId + 1.U
    when(loadLike) {
      nextLoadId := nextLoadId + 1.U
    }
    when(storeLike) {
      nextStoreId := nextStoreId + 1.U
    }
  }
}
