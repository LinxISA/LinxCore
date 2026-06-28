package linxcore.commit

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

final case class CommitTraceParams(
    commitWidth: Int = 4,
    pcWidth: Int = 64,
    insnWidth: Int = 64,
    lenWidth: Int = 4,
    regWidth: Int = 8,
    dataWidth: Int = 64,
    causeWidth: Int = 32,
    seqWidth: Int = 64,
    cycleWidth: Int = 64,
    robValueWidth: Int = 7,
    blockBidWidth: Int = 64) {
  require(commitWidth > 0, "commitWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(insnWidth >= 48, "insnWidth must hold Linx 48-bit encodings")
  require(lenWidth >= 4, "lenWidth must encode 0, 2, 4, 6, and 8 byte rows")
  require(regWidth > 0, "regWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(causeWidth > 0, "causeWidth must be positive")
  require(seqWidth > 0, "seqWidth must be positive")
  require(cycleWidth > 0, "cycleWidth must be positive")
  require(robValueWidth > 0, "robValueWidth must be positive")
  require(blockBidWidth >= 64, "blockBidWidth must preserve the default 64-bit BID")

  def slotWidth: Int = math.max(1, log2Ceil(commitWidth))
}

class CommitOperandTrace(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val valid = Bool()
  val reg = UInt(p.regWidth.W)
  val data = UInt(p.dataWidth.W)
}

class CommitMemTrace(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val valid = Bool()
  val isStore = Bool()
  val addr = UInt(p.dataWidth.W)
  val wdata = UInt(p.dataWidth.W)
  val rdata = UInt(p.dataWidth.W)
  val size = UInt(4.W)
}

class CommitTrapTrace(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val valid = Bool()
  val cause = UInt(p.causeWidth.W)
  val arg0 = UInt(p.dataWidth.W)
}

class CommitRobIdTrace(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val valid = Bool()
  val wrap = Bool()
  val value = UInt(p.robValueWidth.W)
}

class CommitTraceRow(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val valid = Bool()
  val seq = UInt(p.seqWidth.W)
  val cycle = UInt(p.cycleWidth.W)
  val slot = UInt(p.slotWidth.W)

  val identity = new CommitIdentity
  val rob = new CommitRobIdTrace(p)

  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)

  val pc = UInt(p.pcWidth.W)
  val insn = UInt(p.insnWidth.W)
  val len = UInt(p.lenWidth.W)

  val wb = new CommitOperandTrace(p)
  val src0 = new CommitOperandTrace(p)
  val src1 = new CommitOperandTrace(p)
  val dst = new CommitOperandTrace(p)
  val mem = new CommitMemTrace(p)
  val trap = new CommitTrapTrace(p)
  val nextPc = UInt(p.pcWidth.W)
}

class CommitTracePort(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val rows = Vec(p.commitWidth, new CommitTraceRow(p))
}

class CommitTraceWindowIO(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val in = Input(new CommitTracePort(p))
  val validMask = Output(UInt(p.commitWidth.W))
  val validCount = Output(UInt((log2Ceil(p.commitWidth + 1)).W))
}

class CommitTraceWindow(val p: CommitTraceParams = CommitTraceParams()) extends Module {
  val io = IO(new CommitTraceWindowIO(p))

  val validVec = VecInit(io.in.rows.map(_.valid))
  io.validMask := validVec.asUInt
  io.validCount := PopCount(validVec)
}

object CommitTraceSchema {
  val RequiredFields: Seq[String] = Seq(
    "pc",
    "insn",
    "len",
    "wb_valid",
    "wb_rd",
    "wb_data",
    "src0_valid",
    "src0_reg",
    "src0_data",
    "src1_valid",
    "src1_reg",
    "src1_data",
    "dst_valid",
    "dst_reg",
    "dst_data",
    "mem_valid",
    "mem_is_store",
    "mem_addr",
    "mem_wdata",
    "mem_rdata",
    "mem_size",
    "trap_valid",
    "trap_cause",
    "traparg0",
    "next_pc"
  )

  val CommitInfoFields: Seq[String] = Seq("bid", "gid", "rid")

  val SidebandFields: Seq[String] = Seq(
    "seq",
    "cycle",
    "slot",
    "bid",
    "gid",
    "rid",
    "rob_valid",
    "rob_wrap",
    "rob_value",
    "block_bid_valid",
    "block_bid"
  )
}
