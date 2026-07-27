package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

private object OooProductionDispatchSpec {
  final case class Token(
      uopClass: Int,
      bank: Int,
      port: Int,
      slot: Int,
      epoch: Int,
      uopIndex: Int,
      childIndex: Int)
}

class OooProductionDispatchSpec extends AnyFunSuite with ChiselSim {
  import OooProductionDispatchSpec._

  private def clear(dut: OooProductionDispatch): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.reserveFire.poke(false.B)
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publish.valid.poke(false.B)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
  }

  private def pokeTransaction(
      dut: OooProductionDispatch,
      stid: Int,
      transactionId: Int,
      demands: Vector[Vector[Int]],
      plannedOverride: Option[Vector[Int]] = None): Unit = {
    require(demands.length <= dut.p.decodedUopWidth)
    require(demands.forall(_.length == dut.p.iqClassCount))
    val transaction = dut.io.prepare.bits
    transaction.poke(0.U.asTypeOf(transaction))
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(5.U)
    val uopMask = (1 << demands.length) - 1
    transaction.plan.uopMask.poke(uopMask.U)
    transaction.decoded.uopMask.poke(uopMask.U)
    for (uopIndex <- demands.indices) {
      val uop = transaction.decoded.uops(uopIndex)
      val total = demands(uopIndex).sum
      uop.valid.poke(true.B)
      uop.recipe.valid.poke(true.B)
      uop.recipe.dispatchWrites.poke(total.U)
      for (uopClass <- 0 until dut.p.iqClassCount) {
        uop.recipe.dispatchDemand(uopClass).poke(
          demands(uopIndex)(uopClass).U)
      }
    }
    val planned = plannedOverride.getOrElse(Vector.tabulate(
      dut.p.iqClassCount) { uopClass =>
      demands.map(_(uopClass)).sum
    })
    for (uopClass <- 0 until dut.p.iqClassCount) {
      transaction.plan.demand.dispatchWritesByClass(uopClass).poke(
        planned(uopClass).U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def capture(dut: OooProductionDispatch): Vector[Token] =
    (0 until dut.p.dispatchWidth).flatMap { lane =>
      val allocation = dut.io.prepared.allocations(lane)
      if (allocation.valid.peek().litToBoolean) {
        Some(Token(
          uopClass = allocation.reservation.uopClass.peek().litValue.toInt,
          bank = allocation.reservation.bank.peek().litValue.toInt,
          port = allocation.reservation.writePort.peek().litValue.toInt,
          slot = allocation.reservation.speculativeSlot.peek().litValue.toInt,
          epoch = allocation.reservation.reservationEpoch.peek().litValue.toInt,
          uopIndex = allocation.uopIndex.peek().litValue.toInt,
          childIndex = allocation.childIndex.peek().litValue.toInt))
      } else {
        None
      }
    }.toVector

  private def pokeClass(target: OooUopClass.Type, value: Int): Unit =
    value match {
      case 0 => target.poke(OooUopClass.Alu)
      case 1 => target.poke(OooUopClass.Bru)
      case 2 => target.poke(OooUopClass.Agu)
      case 3 => target.poke(OooUopClass.Std)
      case 4 => target.poke(OooUopClass.Fsu)
      case 5 => target.poke(OooUopClass.Sys)
      case 6 => target.poke(OooUopClass.Cmd)
      case 7 => target.poke(OooUopClass.Boundary)
    }

  private def release(
      dut: OooProductionDispatch,
      stid: Int,
      transactionId: Int,
      token: Token): Unit = {
    val request = dut.io.release.bits
    request.poke(0.U.asTypeOf(request))
    request.peId.poke(3.U)
    request.stid.poke(stid.U)
    request.epoch.poke(5.U)
    request.transactionId.poke(transactionId.U)
    request.reservation.valid.poke(true.B)
    pokeClass(request.reservation.uopClass, token.uopClass)
    request.reservation.bank.poke(token.bank.U)
    request.reservation.writePort.poke(token.port.U)
    request.reservation.speculativeSlot.poke(token.slot.U)
    request.reservation.reservationEpoch.poke(token.epoch.U)
    dut.io.release.valid.poke(true.B)
    dut.io.release.ready.expect(true.B)
    dut.clock.step()
    dut.io.release.valid.poke(false.B)
  }

  private def reserve(
      dut: OooProductionDispatch,
      stid: Int,
      transactionId: Int,
      demands: Vector[Vector[Int]]): Vector[Token] = {
    pokeTransaction(dut, stid, transactionId, demands)
    dut.io.prepareReady.expect(true.B)
    dut.io.prepared.valid.expect(true.B)
    val tokens = capture(dut)
    dut.io.reserveFire.poke(true.B)
    dut.clock.step()
    dut.io.reserveFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
    tokens
  }

  private def publish(
      dut: OooProductionDispatch,
      stid: Int,
      transactionId: Int): Unit = {
    dut.io.publish.bits.peId.poke(3.U)
    dut.io.publish.bits.stid.poke(stid.U)
    dut.io.publish.bits.epoch.poke(5.U)
    dut.io.publish.bits.transactionId.poke(transactionId.U)
    dut.io.publish.valid.poke(true.B)
    dut.io.publishRejected.valid.expect(false.B)
    dut.clock.step()
    dut.io.publish.valid.poke(false.B)
  }

  test("reserves publishes releases and generation-qualifies a split bundle") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 4,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooProductionDispatch(p)) { dut =>
      clear(dut)
      val zero = Vector.fill(p.iqClassCount)(0)
      val alu = zero.updated(0, 1)
      val aguStd = zero.updated(2, 1).updated(3, 1)
      val first = reserve(dut, stid = 1, transactionId = 0,
        demands = Vector(alu, aguStd))
      assert(first.map(_.uopClass) == Vector(0, 2, 3))
      assert(first.map(_.uopIndex) == Vector(0, 1, 1))
      assert(first.map(_.childIndex) == Vector(0, 0, 1))
      first.foreach(token => assert(token.epoch == 1))
      dut.io.provisional(1).valid.expect(true.B)
      dut.io.provisionalEntries(0)(0).expect(1.U)
      dut.io.provisionalEntries(2)(1).expect(1.U)
      dut.io.provisionalEntries(3)(0).expect(1.U)

      publish(dut, stid = 1, transactionId = 0)
      dut.io.provisional(1).valid.expect(false.B)
      dut.io.publishedEntries(0)(0).expect(1.U)
      dut.io.publishedEntries(2)(1).expect(1.U)
      dut.io.publishedEntries(3)(0).expect(1.U)
      first.foreach(token => release(dut, 1, 0, token))
      dut.io.publishedEntries(0)(0).expect(0.U)

      val second = reserve(dut, stid = 1, transactionId = 2,
        demands = Vector(alu, aguStd))
      assert(second.map(_.bank) == first.map(_.bank))
      assert(second.map(_.slot) == first.map(_.slot))
      second.foreach(token => assert(token.epoch == 2))
      dut.io.publish.bits.peId.poke(3.U)
      dut.io.publish.bits.stid.poke(1.U)
      dut.io.publish.bits.epoch.poke(4.U)
      dut.io.publish.bits.transactionId.poke(2.U)
      dut.io.publish.valid.poke(true.B)
      dut.io.publishRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.provisional(1).valid.expect(true.B)

      dut.io.publish.bits.epoch.poke(5.U)
      dut.io.publish.valid.poke(true.B)
      dut.io.cancel(1).poke(true.B)
      dut.io.publishRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.cancel(1).poke(false.B)
      dut.io.provisional(1).valid.expect(false.B)
      second.foreach { token =>
        dut.io.freeEntries(token.uopClass)(token.bank).expect(4.U)
      }
    }
  }

  test("isolates STID leases and rejects demand or port overbooking atomically") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 4,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 1,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooProductionDispatch(p)) { dut =>
      clear(dut)
      val zero = Vector.fill(p.iqClassCount)(0)
      val alu = zero.updated(0, 1)
      reserve(dut, stid = 0, transactionId = 0, demands = Vector(alu))
      reserve(dut, stid = 2, transactionId = 0, demands = Vector(alu))
      dut.io.provisional(0).valid.expect(true.B)
      dut.io.provisional(2).valid.expect(true.B)
      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.provisional(0).valid.expect(false.B)
      dut.io.provisional(2).valid.expect(true.B)
      publish(dut, stid = 2, transactionId = 0)

      val twoAlu = zero.updated(0, 2)
      pokeTransaction(dut, stid = 1, transactionId = 0,
        demands = Vector(twoAlu, twoAlu))
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.io.reserveFire.poke(false.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.provisional(1).valid.expect(false.B)

      pokeTransaction(dut, stid = 3, transactionId = 0,
        demands = Vector(alu),
        plannedOverride = Some(zero.updated(1, 1)))
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.io.provisional(3).valid.expect(false.B)
    }
  }

  test("rejects stale published-slot release without freeing it") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooProductionDispatch(p)) { dut =>
      clear(dut)
      val alu = Vector.fill(p.iqClassCount)(0).updated(0, 1)
      val token = reserve(dut, stid = 0, transactionId = 0,
        demands = Vector(alu)).head
      publish(dut, stid = 0, transactionId = 0)

      val stale = token.copy(epoch = token.epoch + 1)
      val request = dut.io.release.bits
      request.poke(0.U.asTypeOf(request))
      request.peId.poke(3.U)
      request.stid.poke(0.U)
      request.epoch.poke(5.U)
      request.transactionId.poke(0.U)
      request.reservation.valid.poke(true.B)
      pokeClass(request.reservation.uopClass, stale.uopClass)
      request.reservation.bank.poke(stale.bank.U)
      request.reservation.writePort.poke(stale.port.U)
      request.reservation.speculativeSlot.poke(stale.slot.U)
      request.reservation.reservationEpoch.poke(stale.epoch.U)
      dut.io.release.valid.poke(true.B)
      dut.io.release.ready.expect(false.B)
      dut.io.releaseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.publishedEntries(token.uopClass)(token.bank).expect(1.U)

      request.reservation.reservationEpoch.poke(token.epoch.U)
      request.reservation.writePort.poke((token.port ^ 1).U)
      dut.io.release.valid.poke(true.B)
      dut.io.release.ready.expect(false.B)
      dut.io.releaseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.publishedEntries(token.uopClass)(token.bank).expect(1.U)
      release(dut, 0, 0, token)
    }
  }

  test("elaborates production dispatch at instruction widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        iqBankCount = 2,
        iqEntriesPerBank = 4,
        pMapQDepthPerStid = 4)
      circt.stage.ChiselStage.emitSystemVerilog(new OooProductionDispatch(p))
    }
  }
}
