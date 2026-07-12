package linxcore.lsu

import chisel3._
import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams

class ReducedStoreNonFlushGateProbeIO extends Bundle {
  val flushValid = Input(Bool())
  val commitValid = Input(Bool())
  val commitStore = Input(Bool())
  val commitBlockBid = Input(UInt(64.W))
  val commitLsId = Input(UInt(32.W))
  val rowValid = Input(Bool())
  val rowScalarIex = Input(Bool())
  val nonFlushValid = Input(Bool())
  val nonFlushHeadBid = Input(UInt(64.W))
  val nonFlushPrefixCount = Input(UInt(3.W))
  val oldestBlockValid = Input(Bool())
  val oldestBlockBid = Input(UInt(64.W))
  val oldestRobValid = Input(Bool())
  val oldestRobBid = Input(UInt(2.W))
  val oldestRobLsId = Input(UInt(32.W))

  val blockedByNonFlush = Output(Bool())
  val pendingMarkMask = Output(UInt(4.W))
  val earlySafeMatchMask = Output(UInt(4.W))
  val residentEarlySafeMask = Output(UInt(4.W))
  val markValid = Output(Bool())
  val markIndex = Output(UInt(2.W))
}

class ReducedStoreNonFlushGateProbe extends Module {
  private val traceParams = CommitTraceParams(commitWidth = 1, robValueWidth = 2)
  val io = IO(new ReducedStoreNonFlushGateProbeIO)
  val owner = Module(new ReducedStoreCommitFreeOwner(
    entries = 4,
    traceParams = traceParams,
    stidWidth = 2,
    tidWidth = 2,
    mapQDepth = 4))

  owner.io.enable := true.B
  owner.io.directFreeEnable := false.B
  owner.io.flushValid := io.flushValid
  owner.io.activeStid := 0.U
  owner.io.nonFlushValid := io.nonFlushValid
  owner.io.nonFlushHeadBid := io.nonFlushHeadBid
  owner.io.nonFlushPrefixCount := io.nonFlushPrefixCount
  owner.io.oldestBlockValid := io.oldestBlockValid
  owner.io.oldestBlockBid := io.oldestBlockBid
  owner.io.oldestRobValid := io.oldestRobValid
  owner.io.oldestRobBid.valid := io.oldestRobValid
  owner.io.oldestRobBid.wrap := false.B
  owner.io.oldestRobBid.value := io.oldestRobBid
  owner.io.oldestRobLsId := io.oldestRobLsId
  owner.io.oldestRobStid := 0.U

  owner.io.commit := 0.U.asTypeOf(owner.io.commit)
  owner.io.commit.rows(0).valid := io.commitValid
  owner.io.commit.rows(0).identity.bid := 1.U
  owner.io.commit.rows(0).identity.gid := 2.U
  owner.io.commit.rows(0).identity.rid := 3.U
  owner.io.commit.rows(0).blockBidValid := io.commitValid
  owner.io.commit.rows(0).blockBid := io.commitBlockBid
  owner.io.commit.rows(0).mem.valid := io.commitValid && io.commitStore
  owner.io.commit.rows(0).mem.isStore := io.commitStore
  owner.io.commitValidMask := io.commitValid
  owner.io.commitMemoryOrder := 0.U.asTypeOf(owner.io.commitMemoryOrder)
  owner.io.commitMemoryOrder(0).valid := io.commitValid
  owner.io.commitMemoryOrder(0).isLoadStore := io.commitStore
  owner.io.commitMemoryOrder(0).isStore := io.commitStore
  owner.io.commitMemoryOrder(0).lsId := io.commitLsId

  val rows = Wire(Vec(4, new STQEntryBankRow(4, stidWidth = 2, tidWidth = 2, mapQDepth = 4)))
  rows := 0.U.asTypeOf(rows)
  rows(0).valid := io.rowValid
  rows(0).status := STQEntryStatus.Wait
  rows(0).storeType := STQStoreType.All
  rows(0).stid := 0.U
  rows(0).bid.valid := io.rowValid
  rows(0).bid.value := 1.U
  rows(0).gid.valid := io.rowValid
  rows(0).gid.value := 2.U
  rows(0).rid.valid := io.rowValid
  rows(0).rid.value := 3.U
  rows(0).addrReady := io.rowValid
  rows(0).dataReady := io.rowValid
  rows(0).scalarIex := io.rowScalarIex
  rows(0).lsId.valid := io.rowValid
  rows(0).lsId.value := 1.U
  owner.io.stqRows := rows

  owner.io.markCommitAccepted := false.B
  owner.io.markCommitIgnored := false.B
  owner.io.commitFreeAccepted := false.B
  owner.io.commitFreeIgnored := false.B
  owner.io.commitFreeAcceptedMask := 0.U
  owner.io.commitFreeIgnoredMask := 0.U

  io.blockedByNonFlush := owner.io.commitStoreBlockedByNonFlush
  io.pendingMarkMask := owner.io.pendingMarkMask
  io.earlySafeMatchMask := owner.io.earlySafeMatchMask
  io.residentEarlySafeMask := owner.io.residentEarlySafeMask
  io.markValid := owner.io.markCommitValid
  io.markIndex := owner.io.markCommitIndex
}

object EmitReducedStoreNonFlushGateProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ReducedStoreNonFlushGateProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
