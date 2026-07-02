package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

import linxcore.rob.ROBID

class ResidentStoreForwardStoreSnapshotIO(
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
  val rows = Input(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, pcWidth)))

  val stores = Output(Vec(entries, new LoadStoreForwardStore(entries, entries, addrWidth, pcWidth, lineBytes)))
  val validMask = Output(UInt(entries.W))
  val waitMask = Output(UInt(entries.W))
  val addrReadyMask = Output(UInt(entries.W))
  val dataReadyMask = Output(UInt(entries.W))
  val scalarMask = Output(UInt(entries.W))
  val crossLineMask = Output(UInt(entries.W))
}

class ResidentStoreForwardStoreSnapshot(
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
  require(entries > 1, "resident store snapshot needs at least two STQ entries")
  require((entries & (entries - 1)) == 0, "resident store snapshot entries must be a power of two")
  require(addrWidth >= 7, "resident store snapshot needs 64-byte line addresses")
  require(dataWidth == 64, "resident store snapshot currently serves 64-bit scalar stores")
  require(sizeWidth >= 4, "resident store snapshot scalar store sizes require at least 4 bits")
  require(lineBytes == 64, "resident store snapshot currently models 64-byte scalar cachelines")

  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new ResidentStoreForwardStoreSnapshotIO(
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

  private def zeroStore: LoadStoreForwardStore = {
    val store = Wire(new LoadStoreForwardStore(entries, entries, addrWidth, pcWidth, lineBytes))
    store := 0.U.asTypeOf(store)
    store.storeId := ROBID.disabled(entries)
    store.storeLsId := ROBID.disabled(entries)
    store
  }

  val validVec = Wire(Vec(entries, Bool()))
  val waitVec = Wire(Vec(entries, Bool()))
  val addrReadyVec = Wire(Vec(entries, Bool()))
  val dataReadyVec = Wire(Vec(entries, Bool()))
  val scalarVec = Wire(Vec(entries, Bool()))
  val crossLineVec = Wire(Vec(entries, Bool()))

  for (idx <- 0 until entries) {
    val row = io.rows(idx)
    val rowWait = row.valid && (row.status === STQEntryStatus.Wait)
    val rowCrosses = crossesLine(row.addr, row.size)
    val byteMask = storeByteMask(row.addr, row.size)
    val store = Wire(new LoadStoreForwardStore(entries, entries, addrWidth, pcWidth, lineBytes))
    store := zeroStore
    store.valid := io.enable && rowWait && !rowCrosses
    store.working := row.status === STQEntryStatus.Wait
    store.addrReady := row.addrReady
    store.dataReady := row.dataReady
    store.isTile := !row.scalarIex
    store.storeIndex := idx.U
    store.storeId := row.bid
    store.storeLsId := row.lsId
    store.pc := row.pc
    store.lineAddr := lineAddr(row.addr)
    store.byteMask := byteMask
    store.data := storeLineData(row.addr, row.data, byteMask)
    io.stores(idx) := store

    validVec(idx) := store.valid
    waitVec(idx) := rowWait
    addrReadyVec(idx) := row.valid && row.addrReady
    dataReadyVec(idx) := row.valid && row.dataReady
    scalarVec(idx) := row.valid && row.scalarIex
    crossLineVec(idx) := rowWait && rowCrosses
  }

  io.validMask := validVec.asUInt
  io.waitMask := waitVec.asUInt
  io.addrReadyMask := addrReadyVec.asUInt
  io.dataReadyMask := dataReadyVec.asUInt
  io.scalarMask := scalarVec.asUInt
  io.crossLineMask := crossLineVec.asUInt
}
