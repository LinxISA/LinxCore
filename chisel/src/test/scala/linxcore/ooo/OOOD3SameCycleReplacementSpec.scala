package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class OOOD3SameCycleReplacementSpec extends AnyFunSuite with ChiselSim {
  private val p = SimulationParamProfiles.W2

  private def clear(dut: OOOD3S1Graph): Unit = {
    dut.io.fromD2.valid.poke(false.B)
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.bruDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.aguDispatch.foreach(_.ready.poke(false.B))
    dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.systemDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.cmdDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.fastResult.ready.poke(true.B)
    dut.io.iex.pcBufferReadAddress.foreach(
      _.poke(0.U.asTypeOf(dut.io.iex.pcBufferReadAddress.head)))
    dut.io.iex.robNoflushReady.valid.poke(false.B)
    dut.io.iex.robNoflushReady.bits.poke(
      0.U.asTypeOf(dut.io.iex.robNoflushReady.bits))
    dut.io.iex.robNoflush.ready.poke(true.B)
    dut.io.iex.robResolve.foreach { resolve =>
      resolve.valid.poke(false.B)
      resolve.bits.poke(0.U.asTypeOf(resolve.bits))
    }
    dut.io.iex.recoveryEvent.valid.poke(false.B)
    dut.io.iex.recoveryEvent.bits.poke(
      0.U.asTypeOf(dut.io.iex.recoveryEvent.bits))
    dut.io.commit.ready.poke(false.B)
    dut.io.trap.ready.poke(true.B)
    dut.io.interrupt.valid.poke(false.B)
    dut.io.interrupt.bits.poke(0.U.asTypeOf(dut.io.interrupt.bits))
    Seq(dut.io.recoveryToD1, dut.io.iex.recovery, dut.io.recoveryToIfu,
      dut.io.recoveryToCtu, dut.io.recoveryToLsu).foreach { target =>
      target.prepare.ready.poke(true.B)
      target.prepared.valid.poke(false.B)
      target.prepared.bits.poke(0.U.asTypeOf(target.prepared.bits))
    }
    dut.io.trace.ready.poke(true.B)
  }

  private def pokeLoad(
      dut: OOOD3S1Graph,
      instructionId: Int,
      ridSlot: Int,
      ridGeneration: Int,
      atag: Int,
      pc: BigInt): Unit = {
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(1.U)
    dut.io.fromD2.bits.groupCount.poke(1.U)

    val group = dut.io.fromD2.bits.groups(0)
    group.valid.poke(true.B)
    group.peId.poke(1.U)
    group.stid.poke(0.U)
    group.ridSlot.poke(ridSlot.U)
    group.ridGeneration.poke(ridGeneration.U)

    val uop = dut.io.fromD2.bits.entries(0).uop
    uop.valid.poke(true.B)
    uop.instruction.parent.identity.peId.poke(1.U)
    uop.instruction.parent.identity.stid.poke(0.U)
    uop.instruction.parent.identity.instructionId.poke(instructionId.U)
    uop.instruction.parent.identity.epoch.poke(3.U)
    uop.instruction.parent.pc.poke(pc.U)
    uop.instruction.parent.lengthBytes.poke(4.U)
    uop.rob.peId.poke(1.U)
    uop.rob.stid.poke(0.U)
    uop.rob.ridSlot.poke(ridSlot.U)
    uop.rob.ridGeneration.poke(ridGeneration.U)
    uop.rob.memberIndex.poke(0.U)
    uop.uopClass.poke(UopClass.Agu)
    uop.blockStart.poke(true.B)
    uop.blockStop.poke(true.B)
    uop.destinations(0).valid.poke(true.B)
    uop.destinations(0).kind.poke(OperandKind.Gpr)
    uop.destinations(0).atag.poke(atag.U)
    uop.memory.valid.poke(true.B)
    uop.memory.isLoad.poke(true.B)
    uop.memory.requestCount.poke(1.U)
    uop.memory.addressMode.poke(MemoryAddressMode.BaseOffset)
    uop.memory.accessBytes.poke(8.U)
    uop.memory.writebackValid.poke(true.B)

    val classification = uop.classification
    classification.valid.poke(true.B)
    classification.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    classification.kind.poke(OooOpcodeRecipeKind.Single.U)
    classification.uopCountMin.poke(1.U)
    classification.uopCountMax.poke(1.U)
    classification.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    classification.dispatchClass.poke(OooDispatchClass.Agu.U)
    classification.dispatchWrites.poke(1.U)
    classification.dispatchDemand(OooDispatchClass.Agu - 1).poke(1.U)
    classification.executionPipeCapability(
      OooDispatchClass.Agu - 1).poke(
        OooIexDomainCapability.mask(
          OooIexDomainCapability.LoadAddress).U)
  }

  private def expectRob(
      target: RobIdentity,
      ridSlot: Int,
      ridGeneration: Int): Unit = {
    target.peId.expect(1.U)
    target.stid.expect(0.U)
    target.ridSlot.expect(ridSlot.U)
    target.ridGeneration.expect(ridGeneration.U)
    target.memberIndex.expect(0.U)
    target.residentGeneration.expect(0.U)
    target.bid.expect(ridSlot.U)
    target.brobGeneration.expect(ridGeneration.U)
  }

  test("accepts a same-STID replacement on the final D3 publication edge") {
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      pokeLoad(
        dut,
        instructionId = 0x80,
        ridSlot = 0,
        ridGeneration = 0,
        atag = 1,
        pc = 0x1000)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()

      // The old row is now the complete retained D3 prefix. Present its
      // same-STID successor while the old row publishes into blocked AGU
      // dispatch. Both transfers must occur on this edge.
      pokeLoad(
        dut,
        instructionId = 0x81,
        ridSlot = 1,
        ridGeneration = 0,
        atag = 2,
        pc = 0x2000)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.iex.allocationClear(0).valid.expect(true.B)
      expectRob(
        dut.io.iex.allocationClear(0).bits.rob,
        ridSlot = 0,
        ridGeneration = 0)
      dut.io.iex.allocationClear(0).bits.destination.ptagValid.expect(true.B)
      val oldAllocatedPtag =
        dut.io.iex.allocationClear(0).bits.destination.ptag.peek().litValue
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.ridTailGeneration(0).expect(0.U)
      dut.io.iex.aguDispatch(0).valid.expect(true.B)
      val oldLive = dut.io.iex.aguDispatch(0).bits
      oldLive.uop.decoded.instruction.parent.identity.instructionId
        .expect(0x80.U)
      oldLive.transactionId.expect(0.U)
      expectRob(oldLive.uop.decoded.rob, ridSlot = 0, ridGeneration = 0)
      oldLive.uop.destinations(0).ptagValid.expect(true.B)
      oldLive.uop.destinations(0).ptag.expect(oldAllocatedPtag.U)
      oldLive.memoryOrder.requestCount.expect(1.U)
      oldLive.memoryOrder.firstLsid.expect(0.U)
      oldLive.memoryOrder.firstLid.expect(0.U)
      oldLive.memoryOrder.firstSid.expect(0.U)
      oldLive.memoryOrder.yostValid.expect(false.B)
      oldLive.memoryOrder.yoldValid.expect(false.B)
      val oldDispatch = dut.io.iex.aguDispatch(0).bits.peek()

      dut.clock.step(2)
      dut.io.iex.aguDispatch(0).valid.expect(true.B)
      dut.io.iex.aguDispatch(0).bits.expect(oldDispatch)
      dut.io.iex.allocationClear.foreach(_.valid.expect(false.B))
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.ridTailGeneration(0).expect(0.U)

      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.io.iex.allocationClear(0).valid.expect(true.B)
      expectRob(
        dut.io.iex.allocationClear(0).bits.rob,
        ridSlot = 1,
        ridGeneration = 0)
      dut.io.iex.allocationClear(0).bits.destination.ptagValid.expect(true.B)
      val newAllocatedPtag =
        dut.io.iex.allocationClear(0).bits.destination.ptag.peek().litValue
      assert(newAllocatedPtag != oldAllocatedPtag,
        "same-cycle replacement reused the still-live physical destination")
      assert(newAllocatedPtag == oldAllocatedPtag + 1,
        "same-cycle replacement transiently released or skipped its reservation")
      dut.clock.step()
      dut.io.iex.aguDispatch(0).ready.poke(false.B)

      dut.io.ridTailSlot(0).expect(0.U)
      dut.io.ridTailGeneration(0).expect(1.U)
      dut.io.iex.aguDispatch(0).valid.expect(true.B)
      val newDispatch = dut.io.iex.aguDispatch(0).bits
      newDispatch.uop.decoded.instruction.parent.identity.instructionId
        .expect(0x81.U)
      newDispatch.transactionId.expect(1.U)
      expectRob(newDispatch.uop.decoded.rob, ridSlot = 1, ridGeneration = 0)
      newDispatch.uop.destinations(0).ptagValid.expect(true.B)
      newDispatch.uop.destinations(0).ptag.expect(newAllocatedPtag.U)
      newDispatch.memoryOrder.requestCount.expect(1.U)
      newDispatch.memoryOrder.firstLsid.expect(1.U)
      newDispatch.memoryOrder.firstLid.expect(1.U)
      newDispatch.memoryOrder.firstSid.expect(0.U)
      newDispatch.memoryOrder.yostValid.expect(false.B)
      newDispatch.memoryOrder.yoldValid.expect(true.B)
      newDispatch.memoryOrder.yoldLsid.expect(0.U)
      newDispatch.memoryOrder.yoldLid.expect(0.U)

      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.iex.aguDispatch.foreach(_.valid.expect(false.B))
      dut.io.iex.allocationClear.foreach(_.valid.expect(false.B))
      dut.io.commit.valid.expect(false.B)
    }
  }
}
