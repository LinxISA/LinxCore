package linxcore.lsu

import chisel3._

object LoadForwardMissKind extends ChiselEnum {
  val NoMiss, StoreDataNotReady, DataNotComplete, AwaitingSources, ReturnPortBlocked = Value
}

/** Canonical E2 byte-selection result consumed by the retained E3/E4 owner.
  *
  * The selector may be the local compatibility `LoadStoreForwarding` scan or
  * the production generation-qualified STQ snapshot pipeline.  Keeping this
  * seam typed prevents a production load from expanding canonical STQ rows
  * back into a second CAM image merely to reuse the E3/E4 timing logic.
  */
class LoadForwardSelection(
    val robEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val loadByteMask = UInt(lineBytes.W)
  val forwardMask = UInt(lineBytes.W)
  val waitMask = UInt(lineBytes.W)
  val mergedData = UInt((lineBytes * 8).W)
  val waitStore = new LoadStoreForwardWait(
    robEntries, storeEntries, pcWidth, lsidWidth)
}

class LoadForwardResultPipelineIO(
    val robEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val flush = Input(Bool())

  val e2Valid = Input(Bool())
  val e2Selection = Input(new LoadForwardSelection(
    robEntries, storeEntries, pcWidth, lineBytes, lsidWidth))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2StqReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val e3Valid = Output(Bool())
  val e3LoadByteMask = Output(UInt(lineBytes.W))
  val e3ForwardMask = Output(UInt(lineBytes.W))
  val e3WaitMask = Output(UInt(lineBytes.W))
  val e3MergedData = Output(UInt((lineBytes * 8).W))

  val e4Valid = Output(Bool())
  val e4LineData = Output(UInt((lineBytes * 8).W))
  val e4ValidMask = Output(UInt(lineBytes.W))
  val e4LoadByteMask = Output(UInt(lineBytes.W))
  val e4ForwardMask = Output(UInt(lineBytes.W))
  val e4WaitMask = Output(UInt(lineBytes.W))
  val e4DataComplete = Output(Bool())
  val e4LoadDataReturned = Output(Bool())
  val e4ScbReturned = Output(Bool())
  val e4StqReturned = Output(Bool())
  val e4SourcesReturned = Output(Bool())
  val e4WakeupValid = Output(Bool())
  val e4WaitStore = Output(new LoadStoreForwardWait(
    robEntries, storeEntries, pcWidth, lsidWidth))
  val e4MissKind = Output(LoadForwardMissKind())
}

/** Retained source-return and miss-classification owner after byte selection.
  *
  * This module deliberately owns no STQ rows and performs no store CAM scan.
  * It registers one already-qualified selection into E3, then classifies the
  * E4 result from byte coverage, source-return evidence, and return credit.
  */
class LoadForwardResultPipeline(
    val robEntries: Int = 16,
    val storeEntries: Int = 16,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Module {
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "ROB entries must be a power of two greater than one")
  require(storeEntries > 1 && (storeEntries & (storeEntries - 1)) == 0,
    "storeEntries must be a power of two greater than one")
  require(lineBytes == 64,
    "LoadForwardResultPipeline currently models 64-byte scalar cachelines")

  val io = IO(new LoadForwardResultPipelineIO(
    robEntries, storeEntries, pcWidth, lineBytes, lsidWidth))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(
      robEntries, storeEntries, pcWidth, lsidWidth))

  val e3ValidReg = RegInit(false.B)
  val e3LoadMaskReg = RegInit(0.U(lineBytes.W))
  val e3ForwardMaskReg = RegInit(0.U(lineBytes.W))
  val e3WaitMaskReg = RegInit(0.U(lineBytes.W))
  val e3MergedDataReg = RegInit(0.U((lineBytes * 8).W))
  val e3BaseValidMaskReg = RegInit(0.U(lineBytes.W))
  val e3LoadDataReturnedReg = RegInit(false.B)
  val e3ScbReturnedReg = RegInit(false.B)
  val e3StqReturnedReg = RegInit(false.B)
  val e3ReturnReadyReg = RegInit(false.B)
  val e3WaitStoreReg = RegInit(zeroWait)

  val e4ValidReg = RegInit(false.B)
  val e4LineDataReg = RegInit(0.U((lineBytes * 8).W))
  val e4ValidMaskReg = RegInit(0.U(lineBytes.W))
  val e4LoadMaskReg = RegInit(0.U(lineBytes.W))
  val e4ForwardMaskReg = RegInit(0.U(lineBytes.W))
  val e4WaitMaskReg = RegInit(0.U(lineBytes.W))
  val e4DataCompleteReg = RegInit(false.B)
  val e4LoadDataReturnedReg = RegInit(false.B)
  val e4ScbReturnedReg = RegInit(false.B)
  val e4StqReturnedReg = RegInit(false.B)
  val e4SourcesReturnedReg = RegInit(false.B)
  val e4WakeupValidReg = RegInit(false.B)
  val e4WaitStoreReg = RegInit(zeroWait)
  val e4MissKindReg = RegInit(LoadForwardMissKind.NoMiss)

  val e3ValidMask = e3BaseValidMaskReg | e3ForwardMaskReg
  val e3DataComplete =
    e3ValidReg && e3LoadMaskReg.orR &&
      ((e3ValidMask & e3LoadMaskReg) === e3LoadMaskReg)
  val e3SourcesReturned =
    e3LoadDataReturnedReg && e3ScbReturnedReg && e3StqReturnedReg
  val e3WaitStoreBlocked = e3WaitMaskReg.orR
  val e3WakeupValid = e3ValidReg && !e3WaitStoreBlocked &&
    e3DataComplete && e3SourcesReturned && e3ReturnReadyReg

  val nextMissKind = WireDefault(LoadForwardMissKind.NoMiss)
  when(e3ValidReg) {
    when(e3WaitStoreBlocked) {
      nextMissKind := LoadForwardMissKind.StoreDataNotReady
    }.elsewhen(!e3DataComplete) {
      nextMissKind := LoadForwardMissKind.DataNotComplete
    }.elsewhen(!e3SourcesReturned) {
      nextMissKind := LoadForwardMissKind.AwaitingSources
    }.elsewhen(!e3ReturnReadyReg) {
      nextMissKind := LoadForwardMissKind.ReturnPortBlocked
    }
  }

  when(io.flush) {
    e3ValidReg := false.B
    e4ValidReg := false.B
    e4WakeupValidReg := false.B
    e4MissKindReg := LoadForwardMissKind.NoMiss
  }.otherwise {
    e4ValidReg := e3ValidReg
    e4LineDataReg := e3MergedDataReg
    e4ValidMaskReg := e3ValidMask
    e4LoadMaskReg := e3LoadMaskReg
    e4ForwardMaskReg := e3ForwardMaskReg
    e4WaitMaskReg := e3WaitMaskReg
    e4DataCompleteReg := e3DataComplete
    e4LoadDataReturnedReg := e3LoadDataReturnedReg
    e4ScbReturnedReg := e3ScbReturnedReg
    e4StqReturnedReg := e3StqReturnedReg
    e4SourcesReturnedReg := e3SourcesReturned
    e4WakeupValidReg := e3WakeupValid
    e4WaitStoreReg := e3WaitStoreReg
    e4MissKindReg := nextMissKind

    e3ValidReg := io.e2Valid
    e3LoadMaskReg := io.e2Selection.loadByteMask
    e3ForwardMaskReg := io.e2Selection.forwardMask
    e3WaitMaskReg := io.e2Selection.waitMask
    e3MergedDataReg := io.e2Selection.mergedData
    e3BaseValidMaskReg := io.e2BaseValidMask
    e3LoadDataReturnedReg := io.e2LoadDataReturned
    e3ScbReturnedReg := io.e2ScbReturned
    e3StqReturnedReg := io.e2StqReturned
    e3ReturnReadyReg := io.e2ReturnReady
    e3WaitStoreReg := io.e2Selection.waitStore
  }

  io.e3Valid := e3ValidReg
  io.e3LoadByteMask := e3LoadMaskReg
  io.e3ForwardMask := e3ForwardMaskReg
  io.e3WaitMask := e3WaitMaskReg
  io.e3MergedData := e3MergedDataReg

  io.e4Valid := e4ValidReg
  io.e4LineData := e4LineDataReg
  io.e4ValidMask := e4ValidMaskReg
  io.e4LoadByteMask := e4LoadMaskReg
  io.e4ForwardMask := e4ForwardMaskReg
  io.e4WaitMask := e4WaitMaskReg
  io.e4DataComplete := e4DataCompleteReg
  io.e4LoadDataReturned := e4LoadDataReturnedReg
  io.e4ScbReturned := e4ScbReturnedReg
  io.e4StqReturned := e4StqReturnedReg
  io.e4SourcesReturned := e4SourcesReturnedReg
  io.e4WakeupValid := e4WakeupValidReg
  io.e4WaitStore := e4WaitStoreReg
  io.e4MissKind := e4MissKindReg
}

class LoadForwardPipelineIO(
    val robEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val lsidWidth: Int = 32)
    extends Bundle {
  val flush = Input(Bool())

  val e2Valid = Input(Bool())
  val e2Query = Input(new LoadStoreForwardQuery(robEntries, addrWidth, lineBytes, sizeWidth, lsidWidth))
  val e2Stores = Input(Vec(storeEntries, new LoadStoreForwardStore(
    robEntries, storeEntries, addrWidth, pcWidth, lineBytes, lsidWidth)))
  val e2BaseData = Input(UInt((lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2StqReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val e3Valid = Output(Bool())
  val e3LoadByteMask = Output(UInt(lineBytes.W))
  val e3ForwardMask = Output(UInt(lineBytes.W))
  val e3WaitMask = Output(UInt(lineBytes.W))
  val e3MergedData = Output(UInt((lineBytes * 8).W))

  val e4Valid = Output(Bool())
  val e4LineData = Output(UInt((lineBytes * 8).W))
  val e4ValidMask = Output(UInt(lineBytes.W))
  val e4LoadByteMask = Output(UInt(lineBytes.W))
  val e4ForwardMask = Output(UInt(lineBytes.W))
  val e4WaitMask = Output(UInt(lineBytes.W))
  val e4DataComplete = Output(Bool())
  val e4LoadDataReturned = Output(Bool())
  val e4ScbReturned = Output(Bool())
  val e4StqReturned = Output(Bool())
  val e4SourcesReturned = Output(Bool())
  val e4WakeupValid = Output(Bool())
  val e4WaitStore = Output(new LoadStoreForwardWait(robEntries, storeEntries, pcWidth, lsidWidth))
  val e4MissKind = Output(LoadForwardMissKind())
}

class LoadForwardPipeline(
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
  require(addrWidth >= 7, "load forwarding pipeline needs 64-byte line addresses")
  require(lineBytes == 64, "LoadForwardPipeline currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new LoadForwardPipelineIO(
    robEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth, lsidWidth))

  val e2Forward = Module(new LoadStoreForwarding(
    robEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth, lsidWidth))
  e2Forward.io.query := io.e2Query
  e2Forward.io.stores := io.e2Stores
  e2Forward.io.cacheData := io.e2BaseData

  val result = Module(new LoadForwardResultPipeline(
    robEntries, storeEntries, pcWidth, lineBytes, lsidWidth))
  result.io.flush := io.flush
  result.io.e2Valid := io.e2Valid && io.e2Query.valid
  result.io.e2Selection.loadByteMask := e2Forward.io.loadByteMask
  result.io.e2Selection.forwardMask := e2Forward.io.forwardMask
  result.io.e2Selection.waitMask := e2Forward.io.waitMask
  result.io.e2Selection.mergedData := e2Forward.io.mergedData
  result.io.e2Selection.waitStore := e2Forward.io.waitStore
  result.io.e2BaseValidMask := io.e2BaseValidMask
  result.io.e2LoadDataReturned := io.e2LoadDataReturned
  result.io.e2ScbReturned := io.e2ScbReturned
  result.io.e2StqReturned := io.e2StqReturned
  result.io.e2ReturnReady := io.e2ReturnReady

  io.e3Valid := result.io.e3Valid
  io.e3LoadByteMask := result.io.e3LoadByteMask
  io.e3ForwardMask := result.io.e3ForwardMask
  io.e3WaitMask := result.io.e3WaitMask
  io.e3MergedData := result.io.e3MergedData
  io.e4Valid := result.io.e4Valid
  io.e4LineData := result.io.e4LineData
  io.e4ValidMask := result.io.e4ValidMask
  io.e4LoadByteMask := result.io.e4LoadByteMask
  io.e4ForwardMask := result.io.e4ForwardMask
  io.e4WaitMask := result.io.e4WaitMask
  io.e4DataComplete := result.io.e4DataComplete
  io.e4LoadDataReturned := result.io.e4LoadDataReturned
  io.e4ScbReturned := result.io.e4ScbReturned
  io.e4StqReturned := result.io.e4StqReturned
  io.e4SourcesReturned := result.io.e4SourcesReturned
  io.e4WakeupValid := result.io.e4WakeupValid
  io.e4WaitStore := result.io.e4WaitStore
  io.e4MissKind := result.io.e4MissKind
}
