package linxcore.ooo

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind
import linxcore.lsu.{STQStoreRequest, STQStoreType}

class OooStqReservationProjectionIO(
    val p: OooParams,
    val stqEntries: Int)
    extends Bundle {
  val inputValid = Input(Bool())
  val input = Input(new OooIexIssueRow(p))
  val reserveValid = Output(Bool())
  val reserveMask = Output(UInt(p.maxMemoryRequestsPerInstruction.W))
  val reserve = Output(Vec(p.maxMemoryRequestsPerInstruction,
    new STQStoreRequest(
      p.robIdentityGroupsPerStid,
      peIdWidth = p.peIdWidth,
      stidWidth = p.stidWidth,
      tidWidth = p.stidWidth,
      mapQDepth = p.tuMapQDepthPerStid,
      lsidWidth = p.lsidWidth,
      nativeBidWidth = p.nativeBidWidth,
      ridGenerationWidth = p.ridGenerationWidth,
      brobGenerationWidth = p.brobGenerationWidth,
      memberIndexWidth = p.robMemberIndexWidth,
      residentGenerationWidth = p.residentGenerationWidth,
      leaseGenerationWidth = p.executeSlotGenerationWidth,
      physicalStqEntries = stqEntries)))
  val rejected = Output(Bool())
}

/** Converts the address child of one published store into an atomic STQ
  * reservation batch. Physical AGU/STD children share one ROB identity and
  * use `childIndex` only to identify their execution half.
  */
class OooStqReservationProjection(
    val p: OooParams = OooParams(),
    val stqEntries: Int = 16)
    extends Module {
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "STQ capacity must be a power of two greater than one")
  require(p.maxMemoryRequestsPerInstruction == 2,
    "scalar OOO-to-STQ projection currently supports at most two beats")

  val io = IO(new OooStqReservationProjectionIO(p, stqEntries))

  private def projectSerial(target: linxcore.rob.ROBID, full: UInt): Unit = {
    val valueWidth = log2Ceil(target.entries)
    target.valid := true.B
    target.value := full(valueWidth - 1, 0)
    target.wrap := full(valueWidth)
  }

  private def projectMember(target: linxcore.rob.ROBID): Unit = {
    target.valid := io.input.member.group.valid
    target.value := io.input.member.group.ridSlot
    target.wrap := io.input.member.group.ridGeneration(0)
  }

  private def projectBid(target: linxcore.rob.ROBID): Unit = {
    val valueWidth = log2Ceil(target.entries)
    target.valid := io.input.member.bid.valid
    target.value := io.input.member.bid.value.pad(valueWidth)(valueWidth - 1, 0)
    target.wrap := io.input.member.brobGeneration(0)
  }

  val storeRecipe = io.input.recipe.valid &&
    (io.input.recipe.disposition === OooOpcodeDisposition.Dispatch.U) &&
    (io.input.recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U) &&
    ((io.input.recipe.recipeKind === OooOpcodeRecipeKind.ScalarStore.U) ||
      (io.input.recipe.recipeKind === OooOpcodeRecipeKind.PairStore.U)) &&
    ((io.input.recipe.lateSplitKind === OooLateSplitKind.StoreAddressData.U) ||
      (io.input.recipe.lateSplitKind === OooLateSplitKind.PairStoreAddressData.U))
  val requestCountLegal =
    (io.input.memoryOrder.requestCount === 1.U) ||
      (io.input.memoryOrder.requestCount === 2.U)
  val pairShapeExact =
    (io.input.recipe.recipeKind === OooOpcodeRecipeKind.PairStore.U) ===
      (io.input.memoryOrder.requestCount === 2.U)
  val exactShape =
    io.input.schedule.valid &&
      io.input.member.group.valid &&
      io.input.member.bid.valid &&
      io.input.memory.valid && io.input.memory.isStore &&
      io.input.memoryOrder.valid && io.input.memoryOrder.memoryValid &&
      io.input.memoryOrder.isStore && !io.input.memoryOrder.isLoad &&
      requestCountLegal && pairShapeExact && storeRecipe &&
      (io.input.reservation.uopClass === OooUopClass.Agu) &&
      (io.input.childIndex === 0.U)

  io.reserveValid := io.inputValid && exactShape
  io.reserveMask := Mux(io.reserveValid,
    Mux(io.input.memoryOrder.requestCount === 2.U, 3.U, 1.U), 0.U)
  io.rejected := io.inputValid && !exactShape

  for (beat <- 0 until p.maxMemoryRequestsPerInstruction) {
    val req = io.reserve(beat)
    val fullLsid = io.input.memoryOrder.firstLsid + beat.U
    val fullStoreId = io.input.memoryOrder.firstTypeId + beat.U
    req := 0.U.asTypeOf(req)
    req.storeType := STQStoreType.All
    req.peId := io.input.peId
    req.stid := io.input.stid
    req.tid := io.input.stid
    projectBid(req.bid)
    projectMember(req.gid)
    projectMember(req.rid)
    projectSerial(req.lsId, fullLsid)
    req.lsIdFull := fullLsid
    req.storeIdFullValid := true.B
    req.storeIdFull := fullStoreId
    req.logicalStoreValid := true.B
    req.logicalFirstLsid := io.input.memoryOrder.firstLsid
    req.logicalFirstStoreId := io.input.memoryOrder.firstTypeId
    req.logicalRequestCount := io.input.memoryOrder.requestCount
    req.logicalBeat := beat.U
    req.exactOwner.valid := io.input.member.group.valid
    req.exactOwner.peId := io.input.member.group.peId
    req.exactOwner.stid := io.input.member.group.stid
    req.exactOwner.nativeBidValid := io.input.member.bid.valid
    req.exactOwner.nativeBid := io.input.member.bid.value
    req.exactOwner.brobGeneration := io.input.member.brobGeneration
    req.exactOwner.ridSlot := io.input.member.group.ridSlot
    req.exactOwner.ridGeneration := io.input.member.group.ridGeneration
    req.exactOwner.memberIndex := io.input.member.memberIndex
    req.exactOwner.residentGeneration :=
      io.input.member.residentGeneration
    req.tSeq.valid := false.B
    req.uSeq.valid := false.B
    req.tuDstValid := false.B
    req.tuDstKind := DestinationKind.None
    req.pc := 0.U
    req.addr := 0.U
    req.data := 0.U
    req.size := io.input.memory.accessBytes
    req.stackValid := false.B
    req.scalarIex := true.B
    req.simtLane := 0.U
  }
}
