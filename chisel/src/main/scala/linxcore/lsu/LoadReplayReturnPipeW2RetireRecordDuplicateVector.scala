package linxcore.lsu

import chisel3._
import chisel3.util.PopCount

class LoadReplayReturnPipeW2RetireRecordDuplicateVectorIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val recordValid = Input(Bool())
  val preArmModelOrderReady = Input(Bool())
  val robDuplicatePhysicalComplete = Input(Bool())
  val rfDuplicatePhysicalWriteback = Input(Bool())
  val wakeupDuplicatePhysicalWakeup = Input(Bool())
  val lifecycleClearDuplicatePhysicalClear = Input(Bool())

  val active = Output(Bool())
  val recordCandidate = Output(Bool())
  val duplicateVectorValid = Output(Bool())
  val returnSideEffectDuplicateBundle = Output(Bool())
  val modelOrderDuplicateBundle = Output(Bool())
  val partialDuplicateVector = Output(Bool())
  val singleDuplicate = Output(Bool())
  val multiDuplicate = Output(Bool())
  val duplicateCount = Output(UInt(3.W))
  val nextOwnerRequiresBundleTransfer = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByPreArmNotReady = Output(Bool())
  val blockedByNoDuplicate = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordDuplicateVector extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordDuplicateVectorIO)

  val active = io.enable && !io.flush
  val recordCandidate = active && io.recordValid
  val duplicates = Seq(
    io.robDuplicatePhysicalComplete,
    io.rfDuplicatePhysicalWriteback,
    io.wakeupDuplicatePhysicalWakeup,
    io.lifecycleClearDuplicatePhysicalClear)
  val duplicateCount = PopCount(duplicates)
  val anyDuplicate = duplicateCount =/= 0.U
  val duplicateVectorValid = recordCandidate && io.preArmModelOrderReady && anyDuplicate
  val returnSideEffectDuplicateBundle =
    duplicateVectorValid &&
      io.robDuplicatePhysicalComplete &&
      io.rfDuplicatePhysicalWriteback &&
      io.wakeupDuplicatePhysicalWakeup
  val modelOrderDuplicateBundle =
    returnSideEffectDuplicateBundle && io.lifecycleClearDuplicatePhysicalClear
  val partialDuplicateVector = duplicateVectorValid && !modelOrderDuplicateBundle

  io.active := active
  io.recordCandidate := recordCandidate
  io.duplicateVectorValid := duplicateVectorValid
  io.returnSideEffectDuplicateBundle := returnSideEffectDuplicateBundle
  io.modelOrderDuplicateBundle := modelOrderDuplicateBundle
  io.partialDuplicateVector := partialDuplicateVector
  io.singleDuplicate := duplicateVectorValid && (duplicateCount === 1.U)
  io.multiDuplicate := duplicateVectorValid && (duplicateCount > 1.U)
  io.duplicateCount := Mux(duplicateVectorValid, duplicateCount, 0.U)
  io.nextOwnerRequiresBundleTransfer := modelOrderDuplicateBundle
  io.blockedByNoRecord := active && !io.recordValid
  io.blockedByPreArmNotReady := recordCandidate && !io.preArmModelOrderReady
  io.blockedByNoDuplicate := recordCandidate && io.preArmModelOrderReady && !anyDuplicate
}
