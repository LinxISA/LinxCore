package linxcore.lsu

import chisel3._
import linxcore.rob.ROBID

class ReducedLoadWaitReplaySlotIO(
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  private val slotEntries = 2

  val flush = Input(Bool())
  val captureValid = Input(Bool())
  val capturePc = Input(UInt(pcWidth.W))
  val captureAddr = Input(UInt(addrWidth.W))
  val captureSize = Input(UInt(sizeWidth.W))
  val captureBid = Input(new ROBID(idEntries))
  val captureLsId = Input(new ROBID(idEntries))
  val captureWaitStore = Input(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))

  val replayWakeValid = Input(Bool())
  val replayWake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes))

  val active = Output(Bool())
  val captureAccepted = Output(Bool())
  val waitStoreClear = Output(Bool())
  val waitStoreClearMask = Output(UInt(slotEntries.W))
  val storedWaitStore = Output(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
  val slotPc = Output(UInt(pcWidth.W))
  val slotAddr = Output(UInt(addrWidth.W))
}

class ReducedLoadWaitReplaySlot(
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  private val slotEntries = 2

  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(addrWidth >= 7, "ReducedLoadWaitReplaySlot needs 64-byte line addresses")
  require(lineBytes == 64, "ReducedLoadWaitReplaySlot currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new ReducedLoadWaitReplaySlotIO(idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))

  private def zeroWait: LoadStoreForwardWait = {
    val wait = Wire(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
    wait := 0.U.asTypeOf(wait)
    wait.storeId := ROBID.disabled(idEntries)
    wait.storeLsId := ROBID.disabled(idEntries)
    wait
  }

  private def zeroRow: LoadInflightRow = {
    val row = Wire(new LoadInflightRow(slotEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
    row := 0.U.asTypeOf(row)
    row.status := LoadInflightStatus.Idle
    row.loadId := ROBID.disabled(slotEntries)
    row.bid := ROBID.disabled(idEntries)
    row.gid := ROBID.disabled(idEntries)
    row.rid := ROBID.disabled(idEntries)
    row.youngestStoreId := ROBID.disabled(idEntries)
    row.youngestStoreLsId := ROBID.disabled(idEntries)
    row.waitStoreInfo := zeroWait
    row.missKind := LoadForwardMissKind.NoMiss
    row
  }

  private def capturedRow: LoadInflightRow = {
    val row = Wire(new LoadInflightRow(slotEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
    row := zeroRow
    row.valid := true.B
    row.status := LoadInflightStatus.Wait
    row.loadId := ROBID.zero(slotEntries)
    row.bid := io.captureBid
    row.pc := io.capturePc
    row.addr := io.captureAddr
    row.size := io.captureSize
    row.youngestStoreId := io.captureBid
    row.youngestStoreLsId := io.captureLsId
    row.waitStore := true.B
    row.waitStoreInfo := io.captureWaitStore
    row
  }

  val activeReg = RegInit(false.B)
  val slotReg = RegInit(zeroRow)
  val emptyRow = zeroRow

  val rows = Wire(Vec(
    slotEntries,
    new LoadInflightRow(slotEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth)
  ))
  rows(0) := slotReg
  rows(1) := emptyRow

  val replay = Module(new LoadReplayWakeup(slotEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  replay.io.wakeValid := io.replayWakeValid && activeReg && !io.flush
  replay.io.wake := io.replayWake
  replay.io.rows := rows

  val captureAccepted = io.captureValid && io.captureWaitStore.valid && !io.flush
  val waitStoreClear = activeReg && replay.io.waitStoreClearMask(0)

  when(io.flush) {
    activeReg := false.B
    slotReg := zeroRow
  }.elsewhen(captureAccepted) {
    activeReg := true.B
    slotReg := capturedRow
  }.elsewhen(waitStoreClear) {
    activeReg := false.B
    slotReg := zeroRow
  }

  val storedWait = Wire(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
  storedWait := zeroWait
  when(activeReg) {
    storedWait := slotReg.waitStoreInfo
  }

  io.active := activeReg
  io.captureAccepted := captureAccepted
  io.waitStoreClear := waitStoreClear
  io.waitStoreClearMask := replay.io.waitStoreClearMask
  io.storedWaitStore := storedWait
  io.slotPc := slotReg.pc
  io.slotAddr := slotReg.addr
}
