package linxcore.common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.frontend.FrontendOpcodeDecodeTable

class ScalarSpAccessProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val decoded = Input(new DecodedUop(p))
  val renamed = Input(new RenamedUop(p))
  val decodedAccess = Output(new ScalarSpAccess(p))
  val renamedAccess = Output(new ScalarSpAccess(p))
  val transaction = Output(new ScalarSpTransaction(p))
}

class ScalarSpAccessProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ScalarSpAccessProbeIO(p))

  io.decodedAccess := ScalarSpAccess.classify(io.decoded)
  io.renamedAccess := ScalarSpAccess.classify(io.renamed)
  io.transaction := 0.U.asTypeOf(io.transaction)
  io.transaction.access := io.renamedAccess
  io.transaction.stid := io.renamed.threadId
  io.transaction.bid := io.renamed.bid
  io.transaction.rid := io.renamed.rid
  io.transaction.epoch := 0x5a.U(p.blockEpochWidth.W)
}

class ScalarSpAccessSpec extends AnyFunSuite with ChiselSim {
  private def clearDecoded(dut: ScalarSpAccessProbe): Unit = {
    dut.io.decoded.poke(0.U.asTypeOf(dut.io.decoded))
    dut.io.decoded.valid.poke(true.B)
  }

  private def clearRenamed(dut: ScalarSpAccessProbe): Unit = {
    dut.io.renamed.poke(0.U.asTypeOf(dut.io.renamed))
    dut.io.renamed.valid.poke(true.B)
  }

  test("frame macros classify as scalar SP read and write accesses") {
    simulate(new ScalarSpAccessProbe()) { dut =>
      clearDecoded(dut)
      clearRenamed(dut)
      dut.io.decoded.opcode.poke(FrontendOpcodeDecodeTable.OP_FENTRY.U)
      dut.io.renamed.opcode.poke(FrontendOpcodeDecodeTable.OP_FRET_STK.U)

      dut.io.decodedAccess.valid.expect(true.B)
      dut.io.decodedAccess.read.expect(true.B)
      dut.io.decodedAccess.write.expect(true.B)
      dut.io.renamedAccess.valid.expect(true.B)
      dut.io.renamedAccess.read.expect(true.B)
      dut.io.renamedAccess.write.expect(true.B)
    }
  }

  test("explicit x1 source and destination fields classify SP dependencies") {
    simulate(new ScalarSpAccessProbe()) { dut =>
      clearDecoded(dut)
      clearRenamed(dut)

      dut.io.decoded.src(1).valid.poke(true.B)
      dut.io.decoded.src(1).operandClass.poke(OperandClass.P)
      dut.io.decoded.src(1).archTag.poke(1.U)
      dut.io.renamed.dst(0).valid.poke(true.B)
      dut.io.renamed.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.renamed.dst(0).archTag.poke(1.U)

      dut.io.decodedAccess.valid.expect(true.B)
      dut.io.decodedAccess.read.expect(true.B)
      dut.io.decodedAccess.write.expect(false.B)
      dut.io.renamedAccess.valid.expect(true.B)
      dut.io.renamedAccess.read.expect(false.B)
      dut.io.renamedAccess.write.expect(true.B)
    }
  }

  test("pair-first destination can independently publish an SP write") {
    simulate(new ScalarSpAccessProbe()) { dut =>
      clearDecoded(dut)
      clearRenamed(dut)
      dut.io.decoded.pairFirstDst.valid.poke(true.B)
      dut.io.decoded.pairFirstDst.kind.poke(DestinationKind.Gpr)
      dut.io.decoded.pairFirstDst.archTag.poke(1.U)
      dut.io.renamed.pairFirstDst.valid.poke(true.B)
      dut.io.renamed.pairFirstDst.kind.poke(DestinationKind.Gpr)
      dut.io.renamed.pairFirstDst.archTag.poke(1.U)

      dut.io.decodedAccess.valid.expect(true.B)
      dut.io.decodedAccess.write.expect(true.B)
      dut.io.renamedAccess.valid.expect(true.B)
      dut.io.renamedAccess.write.expect(true.B)
    }
  }

  test("non-SP scalar fields stay inactive and transaction widths follow interface params") {
    val p = InterfaceParams(robEntries = 8, threadIdWidth = 5, blockEpochWidth = 9)
    val txn = new ScalarSpTransaction(p)
    assert(txn.stid.getWidth == 5)
    assert(txn.bid.value.getWidth == 3)
    assert(txn.rid.value.getWidth == 3)
    assert(txn.epoch.getWidth == 9)

    simulate(new ScalarSpAccessProbe(p)) { dut =>
      clearDecoded(dut)
      clearRenamed(dut)
      dut.io.decoded.src(0).valid.poke(true.B)
      dut.io.decoded.src(0).operandClass.poke(OperandClass.P)
      dut.io.decoded.src(0).archTag.poke(2.U)
      dut.io.renamed.dst(0).valid.poke(true.B)
      dut.io.renamed.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.renamed.dst(0).archTag.poke(2.U)
      dut.io.renamed.threadId.poke(17.U)
      dut.io.renamed.bid.valid.poke(true.B)
      dut.io.renamed.bid.value.poke(6.U)
      dut.io.renamed.rid.valid.poke(true.B)
      dut.io.renamed.rid.value.poke(7.U)

      dut.io.decodedAccess.valid.expect(false.B)
      dut.io.renamedAccess.valid.expect(false.B)
      dut.io.transaction.stid.expect(17.U)
      dut.io.transaction.bid.valid.expect(true.B)
      dut.io.transaction.bid.value.expect(6.U)
      dut.io.transaction.rid.valid.expect(true.B)
      dut.io.transaction.rid.value.expect(7.U)
      dut.io.transaction.epoch.expect(0x5a.U)
    }
  }
}
