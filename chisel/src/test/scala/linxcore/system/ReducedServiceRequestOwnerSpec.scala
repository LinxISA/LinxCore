package linxcore.system

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedServiceRequestOwnerReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Identity(stid: Int, bid: Id, gid: Id, rid: Id)
  final case class Request(
      requestType: Int,
      pc: BigInt,
      nextInsnLen: Int,
      nextHalfword: Int,
      nextInsnRaw: BigInt,
      identity: Identity)

  final case class Result(
      legalType: Boolean,
      legalStopAfter: Boolean,
      serviceCandidate: Boolean,
      trapCandidate: Boolean,
      trapCause: BigInt)

  def apply(req: Request): Result = {
    val legalType = req.requestType == ReducedServiceRequestOwner.RawAcrcRequestType
    val compressedStop =
      req.nextInsnLen == 2 && req.nextHalfword == ReducedServiceRequestOwner.RawCompressedBstop
    val legalStop = compressedStop
    val trapCause =
      if (legalType) ReducedServiceRequestOwner.TrapIllegalServiceSequence
      else ReducedServiceRequestOwner.TrapIllegalServiceRequest

    Result(
      legalType = legalType,
      legalStopAfter = legalStop,
      serviceCandidate = legalType && legalStop,
      trapCandidate = !legalType || !legalStop,
      trapCause = trapCause)
  }
}

class ReducedServiceRequestOwnerSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams(robEntries = 8, blockBidWidth = 64)
  private val trace = CommitTraceParams(robValueWidth = 3)

  private def clear(dut: ReducedServiceRequestOwner): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.serviceRequest.ready.poke(false.B)
    dut.io.serviceResponse.valid.poke(false.B)
    dut.io.serviceResponse.bits.poke(0.U.asTypeOf(dut.io.serviceResponse.bits))
    dut.io.flush.poke(false.B)
    dut.io.completeReady.poke(false.B)
    dut.io.releaseReady.poke(false.B)
    dut.io.writebackReady.poke(false.B)
  }

  private def driveRequest(
      dut: ReducedServiceRequestOwner,
      requestType: Int = ReducedServiceRequestOwner.RawAcrcRequestType,
      pc: BigInt = 0x1000,
      nextInsnLen: Int = 2,
      nextHalfword: Int = ReducedServiceRequestOwner.RawCompressedBstop.toInt,
      nextInsnRaw: BigInt = 0,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      a0: BigInt = 0x10,
      a1: BigInt = 0x11,
      a2: BigInt = 0x12,
      a3: BigInt = 0x13,
      a4: BigInt = 0x14,
      a5: BigInt = 0x15,
      a7: BigInt = 0x17,
      a0PhysTag: Int = 42): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.requestType.poke(requestType.U)
    dut.io.request.bits.pc.poke(pc.U)
    dut.io.request.bits.insnLen.poke(4.U)
    dut.io.request.bits.insnRaw.poke(0x302b.U)
    dut.io.request.bits.nextInsnLen.poke(nextInsnLen.U)
    dut.io.request.bits.nextHalfword.poke(nextHalfword.U)
    dut.io.request.bits.nextInsnRaw.poke(nextInsnRaw.U)
    dut.io.request.bits.identity.stid.poke(stid.U)
    dut.io.request.bits.identity.bid.valid.poke(true.B)
    dut.io.request.bits.identity.bid.wrap.poke(false.B)
    dut.io.request.bits.identity.bid.value.poke(bid.U)
    dut.io.request.bits.identity.gid.valid.poke(true.B)
    dut.io.request.bits.identity.gid.wrap.poke(false.B)
    dut.io.request.bits.identity.gid.value.poke(gid.U)
    dut.io.request.bits.identity.rid.valid.poke(true.B)
    dut.io.request.bits.identity.rid.wrap.poke(true.B)
    dut.io.request.bits.identity.rid.value.poke(rid.U)
    dut.io.request.bits.a0.poke(a0.U)
    dut.io.request.bits.a1.poke(a1.U)
    dut.io.request.bits.a2.poke(a2.U)
    dut.io.request.bits.a3.poke(a3.U)
    dut.io.request.bits.a4.poke(a4.U)
    dut.io.request.bits.a5.poke(a5.U)
    dut.io.request.bits.a7.poke(a7.U)
    dut.io.request.bits.a0PhysTag.poke(a0PhysTag.U)
  }

  private def driveResponse(
      dut: ReducedServiceRequestOwner,
      requestType: Int = ReducedServiceRequestOwner.RawAcrcRequestType,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      a0: BigInt = BigInt("8877665544332211", 16)): Unit = {
    dut.io.serviceResponse.valid.poke(true.B)
    dut.io.serviceResponse.bits.requestType.poke(requestType.U)
    dut.io.serviceResponse.bits.identity.stid.poke(stid.U)
    dut.io.serviceResponse.bits.identity.bid.valid.poke(true.B)
    dut.io.serviceResponse.bits.identity.bid.wrap.poke(false.B)
    dut.io.serviceResponse.bits.identity.bid.value.poke(bid.U)
    dut.io.serviceResponse.bits.identity.gid.valid.poke(true.B)
    dut.io.serviceResponse.bits.identity.gid.wrap.poke(false.B)
    dut.io.serviceResponse.bits.identity.gid.value.poke(gid.U)
    dut.io.serviceResponse.bits.identity.rid.valid.poke(true.B)
    dut.io.serviceResponse.bits.identity.rid.wrap.poke(true.B)
    dut.io.serviceResponse.bits.identity.rid.value.poke(rid.U)
    dut.io.serviceResponse.bits.a0.poke(a0.U)
  }

  test("reference admits only the reduced QEMU-oracle raw ACRC plus adjacent C.BSTOP proxy shape") {
    import ReducedServiceRequestOwnerReference._
    val id = Identity(1, Id(value = 2), Id(value = 3), Id(value = 4))
    val compressed = ReducedServiceRequestOwnerReference(Request(1, 0x1000, 2, 0x0000, 0, id))
    val wide = ReducedServiceRequestOwnerReference(Request(1, 0x1000, 4, 0x1234, 0x00000001, id))
    val badStop = ReducedServiceRequestOwnerReference(Request(1, 0x1000, 2, 0x0004, 0, id))
    val badType = ReducedServiceRequestOwnerReference(Request(2, 0x1000, 2, 0x0000, 0, id))

    assert(compressed.serviceCandidate)
    assert(wide.trapCandidate)
    assert(wide.trapCause == ReducedServiceRequestOwner.TrapIllegalServiceSequence)
    assert(badStop.trapCandidate)
    assert(badStop.trapCause == ReducedServiceRequestOwner.TrapIllegalServiceSequence)
    assert(badType.trapCandidate)
    assert(badType.trapCause == ReducedServiceRequestOwner.TrapIllegalServiceRequest)
  }

  test("sim issues a legal ACRC service request and preserves identity through response side effects") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut)
      dut.io.serviceRequest.ready.poke(false.B)
      dut.io.request.ready.expect(false.B)
      dut.clock.step()

      dut.io.serviceRequest.ready.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.serviceRequest.valid.expect(true.B)
      dut.io.serviceRequest.bits.a0.expect(0x10.U)
      dut.clock.step()

      dut.io.request.valid.poke(false.B)
      dut.io.busy.expect(true.B)
      dut.io.controlFence.expect(true.B)
      driveResponse(dut)
      dut.io.completeReady.poke(true.B)
      dut.io.releaseReady.poke(true.B)
      dut.io.writebackReady.poke(false.B)
      dut.io.serviceResponse.ready.expect(false.B)
      dut.io.completeValid.expect(false.B)
      dut.clock.step()

      dut.io.writebackReady.poke(true.B)
      dut.io.serviceResponse.ready.expect(true.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRobValue.expect(4.U)
      dut.io.completeRow.trap.valid.expect(false.B)
      dut.io.completeRow.rob.wrap.expect(true.B)
      dut.io.completeRow.rob.value.expect(4.U)
      dut.io.completeRow.wb.valid.expect(true.B)
      dut.io.completeRow.wb.reg.expect(10.U)
      dut.io.completeRow.wb.data.expect(BigInt("8877665544332211", 16).U)
      dut.io.releaseValid.expect(true.B)
      dut.io.releaseRid.wrap.expect(true.B)
      dut.io.releaseRid.value.expect(4.U)
      dut.io.writebackValid.expect(true.B)
      dut.io.writeback.physTag.expect(42.U)
      dut.io.writeback.data.expect(BigInt("8877665544332211", 16).U)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  test("sim traps an unsupported request type without issuing service or writeback") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut, requestType = 9)
      dut.io.completeReady.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRow.trap.valid.expect(true.B)
      dut.io.completeRow.trap.cause.expect(ReducedServiceRequestOwner.TrapIllegalServiceRequest.U)
      dut.io.releaseValid.expect(false.B)
      dut.io.writebackValid.expect(false.B)
      dut.io.trappedInvalidRequestType.expect(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  test("sim traps ACRC when the next instruction is not an adjacent stop") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut, nextInsnLen = 2, nextHalfword = 0x0004)
      dut.io.completeReady.poke(true.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRow.trap.valid.expect(true.B)
      dut.io.completeRow.trap.cause.expect(ReducedServiceRequestOwner.TrapIllegalServiceSequence.U)
      dut.io.trappedIllegalSequence.expect(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  test("sim suppresses legal request acceptance, service issue, and trap fire in a flush cycle") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut)
      dut.io.serviceRequest.ready.poke(true.B)
      dut.io.completeReady.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.io.request.ready.expect(false.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.completeValid.expect(false.B)
      dut.clock.step()

      dut.io.flush.poke(false.B)
      dut.io.request.ready.expect(true.B)
      dut.io.serviceRequest.valid.expect(true.B)
    }
  }

  test("sim rejects 4-byte BSTOP adjacency until the cross-stack oracle is settled") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut, nextInsnLen = 4, nextHalfword = 0x0001, nextInsnRaw = ReducedServiceRequestOwner.RawBstop)
      dut.io.completeReady.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRow.trap.valid.expect(true.B)
      dut.io.completeRow.trap.cause.expect(ReducedServiceRequestOwner.TrapIllegalServiceSequence.U)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  test("sim flush cancels an in-flight service request and suppresses its late response") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut)
      dut.io.serviceRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.busy.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.controlFence.expect(true.B)

      driveResponse(dut)
      dut.io.serviceResponse.ready.expect(true.B)
      dut.io.lateResponseSuppressed.expect(true.B)
      dut.io.completeValid.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.writebackValid.expect(false.B)
      dut.clock.step()
      dut.io.controlFence.expect(false.B)
    }
  }

  test("sim holds a canceled request fence until the old response drains before accepting a new request") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut, pc = 0x1000, rid = 4)
      dut.io.serviceRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.busy.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.controlFence.expect(true.B)

      driveRequest(dut, pc = 0x2000, rid = 5)
      dut.io.request.ready.expect(false.B)
      dut.io.serviceRequest.valid.expect(false.B)

      driveResponse(dut, rid = 4)
      dut.io.serviceResponse.ready.expect(true.B)
      dut.io.lateResponseSuppressed.expect(true.B)
      dut.clock.step()

      dut.io.serviceResponse.valid.poke(false.B)
      dut.io.controlFence.expect(false.B)
      dut.io.request.ready.expect(true.B)
      dut.io.serviceRequest.valid.expect(true.B)
      dut.clock.step()

      dut.io.request.valid.poke(false.B)
      dut.io.busy.expect(true.B)
    }
  }

  test("sim holds a mismatched response without releasing the pending request") {
    simulate(new ReducedServiceRequestOwner(p, trace)) { dut =>
      clear(dut)
      driveRequest(dut)
      dut.io.serviceRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      driveResponse(dut, rid = 5)
      dut.io.completeReady.poke(true.B)
      dut.io.releaseReady.poke(true.B)
      dut.io.writebackReady.poke(true.B)
      dut.io.responseIdentityMismatch.expect(true.B)
      dut.io.serviceResponse.ready.expect(false.B)
      dut.io.completeValid.expect(false.B)
      dut.clock.step()
      dut.io.busy.expect(true.B)
    }
  }

  test("Chisel ReducedServiceRequestOwner elaborates the standalone service owner surface") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedServiceRequestOwner(p, trace))

    assert(sv.contains("module ReducedServiceRequestOwner"))
    assert(sv.contains("io_request_ready"))
    assert(sv.contains("io_serviceRequest_valid"))
    assert(sv.contains("io_serviceResponse_ready"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_releaseValid"))
    assert(sv.contains("io_writebackValid"))
    assert(sv.contains("io_controlFence"))
    assert(sv.contains("io_lateResponseSuppressed"))
    assert(sv.contains("cancelDrain"))
  }
}
