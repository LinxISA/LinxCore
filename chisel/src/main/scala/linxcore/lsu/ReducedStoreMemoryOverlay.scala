package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, OHToUInt, PopCount, PriorityEncoderOH, log2Ceil}

class ReducedStoreMemoryOverlayLine(val addrWidth: Int = 64, val lineBytes: Int = 64) extends Bundle {
  val valid = Bool()
  val lineAddr = UInt(addrWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}

class ReducedStoreMemoryOverlayIO(
    val stqEntries: Int,
    val requestCount: Int,
    val lineEntries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else stqEntries
  private val lineCountWidth = log2Ceil(lineEntries + 1)

  val flush = Input(Bool())
  val storeReqs = Input(Vec(requestCount,
    new STQCommitDrainRequest(stqEntries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth)))
  val storeAcceptedMask = Input(UInt(requestCount.W))

  val loadValid = Input(Bool())
  val loadAddr = Input(UInt(addrWidth.W))
  val baseLoadData = Input(UInt(dataWidth.W))
  val loadData = Output(UInt(dataWidth.W))
  val loadForwardMask = Output(UInt((dataWidth / 8).W))

  val lines = Output(Vec(lineEntries, new ReducedStoreMemoryOverlayLine(addrWidth, lineBytes)))
  val validMask = Output(UInt(lineEntries.W))
  val lineCount = Output(UInt(lineCountWidth.W))
  val storeDroppedMask = Output(UInt(requestCount.W))
}

class ReducedStoreMemoryOverlay(
    val stqEntries: Int = 16,
    val requestCount: Int = 4,
    val lineEntries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else stqEntries
  require(stqEntries > 1, "STQ entries must be greater than one")
  require(identityEntries > 1 && (identityEntries & (identityEntries - 1)) == 0,
    "ROB entries must be a power of two greater than one")
  require(requestCount > 0, "store overlay request count must be nonzero")
  require(lineEntries > 0, "store overlay line entries must be nonzero")
  require(addrWidth >= 7, "store overlay needs 64-byte line addresses")
  require(dataWidth == 64, "store overlay currently serves 64-bit scalar load lookups")
  require(sizeWidth >= 4, "store overlay scalar store sizes require at least 4 bits")
  require(lineBytes == 64, "store overlay currently models 64-byte scalar cachelines")

  private val loadBytes = dataWidth / 8
  private val offsetWidth = log2Ceil(lineBytes)
  private val lineCountWidth = log2Ceil(lineEntries + 1)

  val io = IO(new ReducedStoreMemoryOverlayIO(
    stqEntries,
    requestCount,
    lineEntries,
    addrWidth,
    dataWidth,
    sizeWidth,
    lineBytes,
    identityEntries,
    lsidWidth))

  private def zeroLine: ReducedStoreMemoryOverlayLine = {
    val line = Wire(new ReducedStoreMemoryOverlayLine(addrWidth, lineBytes))
    line := 0.U.asTypeOf(line)
    line
  }

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, offsetWidth), 0.U(offsetWidth.W))

  private def requestByteMask(addr: UInt, size: UInt): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(7.W))
    val sizeWide = Wire(UInt(7.W))
    offset := addr(offsetWidth - 1, 0)
    sizeWide := size
    val end = offset +& sizeWide
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      mask(byte) := (sizeWide =/= 0.U) && (byteIdx >= offset) && (byteIdx < end)
    }
    mask.asUInt
  }

  private def mergeData(oldData: UInt, req: STQCommitDrainRequest, byteMask: UInt): UInt = {
    val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
    val offset = Wire(UInt(7.W))
    offset := req.addr(offsetWidth - 1, 0)
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      val reqByteOffset = byteIdx - offset
      val reqByte = (req.data >> (reqByteOffset << 3))(7, 0)
      val oldByte = oldData((byte * 8) + 7, byte * 8)
      mergedBytes(byte) := Mux(byteMask(byte), reqByte, oldByte)
    }
    Cat(mergedBytes.reverse)
  }

  val lines = RegInit(VecInit(Seq.fill(lineEntries)(zeroLine)))
  val ingressStages = Seq.fill(requestCount + 1)(Wire(Vec(lineEntries, new ReducedStoreMemoryOverlayLine(addrWidth, lineBytes))))
  ingressStages.head := lines

  val droppedVec = Wire(Vec(requestCount, Bool()))
  for (lane <- 0 until requestCount) {
    val req = io.storeReqs(lane)
    val accepted = io.storeAcceptedMask(lane) && req.valid
    val reqLine = lineAddr(req.addr)
    val hitVec = VecInit((0 until lineEntries).map(idx =>
      ingressStages(lane)(idx).valid && ingressStages(lane)(idx).lineAddr === reqLine))
    val freeVec = VecInit((0 until lineEntries).map(idx => !ingressStages(lane)(idx).valid))
    val hit = hitVec.asUInt.orR
    val free = freeVec.asUInt.orR
    val targetValid = hit || free
    val targetIndex = OHToUInt(PriorityEncoderOH(Mux(hit, hitVec.asUInt, freeVec.asUInt)))
    val byteMask = requestByteMask(req.addr, req.size)
    val oldLine = ingressStages(lane)(targetIndex)
    val nextLine = Wire(new ReducedStoreMemoryOverlayLine(addrWidth, lineBytes))

    ingressStages(lane + 1) := ingressStages(lane)
    nextLine := oldLine
    nextLine.valid := true.B
    nextLine.lineAddr := reqLine
    nextLine.byteMask := oldLine.byteMask | byteMask
    nextLine.data := mergeData(oldLine.data, req, byteMask)
    droppedVec(lane) := accepted && !targetValid

    when(accepted && targetValid) {
      ingressStages(lane + 1)(targetIndex) := nextLine
    }
  }

  when(io.flush) {
    for (idx <- 0 until lineEntries) {
      lines(idx) := zeroLine
    }
  }.otherwise {
    lines := ingressStages.last
  }

  val outBytes = Wire(Vec(loadBytes, UInt(8.W)))
  val forwardVec = Wire(Vec(loadBytes, Bool()))
  for (byte <- 0 until loadBytes) {
    val byteAddr = io.loadAddr + byte.U(addrWidth.W)
    val byteLineAddr = lineAddr(byteAddr)
    val byteOffset = byteAddr(offsetWidth - 1, 0)
    var selectedByte: UInt = io.baseLoadData((byte * 8) + 7, byte * 8)
    var selectedHit: Bool = false.B
    for (idx <- 0 until lineEntries) {
      val hit = io.loadValid && lines(idx).valid && lines(idx).lineAddr === byteLineAddr && lines(idx).byteMask(byteOffset)
      val lineByte = (lines(idx).data >> (byteOffset << 3))(7, 0)
      selectedByte = Mux(hit, lineByte, selectedByte)
      selectedHit = selectedHit || hit
    }
    outBytes(byte) := selectedByte
    forwardVec(byte) := selectedHit
  }

  val validVec = VecInit(lines.map(_.valid))
  io.loadData := Cat(outBytes.reverse)
  io.loadForwardMask := forwardVec.asUInt
  io.lines := lines
  io.validMask := validVec.asUInt
  io.lineCount := PopCount(validVec.asUInt)(lineCountWidth - 1, 0)
  io.storeDroppedMask := droppedVec.asUInt
}
