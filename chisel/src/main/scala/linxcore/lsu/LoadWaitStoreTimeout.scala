package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PriorityEncoder}

import linxcore.rob.ROBID

class LoadWaitStoreTimeoutIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val timeoutCycles: Int)
    extends Bundle {
  private val indexWidth = log2Ceil(liqEntries)
  private val ageWidth = log2Ceil(timeoutCycles + 1).max(1)

  val flush = Input(Bool())
  val rows = Input(Vec(
    liqEntries,
    new LoadInflightRow(
      liqEntries,
      idEntries,
      storeEntries,
      addrWidth,
      pcWidth,
      lineBytes,
      sizeWidth,
      archRegWidth,
      physRegWidth,
      peIdWidth,
      stidWidth,
      tidWidth
    )
  ))
  val releaseAccepted = Input(Bool())

  val activeMask = Output(UInt(liqEntries.W))
  val expiredMask = Output(UInt(liqEntries.W))
  val releaseValid = Output(Bool())
  val releaseIndex = Output(UInt(indexWidth.W))
  val releaseAge = Output(UInt(ageWidth.W))
}

class LoadWaitStoreTimeout(
    val liqEntries: Int = 16,
    val idEntries: Int = 128,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 7,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val timeoutCycles: Int = 300)
    extends Module {
  require(liqEntries > 1 && (liqEntries & (liqEntries - 1)) == 0,
    "LIQ entries must be a power of two greater than one")
  require(idEntries > 1 && (idEntries & (idEntries - 1)) == 0,
    "identity entries must be a power of two greater than one")
  require(storeEntries > 1 && (storeEntries & (storeEntries - 1)) == 0,
    "store entries must be a power of two greater than one")
  require(timeoutCycles > 0, "failed-wait timeout must be positive")

  private val ageWidth = log2Ceil(timeoutCycles + 1).max(1)
  private val timeoutAge = (timeoutCycles - 1).U(ageWidth.W)

  val io = IO(new LoadWaitStoreTimeoutIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    timeoutCycles
  ))

  val observed = RegInit(VecInit(Seq.fill(liqEntries)(false.B)))
  val observedLoadId = Reg(Vec(liqEntries, new ROBID(liqEntries)))
  val observedStoreBid = Reg(Vec(liqEntries, new ROBID(idEntries)))
  val observedStoreLsId = Reg(Vec(liqEntries, new ROBID(idEntries)))
  val observedStorePc = Reg(Vec(liqEntries, UInt(pcWidth.W)))
  val ages = RegInit(VecInit(Seq.fill(liqEntries)(0.U(ageWidth.W))))

  val active = Wire(Vec(liqEntries, Bool()))
  val sameWait = Wire(Vec(liqEntries, Bool()))
  val expired = Wire(Vec(liqEntries, Bool()))

  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    val waitState =
      (row.status === LoadInflightStatus.Wait) ||
        (row.status === LoadInflightStatus.Repick)
    active(idx) :=
      row.valid &&
        !row.isTile &&
        waitState &&
        row.waitStore &&
        row.waitStoreInfo.valid
    sameWait(idx) :=
      observed(idx) &&
        ROBID.equal(observedLoadId(idx), row.loadId) &&
        ROBID.equal(observedStoreBid(idx), row.waitStoreInfo.storeId) &&
        ROBID.equal(observedStoreLsId(idx), row.waitStoreInfo.storeLsId) &&
        (observedStorePc(idx) === row.waitStoreInfo.pc)
    expired(idx) := active(idx) && sameWait(idx) && (ages(idx) >= timeoutAge)
  }

  val expiredMask = expired.asUInt
  val releaseIndex = PriorityEncoder(expiredMask)

  when(io.flush) {
    for (idx <- 0 until liqEntries) {
      observed(idx) := false.B
      observedLoadId(idx) := 0.U.asTypeOf(observedLoadId(idx))
      observedStoreBid(idx) := 0.U.asTypeOf(observedStoreBid(idx))
      observedStoreLsId(idx) := 0.U.asTypeOf(observedStoreLsId(idx))
      observedStorePc(idx) := 0.U
      ages(idx) := 0.U
    }
  }.otherwise {
    for (idx <- 0 until liqEntries) {
      val row = io.rows(idx)
      when(io.releaseAccepted && (releaseIndex === idx.U)) {
        observed(idx) := false.B
        ages(idx) := 0.U
      }.elsewhen(!active(idx)) {
        observed(idx) := false.B
        ages(idx) := 0.U
      }.elsewhen(!sameWait(idx)) {
        observed(idx) := true.B
        observedLoadId(idx) := row.loadId
        observedStoreBid(idx) := row.waitStoreInfo.storeId
        observedStoreLsId(idx) := row.waitStoreInfo.storeLsId
        observedStorePc(idx) := row.waitStoreInfo.pc
        ages(idx) := 0.U
      }.elsewhen(ages(idx) < timeoutAge) {
        ages(idx) := ages(idx) + 1.U
      }
    }
  }

  io.activeMask := active.asUInt
  io.expiredMask := expiredMask
  io.releaseValid := expiredMask.orR && !io.flush
  io.releaseIndex := releaseIndex
  io.releaseAge := Mux(io.releaseValid, ages(releaseIndex), 0.U)
}
