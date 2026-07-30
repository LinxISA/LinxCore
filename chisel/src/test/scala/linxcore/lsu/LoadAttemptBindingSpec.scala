package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.ScalarLsuParams
import linxcore.ooo.OooParams
import linxcore.rob.ROBID

class LoadAttemptBindingHarnessIO(
    val liqEntries: Int,
    val idEntries: Int)
    extends Bundle {
  val flush = Input(Bool())
  val allocValid = Input(Bool())
  val alloc = Input(new LoadInflightAlloc(
    liqEntries = liqEntries,
    idEntries = idEntries,
    lsidWidth = 40))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocLoadId = Output(new ROBID(liqEntries))
  val allocAttemptMalformed = Output(Bool())
  val rebindValid = Input(Bool())
  val rebind = Input(new LoadAttemptRebind(liqEntries))
  val rebindReady = Output(Bool())
  val rebindAccepted = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByInvalidLoadId = Output(Bool())
  val blockedByNonresidentRow = Output(Bool())
  val blockedByLifecycle = Output(Bool())
  val blockedByStaleAttempt = Output(Bool())
  val blockedByNextAttempt = Output(Bool())
  val launchValid = Input(Bool())
  val launchAccepted = Output(Bool())
  val resolveSources = Input(Bool())
  val clearRow0 = Input(Bool())
  val clearAccepted = Output(Bool())
  val lhqRecordValid = Output(Bool())
  val rowValid = Output(Bool())
  val rowStatus = Output(LoadInflightStatus())
  val rowLoadId = Output(new ROBID(liqEntries))
  val rowAttempt = Output(new LoadAttemptIdentity)
}

class LoadAttemptBindingHarness extends Module {
  private val liqEntries = 4
  private val idEntries = 8
  private val storeEntries = 4

  val io = IO(new LoadAttemptBindingHarnessIO(liqEntries, idEntries))

  val liq = Module(new LoadInflightQueue(
    liqEntries = liqEntries,
    idEntries = idEntries,
    storeEntries = storeEntries,
    lsidWidth = 40))

  liq.io.flush := io.flush
  liq.io.preciseFlush := 0.U.asTypeOf(liq.io.preciseFlush)
  liq.io.allocValid := io.allocValid
  liq.io.alloc := io.alloc
  liq.io.attemptRebindValid := io.rebindValid
  liq.io.attemptRebind := io.rebind
  liq.io.launchValid := io.launchValid
  liq.io.launchIndex := 0.U
  liq.io.pickValid := false.B
  liq.io.pickIndex := 0.U
  liq.io.scbReturnValid := false.B
  liq.io.scbReturnIndex := 0.U
  liq.io.markResolvedValid := false.B
  liq.io.markResolvedIndex := 0.U
  liq.io.e2Stores := 0.U.asTypeOf(liq.io.e2Stores)
  liq.io.e2BaseData := 0.U
  liq.io.e2BaseValidMask := Mux(io.resolveSources, "hffffffffffffffff".U, 0.U)
  liq.io.e2LoadDataReturned := io.resolveSources
  liq.io.e2ScbReturned := io.resolveSources
  liq.io.e2StqReturned := io.resolveSources
  liq.io.e2ReturnReady := io.resolveSources
  liq.io.replayWakeValid := false.B
  liq.io.replayWake := 0.U.asTypeOf(liq.io.replayWake)
  liq.io.refillValid := false.B
  liq.io.refill := 0.U.asTypeOf(liq.io.refill)
  liq.io.clearResolvedValid := io.clearRow0
  liq.io.clearResolvedIndex := 0.U
  liq.io.rowMutationValid := false.B
  liq.io.rowMutationTargetIndex := 0.U
  liq.io.rowMutationSetWaitStatus := false.B
  liq.io.rowMutationKeepRepickStatus := false.B
  liq.io.rowMutationClearReturnState := false.B
  liq.io.rowMutationLineWrite := false.B
  liq.io.rowMutationWaitStoreWrite := false.B
  liq.io.rowMutationNextWaitStore := false.B
  liq.io.rowMutationNextWaitStoreInfo := 0.U.asTypeOf(liq.io.rowMutationNextWaitStoreInfo)
  liq.io.rowMutationNextLineData := 0.U
  liq.io.rowMutationNextValidMask := 0.U
  liq.io.rowMutationNextDataComplete := false.B
  liq.io.rowMutationNextScbReturned := false.B
  liq.io.rowMutationNextStqReturned := false.B
  liq.io.rowMutationNextStoreSourceReturned := false.B
  liq.io.rowMutationAllowWaitTarget := false.B
  liq.io.rowMutationRequireScbReturned := false.B

  io.allocReady := liq.io.allocReady
  io.allocAccepted := liq.io.allocAccepted
  io.allocLoadId := liq.io.allocLoadId
  io.allocAttemptMalformed := liq.io.allocAttemptMalformed
  io.rebindReady := liq.io.attemptRebindReady
  io.rebindAccepted := liq.io.attemptRebindAccepted
  io.blockedByFlush := liq.io.attemptRebindBlockedByFlush
  io.blockedByInvalidLoadId := liq.io.attemptRebindBlockedByInvalidLoadId
  io.blockedByNonresidentRow := liq.io.attemptRebindBlockedByNonresidentRow
  io.blockedByLifecycle := liq.io.attemptRebindBlockedByLifecycle
  io.blockedByStaleAttempt := liq.io.attemptRebindBlockedByStaleAttempt
  io.blockedByNextAttempt := liq.io.attemptRebindBlockedByNextAttempt
  io.launchAccepted := liq.io.launchAccepted
  io.clearAccepted := liq.io.clearResolvedAccepted
  io.lhqRecordValid := liq.io.lhqRecordValid
  io.rowValid := liq.io.rows(0).valid
  io.rowStatus := liq.io.rows(0).status
  io.rowLoadId := liq.io.rows(0).loadId
  io.rowAttempt := liq.io.rows(0).attempt
}

class LoadAttemptBindingSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: LoadAttemptBindingHarness): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.allocValid.poke(false.B)
    dut.io.alloc.poke(0.U.asTypeOf(dut.io.alloc))
    dut.io.rebindValid.poke(false.B)
    dut.io.rebind.poke(0.U.asTypeOf(dut.io.rebind))
    dut.io.launchValid.poke(false.B)
    dut.io.resolveSources.poke(false.B)
    dut.io.clearRow0.poke(false.B)
  }

  private def pokeAttempt(
      attempt: LoadAttemptIdentity,
      nativeBid: Int,
      ridSlot: Int,
      generation: BigInt): Unit = {
    attempt.poke(0.U.asTypeOf(attempt))
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(3.U)
    attempt.producer.stid.poke(2.U)
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke(nativeBid.U)
    attempt.producer.brobGeneration.poke(9.U)
    attempt.producer.ridSlot.poke(ridSlot.U)
    attempt.producer.ridGeneration.poke(11.U)
    attempt.producer.memberIndex.poke(5.U)
    attempt.producer.residentGeneration.poke(13.U)
    attempt.generation.poke(generation.U)
  }

  private def pokeLoadId(id: ROBID, wrap: Boolean = false): Unit = {
    id.valid.poke(true.B)
    id.wrap.poke(wrap.B)
    id.value.poke(0.U)
  }

  test("protocol capacities admit production OOO widths and reject truncating bridges") {
    val p = OooParams()
    LoadAttemptIdentity.requireBridgeFits(
      peIdWidth = p.peIdWidth,
      stidWidth = p.stidWidth,
      nativeBidWidth = p.nativeBidWidth,
      ridSlotWidth = p.ridSlotWidth,
      brobGenerationWidth = p.brobGenerationWidth,
      ridGenerationWidth = p.ridGenerationWidth,
      memberIndexWidth = p.robMemberIndexWidth,
      residentGenerationWidth = p.residentGenerationWidth,
      attemptGenerationWidth = p.loadGenerationWidth)

    intercept[IllegalArgumentException] {
      LoadAttemptIdentity.requireBridgeFits(
        peIdWidth = p.peIdWidth,
        stidWidth = p.stidWidth,
        nativeBidWidth = LoadAttemptIdentity.IndexWidth + 1,
        ridSlotWidth = p.ridSlotWidth,
        brobGenerationWidth = p.brobGenerationWidth,
        ridGenerationWidth = p.ridGenerationWidth,
        memberIndexWidth = p.robMemberIndexWidth,
        residentGenerationWidth = p.residentGenerationWidth,
        attemptGenerationWidth = p.loadGenerationWidth)
    }
    intercept[IllegalArgumentException] {
      LoadAttemptIdentity.requireBridgeFits(
        peIdWidth = p.peIdWidth,
        stidWidth = p.stidWidth,
        nativeBidWidth = p.nativeBidWidth,
        ridSlotWidth = p.ridSlotWidth,
        brobGenerationWidth = p.brobGenerationWidth,
        ridGenerationWidth = p.ridGenerationWidth,
        memberIndexWidth = p.robMemberIndexWidth,
        residentGenerationWidth = p.residentGenerationWidth,
        attemptGenerationWidth = LoadAttemptIdentity.GenerationWidth + 1)
    }
  }

  test("LIQ co-allocation and exact next-generation rebind preserve producer identity") {
    simulate(new LoadAttemptBindingHarness) { dut =>
      clear(dut)
      dut.io.allocValid.poke(true.B)
      dut.io.alloc.size.poke(8.U)
      dut.io.alloc.attempt.valid.poke(true.B)
      dut.io.allocReady.expect(false.B)
      dut.io.allocAccepted.expect(false.B)
      dut.io.allocAttemptMalformed.expect(true.B)

      pokeAttempt(dut.io.alloc.attempt, nativeBid = 6, ridSlot = 17, generation = 3)
      dut.io.allocReady.expect(true.B)
      dut.io.allocAccepted.expect(true.B)
      dut.clock.step()

      dut.io.allocValid.poke(false.B)
      dut.io.rowValid.expect(true.B)
      dut.io.rowAttempt.producer.nativeBid.expect(6.U)
      dut.io.rowAttempt.producer.ridSlot.expect(17.U)
      dut.io.rowAttempt.generation.expect(3.U)

      dut.io.rebindValid.poke(true.B)
      pokeLoadId(dut.io.rebind.loadId)
      pokeAttempt(dut.io.rebind.current, nativeBid = 6, ridSlot = 17, generation = 3)
      pokeAttempt(dut.io.rebind.next, nativeBid = 6, ridSlot = 17, generation = 4)
      dut.io.rebindReady.expect(true.B)
      dut.io.rebindAccepted.expect(true.B)
      dut.clock.step()

      dut.io.rebindValid.poke(false.B)
      dut.io.rowAttempt.producer.nativeBid.expect(6.U)
      dut.io.rowAttempt.producer.ridSlot.expect(17.U)
      dut.io.rowAttempt.generation.expect(4.U)
    }
  }

  test("LIQ rejects stale producer, skipped generation, launch races, flush, and row reuse") {
    simulate(new LoadAttemptBindingHarness) { dut =>
      clear(dut)
      dut.io.allocValid.poke(true.B)
      dut.io.alloc.size.poke(8.U)
      pokeAttempt(dut.io.alloc.attempt, nativeBid = 6, ridSlot = 17, generation = 4)
      dut.clock.step()
      dut.io.allocValid.poke(false.B)

      dut.io.rebindValid.poke(true.B)
      pokeLoadId(dut.io.rebind.loadId)
      pokeAttempt(dut.io.rebind.current, nativeBid = 7, ridSlot = 17, generation = 4)
      pokeAttempt(dut.io.rebind.next, nativeBid = 7, ridSlot = 17, generation = 5)
      dut.io.rebindAccepted.expect(false.B)
      dut.io.blockedByStaleAttempt.expect(true.B)

      pokeAttempt(dut.io.rebind.current, nativeBid = 6, ridSlot = 17, generation = 4)
      pokeAttempt(dut.io.rebind.next, nativeBid = 6, ridSlot = 17, generation = 6)
      dut.io.blockedByNextAttempt.expect(true.B)

      pokeAttempt(dut.io.rebind.next, nativeBid = 6, ridSlot = 17, generation = 5)
      dut.io.launchValid.poke(true.B)
      dut.io.launchAccepted.expect(true.B)
      dut.io.rebindAccepted.expect(false.B)
      dut.io.blockedByLifecycle.expect(true.B)
      dut.clock.step()
      dut.io.launchValid.poke(false.B)
      dut.io.rowAttempt.generation.expect(4.U)

      dut.io.flush.poke(true.B)
      dut.io.rebindAccepted.expect(false.B)
      dut.io.blockedByFlush.expect(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.rebindValid.poke(false.B)
      dut.io.rowValid.expect(false.B)

      dut.io.allocValid.poke(true.B)
      dut.io.alloc.poke(0.U.asTypeOf(dut.io.alloc))
      dut.io.alloc.size.poke(8.U)
      pokeAttempt(dut.io.alloc.attempt, nativeBid = 8, ridSlot = 19, generation = 1)
      dut.clock.step()
      dut.io.allocValid.poke(false.B)

      dut.io.rebindValid.poke(true.B)
      pokeLoadId(dut.io.rebind.loadId)
      pokeAttempt(dut.io.rebind.current, nativeBid = 6, ridSlot = 17, generation = 4)
      pokeAttempt(dut.io.rebind.next, nativeBid = 6, ridSlot = 17, generation = 5)
      dut.io.blockedByStaleAttempt.expect(true.B)
      dut.io.rowAttempt.producer.nativeBid.expect(8.U)
      dut.io.rowAttempt.generation.expect(1.U)
    }
  }

  test("LIQ accepts only the exact modulo successor at attempt-generation wrap") {
    simulate(new LoadAttemptBindingHarness) { dut =>
      clear(dut)
      val lastGeneration = (BigInt(1) << LoadAttemptIdentity.GenerationWidth) - 1
      dut.io.allocValid.poke(true.B)
      dut.io.alloc.size.poke(8.U)
      pokeAttempt(dut.io.alloc.attempt, nativeBid = 9, ridSlot = 21, generation = lastGeneration)
      dut.clock.step()

      dut.io.allocValid.poke(false.B)
      dut.io.rebindValid.poke(true.B)
      pokeLoadId(dut.io.rebind.loadId)
      pokeAttempt(dut.io.rebind.current, nativeBid = 9, ridSlot = 21, generation = lastGeneration)
      pokeAttempt(dut.io.rebind.next, nativeBid = 9, ridSlot = 21, generation = 0)
      dut.io.rebindReady.expect(true.B)
      dut.io.rebindAccepted.expect(true.B)
      dut.clock.step()
      dut.io.rowAttempt.generation.expect(0.U)
    }
  }

  test("LIQ rejects an old slot lease after natural opposite-wrap row reuse") {
    simulate(new LoadAttemptBindingHarness) { dut =>
      clear(dut)

      dut.io.allocValid.poke(true.B)
      dut.io.alloc.size.poke(8.U)
      pokeAttempt(dut.io.alloc.attempt, nativeBid = 20, ridSlot = 30, generation = 1)
      dut.io.allocLoadId.wrap.expect(false.B)
      dut.clock.step()

      for (offset <- 1 to 3) {
        dut.io.alloc.poke(0.U.asTypeOf(dut.io.alloc))
        dut.io.alloc.size.poke(8.U)
        pokeAttempt(
          dut.io.alloc.attempt,
          nativeBid = 20 + offset,
          ridSlot = 30 + offset,
          generation = 1)
        dut.io.allocReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.allocValid.poke(false.B)

      dut.io.launchValid.poke(true.B)
      dut.io.resolveSources.poke(true.B)
      dut.io.launchAccepted.expect(true.B)
      dut.clock.step()
      dut.io.launchValid.poke(false.B)
      dut.io.resolveSources.poke(false.B)
      dut.clock.step()
      dut.io.lhqRecordValid.expect(true.B)
      dut.clock.step()

      dut.io.clearRow0.poke(true.B)
      dut.io.clearAccepted.expect(true.B)
      dut.clock.step()
      dut.io.clearRow0.poke(false.B)
      dut.io.rowValid.expect(false.B)

      dut.io.allocValid.poke(true.B)
      dut.io.alloc.poke(0.U.asTypeOf(dut.io.alloc))
      dut.io.alloc.size.poke(8.U)
      pokeAttempt(dut.io.alloc.attempt, nativeBid = 24, ridSlot = 34, generation = 7)
      dut.io.allocLoadId.wrap.expect(true.B)
      dut.clock.step()
      dut.io.allocValid.poke(false.B)
      dut.io.rowLoadId.wrap.expect(true.B)

      dut.io.rebindValid.poke(true.B)
      pokeLoadId(dut.io.rebind.loadId, wrap = false)
      pokeAttempt(dut.io.rebind.current, nativeBid = 24, ridSlot = 34, generation = 7)
      pokeAttempt(dut.io.rebind.next, nativeBid = 24, ridSlot = 34, generation = 8)
      dut.io.rebindAccepted.expect(false.B)
      dut.io.blockedByNonresidentRow.expect(true.B)
      dut.io.blockedByStaleAttempt.expect(false.B)
      dut.io.rowAttempt.generation.expect(7.U)
    }
  }

  test("W1 and W2 retain exact attempt identity under terminal backpressure") {
    val p = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      scbEntries = 4,
      liqEntries = 4,
      loadMissQueueEntries = 4,
      loadRefillQueueEntries = 4,
      resolveQueueEntries = 4,
      loadReturnPipeCount = 1,
      loadReturnQueueEntries = 2)

    simulate(new ScalarLSULoadReturnPipeline(idEntries = 8, p = p, lsidWidth = 40)) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.flush.poke(false.B)
      dut.io.preciseFlush.poke(0.U.asTypeOf(dut.io.preciseFlush))
      dut.io.inValid.poke(true.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.in.payload.valid.poke(true.B)
      dut.io.in.payload.rid.valid.poke(true.B)
      pokeAttempt(dut.io.in.payload.attempt, nativeBid = 10, ridSlot = 23, generation = 0x1234)
      dut.io.robRowValid.poke(true.B)
      dut.io.robRowNeedFlush.poke(false.B)
      dut.io.resolveReady(0).poke(false.B)
      dut.io.writebackReady(0).poke(false.B)
      dut.io.wakeupReady(0).poke(false.B)

      dut.io.robLookupValid.expect(true.B)
      dut.io.robLookupAttempt.producer.nativeBid.expect(10.U)
      dut.io.robLookupAttempt.producer.ridSlot.expect(23.U)
      dut.io.robLookupAttempt.generation.expect(0x1234.U)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.clock.step()
      dut.io.w2ValidMask.expect(1.U)
      dut.io.resolveFire(0).expect(false.B)
      dut.io.completion(0).payload.attempt.producer.nativeBid.expect(10.U)
      dut.io.completion(0).payload.attempt.producer.ridSlot.expect(23.U)
      dut.io.completion(0).payload.attempt.generation.expect(0x1234.U)

      dut.clock.step(2)
      dut.io.completion(0).payload.attempt.generation.expect(0x1234.U)
      dut.io.resolveReady(0).poke(true.B)
      dut.io.writebackReady(0).poke(true.B)
      dut.io.wakeupReady(0).poke(true.B)
      dut.io.resolveFire(0).expect(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("terminal payload formatting copies exact attempts only for valid data") {
    simulate(new LoadReplayReturnLretPayload(idEntries = 8, lsidWidth = 40)) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.launchValid.poke(true.B)
      dut.io.dataValid.poke(true.B)
      dut.io.selectedBid.poke(0.U.asTypeOf(dut.io.selectedBid))
      dut.io.selectedGid.poke(0.U.asTypeOf(dut.io.selectedGid))
      dut.io.selectedRid.poke(0.U.asTypeOf(dut.io.selectedRid))
      dut.io.selectedLoadLsId.poke(0.U.asTypeOf(dut.io.selectedLoadLsId))
      dut.io.selectedLoadLsIdFullValid.poke(false.B)
      dut.io.selectedLoadLsIdFull.poke(0.U)
      pokeAttempt(dut.io.selectedAttempt, nativeBid = 12, ridSlot = 29, generation = 0x5678)
      dut.io.selectedPeId.poke(0.U)
      dut.io.selectedStid.poke(0.U)
      dut.io.selectedTid.poke(0.U)
      dut.io.selectedPc.poke(0.U)
      dut.io.selectedAddr.poke(0.U)
      dut.io.selectedSize.poke(8.U)
      dut.io.selectedDst.poke(0.U.asTypeOf(dut.io.selectedDst))
      dut.io.selectedSourceTraceValid.poke(false.B)
      dut.io.selectedSource0.poke(0.U.asTypeOf(dut.io.selectedSource0))
      dut.io.selectedSource1.poke(0.U.asTypeOf(dut.io.selectedSource1))
      dut.io.returnData.poke(0x1122334455667788L.U)
      dut.io.returnPipeIndex.poke(0.U)
      dut.io.specWakeup.poke(false.B)
      dut.io.stackValid.poke(false.B)

      dut.io.payloadValid.expect(true.B)
      dut.io.payloadAttempt.producer.nativeBid.expect(12.U)
      dut.io.payloadAttempt.producer.ridSlot.expect(29.U)
      dut.io.payloadAttempt.generation.expect(0x5678.U)

      dut.io.dataValid.poke(false.B)
      dut.io.payloadValid.expect(false.B)
      dut.io.payloadAttempt.valid.expect(false.B)
      dut.io.payloadAttempt.producer.nativeBid.expect(0.U)
      dut.io.payloadAttempt.generation.expect(0.U)

      dut.io.dataValid.poke(true.B)
      dut.io.selectedAttempt.valid.poke(false.B)
      dut.io.selectedAttempt.producer.nativeBid.poke(15.U)
      dut.io.selectedAttempt.generation.poke(9.U)
      dut.io.payloadValid.expect(true.B)
      dut.io.payloadAttempt.valid.expect(false.B)
      dut.io.payloadAttempt.producer.nativeBid.expect(0.U)
      dut.io.payloadAttempt.generation.expect(0.U)
    }
  }
}
