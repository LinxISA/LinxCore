package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundaryIO(
    val idEntries: Int = 16,
    val liqEntries: Int = 16)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val capture = Input(Bool())
  val captureRid = Input(new ROBID(idEntries))
  val captureLoadLsId = Input(new ROBID(idEntries))
  val captureLifecycleRowReady = Input(Bool())
  val captureLifecycleRowIndex = Input(UInt(liqPtrWidth.W))
  val registeredValid = Input(Bool())
  val registeredFullMask = Input(Bool())

  val captureOwnership = Output(Bool())
  val capturedOwnershipValid = Output(Bool())
  val registeredCandidate = Output(Bool())
  val registeredRid = Output(new ROBID(idEntries))
  val registeredLoadLsId = Output(new ROBID(idEntries))
  val registeredLifecycleRowReady = Output(Bool())
  val registeredLifecycleRowIndex = Output(UInt(liqPtrWidth.W))
  val registeredRidValid = Output(Bool())
  val registeredLoadLsIdValid = Output(Bool())
  val registeredOwnershipBundleReady = Output(Bool())
  val eligibleRegisteredMask = Output(Bool())
  val blockedByNoCapturedOwnership = Output(Bool())
  val blockedByMissingRid = Output(Bool())
  val blockedByMissingLoadLsId = Output(Bool())
  val blockedByMissingLifecycleRow = Output(Bool())
  val blockedByNotFullMask = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary(
    val idEntries: Int = 16,
    val liqEntries: Int = 16)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(liqEntries > 1, "liqEntries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "liqEntries must be a power of two")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundaryIO(
    idEntries,
    liqEntries
  ))

  val capturedValidReg = RegInit(false.B)
  val capturedRidReg = RegInit(ROBID.disabled(idEntries))
  val capturedLoadLsIdReg = RegInit(ROBID.disabled(idEntries))
  val capturedLifecycleReadyReg = RegInit(false.B)
  val capturedLifecycleIndexReg = RegInit(0.U(liqPtrWidth.W))

  val active = io.enable && !io.flush
  val captureOwnership = active && io.capture

  when(!io.enable || io.flush) {
    capturedValidReg := false.B
    capturedRidReg := ROBID.disabled(idEntries)
    capturedLoadLsIdReg := ROBID.disabled(idEntries)
    capturedLifecycleReadyReg := false.B
    capturedLifecycleIndexReg := 0.U
  }.elsewhen(captureOwnership) {
    capturedValidReg := true.B
    capturedRidReg := io.captureRid
    capturedLoadLsIdReg := io.captureLoadLsId
    capturedLifecycleReadyReg := io.captureLifecycleRowReady
    capturedLifecycleIndexReg := io.captureLifecycleRowIndex
  }

  val registeredCandidate = active && io.registeredValid
  val capturedOwnershipValid = active && capturedValidReg
  val registeredRidValid = capturedOwnershipValid && capturedRidReg.valid
  val registeredLoadLsIdValid = capturedOwnershipValid && capturedLoadLsIdReg.valid
  val registeredOwnershipBundleReady =
    capturedOwnershipValid &&
      registeredRidValid &&
      registeredLoadLsIdValid &&
      capturedLifecycleReadyReg
  val eligibleRegisteredMask =
    registeredCandidate &&
      registeredOwnershipBundleReady &&
      io.registeredFullMask

  io.captureOwnership := captureOwnership
  io.capturedOwnershipValid := capturedOwnershipValid
  io.registeredCandidate := registeredCandidate
  io.registeredRid := capturedRidReg
  io.registeredLoadLsId := capturedLoadLsIdReg
  io.registeredLifecycleRowReady := capturedLifecycleReadyReg
  io.registeredLifecycleRowIndex := capturedLifecycleIndexReg
  io.registeredRidValid := registeredRidValid
  io.registeredLoadLsIdValid := registeredLoadLsIdValid
  io.registeredOwnershipBundleReady := registeredOwnershipBundleReady
  io.eligibleRegisteredMask := eligibleRegisteredMask
  io.blockedByNoCapturedOwnership := registeredCandidate && !capturedOwnershipValid
  io.blockedByMissingRid := registeredCandidate && capturedOwnershipValid && !capturedRidReg.valid
  io.blockedByMissingLoadLsId :=
    registeredCandidate &&
      capturedOwnershipValid &&
      capturedRidReg.valid &&
      !capturedLoadLsIdReg.valid
  io.blockedByMissingLifecycleRow :=
    registeredCandidate &&
      capturedOwnershipValid &&
      capturedRidReg.valid &&
      capturedLoadLsIdReg.valid &&
      !capturedLifecycleReadyReg
  io.blockedByNotFullMask :=
    registeredCandidate &&
      registeredOwnershipBundleReady &&
      !io.registeredFullMask
}
