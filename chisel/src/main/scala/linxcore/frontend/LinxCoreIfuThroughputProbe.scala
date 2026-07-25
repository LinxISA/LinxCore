package linxcore.frontend

import chisel3._
import chisel3.util.{PopCount, log2Ceil}
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams

class LinxCoreIfuThroughputProbeIO(
    val p: InterfaceParams = InterfaceParams(),
    val joinEntries: Int = 8,
    val missEntries: Int = 8)
    extends Bundle {
  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val d1Ready = Input(Bool())

  val lineReadFire = Output(Bool())
  val lineReadCount = Output(UInt(16.W))
  val lineRefillFire = Output(Bool())
  val lineRefillCount = Output(UInt(16.W))
  val d1Valid = Output(Bool())
  val d1Fire = Output(Bool())
  val d1ValidMask = Output(UInt(p.decodeWidth.W))
  val d1Pc = Output(Vec(p.decodeWidth, UInt(p.pcWidth.W)))
  val d1PredictionStage = Output(Vec(p.decodeWidth, UInt(BSideStage.getWidth.W)))
  val d1PredictionFinal = Output(Vec(p.decodeWidth, Bool()))
  val joinCount = Output(UInt(log2Ceil(joinEntries + 1).W))
  val lineContextCount = Output(UInt(log2Ceil(joinEntries + 1).W))
  val missValidMask = Output(UInt(missEntries.W))
  val canonicalFlushValid = Output(Bool())
  val staleF2Result = Output(Bool())
}

/** Generated-RTL proof shell for the canonical IFU.
  *
  * The shell uses architectural 64-byte cachelines and a synthesizable
  * one-request line-memory responder. Every line contains 32 dense 16-bit
  * instructions, so a warmed four-line region must sustain one full D1 group
  * per cycle. A start also installs an executable identity-mapped ITLB entry;
  * later starts clear speculative work without invalidating physical ITLB/L1I
  * state.
  */
class LinxCoreIfuThroughputProbe(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096,
    val joinEntries: Int = 8,
    val missEntries: Int = 8)
    extends Module {
  require(lineBytes == 64, "throughput promotion uses architectural cacheline geometry")
  require(p.fetchWidth == 4 && p.decodeWidth == 4 && p.insnWidth == 64)

  val io = IO(new LinxCoreIfuThroughputProbeIO(p, joinEntries, missEntries))
  val ifu = Module(
    new LinxCoreIfu(
      p = p,
      threadCount = 1,
      lineBytes = lineBytes,
      pageBytes = pageBytes,
      itlbEntries = 4,
      l1iSets = 8,
      missEntries = missEntries,
      joinEntries = joinEntries,
      maxGroupsPerTransaction = 8,
      instructionBufferDepth = 64))

  ifu.io.start.valid := io.startValid
  ifu.io.start.bits.peId := 1.U
  ifu.io.start.bits.threadId := 0.U
  ifu.io.start.bits.pc := io.startPc
  ifu.io.backendRedirect.valid := false.B
  ifu.io.backendRedirect.bits := 0.U.asTypeOf(ifu.io.backendRedirect.bits)
  ifu.io.branchResolve.valid := false.B
  ifu.io.branchResolve.bits := 0.U.asTypeOf(ifu.io.branchResolve.bits)

  ifu.io.ptwRequest.ready := true.B
  ifu.io.ptwRefill.valid := io.startValid
  ifu.io.ptwRefill.bits.vpn := io.startPc(p.pcWidth - 1, log2Ceil(pageBytes))
  ifu.io.ptwRefill.bits.ppn := io.startPc(p.pcWidth - 1, log2Ceil(pageBytes))
  ifu.io.ptwRefill.bits.executable := true.B

  val pendingLineValid = RegInit(false.B)
  val pendingLine = RegInit(0.U.asTypeOf(new ISideLineReadRequest(p, lineBytes)))
  val denseLine = (0 until lineBytes / 2).foldLeft(BigInt(0)) { case (data, index) =>
    data | (BigInt(0x0010) << (index * 16))
  }

  ifu.io.lineRead.ready := !pendingLineValid
  when(ifu.io.lineRead.fire) {
    pendingLineValid := true.B
    pendingLine := ifu.io.lineRead.bits
  }

  ifu.io.lineRefill.valid := pendingLineValid
  ifu.io.lineRefill.bits := 0.U.asTypeOf(ifu.io.lineRefill.bits)
  ifu.io.lineRefill.bits.peId := pendingLine.request.identity.peId
  ifu.io.lineRefill.bits.transactionId := pendingLine.request.transactionId
  ifu.io.lineRefill.bits.threadId := pendingLine.request.identity.threadId
  ifu.io.lineRefill.bits.fetchPacketUid := pendingLine.request.identity.fetchPacketUid
  ifu.io.lineRefill.bits.fetchSeq := pendingLine.request.identity.fetchSeq
  ifu.io.lineRefill.bits.checkpointId := pendingLine.request.identity.checkpointId
  ifu.io.lineRefill.bits.epoch := pendingLine.request.identity.epoch
  ifu.io.lineRefill.bits.lineVa := pendingLine.request.lineVa
  ifu.io.lineRefill.bits.linePa := pendingLine.linePa
  ifu.io.lineRefill.bits.lineData := denseLine.U((lineBytes * 8).W)
  when(ifu.io.lineRefill.fire) {
    pendingLineValid := false.B
  }

  ifu.io.fetchFault.ready := true.B
  ifu.io.invalidateItlb := false.B
  ifu.io.invalidateL1I := false.B
  ifu.io.d1ThreadId := 0.U
  ifu.io.d1.ready := io.d1Ready

  val lineReadCount = RegInit(0.U(16.W))
  val lineRefillCount = RegInit(0.U(16.W))
  when(ifu.io.lineRead.fire) {
    lineReadCount := lineReadCount + 1.U
  }
  when(ifu.io.lineRefill.fire) {
    lineRefillCount := lineRefillCount + 1.U
  }

  io.lineReadFire := ifu.io.lineRead.fire
  io.lineReadCount := lineReadCount
  io.lineRefillFire := ifu.io.lineRefill.fire
  io.lineRefillCount := lineRefillCount
  io.d1Valid := ifu.io.d1.valid
  io.d1Fire := ifu.io.d1.fire
  io.d1ValidMask := ifu.io.d1.bits.validMask
  for (lane <- 0 until p.decodeWidth) {
    io.d1Pc(lane) := ifu.io.d1.bits.entries(lane).pc
    io.d1PredictionStage(lane) := ifu.io.d1.bits.entries(lane).prediction.stage.asUInt
    io.d1PredictionFinal(lane) := ifu.io.d1.bits.entries(lane).prediction.stage === BSideStage.BF4
  }
  io.joinCount := ifu.io.joinCount
  io.lineContextCount := ifu.io.lineContextCount
  io.missValidMask := ifu.io.missValidMask
  io.canonicalFlushValid := ifu.io.canonicalFlush.valid
  io.staleF2Result := ifu.io.staleF2Result

  when(ifu.io.d1.fire) {
    assert(PopCount(ifu.io.d1.bits.validMask) === p.decodeWidth.U)
    for (lane <- 0 until p.decodeWidth) {
      assert(ifu.io.d1.bits.entries(lane).prediction.stage === BSideStage.BF4)
    }
  }
}

object EmitLinxCoreIfuThroughputProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinxCoreIfuThroughputProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
