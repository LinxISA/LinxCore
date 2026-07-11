package linxcore.backend

import chisel3._
import chisel3.util.Mux1H
import linxcore.common.{DecodedUop, InterfaceParams}

class DecodeLoadStoreIdAssignIO(
    val p: InterfaceParams = InterfaceParams(),
    val serialWidth: Int = 64,
    val stidCount: Int = 1)
    extends Bundle {
  require(serialWidth >= 64, "load/store serial counters must preserve the model uint64 counter contract")
  require(stidCount > 0, "load/store identity owner must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << p.threadIdWidth), "STID count must fit decoded thread ID")

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
  val flushAll = Input(Bool())
  val flushStid = Input(UInt(p.threadIdWidth.W))
  val restoreValid = Input(Bool())
  val restoreStid = Input(UInt(p.threadIdWidth.W))
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
  val nextLsIdByStid = Output(Vec(stidCount, UInt(p.lsidWidth.W)))
  val nextLoadIdByStid = Output(Vec(stidCount, UInt(serialWidth.W)))
  val nextStoreIdByStid = Output(Vec(stidCount, UInt(serialWidth.W)))
  val selectedStidInRange = Output(Bool())
  val storeSplitIntent = Output(Bool())
}

class DecodeLoadStoreIdAssign(
    val p: InterfaceParams = InterfaceParams(),
    val serialWidth: Int = 64,
    val stidCount: Int = 1)
    extends Module {
  require(serialWidth >= 64, "load/store serial counters must preserve the model uint64 counter contract")
  require(stidCount > 0, "load/store identity owner must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << p.threadIdWidth), "STID count must fit decoded thread ID")

  val io = IO(new DecodeLoadStoreIdAssignIO(p, serialWidth, stidCount))

  val nextLsId = RegInit(VecInit(Seq.fill(stidCount)(0.U(p.lsidWidth.W))))
  val nextLoadId = RegInit(VecInit(Seq.fill(stidCount)(0.U(serialWidth.W))))
  val nextStoreId = RegInit(VecInit(Seq.fill(stidCount)(0.U(serialWidth.W))))
  val selectedStidMatch = VecInit((0 until stidCount).map { stid =>
    io.in.threadId === stid.U(p.threadIdWidth.W)
  })
  val flushStidMatch = VecInit((0 until stidCount).map { stid =>
    io.flushStid === stid.U(p.threadIdWidth.W)
  })
  val restoreStidMatch = VecInit((0 until stidCount).map { stid =>
    io.restoreStid === stid.U(p.threadIdWidth.W)
  })
  val selectedStidInRange = selectedStidMatch.asUInt.orR
  val selectedNextLsId = Mux(selectedStidInRange, Mux1H(selectedStidMatch, nextLsId), 0.U)
  val selectedNextLoadId = Mux(selectedStidInRange, Mux1H(selectedStidMatch, nextLoadId), 0.U)
  val selectedNextStoreId = Mux(selectedStidInRange, Mux1H(selectedStidMatch, nextStoreId), 0.U)

  val storeLike = io.in.valid && (io.isStore || io.isDczva)
  val loadLike = io.in.valid && io.isLoad && !storeLike
  val memoryValid = loadLike || storeLike
  val splitIntent =
    io.in.valid && io.isStore && (io.storeSplitRequest || io.stackSetRequest) &&
      !io.isLoadStorePair && !io.cacheMaintainNoSplit

  io.out := io.in
  io.out.lsid := Mux(io.in.valid && selectedStidInRange, selectedNextLsId, io.in.lsid)
  io.out.isLoad := io.isLoad
  io.out.isStore := io.isStore
  io.out.storeSplitIntent := splitIntent
  io.out.isLoadStorePair := io.isLoadStorePair
  io.out.isStorePcr := io.isStorePcr
  io.out.cacheMaintainNoSplit := io.cacheMaintainNoSplit

  io.memoryValid := memoryValid
  io.loadIdValid := loadLike
  io.storeIdValid := storeLike
  io.assignFire := io.accept && memoryValid && selectedStidInRange
  io.assignedLsId := Mux(memoryValid && selectedStidInRange, selectedNextLsId, 0.U)
  io.assignedLoadId := Mux(loadLike && selectedStidInRange, selectedNextLoadId, 0.U)
  io.assignedStoreId := Mux(storeLike && selectedStidInRange, selectedNextStoreId, 0.U)
  io.nextLsId := selectedNextLsId
  io.nextLoadId := selectedNextLoadId
  io.nextStoreId := selectedNextStoreId
  io.nextLsIdByStid := nextLsId
  io.nextLoadIdByStid := nextLoadId
  io.nextStoreIdByStid := nextStoreId
  io.selectedStidInRange := selectedStidInRange
  io.storeSplitIntent := splitIntent

  when(io.flushValid) {
    for (stid <- 0 until stidCount) {
      when(io.flushAll || flushStidMatch(stid)) {
        when(!io.flushAll && io.restoreValid && restoreStidMatch(stid)) {
          nextLsId(stid) := io.restoreLsId
          nextLoadId(stid) := io.restoreLoadId
          nextStoreId(stid) := io.restoreStoreId
        }.otherwise {
          nextLsId(stid) := 0.U
          nextLoadId(stid) := 0.U
          nextStoreId(stid) := 0.U
        }
      }
    }
  }.elsewhen(io.assignFire) {
    for (stid <- 0 until stidCount) {
      when(selectedStidMatch(stid)) {
        nextLsId(stid) := nextLsId(stid) + 1.U
        when(loadLike) {
          nextLoadId(stid) := nextLoadId(stid) + 1.U
        }
        when(storeLike) {
          nextStoreId(stid) := nextStoreId(stid) + 1.U
        }
      }
    }
  }
}
