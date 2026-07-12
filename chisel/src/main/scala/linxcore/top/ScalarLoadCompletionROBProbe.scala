package linxcore.top

import chisel3._
import chisel3.util.Cat
import circt.stage.ChiselStage

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.rob.{ReducedCommitROB, ROBID}

class ScalarLoadCompletionROBProbeIO extends Bundle {
  val allocValid = Input(Bool())
  val allocTag = Input(UInt(3.W))
  val allocReady = Output(Bool())
  val allocRobValue = Output(UInt(3.W))
  val allocRobWrap = Output(Bool())

  val externalCompleteValid = Input(Bool())
  val externalCompleteRobValue = Input(UInt(3.W))
  val loadValid = Input(Bool())
  val loadRidValid = Input(Bool())
  val loadRidWrap = Input(Bool())
  val loadRidValue = Input(UInt(3.W))
  val loadResolveEnable = Input(Bool())

  val lookupRowValid = Output(Bool())
  val lookupBlockedByFree = Output(Bool())
  val lookupBlockedByStaleRid = Output(Bool())
  val loadResolveReady = Output(Bool())
  val scalarLoadSelected = Output(Bool())
  val collision = Output(Bool())
  val protocolError = Output(Bool())
  val commitValid = Output(Bool())
  val commitRobValue = Output(UInt(3.W))
  val size = Output(UInt(4.W))
}

class ScalarLoadCompletionROBProbe extends Module {
  private val entries = 8
  private val traceParams = CommitTraceParams(commitWidth = 1, robValueWidth = 3)
  val io = IO(new ScalarLoadCompletionROBProbeIO)

  val rob = Module(new ReducedCommitROB(entries, traceParams))
  val bridge = Module(new ScalarLoadCompletionROBBridge(entries))

  val allocRow = Wire(new CommitTraceRow(traceParams))
  allocRow := 0.U.asTypeOf(allocRow)
  allocRow.valid := io.allocValid
  allocRow.identity.bid := io.allocTag
  allocRow.identity.gid := 0.U
  allocRow.identity.rid := io.allocTag
  allocRow.blockBidValid := io.allocValid
  allocRow.blockBid := io.allocTag
  allocRow.pc := Cat(0.U(58.W), io.allocTag, 0.U(3.W))
  allocRow.insn := 0x13.U
  allocRow.len := 4.U
  allocRow.nextPc := allocRow.pc + 4.U
  rob.io.allocValid := io.allocValid
  rob.io.allocRow := allocRow

  val loadRid = Wire(new ROBID(entries))
  loadRid.valid := io.loadRidValid
  loadRid.wrap := io.loadRidWrap
  loadRid.value := io.loadRidValue
  bridge.io.externalCompleteValid := io.externalCompleteValid
  bridge.io.externalCompleteRobValue := io.externalCompleteRobValue
  bridge.io.loadLookupValid := io.loadValid
  bridge.io.loadLookupRid := loadRid
  bridge.io.robLookupRowValid := rob.io.lookupRowValid
  bridge.io.robLookupRowNeedFlush := rob.io.lookupRowNeedFlush
  bridge.io.loadCompletionCandidateValid := io.loadValid
  bridge.io.loadCompletionRid := loadRid
  bridge.io.loadResolveEnable := io.loadResolveEnable
  bridge.io.robExactCompleteReady := rob.io.exactCompleteReady
  bridge.io.loadResolveFire :=
    io.loadValid && rob.io.lookupRowValid && bridge.io.loadResolveReady

  rob.io.lookupValid := bridge.io.robLookupValid
  rob.io.lookupRid := bridge.io.robLookupRid
  rob.io.completeValid := bridge.io.robCompleteValid
  rob.io.completeRobValue := bridge.io.robCompleteRobValue
  rob.io.exactCompleteValid := bridge.io.robExactCompleteValid
  rob.io.exactCompleteRid := bridge.io.robExactCompleteRid

  io.allocReady := rob.io.allocReady
  io.allocRobValue := rob.io.allocRid.value
  io.allocRobWrap := rob.io.allocRid.wrap
  io.lookupRowValid := rob.io.lookupRowValid
  io.lookupBlockedByFree := rob.io.lookupBlockedByFree
  io.lookupBlockedByStaleRid := rob.io.lookupBlockedByStaleRid
  io.loadResolveReady := bridge.io.loadResolveReady
  io.scalarLoadSelected := bridge.io.scalarLoadSelected
  io.collision := bridge.io.collision
  io.protocolError := bridge.io.protocolError || rob.io.commitContractError
  io.commitValid := rob.io.commit.rows(0).valid
  io.commitRobValue := rob.io.commit.rows(0).rob.value
  io.size := rob.io.size
}

object EmitScalarLoadCompletionROBProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ScalarLoadCompletionROBProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
