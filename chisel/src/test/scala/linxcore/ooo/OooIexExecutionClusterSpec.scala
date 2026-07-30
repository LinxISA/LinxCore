package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class OooIexExecutionClusterSpec extends AnyFunSuite with ChiselSim {
  private val base = OooParams(
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
    iqBankCount = 8,
    iqEntriesPerBank = 2,
    iqWritePortsPerBank = 2,
    iqFreeSelectLeafEntries = 2,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)
  private val profile = OooIexLinxPhysicalProfile(base)
  private val p = profile.params

  private def clear(dut: OooIexExecutionCluster): Unit = {
    dut.io.e1.foreach { input =>
      input.valid.poke(false.B)
      input.bits.poke(0.U.asTypeOf(input.bits))
    }
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.storeAddress.foreach(_.ready.poke(true.B))
    dut.io.storeData.foreach(_.ready.poke(true.B))
    dut.io.multiCycleAlu.foreach(_.ready.poke(true.B))
    dut.io.system.foreach(_.ready.poke(true.B))
    dut.io.pointerAuth.foreach(_.ready.poke(true.B))
    dut.io.floatingVector.ready.poke(true.B)
    dut.io.engineCommand.ready.poke(true.B)
    dut.io.memoryRequest.foreach(_.ready.poke(true.B))
    dut.io.memoryResponse.foreach { response =>
      response.valid.poke(false.B)
      response.bits.poke(0.U.asTypeOf(response.bits))
    }
    dut.io.pWrite.foreach(_.ready.poke(true.B))
    dut.io.tWrite.foreach(_.ready.poke(true.B))
    dut.io.uWrite.foreach(_.ready.poke(true.B))
    dut.io.bctrl.foreach(_.ready.poke(true.B))
    dut.io.trace.foreach(_.ready.poke(true.B))
    dut.io.completion.foreach(_.ready.poke(true.B))
  }

  private def pokeMember(target: RobMemberKey, ridSlot: Int): Unit = {
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

  private def pokeRoute(
      dut: OooIexExecutionCluster,
      lane: Int,
      ownerClass: OooUopClass.Type,
      dispatchClass: Int,
      capability: BigInt,
      ridSlot: Int): Unit = {
    val execute = dut.io.e1(lane).bits
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(ownerClass)
    execute.ownerLane.poke(lane.U)
    execute.slotGeneration.poke(2.U)
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(ownerClass)
    execute.i2.row.payload.recipe.valid.poke(true.B)
    execute.i2.row.payload.recipe.dispatchClass.poke(dispatchClass.U)
    execute.i2.row.payload.recipe.dispatchCapabilities(dispatchClass - 1)
      .poke(capability.U)
    dut.io.e1(lane).valid.poke(true.B)
  }

  private def pokeSimpleAlu(
      dut: OooIexExecutionCluster,
      lane: Int,
      ridSlot: Int,
      immediate: Int,
      ptag: Int): Unit = {
    val opcode = FrontendOpcodeDecodeTable.OP_C_MOVI
    pokeRoute(dut, lane, OooUopClass.Alu, OooDispatchClass.Alu,
      OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu), ridSlot)
    val execute = dut.io.e1(lane).bits
    val row = execute.i2.row.schedule
    val payload = execute.i2.row.payload
    payload.opcode.poke(opcode.U)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    payload.recipe.pSourceCount.poke(0.U)
    payload.recipe.pDestinationCount.poke(1.U)
    payload.immediateValid.poke(true.B)
    payload.immediate.poke(immediate.U)
    payload.uopKey.primaryParent.valid.poke(true.B)
    payload.uopKey.primaryParent.peId.poke(3.U)
    payload.uopKey.primaryParent.stid.poke(1.U)
    payload.uopKey.primaryParent.instructionId.poke((20 + ridSlot).U)
    payload.uopKey.uopCount.poke(1.U)
    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(DestinationKind.Gpr)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(ptag.U)
    row.destinations(0).ptagGeneration.poke(3.U)
  }

  test("routes every nonlocal execution family through an explicit boundary") {
    simulate(new OooIexExecutionCluster(profile)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val stdLane = profile.pickerIndex("alu0")
      pokeRoute(dut, stdLane, OooUopClass.Std, OooDispatchClass.Std,
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreData), 1)
      dut.io.storeData(0).ready.poke(false.B)
      dut.io.storeData(0).valid.expect(true.B)
      dut.io.e1(stdLane).ready.expect(false.B)
      dut.io.routeRejected(stdLane).valid.expect(false.B)
      dut.io.storeData(0).ready.poke(true.B)
      dut.io.e1(stdLane).ready.expect(true.B)
      dut.io.e1(stdLane).valid.poke(false.B)

      val fsuLane = profile.pickerIndex("fsu0")
      pokeRoute(dut, fsuLane, OooUopClass.Cmd, OooDispatchClass.Cmd,
        OooIexDomainCapability.mask(OooIexDomainCapability.EngineCommand), 2)
      dut.io.engineCommand.valid.expect(true.B)
      dut.io.floatingVector.valid.expect(false.B)
      dut.io.e1(fsuLane).ready.expect(true.B)
      dut.io.e1(fsuLane).valid.poke(false.B)

      val specialLane = profile.pickerIndex("alu2")
      pokeRoute(dut, specialLane, OooUopClass.Alu, OooDispatchClass.Alu,
        OooIexDomainCapability.mask(OooIexDomainCapability.MultiCycleAlu,
          OooIexDomainCapability.PointerAuth), 3)
      dut.io.e1(specialLane).ready.expect(false.B)
      dut.io.routeRejected(specialLane).valid.expect(true.B)
      dut.io.routeRejected(specialLane).bits.capabilityOneHot.expect(false.B)
    }
  }

  test("executes two simple ALUs through W1 and two atomic W2 terminals") {
    simulate(new OooIexExecutionCluster(profile)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val lane0 = profile.pickerIndex("alu0")
      val lane1 = profile.pickerIndex("alu1")
      pokeSimpleAlu(dut, lane0, ridSlot = 1, immediate = 0x41, ptag = 30)
      pokeSimpleAlu(dut, lane1, ridSlot = 2, immediate = 0x52, ptag = 31)
      dut.io.e1(lane0).ready.expect(true.B)
      dut.io.e1(lane1).ready.expect(true.B)
      dut.clock.step()
      dut.io.e1(lane0).valid.poke(false.B)
      dut.io.e1(lane1).valid.poke(false.B)

      dut.io.bypass(0).valid.expect(true.B)
      dut.io.bypass(0).bits.data.expect(0x41.U)
      dut.io.bypass(1).valid.expect(true.B)
      dut.io.bypass(1).bits.data.expect(0x52.U)
      dut.clock.step()

      dut.io.terminalFireMask.expect(3.U)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.key.ptag.expect(30.U)
      dut.io.pWrite(2).valid.expect(true.B)
      dut.io.pWrite(2).bits.key.ptag.expect(31.U)
      dut.io.completion(0).bits.key.group.ridSlot.expect(1.U)
      dut.io.completion(1).bits.key.group.ridSlot.expect(2.U)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }
}
