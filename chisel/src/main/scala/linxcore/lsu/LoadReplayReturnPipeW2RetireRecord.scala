package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordIO(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val countWidth = log2Ceil(2)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val completionClearSlot = Input(Bool())
  val clearIntent = Input(Bool())
  val liveClear = Input(Bool())
  val lretEnqueueAccepted = Input(Bool())
  val retirePayload = Input(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val recordReady = Input(Bool())

  val captureCandidate = Output(Bool())
  val payloadValid = Output(Bool())
  val captureValid = Output(Bool())
  val captureReady = Output(Bool())
  val captureAccepted = Output(Bool())
  val captureDropped = Output(Bool())
  val recordValid = Output(Bool())
  val record = Output(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val recordFire = Output(Bool())
  val pending = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val capturedWithLretEnqueue = Output(Bool())
  val recordFromLretEnqueue = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByNoCompletionClear = Output(Bool())
  val blockedByNoClearIntent = Output(Bool())
  val blockedByLiveClearDisabled = Output(Bool())
  val blockedByInvalidPayload = Output(Bool())
  val blockedByFull = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecord(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(addrWidth > 0, "addrWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new LoadReplayReturnPipeW2RetireRecordIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  private def zeroEntry: LoadReplayReturnLretEntry = {
    val entry = Wire(new LoadReplayReturnLretEntry(
      idEntries,
      addrWidth,
      pcWidth,
      dataWidth,
      sizeWidth,
      returnPipeCount,
      archRegWidth,
      physRegWidth
    ))
    entry := 0.U.asTypeOf(entry)
    entry.bid := ROBID.disabled(idEntries)
    entry.gid := ROBID.disabled(idEntries)
    entry.rid := ROBID.disabled(idEntries)
    entry.loadLsId := ROBID.disabled(idEntries)
    entry.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
    entry
  }

  val recordValidReg = RegInit(false.B)
  val recordReg = RegInit(zeroEntry)
  val recordFromLretEnqueueReg = RegInit(false.B)

  val active = io.enable && !io.flush
  val captureCandidate =
    active &&
      io.slotOccupied &&
      io.completionClearSlot &&
      io.clearIntent &&
      io.liveClear
  val payloadValid = io.retirePayload.valid
  val captureValid = captureCandidate && payloadValid
  val recordFire = recordValidReg && io.recordReady && !io.flush
  val captureReady = !recordValidReg || recordFire
  val captureAccepted = captureValid && captureReady
  val captureDropped = captureValid && !captureReady

  val capturePayload = Wire(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  capturePayload := io.retirePayload
  capturePayload.valid := true.B

  when(io.flush) {
    recordValidReg := false.B
    recordReg := zeroEntry
    recordFromLretEnqueueReg := false.B
  }.otherwise {
    when(captureAccepted) {
      recordValidReg := true.B
      recordReg := capturePayload
      recordFromLretEnqueueReg := io.lretEnqueueAccepted
    }.elsewhen(recordFire) {
      recordValidReg := false.B
      recordReg := zeroEntry
      recordFromLretEnqueueReg := false.B
    }
  }

  io.captureCandidate := captureCandidate
  io.payloadValid := captureCandidate && payloadValid
  io.captureValid := captureValid
  io.captureReady := captureReady
  io.captureAccepted := captureAccepted
  io.captureDropped := captureDropped
  io.recordValid := recordValidReg && !io.flush
  io.record := Mux(recordValidReg && !io.flush, recordReg, zeroEntry)
  io.recordFire := recordFire
  io.pending := recordValidReg && !io.flush
  io.count := Mux(recordValidReg && !io.flush, 1.U, 0.U)
  io.capturedWithLretEnqueue := captureAccepted && io.lretEnqueueAccepted
  io.recordFromLretEnqueue := recordValidReg && !io.flush && recordFromLretEnqueueReg
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := active && !io.slotOccupied
  io.blockedByNoCompletionClear := active && io.slotOccupied && !io.completionClearSlot
  io.blockedByNoClearIntent := active && io.slotOccupied && io.completionClearSlot && !io.clearIntent
  io.blockedByLiveClearDisabled :=
    active && io.slotOccupied && io.completionClearSlot && io.clearIntent && !io.liveClear
  io.blockedByInvalidPayload := captureCandidate && !payloadValid
  io.blockedByFull := captureDropped
}
