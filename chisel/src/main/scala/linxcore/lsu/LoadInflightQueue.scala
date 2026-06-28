package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.rob.ROBID

object LoadInflightStatus extends ChiselEnum {
  val Idle, Wait, Repick, L1DcMiss, L2Wait, Resolved = Value
}

class LoadInflightAlloc(
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val youngestStoreId = new ROBID(idEntries)
  val isTile = Bool()
  val specWakeup = Bool()
  val stackValid = Bool()
}

class LoadInflightRow(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val status = LoadInflightStatus()
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val youngestStoreId = new ROBID(idEntries)
  val isTile = Bool()
  val specWakeup = Bool()
  val stackValid = Bool()

  val lineData = UInt((lineBytes * 8).W)
  val validMask = UInt(lineBytes.W)
  val loadByteMask = UInt(lineBytes.W)
  val forwardMask = UInt(lineBytes.W)
  val waitMask = UInt(lineBytes.W)

  val waitStore = Bool()
  val waitStoreInfo = new LoadStoreForwardWait(idEntries, storeEntries, pcWidth)
  val storeBypass = Bool()
  val dataComplete = Bool()
  val sourcesReturned = Bool()
  val l1Hit = Bool()
  val l1Miss = Bool()
  val missKind = LoadForwardMissKind()
}

class LoadHitRecord(
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val addr = UInt(addrWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
  val forwardedMask = UInt(lineBytes.W)
}

class LoadInflightQueueIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)

  val flush = Input(Bool())

  val allocValid = Input(Bool())
  val alloc = Input(new LoadInflightAlloc(liqEntries, idEntries, addrWidth, pcWidth, sizeWidth))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(liqPtrWidth.W))
  val allocLoadId = Output(new ROBID(liqEntries))

  val launchValid = Input(Bool())
  val launchIndex = Input(UInt(liqPtrWidth.W))
  val launchReady = Output(Bool())
  val launchAccepted = Output(Bool())

  val e2Stores = Input(Vec(storeEntries, new LoadStoreForwardStore(idEntries, storeEntries, addrWidth, pcWidth, lineBytes)))
  val e2BaseData = Input(UInt((lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val replayWakeValid = Input(Bool())
  val replayWake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes))
  val replayWakeWaitStoreClearMask = Output(UInt(liqEntries.W))
  val replayWakeMergeMask = Output(UInt(liqEntries.W))
  val replayWakeCompletedMask = Output(UInt(liqEntries.W))

  val refillValid = Input(Bool())
  val refill = Input(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val refillAccepted = Output(Bool())
  val refillWakeMask = Output(UInt(liqEntries.W))

  val clearResolvedValid = Input(Bool())
  val clearResolvedIndex = Input(UInt(liqPtrWidth.W))
  val clearResolvedAccepted = Output(Bool())

  val e4UpdateValid = Output(Bool())
  val e4UpdateIndex = Output(UInt(liqPtrWidth.W))
  val e4MissKind = Output(LoadForwardMissKind())
  val e4WakeupValid = Output(Bool())

  val lhqRecordValid = Output(Bool())
  val lhqRecord = Output(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))

  val rows = Output(Vec(liqEntries, new LoadInflightRow(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth)))
  val occupiedMask = Output(UInt(liqEntries.W))
  val waitMask = Output(UInt(liqEntries.W))
  val repickMask = Output(UInt(liqEntries.W))
  val missMask = Output(UInt(liqEntries.W))
  val resolvedMask = Output(UInt(liqEntries.W))
  val waitStoreMask = Output(UInt(liqEntries.W))
  val residentCount = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val missPending = Output(Bool())
}

class LoadInflightQueue(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "LoadInflightQueue currently models 64-byte scalar cachelines")
  require(addrWidth >= 7, "LoadInflightQueue needs 64-byte line addresses")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)
  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadInflightQueueIO(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))

  private def zeroRow: LoadInflightRow = {
    val row = Wire(new LoadInflightRow(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
    row := 0.U.asTypeOf(row)
    row.status := LoadInflightStatus.Idle
    row.missKind := LoadForwardMissKind.NoMiss
    row.waitStoreInfo := zeroWait
    row
  }

  private def zeroHitRecord: LoadHitRecord =
    0.U.asTypeOf(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))

  private def currentLoadId: ROBID = {
    val id = Wire(new ROBID(liqEntries))
    id.valid := true.B
    id.wrap := allocWrap
    id.value := allocPtr
    id
  }

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  val rows = RegInit(VecInit(Seq.fill(liqEntries)(zeroRow)))
  val allocPtr = RegInit(0.U(liqPtrWidth.W))
  val allocWrap = RegInit(false.B)
  val residentCount = RegInit(0.U(countWidth.W))

  val pipeline = Module(new LoadForwardPipeline(idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  pipeline.io.flush := io.flush
  pipeline.io.e2Stores := io.e2Stores
  pipeline.io.e2LoadDataReturned := io.e2LoadDataReturned
  pipeline.io.e2ScbReturned := io.e2ScbReturned
  pipeline.io.e2ReturnReady := io.e2ReturnReady

  val launchRow = rows(io.launchIndex)
  val launchReady =
    !io.flush && launchRow.valid && (launchRow.status === LoadInflightStatus.Wait) && !launchRow.waitStore
  val launchAccepted = io.launchValid && launchReady
  val launchUsesRowData = launchRow.validMask.orR

  pipeline.io.e2BaseData := Mux(launchUsesRowData, launchRow.lineData, io.e2BaseData)
  pipeline.io.e2BaseValidMask := Mux(launchUsesRowData, launchRow.validMask, io.e2BaseValidMask)

  val query = Wire(new LoadStoreForwardQuery(idEntries, addrWidth, lineBytes, sizeWidth))
  query := 0.U.asTypeOf(query)
  query.valid := launchAccepted
  query.lineAddr := lineAddr(launchRow.addr)
  query.byteOffset := launchRow.addr(lineOffsetWidth - 1, 0)
  query.size := launchRow.size
  query.youngestStoreId := launchRow.youngestStoreId
  query.isTile := launchRow.isTile

  pipeline.io.e2Valid := launchAccepted
  pipeline.io.e2Query := query

  val replayWakeup = Module(new LoadReplayWakeup(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  replayWakeup.io.wakeValid := io.replayWakeValid && !io.flush
  replayWakeup.io.wake := io.replayWake
  replayWakeup.io.rows := rows

  val refillWakeup = Module(new LoadRefillWakeup(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  refillWakeup.io.refillValid := io.refillValid && !io.flush
  refillWakeup.io.refill := io.refill
  refillWakeup.io.rows := rows

  val e3IndexValid = RegInit(false.B)
  val e3Index = RegInit(0.U(liqPtrWidth.W))
  val e4IndexValid = RegInit(false.B)
  val e4Index = RegInit(0.U(liqPtrWidth.W))

  val allocLoadId = currentLoadId
  val allocReady = !io.flush && !rows(allocPtr).valid
  val allocAccepted = io.allocValid && allocReady
  val clearResolvedReady =
    !io.flush && rows(io.clearResolvedIndex).valid && (rows(io.clearResolvedIndex).status === LoadInflightStatus.Resolved)
  val clearResolvedAccepted = io.clearResolvedValid && clearResolvedReady

  val e4UpdateValid =
    e4IndexValid && pipeline.io.e4Valid && rows(e4Index).valid && (rows(e4Index).status === LoadInflightStatus.Repick)
  val e4Resolved = e4UpdateValid && pipeline.io.e4WakeupValid && (pipeline.io.e4MissKind === LoadForwardMissKind.NoMiss)
  val e4StoreWait = e4UpdateValid && (pipeline.io.e4MissKind === LoadForwardMissKind.StoreDataNotReady)
  val e4DataMiss = e4UpdateValid && (pipeline.io.e4MissKind === LoadForwardMissKind.DataNotComplete)
  val e4ReplayWait =
    e4UpdateValid &&
      ((pipeline.io.e4MissKind === LoadForwardMissKind.AwaitingSources) ||
        (pipeline.io.e4MissKind === LoadForwardMissKind.ReturnPortBlocked))

  val lhqRecord = Wire(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))
  lhqRecord := zeroHitRecord
  lhqRecord.loadId := rows(e4Index).loadId
  lhqRecord.bid := rows(e4Index).bid
  lhqRecord.gid := rows(e4Index).gid
  lhqRecord.rid := rows(e4Index).rid
  lhqRecord.addr := rows(e4Index).addr
  lhqRecord.lineAddr := lineAddr(rows(e4Index).addr)
  lhqRecord.size := rows(e4Index).size
  lhqRecord.byteMask := pipeline.io.e4LoadByteMask
  lhqRecord.data := pipeline.io.e4LineData
  lhqRecord.forwardedMask := pipeline.io.e4ForwardMask

  when(io.flush) {
    for (idx <- 0 until liqEntries) {
      rows(idx) := zeroRow
    }
    residentCount := 0.U
    allocPtr := 0.U
    allocWrap := false.B
    e3IndexValid := false.B
    e4IndexValid := false.B
  }.otherwise {
    e4IndexValid := e3IndexValid
    e4Index := e3Index
    e3IndexValid := launchAccepted
    e3Index := io.launchIndex

    when(e4UpdateValid) {
      rows(e4Index).lineData := pipeline.io.e4LineData
      rows(e4Index).validMask := pipeline.io.e4ValidMask
      rows(e4Index).loadByteMask := pipeline.io.e4LoadByteMask
      rows(e4Index).forwardMask := pipeline.io.e4ForwardMask
      rows(e4Index).waitMask := pipeline.io.e4WaitMask
      rows(e4Index).dataComplete := pipeline.io.e4DataComplete
      rows(e4Index).sourcesReturned := pipeline.io.e4SourcesReturned
      rows(e4Index).missKind := pipeline.io.e4MissKind
      rows(e4Index).storeBypass := pipeline.io.e4ForwardMask.orR

      when(e4Resolved) {
        rows(e4Index).status := LoadInflightStatus.Resolved
        rows(e4Index).waitStore := false.B
        rows(e4Index).waitStoreInfo := zeroWait
        rows(e4Index).l1Hit := false.B
        rows(e4Index).l1Miss := false.B
      }.elsewhen(e4StoreWait) {
        rows(e4Index).status := LoadInflightStatus.Wait
        rows(e4Index).waitStore := true.B
        rows(e4Index).waitStoreInfo := pipeline.io.e4WaitStore
        rows(e4Index).validMask := 0.U
        rows(e4Index).loadByteMask := 0.U
        rows(e4Index).forwardMask := 0.U
        rows(e4Index).waitMask := 0.U
        rows(e4Index).dataComplete := false.B
        rows(e4Index).sourcesReturned := false.B
        rows(e4Index).l1Hit := false.B
      }.elsewhen(e4DataMiss) {
        rows(e4Index).status := LoadInflightStatus.L1DcMiss
        rows(e4Index).waitStore := false.B
        rows(e4Index).waitStoreInfo := zeroWait
        rows(e4Index).validMask := 0.U
        rows(e4Index).loadByteMask := 0.U
        rows(e4Index).forwardMask := 0.U
        rows(e4Index).waitMask := 0.U
        rows(e4Index).dataComplete := false.B
        rows(e4Index).sourcesReturned := false.B
        rows(e4Index).l1Hit := false.B
        rows(e4Index).l1Miss := true.B
      }.elsewhen(e4ReplayWait) {
        rows(e4Index).status := LoadInflightStatus.Wait
        rows(e4Index).waitStore := false.B
        rows(e4Index).waitStoreInfo := zeroWait
        rows(e4Index).validMask := 0.U
        rows(e4Index).loadByteMask := 0.U
        rows(e4Index).forwardMask := 0.U
        rows(e4Index).waitMask := 0.U
        rows(e4Index).dataComplete := false.B
        rows(e4Index).sourcesReturned := false.B
        rows(e4Index).l1Hit := false.B
      }
    }

    when(clearResolvedAccepted) {
      rows(io.clearResolvedIndex) := zeroRow
    }

    when(io.replayWakeValid) {
      for (idx <- 0 until liqEntries) {
        when(replayWakeup.io.waitStoreClearMask(idx)) {
          rows(idx).waitStore := false.B
          rows(idx).waitStoreInfo := zeroWait
        }

        when(replayWakeup.io.mergeMask(idx)) {
          rows(idx).lineData := replayWakeup.io.mergedLineData(idx)
          rows(idx).validMask := replayWakeup.io.mergedValidMasks(idx)
          rows(idx).loadByteMask := replayWakeup.io.requestByteMasks(idx)
          when(replayWakeup.io.completedMask(idx)) {
            rows(idx).status := LoadInflightStatus.Wait
            rows(idx).storeBypass := true.B
            rows(idx).dataComplete := true.B
            rows(idx).sourcesReturned := true.B
            rows(idx).missKind := LoadForwardMissKind.NoMiss
          }
        }
      }
    }

    when(io.refillValid) {
      for (idx <- 0 until liqEntries) {
        val sameRowResolvedAtE4 = e4UpdateValid && e4Resolved && (e4Index === idx.U)
        when(refillWakeup.io.wakeMask(idx) && !sameRowResolvedAtE4) {
          rows(idx).status := LoadInflightStatus.Wait
          rows(idx).lineData := io.refill.data
          rows(idx).validMask := refillWakeup.io.lineValidMask
          rows(idx).loadByteMask := refillWakeup.io.requestByteMasks(idx)
          rows(idx).forwardMask := 0.U
          rows(idx).waitMask := 0.U
          rows(idx).l1Hit := true.B
          rows(idx).dataComplete := false.B
          rows(idx).sourcesReturned := false.B
          rows(idx).missKind := LoadForwardMissKind.NoMiss
        }
      }
    }

    when(launchAccepted) {
      rows(io.launchIndex).status := LoadInflightStatus.Repick
      rows(io.launchIndex).waitStore := false.B
      rows(io.launchIndex).missKind := LoadForwardMissKind.NoMiss
    }

    when(allocAccepted) {
      rows(allocPtr) := zeroRow
      rows(allocPtr).valid := true.B
      rows(allocPtr).status := LoadInflightStatus.Wait
      rows(allocPtr).loadId := allocLoadId
      rows(allocPtr).bid := io.alloc.bid
      rows(allocPtr).gid := io.alloc.gid
      rows(allocPtr).rid := io.alloc.rid
      rows(allocPtr).pc := io.alloc.pc
      rows(allocPtr).addr := io.alloc.addr
      rows(allocPtr).size := io.alloc.size
      rows(allocPtr).youngestStoreId := io.alloc.youngestStoreId
      rows(allocPtr).isTile := io.alloc.isTile
      rows(allocPtr).specWakeup := io.alloc.specWakeup
      rows(allocPtr).stackValid := io.alloc.stackValid

      when(allocPtr === (liqEntries - 1).U) {
        allocPtr := 0.U
        allocWrap := !allocWrap
      }.otherwise {
        allocPtr := allocPtr + 1.U
      }
    }

    residentCount := residentCount + allocAccepted.asUInt - clearResolvedAccepted.asUInt
  }

  val occupiedVec = Wire(Vec(liqEntries, Bool()))
  val waitVec = Wire(Vec(liqEntries, Bool()))
  val repickVec = Wire(Vec(liqEntries, Bool()))
  val missVec = Wire(Vec(liqEntries, Bool()))
  val resolvedVec = Wire(Vec(liqEntries, Bool()))
  val waitStoreVec = Wire(Vec(liqEntries, Bool()))
  for (idx <- 0 until liqEntries) {
    val status = rows(idx).status
    occupiedVec(idx) := rows(idx).valid
    waitVec(idx) := rows(idx).valid && (status === LoadInflightStatus.Wait)
    repickVec(idx) := rows(idx).valid && (status === LoadInflightStatus.Repick)
    missVec(idx) := rows(idx).valid && ((status === LoadInflightStatus.L1DcMiss) || (status === LoadInflightStatus.L2Wait))
    resolvedVec(idx) := rows(idx).valid && (status === LoadInflightStatus.Resolved)
    waitStoreVec(idx) := rows(idx).valid && rows(idx).waitStore
    io.rows(idx) := rows(idx)
  }

  io.allocReady := allocReady
  io.allocAccepted := allocAccepted
  io.allocIndex := allocPtr
  io.allocLoadId := allocLoadId
  io.launchReady := launchReady
  io.launchAccepted := launchAccepted
  io.clearResolvedAccepted := clearResolvedAccepted
  io.replayWakeWaitStoreClearMask := replayWakeup.io.waitStoreClearMask
  io.replayWakeMergeMask := replayWakeup.io.mergeMask
  io.replayWakeCompletedMask := replayWakeup.io.completedMask
  io.refillAccepted := refillWakeup.io.refillAccepted
  io.refillWakeMask := refillWakeup.io.wakeMask
  io.e4UpdateValid := e4UpdateValid
  io.e4UpdateIndex := e4Index
  io.e4MissKind := pipeline.io.e4MissKind
  io.e4WakeupValid := pipeline.io.e4WakeupValid
  io.lhqRecordValid := e4Resolved
  io.lhqRecord := lhqRecord
  io.occupiedMask := occupiedVec.asUInt
  io.waitMask := waitVec.asUInt
  io.repickMask := repickVec.asUInt
  io.missMask := missVec.asUInt
  io.resolvedMask := resolvedVec.asUInt
  io.waitStoreMask := waitStoreVec.asUInt
  io.residentCount := residentCount
  io.empty := residentCount === 0.U
  io.full := residentCount === liqEntries.U
  io.missPending := missVec.asUInt.orR || e4DataMiss
}
