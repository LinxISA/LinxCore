package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

import linxcore.rob.ROBID

class ResidentStoreReplayWakeupIO(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val enable = Input(Bool())
  val waitStore = Input(new LoadStoreForwardWait(entries, entries, pcWidth))
  val rows = Input(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, pcWidth)))

  val wakeValid = Output(Bool())
  val wake = Output(new LoadReplayWakeupRequest(entries, addrWidth, pcWidth, lineBytes))
  val selectedRowValid = Output(Bool())
  val identityMatch = Output(Bool())
  val selectedRowReady = Output(Bool())
  val selectedRowCrossesLine = Output(Bool())
  val wakeByteMask = Output(UInt(lineBytes.W))
}

class ResidentStoreReplayWakeup(
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(entries > 1, "resident replay wakeup needs at least two STQ entries")
  require((entries & (entries - 1)) == 0, "resident replay wakeup entries must be a power of two")
  require(addrWidth >= 7, "resident replay wakeup needs 64-byte line addresses")
  require(dataWidth == 64, "resident replay wakeup currently serves 64-bit scalar stores")
  require(sizeWidth >= 4, "resident replay wakeup scalar store sizes require at least 4 bits")
  require(lineBytes == 64, "resident replay wakeup currently models 64-byte scalar cachelines")

  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new ResidentStoreReplayWakeupIO(
    entries,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth,
    simtLaneWidth,
    mapQDepth,
    pcWidth,
    lineBytes
  ))

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, offsetWidth), 0.U(offsetWidth.W))

  private def crossesLine(addr: UInt, size: UInt): Bool = {
    val offset = Wire(UInt(7.W))
    val sizeWide = Wire(UInt(7.W))
    offset := addr(offsetWidth - 1, 0)
    sizeWide := size
    (offset +& sizeWide) > lineBytes.U
  }

  private def storeByteMask(addr: UInt, size: UInt): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(7.W))
    val sizeWide = Wire(UInt(7.W))
    offset := addr(offsetWidth - 1, 0)
    sizeWide := size
    val end = offset +& sizeWide
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      mask(byte) := (sizeWide =/= 0.U) && (byteIdx >= offset) && (byteIdx < end)
    }
    mask.asUInt
  }

  private def storeLineData(addr: UInt, data: UInt, byteMask: UInt): UInt = {
    val bytes = Wire(Vec(lineBytes, UInt(8.W)))
    val offset = Wire(UInt(7.W))
    offset := addr(offsetWidth - 1, 0)
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      val reqByteOffset = byteIdx - offset
      bytes(byte) := Mux(byteMask(byte), (data >> (reqByteOffset << 3))(7, 0), 0.U)
    }
    Cat(bytes.reverse)
  }

  val row = io.rows(io.waitStore.storeIndex)
  val selectedRowValid =
    io.enable &&
      io.waitStore.valid &&
      row.valid &&
      (row.status === STQEntryStatus.Wait)
  val identityMatch =
    selectedRowValid &&
      ROBID.equal(row.bid, io.waitStore.storeId) &&
      ROBID.equal(row.lsId, io.waitStore.storeLsId) &&
      (row.pc === io.waitStore.pc)
  val rowCrossesLine = crossesLine(row.addr, row.size)
  val rowReady =
    identityMatch &&
      row.addrReady &&
      row.dataReady &&
      row.scalarIex &&
      !rowCrossesLine
  val byteMask = storeByteMask(row.addr, row.size)
  val wakeValid = rowReady && byteMask.orR

  val wake = Wire(new LoadReplayWakeupRequest(entries, addrWidth, pcWidth, lineBytes))
  wake := 0.U.asTypeOf(wake)
  wake.source := LoadReplayWakeSource.StoreUnit
  wake.storeId := io.waitStore.storeId
  wake.storeLsId := io.waitStore.storeLsId
  wake.pc := io.waitStore.pc
  wake.lineAddr := lineAddr(row.addr)
  wake.validMask := byteMask
  wake.data := storeLineData(row.addr, row.data, byteMask)

  io.wakeValid := wakeValid
  io.wake := wake
  io.selectedRowValid := selectedRowValid
  io.identityMatch := identityMatch
  io.selectedRowReady := rowReady
  io.selectedRowCrossesLine := rowCrossesLine
  io.wakeByteMask := Mux(wakeValid, byteMask, 0.U)
}
