package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

class SCBCommitBridgeIO(
    val stqEntries: Int,
    val scbEntries: Int,
    val requestCount: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64)
    extends Bundle {
  private val scbCountWidth = log2Ceil(scbEntries + 1)
  private val freeCountWidth = log2Ceil(requestCount + 1)

  val reqs = Input(Vec(requestCount, new STQCommitDrainRequest(stqEntries, addrWidth, dataWidth, sizeWidth)))

  val modelBatchReady = Output(Bool())
  val modelFull = Output(Bool())
  val acceptedMask = Output(UInt(requestCount.W))
  val stalledMask = Output(UInt(requestCount.W))
  val structuralBlockedMask = Output(UInt(requestCount.W))

  val commitFreeMaskValid = Output(Bool())
  val commitFreeMask = Output(UInt(stqEntries.W))
  val commitFreeCount = Output(UInt(freeCountWidth.W))

  val wakeups = Output(Vec(requestCount, new SCBCommitWakeup(addrWidth, lineBytes)))
  val entries = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val validMask = Output(UInt(scbEntries.W))
  val fullLineMask = Output(UInt(scbEntries.W))
  val entryCount = Output(UInt(scbCountWidth.W))
  val freeCount = Output(UInt(scbCountWidth.W))
  val ingressFull = Output(Bool())
}

class SCBCommitBridge(
    val stqEntries: Int = 16,
    val scbEntries: Int = 16,
    val requestCount: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64)
    extends Module {
  require(stqEntries > 1, "STQ entries must be greater than one")
  require(scbEntries > 0, "SCB entries must be nonzero")
  require(requestCount > 0, "SCB bridge request count must be nonzero")
  require(requestCount <= scbEntries, "SCB bridge model batch width cannot exceed SCB depth")
  require(addrWidth >= 7, "SCB bridge needs at least 7 address bits for 64-byte lines")
  require(dataWidth == 64, "SCB bridge currently models scalar 64-bit store fragments")
  require(sizeWidth >= 4, "SCB bridge scalar store sizes require at least 4 size bits")
  require(lineBytes == 64, "SCB bridge currently models 64-byte scalar cachelines")

  private val scbCountWidth = log2Ceil(scbEntries + 1)
  private val freeCountWidth = log2Ceil(requestCount + 1)

  val io = IO(new SCBCommitBridgeIO(stqEntries, scbEntries, requestCount, addrWidth, dataWidth, sizeWidth, lineBytes))

  val ingress = Module(new SCBCommitIngress(stqEntries, scbEntries, requestCount, addrWidth, dataWidth, sizeWidth, lineBytes))

  val validReqMask = VecInit(io.reqs.map(_.valid)).asUInt
  val modelBatchReady = ingress.io.freeCount >= requestCount.U(scbCountWidth.W)

  for (lane <- 0 until requestCount) {
    ingress.io.reqs(lane) := io.reqs(lane)
    ingress.io.reqs(lane).valid := io.reqs(lane).valid && modelBatchReady
  }

  val commitFreeVec = Wire(Vec(stqEntries, Bool()))
  for (idx <- 0 until stqEntries) {
    commitFreeVec(idx) :=
      (0 until requestCount)
        .map(lane => ingress.io.acceptedMask(lane) && io.reqs(lane).last && (io.reqs(lane).stqIndex === idx.U))
        .reduce(_ || _)
  }

  io.modelBatchReady := modelBatchReady
  io.modelFull := !modelBatchReady
  io.acceptedMask := ingress.io.acceptedMask
  io.structuralBlockedMask := ingress.io.blockedMask
  io.stalledMask := validReqMask & ~ingress.io.acceptedMask
  io.commitFreeMask := commitFreeVec.asUInt
  io.commitFreeMaskValid := commitFreeVec.asUInt.orR
  io.commitFreeCount := PopCount(commitFreeVec)(freeCountWidth - 1, 0)
  io.wakeups := ingress.io.wakeups
  io.entries := ingress.io.entries
  io.validMask := ingress.io.validMask
  io.fullLineMask := ingress.io.fullLineMask
  io.entryCount := ingress.io.entryCount
  io.freeCount := ingress.io.freeCount
  io.ingressFull := ingress.io.full
}
