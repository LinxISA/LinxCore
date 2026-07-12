package linxcore.top

import chisel3._
import chisel3.util.Cat
import circt.stage.ChiselStage

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.{DestinationKind, ScalarBackendParams, ScalarLsuParams}
import linxcore.lsu.LoadReplayDestination
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
  val loadWritebackEnable = Input(Bool())
  val loadWakeupEnable = Input(Bool())
  val loadDstValid = Input(Bool())
  val loadDstKind = Input(UInt(2.W))
  val loadDstTag = Input(UInt(7.W))
  val loadData = Input(UInt(64.W))
  val loadSpecWakeup = Input(Bool())
  val loadStackValid = Input(Bool())

  val gprClearValid = Input(Bool())
  val gprClearTag = Input(UInt(7.W))
  val externalGprWriteValid = Input(Bool())
  val externalGprWriteTag = Input(UInt(7.W))
  val externalGprWriteData = Input(UInt(64.W))
  val gprReadTag = Input(UInt(7.W))
  val singlePortMode = Input(Bool())

  val lookupRowValid = Output(Bool())
  val lookupBlockedByFree = Output(Bool())
  val lookupBlockedByStaleRid = Output(Bool())
  val loadResolveReady = Output(Bool())
  val scalarLoadSelected = Output(Bool())
  val collision = Output(Bool())
  val loadWritebackSelected = Output(Bool())
  val loadWakeupPublished = Output(Bool())
  val loadBlockedByExternalWrite = Output(Bool())
  val externalGprWriteFire = Output(Bool())
  val gprReadData = Output(UInt(64.W))
  val gprReadReady = Output(Bool())
  val protocolError = Output(Bool())
  val commitValid = Output(Bool())
  val commitRobValue = Output(UInt(3.W))
  val size = Output(UInt(4.W))
}

class ScalarLoadCompletionROBProbe extends Module {
  private val entries = 8
  private val traceParams = CommitTraceParams(commitWidth = 1, robValueWidth = 3)
  private val lsuParams = ScalarLsuParams(liqEntries = 8)
  private val backendParams = ScalarBackendParams()
  val io = IO(new ScalarLoadCompletionROBProbeIO)

  val rob = Module(new ReducedCommitROB(entries, traceParams))
  val bridge = Module(new ScalarLoadCompletionROBBridge(entries))
  val gprSink = Module(new ScalarLoadGPRCompletionSink(backendParams, lsuParams))
  val singleGprSink = Module(new ScalarLoadGPRCompletionSink(
    backendParams.copy(gprWritePorts = 1),
    lsuParams
  ))

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

  val loadDst = Wire(new LoadReplayDestination(lsuParams.archRegWidth, lsuParams.physRegWidth))
  loadDst := LoadReplayDestination.none(lsuParams.archRegWidth, lsuParams.physRegWidth)
  loadDst.valid := io.loadDstValid
  loadDst.kind := io.loadDstKind.asTypeOf(DestinationKind())
  loadDst.physTag := io.loadDstTag
  for (sink <- Seq(gprSink, singleGprSink)) {
    sink.io.initValid := false.B
    sink.io.initTag := 0.U
    sink.io.initData := 0.U
    sink.io.clearValid := io.gprClearValid
    sink.io.clearTag := io.gprClearTag
    sink.io.externalWriteValid := io.externalGprWriteValid
    sink.io.externalWriteTag := io.externalGprWriteTag
    sink.io.externalWriteData := io.externalGprWriteData
    sink.io.loadCandidateValid := io.loadValid
    sink.io.loadDst := loadDst
    sink.io.loadData := io.loadData
    sink.io.loadSpecWakeup := io.loadSpecWakeup
    sink.io.loadStackValid := io.loadStackValid
    sink.io.readTag := io.gprReadTag
  }

  val loadWriteRequired = io.loadValid && io.loadDstValid &&
    (loadDst.kind === DestinationKind.Gpr)
  val loadWakeupRequired = io.loadValid && io.loadDstValid &&
    (loadDst.kind =/= DestinationKind.None) && !io.loadSpecWakeup && !io.loadStackValid
  val selectedWritebackReady = Mux(
    io.singlePortMode,
    singleGprSink.io.loadWritebackReady,
    gprSink.io.loadWritebackReady
  ) && io.loadWritebackEnable
  val selectedWakeupReady = Mux(
    io.singlePortMode,
    singleGprSink.io.loadWakeupReady,
    gprSink.io.loadWakeupReady
  ) && io.loadWakeupEnable
  val loadResolveFire = io.loadValid && rob.io.lookupRowValid &&
    bridge.io.loadResolveReady && selectedWritebackReady && selectedWakeupReady
  val loadWritebackFire = loadResolveFire && loadWriteRequired
  val loadWakeupFire = loadResolveFire && loadWakeupRequired
  bridge.io.loadResolveFire := loadResolveFire
  gprSink.io.loadResolveFire := loadResolveFire && !io.singlePortMode
  gprSink.io.loadWritebackFire := loadWritebackFire && !io.singlePortMode
  gprSink.io.loadWakeupFire := loadWakeupFire && !io.singlePortMode
  singleGprSink.io.loadResolveFire := loadResolveFire && io.singlePortMode
  singleGprSink.io.loadWritebackFire := loadWritebackFire && io.singlePortMode
  singleGprSink.io.loadWakeupFire := loadWakeupFire && io.singlePortMode

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
  io.loadResolveReady := bridge.io.loadResolveReady &&
    selectedWritebackReady && selectedWakeupReady
  io.scalarLoadSelected := bridge.io.scalarLoadSelected
  io.collision := bridge.io.collision
  io.loadWritebackSelected := Mux(
    io.singlePortMode,
    singleGprSink.io.loadWritebackSelected,
    gprSink.io.loadWritebackSelected
  )
  io.loadWakeupPublished := Mux(
    io.singlePortMode,
    singleGprSink.io.loadWakeupPublished,
    gprSink.io.loadWakeupPublished
  )
  io.loadBlockedByExternalWrite := Mux(
    io.singlePortMode,
    singleGprSink.io.loadBlockedByExternalWrite,
    gprSink.io.loadBlockedByExternalWrite
  )
  io.externalGprWriteFire := Mux(
    io.singlePortMode,
    singleGprSink.io.externalWriteFire,
    gprSink.io.externalWriteFire
  )
  io.gprReadData := Mux(io.singlePortMode, singleGprSink.io.readData, gprSink.io.readData)
  io.gprReadReady := Mux(io.singlePortMode, singleGprSink.io.readReady, gprSink.io.readReady)
  io.protocolError := bridge.io.protocolError || gprSink.io.protocolError ||
    singleGprSink.io.protocolError || rob.io.commitContractError
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
