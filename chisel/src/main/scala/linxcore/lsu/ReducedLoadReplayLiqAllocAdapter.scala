package linxcore.lsu

import chisel3._

import linxcore.rob.ROBID

class ReducedLoadReplayLiqAllocAdapterIO(
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Bundle {
  val flush = Input(Bool())
  val candidateValid = Input(Bool())
  val candidate = Input(new ReducedLoadReplayCandidate(
    idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth, lsidWidth))
  val allocReady = Input(Bool())

  val allocValid = Output(Bool())
  val alloc = Output(new LoadInflightAlloc(
    liqEntries, idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth,
    lsidWidth = lsidWidth))
  val consumeReady = Output(Bool())
  val blockedByAlloc = Output(Bool())
  val candidateUsable = Output(Bool())
}

class ReducedLoadReplayLiqAllocAdapter(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new ReducedLoadReplayLiqAllocAdapterIO(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth
  ))

  private def zeroAlloc: LoadInflightAlloc = {
    val alloc = Wire(new LoadInflightAlloc(
      liqEntries, idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth,
      lsidWidth = lsidWidth))
    alloc := 0.U.asTypeOf(alloc)
    alloc.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
    alloc.bid := ROBID.disabled(idEntries)
    alloc.gid := ROBID.disabled(idEntries)
    alloc.rid := ROBID.disabled(idEntries)
    alloc.loadLsId := ROBID.disabled(idEntries)
    alloc.youngestStoreId := ROBID.disabled(idEntries)
    alloc.youngestStoreLsId := ROBID.disabled(idEntries)
    alloc
  }

  val candidateUsable = io.candidateValid && io.candidate.valid && !io.flush
  val alloc = Wire(new LoadInflightAlloc(
    liqEntries, idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth,
    lsidWidth = lsidWidth))
  alloc := zeroAlloc

  when(candidateUsable) {
    alloc.dst := io.candidate.dst
    alloc.bid := io.candidate.bid
    alloc.gid := io.candidate.gid
    alloc.rid := io.candidate.rid
    alloc.loadLsId := io.candidate.loadLsId
    alloc.loadLsIdFullValid := io.candidate.loadLsIdFullValid
    alloc.loadLsIdFull := io.candidate.loadLsIdFull
    alloc.pc := io.candidate.pc
    alloc.addr := io.candidate.addr
    alloc.size := io.candidate.size
    alloc.returnSignExtend := io.candidate.returnSignExtend
    alloc.sourceTraceValid := io.candidate.sourceTraceValid
    alloc.source0 := io.candidate.source0
    alloc.source1 := io.candidate.source1
    alloc.youngestStoreId := io.candidate.youngestStoreId
    alloc.youngestStoreLsId := io.candidate.youngestStoreLsId
    alloc.youngestStoreLsIdFullValid := io.candidate.youngestStoreLsIdFullValid
    alloc.youngestStoreLsIdFull := io.candidate.youngestStoreLsIdFull
    alloc.isTile := false.B
    alloc.specWakeup := false.B
    alloc.stackValid := false.B
  }

  io.allocValid := candidateUsable
  io.alloc := alloc
  io.consumeReady := candidateUsable && io.allocReady
  io.blockedByAlloc := candidateUsable && !io.allocReady
  io.candidateUsable := candidateUsable
}
