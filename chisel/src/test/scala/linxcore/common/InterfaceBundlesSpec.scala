package linxcore.common

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class InterfaceBundleProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val f4 = Input(new FrontendDecodePacket(p))
  val decoded = Input(Vec(p.decodeWidth, new DecodedUop(p)))
  val renamed = Output(new RenamedUop(p))
  val iq = Output(new IssueQueueEntry(p))
  val lsuReq = Output(new LsuRequest(p))
  val lsuResp = Output(new LsuResponse(p))
  val robRow = Output(new RobRow(p))
  val trace = Output(new LinxTraceProbe(p))
}

class InterfaceBundleProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new InterfaceBundleProbeIO(p))

  io.renamed := 0.U.asTypeOf(io.renamed)
  io.renamed.valid := io.f4.valid && io.decoded(0).valid
  io.renamed.pc := io.decoded(0).pc
  io.renamed.opcode := io.decoded(0).opcode
  io.renamed.rid := io.decoded(0).rid
  io.renamed.bid := io.decoded(0).bid
  io.renamed.gid := io.decoded(0).gid
  io.renamed.blockBidValid := io.decoded(0).blockBidValid
  io.renamed.blockBid := io.decoded(0).blockBid
  io.renamed.uid := io.decoded(0).uid

  io.iq := 0.U.asTypeOf(io.iq)
  io.iq.valid := io.renamed.valid
  io.iq.uop := io.renamed

  io.lsuReq := 0.U.asTypeOf(io.lsuReq)
  io.lsuReq.valid := io.iq.valid && (io.iq.target === DispatchTarget.Lsu)
  io.lsuReq.uid := io.iq.uop.uid.uid
  io.lsuReq.rid := io.iq.uop.rid
  io.lsuReq.bid := io.iq.uop.bid
  io.lsuReq.gid := io.iq.uop.gid
  io.lsuReq.lsid := io.iq.uop.lsid
  io.lsuReq.blockBidValid := io.iq.uop.blockBidValid
  io.lsuReq.blockBid := io.iq.uop.blockBid

  io.lsuResp := 0.U.asTypeOf(io.lsuResp)
  io.lsuResp.valid := io.lsuReq.valid
  io.lsuResp.rid := io.lsuReq.rid
  io.lsuResp.gid := io.lsuReq.gid
  io.lsuResp.lsid := io.lsuReq.lsid

  io.robRow := 0.U.asTypeOf(io.robRow)
  io.robRow.valid := io.renamed.valid
  io.robRow.pc := io.renamed.pc
  io.robRow.opcode := io.renamed.opcode
  io.robRow.uid := io.renamed.uid
  io.robRow.blockBidValid := io.renamed.blockBidValid
  io.robRow.blockBid := io.renamed.blockBid

  io.trace := 0.U.asTypeOf(io.trace)
  io.trace.valid := io.robRow.valid
  io.trace.pc := io.robRow.pc
  io.trace.opcode := io.robRow.opcode
  io.trace.uid := io.robRow.uid.uid
  io.trace.rid := io.renamed.rid
  io.trace.bid := io.renamed.bid
  io.trace.gid := io.renamed.gid
  io.trace.blockBidValid := io.robRow.blockBidValid
  io.trace.blockBid := io.robRow.blockBid
}

class InterfaceBundlesSpec extends AnyFunSuite {
  test("InterfaceParams defaults match the current pyCircuit bring-up contract") {
    val p = InterfaceParams()

    assert(p.fetchWidth == 4)
    assert(p.decodeWidth == 4)
    assert(p.issueWidth == 4)
    assert(p.commitWidth == 4)
    assert(p.pcWidth == 64)
    assert(p.windowWidth == 64)
    assert(p.opcodeWidth == 12)
    assert(p.archRegWidth == 6)
    assert(p.physRegWidth == 6)
    assert(p.robEntries == 64)
    assert(p.robIndexWidth == 6)
    assert(p.iqEntries == 32)
    assert(p.iqIndexWidth == 5)
    assert(p.lsidWidth == 32)
    assert(p.checkpointWidth == 6)
    assert(p.blockBidWidth == 64)
  }

  test("common constants preserve pyCircuit and commit-trace trap encodings") {
    assert(LinxCommonConstants.RegInvalid == 0x3f)
    assert(LinxCommonConstants.TrapBruRecoveryNotBstart == 0x0000b001L)
  }

  test("boundary kind enum preserves the existing BK_* numeric order") {
    assert(BoundaryKind.Fall.asUInt.litValue == 0)
    assert(BoundaryKind.Cond.asUInt.litValue == 1)
    assert(BoundaryKind.Call.asUInt.litValue == 2)
    assert(BoundaryKind.Ret.asUInt.litValue == 3)
    assert(BoundaryKind.Direct.asUInt.litValue == 4)
    assert(BoundaryKind.Ind.asUInt.litValue == 5)
    assert(BoundaryKind.ICall.asUInt.litValue == 6)
  }

  test("decoded uop packet matches the canonical D2 architectural shape") {
    val p = InterfaceParams()
    val decoded = new DecodedUop(p)

    assert(decoded.src.length == 3)
    assert(decoded.dst.length == 1)
    assert(decoded.pc.getWidth == 64)
    assert(decoded.opcode.getWidth == 12)
    assert(decoded.imm.getWidth == 64)
    assert(decoded.insnLen.getWidth == 4)
    assert(decoded.insnRaw.getWidth == 64)
    assert(decoded.rid.value.getWidth == 6)
    assert(decoded.bid.value.getWidth == 6)
    assert(decoded.gid.value.getWidth == 6)
    assert(decoded.lsid.getWidth == 32)
    assert(decoded.blockUid.getWidth == 64)
    assert(decoded.blockBid.getWidth == 64)
    assert(decoded.checkpointId.getWidth == 6)
    assert(decoded.uid.uid.getWidth == 64)
    assert(decoded.uid.fetchSlot.getWidth == 2)
  }

  test("renamed, issue, LSU, ROB, and trace packets keep model identity and block BID separate") {
    val p = InterfaceParams()
    val renamed = new RenamedUop(p)
    val iq = new IssueQueueEntry(p)
    val lsuReq = new LsuRequest(p)
    val robRow = new RobRow(p)
    val trace = new LinxTraceProbe(p)

    assert(renamed.src.length == 3)
    assert(renamed.dst.length == 1)
    assert(renamed.rid.value.getWidth == p.robIndexWidth)
    assert(renamed.bid.value.getWidth == p.robIndexWidth)
    assert(renamed.gid.value.getWidth == p.robIndexWidth)
    assert(renamed.blockBid.getWidth == 64)
    assert(iq.issueSlot.getWidth == p.iqIndexWidth)
    assert(lsuReq.modelLsId.value.getWidth == p.robIndexWidth)
    assert(lsuReq.lsid.getWidth == 32)
    assert(lsuReq.mask.getWidth == 8)
    assert(robRow.src.length == 3)
    assert(robRow.blockBid.getWidth == 64)
    assert(trace.bid.value.getWidth == p.robIndexWidth)
    assert(trace.blockBid.getWidth == 64)
  }

  test("common interface packets elaborate through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new InterfaceBundleProbe(InterfaceParams()))

    assert(sv.contains("module InterfaceBundleProbe"))
    assert(sv.contains("io_renamed_blockBid"))
    assert(sv.contains("io_trace_blockBid"))
  }
}
