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

  val allocValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))

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

  scalarLsu.io <> io.scalarLsu

  commitRob.io.allocValid := io.allocValid
  commitRob.io.allocRow := io.allocRow
  io.allocReady := commitRob.io.allocReady
  io.allocDuplicateIdentity := commitRob.io.allocDuplicateIdentity

  commitRob.io.completeValid := io.completeValid
  commitRob.io.completeRobValue := io.completeRobValue

  io.commit := commitRob.io.commit
  io.commitValidMask := commitRob.io.commitValidMask
  io.commitCount := commitRob.io.commitCount
  io.commitMonitorValidMask := commitRob.io.commitMonitorValidMask
  io.commitMonitorValidCount := commitRob.io.commitMonitorValidCount
  io.commitSkippedSlot := commitRob.io.commitSkippedSlot
  io.commitDuplicateIdentity := commitRob.io.commitDuplicateIdentity
  io.commitSlotMismatch := commitRob.io.commitSlotMismatch
  io.commitInvalidSideEffect := commitRob.io.commitInvalidSideEffect
  io.commitContractError := commitRob.io.commitContractError

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
      !scalarLsu.io.recovery.cleanupPending
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
