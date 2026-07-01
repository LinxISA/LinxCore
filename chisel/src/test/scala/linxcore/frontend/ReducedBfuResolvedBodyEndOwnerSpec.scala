package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuResolvedBodyEndOwnerReference {
  final case class Inputs(
      flush: Boolean = false,
      headerActive: Boolean = false,
      activeHeaderPc: BigInt = 0,
      resolvedValid: Boolean = false,
      resolvedHeaderPc: BigInt = 0,
      resolvedHSizeBytes: BigInt = 0,
      resolvedBodyEndPc: BigInt = 0)
  final case class Result(
      geometryValid: Boolean,
      geometryHeaderPc: BigInt,
      hsizeBytes: BigInt,
      bsizeBytes: BigInt,
      bodyEndPc: BigInt,
      headerMismatch: Boolean,
      inactiveDrop: Boolean,
      flushDrop: Boolean,
      bodyEndUnderflow: Boolean)

  def apply(in: Inputs): Result = {
    val bodyBase = in.activeHeaderPc + 2
    val headerMatch = in.resolvedHeaderPc == in.activeHeaderPc
    val accepted = in.resolvedValid && !in.flush && in.headerActive && headerMatch
    val bsize = if (accepted && in.resolvedBodyEndPc > bodyBase) in.resolvedBodyEndPc - bodyBase else BigInt(0)

    Result(
      geometryValid = accepted,
      geometryHeaderPc = in.activeHeaderPc,
      hsizeBytes = if (accepted) in.resolvedHSizeBytes else BigInt(0),
      bsizeBytes = bsize,
      bodyEndPc = if (accepted) in.resolvedBodyEndPc else BigInt(0),
      headerMismatch = in.resolvedValid && !in.flush && in.headerActive && !headerMatch,
      inactiveDrop = in.resolvedValid && !in.flush && !in.headerActive,
      flushDrop = in.resolvedValid && in.flush,
      bodyEndUnderflow = accepted && in.resolvedBodyEndPc <= bodyBase)
  }
}

class ReducedBfuResolvedBodyEndOwnerProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val headerActive = Input(Bool())
  val activeHeaderPc = Input(UInt(p.pcWidth.W))
  val resolvedValid = Input(Bool())
  val resolvedHeaderPc = Input(UInt(p.pcWidth.W))
  val resolvedHSizeBytes = Input(UInt(p.pcWidth.W))
  val resolvedBodyEndPc = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val geometryHeaderPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
  val bodyEndPc = Output(UInt(p.pcWidth.W))
  val accepted = Output(Bool())
  val headerMismatch = Output(Bool())
  val inactiveDrop = Output(Bool())
  val flushDrop = Output(Bool())
  val bodyEndUnderflow = Output(Bool())
}

class ReducedBfuResolvedBodyEndOwnerProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuResolvedBodyEndOwnerProbeIO(p))
  val owner = Module(new ReducedBfuResolvedBodyEndOwner(p))

  owner.io.flushValid := io.flushValid
  owner.io.headerActive := io.headerActive
  owner.io.activeHeaderPc := io.activeHeaderPc
  owner.io.resolvedValid := io.resolvedValid
  owner.io.resolvedHeaderPc := io.resolvedHeaderPc
  owner.io.resolvedHSizeBytes := io.resolvedHSizeBytes
  owner.io.resolvedBodyEndPc := io.resolvedBodyEndPc

  io.geometryValid := owner.io.geometryValid
  io.geometryHeaderPc := owner.io.geometryHeaderPc
  io.hsizeBytes := owner.io.hsizeBytes
  io.bsizeBytes := owner.io.bsizeBytes
  io.bodyEndPc := owner.io.bodyEndPc
  io.accepted := owner.io.accepted
  io.headerMismatch := owner.io.headerMismatch
  io.inactiveDrop := owner.io.inactiveDrop
  io.flushDrop := owner.io.flushDrop
  io.bodyEndUnderflow := owner.io.bodyEndUnderflow
}

class ReducedBfuResolvedBodyEndOwnerSpec extends AnyFunSuite {
  test("reference accepts matching resolved body-end geometry and saturates from header body base") {
    val result = ReducedBfuResolvedBodyEndOwnerReference(
      ReducedBfuResolvedBodyEndOwnerReference.Inputs(
        headerActive = true,
        activeHeaderPc = BigInt("4000630c", 16),
        resolvedValid = true,
        resolvedHeaderPc = BigInt("4000630c", 16),
        resolvedHSizeBytes = 0,
        resolvedBodyEndPc = BigInt("4000632e", 16)))

    assert(result.geometryValid)
    assert(result.geometryHeaderPc == BigInt("4000630c", 16))
    assert(result.hsizeBytes == 0)
    assert(result.bsizeBytes == 0x20)
    assert(result.bodyEndPc == BigInt("4000632e", 16))
    assert(!result.headerMismatch)
    assert(!result.inactiveDrop)
    assert(!result.flushDrop)
    assert(!result.bodyEndUnderflow)
  }

  test("reference carries resolved hsize only on an accepted geometry event") {
    val result = ReducedBfuResolvedBodyEndOwnerReference(
      ReducedBfuResolvedBodyEndOwnerReference.Inputs(
        headerActive = true,
        activeHeaderPc = 0x4000,
        resolvedValid = true,
        resolvedHeaderPc = 0x4000,
        resolvedHSizeBytes = 6,
        resolvedBodyEndPc = 0x4012))

    assert(result.geometryValid)
    assert(result.hsizeBytes == 6)
    assert(result.bsizeBytes == 0x10)
  }

  test("reference rejects mismatched, inactive, and flushed resolved events with diagnostics") {
    val mismatch = ReducedBfuResolvedBodyEndOwnerReference(
      ReducedBfuResolvedBodyEndOwnerReference.Inputs(
        headerActive = true,
        activeHeaderPc = 0x5000,
        resolvedValid = true,
        resolvedHeaderPc = 0x6000,
        resolvedBodyEndPc = 0x6010))
    val inactive = ReducedBfuResolvedBodyEndOwnerReference(
      ReducedBfuResolvedBodyEndOwnerReference.Inputs(
        headerActive = false,
        activeHeaderPc = 0x5000,
        resolvedValid = true,
        resolvedHeaderPc = 0x5000,
        resolvedBodyEndPc = 0x5010))
    val flushed = ReducedBfuResolvedBodyEndOwnerReference(
      ReducedBfuResolvedBodyEndOwnerReference.Inputs(
        flush = true,
        headerActive = true,
        activeHeaderPc = 0x5000,
        resolvedValid = true,
        resolvedHeaderPc = 0x5000,
        resolvedBodyEndPc = 0x5010))

    assert(!mismatch.geometryValid)
    assert(mismatch.headerMismatch)
    assert(!inactive.geometryValid)
    assert(inactive.inactiveDrop)
    assert(!flushed.geometryValid)
    assert(flushed.flushDrop)
  }

  test("reference preserves model SetBsize underflow saturation") {
    val result = ReducedBfuResolvedBodyEndOwnerReference(
      ReducedBfuResolvedBodyEndOwnerReference.Inputs(
        headerActive = true,
        activeHeaderPc = 0x7000,
        resolvedValid = true,
        resolvedHeaderPc = 0x7000,
        resolvedBodyEndPc = 0x7001))

    assert(result.geometryValid)
    assert(result.bsizeBytes == 0)
    assert(result.bodyEndUnderflow)
  }

  test("ReducedBfuResolvedBodyEndOwner elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuResolvedBodyEndOwnerProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuResolvedBodyEndOwnerProbe"))
    assert(sv.contains("module ReducedBfuResolvedBodyEndOwner"))
    assert(sv.contains("io_geometryValid"))
    assert(sv.contains("io_geometryHeaderPc"))
    assert(sv.contains("io_bsizeBytes"))
    assert(sv.contains("io_bodyEndUnderflow"))
  }
}
