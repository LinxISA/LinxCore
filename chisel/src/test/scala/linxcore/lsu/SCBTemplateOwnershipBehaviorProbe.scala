package linxcore.lsu

import circt.stage.ChiselStage
import chisel3._
import org.scalatest.funsuite.AnyFunSuite

class SCBTemplateOwnershipBehaviorProbeIO extends Bundle {
  val reqValid = Input(Vec(2, Bool()))
  val reqOwnsStqRow = Input(Vec(2, Bool()))
  val reqLast = Input(Vec(2, Bool()))
  val reqStqIndex = Input(Vec(2, UInt(3.W)))
  val reqAddr = Input(Vec(2, UInt(64.W)))
  val reqData = Input(Vec(2, UInt(64.W)))
  val reqSize = Input(Vec(2, UInt(4.W)))

  val modelBatchReady = Output(Bool())
  val acceptedMask = Output(UInt(2.W))
  val stalledMask = Output(UInt(2.W))
  val structuralBlockedMask = Output(UInt(2.W))
  val commitFreeMaskValid = Output(Bool())
  val commitFreeMask = Output(UInt(8.W))
  val commitFreeCount = Output(UInt(2.W))
  val wakeupValid = Output(Vec(2, Bool()))
  val validMask = Output(UInt(4.W))
  val entryCount = Output(UInt(3.W))
}

class SCBTemplateOwnershipBehaviorProbe extends Module {
  val io = IO(new SCBTemplateOwnershipBehaviorProbeIO)

  private val bank = Module(new SCBRowBank(
    stqEntries = 8,
    scbEntries = 4,
    requestCount = 2,
    responseBufferDepth = 2,
    robEntries = 8))

  for (lane <- 0 until 2) {
    bank.io.reqs(lane) := 0.U.asTypeOf(bank.io.reqs(lane))
    bank.io.reqs(lane).valid := io.reqValid(lane)
    bank.io.reqs(lane).ownsStqRow := io.reqOwnsStqRow(lane)
    bank.io.reqs(lane).last := io.reqLast(lane)
    bank.io.reqs(lane).stqIndex := io.reqStqIndex(lane)
    bank.io.reqs(lane).addr := io.reqAddr(lane)
    bank.io.reqs(lane).data := io.reqData(lane)
    bank.io.reqs(lane).size := io.reqSize(lane)
  }

  bank.io.evictEnable := false.B
  bank.io.dcacheReady := false.B
  bank.io.dcacheWriteHit := false.B
  bank.io.dcacheTagHit := false.B
  bank.io.l2RequestReady := false.B
  bank.io.rawRespValid := false.B
  bank.io.rawRespTxnId := 0.U
  bank.io.rawRespWrite := false.B
  bank.io.rawRespUpgrade := false.B

  io.modelBatchReady := bank.io.modelBatchReady
  io.acceptedMask := bank.io.acceptedMask
  io.stalledMask := bank.io.stalledMask
  io.structuralBlockedMask := bank.io.structuralBlockedMask
  io.commitFreeMaskValid := bank.io.commitFreeMaskValid
  io.commitFreeMask := bank.io.commitFreeMask
  io.commitFreeCount := bank.io.commitFreeCount
  io.wakeupValid := VecInit(bank.io.wakeups.map(_.valid))
  io.validMask := bank.io.validMask
  io.entryCount := bank.io.entryCount
}

object EmitSCBTemplateOwnershipBehaviorProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/lsu-scb-template-ownership-behavior-probe")

  ChiselStage.emitSystemVerilogFile(
    new SCBTemplateOwnershipBehaviorProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}

class SCBTemplateOwnershipBehaviorProbeSpec extends AnyFunSuite {
  test("elaborates a public ownership wrapper around the real SCB row bank") {
    val sv = ChiselStage.emitSystemVerilog(new SCBTemplateOwnershipBehaviorProbe)

    assert(sv.contains("module SCBTemplateOwnershipBehaviorProbe"))
    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("io_reqOwnsStqRow_0"))
    assert(sv.contains("io_commitFreeMask"))
    assert(sv.contains("io_acceptedMask"))
  }
}
