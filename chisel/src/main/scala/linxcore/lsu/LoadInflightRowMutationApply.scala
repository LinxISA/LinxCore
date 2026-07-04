package linxcore.lsu

import chisel3._

class LoadInflightRowMutationApplyIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int,
    val archRegWidth: Int,
    val physRegWidth: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestValid = Input(Bool())
  val row = Input(new LoadInflightRow(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val setWaitStatus = Input(Bool())
  val keepRepickStatus = Input(Bool())
  val clearReturnState = Input(Bool())
  val lineWrite = Input(Bool())
  val waitStoreWrite = Input(Bool())
  val nextWaitStore = Input(Bool())
  val nextWaitStoreInfo = Input(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
  val nextLineData = Input(UInt((lineBytes * 8).W))
  val nextValidMask = Input(UInt(lineBytes.W))
  val nextDataComplete = Input(Bool())
  val nextScbReturned = Input(Bool())
  val nextStqReturned = Input(Bool())
  val nextStoreSourceReturned = Input(Bool())

  val active = Output(Bool())
  val applyValid = Output(Bool())
  val nextRow = Output(new LoadInflightRow(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRequest = Output(Bool())
  val blockedByInvalidRow = Output(Bool())
  val blockedByNotRepick = Output(Bool())
  val invalidConflictingStatusWrite = Output(Bool())
  val invalidWaitStoreWithoutWaitStatus = Output(Bool())
  val invalidReturnWithoutSplitSources = Output(Bool())
}

class LoadInflightRowMutationApply(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "LoadInflightRowMutationApply currently models 64-byte scalar cachelines")

  val io = IO(new LoadInflightRowMutationApplyIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))

  val active = io.enable && !io.flush
  val requestActive = active && io.requestValid
  val rowRepick = io.row.valid && (io.row.status === LoadInflightStatus.Repick)
  val validShape =
    !(io.setWaitStatus && io.keepRepickStatus) &&
      !(io.nextWaitStore && !io.setWaitStatus) &&
      !(io.nextStoreSourceReturned && !(io.nextScbReturned && io.nextStqReturned))
  val applyValid = requestActive && rowRepick && validShape

  val next = WireDefault(io.row)
  when(applyValid) {
    when(io.setWaitStatus) {
      next.status := LoadInflightStatus.Wait
    }.elsewhen(io.keepRepickStatus) {
      next.status := LoadInflightStatus.Repick
    }

    when(io.clearReturnState) {
      next.sourcesReturned := false.B
      next.scbReturned := false.B
      next.stqReturned := false.B
      next.dataComplete := false.B
    }.otherwise {
      next.sourcesReturned := io.nextStoreSourceReturned
      next.scbReturned := io.nextScbReturned
      next.stqReturned := io.nextStqReturned
      next.dataComplete := io.nextDataComplete
    }

    when(io.lineWrite) {
      next.lineData := io.nextLineData
      next.validMask := io.nextValidMask
      next.dataComplete := io.nextDataComplete
    }

    when(io.waitStoreWrite) {
      next.waitStore := io.nextWaitStore
      next.waitStoreInfo := Mux(io.nextWaitStore, io.nextWaitStoreInfo, 0.U.asTypeOf(io.nextWaitStoreInfo))
    }
  }

  io.active := active
  io.applyValid := applyValid
  io.nextRow := next
  io.blockedByDisabled := !io.enable && io.requestValid
  io.blockedByFlush := io.enable && io.flush && io.requestValid
  io.blockedByNoRequest := active && !io.requestValid
  io.blockedByInvalidRow := requestActive && !io.row.valid
  io.blockedByNotRepick := requestActive && io.row.valid && (io.row.status =/= LoadInflightStatus.Repick)
  io.invalidConflictingStatusWrite := requestActive && io.setWaitStatus && io.keepRepickStatus
  io.invalidWaitStoreWithoutWaitStatus := requestActive && io.nextWaitStore && !io.setWaitStatus
  io.invalidReturnWithoutSplitSources :=
    requestActive && io.nextStoreSourceReturned && !(io.nextScbReturned && io.nextStqReturned)
}
