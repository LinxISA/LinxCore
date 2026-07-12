package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

import linxcore.common.LSIDOrder
import linxcore.rob.ROBID

class LoadStoreForwardQuery(
    val robEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val lineAddr = UInt(addrWidth.W)
  val byteOffset = UInt(log2Ceil(lineBytes).W)
  val size = UInt(sizeWidth.W)
  val youngestStoreId = new ROBID(robEntries)
  val youngestStoreLsId = new ROBID(robEntries)
  val youngestStoreLsIdFullValid = Bool()
  val youngestStoreLsIdFull = UInt(lsidWidth.W)
  val isTile = Bool()
}

class LoadStoreForwardStore(
    val robEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val working = Bool()
  val addrReady = Bool()
  val dataReady = Bool()
  val isTile = Bool()
  val storeIndex = UInt(log2Ceil(storeEntries).W)
  val storeId = new ROBID(robEntries)
  val storeLsId = new ROBID(robEntries)
  val storeLsIdFullValid = Bool()
  val storeLsIdFull = UInt(lsidWidth.W)
  val pc = UInt(pcWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}

class LoadStoreForwardWait(
    val robEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val storeIndex = UInt(log2Ceil(storeEntries).W)
  val storeId = new ROBID(robEntries)
  val storeLsId = new ROBID(robEntries)
  val storeLsIdFullValid = Bool()
  val storeLsIdFull = UInt(lsidWidth.W)
  val pc = UInt(pcWidth.W)
}

class LoadStoreForwardingIO(
    val robEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val lsidWidth: Int = 32)
    extends Bundle {
  val query = Input(new LoadStoreForwardQuery(robEntries, addrWidth, lineBytes, sizeWidth, lsidWidth))
  val stores = Input(Vec(storeEntries, new LoadStoreForwardStore(
    robEntries, storeEntries, addrWidth, pcWidth, lineBytes, lsidWidth)))
  val cacheData = Input(UInt((lineBytes * 8).W))

  val loadByteMask = Output(UInt(lineBytes.W))
  val eligibleStoreMask = Output(UInt(storeEntries.W))
  val tileSuppressedMask = Output(UInt(storeEntries.W))
  val fullLsIdMissingMask = Output(UInt(storeEntries.W))
  val fullLsIdAmbiguousMask = Output(UInt(storeEntries.W))
  val coveredMask = Output(UInt(lineBytes.W))
  val forwardMask = Output(UInt(lineBytes.W))
  val waitMask = Output(UInt(lineBytes.W))
  val uncoveredLoadMask = Output(UInt(lineBytes.W))
  val forwardData = Output(UInt((lineBytes * 8).W))
  val mergedData = Output(UInt((lineBytes * 8).W))
  val forwardValid = Output(Bool())
  val storeBypassComplete = Output(Bool())
  val waitStore = Output(new LoadStoreForwardWait(robEntries, storeEntries, pcWidth, lsidWidth))
  val selectedStoreIndexByByte = Output(Vec(lineBytes, UInt(log2Ceil(storeEntries).W)))
}

class LoadStoreForwarding(
    val robEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val lsidWidth: Int = 32)
    extends Module {
  require(robEntries > 1, "ROB entries must be greater than one")
  require((robEntries & (robEntries - 1)) == 0, "ROB entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(addrWidth >= 7, "forwarding needs at least 7 address bits for 64-byte scalar lines")
  require(lineBytes == 64, "LoadStoreForwarding currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new LoadStoreForwardingIO(
    robEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth, lsidWidth))

  private def zeroRobId: ROBID =
    0.U.asTypeOf(new ROBID(robEntries))

  private def storeBeforeOrSame(
      storeBid: ROBID,
      storeLsIdFullValid: Bool,
      storeLsIdFull: UInt,
      loadBid: ROBID,
      loadLsIdFullValid: Bool,
      loadLsIdFull: UInt): Bool =
    ROBID.less(storeBid, loadBid) ||
      (ROBID.equal(storeBid, loadBid) && storeLsIdFullValid && loadLsIdFullValid &&
        LSIDOrder.lessEqual(storeLsIdFull, loadLsIdFull))

  private def storeAfter(
      aBid: ROBID,
      aLsIdFullValid: Bool,
      aLsIdFull: UInt,
      bBid: ROBID,
      bLsIdFullValid: Bool,
      bLsIdFull: UInt): Bool =
    ROBID.less(bBid, aBid) ||
      (ROBID.equal(aBid, bBid) && aLsIdFullValid && bLsIdFullValid &&
        LSIDOrder.less(bLsIdFull, aLsIdFull))

  private def zeroWait: LoadStoreForwardWait = {
    val wait = Wire(new LoadStoreForwardWait(robEntries, storeEntries, pcWidth, lsidWidth))
    wait := 0.U.asTypeOf(wait)
    wait.storeId := zeroRobId
    wait
  }

  private def requestByteMask(offset: UInt, size: UInt): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offsetWide = Wire(UInt(sizeWidth.W))
    offsetWide := offset
    val end = offsetWide +& size
    for (byte <- 0 until lineBytes) {
      val byteIndex = byte.U(end.getWidth.W)
      mask(byte) := io.query.valid && size =/= 0.U && byteIndex >= offsetWide && byteIndex < end
    }
    mask.asUInt
  }

  val loadMask = requestByteMask(io.query.byteOffset, io.query.size)
  val eligibleVec = Wire(Vec(storeEntries, Bool()))
  val tileSuppressedVec = Wire(Vec(storeEntries, Bool()))
  val fullLsIdMissingVec = Wire(Vec(storeEntries, Bool()))
  val fullLsIdAmbiguousVec = Wire(Vec(storeEntries, Bool()))

  for (idx <- 0 until storeEntries) {
    val store = io.stores(idx)
    val sameLine = store.lineAddr === io.query.lineAddr
    val overlapped = (store.byteMask & loadMask).orR
    val scalarEligible = !store.isTile && !io.query.isTile
    val candidateDomain =
      io.query.valid && store.valid && store.working && store.addrReady && sameLine && overlapped && scalarEligible
    val sameBid = ROBID.equal(store.storeId, io.query.youngestStoreId)
    val fullLsIdMissing = sameBid && (!store.storeLsIdFullValid || !io.query.youngestStoreLsIdFullValid)
    val fullLsIdAmbiguous = sameBid && store.storeLsIdFullValid && io.query.youngestStoreLsIdFullValid &&
      LSIDOrder.ambiguous(store.storeLsIdFull, io.query.youngestStoreLsIdFull)
    val ordered = storeBeforeOrSame(
      store.storeId,
      store.storeLsIdFullValid,
      store.storeLsIdFull,
      io.query.youngestStoreId,
      io.query.youngestStoreLsIdFullValid,
      io.query.youngestStoreLsIdFull)
    val baseEligible =
      io.query.valid && store.valid && store.working && store.addrReady && sameLine && overlapped && ordered

    eligibleVec(idx) := baseEligible && scalarEligible
    tileSuppressedVec(idx) := baseEligible && !scalarEligible
    fullLsIdMissingVec(idx) := candidateDomain && fullLsIdMissing
    fullLsIdAmbiguousVec(idx) := candidateDomain && fullLsIdAmbiguous
  }

  val selectedValidByByte = Wire(Vec(lineBytes, Bool()))
  val selectedReadyByByte = Wire(Vec(lineBytes, Bool()))
  val selectedDataByByte = Wire(Vec(lineBytes, UInt(8.W)))
  val selectedStoreIndexByByte = Wire(Vec(lineBytes, UInt(log2Ceil(storeEntries).W)))

  for (byte <- 0 until lineBytes) {
    var selectedValid: Bool = false.B
    var selectedReady: Bool = false.B
    var selectedData: UInt = 0.U(8.W)
    var selectedStoreId: ROBID = zeroRobId
    var selectedStoreLsIdFullValid: Bool = false.B
    var selectedStoreLsIdFull: UInt = 0.U(lsidWidth.W)
    var selectedStoreIndex: UInt = 0.U(log2Ceil(storeEntries).W)

    for (storeIdx <- 0 until storeEntries) {
      val store = io.stores(storeIdx)
      val byteHit = eligibleVec(storeIdx) && store.byteMask(byte)
      val nearerStore = !selectedValid || storeAfter(
        store.storeId,
        store.storeLsIdFullValid,
        store.storeLsIdFull,
        selectedStoreId,
        selectedStoreLsIdFullValid,
        selectedStoreLsIdFull)
      val takeStore = byteHit && nearerStore
      val storeByte = store.data((byte * 8) + 7, byte * 8)

      selectedValid = Mux(takeStore, true.B, selectedValid)
      selectedReady = Mux(takeStore, store.dataReady, selectedReady)
      selectedData = Mux(takeStore && store.dataReady, storeByte, selectedData)
      selectedStoreId = Mux(takeStore, store.storeId, selectedStoreId)
      selectedStoreLsIdFullValid = Mux(takeStore, store.storeLsIdFullValid, selectedStoreLsIdFullValid)
      selectedStoreLsIdFull = Mux(takeStore, store.storeLsIdFull, selectedStoreLsIdFull)
      selectedStoreIndex = Mux(takeStore, store.storeIndex, selectedStoreIndex)
    }

    selectedValidByByte(byte) := selectedValid
    selectedReadyByByte(byte) := selectedReady
    selectedDataByByte(byte) := selectedData
    selectedStoreIndexByByte(byte) := selectedStoreIndex
  }

  val coveredVec = VecInit((0 until lineBytes).map(byte => selectedValidByByte(byte)))
  val forwardVec = VecInit((0 until lineBytes).map(byte => selectedValidByByte(byte) && selectedReadyByByte(byte)))
  val waitVec = VecInit((0 until lineBytes).map(byte => selectedValidByByte(byte) && !selectedReadyByByte(byte)))
  val forwardBytes = Wire(Vec(lineBytes, UInt(8.W)))
  val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))

  for (byte <- 0 until lineBytes) {
    val cacheByte = io.cacheData((byte * 8) + 7, byte * 8)
    forwardBytes(byte) := Mux(forwardVec(byte), selectedDataByByte(byte), 0.U)
    mergedBytes(byte) := Mux(forwardVec(byte), selectedDataByByte(byte), cacheByte)
  }

  val selectedInvalidStoreVec = Wire(Vec(storeEntries, Bool()))
  for (storeIdx <- 0 until storeEntries) {
    selectedInvalidStoreVec(storeIdx) := VecInit((0 until lineBytes).map { byte =>
      loadMask(byte) && waitVec(byte) && (selectedStoreIndexByByte(byte) === io.stores(storeIdx).storeIndex)
    }).asUInt.orR
  }

  var waitValid: Bool = false.B
  var waitStoreIndex: UInt = 0.U(log2Ceil(storeEntries).W)
  var waitStoreId: ROBID = zeroRobId
  var waitStoreLsId: ROBID = zeroRobId
  var waitStoreLsIdFullValid: Bool = false.B
  var waitStoreLsIdFull: UInt = 0.U(lsidWidth.W)
  var waitStorePc: UInt = 0.U(pcWidth.W)
  for (storeIdx <- 0 until storeEntries) {
    val store = io.stores(storeIdx)
    val newerWaitStore = !waitValid || storeAfter(
      store.storeId,
      store.storeLsIdFullValid,
      store.storeLsIdFull,
      waitStoreId,
      waitStoreLsIdFullValid,
      waitStoreLsIdFull)
    val takeWait = selectedInvalidStoreVec(storeIdx) && newerWaitStore

    waitStoreIndex = Mux(takeWait, store.storeIndex, waitStoreIndex)
    waitStoreId = Mux(takeWait, store.storeId, waitStoreId)
    waitStoreLsId = Mux(takeWait, store.storeLsId, waitStoreLsId)
    waitStoreLsIdFullValid = Mux(takeWait, store.storeLsIdFullValid, waitStoreLsIdFullValid)
    waitStoreLsIdFull = Mux(takeWait, store.storeLsIdFull, waitStoreLsIdFull)
    waitStorePc = Mux(takeWait, store.pc, waitStorePc)
    waitValid = waitValid || selectedInvalidStoreVec(storeIdx)
  }

  val waitInfo = Wire(new LoadStoreForwardWait(robEntries, storeEntries, pcWidth, lsidWidth))
  waitInfo := zeroWait
  waitInfo.valid := waitValid
  waitInfo.storeIndex := waitStoreIndex
  waitInfo.storeId := waitStoreId
  waitInfo.storeLsId := waitStoreLsId
  waitInfo.storeLsIdFullValid := waitStoreLsIdFullValid
  waitInfo.storeLsIdFull := waitStoreLsIdFull
  waitInfo.pc := waitStorePc

  val coveredMask = coveredVec.asUInt & loadMask
  val forwardMask = forwardVec.asUInt & loadMask
  val waitMask = waitVec.asUInt & loadMask

  io.loadByteMask := loadMask
  io.eligibleStoreMask := eligibleVec.asUInt
  io.tileSuppressedMask := tileSuppressedVec.asUInt
  io.fullLsIdMissingMask := fullLsIdMissingVec.asUInt
  io.fullLsIdAmbiguousMask := fullLsIdAmbiguousVec.asUInt
  io.coveredMask := coveredMask
  io.forwardMask := forwardMask
  io.waitMask := waitMask
  io.uncoveredLoadMask := loadMask & ~coveredMask
  io.forwardData := Cat(forwardBytes.reverse)
  io.mergedData := Cat(mergedBytes.reverse)
  io.forwardValid := forwardMask.orR
  io.storeBypassComplete := io.query.valid && (loadMask =/= 0.U) && !waitMask.orR && ((forwardMask & loadMask) === loadMask)
  io.waitStore := waitInfo
  io.waitStore.valid := waitMask.orR
  io.selectedStoreIndexByByte := selectedStoreIndexByByte
}
