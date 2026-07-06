package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuardIO(
    val idEntries: Int = 16,
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val ptrWidth = log2Ceil(idEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val fallbackEnable = Input(Bool())
  val captureAccepted = Input(Bool())
  val captureRid = Input(new ROBID(idEntries))
  val physicalWritebackValid = Input(Bool())
  val physicalWritebackRid = Input(new ROBID(idEntries))
  val physicalWritebackTag = Input(UInt(physRegWidth.W))
  val physicalWritebackData = Input(UInt(dataWidth.W))
  val recordValid = Input(Bool())
  val recordRid = Input(new ROBID(idEntries))
  val recordWritebackValid = Input(Bool())
  val recordWritebackTag = Input(UInt(physRegWidth.W))
  val recordWritebackData = Input(UInt(dataWidth.W))
  val recordFire = Input(Bool())

  val active = Output(Bool())
  val captureIntent = Output(Bool())
  val capturePhysicalWriteback = Output(Bool())
  val captureBlockedByNoPhysicalWriteback = Output(Bool())
  val recordCandidate = Output(Bool())
  val recordMatchesCapture = Output(Bool())
  val physicalWritebackPayloadMatches = Output(Bool())
  val duplicatePhysicalWriteback = Output(Bool())
  val fallbackEligible = Output(Bool())
  val fallbackWritebackValid = Output(Bool())
  val fallbackWritebackTag = Output(UInt(physRegWidth.W))
  val fallbackWritebackData = Output(UInt(dataWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByNoRecordWriteback = Output(Bool())
  val blockedByNoCaptureEvidence = Output(Bool())
  val blockedByPriorPhysicalWriteback = Output(Bool())
  val blockedByPhysicalWritebackPayloadMismatch = Output(Bool())
  val blockedByFallbackDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard(
    val idEntries: Int = 16,
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(dataWidth > 0, "dataWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val ptrWidth = log2Ceil(idEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuardIO(
    idEntries,
    dataWidth,
    physRegWidth
  ))

  val captureValidReg = RegInit(false.B)
  val captureRidValueReg = RegInit(0.U(ptrWidth.W))
  val physicalWritebackSeenReg = RegInit(false.B)
  val physicalWritebackTagReg = RegInit(0.U(physRegWidth.W))
  val physicalWritebackDataReg = RegInit(0.U(dataWidth.W))

  val active = io.enable && !io.flush
  val captureIntent = active && io.captureAccepted && io.captureRid.valid
  val capturePhysicalWriteback =
    captureIntent &&
      io.physicalWritebackValid &&
      io.physicalWritebackRid.valid &&
      (io.physicalWritebackRid.value === io.captureRid.value)
  val clearCapture = active && io.recordFire && captureValidReg

  when(io.flush || !io.enable) {
    captureValidReg := false.B
    captureRidValueReg := 0.U
    physicalWritebackSeenReg := false.B
    physicalWritebackTagReg := 0.U
    physicalWritebackDataReg := 0.U
  }.otherwise {
    when(clearCapture && !captureIntent) {
      captureValidReg := false.B
      physicalWritebackSeenReg := false.B
      physicalWritebackTagReg := 0.U
      physicalWritebackDataReg := 0.U
    }
    when(captureIntent) {
      captureValidReg := true.B
      captureRidValueReg := io.captureRid.value
      physicalWritebackSeenReg := capturePhysicalWriteback
      physicalWritebackTagReg := Mux(capturePhysicalWriteback, io.physicalWritebackTag, 0.U)
      physicalWritebackDataReg := Mux(capturePhysicalWriteback, io.physicalWritebackData, 0.U)
    }
  }

  val recordCandidate = active && io.recordValid
  val recordRidValid = io.recordRid.valid
  val recordMatchesCapture =
    recordCandidate && recordRidValid && captureValidReg && (io.recordRid.value === captureRidValueReg)
  val recordWritebackReady = recordCandidate && io.recordWritebackValid
  val physicalWritebackPayloadMatches =
    recordWritebackReady &&
      physicalWritebackSeenReg &&
      (io.recordWritebackTag === physicalWritebackTagReg) &&
      (io.recordWritebackData === physicalWritebackDataReg)
  val duplicatePhysicalWriteback = recordMatchesCapture && physicalWritebackPayloadMatches
  val fallbackEligible = recordWritebackReady && recordMatchesCapture && !physicalWritebackSeenReg
  val fallbackWritebackValid = io.fallbackEnable && fallbackEligible

  io.active := active
  io.captureIntent := captureIntent
  io.capturePhysicalWriteback := capturePhysicalWriteback
  io.captureBlockedByNoPhysicalWriteback := captureIntent && !capturePhysicalWriteback
  io.recordCandidate := recordCandidate
  io.recordMatchesCapture := recordMatchesCapture
  io.physicalWritebackPayloadMatches := physicalWritebackPayloadMatches
  io.duplicatePhysicalWriteback := duplicatePhysicalWriteback
  io.fallbackEligible := fallbackEligible
  io.fallbackWritebackValid := fallbackWritebackValid
  io.fallbackWritebackTag := Mux(fallbackWritebackValid, io.recordWritebackTag, 0.U)
  io.fallbackWritebackData := Mux(fallbackWritebackValid, io.recordWritebackData, 0.U)
  io.blockedByDisabled :=
    !io.enable && (io.captureAccepted || io.recordValid || io.recordWritebackValid)
  io.blockedByFlush :=
    io.enable && io.flush && (io.captureAccepted || io.recordValid || io.recordWritebackValid)
  io.blockedByNoRecord := active && !io.recordValid && io.recordWritebackValid
  io.blockedByInvalidRid := recordCandidate && !recordRidValid
  io.blockedByNoRecordWriteback := recordCandidate && !io.recordWritebackValid
  io.blockedByNoCaptureEvidence := recordWritebackReady && !recordMatchesCapture
  io.blockedByPriorPhysicalWriteback := recordWritebackReady && recordMatchesCapture && physicalWritebackSeenReg
  io.blockedByPhysicalWritebackPayloadMismatch :=
    recordWritebackReady && recordMatchesCapture && physicalWritebackSeenReg && !physicalWritebackPayloadMatches
  io.blockedByFallbackDisabled := fallbackEligible && !io.fallbackEnable
}
