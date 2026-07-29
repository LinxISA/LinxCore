package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

class OooIexLoadUnitSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    pcBufferEntries = 8,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    iexLoadTrackEntries = 4,
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexLoadUnit): Unit = {
    dut.io.agu.valid.poke(false.B)
    dut.io.agu.bits.poke(0.U.asTypeOf(dut.io.agu.bits))
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(
      0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.result.ready.poke(false.B)
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
  }

  private def pokeMember(target: RobMemberKey, ridSlot: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(1.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(5.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke(4.U)
  }

  private def pokeAgu(
      dut: OooIexLoadUnit,
      ridSlot: Int,
      address: BigInt,
      accessBytes: Int,
      signExtend: Boolean,
      ptag: Int = 31): Unit = {
    val request = dut.io.agu.bits
    request.poke(0.U.asTypeOf(request))
    val row = request.execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    pokeMember(row.member, ridSlot)
    request.address.poke(address.U)
    request.accessBytes.poke(accessBytes.U)
    request.signExtend.poke(signExtend.B)
    request.destination.valid.poke(true.B)
    request.destination.kind.poke(DestinationKind.Gpr)
    request.destination.atag.poke(6.U)
    request.destination.ptag.poke(ptag.U)
    request.destination.ptagGeneration.poke(2.U)
    dut.io.agu.valid.poke(true.B)
  }

  private def pokeLoad(
      target: OooIexLoadGeneration,
      ridSlot: Int,
      generation: BigInt): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    pokeMember(target.producer, ridSlot)
    target.generation.poke(generation.U)
  }

  private def pokeResponse(
      dut: OooIexLoadUnit,
      ridSlot: Int,
      generation: BigInt,
      kind: OooIexLoadResponseKind.Type,
      data: BigInt = 0,
      cause: BigInt = 0): Unit = {
    dut.io.memoryResponse.bits.poke(
      0.U.asTypeOf(dut.io.memoryResponse.bits))
    pokeLoad(dut.io.memoryResponse.bits.load, ridSlot, generation)
    dut.io.memoryResponse.bits.kind.poke(kind)
    dut.io.memoryResponse.bits.data.poke(data.U)
    dut.io.memoryResponse.bits.faultCause.poke(cause.U)
    dut.io.memoryResponse.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooIexLoadUnit,
      newOccupied: Int): Unit = {
    val plan = dut.io.recoveryApply.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(1.U)
    plan.oldHead.ridSlot.poke(0.U)
    plan.oldHead.ridGeneration.poke(1.U)
    plan.oldOccupied.poke(4.U)
    plan.newOccupied.poke(newOccupied.U)
    dut.io.recoveryApply.valid.poke(true.B)
  }

  test("allocates a generation and retains an extended hit result") {
    simulate(new OooIexLoadUnit(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeAgu(dut, ridSlot = 1, address = 0x4000,
        accessBytes = 1, signExtend = true)
      dut.io.agu.ready.expect(true.B)
      dut.clock.step()
      dut.io.agu.valid.poke(false.B)
      dut.io.occupied.expect(1.U)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x4000.U)
      val generation = dut.io.memoryRequest.bits.load.generation.peek().litValue
      assert(generation != 0)
      dut.clock.step(2)
      dut.io.memoryRequest.bits.load.generation.expect(generation.U)

      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.speculativeWakeup.valid.expect(true.B)
      dut.io.speculativeWakeup.bits.load.generation.expect(generation.U)
      dut.io.speculativeWakeup.bits.ptag.expect(31.U)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      pokeResponse(dut, ridSlot = 1, generation = generation,
        kind = OooIexLoadResponseKind.Hit, data = 0x80)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.io.cancel.valid.expect(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.data.expect(BigInt("ffffffffffffff80", 16).U)
      dut.io.bypass.valid.expect(true.B)
      dut.io.bypass.bits.load.generation.expect(generation.U)
      dut.clock.step(2)
      dut.io.result.bits.data.expect(BigInt("ffffffffffffff80", 16).U)

      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.occupied.expect(0.U)
      dut.io.bypass.valid.expect(false.B)
    }
  }

  test("cancels a missed generation and retries with a new generation") {
    simulate(new OooIexLoadUnit(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeAgu(dut, ridSlot = 2, address = 0x5000,
        accessBytes = 4, signExtend = false)
      dut.clock.step()
      dut.io.agu.valid.poke(false.B)
      val first = dut.io.memoryRequest.bits.load.generation.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      pokeResponse(dut, ridSlot = 2, generation = first,
        kind = OooIexLoadResponseKind.Miss)
      dut.io.cancel.valid.expect(true.B)
      dut.io.cancel.bits.load.generation.expect(first.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryRequest.valid.expect(true.B)
      val second = dut.io.memoryRequest.bits.load.generation.peek().litValue
      assert(second != first)
      dut.io.speculativeWakeup.valid.expect(false.B)

      pokeResponse(dut, ridSlot = 2, generation = first,
        kind = OooIexLoadResponseKind.Hit, data = 0x1234)
      dut.io.responseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.speculativeWakeup.valid.expect(true.B)
      dut.io.speculativeWakeup.bits.load.generation.expect(second.U)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)
      pokeResponse(dut, ridSlot = 2, generation = second,
        kind = OooIexLoadResponseKind.Hit,
        data = BigInt("deadbeef", 16))
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.data.expect(BigInt("deadbeef", 16).U)
    }
  }

  test("retains precise faults and drops recovered outstanding loads") {
    simulate(new OooIexLoadUnit(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeAgu(dut, ridSlot = 1, address = 0x6000,
        accessBytes = 8, signExtend = false)
      dut.clock.step()
      dut.io.agu.valid.poke(false.B)
      val faultGeneration =
        dut.io.memoryRequest.bits.load.generation.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)
      pokeResponse(dut, ridSlot = 1, generation = faultGeneration,
        kind = OooIexLoadResponseKind.Fault, cause = 13)
      dut.io.cancel.valid.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.faultValid.expect(true.B)
      dut.io.result.bits.faultCause.expect(13.U)
      dut.io.bypass.valid.expect(false.B)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()

      dut.io.result.ready.poke(false.B)
      pokeAgu(dut, ridSlot = 3, address = 0x7000,
        accessBytes = 8, signExtend = false)
      dut.clock.step()
      dut.io.agu.valid.poke(false.B)
      val killedGeneration =
        dut.io.memoryRequest.bits.load.generation.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)
      pokeRecovery(dut, newOccupied = 3)
      dut.io.occupied.expect(1.U)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.occupied.expect(0.U)
      pokeResponse(dut, ridSlot = 3, generation = killedGeneration,
        kind = OooIexLoadResponseKind.Hit, data = 9)
      dut.io.responseRejected.valid.expect(true.B)
    }
  }

  test("rejects malformed requests and duplicate live producers") {
    simulate(new OooIexLoadUnit(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeAgu(dut, ridSlot = 1, address = 0x8000,
        accessBytes = 3, signExtend = false)
      dut.io.agu.ready.expect(false.B)
      dut.io.acceptRejected.valid.expect(true.B)
      dut.io.acceptRejected.bits.accessExact.expect(false.B)
      dut.io.agu.bits.accessBytes.poke(8.U)
      dut.io.agu.ready.expect(true.B)
      dut.clock.step()

      pokeAgu(dut, ridSlot = 1, address = 0x8008,
        accessBytes = 8, signExtend = false)
      dut.io.agu.ready.expect(false.B)
      dut.io.acceptRejected.bits.duplicateProducer.expect(true.B)
      dut.io.agu.bits.execute.i2.row.schedule.peId.poke(2.U)
      dut.io.acceptRejected.bits.identityExact.expect(false.B)
    }
  }
}
