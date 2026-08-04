package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

private final case class OOOD3PublishedPrefixLane(
    transactionId: BigInt,
    instructionId: BigInt,
    ridSlot: BigInt,
    ridGeneration: BigInt,
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

  private def pokeLoadGroups(
      dut: OOOD3S1Graph,
      count: Int,
      ridSlot: Int,
      ridGeneration: Int,
      instructionBase: Int,
      pcBase: Long): Unit = {
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(count.U)
    dut.io.fromD2.bits.groupCount.poke(count.U)
    for (lane <- 0 until count) {
      val absoluteRid = ridSlot + lane
      val laneRidSlot = absoluteRid % dut.p.ooo.robGroupsPerStid
      val laneRidGeneration =
        ridGeneration + absoluteRid / dut.p.ooo.robGroupsPerStid
      val group = dut.io.fromD2.bits.groups(lane)
      group.valid.poke(true.B)
      group.peId.poke(1.U)
      group.stid.poke(0.U)
      group.ridSlot.poke(laneRidSlot.U)
      group.ridGeneration.poke(laneRidGeneration.U)

      val uop = dut.io.fromD2.bits.entries(lane).uop
      uop.valid.poke(true.B)
      uop.instruction.parent.identity.peId.poke(1.U)
      uop.instruction.parent.identity.stid.poke(0.U)
      uop.instruction.parent.identity.instructionId.poke(
        (instructionBase + lane).U)
      uop.instruction.parent.identity.epoch.poke(1.U)
      uop.instruction.parent.pc.poke((pcBase + lane * 0x1000L).U)
      uop.instruction.parent.lengthBytes.poke(4.U)
      uop.rob.peId.poke(1.U)
      uop.rob.stid.poke(0.U)
      uop.rob.ridSlot.poke(laneRidSlot.U)
      uop.rob.ridGeneration.poke(laneRidGeneration.U)
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

  private def pokeLoadPrefix(dut: OOOD3S1Graph): Unit =
    pokeLoadGroups(dut, count = 8, ridSlot = 0, ridGeneration = 0,
      instructionBase = 0x80, pcBase = 0x1000L)

  private def acceptLoadGroups(dut: OOOD3S1Graph): Unit = {
    dut.io.fromD2.valid.poke(true.B)
    dut.io.fromD2.ready.expect(true.B)
    dut.clock.step()
    dut.io.fromD2.valid.poke(false.B)
  }

  private def collectAguPublications(
      dut: OOOD3S1Graph,
      count: Int): Seq[OOOD3PublishedPrefixLane] = {
    val published = ArrayBuffer.empty[OOOD3PublishedPrefixLane]
    var cycles = 0
    while (published.size < count && cycles < 32) {
      for (pipe <- 0 until dut.io.iex.aguDispatch.length) {
        val dispatch = dut.io.iex.aguDispatch(pipe)
        if (dispatch.valid.peek().litToBoolean) {
          val bits = dispatch.bits.peek()
          assert(bits.uop.destinations(0).ptagValid.litToBoolean)
          published += OOOD3PublishedPrefixLane(
            bits.transactionId.litValue,
            bits.uop.decoded.instruction.parent.identity.instructionId.litValue,
            bits.uop.decoded.rob.ridSlot.litValue,
            bits.uop.decoded.rob.ridGeneration.litValue,
            bits.memoryOrder.firstLsid.litValue,
            bits.uop.destinations(0).ptag.litValue,
            bits.pcBufferIndexOffset.pcBufferIndex.litValue,
            bits.pcBufferIndexOffset.allocationEpoch.litValue,
          )
        }
      }
      dut.clock.step()
      cycles += 1
    }
    assert(published.size == count,
      s"timed out after ${published.size} of $count AGU publications")
    published.toSeq
  }

  private def resolve(
      dut: OOOD3S1Graph,
      ridSlot: Int,
      ridGeneration: Int): Unit = {
    val completion = dut.io.iex.robResolve(0)
    completion.bits.poke(0.U.asTypeOf(completion.bits))
    completion.bits.rob.peId.poke(1.U)
    completion.bits.rob.stid.poke(0.U)
    completion.bits.rob.ridSlot.poke(ridSlot.U)
    completion.bits.rob.ridGeneration.poke(ridGeneration.U)
    completion.bits.rob.memberIndex.poke(0.U)
    completion.valid.poke(true.B)
    completion.ready.expect(true.B)
    dut.clock.step()
    completion.valid.poke(false.B)
  }

  test("W8 publishes one retained D3 transaction as three three two") {
    val p = SimulationParamProfiles.W8
    val robCapacity = BigInt(p.ooo.robGroupsPerStid)
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      pokeLoadPrefix(dut)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      val acceptedPrefixSizes = ArrayBuffer.empty[BigInt]
      val tailTransitions = ArrayBuffer.empty[(BigInt, BigInt)]
      val published = ArrayBuffer.empty[OOOD3PublishedPrefixLane]
      var previousTail = (BigInt(0), BigInt(0))
      var cycles = 0
      while ((published.size < 8 || previousTail != (BigInt(0), BigInt(1))) &&
          cycles < 96) {
        for (pipe <- 0 until dut.io.iex.aguDispatch.length) {
          val dispatch = dut.io.iex.aguDispatch(pipe)
          if (dispatch.valid.peek().litToBoolean) {
            val bits = dispatch.bits.peek()
            assert(bits.uop.destinations(0).ptagValid.litToBoolean)
            published += OOOD3PublishedPrefixLane(
              bits.transactionId.litValue,
              bits.uop.decoded.instruction.parent.identity.instructionId.litValue,
              bits.uop.decoded.rob.ridSlot.litValue,
              bits.uop.decoded.rob.ridGeneration.litValue,
              bits.memoryOrder.firstLsid.litValue,
              bits.uop.destinations(0).ptag.litValue,
              bits.pcBufferIndexOffset.pcBufferIndex.litValue,
              bits.pcBufferIndexOffset.allocationEpoch.litValue,
            )
          }
        }
        val tail = (
          dut.io.ridTailSlot(0).peek().litValue,
          dut.io.ridTailGeneration(0).peek().litValue)
        if (tail != previousTail) {
          acceptedPrefixSizes +=
            (tail._2 - previousTail._2) * robCapacity + tail._1 - previousTail._1
          tailTransitions += tail
          previousTail = tail
        }
        dut.clock.step()
        cycles += 1
      }

      assert(cycles < 96,
        s"retained W8 D3 suffix made no forward progress: " +
          s"published=${published.size}, prefixSizes=${acceptedPrefixSizes.mkString(",")}, " +
          s"tail=$previousTail, " +
          s"transitions=${tailTransitions.mkString(",")}")
      assert(acceptedPrefixSizes.toSeq == Seq(BigInt(3), BigInt(3), BigInt(2)))
      assert(acceptedPrefixSizes.scanLeft(BigInt(0))(_ + _).tail.toSeq ==
        Seq(BigInt(3), BigInt(6), BigInt(8)))
      assert(tailTransitions.toSeq == Seq(
        (BigInt(3), BigInt(0)),
        (BigInt(6), BigInt(0)),
        (BigInt(0), BigInt(1))))
      assert(published.map(_.transactionId).toSeq == (0 until 8).map(BigInt(_)))
      assert(published.map(_.ridSlot).toSeq == (0 until 8).map(BigInt(_)))
      assert(published.forall(_.ridGeneration == 0))
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

  test("ROB and BROB capacity limit the oldest feasible complete D3 prefix") {
    val base = SimulationParamProfiles.W4
    val p = base.copy(ooo = base.ooo.copy(
      pcBufferEntries = 8,
      pcBankCount = 4,
    ))
    assert(p.ooo.robGroupsPerStid == 4)
    assert(p.ooo.brobEntriesPerStid == 4)
    assert(p.ooo.pcBufferEntries == 8)
    assert(p.ooo.pcWritePorts == 3)

    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)

      pokeLoadGroups(dut, count = 2, ridSlot = 0, ridGeneration = 0,
        instructionBase = 0x10, pcBase = 0x1000L)
      acceptLoadGroups(dut)
      val resident = collectAguPublications(dut, count = 2)
      assert(resident.map(_.transactionId) == Seq(BigInt(0), BigInt(1)))
      assert(resident.map(_.ridSlot) == Seq(BigInt(0), BigInt(1)))
      assert(resident.forall(_.ridGeneration == 0))
      dut.io.ridTailSlot(0).expect(2.U)
      dut.io.ridTailGeneration(0).expect(0.U)

      // Three distinct PC bases fit the PC buffer and its three write ports,
      // while the resident pair leaves only two ROB and BROB groups free.
      pokeLoadGroups(dut, count = 3, ridSlot = 2, ridGeneration = 0,
        instructionBase = 0x20, pcBase = 0x4000L)
      acceptLoadGroups(dut)
      val oldestFeasible = collectAguPublications(dut, count = 2)
      dut.io.ridTailSlot(0).expect(0.U)
      dut.io.ridTailGeneration(0).expect(1.U)
      assert(oldestFeasible.map(_.transactionId) ==
        Seq(BigInt(2), BigInt(3)))
      assert(oldestFeasible.map(_.instructionId) ==
        Seq(BigInt(0x20), BigInt(0x21)))
      assert(oldestFeasible.map(row => (row.ridSlot, row.ridGeneration)) ==
        Seq((BigInt(2), BigInt(0)), (BigInt(3), BigInt(0))))
      assert(oldestFeasible.map(_.firstLsid) == Seq(BigInt(2), BigInt(3)))
      assert(oldestFeasible.map(_.pcBufferIndex) == Seq(BigInt(2), BigInt(3)))

      resolve(dut, ridSlot = 0, ridGeneration = 0)
      var cycles = 0
      while (!dut.io.commit.valid.peek().litToBoolean && cycles < 32) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 32,
        "retained same-STID suffix must not block the resident commit that frees its capacity")
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.entries(0).rob.ridSlot.expect(0.U)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)

      val retriedSuffix = collectAguPublications(dut, count = 1)
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.ridTailGeneration(0).expect(1.U)
      val published = oldestFeasible ++ retriedSuffix
      assert(published.map(_.transactionId) ==
        Seq(BigInt(2), BigInt(3), BigInt(4)))
      assert(published.map(_.instructionId) ==
        Seq(BigInt(0x20), BigInt(0x21), BigInt(0x22)))
      assert(published.map(row => (row.ridSlot, row.ridGeneration)) == Seq(
        (BigInt(2), BigInt(0)),
        (BigInt(3), BigInt(0)),
        (BigInt(0), BigInt(1))))
      assert(published.map(_.firstLsid) ==
        Seq(BigInt(2), BigInt(3), BigInt(4)))
      assert(published.map(_.pcBufferIndex) ==
        Seq(BigInt(2), BigInt(3), BigInt(4)))
      assert(published.map(_.ptag).distinct.size == 3)
      assert(published.forall(_.allocationEpoch == 0))
    }
  }
}
