package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

object OooProductionTURetireSpec {
  final case class Destination(kind: DestinationKind.Type, sequence: Int)
  final case class SourceShape(
      destinations: Seq[Destination] = Seq.empty,
      blockLast: Boolean = false,
      closeBefore: Option[(Int, Int)] = None)
}

class OooProductionTURetireSpec extends AnyFunSuite with ChiselSim {
  import OooProductionTURetireSpec._

  private def params(releaseThreshold: Int = 2): OooParams = OooParams(
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 4,
    brobEntriesPerStid = 8,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 8,
    tuRelationDepthPerStid = 4,
    tuRelationReleaseThreshold = releaseThreshold)

  private def clear(dut: OooProductionTURetire): Unit = {
    dut.io.publicationPrepare.valid.poke(false.B)
    dut.io.publicationPrepare.bits.poke(
      0.U.asTypeOf(dut.io.publicationPrepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.commitPrepare.valid.poke(false.B)
    dut.io.commitPrepare.bits.poke(0.U.asTypeOf(dut.io.commitPrepare.bits))
    dut.io.commitFire.poke(false.B)
    dut.io.retireCommand.ready.poke(true.B)
    dut.io.blockCommit.ready.poke(true.B)
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryRequest.bits.poke(
      0.U.asTypeOf(dut.io.recoveryRequest.bits))
    dut.io.recoveryAuthorize.ready.poke(true.B)
    dut.io.recoverySource.ready.poke(true.B)
    dut.io.recoveryFinish.poke(false.B)
  }

  private def pokeGroupKey(
      key: RobGroupKey,
      peId: Int,
      stid: Int,
      rid: Int,
      p: OooParams): Unit = {
    key.valid.poke(true.B)
    key.peId.poke(peId.U)
    key.stid.poke(stid.U)
    key.ridSlot.poke((rid % p.robGroupsPerStid).U)
    key.ridGeneration.poke((rid / p.robGroupsPerStid).U)
  }

  private def pokePublication(
      dut: OooProductionTURetire,
      stid: Int,
      transactionId: Int,
      rid: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      shapes: Seq[SourceShape]): Unit = {
    require(shapes.nonEmpty && shapes.size <= dut.p.decodedUopWidth)
    val request = dut.io.publicationPrepare.bits
    request.poke(0.U.asTypeOf(request))
    request.peId.poke(2.U)
    request.stid.poke(stid.U)
    request.epoch.poke(7.U)
    request.transactionId.poke(transactionId.U)
    request.uopMask.poke(((BigInt(1) << shapes.size) - 1).U)
    shapes.zipWithIndex.foreach { case (shape, uopIndex) =>
      val source = request.sources(uopIndex)
      source.valid.poke(true.B)
      source.transactionId.poke(transactionId.U)
      source.epoch.poke(7.U)
      source.uopIndex.poke(uopIndex.U)
      pokeGroupKey(source.member.group, peId = 2, stid, rid, dut.p)
      source.member.bid.valid.poke(true.B)
      source.member.bid.value.poke(bid.U)
      source.member.brobGeneration.poke(brobGeneration.U)
      source.member.memberIndex.poke(uopIndex.U)
      source.member.residentGeneration.poke(residentGeneration.U)
      source.blockLast.poke(shape.blockLast.B)
      source.closeBeforeValid.poke(shape.closeBefore.nonEmpty.B)
      shape.closeBefore.foreach { case (closeBid, closeGeneration) =>
        source.closeBefore.valid.poke(true.B)
        source.closeBefore.bid.valid.poke(true.B)
        source.closeBefore.bid.value.poke(closeBid.U)
        source.closeBefore.generation.poke(closeGeneration.U)
      }
      source.tSeqBefore.valid.poke(true.B)
      source.uSeqBefore.valid.poke(true.B)
      shape.destinations.zipWithIndex.foreach {
        case (destinationShape, destinationIndex) =>
          val destination = source.destinations(destinationIndex)
          destination.valid.poke(true.B)
          destination.kind.poke(destinationShape.kind)
          destination.relativeIndex.poke(destinationShape.sequence.U)
          destination.sequence.valid.poke(true.B)
          destination.sequence.index.poke(
            (destinationShape.sequence % dut.p.tuMapQDepthPerStid).U)
          destination.sequence.generation.poke(
            (destinationShape.sequence / dut.p.tuMapQDepthPerStid).U)
          destination.physicalTag.poke(destinationShape.sequence.U)
          destination.stid.poke(stid.U)
          destination.epoch.poke(7.U)
      }
    }
  }

  private def publish(
      dut: OooProductionTURetire,
      stid: Int,
      transactionId: Int,
      rid: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      shapes: Seq[SourceShape]): Unit = {
    pokePublication(dut, stid, transactionId, rid, bid,
      brobGeneration, residentGeneration, shapes)
    dut.io.publicationPrepare.valid.poke(true.B)
    dut.io.publicationReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.publicationPrepare.valid.poke(false.B)
  }

  private def pokeRecovery(
      dut: OooProductionTURetire,
      stid: Int,
      transactionId: Int,
      rid: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      epoch: Int = 7,
      killTrigger: Boolean): Unit = {
    val request = dut.io.recoveryRequest.bits
    request.poke(0.U.asTypeOf(request))
    pokeGroupKey(request.key.member.group, peId = 2, stid, rid, dut.p)
    request.key.member.bid.valid.poke(true.B)
    request.key.member.bid.value.poke(bid.U)
    request.key.member.brobGeneration.poke(brobGeneration.U)
    request.key.member.memberIndex.poke(0.U)
    request.key.member.residentGeneration.poke(residentGeneration.U)
    request.key.cause.poke(OooRecoveryCause.Branch)
    request.key.transactionId.poke(transactionId.U)
    request.key.epoch.poke(epoch.U)
    request.killTrigger.poke(killTrigger.B)
  }

  private def startRecovery(dut: OooProductionTURetire): Unit = {
    dut.io.recoveryRequest.valid.poke(true.B)
    dut.io.recoveryRequest.ready.expect(true.B)
    dut.clock.step()
    dut.io.recoveryRequest.valid.poke(false.B)
  }

  private def waitForRecoverySource(
      dut: OooProductionTURetire,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.recoverySource.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for a rename recovery source")
  }

  private def waitForRecoveryAuthorize(
      dut: OooProductionTURetire,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.recoveryAuthorize.valid.peek().litToBoolean &&
        cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for rename recovery authority")
  }

  private def waitForRecoveryReject(
      dut: OooProductionTURetire,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.recoveryRejected.valid.peek().litToBoolean &&
        cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for a rename recovery reject")
  }

  private def finishRecovery(dut: OooProductionTURetire): Unit = {
    dut.io.recoverySourcesDone.expect(true.B)
    dut.io.recoveryFinish.poke(true.B)
    dut.clock.step()
    dut.io.recoveryFinish.poke(false.B)
    dut.io.recoveryBusy.expect(false.B)
  }

  private def pokeCommit(
      dut: OooProductionTURetire,
      stid: Int,
      transactionId: Int,
      rid: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      logicalUopCount: Int): Unit = {
    val batch = dut.io.commitPrepare.bits
    batch.poke(0.U.asTypeOf(batch))
    pokeGroupKey(batch.release.firstGroup, peId = 2, stid, rid, dut.p)
    batch.release.groupCount.poke(1.U)
    val group = batch.groups(0)
    group.valid.poke(true.B)
    pokeGroupKey(group.key, peId = 2, stid, rid, dut.p)
    group.transactionId.poke(transactionId.U)
    group.brob.valid.poke(true.B)
    group.brob.bid.valid.poke(true.B)
    group.brob.bid.value.poke(bid.U)
    group.brob.generation.poke(brobGeneration.U)
    group.residentGeneration.poke(residentGeneration.U)
    group.logicalUopMask.poke(
      ((BigInt(1) << logicalUopCount) - 1).U)
    group.physicalMemberCount.poke(logicalUopCount.U)
    group.completedMembers.poke(
      ((BigInt(1) << logicalUopCount) - 1).U)
    dut.io.commitPrepare.valid.poke(true.B)
  }

  private def waitForRetireCommand(
      dut: OooProductionTURetire,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.retireCommand.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for a T/U retire command")
  }

  private def waitForBlockCommit(
      dut: OooProductionTURetire,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.blockCommit.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for a local block commit")
  }

  private def finishCommit(dut: OooProductionTURetire): Unit = {
    var cycles = 0
    while (!dut.io.commitReady.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 32, "timed out waiting for T/U commit readiness")
    dut.io.commitFire.poke(true.B)
    dut.clock.step()
    dut.io.commitFire.poke(false.B)
    dut.io.commitPrepare.valid.poke(false.B)
    dut.io.commitBusy.expect(false.B)
  }

  test("keeps a no-destination block-last source and drains relations before block commit") {
    simulate(new OooProductionTURetire(params())) { dut =>
      clear(dut)
      publish(dut, stid = 1, transactionId = 10, rid = 0, bid = 3,
        brobGeneration = 2, residentGeneration = 1,
        Seq(
          SourceShape(Seq(Destination(DestinationKind.T, sequence = 0))),
          SourceShape(blockLast = true)))
      dut.io.sourceQueueUsed(1).expect(2.U)

      pokeCommit(dut, stid = 1, transactionId = 10, rid = 0, bid = 3,
        brobGeneration = 2, residentGeneration = 1, logicalUopCount = 2)
      dut.io.commitStartReady.expect(true.B)

      waitForRetireCommand(dut)
      dut.io.retireCommand.bits.kind.expect(DestinationKind.T)
      dut.io.retireCommand.bits.sequence.index.expect(0.U)
      dut.io.retireCommand.bits.dealloc.expect(false.B)
      dut.clock.step()

      waitForRetireCommand(dut)
      dut.io.retireCommand.bits.kind.expect(DestinationKind.T)
      dut.io.retireCommand.bits.sequence.index.expect(0.U)
      dut.io.retireCommand.bits.dealloc.expect(true.B)
      dut.clock.step()

      waitForBlockCommit(dut)
      dut.io.blockCommit.bits.stid.expect(1.U)
      dut.io.blockCommit.bits.block.bid.value.expect(3.U)
      dut.io.blockCommit.bits.block.generation.expect(2.U)
      dut.clock.step()

      finishCommit(dut)
      dut.io.sourceQueueUsed(1).expect(0.U)
      dut.io.tRelationUsed(1).expect(0.U)
      dut.io.uRelationUsed(1).expect(0.U)
    }
  }

  test("pressure-releases only the oldest relation after the next mark") {
    simulate(new OooProductionTURetire(params(releaseThreshold = 1))) { dut =>
      clear(dut)
      publish(dut, stid = 0, transactionId = 20, rid = 1, bid = 4,
        brobGeneration = 0, residentGeneration = 3,
        Seq(
          SourceShape(Seq(Destination(DestinationKind.T, sequence = 0))),
          SourceShape(Seq(Destination(DestinationKind.T, sequence = 1)))))
      pokeCommit(dut, stid = 0, transactionId = 20, rid = 1, bid = 4,
        brobGeneration = 0, residentGeneration = 3, logicalUopCount = 2)

      waitForRetireCommand(dut)
      dut.io.retireCommand.bits.sequence.index.expect(0.U)
      dut.io.retireCommand.bits.dealloc.expect(false.B)
      dut.clock.step()
      waitForRetireCommand(dut)
      dut.io.retireCommand.bits.sequence.index.expect(1.U)
      dut.io.retireCommand.bits.dealloc.expect(false.B)
      dut.clock.step()
      waitForRetireCommand(dut)
      dut.io.retireCommand.bits.sequence.index.expect(0.U)
      dut.io.retireCommand.bits.dealloc.expect(true.B)
      dut.clock.step()

      finishCommit(dut)
      dut.io.tRelationUsed(0).expect(1.U)
      dut.io.tRelationUsed(1).expect(0.U)
    }
  }

  test("rejects wrong STID and BROB generation without consuming the source head") {
    simulate(new OooProductionTURetire(params())) { dut =>
      clear(dut)
      publish(dut, stid = 2, transactionId = 30, rid = 2, bid = 7,
        brobGeneration = 5, residentGeneration = 4,
        Seq(SourceShape()))

      pokeCommit(dut, stid = 1, transactionId = 30, rid = 2, bid = 7,
        brobGeneration = 5, residentGeneration = 4, logicalUopCount = 1)
      dut.io.commitStartReady.expect(false.B)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.sourceQueueUsed(2).expect(1.U)

      pokeCommit(dut, stid = 2, transactionId = 30, rid = 2, bid = 7,
        brobGeneration = 6, residentGeneration = 4, logicalUopCount = 1)
      dut.io.commitStartReady.expect(false.B)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.sourceQueueUsed(2).expect(1.U)

      pokeCommit(dut, stid = 2, transactionId = 30, rid = 2, bid = 7,
        brobGeneration = 5, residentGeneration = 4, logicalUopCount = 1)
      dut.io.commitStartReady.expect(true.B)
      finishCommit(dut)
      dut.io.sourceQueueUsed(2).expect(0.U)
    }
  }

  test("derives an exact youngest suffix and isolates the recovering STID") {
    simulate(new OooProductionTURetire(params())) { dut =>
      clear(dut)
      publish(dut, stid = 1, transactionId = 0, rid = 0, bid = 1,
        brobGeneration = 0, residentGeneration = 1, Seq(SourceShape()))
      publish(dut, stid = 1, transactionId = 1, rid = 1, bid = 2,
        brobGeneration = 0, residentGeneration = 2, Seq(SourceShape()))
      publish(dut, stid = 1, transactionId = 2, rid = 2, bid = 3,
        brobGeneration = 0, residentGeneration = 3, Seq(SourceShape()))
      dut.io.sourceQueueUsed(1).expect(3.U)

      // Preserve the exact middle trigger: only the younger transaction exits.
      pokeRecovery(dut, stid = 1, transactionId = 1, rid = 1, bid = 2,
        brobGeneration = 0, residentGeneration = 2, killTrigger = false)
      startRecovery(dut)
      waitForRecoveryAuthorize(dut)
      dut.io.recoveryAuthorize.bits.key.transactionId.expect(1.U)
      dut.io.recoveryAuthorize.bits.killTrigger.expect(false.B)
      dut.clock.step()
      waitForRecoverySource(dut)
      dut.io.recoverySource.bits.source.transactionId.expect(2.U)
      dut.io.recoverySource.bits.source.member.group.ridSlot.expect(2.U)
      dut.io.recoverySource.bits.last.expect(true.B)
      dut.clock.step()
      dut.io.sourceQueueUsed(1).expect(2.U)
      dut.io.recoverySourcesDone.expect(true.B)

      // Same-STID publication waits, while an unrelated STID remains live.
      pokePublication(dut, stid = 1, transactionId = 3, rid = 3, bid = 4,
        brobGeneration = 0, residentGeneration = 4, Seq(SourceShape()))
      dut.io.publicationPrepare.valid.poke(true.B)
      dut.io.publicationReady.expect(false.B)
      dut.io.publicationPrepare.valid.poke(false.B)

      pokePublication(dut, stid = 2, transactionId = 4, rid = 0, bid = 5,
        brobGeneration = 0, residentGeneration = 1, Seq(SourceShape()))
      dut.io.publicationPrepare.valid.poke(true.B)
      dut.io.publicationReady.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.publishFire.poke(false.B)
      dut.io.publicationPrepare.valid.poke(false.B)
      dut.io.sourceQueueUsed(2).expect(1.U)
      finishRecovery(dut)

      // A stale epoch must reject after a read-only scan and preserve the ring.
      pokeRecovery(dut, stid = 1, transactionId = 1, rid = 1, bid = 2,
        brobGeneration = 0, residentGeneration = 2, epoch = 8,
        killTrigger = true)
      startRecovery(dut)
      waitForRecoveryReject(dut)
      dut.io.recoveryRejected.bits.requested.key.epoch.expect(8.U)
      dut.io.recoveryRejected.bits.sourceCount.expect(2.U)
      dut.io.sourceQueueUsed(1).expect(2.U)
      dut.io.recoveryBusy.expect(false.B)

      // Killing the trigger removes that exact tail row, leaving transaction 0.
      pokeRecovery(dut, stid = 1, transactionId = 1, rid = 1, bid = 2,
        brobGeneration = 0, residentGeneration = 2, killTrigger = true)
      startRecovery(dut)
      waitForRecoverySource(dut)
      dut.io.recoverySource.bits.source.transactionId.expect(1.U)
      dut.io.recoverySource.bits.last.expect(true.B)
      dut.clock.step()
      dut.io.sourceQueueUsed(1).expect(1.U)
      finishRecovery(dut)

      // Transaction ID zero is legal. Preserving the youngest trigger emits
      // no killed row but still reaches the owner-completion barrier.
      pokeRecovery(dut, stid = 1, transactionId = 0, rid = 0, bid = 1,
        brobGeneration = 0, residentGeneration = 1, killTrigger = false)
      startRecovery(dut)
      var cycles = 0
      while (!dut.io.recoverySourcesDone.peek().litToBoolean && cycles < 16) {
        dut.io.recoverySource.valid.expect(false.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 16, "transaction-zero recovery did not finish its scan")
      dut.io.sourceQueueUsed(1).expect(1.U)
      finishRecovery(dut)
    }
  }

  test("gives a simultaneous exact commit priority over recovery capture") {
    simulate(new OooProductionTURetire(params())) { dut =>
      clear(dut)
      publish(dut, stid = 0, transactionId = 6, rid = 0, bid = 1,
        brobGeneration = 0, residentGeneration = 1, Seq(SourceShape()))
      pokeCommit(dut, stid = 0, transactionId = 6, rid = 0, bid = 1,
        brobGeneration = 0, residentGeneration = 1, logicalUopCount = 1)
      pokeRecovery(dut, stid = 0, transactionId = 6, rid = 0, bid = 1,
        brobGeneration = 0, residentGeneration = 1, killTrigger = true)
      dut.io.recoveryRequest.valid.poke(true.B)
      dut.io.commitStartReady.expect(true.B)
      dut.io.recoveryRequest.ready.expect(false.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      dut.io.commitBusy.expect(true.B)
      finishCommit(dut)
      dut.io.sourceQueueUsed(0).expect(0.U)
    }
  }
}
