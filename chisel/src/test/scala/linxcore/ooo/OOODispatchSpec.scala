package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class OOODispatchSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: Dispatch): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.robPrepared.poke(0.U.asTypeOf(dut.io.robPrepared))
    dut.io.brobPrepared.poke(0.U.asTypeOf(dut.io.brobPrepared))
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.bruDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.aguDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.systemDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.cmdDispatch.foreach(_.ready.poke(true.B))
  }

  private def publish(
      dut: Dispatch,
      classes: Seq[UopClass.Type],
      early: Set[Int] = Set.empty,
      trapCauses: Map[Int, BigInt] = Map.empty): Unit = {
    val count = classes.length
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.robPrepared.poke(0.U.asTypeOf(dut.io.robPrepared))
    dut.io.brobPrepared.poke(0.U.asTypeOf(dut.io.brobPrepared))
    dut.io.in.bits.count.poke(count.U)
    dut.io.in.bits.groupCount.poke(count.U)
    dut.io.robPrepared.count.poke(count.U)
    dut.io.brobPrepared.count.poke(count.U)
    classes.zipWithIndex.foreach { case (cls, lane) =>
      val raw = dut.io.in.bits.entries(lane).uop.decoded.rob
      val classification =
        dut.io.in.bits.entries(lane).uop.decoded.classification
      val classValue = cls.asUInt.litValue.toInt
      val aluValue = UopClass.Alu.asUInt.litValue.toInt
      val bruValue = UopClass.Bru.asUInt.litValue.toInt
      val aguValue = UopClass.Agu.asUInt.litValue.toInt
      val stdValue = UopClass.Std.asUInt.litValue.toInt
      val systemValue = UopClass.System.asUInt.litValue.toInt
      val cmdValue = UopClass.Cmd.asUInt.litValue.toInt
      val boundaryValue = UopClass.Boundary.asUInt.litValue.toInt
      dut.io.in.bits.groups(lane).valid.poke(true.B)
      dut.io.in.bits.groups(lane).peId.poke(1.U)
      dut.io.in.bits.groups(lane).stid.poke(0.U)
      dut.io.in.bits.groups(lane).ridSlot.poke(lane.U)
      dut.io.in.bits.entries(lane).uop.decoded.valid.poke(true.B)
      dut.io.in.bits.entries(lane).uop.decoded.uopClass.poke(cls)
      classification.valid.poke(true.B)
      classification.disposition.poke(
        (if (classValue == boundaryValue) OooOpcodeDisposition.FastResolve
         else OooOpcodeDisposition.Dispatch).U)
      classification.kind.poke(
        (if (classValue == stdValue) OooOpcodeRecipeKind.ScalarStore
         else if (classValue == aguValue) OooOpcodeRecipeKind.ScalarLoad
         else if (classValue == boundaryValue) OooOpcodeRecipeKind.Boundary
         else OooOpcodeRecipeKind.Single).U)
      classification.uopCountMin.poke(
        (if (classValue == boundaryValue) 0 else 1).U)
      classification.uopCountMax.poke(
        (if (classValue == stdValue) 2 else 1).U)
      classification.sideEffectOwner.poke(
        (if (classValue == stdValue || classValue == aguValue)
           OooSideEffectOwner.Lsu
         else if (classValue == bruValue || classValue == boundaryValue)
           OooSideEffectOwner.Bctrl
         else if (classValue == systemValue || classValue == cmdValue)
           OooSideEffectOwner.Commit
         else OooSideEffectOwner.Iex).U)
      classification.dispatchWrites.poke(
        (if (classValue == boundaryValue) 0
         else if (classValue == stdValue) 2
         else 1).U)
      if (classValue == aluValue) {
        classification.dispatchClass.poke(OooDispatchClass.Alu.U)
        classification.dispatchDemand(OooDispatchClass.Alu - 1).poke(1.U)
        classification.executionPipeCapability(OooDispatchClass.Alu - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu).U)
      } else if (classValue == bruValue) {
        classification.dispatchClass.poke(OooDispatchClass.Bru.U)
        classification.dispatchDemand(OooDispatchClass.Bru - 1).poke(1.U)
        classification.executionPipeCapability(OooDispatchClass.Bru - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.Branch).U)
      } else if (classValue == aguValue) {
        classification.dispatchClass.poke(OooDispatchClass.Agu.U)
        classification.dispatchDemand(OooDispatchClass.Agu - 1).poke(1.U)
        classification.executionPipeCapability(OooDispatchClass.Agu - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress).U)
      } else if (classValue == stdValue) {
        classification.dispatchClass.poke(OooDispatchClass.Std.U)
        classification.splitKind.poke(OooLateSplitKind.StoreAddressData.U)
        classification.dispatchDemand(OooDispatchClass.Agu - 1).poke(1.U)
        classification.dispatchDemand(OooDispatchClass.Std - 1).poke(1.U)
        classification.executionPipeCapability(OooDispatchClass.Agu - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress).U)
        classification.executionPipeCapability(OooDispatchClass.Std - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.StoreData).U)
      } else if (classValue == systemValue) {
        classification.dispatchClass.poke(OooDispatchClass.Sys.U)
        classification.dispatchDemand(OooDispatchClass.Sys - 1).poke(1.U)
        classification.executionPipeCapability(OooDispatchClass.Sys - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.System).U)
      } else if (classValue == cmdValue) {
        classification.dispatchClass.poke(OooDispatchClass.Cmd.U)
        classification.dispatchDemand(OooDispatchClass.Cmd - 1).poke(1.U)
        classification.executionPipeCapability(OooDispatchClass.Cmd - 1).poke(
          OooIexDomainCapability.mask(OooIexDomainCapability.EngineCommand).U)
      } else {
        assert(classValue == boundaryValue)
        classification.fastResolveClass.poke(
          OooFastResolveClass.BoundaryMetadata.U)
        classification.nonspeculative.poke(true.B)
        classification.dispatchClass.poke(OooDispatchClass.None.U)
      }
      dut.io.in.bits.entries(lane).uop.decoded.opcode.poke((0x120 + lane).U)
      dut.io.in.bits.entries(lane).uop.decoded.immediateValid.poke(true.B)
      dut.io.in.bits.entries(lane).uop.decoded.immediate.poke((0x500 + lane).U)
      dut.io.in.bits.entries(lane).uop.decoded.instruction.parent.identity.instructionId.poke((0x80 + lane).U)
      dut.io.in.bits.entries(lane).trap.valid.poke(trapCauses.contains(lane).B)
      dut.io.in.bits.entries(lane).trap.cause.poke(trapCauses.getOrElse(lane, BigInt(0)).U)
      dut.io.in.bits.entries(lane).pcBufferIndexOffset.valid.poke(true.B)
      dut.io.in.bits.entries(lane).pcBufferIndexOffset.pcBufferIndex.poke(
        (lane + 4).U)
      dut.io.in.bits.entries(lane).pcBufferIndexOffset.pcOffset.poke(
        (lane * 8).U)
      dut.io.in.bits.entries(lane).pcBufferIndexOffset.allocationEpoch.poke(
        (lane + 7).U)
      dut.io.in.bits.entries(lane).uop.sources(0).valid.poke(true.B)
      dut.io.in.bits.entries(lane).uop.sources(0).kind.poke(OperandKind.Gpr)
      dut.io.in.bits.entries(lane).uop.sources(0).atag.poke((4 + lane).U)
      dut.io.in.bits.entries(lane).uop.sources(0).ptag.poke((20 + lane).U)
      dut.io.in.bits.entries(lane).uop.sources(0).ptagValid.poke(true.B)
      dut.io.in.bits.entries(lane).uop.sources(0).ready.poke(true.B)
      dut.io.in.bits.entries(lane).uop.destinations(0).valid.poke(true.B)
      dut.io.in.bits.entries(lane).uop.destinations(0).kind.poke(OperandKind.Gpr)
      dut.io.in.bits.entries(lane).uop.destinations(0).atag.poke((8 + lane).U)
      dut.io.in.bits.entries(lane).uop.destinations(0).ptag.poke((24 + lane).U)
      dut.io.in.bits.entries(lane).uop.destinations(0).ptagValid.poke(true.B)
      raw.peId.poke(1.U); raw.stid.poke(0.U); raw.ridSlot.poke(lane.U)
      raw.ridGeneration.poke(2.U); raw.memberIndex.poke(0.U)
      dut.io.in.bits.entries(lane).earlyRobComplete.poke(early(lane).B)

      dut.io.robPrepared.entries(lane).valid.poke(true.B)
      dut.io.robPrepared.entries(lane).rob.poke(raw.peek())
      dut.io.robPrepared.entries(lane).rob.bid.poke((9 + lane).U)
      dut.io.robPrepared.entries(lane).rob.brobGeneration.poke(3.U)
      dut.io.robPrepared.entries(lane).rob.residentGeneration.poke(5.U)
      dut.io.brobPrepared.entries(lane).valid.poke(true.B)
      dut.io.brobPrepared.entries(lane).stid.poke(0.U)
      dut.io.brobPrepared.entries(lane).bid.poke((9 + lane).U)
      dut.io.brobPrepared.entries(lane).brobGeneration.poke(3.U)
    }
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  test("maps every canonical D3 field and keeps STA plus STD trap and CMD classing exact") {
    simulate(new Dispatch(ParamProfiles.W4)) { dut =>
      clear(dut)
      publish(
        dut,
        Seq(UopClass.Alu, UopClass.Std, UopClass.Cmd, UopClass.Boundary),
        early = Set(3),
        trapCauses = Map(1 -> OooD1TrapCause.IllegalEncoding))

      dut.io.iex.aluDispatch(0).valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.opcode.expect(0x120.U)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.immediate.expect(0x500.U)
      dut.io.iex.aluDispatch(0).bits.uop.sources(0).atag.expect(4.U)
      dut.io.iex.aluDispatch(0).bits.uop.sources(0).ptag.expect(20.U)
      dut.io.iex.aluDispatch(0).bits.uop.destinations(0).atag.expect(8.U)
      dut.io.iex.aluDispatch(0).bits.uop.destinations(0).ptag.expect(24.U)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.rob.bid.expect(9.U)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.rob.brobGeneration.expect(3.U)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.rob.residentGeneration.expect(5.U)
      dut.io.iex.aluDispatch(0).bits.pcBufferIndexOffset.pcBufferIndex.expect(4.U)
      dut.io.iex.aluDispatch(0).bits.pcBufferIndexOffset.pcOffset.expect(0.U)
      dut.io.iex.aluDispatch(0).bits.pcBufferIndexOffset.allocationEpoch.expect(7.U)
      dut.io.iex.storeDispatch(0).valid.expect(true.B)
      dut.io.iex.storeDispatch(0).bits.sta.transactionId.expect(
        dut.io.iex.storeDispatch(0).bits.std.transactionId.peek())
      dut.io.iex.storeDispatch(0).bits.sta.uop.decoded.opcode.expect(
        dut.io.iex.storeDispatch(0).bits.std.uop.decoded.opcode.peek())
      dut.io.iex.storeDispatch(0).bits.sta.uop.decoded.rob.bid.expect(
        dut.io.iex.storeDispatch(0).bits.std.uop.decoded.rob.bid.peek())
      dut.io.iex.storeDispatch(0).bits.sta.trap.valid.expect(true.B)
      dut.io.iex.storeDispatch(0).bits.sta.trap.cause.expect(
        OooD1TrapCause.IllegalEncoding.U)
      dut.io.iex.storeDispatch(0).bits.std.trap.expect(
        dut.io.iex.storeDispatch(0).bits.sta.trap.peek())
      dut.io.iex.storeDispatch(0).bits.sta.pcBufferIndexOffset.expect(
        dut.io.iex.storeDispatch(0).bits.std.pcBufferIndexOffset.peek())
      dut.io.iex.storeDispatch(0).bits.sta.pcBufferIndexOffset.pcBufferIndex
        .expect(5.U)
      dut.io.iex.storeDispatch(0).bits.sta.pcBufferIndexOffset.pcOffset
        .expect(8.U)
      dut.io.iex.storeDispatch(0).bits.sta.pcBufferIndexOffset.allocationEpoch
        .expect(8.U)
      dut.io.iex.cmdDispatch(0).valid.expect(true.B)
      dut.io.iex.systemDispatch(0).valid.expect(false.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }

  test("retries only the undispatched continuous suffix") {
    simulate(new Dispatch(ParamProfiles.W4)) { dut =>
      clear(dut)
      publish(dut, Seq(UopClass.Alu, UopClass.Alu, UopClass.Alu, UopClass.Cmd))
      dut.io.iex.aluDispatch(0).valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.opcode.expect(0x120.U)
      dut.io.iex.aluDispatch(1).valid.expect(true.B)
      dut.io.iex.aluDispatch(1).bits.uop.decoded.opcode.expect(0x121.U)
      dut.io.iex.cmdDispatch(0).valid.expect(false.B)
      dut.clock.step()
      dut.io.pending.expect(true.B)
      dut.io.iex.aluDispatch(0).valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.uop.decoded.opcode.expect(0x122.U)
      dut.io.iex.cmdDispatch(0).valid.expect(true.B)
      dut.io.iex.cmdDispatch(0).bits.uop.decoded.opcode.expect(0x123.U)
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }

  test("holds valid and payload stable until a wait-for-valid consumer accepts") {
    simulate(new Dispatch(ParamProfiles.W4)) { dut =>
      clear(dut)
      dut.io.iex.aluDispatch.foreach(_.ready.poke(false.B))
      publish(dut, Seq(UopClass.Alu),
        trapCauses = Map(0 -> OooD1TrapCause.IllegalEncoding))

      dut.io.iex.aluDispatch(0).valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.trap.valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.trap.cause.expect(
        OooD1TrapCause.IllegalEncoding.U)
      val held = dut.io.iex.aluDispatch(0).bits.peek()
      dut.clock.step(3)
      dut.io.iex.aluDispatch(0).valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.expect(held)
      dut.io.iex.aluDispatch(0).bits.trap.valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.trap.cause.expect(
        OooD1TrapCause.IllegalEncoding.U)

      dut.io.iex.aluDispatch(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }

  test("requires atomic AGU and STD credit and suppresses recovery-coincident dispatch") {
    simulate(new Dispatch(ParamProfiles.W4)) { dut =>
      clear(dut)
      dut.io.iex.storeDispatch.foreach(_.ready.poke(false.B))
      publish(dut, Seq(UopClass.Std),
        trapCauses = Map(0 -> OooD1TrapCause.IllegalEncoding))
      dut.io.iex.aguDispatch(0).valid.expect(false.B)
      dut.io.iex.storeDispatch(0).valid.expect(true.B)
      dut.io.iex.storeDispatch(0).bits.sta.trap.cause.expect(
        OooD1TrapCause.IllegalEncoding.U)
      dut.io.iex.storeDispatch(0).bits.std.trap.expect(
        dut.io.iex.storeDispatch(0).bits.sta.trap.peek())
      val held = dut.io.iex.storeDispatch(0).bits.peek()
      dut.clock.step()
      dut.io.pending.expect(true.B)
      dut.io.iex.aguDispatch(0).valid.expect(false.B)
      dut.io.iex.storeDispatch(0).valid.expect(true.B)
      dut.io.iex.storeDispatch(0).bits.expect(held)
      dut.io.iex.storeDispatch(0).bits.sta.trap.valid.expect(true.B)
      dut.io.iex.storeDispatch(0).bits.std.trap.expect(
        dut.io.iex.storeDispatch(0).bits.sta.trap.peek())

      dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
      dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.transactionId.poke(17.U)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.apply.bits.poke(dut.io.recovery.prepared.bits.peek())
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.io.iex.aguDispatch(0).valid.expect(false.B)
      dut.io.iex.storeDispatch(0).valid.expect(false.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.pending.expect(false.B)
    }
  }

  test("every accepted input is retained even without a separate publication pulse") {
    simulate(new Dispatch(ParamProfiles.W4)) { dut =>
      clear(dut)
      dut.io.iex.aluDispatch.foreach(_.ready.poke(false.B))
      dut.io.in.bits.count.poke(1.U)
      dut.io.in.bits.groupCount.poke(1.U)
      dut.io.in.bits.groups(0).valid.poke(true.B)
      dut.io.in.bits.entries(0).uop.decoded.valid.poke(true.B)
      dut.io.in.bits.entries(0).uop.decoded.uopClass.poke(UopClass.Alu)
      dut.io.robPrepared.count.poke(1.U)
      dut.io.robPrepared.entries(0).valid.poke(true.B)
      dut.io.brobPrepared.count.poke(1.U)
      dut.io.brobPrepared.entries(0).valid.poke(true.B)
      dut.io.in.valid.poke(true.B)

      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.pending.expect(true.B)
    }
  }

  test("rejects mismatched owner preparation without mutation and abort preserves the suffix") {
    simulate(new Dispatch(ParamProfiles.W4)) { dut =>
      clear(dut)
      dut.io.iex.aluDispatch.foreach(_.ready.poke(false.B))
      dut.io.in.bits.count.poke(1.U)
      dut.io.in.bits.groupCount.poke(1.U)
      dut.io.in.bits.groups(0).valid.poke(true.B)
      dut.io.in.bits.entries(0).uop.decoded.valid.poke(true.B)
      dut.io.in.bits.entries(0).uop.decoded.uopClass.poke(UopClass.Alu)
      dut.io.robPrepared.count.poke(1.U)
      dut.io.robPrepared.entries(0).valid.poke(true.B)
      dut.io.brobPrepared.entries(0).valid.poke(true.B)
      dut.io.in.valid.poke(true.B)

      dut.io.in.ready.expect(false.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)

      dut.io.brobPrepared.count.poke(1.U)
      dut.io.brobPrepared.entries(0).stid.poke(1.U)
      dut.io.in.ready.expect(false.B)
      dut.io.brobPrepared.entries(0).stid.poke(0.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.pending.expect(true.B)

      dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.transactionId.poke(31.U)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.abort.bits.poke(dut.io.recovery.prepared.bits.peek())
      dut.io.recovery.abort.bits.phase.poke(RecoveryPhase.Abort)
      dut.io.recovery.abort.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.pending.expect(true.B)
    }
  }

  test("elaborates the dispatch mechanism at W2 W4 W6 and W8") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p: CoreParams = ParamProfiles.forWidth(width)
      simulate(new Dispatch(p)) { dut => clear(dut) }
    }
  }
}
