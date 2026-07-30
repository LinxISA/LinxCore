package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

private object OooO3RenameRandomizedSpec {
  final case class Shape(p: Boolean, t: Boolean, u: Boolean)

  final case class Mapping(
      valid: Boolean,
      ptag: BigInt,
      generation: BigInt,
      producerToken: BigInt,
      producerIqEpoch: BigInt,
      ready: Boolean,
      stid: Int,
      epoch: Int)

  final case class Member(
      ridSlot: BigInt,
      ridGeneration: BigInt,
      bid: BigInt,
      brobGeneration: BigInt,
      memberIndex: BigInt,
      residentGeneration: BigInt)

  final case class PDestination(atag: Int, mapping: Mapping)

  final case class Source(
      transactionId: BigInt,
      epoch: Int,
      member: Member,
      pDestinations: Vector[PDestination],
      tDestinations: Int,
      uDestinations: Int)

  final case class ThreadModel(
      sources: ArrayBuffer[Source],
      initialMappings: Vector[Mapping],
      var instructionSerial: Int,
      var nextTransactionId: BigInt,
      var headSlot: Int,
      var headGeneration: BigInt,
      var tailSlot: Int,
      var tailGeneration: BigInt,
      var tailEpoch: BigInt,
      var recoveries: Int)
}

class OooO3RenameRandomizedSpec extends AnyFunSuite with ChiselSim {
  import OooO3RenameRandomizedSpec._

  private def identityMapping(p: OooParams, stid: Int, atag: Int): Mapping =
    Mapping(valid = true, ptag = stid * p.pArchRegs + atag,
      generation = 0, producerToken = 0, producerIqEpoch = 0,
      ready = true, stid = stid,
      epoch = 0)

  private def incrementWrapped(value: BigInt, width: Int): BigInt =
    (value + 1) & ((BigInt(1) << width) - 1)

  private def advanceRobPointer(
      p: OooParams,
      slot: Int,
      generation: BigInt,
      count: Int): (Int, BigInt) = {
    val absolute = slot + count
    val nextSlot = absolute % p.robGroupsPerStid
    val nextGeneration = generation + absolute / p.robGroupsPerStid
    (nextSlot, nextGeneration &
      ((BigInt(1) << p.ridGenerationWidth) - 1))
  }

  private def clear(dut: OooO3RenameCoordinator): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.iexS1.ready.poke(false.B)
    dut.io.fastBoundary.ready.poke(true.B)
    dut.io.fastWriteback.ready.poke(true.B)
    dut.io.fastWakeup.ready.poke(true.B)
    dut.io.fastTrace.ready.poke(true.B)
    dut.io.completions.foreach { completion =>
      completion.valid.poke(false.B)
      completion.bits.poke(0.U.asTypeOf(completion.bits))
    }
    dut.io.nonFlushEvidence.valid.poke(false.B)
    dut.io.nonFlushEvidence.bits.poke(
      0.U.asTypeOf(dut.io.nonFlushEvidence.bits))
    dut.io.interruptPending.foreach(_.poke(false.B))
    dut.io.commit.ready.poke(false.B)
    dut.io.storeCommit.ready.poke(true.B)
    dut.io.ptagRecycle.ready.poke(true.B)
    dut.io.dispatchRelease.valid.poke(false.B)
    dut.io.dispatchRelease.bits.poke(
      0.U.asTypeOf(dut.io.dispatchRelease.bits))
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryRequest.bits.poke(
      0.U.asTypeOf(dut.io.recoveryRequest.bits))
    dut.io.iexRecoveryPrepareReady.poke(true.B)
    dut.io.iexRecoveryPrepared.poke(
      0.U.asTypeOf(dut.io.iexRecoveryPrepared))
    dut.io.iexRecoveryRejected.valid.poke(false.B)
    dut.io.iexRecoveryRejected.bits.poke(
      0.U.asTypeOf(dut.io.iexRecoveryRejected.bits))
    dut.io.queryStid.poke(0.U)
    dut.io.queryAtag.poke(0.U)
    dut.io.pcReadTokens.foreach(_.poke(
      0.U.asTypeOf(dut.io.pcReadTokens.head)))
  }

  private def pokeTransaction(
      dut: OooO3RenameCoordinator,
      stid: Int,
      instructionSerial: Int,
      transactionId: BigInt,
      tailSlot: Int,
      tailGeneration: BigInt,
      tailEpoch: BigInt,
      sourceAtag: Int,
      destinationAtag: Int,
      shape: Shape): Unit = {
    val transaction = dut.io.reserve.bits
    transaction.poke(0.U.asTypeOf(transaction))
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.uopMask.poke(1.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.virtualTailEpoch.poke(tailEpoch.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(3.U)
    transaction.plan.firstVirtualGroup.stid.poke(stid.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(tailSlot.U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(tailGeneration.U)
    transaction.plan.demand.pDestinations.poke((if (shape.p) 1 else 0).U)
    transaction.plan.demand.mapQRows.poke((if (shape.p) 1 else 0).U)
    transaction.plan.demand.tAllocations.poke((if (shape.t) 1 else 0).U)
    transaction.plan.demand.uAllocations.poke((if (shape.u) 1 else 0).U)

    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(1.U)
    transaction.groupMask.poke(1.U)
    transaction.uopGroupIndex(0).poke(0.U)
    transaction.uopMemberBase(0).poke(0.U)

    val group = transaction.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(stid.U)
    group.key.ridSlot.poke(tailSlot.U)
    group.key.ridGeneration.poke(tailGeneration.U)
    group.logicalUopMask.poke(1.U)
    group.physicalMemberCount.poke(1.U)
    group.pMapQRows.poke((if (shape.p) 1 else 0).U)
    group.architecturalParentCount.poke(1.U)
    group.boundaryStart.poke(true.B)
    group.boundaryStop.poke(true.B)
    group.releasePcBase.poke(true.B)

    val uop = transaction.decoded.uops(0)
    uop.valid.poke(true.B)
    uop.recipe.valid.poke(true.B)
    uop.plannedChildCount.poke(1.U)
    uop.identity.parentCount.poke(1.U)
    uop.identity.parents(0).key.valid.poke(true.B)
    uop.identity.parents(0).key.peId.poke(3.U)
    uop.identity.parents(0).key.stid.poke(stid.U)
    uop.identity.parents(0).key.instructionId.poke(
      (1000 + stid * 100 + instructionSerial).U)
    uop.identity.parents(0).key.epoch.poke(5.U)
    uop.identity.parents(0).pc.poke(
      (0x1000 + stid * 0x400 + instructionSerial * 8).U)
    uop.sources(0).valid.poke(true.B)
    uop.sources(0).operandClass.poke(OperandClass.P)
    uop.sources(0).atag.poke(sourceAtag.U)

    var destinationIndex = 0
    if (shape.p) {
      val destination = uop.destinations(destinationIndex)
      destination.valid.poke(true.B)
      destination.kind.poke(DestinationKind.Gpr)
      destination.atag.poke(destinationAtag.U)
      destinationIndex += 1
    }
    if (shape.t) {
      val destination = uop.destinations(destinationIndex)
      destination.valid.poke(true.B)
      destination.kind.poke(DestinationKind.T)
      destination.relativeIndex.poke(0.U)
      destinationIndex += 1
    }
    if (shape.u) {
      val destination = uop.destinations(destinationIndex)
      destination.valid.poke(true.B)
      destination.kind.poke(DestinationKind.U)
      destination.relativeIndex.poke(0.U)
    }
    dut.io.reserve.valid.poke(true.B)
  }

  private def captureMapping(
      dut: OooO3RenameCoordinator,
      destinationIndex: Int): Mapping = {
    val mapping = dut.io.prepared.uops(0).destinations(destinationIndex)
      .currentPMapping
    Mapping(
      valid = mapping.valid.peek().litToBoolean,
      ptag = mapping.ptag.peek().litValue,
      generation = mapping.ptagGeneration.peek().litValue,
      producerToken = mapping.producerToken.peek().litValue,
      producerIqEpoch = mapping.producerIqEpoch.peek().litValue,
      ready = mapping.ready.peek().litToBoolean,
      stid = mapping.stid.peek().litValue.toInt,
      epoch = mapping.epoch.peek().litValue.toInt)
  }

  private def captureMember(dut: OooO3RenameCoordinator): Member = {
    val member = dut.io.tuPrepared.uops(0).member
    Member(
      ridSlot = member.group.ridSlot.peek().litValue,
      ridGeneration = member.group.ridGeneration.peek().litValue,
      bid = member.bid.value.peek().litValue,
      brobGeneration = member.brobGeneration.peek().litValue,
      memberIndex = member.memberIndex.peek().litValue,
      residentGeneration = member.residentGeneration.peek().litValue)
  }

  private def publish(
      dut: OooO3RenameCoordinator,
      models: Vector[ThreadModel],
      stid: Int,
      sourceAtag: Int,
      destinationAtag: Int,
      shape: Shape): Unit = {
    val model = models(stid)
    val transactionId = model.nextTransactionId
    pokeTransaction(dut, stid, model.instructionSerial, transactionId,
      model.tailSlot, model.tailGeneration, model.tailEpoch, sourceAtag,
      destinationAtag, shape)
    dut.io.reserve.ready.expect(true.B)
    dut.clock.step()
    dut.io.reserve.valid.poke(false.B)
    assert(dut.io.preparedValid.peek().litToBoolean,
      s"STID $stid serial ${model.instructionSerial} shape $shape did not prepare; " +
        s"P provisional=${dut.io.ptagProvisionalCount.peek().litValue} " +
        s"T/U reserve reject=${dut.io.tuReserveRejected.valid.peek().litToBoolean} " +
        s"T/U publish reject=${dut.io.tuPublicationRejected.valid.peek().litToBoolean}")
    dut.io.tuPrepared.valid.expect(true.B)

    val pDestinations = if (shape.p) {
      Vector(PDestination(destinationAtag, captureMapping(dut, 0)))
    } else {
      Vector.empty
    }
    val source = Source(transactionId = transactionId, epoch = 5,
      member = captureMember(dut), pDestinations = pDestinations,
      tDestinations = if (shape.t) 1 else 0,
      uDestinations = if (shape.u) 1 else 0)

    dut.io.iexS1.ready.poke(true.B)
    dut.io.publishFire.expect(true.B)
    dut.clock.step()
    dut.io.iexS1.ready.poke(false.B)
    model.sources += source
    val (nextTailSlot, nextTailGeneration) = advanceRobPointer(
      dut.p, model.tailSlot, model.tailGeneration, count = 1)
    model.tailSlot = nextTailSlot
    model.tailGeneration = nextTailGeneration
    model.tailEpoch = incrementWrapped(
      model.tailEpoch, dut.p.reservationEpochWidth)
    model.nextTransactionId = incrementWrapped(
      model.nextTransactionId, dut.p.transactionIdWidth)
    model.instructionSerial += 1
  }

  private def pokeRecovery(
      dut: OooO3RenameCoordinator,
      stid: Int,
      source: Source,
      killTrigger: Boolean): Unit = {
    val global = dut.io.recoveryRequest.bits
    val request = global.rename
    request.poke(0.U.asTypeOf(request))
    request.key.member.group.valid.poke(true.B)
    request.key.member.group.peId.poke(3.U)
    request.key.member.group.stid.poke(stid.U)
    request.key.member.group.ridSlot.poke(source.member.ridSlot.U)
    request.key.member.group.ridGeneration.poke(
      source.member.ridGeneration.U)
    request.key.member.bid.valid.poke(true.B)
    request.key.member.bid.value.poke(source.member.bid.U)
    request.key.member.brobGeneration.poke(source.member.brobGeneration.U)
    request.key.member.memberIndex.poke(source.member.memberIndex.U)
    request.key.member.residentGeneration.poke(
      source.member.residentGeneration.U)
    request.key.cause.poke(OooRecoveryCause.Branch)
    request.key.transactionId.poke(source.transactionId.U)
    request.key.epoch.poke(source.epoch.U)
    request.killTrigger.poke(killTrigger.B)
    global.triggerMemberCount.poke(1.U)
    dut.io.iexRecoveryPrepared.valid.poke(true.B)
    dut.io.iexRecoveryPrepared.stid.poke(stid.U)
    dut.io.recoveryRequest.valid.poke(true.B)
  }

  private def recover(
      dut: OooO3RenameCoordinator,
      model: ThreadModel,
      stid: Int,
      anchorIndex: Int,
      killTrigger: Boolean): Unit = {
    val anchor = model.sources(anchorIndex)
    pokeRecovery(dut, stid, anchor, killTrigger)
    dut.io.recoveryRequest.ready.expect(true.B)
    dut.clock.step()
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryBusy.expect(true.B)

    var cycles = 0
    var sawComplete = false
    var sawReject = false
    while (dut.io.recoveryBusy.peek().litToBoolean && cycles < 96) {
      sawComplete ||= dut.io.recoveryComplete.peek().litToBoolean
      sawReject ||= dut.io.recoveryRejected.valid.peek().litToBoolean
      sawReject ||= dut.io.pRecoveryRejected.valid.peek().litToBoolean
      sawReject ||= dut.io.tuRecoveryRejected.valid.peek().litToBoolean
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 96, s"STID $stid recovery timed out")
    assert(sawComplete, s"STID $stid recovery did not join all owners")
    assert(!sawReject, s"STID $stid exact recovery was rejected")

    val survivorCount = anchorIndex + (if (killTrigger) 0 else 1)
    model.sources.remove(survivorCount,
      model.sources.length - survivorCount)
    val (nextTailSlot, nextTailGeneration) = advanceRobPointer(
      dut.p, model.headSlot, model.headGeneration, survivorCount)
    model.tailSlot = nextTailSlot
    model.tailGeneration = nextTailGeneration
    model.tailEpoch = incrementWrapped(
      model.tailEpoch, dut.p.reservationEpochWidth)
    model.recoveries += 1
  }

  private def expectedMappings(model: ThreadModel): Vector[Mapping] = {
    val mappings = model.initialMappings.toArray
    model.sources.foreach { source =>
      source.pDestinations.foreach { destination =>
        mappings(destination.atag) = destination.mapping
      }
    }
    mappings.toVector
  }

  private def assertMapping(
      actual: PMapPayload,
      expected: Mapping): Unit = {
    actual.valid.expect(expected.valid.B)
    actual.ptag.expect(expected.ptag.U)
    actual.ptagGeneration.expect(expected.generation.U)
    actual.producerToken.expect(expected.producerToken.U)
    actual.producerIqEpoch.expect(expected.producerIqEpoch.U)
    actual.ready.expect(expected.ready.B)
    actual.stid.expect(expected.stid.U)
    actual.epoch.expect(expected.epoch.U)
  }

  private def assertModel(
      dut: OooO3RenameCoordinator,
      models: Vector[ThreadModel]): Unit = {
    var expectedPublishedPtags = 0
    for (stid <- models.indices) {
      val model = models(stid)
      val pCount = model.sources.map(_.pDestinations.size).sum
      val tCount = model.sources.map(_.tDestinations).sum
      val uCount = model.sources.map(_.uDestinations).sum
      expectedPublishedPtags += pCount
      dut.io.mapQUsed(stid).expect(pCount.U)
      dut.io.tMapQUsed(stid).expect(tCount.U)
      dut.io.uMapQUsed(stid).expect(uCount.U)
      dut.io.tuRetireSourceUsed(stid).expect(model.sources.size.U)
      dut.io.robOccupiedGroups(stid).expect(model.sources.size.U)
      dut.io.d3UsedGroups(stid).expect(model.sources.size.U)
      dut.io.d3PublishedGroups(stid).expect(model.sources.size.U)
      dut.io.d3TailSlot(stid).expect(model.tailSlot.U)
      dut.io.d3TailGeneration(stid).expect(model.tailGeneration.U)
      dut.io.d3TailEpoch(stid).expect(model.tailEpoch.U)
      dut.io.d3NextTransactionId(stid).expect(
        model.nextTransactionId.U)
      dut.io.tRelationUsed(stid).expect(0.U)
      dut.io.uRelationUsed(stid).expect(0.U)

      val speculative = expectedMappings(model)
      for (atag <- 0 until dut.p.pArchRegs) {
        dut.io.queryStid.poke(stid.U)
        dut.io.queryAtag.poke(atag.U)
        assertMapping(dut.io.speculativeMapping, speculative(atag))
        assertMapping(dut.io.committedMapping,
          model.initialMappings(atag))
      }
    }
    dut.io.ptagProvisionalCount.expect(0.U)
    dut.io.ptagPublishedCount.expect(expectedPublishedPtags.U)
  }

  test("matches a four-STID sequential reference across randomized publish and recovery") {
    val seed = 0x4f344dL
    val random = new Random(seed)
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 16,
      tuRetireSourceDepthPerStid = 32,
      brobEntriesPerStid = 16,
      pMapQDepthPerStid = 32,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 2,
      tPhysRegs = 16,
      uPhysRegs = 16,
      tuMapQDepthPerStid = 32)
    val shapes = Vector(
      Shape(p = true, t = false, u = false),
      Shape(p = false, t = true, u = false),
      Shape(p = false, t = false, u = true),
      Shape(p = true, t = true, u = false),
      Shape(p = true, t = false, u = true),
      Shape(p = false, t = true, u = true),
      Shape(p = false, t = false, u = false))

    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      val models = Vector.tabulate(p.stidCount) { stid =>
        ThreadModel(ArrayBuffer.empty,
          Vector.tabulate(p.pArchRegs)(atag =>
            identityMapping(p, stid, atag)), instructionSerial = 0,
          nextTransactionId = 0, headSlot = 0, headGeneration = 0,
          tailSlot = 0, tailGeneration = 0, tailEpoch = 0,
          recoveries = 0)
      }

      // Seed every namespace and every STID before randomized selection.
      for (stid <- 0 until p.stidCount) {
        publish(dut, models, stid, sourceAtag = stid,
          destinationAtag = (stid + 1) % p.pArchRegs,
          shape = shapes(stid))
        publish(dut, models, stid, sourceAtag = stid + 1,
          destinationAtag = (stid + 5) % p.pArchRegs,
          shape = shapes(3 + (stid % 3)))
      }
      publish(dut, models, stid = 0, sourceAtag = 7,
        destinationAtag = 7, shape = shapes(6))
      recover(dut, models(0), stid = 0,
        anchorIndex = models(0).sources.length - 1, killTrigger = false)
      recover(dut, models(3), stid = 3,
        anchorIndex = models(3).sources.length - 1, killTrigger = true)
      assertModel(dut, models)

      for (_ <- 0 until 36) {
        val stid = random.nextInt(p.stidCount)
        val model = models(stid)
        val livePtags = models.map(_.sources.map(_.pDestinations.size).sum).sum
        val mayPublish = model.instructionSerial < 7 && livePtags < 20
        val choosePublish = model.sources.isEmpty ||
          (mayPublish && random.nextInt(100) < 62)
        if (choosePublish) {
          publish(dut, models, stid,
            sourceAtag = random.nextInt(p.pArchRegs),
            destinationAtag = random.nextInt(p.pArchRegs),
            shape = shapes(random.nextInt(shapes.length)))
        } else {
          val anchorIndex = random.nextInt(model.sources.length)
          recover(dut, model, stid, anchorIndex,
            killTrigger = random.nextBoolean())
        }
        assertModel(dut, models)
      }

      // Close coverage deterministically if random choice missed one STID.
      for (stid <- 0 until p.stidCount) {
        val model = models(stid)
        if (model.sources.isEmpty) {
          publish(dut, models, stid, sourceAtag = stid,
            destinationAtag = stid, shape = shapes(stid))
        }
        if (model.recoveries == 0) {
          recover(dut, model, stid, anchorIndex = model.sources.length - 1,
            killTrigger = false)
        }
        assertModel(dut, models)
      }
    }
  }
}
