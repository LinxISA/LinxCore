package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, PopCount}
import linxcore.ooo.{OOOD3S1Graph, OooDispatchClass,
  OooIexDomainCapability, OooLateSplitKind, OooOpcodeDisposition,
  OooOpcodeRecipeKind, OooSideEffectOwner}
import linxcore.params.{CoreParams, SimulationParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class OOOIEXDirectD3StoreHarnessIO(val p: CoreParams) extends Bundle {
  val fromD2 = Flipped(Decoupled(new D2AdmissionGroup(p)))
  val bootstrapReady = Output(Bool())
  val robTailSlot = Output(UInt(p.ooo.ridSlotWidth.W))
  val storeDispatchCount = Output(UInt(32.W))
  val storeReservationCount = Output(UInt(32.W))
  val storeBindingCount = Output(UInt(32.W))
  val storeBindingMismatchCount = Output(UInt(32.W))
  val storeDispatchValid = Output(Vec(p.iex.stdPipes, Bool()))
  val storeDispatchReady = Output(Vec(p.iex.stdPipes, Bool()))
  val storeBindingValid = Output(Vec(p.iex.stdPipes, Bool()))
  val storeBindingReady = Output(Vec(p.iex.stdPipes, Bool()))
}

class OOOIEXDirectD3StoreHarness(val p: CoreParams) extends Module {
  val io = IO(new OOOIEXDirectD3StoreHarnessIO(p))

  private val ooo = Module(new OOOD3S1Graph(p))
  private val iex = Module(new IEX(p))
  iex.io.branchResolve.ready := true.B
  ooo.io.fromD2 <> io.fromD2
  iex.io.ooo <> ooo.io.iex
  ooo.io.storeResolve.valid := false.B
  ooo.io.storeResolve.bits := 0.U.asTypeOf(ooo.io.storeResolve.bits)

  private val bootCount = p.ooo.stidCount * p.ooo.gprArchRegs
  private val bootIndex = RegInit(0.U(chisel3.util.log2Ceil(bootCount + 1).W))
  private val bootActive = bootIndex < bootCount.U
  iex.io.pInit.valid := bootActive
  iex.io.pInit.bits := 0.U.asTypeOf(iex.io.pInit.bits)
  iex.io.pInit.bits.stid := bootIndex / p.ooo.gprArchRegs.U
  iex.io.pInit.bits.atag := bootIndex % p.ooo.gprArchRegs.U
  iex.io.pInit.bits.epoch := 1.U
  iex.io.pInit.bits.ptag := bootIndex
  when(iex.io.pInit.fire) { bootIndex := bootIndex + 1.U }
  iex.io.bootstrapComplete := !bootActive
  io.bootstrapReady := iex.io.bootstrapReady

  iex.io.lsu.storeReservation.foreach(_.ready := true.B)
  iex.io.lsu.loadAddress.foreach(_.ready := true.B)
  iex.io.lsu.loadAllocation.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  iex.io.lsu.loadLaunch.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  iex.io.lsu.storeAddress.foreach(_.ready := true.B)
  iex.io.lsu.storeData.foreach(_.ready := true.B)
  iex.io.lsu.loadResult.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  iex.io.lsu.loadReissue.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  iex.io.lsu.loadRebindApply.foreach(_.ready := true.B)
  iex.io.lsu.loadRepick.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  iex.io.lsu.loadCancel.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  iex.io.lsu.recoveryEvent.valid := false.B
  iex.io.lsu.recoveryEvent.bits := 0.U.asTypeOf(iex.io.lsu.recoveryEvent.bits)
  iex.io.cmdIssue.ready := true.B
  iex.io.trace.ready := true.B

  ooo.io.commit.ready := true.B
  ooo.io.storeCommit.ready := true.B
  ooo.io.trap.ready := true.B
  ooo.io.interrupt.valid := false.B
  ooo.io.interrupt.bits := 0.U.asTypeOf(ooo.io.interrupt.bits)
  ooo.io.debugRequest.valid := false.B
  ooo.io.debugRequest.bits := 0.U.asTypeOf(ooo.io.debugRequest.bits)
  ooo.io.debugResponse.ready := true.B
  ooo.io.systemIssue.foreach(_.ready := true.B)
  ooo.io.trace.ready := true.B
  Seq(ooo.io.recoveryToD1, ooo.io.recoveryToIfu,
    ooo.io.recoveryToCtu, ooo.io.recoveryToLsu).foreach { target =>
    target.prepare.ready := true.B
    target.prepared.valid := false.B
    target.prepared.bits := 0.U.asTypeOf(target.prepared.bits)
  }

  private val dispatchFire = iex.io.ooo.storeDispatch.map(_.fire).reduce(_ || _)
  private val dispatchCount = RegInit(0.U(32.W))
  when(dispatchFire) {
    dispatchCount := dispatchCount + PopCount(iex.io.ooo.storeDispatch.map(_.fire))
  }
  private val reservationFires = PopCount(iex.io.lsu.storeReservation.map(_.fire))
  private val reservationCount = RegInit(0.U(32.W))
  when(reservationFires.orR) {
    reservationCount := reservationCount + reservationFires
  }
  private val bindingFires = PopCount(iex.io.ooo.storeBinding.map(_.fire))
  private val bindingCount = RegInit(0.U(32.W))
  when(bindingFires.orR) {
    bindingCount := bindingCount + bindingFires
  }
  private val bindingMismatches = VecInit(
    iex.io.ooo.storeBinding.zipWithIndex.map { case (binding, lane) =>
      val expected = Wire(new MemoryOrderMeta(p))
      expected := 0.U.asTypeOf(expected)
      expected.requestCount := 1.U
      expected.firstLsid := lane.U
      expected.firstSid := lane.U
      expected.yostValid := (lane > 0).B
      binding.fire && binding.bits.memoryOrder.asUInt =/= expected.asUInt
    })
  private val bindingMismatchCount = RegInit(0.U(32.W))
  when(bindingMismatches.asUInt.orR) {
    bindingMismatchCount := bindingMismatchCount + PopCount(bindingMismatches)
  }
  io.robTailSlot := ooo.io.ridTailSlot.head
  io.storeDispatchCount := dispatchCount
  io.storeReservationCount := reservationCount
  io.storeBindingCount := bindingCount
  io.storeBindingMismatchCount := bindingMismatchCount
  io.storeDispatchValid := VecInit(iex.io.ooo.storeDispatch.map(_.valid))
  io.storeDispatchReady := VecInit(iex.io.ooo.storeDispatch.map(_.ready))
  io.storeBindingValid := VecInit(iex.io.ooo.storeBinding.map(_.valid))
  io.storeBindingReady := VecInit(iex.io.ooo.storeBinding.map(_.ready))
}

class OOOIEXDirectDualStoreBoundarySpec extends AnyFunSuite with ChiselSim {
  private val p = SimulationParamProfiles.W4

  private def pokeStorePrefix(dut: OOOIEXDirectD3StoreHarness): Unit = {
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(2.U)
    dut.io.fromD2.bits.groupCount.poke(2.U)
    for (lane <- 0 until 2) {
      val group = dut.io.fromD2.bits.groups(lane)
      group.valid.poke(true.B)
      group.peId.poke(1.U)
      group.stid.poke(0.U)
      group.ridSlot.poke(lane.U)
      group.ridGeneration.poke(0.U)

      val uop = dut.io.fromD2.bits.entries(lane).uop
      uop.valid.poke(true.B)
      uop.instruction.parent.identity.peId.poke(1.U)
      uop.instruction.parent.identity.stid.poke(0.U)
      uop.instruction.parent.identity.instructionId.poke((20 + lane).U)
      uop.instruction.parent.identity.epoch.poke(1.U)
      uop.instruction.parent.pc.poke((0x2400 + lane * 4).U)
      uop.instruction.parent.lengthBytes.poke(4.U)
      uop.rob.peId.poke(1.U)
      uop.rob.stid.poke(0.U)
      uop.rob.ridSlot.poke(lane.U)
      uop.rob.ridGeneration.poke(0.U)
      uop.rob.memberIndex.poke(0.U)
      uop.uopClass.poke(UopClass.Std)
      uop.blockStart.poke(true.B)
      uop.blockStop.poke(true.B)
      uop.memory.valid.poke(true.B)
      uop.memory.isStore.poke(true.B)
      uop.memory.requestCount.poke(1.U)
      uop.memory.addressMode.poke(MemoryAddressMode.BaseOffset)
      uop.memory.accessBytes.poke(8.U)

      val classification = uop.classification
      classification.valid.poke(true.B)
      classification.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      classification.kind.poke(OooOpcodeRecipeKind.ScalarStore.U)
      classification.uopCountMin.poke(1.U)
      classification.uopCountMax.poke(2.U)
      classification.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
      classification.dispatchClass.poke(OooDispatchClass.Std.U)
      classification.splitKind.poke(OooLateSplitKind.StoreAddressData.U)
      classification.dispatchWrites.poke(2.U)
      classification.memoryRequestCount.poke(1.U)
      classification.dispatchDemand(OooDispatchClass.Agu - 1).poke(1.U)
      classification.dispatchDemand(OooDispatchClass.Std - 1).poke(1.U)
      classification.executionPipeCapability(
        OooDispatchClass.Agu - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress).U)
      classification.executionPipeCapability(
        OooDispatchClass.Std - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.StoreData).U)
    }
  }

  test("empty ROB accepts a direct W4 dual-store D3 prefix without a dispatch queue") {
    simulate(new OOOIEXDirectD3StoreHarness(p)) { dut =>
      dut.io.fromD2.valid.poke(false.B)
      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      while (!dut.io.bootstrapReady.peek().litToBoolean) dut.clock.step()

      pokeStorePrefix(dut)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      var cycles = 0
      while (dut.io.storeDispatchCount.peek().litValue < 2 && cycles < 16) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 16,
        s"the no-Queue D3 graph deadlocked before dual-store dispatch: " +
          s"robTail=${dut.io.robTailSlot.peek().litValue} " +
          s"count=${dut.io.storeDispatchCount.peek().litValue} " +
          s"reservation=${dut.io.storeReservationCount.peek().litValue} " +
          s"binding=${dut.io.storeBindingCount.peek().litValue} " +
          s"dispatchValid=${dut.io.storeDispatchValid.map(_.peek().litValue)} " +
          s"dispatchReady=${dut.io.storeDispatchReady.map(_.peek().litValue)} " +
          s"bindingValid=${dut.io.storeBindingValid.map(_.peek().litValue)} " +
          s"bindingReady=${dut.io.storeBindingReady.map(_.peek().litValue)}")
      dut.io.storeDispatchCount.expect(2.U)
      dut.io.storeReservationCount.expect(2.U)
      dut.io.storeBindingCount.expect(2.U)
      dut.io.storeBindingMismatchCount.expect(0.U)
    }
  }
}
