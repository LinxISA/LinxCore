package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

private final case class OOOD3PublishedPrefixLane(
    transactionId: BigInt,
    ridSlot: BigInt,
    firstLsid: BigInt,
    ptag: BigInt,
    pcBufferIndex: BigInt,
    allocationEpoch: BigInt)

class OOOD3ContinuousPrefixSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OOOD3S1Graph): Unit = {
    dut.io.fromD2.valid.poke(false.B)
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.bruDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.aguDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.systemDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.cmdDispatch.foreach(_.ready.poke(true.B))
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

  private def pokeLoadPrefix(dut: OOOD3S1Graph): Unit = {
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(8.U)
    dut.io.fromD2.bits.groupCount.poke(8.U)
    for (lane <- 0 until 8) {
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
      uop.instruction.parent.identity.instructionId.poke((0x80 + lane).U)
      uop.instruction.parent.identity.epoch.poke(1.U)
      uop.instruction.parent.pc.poke((0x1000L + lane * 0x1000L).U)
      uop.instruction.parent.lengthBytes.poke(4.U)
      uop.rob.peId.poke(1.U)
      uop.rob.stid.poke(0.U)
      uop.rob.ridSlot.poke(lane.U)
      uop.rob.ridGeneration.poke(0.U)
      uop.rob.memberIndex.poke(0.U)
      uop.uopClass.poke(UopClass.Agu)
      uop.blockStart.poke(true.B)
      uop.blockStop.poke(true.B)
      // Keep each ROB group and PC base indivisible without turning the load
      // into an early-complete boundary uop; every published lane must still
      // reach canonical AGU dispatch so identity continuity is observable.
      uop.blockBoundary.poke(false.B)
      uop.destinations(0).valid.poke(true.B)
      uop.destinations(0).kind.poke(OperandKind.Gpr)
      uop.destinations(0).atag.poke((lane + 1).U)
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
      classification.executionPipeCapability(OooDispatchClass.Agu - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress).U)
    }
  }

  test("W8 publishes one retained D3 transaction as three three two") {
    simulate(new OOOD3S1Graph(SimulationParamProfiles.W8)) { dut =>
      clear(dut)
      pokeLoadPrefix(dut)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      val tailTransitions = ArrayBuffer.empty[BigInt]
    val published = ArrayBuffer.empty[OOOD3PublishedPrefixLane]
      var previousTail = BigInt(0)
      var cycles = 0
      while ((published.size < 8 || previousTail != 8) && cycles < 96) {
        for (pipe <- 0 until dut.io.iex.aguDispatch.length) {
          val dispatch = dut.io.iex.aguDispatch(pipe)
          if (dispatch.valid.peek().litToBoolean) {
            val bits = dispatch.bits.peek()
            assert(bits.uop.destinations(0).ptagValid.litToBoolean)
            published += OOOD3PublishedPrefixLane(
              bits.transactionId.litValue,
              bits.uop.decoded.rob.ridSlot.litValue,
              bits.memoryOrder.firstLsid.litValue,
              bits.uop.destinations(0).ptag.litValue,
              bits.pcBufferIndexOffset.pcBufferIndex.litValue,
              bits.pcBufferIndexOffset.allocationEpoch.litValue,
            )
          }
        }
        val tail = dut.io.ridTailSlot(0).peek().litValue
        if (tail != previousTail) {
          tailTransitions += tail
          previousTail = tail
        }
        dut.clock.step()
        cycles += 1
      }

      assert(cycles < 96,
        s"retained W8 D3 suffix made no forward progress: " +
          s"published=${published.size}, tail=$previousTail, " +
          s"transitions=${tailTransitions.mkString(",")}")
      assert(tailTransitions.toSeq == Seq(3, 6, 8))
      assert(published.map(_.transactionId).toSeq == (0 until 8).map(BigInt(_)))
      assert(published.map(_.ridSlot).toSeq == (0 until 8).map(BigInt(_)))
      assert(published.map(_.firstLsid).toSeq == (0 until 8).map(BigInt(_)))
      assert(published.map(_.ptag).distinct.size == 8)
      assert(published.map(_.pcBufferIndex).toSeq ==
        (0 until 8).map(BigInt(_)))
      assert(published.forall(_.allocationEpoch == 0))

      for (lane <- 0 until 8) {
        val address = dut.io.iex.pcBufferReadAddress.head
        address.valid.poke(true.B)
        address.stid.poke(0.U)
        address.pcBufferIndex.poke(lane.U)
        address.allocationEpoch.poke(0.U)
        dut.io.iex.pcBufferReadPcBase.head.valid.expect(true.B)
        dut.io.iex.pcBufferReadPcBase.head.bits.expect(
          (0x1000L + lane * 0x1000L).U)
      }
      dut.io.commit.valid.expect(false.B)
    }
  }
}
