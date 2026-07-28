package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

class OooFastResolveHarnessIO(val p: OooParams) extends Bundle {
  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val stid = Input(UInt(p.stidWidth.W))
  val epoch = Input(UInt(p.epochWidth.W))
  val transactionId = Input(UInt(p.transactionIdWidth.W))
  val fastResolveClass = Input(UInt(3.W))
  val opcode = Input(UInt(p.opcodeWidth.W))
  val pc = Input(UInt(p.pcWidth.W))
  val lengthBytes = Input(UInt(p.instructionLengthWidth.W))
  val immediateValid = Input(Bool())
  val immediate = Input(UInt(p.pcWidth.W))
  val boundaryStart = Input(Bool())
  val boundaryStop = Input(Bool())
  val requiresTargetValidation = Input(Bool())
  val targetValid = Input(Bool())
  val target = Input(UInt(p.pcWidth.W))
  val trapValid = Input(Bool())
  val trapCause = Input(UInt(p.trapCauseWidth.W))
  val destinationValid = Input(Bool())
  val ptag = Input(UInt(p.pTagWidth.W))
  val ptagGeneration = Input(UInt(p.pTagGenerationWidth.W))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooFastResolveRecoveryPrepared(p))
  val recoveryFire = Input(Bool())
  val recoveryRejected = Valid(new OooFastResolveRecoveryReject(p))

  val boundary = Decoupled(new OooFastResolveBoundaryRequest(p))
  val writeback = Decoupled(new OooFastResolveWriteback(p))
  val wakeup = Decoupled(new OooIexWakeup(p))
  val trace = Decoupled(new OooFastResolveTrace(p))
  val completion = Decoupled(new OooRobMemberCompletion(p))
  val pendingByStid = Output(Vec(p.stidCount,
    UInt(p.decodedUopCountWidth.W)))
  val terminalFire = Output(Bool())
  val rejected = Valid(new OooFastResolveS1Reject(p))
}

/** Compact stimulus shell.  It still feeds the owner its complete
  * exact O3/P/TU/dispatch transaction; the shell only removes repetitive test
  * pokes and deliberately exposes malformed result-destination admission.
  */
class OooFastResolveHarness(val p: OooParams) extends Module {
  val io = IO(new OooFastResolveHarnessIO(p))
  val owner = Module(new OooFastResolve(p))

  val request = Wire(new OooIexS1Transaction(p))
  request := 0.U.asTypeOf(request)
  val plan = request.o3.request.reservation.transaction.plan
  val decoded = request.o3.request.reservation.transaction.decoded
  val decodedUop = decoded.uops(0)
  val pUop = request.pRename.uops(0)

  plan.peId := 3.U
  plan.stid := io.stid
  plan.epoch := io.epoch
  plan.transactionId := io.transactionId
  plan.uopMask := 1.U
  decoded.peId := 3.U
  decoded.stid := io.stid
  decoded.epoch := io.epoch
  decoded.uopMask := 1.U

  decodedUop.valid := true.B
  decodedUop.opcode := io.opcode
  decodedUop.recipe.valid := true.B
  decodedUop.recipe.opcode := io.opcode
  decodedUop.recipe.disposition := Mux(io.trapValid,
    OooOpcodeDisposition.Illegal.U, OooOpcodeDisposition.FastResolve.U)
  decodedUop.recipe.fastResolveClass := io.fastResolveClass
  decodedUop.recipe.uopCountMin := 1.U
  decodedUop.recipe.uopCountMax := 1.U
  decodedUop.recipe.requiresTargetValidation :=
    io.requiresTargetValidation
  decodedUop.recipe.pDestinationCount := io.destinationValid
  decodedUop.recipe.sideEffectOwner := Mux(
    io.fastResolveClass === OooFastResolveClass.NoEffect.U,
    OooSideEffectOwner.None.U, OooSideEffectOwner.Iex.U)
  decodedUop.plannedChildCount := 1.U
  decodedUop.immediateValid := io.immediateValid
  decodedUop.immediate := io.immediate
  decodedUop.boundaryTargetValid := io.targetValid
  decodedUop.boundaryTarget := io.target
  decodedUop.preciseTrap := io.trapValid
  decodedUop.trapCause := io.trapCause
  decodedUop.identity.key.primaryParent.valid := true.B
  decodedUop.identity.key.primaryParent.peId := 3.U
  decodedUop.identity.key.primaryParent.stid := io.stid
  decodedUop.identity.key.primaryParent.instructionId := 11.U
  decodedUop.identity.key.uopOrdinal := 0.U
  decodedUop.identity.key.uopCount := 1.U
  decodedUop.identity.parentCount := 1.U
  decodedUop.identity.parents(0).key :=
    decodedUop.identity.key.primaryParent
  decodedUop.identity.parents(0).pc := io.pc
  decodedUop.identity.parents(0).lengthBytes := io.lengthBytes
  decodedUop.identity.boundary.start := io.boundaryStart
  decodedUop.identity.boundary.stop := io.boundaryStop
  decodedUop.destinations(0).valid := io.destinationValid
  decodedUop.destinations(0).kind := Mux(io.destinationValid,
    DestinationKind.Gpr, DestinationKind.None)

  request.pRename.valid := true.B
  request.pRename.peId := 3.U
  request.pRename.stid := io.stid
  request.pRename.epoch := io.epoch
  request.pRename.transactionId := io.transactionId
  request.pRename.uopMask := 1.U
  pUop.valid := true.B
  pUop.decoded := decodedUop
  pUop.member.group.valid := true.B
  pUop.member.group.peId := 3.U
  pUop.member.group.stid := io.stid
  pUop.member.group.ridSlot := 5.U
  pUop.member.group.ridGeneration := 2.U
  pUop.member.bid.valid := true.B
  pUop.member.bid.value := 9.U
  pUop.member.brobGeneration := 4.U
  pUop.member.memberIndex := 1.U
  pUop.member.residentGeneration := 6.U
  pUop.destinations(0).decoded := decodedUop.destinations(0)
  pUop.destinations(0).currentPMapping.valid := io.destinationValid
  pUop.destinations(0).currentPMapping.ptag := io.ptag
  pUop.destinations(0).currentPMapping.ptagGeneration := io.ptagGeneration
  pUop.destinations(0).currentPMapping.stid := io.stid
  pUop.destinations(0).currentPMapping.epoch := io.epoch

  request.tuRename.valid := true.B
  request.tuRename.peId := 3.U
  request.tuRename.stid := io.stid
  request.tuRename.epoch := io.epoch
  request.tuRename.transactionId := io.transactionId
  request.tuRename.uopMask := 1.U
  request.dispatch.valid := true.B
  request.dispatch.peId := 3.U
  request.dispatch.stid := io.stid
  request.dispatch.epoch := io.epoch
  request.dispatch.transactionId := io.transactionId

  owner.io.s1.valid := io.inValid
  owner.io.s1.bits := request
  owner.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := owner.io.recoveryPrepareReady
  io.recoveryPrepared := owner.io.recoveryPrepared
  owner.io.recoveryFire := io.recoveryFire
  io.recoveryRejected := owner.io.recoveryRejected
  io.inReady := owner.io.s1.ready
  io.boundary <> owner.io.boundary
  io.writeback <> owner.io.writeback
  io.wakeup <> owner.io.wakeup
  io.trace <> owner.io.trace
  io.completion <> owner.io.completion
  io.pendingByStid := owner.io.pendingByStid
  io.terminalFire := owner.io.terminalFire
  io.rejected := owner.io.s1Rejected
}

class OooFastResolveSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooFastResolveHarness): Unit = {
    dut.io.inValid.poke(false.B)
    dut.io.stid.poke(1.U)
    dut.io.epoch.poke(7.U)
    dut.io.transactionId.poke(13.U)
    dut.io.fastResolveClass.poke(OooFastResolveClass.BoundaryMetadata.U)
    dut.io.opcode.poke(19.U)
    dut.io.pc.poke("h1000".U)
    dut.io.lengthBytes.poke(4.U)
    dut.io.immediateValid.poke(false.B)
    dut.io.immediate.poke(0.U)
    dut.io.boundaryStart.poke(false.B)
    dut.io.boundaryStop.poke(false.B)
    dut.io.requiresTargetValidation.poke(false.B)
    dut.io.targetValid.poke(false.B)
    dut.io.target.poke(0.U)
    dut.io.trapValid.poke(false.B)
    dut.io.trapCause.poke(0.U)
    dut.io.destinationValid.poke(false.B)
    dut.io.ptag.poke(100.U)
    dut.io.ptagGeneration.poke(9.U)
    dut.io.boundary.ready.poke(true.B)
    dut.io.writeback.ready.poke(true.B)
    dut.io.wakeup.ready.poke(true.B)
    dut.io.trace.ready.poke(true.B)
    dut.io.completion.ready.poke(true.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def pokeRecovery(
      dut: OooFastResolveHarness,
      stid: Int): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(stid.U)
    plan.oldHead.ridSlot.poke(5.U)
    plan.oldHead.ridGeneration.poke(2.U)
    plan.oldOccupied.poke(1.U)
    plan.newOccupied.poke(0.U)
    plan.pivotOffset.poke(0.U)
    plan.pivot.group.valid.poke(true.B)
    plan.pivot.group.peId.poke(3.U)
    plan.pivot.group.stid.poke(stid.U)
    plan.pivot.group.ridSlot.poke(5.U)
    plan.pivot.group.ridGeneration.poke(2.U)
    plan.pivot.bid.valid.poke(true.B)
    plan.pivot.bid.value.poke(9.U)
    plan.pivot.brobGeneration.poke(4.U)
    plan.pivot.memberIndex.poke(1.U)
    plan.pivot.residentGeneration.poke(6.U)
    plan.pivotPhysicalMemberCount.poke(2.U)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  private def accept(dut: OooFastResolveHarness): Unit = {
    dut.io.inValid.poke(true.B)
    dut.io.inReady.expect(true.B)
    dut.clock.step()
    dut.io.inValid.poke(false.B)
  }

  test("retains each typed fast class and fires all required sinks atomically") {
    simulate(new OooFastResolveHarness(OooParams())) { dut =>
      clear(dut)
      dut.clock.step()

      // Boundary metadata waits for its own BCTRL sink and never fabricates a
      // PRF result.
      dut.io.boundaryStart.poke(true.B)
      dut.io.requiresTargetValidation.poke(true.B)
      dut.io.targetValid.poke(true.B)
      dut.io.target.poke("h2200".U)
      dut.io.boundary.ready.poke(false.B)
      accept(dut)
      dut.io.pendingByStid(1).expect(1.U)
      dut.io.boundary.valid.expect(true.B)
      dut.io.trace.valid.expect(false.B)
      dut.io.completion.valid.expect(false.B)
      dut.io.writeback.valid.expect(false.B)
      dut.io.wakeup.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.pendingByStid(1).expect(1.U)
      dut.io.boundary.ready.poke(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.clock.step()
      dut.io.pendingByStid(1).expect(0.U)

      // SETRET uses the primary architectural parent PC and decoded immediate.
      clear(dut)
      dut.io.fastResolveClass.poke(OooFastResolveClass.ImmediateProducer.U)
      dut.io.opcode.poke(343.U)
      dut.io.immediateValid.poke(true.B)
      dut.io.immediate.poke("h28".U)
      dut.io.destinationValid.poke(true.B)
      dut.io.writeback.ready.poke(false.B)
      accept(dut)
      dut.io.writeback.valid.expect(true.B)
      dut.io.writeback.bits.data.expect("h1028".U)
      dut.io.writeback.bits.ptag.expect(100.U)
      dut.io.writeback.bits.ptagGeneration.expect(9.U)
      dut.io.completion.valid.expect(false.B)
      dut.clock.step()
      dut.io.writeback.ready.poke(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.io.writeback.valid.expect(true.B)
      dut.io.wakeup.valid.expect(true.B)
      dut.io.trace.bits.result.expect("h1028".U)
      dut.clock.step()

      // START_CALL retains both target validation and RA result obligations.
      clear(dut)
      dut.io.fastResolveClass.poke(
        OooFastResolveClass.ControlValueProducer.U)
      dut.io.opcode.poke(652.U)
      dut.io.destinationValid.poke(true.B)
      dut.io.boundaryStart.poke(true.B)
      dut.io.requiresTargetValidation.poke(true.B)
      dut.io.targetValid.poke(true.B)
      dut.io.target.poke("h3000".U)
      accept(dut)
      dut.io.terminalFire.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.writeback.valid.expect(true.B)
      dut.io.writeback.bits.data.expect("h1004".U)
      dut.clock.step()

      // A precise trap publishes only the ordered trace and exact completion.
      clear(dut)
      dut.io.fastResolveClass.poke(
        OooFastResolveClass.PreciseTrapRecord.U)
      dut.io.opcode.poke(0.U)
      dut.io.trapValid.poke(true.B)
      dut.io.trapCause.poke("hdeadbeef".U)
      accept(dut)
      dut.io.terminalFire.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.trapValid.expect(true.B)
      dut.io.trace.bits.trapCause.expect("hdeadbeef".U)
      dut.io.boundary.valid.expect(false.B)
      dut.io.writeback.valid.expect(false.B)
      dut.io.wakeup.valid.expect(false.B)
      dut.clock.step()
    }
  }

  test("rejects a result producer without one exact current P destination") {
    simulate(new OooFastResolveHarness(OooParams())) { dut =>
      clear(dut)
      dut.io.fastResolveClass.poke(OooFastResolveClass.ImmediateProducer.U)
      dut.io.immediateValid.poke(true.B)
      dut.io.destinationValid.poke(false.B)
      dut.io.inValid.poke(true.B)
      dut.io.inReady.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.shapeExact.expect(false.B)
      dut.clock.step()
      dut.io.pendingByStid.foreach(_.expect(0.U))
    }
  }

  test("retains independent STIDs and drains them fairly without overwrite") {
    simulate(new OooFastResolveHarness(OooParams())) { dut =>
      clear(dut)
      dut.io.boundaryStart.poke(true.B)
      dut.io.boundary.ready.poke(false.B)
      dut.io.stid.poke(0.U)
      accept(dut)
      dut.io.pendingByStid(0).expect(1.U)

      // The resident STID cannot be overwritten, but an unrelated STID can
      // still publish while the shared terminal path is backpressured.
      dut.io.inValid.poke(true.B)
      dut.io.inReady.expect(false.B)
      dut.io.stid.poke(1.U)
      dut.io.transactionId.poke(14.U)
      dut.io.inReady.expect(true.B)
      dut.clock.step()
      dut.io.inValid.poke(false.B)
      dut.io.pendingByStid(0).expect(1.U)
      dut.io.pendingByStid(1).expect(1.U)

      dut.io.boundary.ready.poke(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.io.completion.bits.key.group.stid.expect(0.U)
      dut.clock.step()
      dut.io.pendingByStid(0).expect(0.U)
      dut.io.pendingByStid(1).expect(1.U)
      dut.io.terminalFire.expect(true.B)
      dut.io.completion.bits.key.group.stid.expect(1.U)
      dut.clock.step()
      dut.io.pendingByStid.foreach(_.expect(0.U))
    }
  }

  test("cancels exact pending fast state while another STID completes") {
    val p = OooParams(
      stidCount = 2,
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 1,
      iqEntriesPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooFastResolveHarness(p)) { dut =>
      clear(dut)
      dut.io.boundaryStart.poke(true.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.ptagGeneration.poke(1.U)
      dut.io.stid.poke(1.U)
      dut.io.transactionId.poke(1.U)
      accept(dut)
      dut.io.stid.poke(0.U)
      dut.io.transactionId.poke(2.U)
      accept(dut)
      dut.io.pendingByStid(0).expect(1.U)
      dut.io.pendingByStid(1).expect(1.U)

      pokeRecovery(dut, stid = 1)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.pendingKilled.expect(1.U)
      dut.io.completion.ready.poke(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.io.completion.bits.key.group.stid.expect(0.U)
      dut.clock.step()
      dut.io.pendingByStid(0).expect(0.U)
      dut.io.pendingByStid(1).expect(1.U)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.pendingByStid(1).expect(0.U)
      dut.io.terminalFire.expect(false.B)
    }
  }

  test("elaborates the typed owner at instruction widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      simulate(new OooFastResolveHarness(
        OooParams(instructionDecodeWidth = width))) { dut =>
        clear(dut)
        dut.clock.step()
        dut.io.pendingByStid.foreach(_.expect(0.U))
      }
    }
  }
}
