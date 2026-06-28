package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, UIntToOH}

class SCBResponseDecodeIO(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val txnIdWidth = entryIndexWidth + 2

  val entries = Input(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val rawValid = Input(Bool())
  val rawTxnId = Input(UInt(txnIdWidth.W))
  val rawWriteResp = Input(Bool())
  val rawUpgradeResp = Input(Bool())

  val memRespValid = Output(Bool())
  val memRespEntryIndex = Output(UInt(entryIndexWidth.W))
  val decodedMask = Output(UInt(scbEntries.W))
  val tagMatch = Output(Bool())
  val responseTypeValid = Output(Bool())
  val indexInRange = Output(Bool())
  val targetMiss = Output(Bool())
  val typeIllegal = Output(Bool())
  val tagIllegal = Output(Bool())
  val indexIllegal = Output(Bool())
  val stateIllegalMask = Output(UInt(scbEntries.W))
  val illegal = Output(Bool())
}

class SCBResponseDecode(
    val scbEntries: Int = 16,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(scbEntries > 0, "SCB response decode entries must be nonzero")
  require(addrWidth >= 7, "SCB response decode needs at least 7 address bits for 64-byte lines")
  require(lineBytes == 64, "SCB response decode currently models 64-byte scalar cachelines")

  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val io = IO(new SCBResponseDecodeIO(scbEntries, addrWidth, lineBytes))

  val decodedIndex = io.rawTxnId(entryIndexWidth + 1, 2)
  val tagMatch = io.rawTxnId(1, 0) === 2.U
  val responseTypeValid = io.rawWriteResp =/= io.rawUpgradeResp
  val indexInRange = decodedIndex < scbEntries.U
  val candidate = io.rawValid && responseTypeValid && tagMatch && indexInRange

  val rawDecodedMask = UIntToOH(decodedIndex, scbEntries)
  val decodedMask = Mux(candidate, rawDecodedMask, 0.U(scbEntries.W))
  val missMask = VecInit(io.entries.map(entry => entry.valid && (entry.state === SCBEntryState.Miss))).asUInt
  val targetMiss = (decodedMask & missMask).orR
  val stateIllegal = candidate && !targetMiss

  io.memRespValid := candidate && targetMiss
  io.memRespEntryIndex := decodedIndex
  io.decodedMask := decodedMask
  io.tagMatch := tagMatch
  io.responseTypeValid := responseTypeValid
  io.indexInRange := indexInRange
  io.targetMiss := targetMiss
  io.typeIllegal := io.rawValid && !responseTypeValid
  io.tagIllegal := io.rawValid && responseTypeValid && !tagMatch
  io.indexIllegal := io.rawValid && responseTypeValid && tagMatch && !indexInRange
  io.stateIllegalMask := Mux(stateIllegal, decodedMask, 0.U(scbEntries.W))
  io.illegal := io.typeIllegal || io.tagIllegal || io.indexIllegal || io.stateIllegalMask.orR
}
