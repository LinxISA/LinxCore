package linxcore.execute

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, DispatchTarget, InterfaceParams, OperandClass, RenamedUop}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

class ScalarIssueIngressSkid2Spec extends AnyFunSuite with ChiselSim {
  private val P = InterfaceParams(robEntries = 16)

  private def pokeRobId(id: ROBID, valid: Boolean, wrap: Boolean, value: Int): Unit = {
    id.valid.poke(valid.B)
    id.wrap.poke(wrap.B)
    id.value.poke(value.U)
  }

  private def pokeRow(
      row: RenamedUop,
      pc: Long,
      ridValue: Int,
      bankHint: Int,
      opcode: Int = FrontendOpcodeDecodeTable.OP_ADDI,
      dispatch: DispatchTarget.Type = DispatchTarget.Alu,
      isStore: Boolean = false,
      isLoad: Boolean = false,
      pairFirst: Boolean = false,
      bidWrap: Boolean = false,
      ridWrap: Boolean = false,
      lsid: Long = 0x100L): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.peId.poke(2.U)
    row.threadId.poke(0.U)
    row.pc.poke(pc.U)
    row.opcode.poke(opcode.U(P.opcodeWidth.W))
    row.dispatchTarget.poke(dispatch)
    for (idx <- 0 until 3) {
      row.src(idx).valid.poke((idx != 2).B)
      row.src(idx).operandClass.poke(OperandClass.P)
      row.src(idx).archTag.poke((8 + idx).U)
      row.src(idx).relTag.poke((8 + idx).U)
      row.src(idx).physTag.poke((12 + ridValue + idx).U)
    }
    row.dst(0).valid.poke(true.B)
    row.dst(0).kind.poke(DestinationKind.Gpr)
    row.dst(0).archTag.poke(5.U)
    row.dst(0).relTag.poke(5.U)
    row.dst(0).physTag.poke((32 + ridValue).U)
    row.pairFirstDst.valid.poke(pairFirst.B)
    row.pairFirstDst.kind.poke(DestinationKind.Gpr)
    row.pairFirstDst.archTag.poke(6.U)
    row.pairFirstDst.relTag.poke(6.U)
    row.pairFirstDst.physTag.poke((40 + ridValue).U)
    row.imm.poke((0x5550L + ridValue).U)
    row.immValid.poke(true.B)
    pokeRobId(row.bid, valid = true, wrap = bidWrap, value = 2 + bankHint)
    pokeRobId(row.gid, valid = true, wrap = true, value = 7 + bankHint)
    pokeRobId(row.rid, valid = true, wrap = ridWrap, value = ridValue)
    row.lsid.poke(lsid.U)
    row.isLoad.poke(isLoad.B)
    row.isStore.poke(isStore.B)
    row.blockBidValid.poke(true.B)
    row.blockBid.poke((0x80 + bankHint).U)
    row.uid.uid.poke((0x20 + ridValue).U)
    row.uid.parentUid.poke((0x120 + ridValue).U)
    row.uid.fetchSlot.poke(bankHint.U)
  }

  private def init(dut: ScalarIssueIngressSkid2): Unit = {
    dut.io.inValid.poke(false.B)
    pokeRow(dut.io.in, pc = 0x1000L, ridValue = 1, bankHint = 0)
    dut.io.inBank.poke(0.U)
    dut.io.flushValid.poke(false.B)
    dut.io.drainCanConsume(0).poke(false.B)
    dut.io.drainCanConsume(1).poke(false.B)
  }

  private def enqueue(
      dut: ScalarIssueIngressSkid2,
      pc: Long,
      ridValue: Int,
      bank: Int,
      opcode: Int = FrontendOpcodeDecodeTable.OP_ADDI,
      dispatch: DispatchTarget.Type = DispatchTarget.Alu,
      isStore: Boolean = false,
      isLoad: Boolean = false,
      pairFirst: Boolean = false,
      bidWrap: Boolean = false,
      ridWrap: Boolean = false,
      lsid: Long = 0x100L): Unit = {
    dut.io.inValid.poke(true.B)
    dut.io.inBank.poke(bank.U)
    pokeRow(
      dut.io.in,
      pc = pc,
      ridValue = ridValue,
      bankHint = bank,
      opcode = opcode,
      dispatch = dispatch,
      isStore = isStore,
      isLoad = isLoad,
      pairFirst = pairFirst,
      bidWrap = bidWrap,
      ridWrap = ridWrap,
      lsid = lsid)
    dut.io.inReady.expect(true.B)
    dut.clock.step()
    dut.io.inValid.poke(false.B)
  }

  test("empty skid can flow input directly to consume credit without capture") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      dut.io.inValid.poke(true.B)
      dut.io.inBank.poke(1.U)
      pokeRow(dut.io.in, pc = 0x0800L, ridValue = 1, bankHint = 1)
      dut.io.drainCanConsume(1).poke(true.B)

      dut.io.empty.expect(true.B)
      dut.io.inReady.expect(true.B)
      dut.io.drainPresent(0).expect(false.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x0800L.U)
      dut.io.drainPreview(1).rid.value.expect(1.U)
      dut.io.drainFire(1).expect(true.B)
      dut.io.olderOnlyFire.expect(true.B)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.io.empty.expect(true.B)
      dut.io.drainPresent(1).expect(false.B)
    }
  }

  test("empty skid captures current input when consume credit is absent") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      dut.io.inValid.poke(true.B)
      dut.io.inBank.poke(0.U)
      pokeRow(dut.io.in, pc = 0x0840L, ridValue = 2, bankHint = 0)
      dut.io.drainCanConsume(0).poke(false.B)

      dut.io.inReady.expect(true.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x0840L.U)
      dut.io.drainFire(0).expect(false.B)
      dut.clock.step()

      dut.io.count.expect(1.U)
      dut.io.inValid.poke(false.B)
      pokeRow(dut.io.in, pc = 0x0880L, ridValue = 3, bankHint = 1)
      dut.io.inBank.poke(1.U)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x0840L.U)
      dut.io.drainPreview(0).rid.value.expect(2.U)
    }
  }

  test("one resident and current input can dual fire in order") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x08c0L, ridValue = 3, bank = 0)
      dut.io.inValid.poke(true.B)
      dut.io.inBank.poke(1.U)
      pokeRow(dut.io.in, pc = 0x08c4L, ridValue = 4, bankHint = 1)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(true.B)

      dut.io.count.expect(1.U)
      dut.io.inReady.expect(true.B)
      dut.io.dualDrainEligible.expect(true.B)
      dut.io.dualDrainFire.expect(true.B)
      dut.io.drainFire(0).expect(true.B)
      dut.io.drainFire(1).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x08c0L.U)
      dut.io.drainPreview(1).pc.expect(0x08c4L.U)
      dut.clock.step()

      dut.io.empty.expect(true.B)
    }
  }

  test("one resident older-only fire captures current input as next older") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x0900L, ridValue = 5, bank = 0)
      dut.io.inValid.poke(true.B)
      dut.io.inBank.poke(1.U)
      pokeRow(dut.io.in, pc = 0x0904L, ridValue = 6, bankHint = 1)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(false.B)

      dut.io.inReady.expect(true.B)
      dut.io.dualDrainEligible.expect(true.B)
      dut.io.dualDrainFire.expect(false.B)
      dut.io.olderOnlyFire.expect(true.B)
      dut.io.drainFire(0).expect(true.B)
      dut.io.drainFire(1).expect(false.B)
      dut.io.drainPreview(0).pc.expect(0x0900L.U)
      dut.io.drainPreview(1).pc.expect(0x0904L.U)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.io.count.expect(1.U)
      dut.io.drainPresent(0).expect(false.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x0904L.U)
      dut.io.drainPreview(1).rid.value.expect(6.U)
    }
  }

  test("one resident younger-only credit captures current input without reordering") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x0940L, ridValue = 7, bank = 0)
      dut.io.inValid.poke(true.B)
      dut.io.inBank.poke(1.U)
      pokeRow(dut.io.in, pc = 0x0944L, ridValue = 8, bankHint = 1)
      dut.io.drainCanConsume(0).poke(false.B)
      dut.io.drainCanConsume(1).poke(true.B)

      dut.io.inReady.expect(true.B)
      dut.io.dualDrainEligible.expect(true.B)
      dut.io.dualDrainFire.expect(false.B)
      dut.io.olderOnlyFire.expect(false.B)
      dut.io.drainFire(0).expect(false.B)
      dut.io.drainFire(1).expect(false.B)
      dut.io.drainPreview(0).pc.expect(0x0940L.U)
      dut.io.drainPreview(1).pc.expect(0x0944L.U)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.io.count.expect(2.U)
      dut.io.full.expect(true.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x0940L.U)
      dut.io.drainPreview(0).rid.value.expect(7.U)
      dut.io.drainPreview(1).pc.expect(0x0944L.U)
      dut.io.drainPreview(1).rid.value.expect(8.U)
    }
  }

  test("drains two fixed scalar ALU rows to different banks in one cycle") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x1000L, ridValue = 1, bank = 0)
      enqueue(dut, pc = 0x1004L, ridValue = 2, bank = 1)
      dut.io.count.expect(2.U)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.dualDrainEligible.expect(true.B)
      dut.io.dualDrainFire.expect(true.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x1000L.U)
      dut.io.drainPreview(1).pc.expect(0x1004L.U)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("same-bank candidates drain only the older row") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x2000L, ridValue = 3, bank = 0)
      enqueue(dut, pc = 0x2004L, ridValue = 4, bank = 0)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.dualDrainEligible.expect(false.B)
      dut.io.olderOnlyFire.expect(true.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(false.B)
      dut.io.drainPreview(0).pc.expect(0x2000L.U)
      dut.clock.step()
      dut.io.count.expect(1.U)
      dut.io.drainPreview(0).pc.expect(0x2004L.U)
    }
  }

  test("unsafe younger row keeps the pair on older-only drain") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x3000L, ridValue = 5, bank = 0)
      enqueue(
        dut,
        pc = 0x3004L,
        ridValue = 6,
        bank = 1,
        opcode = FrontendOpcodeDecodeTable.OP_SDI,
        dispatch = DispatchTarget.Alu,
        isStore = true)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.dualDrainEligible.expect(false.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(false.B)
      dut.io.drainPreview(0).pc.expect(0x3000L.U)
      dut.clock.step()
      dut.io.count.expect(1.U)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x3004L.U)
    }
  }

  test("backpressure holds registered payload stable") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x4000L, ridValue = 7, bank = 1)
      dut.io.drainCanConsume(0).poke(false.B)
      dut.io.drainCanConsume(1).poke(false.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x4000L.U)
      dut.io.drainPreview(1).rid.value.expect(7.U)
      dut.clock.step(3)
      dut.io.count.expect(1.U)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x4000L.U)
      dut.io.drainPreview(1).rid.value.expect(7.U)
    }
  }

  test("dual presentation does not depend on younger consume credit and compacts in order") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x4800L, ridValue = 13, bank = 0)
      enqueue(dut, pc = 0x4804L, ridValue = 14, bank = 1)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(false.B)
      dut.io.dualDrainEligible.expect(true.B)
      dut.io.dualDrainFire.expect(false.B)
      dut.io.olderOnlyFire.expect(true.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x4800L.U)
      dut.io.drainPreview(1).pc.expect(0x4804L.U)
      dut.io.drainPreview(1).rid.value.expect(14.U)
      dut.clock.step()

      dut.io.count.expect(1.U)
      dut.io.drainCanConsume(0).poke(false.B)
      dut.io.drainCanConsume(1).poke(false.B)
      dut.io.drainPresent(0).expect(false.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x4804L.U)
      dut.io.drainPreview(1).rid.value.expect(14.U)
      dut.clock.step(2)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x4804L.U)
      dut.io.drainPreview(1).rid.value.expect(14.U)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.olderOnlyFire.expect(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("younger-only consume credit does not fire or dequeue a dual-eligible pair") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x4c00L, ridValue = 6, bank = 0)
      enqueue(dut, pc = 0x4c04L, ridValue = 7, bank = 1)
      dut.io.drainCanConsume(0).poke(false.B)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.dualDrainEligible.expect(true.B)
      dut.io.dualDrainFire.expect(false.B)
      dut.io.olderOnlyFire.expect(false.B)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainFire(0).expect(false.B)
      dut.io.drainFire(1).expect(false.B)
      dut.io.drainPreview(0).pc.expect(0x4c00L.U)
      dut.io.drainPreview(1).pc.expect(0x4c04L.U)
      dut.clock.step()

      dut.io.count.expect(2.U)
      dut.io.drainPresent(0).expect(true.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(0).pc.expect(0x4c00L.U)
      dut.io.drainPreview(1).pc.expect(0x4c04L.U)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(false.B)
      dut.io.drainFire(0).expect(true.B)
      dut.io.drainFire(1).expect(false.B)
      dut.clock.step()
      dut.io.count.expect(1.U)
      dut.io.drainPresent(0).expect(false.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).pc.expect(0x4c04L.U)
    }
  }

  test("flush clears both resident rows and suppresses drain") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x5000L, ridValue = 8, bank = 0)
      enqueue(dut, pc = 0x5004L, ridValue = 9, bank = 1)
      dut.io.flushValid.poke(true.B)
      dut.io.inReady.expect(false.B)
      dut.io.drainPresent(0).expect(false.B)
      dut.io.drainPresent(1).expect(false.B)
      dut.clock.step()
      dut.io.flushValid.poke(false.B)
      dut.io.empty.expect(true.B)
      dut.io.inReady.expect(true.B)
    }
  }

  test("wrap and full RenamedUop identity sidecars are preserved") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(
        dut,
        pc = 0x6000L,
        ridValue = 10,
        bank = 1,
        bidWrap = true,
        ridWrap = true,
        lsid = 0x12345678L)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.drainPresent(1).expect(true.B)
      dut.io.drainPreview(1).peId.expect(2.U)
      dut.io.drainPreview(1).pc.expect(0x6000L.U)
      dut.io.drainPreview(1).bid.valid.expect(true.B)
      dut.io.drainPreview(1).bid.wrap.expect(true.B)
      dut.io.drainPreview(1).bid.value.expect(3.U)
      dut.io.drainPreview(1).gid.valid.expect(true.B)
      dut.io.drainPreview(1).gid.wrap.expect(true.B)
      dut.io.drainPreview(1).gid.value.expect(8.U)
      dut.io.drainPreview(1).rid.valid.expect(true.B)
      dut.io.drainPreview(1).rid.wrap.expect(true.B)
      dut.io.drainPreview(1).rid.value.expect(10.U)
      dut.io.drainPreview(1).lsid.expect(0x12345678L.U)
      dut.io.drainPreview(1).src(0).physTag.expect(22.U)
      dut.io.drainPreview(1).dst(0).physTag.expect(42.U)
      dut.io.drainPreview(1).blockBidValid.expect(true.B)
      dut.io.drainPreview(1).blockBid.expect(0x81.U)
      dut.io.drainPreview(1).uid.uid.expect(0x2a.U)
      dut.io.drainPreview(1).uid.parentUid.expect(0x12a.U)
      dut.io.drainPreview(1).uid.fetchSlot.expect(1.U)
    }
  }

  test("full plus dequeue keeps upstream backpressured until the next cycle") {
    simulate(new ScalarIssueIngressSkid2(P)) { dut =>
      init(dut)
      enqueue(dut, pc = 0x7000L, ridValue = 11, bank = 0)
      enqueue(dut, pc = 0x7004L, ridValue = 12, bank = 1)
      pokeRow(dut.io.in, pc = 0x7008L, ridValue = 13, bankHint = 0)
      dut.io.inBank.poke(0.U)
      dut.io.inValid.poke(true.B)
      dut.io.full.expect(true.B)
      dut.io.inReady.expect(false.B)
      dut.io.drainCanConsume(0).poke(true.B)
      dut.io.drainCanConsume(1).poke(true.B)
      dut.io.dualDrainFire.expect(true.B)
      dut.clock.step()
      dut.io.inReady.expect(true.B)
      dut.io.count.expect(0.U)
    }
  }
}
