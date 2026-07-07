package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlanIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val transferCandidate = Input(Bool())
  val requiresPhysicalBundleSuppression = Input(Bool())
  val robDuplicatePhysicalComplete = Input(Bool())
  val rfDuplicatePhysicalWriteback = Input(Bool())
  val wakeupDuplicatePhysicalWakeup = Input(Bool())
  val lifecycleClearDuplicatePhysicalClear = Input(Bool())

  val active = Output(Bool())
  val transferCandidateSeen = Output(Bool())
  val suppressionRequired = Output(Bool())
  val physicalBundleComplete = Output(Bool())
  val atomicSuppressCandidate = Output(Bool())
  val suppressRobComplete = Output(Bool())
  val suppressRfWriteback = Output(Bool())
  val suppressWakeup = Output(Bool())
  val suppressLifecycleClear = Output(Bool())
  val suppressMask = Output(UInt(4.W))
  val allOrNoneSuppress = Output(Bool())
  val blockedByNoTransferCandidate = Output(Bool())
  val blockedByNoSuppressionRequirement = Output(Bool())
  val blockedByIncompletePhysicalBundle = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlanIO)

  val active = io.enable && !io.flush
  val transferCandidateSeen = active && io.transferCandidate
  val suppressionRequired =
    transferCandidateSeen && io.requiresPhysicalBundleSuppression
  val physicalBundleComplete =
    suppressionRequired &&
      io.robDuplicatePhysicalComplete &&
      io.rfDuplicatePhysicalWriteback &&
      io.wakeupDuplicatePhysicalWakeup &&
      io.lifecycleClearDuplicatePhysicalClear
  val atomicSuppressCandidate = suppressionRequired && physicalBundleComplete
  val suppressMask = Cat(
    atomicSuppressCandidate,
    atomicSuppressCandidate,
    atomicSuppressCandidate,
    atomicSuppressCandidate)

  io.active := active
  io.transferCandidateSeen := transferCandidateSeen
  io.suppressionRequired := suppressionRequired
  io.physicalBundleComplete := physicalBundleComplete
  io.atomicSuppressCandidate := atomicSuppressCandidate
  io.suppressRobComplete := atomicSuppressCandidate
  io.suppressRfWriteback := atomicSuppressCandidate
  io.suppressWakeup := atomicSuppressCandidate
  io.suppressLifecycleClear := atomicSuppressCandidate
  io.suppressMask := suppressMask
  io.allOrNoneSuppress := suppressMask === 0.U || suppressMask === "b1111".U
  io.blockedByNoTransferCandidate := active && !io.transferCandidate
  io.blockedByNoSuppressionRequirement :=
    transferCandidateSeen && !io.requiresPhysicalBundleSuppression
  io.blockedByIncompletePhysicalBundle :=
    suppressionRequired && !physicalBundleComplete
}
