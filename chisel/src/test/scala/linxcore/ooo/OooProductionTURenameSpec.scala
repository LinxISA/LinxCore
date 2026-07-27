package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

object OooProductionTURenameSpec {
  final case class UopShape(
      sources: Seq[(OperandClass.Type, Int)] = Seq.empty,
      destinations: Seq[(DestinationKind.Type, Int)] = Seq.empty)
}

class OooProductionTURenameSpec extends AnyFunSuite with ChiselSim {
  import OooProductionTURenameSpec.UopShape

  private def clear(dut: OooProductionTURename): Unit = {
    dut.io.reservePrepare.valid.poke(false.B)
    dut.io.reservePrepare.bits.poke(0.U.asTypeOf(dut.io.reservePrepare.bits))
    dut.io.reserveFire.poke(false.B)
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publicationPrepare.valid.poke(false.B)
    dut.io.publicationPrepare.bits.poke(
      0.U.asTypeOf(dut.io.publicationPrepare.bits))
    dut.io.publishFire.poke(false.B)
  }

  private def pokeTransaction(
      transaction: OooD2GroupedTransaction,
      p: OooParams,
      stid: Int,
      transactionId: Int,
      shapes: Seq[UopShape]): Unit = {
    transaction.poke(0.U.asTypeOf(transaction))
    val mask = (BigInt(1) << shapes.size) - 1
    val tCount = shapes.flatMap(_.destinations).count(_._1 == DestinationKind.T)
    val uCount = shapes.flatMap(_.destinations).count(_._1 == DestinationKind.U)

    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.peId.poke(2.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(7.U)
    transaction.plan.uopMask.poke(mask.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.demand.tAllocations.poke(tCount.U)
    transaction.plan.demand.uAllocations.poke(uCount.U)
    transaction.decoded.peId.poke(2.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(7.U)
    transaction.decoded.uopMask.poke(mask.U)
    transaction.groupMask.poke(1.U)

    val group = transaction.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(2.U)
    group.key.stid.poke(stid.U)
    group.key.ridSlot.poke(5.U)
    group.key.ridGeneration.poke(3.U)
    group.logicalUopMask.poke(mask.U)
    group.logicalUopCount.poke(shapes.size.U)
    group.physicalMemberCount.poke(shapes.size.U)

    shapes.zipWithIndex.foreach { case (shape, uopIndex) =>
      val uop = transaction.decoded.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.plannedChildCount.poke(1.U)
      transaction.uopGroupIndex(uopIndex).poke(0.U)
      transaction.uopMemberBase(uopIndex).poke(uopIndex.U)
      shape.sources.zipWithIndex.foreach { case ((operandClass, relative), sourceIndex) =>
        uop.sources(sourceIndex).valid.poke(true.B)
        uop.sources(sourceIndex).operandClass.poke(operandClass)
        uop.sources(sourceIndex).relativeIndex.poke(relative.U)
      }
      shape.destinations.zipWithIndex.foreach {
        case ((kind, relative), destinationIndex) =>
          uop.destinations(destinationIndex).valid.poke(true.B)
          uop.destinations(destinationIndex).kind.poke(kind)
          uop.destinations(destinationIndex).relativeIndex.poke(relative.U)
      }
    }
  }

  private def pokeReserve(
      dut: OooProductionTURename,
      stid: Int,
      transactionId: Int,
      shapes: Seq[UopShape]): Unit = {
    pokeTransaction(dut.io.reservePrepare.bits, dut.p, stid, transactionId,
      shapes)
    dut.io.reservePrepare.valid.poke(true.B)
  }

  private def pokePublication(
      dut: OooProductionTURename,
      stid: Int,
      transactionId: Int,
      shapes: Seq[UopShape]): Unit = {
    dut.io.publicationPrepare.bits.poke(
      0.U.asTypeOf(dut.io.publicationPrepare.bits))
    val request = dut.io.publicationPrepare.bits
    val mask = (BigInt(1) << shapes.size) - 1
    request.peId.poke(2.U)
    request.stid.poke(stid.U)
    request.epoch.poke(7.U)
    request.transactionId.poke(transactionId.U)
    request.uopMask.poke(mask.U)
    shapes.zipWithIndex.foreach { case (shape, uopIndex) =>
      val uop = request.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.member.group.valid.poke(true.B)
      uop.member.group.peId.poke(2.U)
      uop.member.group.stid.poke(stid.U)
      uop.member.group.ridSlot.poke(5.U)
      uop.member.group.ridGeneration.poke(3.U)
      uop.member.bid.valid.poke(true.B)
      uop.member.bid.value.poke(9.U)
      uop.member.brobGeneration.poke(4.U)
      uop.member.memberIndex.poke(uopIndex.U)
      uop.member.residentGeneration.poke(11.U)
      shape.sources.zipWithIndex.foreach {
        case ((operandClass, relative), sourceIndex) =>
          val source = uop.sources(sourceIndex)
          source.valid.poke(
            (operandClass == OperandClass.T || operandClass == OperandClass.U).B)
          source.kind.poke(if (operandClass == OperandClass.T)
            DestinationKind.T else DestinationKind.U)
          source.relativeIndex.poke(relative.U)
      }
      shape.destinations.zipWithIndex.foreach {
        case ((kind, relative), destinationIndex) =>
          val destination = uop.destinations(destinationIndex)
          destination.valid.poke(
            (kind == DestinationKind.T || kind == DestinationKind.U).B)
          destination.kind.poke(kind)
          destination.relativeIndex.poke(relative.U)
      }
    }
    dut.io.publicationPrepare.valid.poke(true.B)
  }

  private def reserve(dut: OooProductionTURename): Unit = {
    dut.io.reserveReady.expect(true.B)
    dut.io.reserveFire.poke(true.B)
    dut.clock.step()
    dut.io.reserveFire.poke(false.B)
    dut.io.reservePrepare.valid.poke(false.B)
  }

  private def publish(dut: OooProductionTURename): Unit = {
    dut.io.publicationReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.publicationPrepare.valid.poke(false.B)
  }

  test("resolves T and U relative sources across one retained bundle") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 4,
      renameWidth = 4,
      dispatchWidth = 4,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8)
    simulate(new OooProductionTURename(p)) { dut =>
      clear(dut)
      val shapes = Seq(
        UopShape(destinations = Seq(DestinationKind.T -> 0)),
        UopShape(
          sources = Seq(OperandClass.T -> 0),
          destinations = Seq(DestinationKind.U -> 0)),
        UopShape(
          sources = Seq(OperandClass.U -> 0),
          destinations = Seq(DestinationKind.T -> 0)))

      pokeReserve(dut, stid = 1, transactionId = 20, shapes)
      dut.io.reserveReady.expect(true.B)
      dut.io.reservation.tAllocationCount.expect(2.U)
      dut.io.reservation.uAllocationCount.expect(1.U)
      dut.io.reservation.tSeqBefore(0).index.expect(0.U)
      dut.io.reservation.tSeqBefore(1).index.expect(1.U)
      dut.io.reservation.uSeqBefore(1).index.expect(0.U)
      dut.io.reservation.tSeqBefore(2).index.expect(1.U)
      dut.io.reservation.uSeqBefore(2).index.expect(1.U)
      dut.io.reservation.sourceMappings(1)(0).valid.expect(true.B)
      dut.io.reservation.sourceMappings(1)(0).physicalTag.expect(0.U)
      dut.io.reservation.sourceMappings(2)(0).valid.expect(true.B)
      dut.io.reservation.sourceMappings(2)(0).physicalTag.expect(0.U)
      dut.io.reservation.allocations(0).mapping.sequence.index.expect(0.U)
      dut.io.reservation.allocations(0).mapping.physicalTag.expect(0.U)
      dut.io.reservation.allocations(2).mapping.sequence.index.expect(0.U)
      dut.io.reservation.allocations(2).mapping.physicalTag.expect(0.U)
      dut.io.reservation.allocations(4).mapping.sequence.index.expect(1.U)
      dut.io.reservation.allocations(4).mapping.physicalTag.expect(1.U)
      dut.clock.step(3)
      dut.io.tMapQUsed(1).expect(0.U)
      dut.io.uMapQUsed(1).expect(0.U)

      reserve(dut)
      dut.io.provisional(1).valid.expect(true.B)
      pokePublication(dut, stid = 1, transactionId = 20, shapes)
      dut.io.publicationReady.expect(true.B)
      dut.io.prepared.uops(1).sources(0).physicalTag.expect(0.U)
      dut.io.prepared.uops(2).destinations(0).physicalTag.expect(1.U)
      dut.io.prepared.rows(4).member.memberIndex.expect(2.U)
      publish(dut)
      dut.io.provisional(1).valid.expect(false.B)
      dut.io.tMapQUsed(1).expect(2.U)
      dut.io.uMapQUsed(1).expect(1.U)
      dut.io.tPhysicalUsed(1).expect(2.U)
      dut.io.uPhysicalUsed(1).expect(1.U)
      dut.io.tMapQUsed(0).expect(0.U)

      val lookup = Seq(UopShape(sources = Seq(
        OperandClass.T -> 0,
        OperandClass.T -> 1,
        OperandClass.U -> 0)))
      pokeReserve(dut, stid = 1, transactionId = 21, lookup)
      dut.io.reserveReady.expect(true.B)
      dut.io.reservation.sourceMappings(0)(0).physicalTag.expect(1.U)
      dut.io.reservation.sourceMappings(0)(0).sequence.index.expect(1.U)
      dut.io.reservation.sourceMappings(0)(1).physicalTag.expect(0.U)
      dut.io.reservation.sourceMappings(0)(1).sequence.index.expect(0.U)
      dut.io.reservation.sourceMappings(0)(2).physicalTag.expect(0.U)
      dut.io.reservation.sourceMappings(0)(2).sequence.index.expect(0.U)
    }
  }

  test("rejects relative-source underflow without reserving state") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooProductionTURename(p)) { dut =>
      clear(dut)
      pokeReserve(dut, stid = 0, transactionId = 30,
        Seq(UopShape(sources = Seq(OperandClass.T -> 0))))
      dut.io.reserveReady.expect(false.B)
      dut.io.reserveRejected.valid.expect(true.B)
      dut.io.reserveRejected.bits.sourceUnderflowMask.expect(1.U)
      dut.clock.step(3)
      dut.io.tMapQUsed(0).expect(0.U)
      dut.io.provisional(0).valid.expect(false.B)
    }
  }

  test("keeps one provisional lease per STID and cancels it exactly") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooProductionTURename(p)) { dut =>
      clear(dut)
      val oneT = Seq(UopShape(destinations = Seq(DestinationKind.T -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 40, oneT)
      val heldTag = dut.io.reservation.allocations(0).mapping.physicalTag.peek().litValue
      dut.clock.step(3)
      dut.io.reservation.allocations(0).mapping.physicalTag.expect(heldTag.U)
      reserve(dut)
      dut.io.provisional(0).valid.expect(true.B)
      dut.io.tMapQUsed(0).expect(0.U)

      pokeReserve(dut, stid = 0, transactionId = 41, oneT)
      dut.io.reserveReady.expect(false.B)
      pokeReserve(dut, stid = 1, transactionId = 42, oneT)
      dut.io.reserveReady.expect(true.B)
      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.provisional(0).valid.expect(false.B)
      dut.io.tMapQUsed(0).expect(0.U)
      pokeReserve(dut, stid = 0, transactionId = 43, oneT)
      dut.io.reserveReady.expect(true.B)
      dut.io.reservation.allocations(0).mapping.physicalTag.expect(0.U)
    }
  }

  test("rejects a mismatched publication and publishes only the exact lease") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooProductionTURename(p)) { dut =>
      clear(dut)
      val oneU = Seq(UopShape(destinations = Seq(DestinationKind.U -> 0)))
      pokeReserve(dut, stid = 1, transactionId = 50, oneU)
      reserve(dut)

      pokePublication(dut, stid = 1, transactionId = 51, oneU)
      dut.io.publicationReady.expect(false.B)
      dut.io.publicationRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.uMapQUsed(1).expect(0.U)
      dut.io.provisional(1).valid.expect(true.B)

      pokePublication(dut, stid = 1, transactionId = 50, oneU)
      dut.io.publicationPrepare.bits.uops(0).member.memberIndex.poke(1.U)
      dut.io.publicationReady.expect(false.B)
      dut.io.publicationPrepare.bits.uops(0).member.memberIndex.poke(0.U)
      dut.io.publicationPrepare.bits.uopMask.poke(0.U)
      dut.io.publicationReady.expect(false.B)
      dut.io.publicationPrepare.bits.uopMask.poke(1.U)
      dut.io.publicationReady.expect(true.B)
      publish(dut)
      dut.io.uMapQUsed(1).expect(1.U)
      dut.io.uPhysicalUsed(1).expect(1.U)
      dut.io.provisional(1).valid.expect(false.B)
    }
  }

  test("replaces a publishing lease and bypasses its local destination") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooProductionTURename(p)) { dut =>
      clear(dut)
      val producer = Seq(UopShape(
        destinations = Seq(DestinationKind.T -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 60, producer)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 60, producer)

      val consumer = Seq(UopShape(
        sources = Seq(OperandClass.T -> 0),
        destinations = Seq(DestinationKind.U -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 61, consumer)
      dut.io.publicationReady.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.io.reserveReady.expect(true.B)
      dut.io.reservation.tSeqBefore(0).index.expect(1.U)
      dut.io.reservation.sourceMappings(0)(0).valid.expect(true.B)
      dut.io.reservation.sourceMappings(0)(0).physicalTag.expect(0.U)

      dut.io.reserveFire.poke(true.B)
      dut.clock.step()
      dut.io.publishFire.poke(false.B)
      dut.io.reserveFire.poke(false.B)
      dut.io.reservePrepare.valid.poke(false.B)
      dut.io.publicationPrepare.valid.poke(false.B)
      dut.io.tMapQUsed(0).expect(1.U)
      dut.io.uMapQUsed(0).expect(0.U)
      dut.io.provisional(0).valid.expect(true.B)
      dut.io.provisional(0).transactionId.expect(61.U)
      dut.io.provisional(0).sourceMappings(0)(0).physicalTag.expect(0.U)

      pokePublication(dut, stid = 0, transactionId = 61, consumer)
      publish(dut)
      dut.io.tMapQUsed(0).expect(1.U)
      dut.io.uMapQUsed(0).expect(1.U)
    }
  }

  test("keeps four STID local namespaces independent") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      stidCount = 4,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8)
    simulate(new OooProductionTURename(p)) { dut =>
      clear(dut)
      val oneT = Seq(UopShape(destinations = Seq(DestinationKind.T -> 0)))
      for (stid <- 0 until 4) {
        pokeReserve(dut, stid = stid, transactionId = 70 + stid, oneT)
        dut.io.reservation.allocations(0).mapping.physicalTag.expect(0.U)
        reserve(dut)
        pokePublication(dut, stid = stid, transactionId = 70 + stid, oneT)
        publish(dut)
      }
      dut.io.tMapQUsed.foreach(_.expect(1.U))

      pokeReserve(dut, stid = 2, transactionId = 80, oneT)
      dut.io.reservation.allocations(0).mapping.physicalTag.expect(1.U)
      reserve(dut)
      pokePublication(dut, stid = 2, transactionId = 80, oneT)
      publish(dut)
      dut.io.tMapQUsed(2).expect(2.U)
      dut.io.tMapQUsed(0).expect(1.U)
      dut.io.tMapQUsed(1).expect(1.U)
      dut.io.tMapQUsed(3).expect(1.U)

      val lookup = Seq(UopShape(sources = Seq(OperandClass.T -> 0)))
      pokeReserve(dut, stid = 2, transactionId = 81, lookup)
      dut.io.reservation.sourceMappings(0)(0).physicalTag.expect(1.U)
      pokeReserve(dut, stid = 1, transactionId = 82, lookup)
      dut.io.reservation.sourceMappings(0)(0).physicalTag.expect(0.U)
    }
  }
}
