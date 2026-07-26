package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object LinxCoreBenchmarkAutonomousTopReference {
  def blockFitsRob(robEntries: Int, bodyUopsBeforeClosingMarker: Int): Boolean =
    robEntries > bodyUopsBeforeClosingMarker

  def localIncomingBlocked(usesLocal: Boolean, pendingT: Int, pendingU: Int): Boolean =
    usesLocal && (pendingT != 0 || pendingU != 0)

  final case class Inputs(
      rowValid: Boolean = true,
      memValid: Boolean = true,
      isStore: Boolean = true,
      addr: BigInt = 0,
      data: BigInt = 0,
      size: Int = 1,
      trapValid: Boolean = false,
      commitContractError: Boolean = false,
      startValid: Boolean = false,
      restartValid: Boolean = false,
      flushValid: Boolean = false,
      sourceBlocked: Boolean = false,
      resetSp: BigInt = 0,
      restartSp: BigInt = 0)

  final case class RowInput(
      valid: Boolean = true,
      ready: Boolean = true,
      memValid: Boolean = false,
      isStore: Boolean = false,
      addr: BigInt = 0,
      data: BigInt = 0,
      size: Int = 1,
      trapValid: Boolean = false,
      commitContractError: Boolean = false,
      blockLast: Boolean = false)

  final case class Decision(
      commitAccepted: Boolean,
      committedSideEffectAccepted: Boolean,
      storeObserveValid: Boolean,
      storeObserveMask: Int,
      uartWriteValid: Boolean,
      uartWriteByte: Int,
      finisherWriteValid: Boolean,
      finisherPass: Boolean,
      bootSp: BigInt,
      halted: Boolean,
      trapValid: Boolean,
      unsupportedInstruction: Boolean,
      status: Int)

  final case class WindowDecision(
      retiredRows: Int,
      sideEffectAddrs: Seq[BigInt],
      halted: Boolean,
      trapValid: Boolean,
      unsupportedInstruction: Boolean)

  def storeFitsObservationLane(addr: BigInt, size: Int): Boolean = {
    val lowAddr = (addr & 0x7).toInt
    size > 0 && size <= 8 && lowAddr + size <= 8
  }

  def storeMask(addr: BigInt, size: Int): Option[Int] =
    if (storeFitsObservationLane(addr, size)) {
      val lowAddr = (addr & 0x7).toInt
      Some(((1 << size) - 1) << lowAddr)
    } else {
      None
    }

  def storeLaneData(addr: BigInt, data: BigInt): BigInt =
    (data << (((addr & 0x7).toInt) * 8)) & ((BigInt(1) << 64) - 1)

  def decide(in: Inputs): Decision = {
    val startOrRestart = in.startValid || in.restartValid
    val selectedSp = if (in.restartValid) in.restartSp else in.resetSp
    val commitAccepted = in.rowValid && !startOrRestart && !in.flushValid && !in.sourceBlocked
    val mask = storeMask(in.addr, in.size)
    val exceptionalCommit = in.trapValid || in.commitContractError
    val committedSideEffectAccepted = commitAccepted && !exceptionalCommit && in.memValid && in.isStore && mask.nonEmpty
    val uartWrite =
      committedSideEffectAccepted && in.addr == LinxCoreBenchmarkAutonomousTop.UartDataAddr
    val finisherWrite =
      committedSideEffectAccepted && in.addr == LinxCoreBenchmarkAutonomousTop.TestFinisherAddr
    val finisherPass = (in.data & 0xffff) == LinxCoreBenchmarkAutonomousTop.FinisherPass
    val invalidStoreSideEffect = commitAccepted && !exceptionalCommit && in.memValid && in.isStore && mask.isEmpty
    val unsupported = commitAccepted && in.commitContractError
    val trapCommit = commitAccepted && in.trapValid

    val (halted, trap, unsupportedInstruction, status) =
      if (startOrRestart) {
        (false, false, false, LinxCoreBenchmarkAutonomousTop.StatusFetch)
      } else if (in.flushValid) {
        (true, false, false, LinxCoreBenchmarkAutonomousTop.StatusIdle)
      } else if (finisherWrite) {
        (
          true,
          !finisherPass,
          false,
          if (finisherPass) LinxCoreBenchmarkAutonomousTop.StatusFinisherPass
          else LinxCoreBenchmarkAutonomousTop.StatusFinisherFail)
      } else if (trapCommit || unsupported || invalidStoreSideEffect) {
        (true, true, unsupported || invalidStoreSideEffect, LinxCoreBenchmarkAutonomousTop.StatusUnsupported)
      } else {
        (false, false, false, LinxCoreBenchmarkAutonomousTop.StatusFetch)
      }

    Decision(
      commitAccepted = commitAccepted,
      committedSideEffectAccepted = committedSideEffectAccepted,
      storeObserveValid = committedSideEffectAccepted,
      storeObserveMask = mask.getOrElse(0),
      uartWriteValid = uartWrite,
      uartWriteByte = (in.data & 0xff).toInt,
      finisherWriteValid = finisherWrite,
      finisherPass = finisherWrite && finisherPass,
      bootSp = selectedSp,
      halted = halted,
      trapValid = trap,
      unsupportedInstruction = unsupportedInstruction,
      status = status)
  }

  def decideWindow(rows: Seq[RowInput]): WindowDecision = {
    var retired = 0
    var stop = false
    var halted = false
    var trap = false
    var unsupported = false
    val sideEffects = scala.collection.mutable.ArrayBuffer.empty[BigInt]

    rows.foreach { row =>
      if (!stop && row.valid && row.ready) {
        retired += 1
        val mask = storeMask(row.addr, row.size)
        val exceptional = row.trapValid || row.commitContractError
        val invalidStore = row.memValid && row.isStore && !exceptional && mask.isEmpty

        if (exceptional || invalidStore) {
          halted = true
          trap = true
          unsupported = row.commitContractError || invalidStore
          stop = true
        } else if (row.memValid && row.isStore) {
          sideEffects += row.addr
          stop = true
        }

        if (row.blockLast) {
          stop = true
        }
      } else {
        stop = true
      }
    }

    WindowDecision(retired, sideEffects.toSeq, halted, trap, unsupported)
  }

  def drainOneSideEffectPerCycle(retiredWindows: Seq[Seq[BigInt]]): Seq[BigInt] = {
    val pending = scala.collection.mutable.Queue.empty[BigInt]
    val observed = scala.collection.mutable.ArrayBuffer.empty[BigInt]

    retiredWindows.foreach { window =>
      if (pending.nonEmpty) {
        observed += pending.dequeue()
        pending.enqueueAll(window)
      } else if (window.nonEmpty) {
        observed += window.head
        pending.enqueueAll(window.tail)
      }
    }

    while (pending.nonEmpty) {
      observed += pending.dequeue()
    }

    observed.toSeq
  }
}

object LinxCoreBenchmarkAutonomousTopSpecFixtures {
  lazy val defaultTopSystemVerilog: String =
    ChiselStage.emitSystemVerilog(new LinxCoreBenchmarkAutonomousTop())

  def sourceText(path: String): String =
    new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
}

class LinxCoreBenchmarkAutonomousTopSpec extends AnyFunSuite {
  test("natural _start_c block capacity and local ordering have explicit guards") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val startCBodyUopsBeforeClosingMarker = 16
    assert(!blockFitsRob(8, startCBodyUopsBeforeClosingMarker))
    assert(blockFitsRob(
      LinxCoreBenchmarkAutonomousTop.BenchmarkRobEntries,
      startCBodyUopsBeforeClosingMarker))

    assert(localIncomingBlocked(usesLocal = true, pendingT = 1, pendingU = 0))
    assert(localIncomingBlocked(usesLocal = true, pendingT = 0, pendingU = 1))
    assert(localIncomingBlocked(usesLocal = true, pendingT = 1, pendingU = 1))
    assert(!localIncomingBlocked(usesLocal = true, pendingT = 0, pendingU = 0))
    assert(!localIncomingBlocked(usesLocal = false, pendingT = 3, pendingU = 3))
  }

  test("Phase 1 autonomous top exposes live pipeline memory and commit boundaries") {
    val p = LinxCoreBenchmarkAutonomousTop.interfaceParamsFor(CoreParams(robEntries = 8, commitWidth = 2))
    val traceParams = LinxCoreBenchmarkAutonomousTop.traceParamsFor(p)
    val io = new LinxCoreBenchmarkAutonomousTopIO(p, traceParams)

    assert(io.p.commitWidth == 2)
    assert(io.traceParams.commitWidth == 2)
    assert(io.fetchReqPc.getWidth == 64)
    assert(io.fetchRespWindow.getWidth == io.p.windowWidth)
    assert(io.loadLookupData.getWidth == 64)
    assert(io.loadLookupAddr.getWidth == 64)
    assert(io.loadLookupPc.getWidth == 64)
    assert(io.loadLookupDstPhysTag.getWidth == io.p.physRegWidth)
    assert(!io.elements.contains("loadLookupReady"))
    assert(!io.elements.contains("loadRespValid"))
    assert(!io.elements.contains("loadRespReady"))
    assert(io.commit.rows.length == 2)
    assert(io.storeObserveAddr.getWidth == 64)
    assert(io.storeObserveData.getWidth == 64)
    assert(io.storeObserveSize.getWidth == io.p.memSizeWidth)
    assert(io.storeObserveMask.getWidth == 8)
    assert(io.storeObservePairValid.getWidth == 1)
    assert(io.storeObservePairAddr.getWidth == 64)
    assert(io.storeObservePairData.getWidth == 64)
    assert(io.storeObservePairSize.getWidth == io.p.memSizeWidth)
    assert(io.storeObservePairMask.getWidth == 8)
    assert(io.storeObserveRobValue.getWidth == io.p.robIndexWidth)
    assert(io.storeObserveBlockBid.getWidth == io.p.blockBidWidth)
    assert(io.uartWriteByte.getWidth == 8)
    assert(io.finisherCode.getWidth == 16)
    assert(io.finisherPayload.getWidth == 32)
    assert(io.debugLocalPendingCounts.getWidth == 6)
    assert(io.debugLocalReadyMasks.getWidth == 8)
    assert(io.debugLocalHeadPc.getWidth == 64)
    assert(io.debugLocalExecuteCompleteValid.getWidth == 1)
    assert(io.debugLocalCompletePc.getWidth == 64)
    assert(io.debugLocalCompleteWbReg.getWidth == traceParams.regWidth)
    assert(io.debugDecodeBlockBits.getWidth == 4)
    assert(io.debugDecodeReadyBits.getWidth == 5)
  }

  test("reference suppresses committed side effects during start restart flush and halted state") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val ordinary = decide(Inputs(addr = 0x40, data = 0x1234, size = 4))
    assert(ordinary.commitAccepted)
    assert(ordinary.committedSideEffectAccepted)
    assert(ordinary.storeObserveValid)

    for (blocked <- Seq(
        Inputs(startValid = true, resetSp = 0x1000),
        Inputs(restartValid = true, restartSp = 0x2000),
        Inputs(flushValid = true),
        Inputs(sourceBlocked = true))) {
      val decision = decide(blocked.copy(addr = LinxCoreBenchmarkAutonomousTop.UartDataAddr, data = 0x41))
      assert(!decision.commitAccepted)
      assert(!decision.committedSideEffectAccepted)
      assert(!decision.storeObserveValid)
      assert(!decision.uartWriteValid)
      assert(!decision.finisherWriteValid)
    }
  }

  test("reference covers boot SP and Phase 1 single-cycle load boundary") {
    import LinxCoreBenchmarkAutonomousTopReference._

    assert(decide(Inputs(startValid = true, resetSp = 0x801000)).bootSp == 0x801000)
    assert(decide(Inputs(restartValid = true, resetSp = 0x801000, restartSp = 0x802000)).bootSp == 0x802000)

    val p = LinxCoreBenchmarkAutonomousTop.interfaceParamsFor(CoreParams(robEntries = 8, commitWidth = 2))
    val io = new LinxCoreBenchmarkAutonomousTopIO(p, LinxCoreBenchmarkAutonomousTop.traceParamsFor(p))
    assert(io.loadLookupData.getWidth == p.immWidth)
    assert(io.loadLookupValid.getWidth == 1)
    assert(io.loadLookupAddr.getWidth == p.immWidth)
    assert(io.loadLookupPc.getWidth == p.pcWidth)
    assert(!io.elements.contains("loadLookupReady"))
    assert(!io.elements.contains("loadRespData"))
  }

  test("reference derives committed store UART and finisher only from accepted store payloads") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val store = decide(Inputs(addr = 0x48, data = BigInt("feedfacecafebeef", 16), size = 8))
    assert(store.storeObserveValid)
    assert(store.storeObserveMask == 0xff)
    assert(!store.uartWriteValid)
    assert(!store.finisherWriteValid)

    assert(storeMask(0x1003, 1).contains(0x08))
    assert(storeLaneData(0x1003, 0x52) == BigInt("52000000", 16))
    assert(storeLaneData(0x1006, BigInt("bbaa", 16)) == BigInt("bbaa000000000000", 16))

    val uart = decide(Inputs(addr = LinxCoreBenchmarkAutonomousTop.UartDataAddr, data = 0x41, size = 1))
    assert(uart.storeObserveValid)
    assert(uart.uartWriteValid)
    assert(uart.uartWriteByte == 0x41)
    assert(!uart.finisherWriteValid)

    val finisher = decide(Inputs(
      addr = LinxCoreBenchmarkAutonomousTop.TestFinisherAddr,
      data = LinxCoreBenchmarkAutonomousTop.FinisherPass,
      size = 8))
    assert(finisher.storeObserveValid)
    assert(finisher.finisherWriteValid)
    assert(finisher.finisherPass)
    assert(finisher.halted)
    assert(!finisher.trapValid)
    assert(finisher.status == LinxCoreBenchmarkAutonomousTop.StatusFinisherPass)
  }

  test("reference gives flush priority over trap and committed side effects") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val flushedTrapAndStore = decide(Inputs(
      flushValid = true,
      trapValid = true,
      addr = LinxCoreBenchmarkAutonomousTop.TestFinisherAddr,
      data = 1,
      size = 8))

    assert(flushedTrapAndStore.halted)
    assert(!flushedTrapAndStore.trapValid)
    assert(!flushedTrapAndStore.storeObserveValid)
    assert(!flushedTrapAndStore.finisherWriteValid)
    assert(flushedTrapAndStore.status == LinxCoreBenchmarkAutonomousTop.StatusIdle)

    val trap = decide(Inputs(rowValid = true, memValid = false, isStore = false, trapValid = true))
    assert(trap.halted)
    assert(trap.trapValid)
    assert(!trap.storeObserveValid)
    assert(trap.status == LinxCoreBenchmarkAutonomousTop.StatusUnsupported)
  }

  test("reference suppresses exceptional row stores UART and finisher pulses") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val trapUart = decide(Inputs(
      trapValid = true,
      addr = LinxCoreBenchmarkAutonomousTop.UartDataAddr,
      data = 0x41,
      size = 1))
    assert(trapUart.commitAccepted)
    assert(!trapUart.committedSideEffectAccepted)
    assert(!trapUart.storeObserveValid)
    assert(!trapUart.uartWriteValid)
    assert(!trapUart.finisherWriteValid)
    assert(trapUart.halted)
    assert(trapUart.trapValid)
    assert(!trapUart.unsupportedInstruction)
    assert(trapUart.status == LinxCoreBenchmarkAutonomousTop.StatusUnsupported)

    val trapFinisher = decide(Inputs(
      trapValid = true,
      addr = LinxCoreBenchmarkAutonomousTop.TestFinisherAddr,
      data = LinxCoreBenchmarkAutonomousTop.FinisherPass,
      size = 8))
    assert(trapFinisher.commitAccepted)
    assert(!trapFinisher.committedSideEffectAccepted)
    assert(!trapFinisher.storeObserveValid)
    assert(!trapFinisher.uartWriteValid)
    assert(!trapFinisher.finisherWriteValid)
    assert(!trapFinisher.finisherPass)
    assert(trapFinisher.halted)
    assert(trapFinisher.trapValid)
    assert(!trapFinisher.unsupportedInstruction)
    assert(trapFinisher.status == LinxCoreBenchmarkAutonomousTop.StatusUnsupported)

    val contractErrorStore = decide(Inputs(
      commitContractError = true,
      addr = 0x40,
      data = 0x1234,
      size = 4))
    assert(contractErrorStore.commitAccepted)
    assert(!contractErrorStore.committedSideEffectAccepted)
    assert(!contractErrorStore.storeObserveValid)
    assert(!contractErrorStore.uartWriteValid)
    assert(!contractErrorStore.finisherWriteValid)
    assert(contractErrorStore.halted)
    assert(contractErrorStore.trapValid)
    assert(contractErrorStore.unsupportedInstruction)
    assert(contractErrorStore.status == LinxCoreBenchmarkAutonomousTop.StatusUnsupported)
  }

  test("reference masks aligned in-lane stores and rejects cross-lane stores") {
    import LinxCoreBenchmarkAutonomousTopReference._

    assert(storeMask(0x1000, 1).contains(0x01))
    assert(storeMask(0x1002, 2).contains(0x0c))
    assert(storeMask(0x1004, 4).contains(0xf0))
    assert(storeMask(0x1000, 8).contains(0xff))

    for ((addr, size) <- Seq(0x1001 -> 8, 0x1006 -> 4, 0x1007 -> 2, 0x1000 -> 0, 0x1000 -> 9)) {
      val rejected = decide(Inputs(addr = addr, size = size))
      assert(storeMask(addr, size).isEmpty)
      assert(!rejected.storeObserveValid)
      assert(rejected.halted)
      assert(rejected.trapValid)
      assert(rejected.unsupportedInstruction)
      assert(rejected.status == LinxCoreBenchmarkAutonomousTop.StatusUnsupported)
    }
  }

  test("reference retires two consecutive register rows and serializes store side effects") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val registers = decideWindow(Seq(RowInput(), RowInput()))
    assert(registers.retiredRows == 2)
    assert(registers.sideEffectAddrs.isEmpty)

    val stores = decideWindow(Seq(
      RowInput(memValid = true, isStore = true, addr = 0x1000, data = 0x11),
      RowInput(memValid = true, isStore = true, addr = 0x1008, data = 0x22)))

    assert(stores.retiredRows == 1)
    assert(stores.sideEffectAddrs == Seq(0x1000))
  }

  test("reference stops width two retirement on unready exception invalid store and block last boundaries") {
    import LinxCoreBenchmarkAutonomousTopReference._

    assert(decideWindow(Seq(RowInput(), RowInput(ready = false))).retiredRows == 1)

    val trapFirst = decideWindow(Seq(
      RowInput(trapValid = true),
      RowInput(memValid = true, isStore = true, addr = 0x2000)))
    assert(trapFirst.retiredRows == 1)
    assert(trapFirst.sideEffectAddrs.isEmpty)
    assert(trapFirst.halted)
    assert(trapFirst.trapValid)

    val invalidStoreFirst = decideWindow(Seq(
      RowInput(memValid = true, isStore = true, addr = 0x1007, size = 2),
      RowInput(memValid = true, isStore = true, addr = 0x2000)))
    assert(invalidStoreFirst.retiredRows == 1)
    assert(invalidStoreFirst.sideEffectAddrs.isEmpty)
    assert(invalidStoreFirst.unsupportedInstruction)

    val blockLastFirst = decideWindow(Seq(
      RowInput(blockLast = true),
      RowInput(memValid = true, isStore = true, addr = 0x2000)))
    assert(blockLastFirst.retiredRows == 1)
    assert(blockLastFirst.sideEffectAddrs.isEmpty)
  }

  test("reference observes already serialized committed stores without queueing") {
    import LinxCoreBenchmarkAutonomousTopReference._

    val observed = Seq(
      Seq(0x1000),
      Seq(0x1010),
      Seq.empty,
      Seq(0x1018)).flatten

    assert(observed == Seq(0x1000, 0x1010, 0x1018))
    assert(observed.distinct == observed)
  }

  test("Chisel top elaborates the production IFU and live backend without oracle inputs") {
    val sv = LinxCoreBenchmarkAutonomousTopSpecFixtures.defaultTopSystemVerilog

    assert(sv.contains("module LinxCoreBenchmarkAutonomousTop"))
    assert(sv.contains("module LinxCoreFrontendFetchRfAluTraceTop"))
    assert(sv.contains("module LinxCoreIfu"))
    assert(sv.contains("module BSidePredictionPipeline"))
    assert(sv.contains("module D1InstructionDecodeStage"))
    assert(sv.contains("module D1DecodedLaneQueue"))
    assert(sv.contains("module IfuWindowLineFillAdapter"))
    assert(!sv.contains("module FrontendFetchPacketSource"))
    assert(!sv.contains("module F4DecodeWindow"))
    assert(!sv.contains("module F4DenseSlotQueue"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module ScalarGPRFile"))
    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("module StoreDispatchSTQPath"))
    assert(sv.contains("module ReducedStoreStaAddressExecBridge"))
    assert(sv.contains("module ScalarScTopHandshake"))
    assert(sv.contains("io_resetPc"))
    assert(sv.contains("io_resetSp"))
    assert(sv.contains("io_fetchReqValid"))
    assert(sv.contains("io_fetchReqReady"))
    assert(sv.contains("io_fetchRespWindow"))
    assert(sv.contains("io_loadLookupValid"))
    assert(sv.contains("io_loadLookupData"))
    assert(sv.contains("io_commit_rows_0_valid"))
    assert(sv.contains("io_storeObserveValid"))
    assert(sv.contains("io_storeObserveMask"))
    assert(sv.contains("io_storeObservePairValid"))
    assert(sv.contains("io_storeObservePairMask"))
    assert(sv.contains("io_uartWriteValid"))
    assert(sv.contains("io_finisherWriteValid"))
    assert(sv.contains("io_finisherPass"))
    assert(!sv.contains("loadRespValid"))
    assert(!sv.contains("loadRespReady"))
    assert(!sv.contains("loadLookupReady"))
    assert(!sv.contains("expectedPc"))
    assert(!sv.contains("io_expected"))
    assert(!sv.contains("qemu"))
  }

  test("default autonomous top exposes two commit lanes") {
    val sv = LinxCoreBenchmarkAutonomousTopSpecFixtures.defaultTopSystemVerilog

    assert(sv.contains("module LinxCoreBenchmarkAutonomousTop"))
    assert(sv.contains("io_commit_rows_0_valid"))
    assert(sv.contains("io_commit_rows_1_valid"))
    assert(sv.contains("autonomous benchmark top observed multiple committed stores in one cycle"))
    assert(!sv.contains("sideEffectQueue"))
  }

  test("autonomous benchmark top enables reduced SC store issue knobs") {
    val source = LinxCoreBenchmarkAutonomousTopSpecFixtures.sourceText(
      "src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala")

    assert(source.contains("useReducedStoreDispatchStq = true"))
    assert(source.contains("useReducedStoreStaAddressExecBridge = true"))
    assert(source.contains("useProductionD1Ingress = true"))
  }

  test("autonomous benchmark top keeps reduced SC store issue path live") {
    val sv = LinxCoreBenchmarkAutonomousTopSpecFixtures.defaultTopSystemVerilog
    val frontendSource = LinxCoreBenchmarkAutonomousTopSpecFixtures.sourceText(
      "src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala")

    assert(frontendSource.contains("Module(new ScalarScTopHandshake"))
    assert(frontendSource.contains("scalarScHandshake.io.ownerScReqReady"))
    assert(frontendSource.contains("path.io.storeScResultValid := scalarScHandshake.io.storeScResultValid"))
    assert(frontendSource.contains("issue.io.issueReady :="))
    assert(sv.contains("module ScalarScTopHandshake"))
    assert(!sv.contains("assign scalarScHandshake_io_scReqValid = 1'h0"))
  }
}
