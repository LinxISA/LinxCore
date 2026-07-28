package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class OooIfuD1IngressSpec extends AnyFunSuite with ChiselSim {
  private val ifuP = InterfaceParams()
  private val oooP = OooParams(instructionDecodeWidth = 4)

  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).getOrElse(
      fail(s"missing generated recipe for $symbol"))

  private def clear(dut: OooIfuD1Ingress): Unit = {
    dut.io.ifuD1.valid.poke(false.B)
    dut.io.ifuD1.bits.poke(0.U.asTypeOf(dut.io.ifuD1.bits))
    dut.io.selectStid.poke(0.U)
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
    dut.io.fence.foreach(_.poke(false.B))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.ctuParentClaim.ready.poke(false.B)
    dut.io.ctuExpansionPlan.valid.poke(false.B)
    dut.io.ctuExpansionPlan.bits.poke(
      0.U.asTypeOf(dut.io.ctuExpansionPlan.bits))
    dut.io.ctuChild.valid.poke(false.B)
    dut.io.ctuChild.bits.poke(0.U.asTypeOf(dut.io.ctuChild.bits))
    dut.io.ctuRecoveryPrepare.valid.poke(false.B)
    dut.io.ctuRecoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.ctuRecoveryPrepare.bits))
    dut.io.ctuRecoveryApply.poke(false.B)
    dut.io.ctuRecoveryAbort.poke(false.B)
    dut.io.out.ready.poke(true.B)
  }

  private def pokeEntry(
      dut: OooIfuD1Ingress,
      lane: Int,
      symbol: String,
      instructionId: Int,
      stid: Int = 0,
      epoch: Int = 3): Unit = {
    val entry = dut.io.ifuD1.bits.entries(lane)
    val recipe = rule(symbol)
    val pc = 0x8000 + instructionId * 8
    entry.pc.poke(pc.U)
    entry.instructionUid.poke(instructionId.U)
    entry.transactionId.poke((0x1000 + instructionId).U)
    entry.insn.poke(recipe.value.U)
    entry.lenBytes.poke(recipe.lenBytes.U)
    entry.identity.peId.poke(4.U)
    entry.identity.threadId.poke(stid.U)
    entry.identity.fetchPacketUid.poke((0x2000 + instructionId).U)
    entry.identity.fetchSeq.poke((0x3000 + instructionId).U)
    entry.identity.fetchSlot.poke(lane.U)
    entry.identity.checkpointId.poke(2.U)
    entry.identity.epoch.poke(epoch.U)
    entry.prediction.valid.poke(true.B)
    entry.prediction.predictionTag.poke((0x4000 + instructionId).U)
    entry.prediction.requestPc.poke(pc.U)
    entry.prediction.branchPc.poke(pc.U)
    entry.prediction.target.poke((pc + 0x100).U)
    entry.prediction.fallthroughPc.poke((pc + recipe.lenBytes).U)
    entry.prediction.checkpointId.poke(2.U)
    entry.prediction.epoch.poke(epoch.U)
  }

  private def enqueue(
      dut: OooIfuD1Ingress,
      symbols: Seq[String],
      firstId: Int): Unit = {
    dut.io.ifuD1.bits.poke(0.U.asTypeOf(dut.io.ifuD1.bits))
    symbols.zipWithIndex.foreach { case (symbol, lane) =>
      pokeEntry(dut, lane, symbol, firstId + lane)
    }
    dut.io.ifuD1.bits.validMask.poke(((1 << symbols.size) - 1).U)
    dut.io.ifuD1.valid.poke(true.B)
    dut.io.ifuD1.ready.expect(true.B)
    dut.clock.step()
    dut.io.ifuD1.valid.poke(false.B)
  }

  test("connects IFU raw rows through canonical decode and boundary fusion") {
    simulate(new OooIfuD1Ingress(ifuP, oooP, depthPerStid = 8)) { dut =>
      clear(dut)
      enqueue(dut, Seq("OP_ADD", "OP_BSTOP"), firstId = 10)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uopMask.expect(1.U)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(2.U)
      dut.io.out.bits.uops(0).identity.parents(0).key.instructionId.expect(10.U)
      dut.io.out.bits.uops(0).identity.parents(1).key.instructionId.expect(11.U)
      dut.io.out.bits.uops(0).identity.boundary.stop.expect(true.B)
      dut.io.out.bits.uops(0).identity.parents(0).prediction.transactionId
        .expect((0x1000 + 10).U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.rawCounts(0).expect(0.U)
    }
  }

  test("claims an exact CTU parent instead of forwarding a diversion mask to D2") {
    simulate(new OooIfuD1Ingress(ifuP, oooP, depthPerStid = 8)) { dut =>
      clear(dut)
      enqueue(dut, Seq("OP_FENTRY"), firstId = 20)
      dut.clock.step()

      dut.io.out.valid.expect(false.B)
      dut.io.ctuParentClaim.valid.expect(true.B)
      val parent = dut.io.ctuParentClaim.bits.parent.parent
      parent.key.valid.expect(true.B)
      parent.key.peId.expect(4.U)
      parent.key.stid.expect(0.U)
      parent.key.instructionId.expect(20.U)
      parent.rawInstruction.expect(rule("OP_FENTRY").value.U)
      parent.prediction.transactionId.expect((0x1000 + 20).U)
      parent.prediction.fetchPacketUid.expect((0x2000 + 20).U)
      parent.prediction.fetchSeq.expect((0x3000 + 20).U)
      parent.prediction.predictionTag.expect((0x4000 + 20).U)
      dut.io.ctuParentClaim.bits.lease.parent.instructionId.expect(20.U)
      dut.io.ctuParentClaim.bits.lease.templateGroupId.expect(0.U)
      dut.io.ctuParentClaim.bits.lease.generation.expect(0.U)
    }
  }
}
