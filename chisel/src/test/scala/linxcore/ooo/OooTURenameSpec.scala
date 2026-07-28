package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

object OooTURenameSpec {
  final case class UopShape(
      sources: Seq[(OperandClass.Type, Int)] = Seq.empty,
      destinations: Seq[(DestinationKind.Type, Int)] = Seq.empty)
}

class OooTURenameSpec extends AnyFunSuite with ChiselSim {
  import OooTURenameSpec.UopShape

  private def clear(dut: OooTURename): Unit = {
    dut.io.reservePrepare.valid.poke(false.B)
    dut.io.reservePrepare.bits.poke(0.U.asTypeOf(dut.io.reservePrepare.bits))
    dut.io.reserveFire.poke(false.B)
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publicationPrepare.valid.poke(false.B)
    dut.io.publicationPrepare.bits.poke(
      0.U.asTypeOf(dut.io.publicationPrepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.retireCommand.valid.poke(false.B)
    dut.io.retireCommand.bits.poke(
      0.U.asTypeOf(dut.io.retireCommand.bits))
    dut.io.blockCommit.valid.poke(false.B)
    dut.io.blockCommit.bits.poke(
      0.U.asTypeOf(dut.io.blockCommit.bits))
    dut.io.recoveryAuthorize.valid.poke(false.B)
    dut.io.recoveryAuthorize.bits.poke(
      0.U.asTypeOf(dut.io.recoveryAuthorize.bits))
    dut.io.recoverySource.valid.poke(false.B)
    dut.io.recoverySource.bits.poke(
      0.U.asTypeOf(dut.io.recoverySource.bits))
    dut.io.recoverySourcesDone.poke(false.B)
    dut.io.recoveryFinish.poke(false.B)
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
      dut: OooTURename,
      stid: Int,
      transactionId: Int,
      shapes: Seq[UopShape]): Unit = {
    pokeTransaction(dut.io.reservePrepare.bits, dut.p, stid, transactionId,
      shapes)
    dut.io.reservePrepare.valid.poke(true.B)
  }

  private def pokePublication(
      dut: OooTURename,
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

  private def reserve(dut: OooTURename): Unit = {
    dut.io.reserveReady.expect(true.B)
    dut.io.reserveFire.poke(true.B)
    dut.clock.step()
    dut.io.reserveFire.poke(false.B)
    dut.io.reservePrepare.valid.poke(false.B)
  }

  private def publish(dut: OooTURename): Unit = {
    dut.io.publicationReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.publicationPrepare.valid.poke(false.B)
  }

  private def retireLocal(
      dut: OooTURename,
      kind: DestinationKind.Type,
      sequenceIndex: Int,
      sequenceGeneration: Int,
      memberIndex: Int = 0,
      dealloc: Boolean): Unit = {
    val command = dut.io.retireCommand.bits
    command.poke(0.U.asTypeOf(command))
    command.valid.poke(true.B)
    command.member.group.valid.poke(true.B)
    command.member.group.peId.poke(2.U)
    command.member.group.stid.poke(0.U)
    command.member.group.ridSlot.poke(5.U)
    command.member.group.ridGeneration.poke(3.U)
    command.member.bid.valid.poke(true.B)
    command.member.bid.value.poke(9.U)
    command.member.brobGeneration.poke(4.U)
    command.member.memberIndex.poke(memberIndex.U)
    command.member.residentGeneration.poke(11.U)
    command.kind.poke(kind)
    command.sequence.valid.poke(true.B)
    command.sequence.index.poke(sequenceIndex.U)
    command.sequence.generation.poke(sequenceGeneration.U)
    command.dealloc.poke(dealloc.B)
    dut.io.retireCommand.valid.poke(true.B)
    dut.io.retireCommand.ready.expect(true.B)
    dut.clock.step()
    dut.io.retireCommand.valid.poke(false.B)
  }

  private def pokeRecoveryRequest(
      request: OooRenameRecoveryRequest,
      stid: Int,
      transactionId: Int,
      uopIndex: Int,
      killTrigger: Boolean): Unit = {
    request.poke(0.U.asTypeOf(request))
    request.key.member.group.valid.poke(true.B)
    request.key.member.group.peId.poke(2.U)
    request.key.member.group.stid.poke(stid.U)
    request.key.member.group.ridSlot.poke(5.U)
    request.key.member.group.ridGeneration.poke(3.U)
    request.key.member.bid.valid.poke(true.B)
    request.key.member.bid.value.poke(9.U)
    request.key.member.brobGeneration.poke(4.U)
    request.key.member.memberIndex.poke(uopIndex.U)
    request.key.member.residentGeneration.poke(11.U)
    request.key.cause.poke(OooRecoveryCause.Branch)
    request.key.transactionId.poke(transactionId.U)
    request.key.epoch.poke(7.U)
    request.killTrigger.poke(killTrigger.B)
  }

  private def startRecovery(
      dut: OooTURename,
      stid: Int,
      transactionId: Int,
      uopIndex: Int = 0,
      killTrigger: Boolean = false): Unit = {
    pokeRecoveryRequest(dut.io.recoveryAuthorize.bits, stid,
      transactionId, uopIndex, killTrigger)
    dut.io.recoveryAuthorize.valid.poke(true.B)
    dut.io.recoveryAuthorize.ready.expect(true.B)
    dut.clock.step()
    dut.io.recoveryAuthorize.valid.poke(false.B)
    dut.io.recoveryBusy.expect(true.B)
    dut.io.recoveryStid.expect(stid.U)
  }

  private def sendRecoverySource(
      dut: OooTURename,
      triggerTransactionId: Int,
      killedTransactionId: Int,
      killedUopIndex: Int,
      tBeforeIndex: Int,
      tBeforeGeneration: Int,
      uBeforeIndex: Int,
      uBeforeGeneration: Int,
      destinations: Seq[(DestinationKind.Type, Int, Int, Int)],
      last: Boolean,
      stid: Int = 0): Unit = {
    val transfer = dut.io.recoverySource.bits
    transfer.poke(0.U.asTypeOf(transfer))
    pokeRecoveryRequest(transfer.request, stid, triggerTransactionId,
      uopIndex = 0, killTrigger = false)
    val source = transfer.source
    source.valid.poke(true.B)
    source.transactionId.poke(killedTransactionId.U)
    source.epoch.poke(7.U)
    source.uopIndex.poke(killedUopIndex.U)
    source.member.group.valid.poke(true.B)
    source.member.group.peId.poke(2.U)
    source.member.group.stid.poke(stid.U)
    source.member.group.ridSlot.poke(5.U)
    source.member.group.ridGeneration.poke(3.U)
    source.member.bid.valid.poke(true.B)
    source.member.bid.value.poke(9.U)
    source.member.brobGeneration.poke(4.U)
    source.member.memberIndex.poke(killedUopIndex.U)
    source.member.residentGeneration.poke(11.U)
    source.tSeqBefore.valid.poke(true.B)
    source.tSeqBefore.index.poke(tBeforeIndex.U)
    source.tSeqBefore.generation.poke(tBeforeGeneration.U)
    source.uSeqBefore.valid.poke(true.B)
    source.uSeqBefore.index.poke(uBeforeIndex.U)
    source.uSeqBefore.generation.poke(uBeforeGeneration.U)
    destinations.zipWithIndex.foreach {
      case ((kind, sequenceIndex, sequenceGeneration, physicalTag), index) =>
        val destination = source.destinations(index)
        destination.valid.poke(true.B)
        destination.kind.poke(kind)
        destination.relativeIndex.poke(0.U)
        destination.sequence.valid.poke(true.B)
        destination.sequence.index.poke(sequenceIndex.U)
        destination.sequence.generation.poke(sequenceGeneration.U)
        destination.physicalTag.poke(physicalTag.U)
        destination.stid.poke(stid.U)
        destination.epoch.poke(7.U)
    }
    transfer.last.poke(last.B)
    dut.io.recoverySource.valid.poke(true.B)
    dut.io.recoverySource.ready.expect(true.B)
    dut.clock.step()
    dut.io.recoverySource.valid.poke(false.B)
  }

  private def finishRecovery(dut: OooTURename): Unit = {
    dut.io.recoverySourcesDone.poke(true.B)
    dut.clock.step()
    dut.io.recoverySourcesDone.poke(false.B)
    dut.io.recoveryComplete.expect(true.B)
    dut.io.recoveryFinish.poke(true.B)
    dut.clock.step()
    dut.io.recoveryFinish.poke(false.B)
    dut.io.recoveryBusy.expect(false.B)
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
    simulate(new OooTURename(p)) { dut =>
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
    simulate(new OooTURename(p)) { dut =>
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
    simulate(new OooTURename(p)) { dut =>
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
    simulate(new OooTURename(p)) { dut =>
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
    simulate(new OooTURename(p)) { dut =>
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
    simulate(new OooTURename(p)) { dut =>
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

  test("requires exact wrap-qualified retire commands and reclaims physical tags") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooTURename(p)) { dut =>
      clear(dut)
      val oneT = Seq(UopShape(destinations = Seq(DestinationKind.T -> 0)))

      for (transactionId <- 90 until 94) {
        val sequenceIndex = transactionId - 90
        pokeReserve(dut, stid = 0, transactionId, oneT)
        dut.io.reservation.allocations(0).mapping.sequence.index.expect(
          sequenceIndex.U)
        dut.io.reservation.allocations(0).mapping.sequence.generation.expect(0.U)
        reserve(dut)
        pokePublication(dut, stid = 0, transactionId, oneT)
        publish(dut)
        retireLocal(dut, DestinationKind.T, sequenceIndex,
          sequenceGeneration = 0, dealloc = false)
        retireLocal(dut, DestinationKind.T, sequenceIndex,
          sequenceGeneration = 0, dealloc = true)
      }
      dut.io.tMapQUsed(0).expect(0.U)
      dut.io.tPhysicalUsed(0).expect(0.U)

      pokeReserve(dut, stid = 0, transactionId = 94, oneT)
      dut.io.reservation.allocations(0).mapping.sequence.index.expect(0.U)
      dut.io.reservation.allocations(0).mapping.sequence.generation.expect(1.U)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 94, oneT)
      publish(dut)

      val stale = dut.io.retireCommand.bits
      stale.poke(0.U.asTypeOf(stale))
      stale.valid.poke(true.B)
      stale.member.group.valid.poke(true.B)
      stale.member.group.peId.poke(2.U)
      stale.member.group.stid.poke(0.U)
      stale.member.group.ridSlot.poke(5.U)
      stale.member.group.ridGeneration.poke(3.U)
      stale.member.bid.valid.poke(true.B)
      stale.member.bid.value.poke(9.U)
      stale.member.brobGeneration.poke(4.U)
      stale.member.residentGeneration.poke(11.U)
      stale.kind.poke(DestinationKind.T)
      stale.sequence.valid.poke(true.B)
      stale.sequence.index.poke(0.U)
      stale.sequence.generation.poke(0.U)
      dut.io.retireCommand.valid.poke(true.B)
      dut.io.retireCommand.ready.expect(false.B)
      dut.clock.step()
      dut.io.retireCommand.valid.poke(false.B)
      dut.io.tMapQUsed(0).expect(1.U)

      retireLocal(dut, DestinationKind.T, sequenceIndex = 0,
        sequenceGeneration = 1, dealloc = false)
      retireLocal(dut, DestinationKind.T, sequenceIndex = 0,
        sequenceGeneration = 1, dealloc = true)
      dut.io.tMapQUsed(0).expect(0.U)
      dut.io.tPhysicalUsed(0).expect(0.U)
    }
  }

  test("releases the exact retired block prefix after relation cleanup") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooTURename(p)) { dut =>
      clear(dut)
      val twoT = Seq(
        UopShape(destinations = Seq(DestinationKind.T -> 0)),
        UopShape(destinations = Seq(DestinationKind.T -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 100, twoT)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 100, twoT)
      publish(dut)
      retireLocal(dut, DestinationKind.T, sequenceIndex = 0,
        sequenceGeneration = 0, memberIndex = 0, dealloc = false)
      retireLocal(dut, DestinationKind.T, sequenceIndex = 1,
        sequenceGeneration = 0, memberIndex = 1, dealloc = false)
      dut.io.tMapQUsed(0).expect(2.U)

      val commit = dut.io.blockCommit.bits
      commit.poke(0.U.asTypeOf(commit))
      commit.valid.poke(true.B)
      commit.peId.poke(2.U)
      commit.stid.poke(0.U)
      commit.block.valid.poke(true.B)
      commit.block.bid.valid.poke(true.B)
      commit.block.bid.value.poke(9.U)
      commit.block.generation.poke(4.U)
      dut.io.blockCommit.valid.poke(true.B)
      dut.io.blockCommit.ready.expect(true.B)
      dut.clock.step()
      dut.io.blockCommit.valid.poke(false.B)
      dut.io.tMapQUsed(0).expect(0.U)
      dut.io.tPhysicalUsed(0).expect(0.U)
    }
  }

  test("rolls back an exact mixed T/U suffix and restores both cursors") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      stidCount = 4,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8)
    simulate(new OooTURename(p)) { dut =>
      clear(dut)
      val both = Seq(UopShape(destinations = Seq(
        DestinationKind.T -> 0, DestinationKind.U -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 200, both)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 200, both)
      publish(dut)

      val killed = Seq(
        UopShape(destinations = Seq(
          DestinationKind.T -> 0, DestinationKind.U -> 0)),
        UopShape(destinations = Seq(DestinationKind.T -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 201, killed)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 201, killed)
      publish(dut)
      dut.io.tMapQUsed(0).expect(3.U)
      dut.io.uMapQUsed(0).expect(2.U)

      startRecovery(dut, stid = 0, transactionId = 200)

      val oneT = Seq(UopShape(destinations = Seq(DestinationKind.T -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 202, oneT)
      dut.io.reserveReady.expect(false.B)
      pokeReserve(dut, stid = 1, transactionId = 203, oneT)
      dut.io.reserveReady.expect(true.B)
      reserve(dut)
      pokePublication(dut, stid = 1, transactionId = 203, oneT)
      publish(dut)
      dut.io.tMapQUsed(1).expect(1.U)

      sendRecoverySource(dut, triggerTransactionId = 200,
        killedTransactionId = 201, killedUopIndex = 1,
        tBeforeIndex = 2, tBeforeGeneration = 0,
        uBeforeIndex = 2, uBeforeGeneration = 0,
        destinations = Seq((DestinationKind.T, 2, 0, 2)), last = false)
      dut.io.tMapQUsed(0).expect(2.U)
      dut.io.uMapQUsed(0).expect(2.U)
      sendRecoverySource(dut, triggerTransactionId = 200,
        killedTransactionId = 201, killedUopIndex = 0,
        tBeforeIndex = 1, tBeforeGeneration = 0,
        uBeforeIndex = 1, uBeforeGeneration = 0,
        destinations = Seq(
          (DestinationKind.T, 1, 0, 1),
          (DestinationKind.U, 1, 0, 1)), last = true)
      dut.io.tMapQUsed(0).expect(1.U)
      dut.io.uMapQUsed(0).expect(1.U)
      dut.io.tPhysicalUsed(0).expect(1.U)
      dut.io.uPhysicalUsed(0).expect(1.U)
      finishRecovery(dut)

      val lookup = Seq(UopShape(sources = Seq(
        OperandClass.T -> 0, OperandClass.U -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 204, lookup)
      dut.io.reserveReady.expect(true.B)
      dut.io.reservation.sourceMappings(0)(0).physicalTag.expect(0.U)
      dut.io.reservation.sourceMappings(0)(1).physicalTag.expect(0.U)

      pokeReserve(dut, stid = 0, transactionId = 205, both)
      dut.io.reserveReady.expect(true.B)
      dut.io.reservation.allocations(0).mapping.sequence.index.expect(1.U)
      dut.io.reservation.allocations(0).mapping.physicalTag.expect(1.U)
      dut.io.reservation.allocations(1).mapping.sequence.index.expect(1.U)
      dut.io.reservation.allocations(1).mapping.physicalTag.expect(1.U)
    }
  }

  test("preserves wrapped survivor state across a zero-destination suffix row") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooTURename(p)) { dut =>
      clear(dut)
      val oneT = Seq(UopShape(destinations = Seq(DestinationKind.T -> 0)))
      for (transactionId <- 300 until 304) {
        pokeReserve(dut, stid = 0, transactionId, oneT)
        reserve(dut)
        pokePublication(dut, stid = 0, transactionId, oneT)
        publish(dut)
        retireLocal(dut, DestinationKind.T,
          sequenceIndex = transactionId - 300,
          sequenceGeneration = 0, dealloc = false)
        retireLocal(dut, DestinationKind.T,
          sequenceIndex = transactionId - 300,
          sequenceGeneration = 0, dealloc = true)
      }

      pokeReserve(dut, stid = 0, transactionId = 304, oneT)
      dut.io.reservation.allocations(0).mapping.sequence.index.expect(0.U)
      dut.io.reservation.allocations(0).mapping.sequence.generation.expect(1.U)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 304, oneT)
      publish(dut)
      pokeReserve(dut, stid = 0, transactionId = 305, oneT)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 305, oneT)
      publish(dut)

      val noDestination = Seq(UopShape())
      pokeReserve(dut, stid = 0, transactionId = 0, noDestination)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 0, noDestination)
      publish(dut)

      startRecovery(dut, stid = 0, transactionId = 304)
      sendRecoverySource(dut, triggerTransactionId = 304,
        killedTransactionId = 0, killedUopIndex = 0,
        tBeforeIndex = 2, tBeforeGeneration = 1,
        uBeforeIndex = 0, uBeforeGeneration = 0,
        destinations = Seq.empty, last = false)
      sendRecoverySource(dut, triggerTransactionId = 304,
        killedTransactionId = 305, killedUopIndex = 0,
        tBeforeIndex = 1, tBeforeGeneration = 1,
        uBeforeIndex = 0, uBeforeGeneration = 0,
        destinations = Seq((DestinationKind.T, 1, 1, 1)), last = true)
      finishRecovery(dut)

      dut.io.tMapQUsed(0).expect(1.U)
      pokeReserve(dut, stid = 0, transactionId = 306, oneT)
      dut.io.reservation.allocations(0).mapping.sequence.index.expect(1.U)
      dut.io.reservation.allocations(0).mapping.sequence.generation.expect(1.U)
      dut.io.reservation.allocations(0).mapping.physicalTag.expect(1.U)
    }
  }

  test("rejects malformed recovery authority and gives retire exact priority") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)
    simulate(new OooTURename(p)) { dut =>
      clear(dut)
      val oneT = Seq(UopShape(destinations = Seq(DestinationKind.T -> 0)))
      pokeReserve(dut, stid = 0, transactionId = 400, oneT)
      reserve(dut)
      pokePublication(dut, stid = 0, transactionId = 400, oneT)
      publish(dut)

      dut.io.recoveryAuthorize.bits.poke(
        0.U.asTypeOf(dut.io.recoveryAuthorize.bits))
      dut.io.recoveryAuthorize.valid.poke(true.B)
      dut.io.recoveryAuthorize.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryAuthorize.valid.poke(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.io.recoveryRejected.bits.tMapQCount.expect(1.U)
      dut.io.recoveryBusy.expect(false.B)

      val command = dut.io.retireCommand.bits
      command.poke(0.U.asTypeOf(command))
      command.valid.poke(true.B)
      command.member.group.valid.poke(true.B)
      command.member.group.peId.poke(2.U)
      command.member.group.stid.poke(0.U)
      command.member.group.ridSlot.poke(5.U)
      command.member.group.ridGeneration.poke(3.U)
      command.member.bid.valid.poke(true.B)
      command.member.bid.value.poke(9.U)
      command.member.brobGeneration.poke(4.U)
      command.member.residentGeneration.poke(11.U)
      command.kind.poke(DestinationKind.T)
      command.sequence.valid.poke(true.B)
      command.sequence.index.poke(0.U)
      command.sequence.generation.poke(0.U)
      command.dealloc.poke(false.B)
      dut.io.retireCommand.valid.poke(true.B)
      pokeRecoveryRequest(dut.io.recoveryAuthorize.bits, stid = 0,
        transactionId = 400, uopIndex = 0, killTrigger = true)
      dut.io.recoveryAuthorize.valid.poke(true.B)
      dut.io.retireCommand.ready.expect(true.B)
      dut.io.recoveryAuthorize.ready.expect(false.B)
      dut.clock.step()
      dut.io.retireCommand.valid.poke(false.B)
      dut.io.recoveryAuthorize.valid.poke(false.B)
      dut.io.recoveryBusy.expect(false.B)
      dut.io.tMapQUsed(0).expect(1.U)
    }
  }
}
