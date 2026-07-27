package linxcore.ooo

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class OooBundleProbeIO(val p: OooParams = OooParams()) extends Bundle {
  val plan = Input(new OooD2VirtualPlan(p))
  val reserve = Output(new OooD3Reservation(p))
  val publish = Output(new OooS1Publication(p))
  val recovery = Output(new ExactRecoveryKey(p))
  val nonFlush = Output(new NonFlushWindow(p))
  val mapPayload = Output(new PMapPayload(p))
}

class OooBundleProbe(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooBundleProbeIO(p))

  io.reserve := 0.U.asTypeOf(io.reserve)
  io.reserve.plan := io.plan
  io.publish := 0.U.asTypeOf(io.publish)
  io.publish.reservation := io.reserve
  io.recovery := 0.U.asTypeOf(io.recovery)
  io.nonFlush := 0.U.asTypeOf(io.nonFlush)
  io.mapPayload := 0.U.asTypeOf(io.mapPayload)
}

class OooBundlesSpec extends AnyFunSuite {
  test("native BID and exact ROB member identities remain structurally separate") {
    val p = OooParams()
    val bid = new NativeBid(p)
    val pointer = new BrobPointer(p)
    val group = new RobGroupKey(p)
    val member = new RobMemberKey(p)

    assert(bid.value.getWidth == 8)
    assert(pointer.bid.value.getWidth == 8)
    assert(pointer.generation.getWidth == p.brobGenerationWidth)
    assert(group.stid.getWidth == 2)
    assert(group.ridSlot.getWidth == 6)
    assert(group.ridGeneration.getWidth == p.ridGenerationWidth)
    assert(member.memberIndex.getWidth == p.robMemberIndexWidth)
    assert(member.residentGeneration.getWidth == p.residentGenerationWidth)
  }

  test("canonical uop identity preserves three architectural parents and dual boundaries") {
    val p = OooParams()
    val identity = new CanonicalUopIdentity(p)

    assert(identity.parents.length == 3)
    assert(identity.parents.head.pc.getWidth == 64)
    assert(identity.parents.head.rawInstruction.getWidth == 64)
    assert(identity.boundary.start.getWidth == 1)
    assert(identity.boundary.stop.getWidth == 1)
    assert(identity.boundary.opening.bid.value.getWidth == 8)
    assert(identity.boundary.closing.bid.value.getWidth == 8)
  }

  test("D2 D3 and S1 packets expose distinct preview reserve and publish transactions") {
    val p = OooParams()
    val plan = new OooD2VirtualPlan(p)
    val reserve = new OooD3Reservation(p)
    val publish = new OooS1Publication(p)

    assert(plan.instructionMask.getWidth == 4)
    assert(plan.uopMask.getWidth == 8)
    assert(plan.demand.dispatchWritesByClass.length == p.iqClassCount)
    assert(plan.demand.dispatchWritesByBank.length == p.iqBankCount)
    assert(reserve.groups.length == p.instructionDecodeWidth)
    assert(reserve.ptags.length == p.renameWidth)
    assert(reserve.dispatch.length == p.dispatchWidth)
    assert(publish.publishedUopMask.getWidth == p.dispatchWidth)
  }

  test("production packet family elaborates at widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(instructionDecodeWidth = width)
      val sv = ChiselStage.emitSystemVerilog(new OooBundleProbe(p))
      assert(sv.contains("module OooBundleProbe"))
      assert(sv.contains("io_reserve_plan_transactionId"))
      assert(sv.contains("io_nonFlush_head_ridGeneration"))
    }
  }
}
