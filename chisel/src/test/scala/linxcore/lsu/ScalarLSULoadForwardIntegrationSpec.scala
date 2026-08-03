package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.rob.ROBID

class ScalarLSULoadForwardIntegrationHarnessIO extends Bundle {
  val flush = Input(Bool())
  val preciseFlush = Input(Bool())
  val nativeBid = Input(UInt(LoadAttemptIdentity.IndexWidth.W))
  val allocValid = Input(Bool())
  val allocAccepted = Output(Bool())
  val launchValid = Input(Bool())
  val launchAccepted = Output(Bool())
  val queryReady = Input(Bool())
  val queryValid = Output(Bool())
  val queryAccepted = Output(Bool())
  val queryCaptured = Output(Bool())
  val queryLoadId = Output(new LoadCanonicalRowIdentity)
  val queryAttempt = Output(new LoadAttemptIdentity)
  val responseValid = Input(Bool())
  val responseReady = Output(Bool())
  val resultAccepted = Output(Bool())
  val resultPending = Output(Bool())
  val pathEmpty = Output(Bool())
  val rowDataComplete = Output(Bool())
  val rowLineData = Output(UInt(512.W))
  val protocolError = Output(Bool())
}

class ScalarLSULoadForwardIntegrationHarness extends Module {
  private val lsu = ScalarLsuParams(
    stqEntries = 4,
    commitQueueEntries = 4,
    commitIssueWidth = 1,
    scbEntries = 4,
    liqEntries = 4,
    loadMissQueueEntries = 2,
    loadRefillQueueEntries = 2,
    resolveQueueEntries = 8,
    mdbSsitEntries = 4,
    mdbCommandQueueEntries = 4,
    mdbOutputQueueEntries = 4,
    mdbWaitPlanQueueEntries = 4,
    mdbRecoveryQueueEntries = 4,
    mdbFailedWaitTimeoutCycles = 8,
    loadReturnQueueEntries = 2,
    loadReturnPipeCount = 2,
    l1dSets = 2,
    l1dWays = 2,
    mapQDepth = 8)
  private val core = CoreParams(robEntries = 8, lsidWidth = 40, scalarLsu = lsu)

  val io = IO(new ScalarLSULoadForwardIntegrationHarnessIO)
  val path = Module(new ScalarLSULoadPath(
    core,
    useExternalStqForwarding = true,
    stqForwardRobEntries = 8,
    stqForwardTokenWidth = 40))
  val forward = path.stqForward.get

  path.io.flush := io.flush
  path.io.preciseFlush := 0.U.asTypeOf(path.io.preciseFlush)
  path.io.preciseFlush.req.valid := io.preciseFlush
  path.io.preciseFlush.req.stid := 1.U
  path.io.allocValid := io.allocValid
  path.io.alloc := 0.U.asTypeOf(path.io.alloc)
  path.io.alloc.bid := ROBID.zero(8)
  path.io.alloc.gid := ROBID.zero(8)
  path.io.alloc.rid := ROBID.zero(8)
  path.io.alloc.loadLsId := ROBID.zero(8)
  path.io.alloc.loadLsIdFullValid := true.B
  path.io.alloc.loadLsIdFull := 11.U
  path.io.alloc.attempt.valid := true.B
  path.io.alloc.attempt.producer.valid := true.B
  path.io.alloc.attempt.producer.peId := 0.U
  path.io.alloc.attempt.producer.stid := 0.U
  path.io.alloc.attempt.producer.nativeBidValid := true.B
  path.io.alloc.attempt.producer.nativeBid := io.nativeBid
  path.io.alloc.attempt.producer.brobGeneration := 2.U
  path.io.alloc.attempt.producer.ridSlot := 3.U
  path.io.alloc.attempt.producer.ridGeneration := 4.U
  path.io.alloc.attempt.producer.memberIndex := 1.U
  path.io.alloc.attempt.producer.residentGeneration := 6.U
  path.io.alloc.attempt.generation := 7.U
  path.io.alloc.addr := 0.U
  path.io.alloc.size := 8.U
  path.io.alloc.returnPipeIndex := 1.U
  path.io.alloc.youngestStoreId := ROBID.disabled(8)
  path.io.alloc.youngestStoreLsId := ROBID.disabled(8)
  path.io.attemptRebindValid := false.B
  path.io.attemptRebind := 0.U.asTypeOf(path.io.attemptRebind)
  path.io.structuralRetryValid := false.B
  path.io.structuralRetry := 0.U.asTypeOf(path.io.structuralRetry)
  path.io.launchValid := io.launchValid
  path.io.launchIndex := 0.U
  path.io.pickValid := false.B
  path.io.pickIndex := 0.U
  path.io.scbReturnValid := false.B
  path.io.scbReturnIndex := 0.U

  path.io.e2Stores := 0.U.asTypeOf(path.io.e2Stores)
  path.io.e2ScbReturned := false.B
  path.io.e2StqReturned := false.B
  path.io.replayWakeValid := false.B
  path.io.replayWake := 0.U.asTypeOf(path.io.replayWake)
  path.io.refillValid := false.B
  path.io.refill := 0.U.asTypeOf(path.io.refill)
  path.io.missRequestReady := true.B
  path.io.missResponseValid := false.B
  path.io.missResponse := 0.U.asTypeOf(path.io.missResponse)
  path.io.resolveRetireValid := false.B
  path.io.resolveRetireBid := ROBID.disabled(8)
  path.io.resolveRetireLsId := ROBID.disabled(8)
  path.io.resolveRetireLsIdFullValid := false.B
  path.io.resolveRetireLsIdFull := 0.U
  path.io.l1dEvictionReady := true.B
  path.io.loadReturn.robRowValid := true.B
  path.io.loadReturn.robRowNeedFlush := false.B
  path.io.loadReturn.resolveReady := true.B
  path.io.loadReturn.writebackReady := true.B
  path.io.loadReturn.wakeupReady := true.B

  path.scbCache.update := 0.U.asTypeOf(path.scbCache.update)
  path.scbCache.lookupValid := false.B
  path.scbCache.lookupLineAddr := 0.U
  path.scbCache.grantWriteValid := false.B
  path.scbCache.grantWriteLineAddr := 0.U
  path.mdbStore.probe := 0.U.asTypeOf(path.mdbStore.probe)
  path.mdbStore.probeCommit := false.B
  path.mdbStore.rows := 0.U.asTypeOf(path.mdbStore.rows)
  path.recovery.ready := true.B

  forward.scb.returned := true.B
  forward.scb.validMask := 0.U
  forward.scb.data := 0.U
  for (pipe <- 0 until 2) {
    forward.queries(pipe).ready := io.queryReady
    forward.responses(pipe).valid := false.B
    forward.responses(pipe).bits := 0.U.asTypeOf(forward.responses(pipe).bits)
  }
  forward.hardBlock.ready := true.B

  val capturedQuery = RegInit(0.U.asTypeOf(chiselTypeOf(forward.queries(1).bits)))
  val capturedQueryValid = RegInit(false.B)
  when(forward.queries(1).fire) {
    capturedQuery := forward.queries(1).bits
    capturedQueryValid := true.B
  }
  forward.responses(1).valid := io.responseValid && capturedQueryValid
  forward.responses(1).bits.query := capturedQuery
  forward.responses(1).bits.loadByteMask := "hff".U
  forward.responses(1).bits.forwardMask := "hff".U
  forward.responses(1).bits.mergedLineData := "h8877665544332211".U
  forward.responses(1).bits.bypassComplete := true.B

  io.allocAccepted := path.io.allocAccepted
  io.launchAccepted := path.io.launchAccepted
  io.queryValid := forward.queries(1).valid
  io.queryAccepted := forward.queryAccepted
  io.queryCaptured := capturedQueryValid
  io.queryLoadId := forward.queries(1).bits.loadId
  io.queryAttempt := forward.queries(1).bits.attempt
  io.responseReady := forward.responses(1).ready && capturedQueryValid
  io.resultAccepted := forward.resultAccepted
  io.resultPending := forward.resultPending
  io.pathEmpty := path.io.empty
  io.rowDataComplete := path.io.liqRows(0).dataComplete
  io.rowLineData := path.io.liqRows(0).lineData
  io.protocolError := forward.protocolError || path.io.transferProtocolError ||
    (io.responseValid && !capturedQueryValid)
}

class ScalarLSULoadForwardIntegrationSpec extends AnyFunSuite with ChiselSim {
  test("two-pipe forwarding preserves query result recovery and identity behavior") {
    simulate(new ScalarLSULoadForwardIntegrationHarness) { dut =>
      def resetHarness(nativeBid: Int = 5, queryReady: Boolean = false): Unit = {
        dut.io.flush.poke(false.B)
        dut.io.preciseFlush.poke(false.B)
        dut.io.nativeBid.poke(nativeBid.U)
        dut.io.allocValid.poke(false.B)
        dut.io.launchValid.poke(false.B)
        dut.io.queryReady.poke(queryReady.B)
        dut.io.responseValid.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step()
      }

      def allocateAndLaunch(): Unit = {
        dut.io.allocValid.poke(true.B)
        dut.io.allocAccepted.expect(true.B)
        dut.clock.step()
        dut.io.allocValid.poke(false.B)
        dut.io.launchValid.poke(true.B)
        dut.io.launchAccepted.expect(true.B)
        dut.clock.step()
        dut.io.launchValid.poke(false.B)
      }

      withClue("retained exact result: ") {
        resetHarness()
        dut.io.allocValid.poke(true.B)
        dut.io.allocAccepted.expect(true.B)
        dut.clock.step()
        dut.io.allocValid.poke(false.B)
        dut.io.launchValid.poke(true.B)
        dut.io.queryValid.expect(false.B)
        dut.io.launchAccepted.expect(true.B)
        dut.clock.step()
        dut.io.launchValid.poke(false.B)
        dut.io.queryValid.expect(true.B)
        dut.io.launchAccepted.expect(false.B)
        dut.io.queryAccepted.expect(false.B)
        dut.io.queryLoadId.valid.expect(true.B)
        dut.io.queryLoadId.slot.expect(0.U)
        dut.io.queryAttempt.generation.expect(7.U)
        dut.clock.step(2)
        dut.io.queryValid.expect(true.B)
        dut.io.queryLoadId.slot.expect(0.U)
        dut.io.queryAttempt.generation.expect(7.U)
        dut.io.queryReady.poke(true.B)
        dut.clock.step()
        dut.io.queryValid.expect(false.B)
        dut.io.queryCaptured.expect(true.B)
        dut.io.responseValid.poke(true.B)
        dut.io.responseReady.expect(true.B)
        dut.clock.step()
        dut.io.responseValid.poke(false.B)

        var accepted = false
        var completed = false
        var lineData = BigInt(0)
        for (_ <- 0 until 6) {
          accepted ||= dut.io.resultAccepted.peek().litToBoolean
          if (dut.io.rowDataComplete.peek().litToBoolean) {
            completed = true
            lineData = dut.io.rowLineData.peek().litValue
          }
          dut.clock.step()
        }
        assert(accepted, "the exact retained STQ E4 result must reach LIQ")
        assert(completed, "the accepted result must complete the canonical row")
        assert(lineData == BigInt("8877665544332211", 16))
        dut.io.protocolError.expect(false.B)
      }

      withClue("hard recovery: ") {
        resetHarness()
        allocateAndLaunch()
        dut.io.queryValid.expect(true.B)
        dut.io.pathEmpty.expect(false.B)
        dut.io.flush.poke(true.B)
        dut.clock.step()
        dut.io.flush.poke(false.B)
        dut.io.queryValid.expect(false.B)
        dut.io.resultPending.expect(false.B)
        dut.io.pathEmpty.expect(true.B)
        dut.io.protocolError.expect(false.B)
      }

      withClue("precise recovery retained query: ") {
        resetHarness()
        allocateAndLaunch()
        dut.io.queryValid.expect(true.B)
        dut.io.preciseFlush.poke(true.B)
        dut.io.queryValid.expect(false.B)
        dut.io.pathEmpty.expect(false.B)
        dut.clock.step()
        dut.io.preciseFlush.poke(false.B)
        dut.io.queryValid.expect(true.B)
        dut.io.queryLoadId.valid.expect(true.B)
        dut.io.queryAttempt.generation.expect(7.U)
        dut.io.queryReady.poke(true.B)
        dut.clock.step()
        dut.io.queryValid.expect(false.B)
        dut.io.protocolError.expect(false.B)
      }

      withClue("precise recovery retained E4: ") {
        resetHarness(queryReady = true)
        allocateAndLaunch()
        dut.clock.step()
        dut.io.queryCaptured.expect(true.B)
        dut.io.responseValid.poke(true.B)
        dut.io.responseReady.expect(true.B)
        dut.clock.step()
        dut.io.responseValid.poke(false.B)
        dut.clock.step()
        dut.io.preciseFlush.poke(true.B)
        dut.io.pathEmpty.expect(false.B)
        dut.clock.step()
        dut.io.preciseFlush.poke(false.B)

        var accepted = false
        var completed = false
        for (_ <- 0 until 8) {
          accepted ||= dut.io.resultAccepted.peek().litToBoolean
          completed ||= dut.io.rowDataComplete.peek().litToBoolean
          dut.clock.step()
        }
        assert(accepted, "surviving E4 must be retained until LIQ can revalidate it")
        assert(completed, "post-recovery exact apply must complete the surviving row")
        dut.io.protocolError.expect(false.B)
      }

      withClue("unrepresentable native BID: ") {
        resetHarness(nativeBid = 13, queryReady = true)
        dut.io.allocValid.poke(true.B)
        dut.io.allocAccepted.expect(true.B)
        dut.clock.step()
        dut.io.allocValid.poke(false.B)
        dut.io.launchValid.poke(true.B)
        dut.io.launchAccepted.expect(false.B)
        dut.io.queryValid.expect(false.B)
        dut.io.protocolError.expect(true.B)
        dut.clock.step()
        dut.io.queryValid.expect(false.B)
      }
    }
  }
}
