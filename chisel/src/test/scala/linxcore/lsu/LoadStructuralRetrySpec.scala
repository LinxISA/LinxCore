package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class LoadStructuralRetryHarnessIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int) extends Bundle {
    val flush = Input(Bool())
    val allocValid = Input(Bool())
    val alloc = Input(new LoadInflightAlloc(
      liqEntries = liqEntries,
      idEntries = idEntries,
      returnPipeCount = 3,
      lsidWidth = 40))
    val allocAccepted = Output(Bool())
    val launchValid = Input(Bool())
    val launchAccepted = Output(Bool())
    val retryValid = Input(Bool())
    val retry = Input(new LoadStructuralBlockRetry(
      idEntries, storeEntries, lsidWidth = 40))
    val retryReady = Output(Bool())
    val retryAccepted = Output(Bool())
    val blockedByAttempt = Output(Bool())
    val blockedByPipe = Output(Bool())
    val blockedByWaitStore = Output(Bool())
    val blockedByMutation = Output(Bool())
    val mutationConflict = Input(Bool())
    val row = Output(new LoadInflightRow(
      liqEntries = liqEntries,
      idEntries = idEntries,
      storeEntries = storeEntries,
      returnPipeCount = 3,
      lsidWidth = 40))
}

class LoadStructuralRetryHarness extends Module {
  private val liqEntries = 4
  private val idEntries = 8
  private val storeEntries = 4

  val io = IO(new LoadStructuralRetryHarnessIO(
    liqEntries, idEntries, storeEntries))

  val liq = Module(new LoadInflightQueue(
    liqEntries = liqEntries,
    idEntries = idEntries,
    storeEntries = storeEntries,
    returnPipeCount = 3,
    lsidWidth = 40,
    useExternalForwardResult = true))

  liq.io.flush := io.flush
  liq.io.preciseFlush := 0.U.asTypeOf(liq.io.preciseFlush)
  liq.io.allocValid := io.allocValid
  liq.io.alloc := io.alloc
  liq.io.attemptRebindValid := false.B
  liq.io.attemptRebind := 0.U.asTypeOf(liq.io.attemptRebind)
  liq.io.structuralRetryValid := io.retryValid
  liq.io.structuralRetry := io.retry
  liq.io.launchValid := io.launchValid
  liq.io.launchIndex := 0.U
  liq.io.launchIntentValid := io.launchValid
  liq.io.launchIntentIndex := 0.U
  liq.io.pickValid := false.B
  liq.io.pickIndex := 0.U
  liq.io.scbReturnValid := false.B
  liq.io.scbReturnIndex := 0.U
  liq.io.markResolvedValid := false.B
  liq.io.markResolvedIndex := 0.U
  liq.io.e2Stores := 0.U.asTypeOf(liq.io.e2Stores)
  liq.io.e2BaseData := 0.U
  liq.io.e2BaseValidMask := 0.U
  liq.io.e2LoadDataReturned := false.B
  liq.io.e2ScbReturned := false.B
  liq.io.e2StqReturned := false.B
  liq.io.e2ReturnReady := false.B
  liq.io.forwardResultValid := false.B
  liq.io.forwardResult := 0.U.asTypeOf(liq.io.forwardResult)
  liq.io.replayWakeValid := false.B
  liq.io.replayWake := 0.U.asTypeOf(liq.io.replayWake)
  liq.io.refillValid := false.B
  liq.io.refill := 0.U.asTypeOf(liq.io.refill)
  liq.io.clearResolvedValid := false.B
  liq.io.clearResolvedIndex := 0.U
  liq.io.rowMutationValid := io.mutationConflict
  liq.io.rowMutationTargetIndex := 0.U
  liq.io.rowMutationSetWaitStatus := false.B
  liq.io.rowMutationKeepRepickStatus := false.B
  liq.io.rowMutationClearReturnState := false.B
  liq.io.rowMutationLineWrite := false.B
  liq.io.rowMutationWaitStoreWrite := false.B
  liq.io.rowMutationNextWaitStore := false.B
  liq.io.rowMutationNextWaitStoreInfo :=
    0.U.asTypeOf(liq.io.rowMutationNextWaitStoreInfo)
  liq.io.rowMutationNextLineData := 0.U
  liq.io.rowMutationNextValidMask := 0.U
  liq.io.rowMutationNextDataComplete := false.B
  liq.io.rowMutationNextScbReturned := false.B
  liq.io.rowMutationNextStqReturned := false.B
  liq.io.rowMutationNextStoreSourceReturned := false.B
  liq.io.rowMutationAllowWaitTarget := false.B
  liq.io.rowMutationRequireScbReturned := false.B

  io.allocAccepted := liq.io.allocAccepted
  io.launchAccepted := liq.io.launchAccepted
  io.retryReady := liq.io.structuralRetryReady
  io.retryAccepted := liq.io.structuralRetryAccepted
  io.blockedByAttempt := liq.io.structuralRetryBlockedByAttempt
  io.blockedByPipe := liq.io.structuralRetryBlockedByPipe
  io.blockedByWaitStore := liq.io.structuralRetryBlockedByWaitStore
  io.blockedByMutation := liq.io.structuralRetryBlockedByMutation
  io.row := liq.io.rows(0)
}

class LoadStructuralRetrySpec extends AnyFunSuite with ChiselSim {
  private def pokeAttempt(
      attempt: LoadAttemptIdentity,
      generation: BigInt): Unit = {
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(2.U)
    attempt.producer.stid.poke(1.U)
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke(7.U)
    attempt.producer.brobGeneration.poke(11.U)
    attempt.producer.ridSlot.poke(13.U)
    attempt.producer.ridGeneration.poke(17.U)
    attempt.producer.memberIndex.poke(19.U)
    attempt.producer.residentGeneration.poke(23.U)
    attempt.generation.poke(generation.U)
  }

  private def clear(dut: LoadStructuralRetryHarness): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.allocValid.poke(false.B)
    dut.io.alloc.poke(0.U.asTypeOf(dut.io.alloc))
    dut.io.launchValid.poke(false.B)
    dut.io.retryValid.poke(false.B)
    dut.io.retry.poke(0.U.asTypeOf(dut.io.retry))
    dut.io.mutationConflict.poke(false.B)
  }

  private def allocateAndLaunch(dut: LoadStructuralRetryHarness): Unit = {
    dut.io.alloc.poke(0.U.asTypeOf(dut.io.alloc))
    dut.io.alloc.size.poke(8.U)
    dut.io.alloc.returnPipeIndex.poke(2.U)
    pokeAttempt(dut.io.alloc.attempt, generation = 5)
    dut.io.allocValid.poke(true.B)
    dut.io.allocAccepted.expect(true.B)
    dut.clock.step()
    dut.io.allocValid.poke(false.B)

    dut.io.launchValid.poke(true.B)
    dut.io.launchAccepted.expect(true.B)
    dut.clock.step()
    dut.io.launchValid.poke(false.B)
    dut.io.row.status.expect(LoadInflightStatus.Repick)
    dut.io.row.forwardPending.expect(true.B)
  }

  private def pokeRetry(dut: LoadStructuralRetryHarness): Unit = {
    dut.io.retry.poke(0.U.asTypeOf(dut.io.retry))
    dut.io.retry.loadId.valid.poke(true.B)
    dut.io.retry.loadId.slot.poke(0.U)
    dut.io.retry.loadId.generation.poke(0.U)
    pokeAttempt(dut.io.retry.current, generation = 5)
    pokeAttempt(dut.io.retry.next, generation = 6)
    dut.io.retry.returnPipeIndex.poke(2.U)
    dut.io.retry.waitStore.poke(true.B)
    dut.io.retry.waitStoreInfo.valid.poke(true.B)
    dut.io.retry.waitStoreInfo.storeIndex.poke(3.U)
    dut.io.retry.waitStoreInfo.storeId.valid.poke(true.B)
    dut.io.retry.waitStoreInfo.storeId.value.poke(4.U)
    dut.io.retry.waitStoreInfo.storeLsId.valid.poke(true.B)
    dut.io.retry.waitStoreInfo.storeLsId.value.poke(5.U)
    dut.io.retry.waitStoreInfo.storeLsIdFullValid.poke(true.B)
    dut.io.retry.waitStoreInfo.storeLsIdFull.poke("h123".U)
    dut.io.retry.waitStoreInfo.pc.poke("h80000100".U)
  }

  test("exact structural retry atomically returns a pending row to wait") {
    simulate(new LoadStructuralRetryHarness) { dut =>
      clear(dut)
      allocateAndLaunch(dut)
      pokeRetry(dut)
      dut.io.retryValid.poke(true.B)
      dut.io.retryReady.expect(true.B)
      dut.io.retryAccepted.expect(true.B)
      dut.clock.step()

      dut.io.retryValid.poke(false.B)
      dut.io.row.status.expect(LoadInflightStatus.Wait)
      dut.io.row.forwardPending.expect(false.B)
      dut.io.row.attempt.generation.expect(6.U)
      dut.io.row.waitStore.expect(true.B)
      dut.io.row.waitStoreInfo.storeIndex.expect(3.U)
      dut.io.row.waitStoreInfo.storeLsIdFull.expect("h123".U)
      dut.io.row.dataComplete.expect(false.B)
      dut.io.row.sourcesReturned.expect(false.B)
    }
  }

  test("stale attempt and wrong return pipe cannot mutate the row") {
    simulate(new LoadStructuralRetryHarness) { dut =>
      clear(dut)
      allocateAndLaunch(dut)
      pokeRetry(dut)
      dut.io.retry.current.generation.poke(4.U)
      dut.io.retryValid.poke(true.B)
      dut.io.retryReady.expect(false.B)
      dut.io.blockedByAttempt.expect(true.B)
      dut.clock.step()
      dut.io.row.attempt.generation.expect(5.U)
      dut.io.row.forwardPending.expect(true.B)

      pokeRetry(dut)
      dut.io.retry.returnPipeIndex.poke(1.U)
      dut.io.retryReady.expect(false.B)
      dut.io.blockedByPipe.expect(true.B)
      dut.clock.step()
      dut.io.row.attempt.generation.expect(5.U)
      dut.io.row.forwardPending.expect(true.B)
    }
  }

  test("malformed wait-store key cannot create an unwakeable wait") {
    simulate(new LoadStructuralRetryHarness) { dut =>
      clear(dut)
      allocateAndLaunch(dut)
      pokeRetry(dut)
      dut.io.retry.waitStoreInfo.storeLsIdFullValid.poke(false.B)
      dut.io.retryValid.poke(true.B)
      dut.io.retryReady.expect(false.B)
      dut.io.blockedByWaitStore.expect(true.B)
      dut.clock.step()
      dut.io.row.status.expect(LoadInflightStatus.Repick)
      dut.io.row.forwardPending.expect(true.B)
      dut.io.row.waitStore.expect(false.B)
    }
  }

  test("same-row mutation intent blocks structural retry without partial update") {
    simulate(new LoadStructuralRetryHarness) { dut =>
      clear(dut)
      allocateAndLaunch(dut)
      pokeRetry(dut)
      dut.io.retryValid.poke(true.B)
      dut.io.mutationConflict.poke(true.B)
      dut.io.retryReady.expect(false.B)
      dut.io.blockedByMutation.expect(true.B)
      dut.clock.step()
      dut.io.row.status.expect(LoadInflightStatus.Repick)
      dut.io.row.forwardPending.expect(true.B)
      dut.io.row.attempt.generation.expect(5.U)
    }
  }
}
