package linxcore.top

import chisel3._
import chisel3.util.log2Ceil
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.lsu.{ScalarLSU, ScalarLSUIO}
import linxcore.rob.ReducedCommitROB

class LinxCoreTopIO(val coreParams: CoreParams, val traceParams: CommitTraceParams) extends Bundle {
  private val ptrWidth = log2Ceil(coreParams.robEntries)
  private val sizeWidth = log2Ceil(coreParams.robEntries + 1)
  private val gprTagWidth = log2Ceil(coreParams.scalarBackend.gprPhysRegs)

  val allocValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val scalarLoadCompleteSelected = Output(Bool())
  val completeCollision = Output(Bool())

  val scalarGprInitValid = Input(Bool())
  val scalarGprInitTag = Input(UInt(gprTagWidth.W))
  val scalarGprInitData = Input(UInt(coreParams.scalarLsu.dataWidth.W))
  val scalarGprClearValid = Input(Bool())
  val scalarGprClearTag = Input(UInt(gprTagWidth.W))
  val externalGprWriteValid = Input(Bool())
  val externalGprWriteTag = Input(UInt(gprTagWidth.W))
  val externalGprWriteData = Input(UInt(coreParams.scalarLsu.dataWidth.W))
  val externalGprWriteReady = Output(Bool())
  val externalGprWriteFire = Output(Bool())
  val scalarGprReadTag = Input(UInt(gprTagWidth.W))
  val scalarGprReadData = Output(UInt(coreParams.scalarLsu.dataWidth.W))
  val scalarGprReadReady = Output(Bool())
  val scalarGprReadyMask = Output(UInt(coreParams.scalarBackend.gprPhysRegs.W))
  val scalarLoadWritebackSelected = Output(Bool())
  val scalarLoadWakeupPublished = Output(Bool())
  val scalarLoadBlockedByExternalWrite = Output(Bool())
  val scalarLoadBlockedByUnsupportedDestination = Output(Bool())
  val scalarBackendContractError = Output(Bool())

  val scalarLsu = new ScalarLSUIO(coreParams, coreParams.scalarLsu)

  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitMonitorValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitMonitorValidCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitSkippedSlot = Output(Bool())
  val commitDuplicateIdentity = Output(Bool())
  val commitSlotMismatch = Output(Bool())
  val commitInvalidSideEffect = Output(Bool())
  val commitContractError = Output(Bool())

  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val headValid = Output(Bool())
  val headComplete = Output(Bool())
  val headRobValue = Output(UInt(ptrWidth.W))
  val idle = Output(Bool())
}

class LinxCoreTop(val coreParams: CoreParams = CoreParams()) extends Module {
  private val traceParams = LinxCoreTop.traceParamsFor(coreParams)
  val io = IO(new LinxCoreTopIO(coreParams, traceParams))

  val commitRob = Module(new ReducedCommitROB(
    entries = coreParams.robEntries,
    traceParams = traceParams
  ))
  val scalarLsu = Module(new ScalarLSU(coreParams))
  val scalarLoadCompletion = Module(new ScalarLoadCompletionROBBridge(coreParams.robEntries))
  val scalarLoadGprSink = Module(new ScalarLoadGPRCompletionSink(
    coreParams.scalarBackend,
    coreParams.scalarLsu
  ))

  scalarLsu.io <> io.scalarLsu

  commitRob.io.allocValid := io.allocValid
  commitRob.io.allocRow := io.allocRow
  io.allocReady := commitRob.io.allocReady
  io.allocDuplicateIdentity := commitRob.io.allocDuplicateIdentity

  scalarLoadCompletion.io.externalCompleteValid := io.completeValid
  scalarLoadCompletion.io.externalCompleteRobValue := io.completeRobValue
  scalarLoadCompletion.io.loadLookupValid := scalarLsu.io.load.loadReturn.robLookupValid
  scalarLoadCompletion.io.loadLookupRid := scalarLsu.io.load.loadReturn.robLookupRid
  scalarLoadCompletion.io.robLookupRowValid := commitRob.io.lookupRowValid
  scalarLoadCompletion.io.robLookupRowNeedFlush := commitRob.io.lookupRowNeedFlush
  scalarLoadCompletion.io.loadCompletionCandidateValid :=
    scalarLsu.io.load.loadReturn.completionCandidateValid
  scalarLoadCompletion.io.loadCompletionRid :=
    scalarLsu.io.load.loadReturn.completion.payload.rid
  scalarLoadCompletion.io.loadResolveEnable := io.scalarLsu.load.loadReturn.resolveReady
  scalarLoadCompletion.io.robExactCompleteReady := commitRob.io.exactCompleteReady
  scalarLoadCompletion.io.loadResolveFire := scalarLsu.io.load.loadReturn.resolveFire

  scalarLoadGprSink.io.initValid := io.scalarGprInitValid
  scalarLoadGprSink.io.initTag := io.scalarGprInitTag
  scalarLoadGprSink.io.initData := io.scalarGprInitData
  scalarLoadGprSink.io.clearValid := io.scalarGprClearValid
  scalarLoadGprSink.io.clearTag := io.scalarGprClearTag
  scalarLoadGprSink.io.externalWriteValid := io.externalGprWriteValid
  scalarLoadGprSink.io.externalWriteTag := io.externalGprWriteTag
  scalarLoadGprSink.io.externalWriteData := io.externalGprWriteData
  scalarLoadGprSink.io.loadCandidateValid :=
    scalarLsu.io.load.loadReturn.completionCandidateValid
  scalarLoadGprSink.io.loadDst := scalarLsu.io.load.loadReturn.completion.payload.dst
  scalarLoadGprSink.io.loadData := scalarLsu.io.load.loadReturn.completion.payload.data
  scalarLoadGprSink.io.loadSpecWakeup :=
    scalarLsu.io.load.loadReturn.completion.payload.specWakeup
  scalarLoadGprSink.io.loadStackValid :=
    scalarLsu.io.load.loadReturn.completion.payload.stackValid
  scalarLoadGprSink.io.loadResolveFire := scalarLsu.io.load.loadReturn.resolveFire
  scalarLoadGprSink.io.loadWritebackFire := scalarLsu.io.load.loadReturn.writebackFire
  scalarLoadGprSink.io.loadWakeupFire := scalarLsu.io.load.loadReturn.wakeupFire
  scalarLoadGprSink.io.readTag := io.scalarGprReadTag

  commitRob.io.lookupValid := scalarLoadCompletion.io.robLookupValid
  commitRob.io.lookupRid := scalarLoadCompletion.io.robLookupRid
  scalarLsu.io.load.loadReturn.robRowValid := scalarLoadCompletion.io.loadRobRowValid
  scalarLsu.io.load.loadReturn.robRowNeedFlush := scalarLoadCompletion.io.loadRobRowNeedFlush
  scalarLsu.io.load.loadReturn.resolveReady := scalarLoadCompletion.io.loadResolveReady
  scalarLsu.io.load.loadReturn.writebackReady :=
    io.scalarLsu.load.loadReturn.writebackReady && scalarLoadGprSink.io.loadWritebackReady
  scalarLsu.io.load.loadReturn.wakeupReady :=
    io.scalarLsu.load.loadReturn.wakeupReady && scalarLoadGprSink.io.loadWakeupReady
  commitRob.io.completeValid := scalarLoadCompletion.io.robCompleteValid
  commitRob.io.completeRobValue := scalarLoadCompletion.io.robCompleteRobValue
  commitRob.io.exactCompleteValid := scalarLoadCompletion.io.robExactCompleteValid
  commitRob.io.exactCompleteRid := scalarLoadCompletion.io.robExactCompleteRid
  io.scalarLoadCompleteSelected := scalarLoadCompletion.io.scalarLoadSelected
  io.completeCollision := scalarLoadCompletion.io.collision
  io.externalGprWriteReady := scalarLoadGprSink.io.externalWriteReady
  io.externalGprWriteFire := scalarLoadGprSink.io.externalWriteFire
  io.scalarGprReadData := scalarLoadGprSink.io.readData
  io.scalarGprReadReady := scalarLoadGprSink.io.readReady
  io.scalarGprReadyMask := scalarLoadGprSink.io.readyMask
  io.scalarLoadWritebackSelected := scalarLoadGprSink.io.loadWritebackSelected
  io.scalarLoadWakeupPublished := scalarLoadGprSink.io.loadWakeupPublished
  io.scalarLoadBlockedByExternalWrite := scalarLoadGprSink.io.loadBlockedByExternalWrite
  io.scalarLoadBlockedByUnsupportedDestination :=
    scalarLoadGprSink.io.loadBlockedByUnsupportedDestination
  io.scalarBackendContractError :=
    scalarLoadCompletion.io.protocolError || scalarLoadGprSink.io.protocolError

  io.commit := commitRob.io.commit
  io.commitValidMask := commitRob.io.commitValidMask
  io.commitCount := commitRob.io.commitCount
  io.commitMonitorValidMask := commitRob.io.commitMonitorValidMask
  io.commitMonitorValidCount := commitRob.io.commitMonitorValidCount
  io.commitSkippedSlot := commitRob.io.commitSkippedSlot
  io.commitDuplicateIdentity := commitRob.io.commitDuplicateIdentity
  io.commitSlotMismatch := commitRob.io.commitSlotMismatch
  io.commitInvalidSideEffect := commitRob.io.commitInvalidSideEffect
  io.commitContractError := commitRob.io.commitContractError || io.scalarBackendContractError

  io.empty := commitRob.io.empty
  io.full := commitRob.io.full
  io.size := commitRob.io.size
  io.headValid := commitRob.io.headValid
  io.headComplete := commitRob.io.headComplete
  io.headRobValue := commitRob.io.headRobValue

  io.idle :=
    commitRob.io.empty &&
      scalarLsu.io.store.stqEmpty &&
      scalarLsu.io.store.drainEmpty &&
      (scalarLsu.io.store.scbEntryCount === 0.U) &&
      scalarLsu.io.store.scbRespBufferEmpty &&
      scalarLsu.io.load.empty &&
      !scalarLsu.io.recovery.sourcePending
}

object LinxCoreTop {
  def traceParamsFor(coreParams: CoreParams): CommitTraceParams =
    CommitTraceParams(
      commitWidth = coreParams.commitWidth,
      robValueWidth = log2Ceil(coreParams.robEntries)
    )
}

object Elaborate extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreTop,
    args = Array("--target-dir", "../generated/chisel-verilog"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}

object EmitLinxCoreTopXcheck extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreTop(CoreParams(
      robEntries = 8,
      commitWidth = 2,
      scalarLsu = ScalarLsuParams(liqEntries = 8)
    )),
    args = Array("--target-dir", "../generated/chisel-verilog/top-xcheck"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
