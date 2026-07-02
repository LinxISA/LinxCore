package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

import linxcore.rob.ROBID

class ReducedStoreResidentForwardIO(
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
  val loadValid = Input(Bool())
  val loadAddr = Input(UInt(addrWidth.W))
  val loadSize = Input(UInt(sizeWidth.W))
  val loadBid = Input(new ROBID(entries))
  val loadLsId = Input(new ROBID(entries))
  val baseLoadData = Input(UInt(dataWidth.W))
  val rows = Input(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth)))

  val loadData = Output(UInt(dataWidth.W))
  val loadForwardMask = Output(UInt((dataWidth / 8).W))
  val waitMask = Output(UInt((dataWidth / 8).W))
  val eligibleStoreMask = Output(UInt(entries.W))
  val waitStore = Output(new LoadStoreForwardWait(entries, entries, pcWidth))
  val readyForward = Output(Bool())
  val waitBlocked = Output(Bool())
  val loadCrossesLine = Output(Bool())
}

class ReducedStoreResidentForward(
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
  require(entries > 1, "resident store forwarding needs at least two STQ entries")
  require((entries & (entries - 1)) == 0, "resident store forwarding STQ entries must be a power of two")
  require(addrWidth >= 7, "resident store forwarding needs 64-byte line addresses")
  require(dataWidth == 64, "resident store forwarding currently serves 64-bit scalar load lookups")
  require(sizeWidth >= 4, "resident store forwarding scalar load/store sizes require at least 4 bits")
  require(lineBytes == 64, "resident store forwarding currently models 64-byte scalar cachelines")

  private val loadBytes = dataWidth / 8
  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new ReducedStoreResidentForwardIO(
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

  private def baseLineData(loadAddr: UInt, baseLoadData: UInt): UInt = {
    val bytes = Wire(Vec(lineBytes, UInt(8.W)))
    for (byte <- 0 until lineBytes) {
      bytes(byte) := 0.U
    }
    val offset = Wire(UInt(7.W))
    offset := loadAddr(offsetWidth - 1, 0)
    for (loadByte <- 0 until loadBytes) {
      val target = offset + loadByte.U(7.W)
      for (byte <- 0 until lineBytes) {
        when(target === byte.U) {
          bytes(byte) := baseLoadData((loadByte * 8) + 7, loadByte * 8)
        }
      }
    }
    Cat(bytes.reverse)
  }

  val loadCrosses = crossesLine(io.loadAddr, io.loadSize)
  val queryValid = io.enable && io.loadValid && (io.loadSize =/= 0.U) && !loadCrosses
  val storeSnapshot = Module(new ResidentStoreForwardStoreSnapshot(
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
  val forward = Module(new LoadStoreForwarding(entries, entries, addrWidth, pcWidth, lineBytes, sizeWidth = 7))

  storeSnapshot.io.enable := io.enable
  storeSnapshot.io.rows := io.rows

  forward.io.query.valid := queryValid
  forward.io.query.lineAddr := lineAddr(io.loadAddr)
  forward.io.query.byteOffset := io.loadAddr(offsetWidth - 1, 0)
  forward.io.query.size := io.loadSize
  forward.io.query.youngestStoreId := io.loadBid
  forward.io.query.youngestStoreLsId := io.loadLsId
  forward.io.query.isTile := false.B
  forward.io.cacheData := baseLineData(io.loadAddr, io.baseLoadData)

  for (idx <- 0 until entries) {
    forward.io.stores(idx) := storeSnapshot.io.stores(idx)
  }

  val loadOffset = Wire(UInt(7.W))
  loadOffset := io.loadAddr(offsetWidth - 1, 0)
  val outBytes = Wire(Vec(loadBytes, UInt(8.W)))
  val forwardVec = Wire(Vec(loadBytes, Bool()))
  val waitVec = Wire(Vec(loadBytes, Bool()))
  for (byte <- 0 until loadBytes) {
    val lineByte = loadOffset + byte.U(7.W)
    outBytes(byte) := (forward.io.mergedData >> (lineByte << 3))(7, 0)
    forwardVec(byte) := (forward.io.forwardMask >> lineByte)(0)
    waitVec(byte) := (forward.io.waitMask >> lineByte)(0)
  }

  val windowForwardMask = forwardVec.asUInt
  val windowWaitMask = waitVec.asUInt
  val waitBlocked = queryValid && windowWaitMask.orR
  val readyForward = queryValid && !waitBlocked && windowForwardMask.orR

  io.loadData := Mux(queryValid && !waitBlocked, Cat(outBytes.reverse), io.baseLoadData)
  io.loadForwardMask := Mux(queryValid && !waitBlocked, windowForwardMask, 0.U)
  io.waitMask := Mux(queryValid, windowWaitMask, 0.U)
  io.eligibleStoreMask := forward.io.eligibleStoreMask
  io.waitStore := forward.io.waitStore
  io.readyForward := readyForward
  io.waitBlocked := waitBlocked
  io.loadCrossesLine := io.enable && io.loadValid && loadCrosses
}
