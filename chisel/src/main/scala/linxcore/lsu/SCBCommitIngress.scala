package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil, OHToUInt, PopCount, PriorityEncoderOH}

class SCBLineEntry(
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val valid = Bool()
  val lineAddr = UInt(addrWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
  val full = Bool()
  val state = SCBEntryState()
}

class SCBCommitWakeup(
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val valid = Bool()
  val lineAddr = UInt(addrWidth.W)
  val byteMask = UInt(lineBytes.W)
}

class SCBCommitIngressIO(
    val stqEntries: Int,
    val scbEntries: Int,
    val requestCount: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val countWidth = log2Ceil(scbEntries + 1)

  val reqs = Input(Vec(requestCount, new STQCommitDrainRequest(stqEntries, addrWidth, dataWidth, sizeWidth, 0, lsidWidth)))

  val acceptedMask = Output(UInt(requestCount.W))
  val blockedMask = Output(UInt(requestCount.W))
  val wakeups = Output(Vec(requestCount, new SCBCommitWakeup(addrWidth, lineBytes)))

  val entries = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val validMask = Output(UInt(scbEntries.W))
  val fullLineMask = Output(UInt(scbEntries.W))
  val entryCount = Output(UInt(countWidth.W))
  val freeCount = Output(UInt(countWidth.W))
  val full = Output(Bool())
}

object SCBCommitIngress {
  def lineAddr(addr: UInt, addrWidth: Int): UInt =
    Cat(addr(addrWidth - 1, 6), 0.U(6.W))
}

class SCBCommitIngress(
    val stqEntries: Int = 16,
    val scbEntries: Int = 16,
    val requestCount: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Module {
  require(stqEntries > 1, "STQ entries must be greater than one")
  require(scbEntries > 0, "SCB entries must be nonzero")
  require(requestCount > 0, "SCB ingress request count must be nonzero")
  require(addrWidth >= 7, "SCB ingress needs at least 7 address bits for 64-byte lines")
  require(dataWidth == 64, "SCB ingress currently models scalar 64-bit store fragments")
  require(sizeWidth >= 4, "SCB ingress scalar store sizes require at least 4 size bits")
  require(lineBytes == 64, "SCB ingress currently models 64-byte scalar cachelines")

  private val countWidth = log2Ceil(scbEntries + 1)

  val io = IO(new SCBCommitIngressIO(
    stqEntries, scbEntries, requestCount, addrWidth, dataWidth, sizeWidth, lineBytes, lsidWidth))

  private def zeroEntry: SCBLineEntry = {
    val entry = Wire(new SCBLineEntry(addrWidth, lineBytes))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def zeroWakeup: SCBCommitWakeup = {
    val wakeup = Wire(new SCBCommitWakeup(addrWidth, lineBytes))
    wakeup := 0.U.asTypeOf(wakeup)
    wakeup
  }

  private def requestByteMask(addr: UInt, size: UInt): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(7.W))
    val sizeWide = Wire(UInt(7.W))
    offset := addr(5, 0)
    sizeWide := size
    val end = offset +& sizeWide
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      mask(byte) := (byteIdx >= offset) && (byteIdx < end)
    }
    mask.asUInt
  }

  private def mergeData(oldData: UInt, req: STQCommitDrainRequest, byteMask: UInt): UInt = {
    val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
    val offset = Wire(UInt(7.W))
    offset := req.addr(5, 0)
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      val reqByteOffset = byteIdx - offset
      val reqByte = (req.data >> (reqByteOffset << 3))(7, 0)
      val oldByte = oldData((byte * 8) + 7, byte * 8)
      mergedBytes(byte) := Mux(byteMask(byte), reqByte, oldByte)
    }
    Cat(mergedBytes.reverse)
  }

  val entries = RegInit(VecInit(Seq.fill(scbEntries)(zeroEntry)))
  val stages = Seq.fill(requestCount + 1)(Wire(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes))))
  stages.head := entries

  val acceptedVec = Wire(Vec(requestCount, Bool()))
  val blockedVec = Wire(Vec(requestCount, Bool()))
  val wakeups = Wire(Vec(requestCount, new SCBCommitWakeup(addrWidth, lineBytes)))

  for (lane <- 0 until requestCount) {
    val req = io.reqs(lane)
    val line = SCBCommitIngress.lineAddr(req.addr, addrWidth)
    val hitVec = VecInit((0 until scbEntries).map { idx =>
      stages(lane)(idx).valid &&
      (stages(lane)(idx).state === SCBEntryState.Valid) &&
      (stages(lane)(idx).lineAddr === line)
    })
    val freeVec = VecInit((0 until scbEntries).map(idx => !stages(lane)(idx).valid))
    val hit = hitVec.asUInt.orR
    val free = freeVec.asUInt.orR
    val accept = req.valid && (hit || free)
    val hitIndex = OHToUInt(PriorityEncoderOH(hitVec.asUInt))
    val freeIndex = OHToUInt(PriorityEncoderOH(freeVec.asUInt))
    val targetIndex = Mux(hit, hitIndex, freeIndex)
    val byteMask = requestByteMask(req.addr, req.size)
    val oldEntry = stages(lane)(targetIndex)
    val mergedMask = oldEntry.byteMask | byteMask
    val mergedData = mergeData(oldEntry.data, req, byteMask)
    val nextEntry = Wire(new SCBLineEntry(addrWidth, lineBytes))

    stages(lane + 1) := stages(lane)
    nextEntry := oldEntry
    nextEntry.valid := true.B
    nextEntry.lineAddr := line
    nextEntry.byteMask := mergedMask
    nextEntry.data := mergedData
    nextEntry.full := mergedMask.andR
    nextEntry.state := SCBEntryState.Valid

    acceptedVec(lane) := accept
    blockedVec(lane) := req.valid && !accept
    wakeups(lane) := zeroWakeup
    wakeups(lane).valid := accept
    wakeups(lane).lineAddr := line
    wakeups(lane).byteMask := mergedMask

    when(accept) {
      stages(lane + 1)(targetIndex) := nextEntry
    }
  }

  entries := stages.last

  val validVec = VecInit(entries.map(_.valid))
  val fullLineVec = VecInit(entries.map(entry => entry.valid && entry.full))
  val entryCount = PopCount(validVec)

  for (idx <- 0 until scbEntries) {
    io.entries(idx) := entries(idx)
  }
  io.acceptedMask := acceptedVec.asUInt
  io.blockedMask := blockedVec.asUInt
  io.wakeups := wakeups
  io.validMask := validVec.asUInt
  io.fullLineMask := fullLineVec.asUInt
  io.entryCount := entryCount
  io.freeCount := scbEntries.U(countWidth.W) - entryCount
  io.full := entryCount === scbEntries.U
}
