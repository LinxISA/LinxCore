package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, FillInterleaved}

/** One partial 64-byte source image returned by L1D or SCB. */
class LoadSourceLine(
    val lineBytes: Int = 64)
    extends Bundle {
  val returned = Bool()
  val validMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}

class LoadSourceLineMergeIO(
    val lineBytes: Int = 64)
    extends Bundle {
  val l1d = Input(new LoadSourceLine(lineBytes))
  val scb = Input(new LoadSourceLine(lineBytes))
  val mergedData = Output(UInt((lineBytes * 8).W))
  val mergedValidMask = Output(UInt(lineBytes.W))
  val loadDataReturned = Output(Bool())
  val scbReturned = Output(Bool())
}

/** Byte-exact partial L1D/SCB merge before the canonical STQ lookup.
  *
  * SCB is younger than the cache image and therefore overrides every byte it
  * owns.  Invalid SCB bytes cannot overwrite valid L1D data.  Source-return
  * evidence remains split because E4 must not infer a returned source merely
  * from byte coverage.
  */
class LoadSourceLineMerge(
    val lineBytes: Int = 64)
    extends Module {
  require(lineBytes == 64,
    "production scalar source merge currently requires 64-byte lines")

  val io = IO(new LoadSourceLineMergeIO(lineBytes))

  val scbDataMask = FillInterleaved(8, io.scb.validMask)
  io.mergedData := (io.l1d.data & ~scbDataMask) |
    (io.scb.data & scbDataMask)
  io.mergedValidMask := io.l1d.validMask | io.scb.validMask
  io.loadDataReturned := io.l1d.returned
  io.scbReturned := io.scb.returned
}

class STQLoadForwardResultPipelineIO(
    val robEntries: Int,
    val stqEntries: Int,
    val addrWidth: Int = 64,
    val stidWidth: Int = 8,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val tokenWidth: Int = 64)
    extends Bundle {
  val flush = Input(Bool())
  val response = Flipped(Decoupled(new STQLoadForwardResponse(
    robEntries, stqEntries, addrWidth, stidWidth, pcWidth, lineBytes,
    lsidWidth, tokenWidth)))
  val returnReady = Input(Bool())

  val hardBlock = Decoupled(new STQLoadForwardResponse(
    robEntries, stqEntries, addrWidth, stidWidth, pcWidth, lineBytes,
    lsidWidth, tokenWidth))
  val accepted = Output(Bool())
  val hardBlockAccepted = Output(Bool())

  val e3Valid = Output(Bool())
  val e3Identity = Output(new STQLoadForwardResultIdentity)
  val e3LoadByteMask = Output(UInt(lineBytes.W))
  val e3ForwardMask = Output(UInt(lineBytes.W))
  val e3WaitMask = Output(UInt(lineBytes.W))
  val e3MergedData = Output(UInt((lineBytes * 8).W))

  val e4Valid = Output(Bool())
  val e4Identity = Output(new STQLoadForwardResultIdentity)
  val e4LineData = Output(UInt((lineBytes * 8).W))
  val e4ValidMask = Output(UInt(lineBytes.W))
  val e4LoadByteMask = Output(UInt(lineBytes.W))
  val e4ForwardMask = Output(UInt(lineBytes.W))
  val e4WaitMask = Output(UInt(lineBytes.W))
  val e4DataComplete = Output(Bool())
  val e4SourcesReturned = Output(Bool())
  val e4WakeupValid = Output(Bool())
  val e4WaitStore = Output(new LoadStoreForwardWait(
    robEntries, stqEntries, pcWidth, lsidWidth))
  val e4MissKind = Output(LoadForwardMissKind())
}

/** Production STQ response consumer for the common E3/E4 load result stages.
  *
  * Ordinary selected-store data waits are legal results and enter E3 so the
  * LIQ can record a precise wait owner.  Structural uncertainty (unknown
  * older address, stale generation, missing/ambiguous full LSID, malformed
  * query, or cross-line alias) takes a separate retained hard-block boundary
  * and never masquerades as a cache miss or a usable forwarding selection.
  */
class STQLoadForwardResultPipeline(
    val robEntries: Int = 64,
    val stqEntries: Int = 16,
    val addrWidth: Int = 64,
    val stidWidth: Int = 8,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val tokenWidth: Int = 64)
    extends Module {
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "ROB entries must be a power of two greater than one")
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "STQ entries must be a power of two greater than one")
  require(lineBytes == 64,
    "production scalar STQ result path currently requires 64-byte lines")

  val io = IO(new STQLoadForwardResultPipelineIO(
    robEntries, stqEntries, addrWidth, stidWidth, pcWidth, lineBytes,
    lsidWidth, tokenWidth))

  val hardBlocked = io.response.bits.unknownOlderMask.orR ||
    io.response.bits.staleSnapshotMask.orR ||
    io.response.bits.fullLsIdMissingMask.orR ||
    io.response.bits.fullLsIdAmbiguousMask.orR ||
    io.response.bits.crossLineStoreMask.orR ||
    io.response.bits.queryIdentityInvalid ||
    io.response.bits.loadCrossesLine

  io.hardBlock.valid := io.response.valid && hardBlocked && !io.flush
  io.hardBlock.bits := io.response.bits
  io.response.ready := !io.flush && Mux(hardBlocked,
    io.hardBlock.ready, true.B)
  io.accepted := io.response.fire && !hardBlocked
  io.hardBlockAccepted := io.hardBlock.fire

  val result = Module(new LoadForwardResultPipeline(
    robEntries, stqEntries, pcWidth, lineBytes, lsidWidth))
  result.io.flush := io.flush
  result.io.e2Valid := io.accepted
  result.io.e2Selection.loadByteMask := io.response.bits.loadByteMask
  result.io.e2Selection.forwardMask := io.response.bits.forwardMask
  result.io.e2Selection.waitMask := io.response.bits.waitMask
  result.io.e2Selection.mergedData := io.response.bits.mergedLineData
  result.io.e2Selection.waitStore := io.response.bits.waitStore
  result.io.e2BaseValidMask := io.response.bits.query.baseValidMask
  result.io.e2LoadDataReturned := io.response.bits.query.loadDataReturned
  result.io.e2ScbReturned := io.response.bits.query.scbReturned
  result.io.e2StqReturned := io.accepted
  result.io.e2ReturnReady := io.returnReady

  val acceptedIdentity = Wire(new STQLoadForwardResultIdentity)
  acceptedIdentity.loadId := io.response.bits.query.loadId
  acceptedIdentity.attempt := io.response.bits.query.attempt
  acceptedIdentity.returnPipeIndex :=
    io.response.bits.query.returnPipeIndex
  val e3Identity = RegInit(0.U.asTypeOf(acceptedIdentity))
  val e4Identity = RegInit(0.U.asTypeOf(acceptedIdentity))
  when(io.flush) {
    e3Identity := 0.U.asTypeOf(e3Identity)
    e4Identity := 0.U.asTypeOf(e4Identity)
  }.otherwise {
    e4Identity := e3Identity
    when(io.accepted) {
      e3Identity := acceptedIdentity
    }
  }

  io.e3Valid := result.io.e3Valid
  io.e3Identity := e3Identity
  io.e3LoadByteMask := result.io.e3LoadByteMask
  io.e3ForwardMask := result.io.e3ForwardMask
  io.e3WaitMask := result.io.e3WaitMask
  io.e3MergedData := result.io.e3MergedData
  io.e4Valid := result.io.e4Valid
  io.e4Identity := e4Identity
  io.e4LineData := result.io.e4LineData
  io.e4ValidMask := result.io.e4ValidMask
  io.e4LoadByteMask := result.io.e4LoadByteMask
  io.e4ForwardMask := result.io.e4ForwardMask
  io.e4WaitMask := result.io.e4WaitMask
  io.e4DataComplete := result.io.e4DataComplete
  io.e4SourcesReturned := result.io.e4SourcesReturned
  io.e4WakeupValid := result.io.e4WakeupValid
  io.e4WaitStore := result.io.e4WaitStore
  io.e4MissKind := result.io.e4MissKind
}
