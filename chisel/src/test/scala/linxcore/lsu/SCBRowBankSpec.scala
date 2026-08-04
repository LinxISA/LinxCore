package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBRowBankReference {
  sealed abstract class State
  case object Empty extends State
  case object Valid extends State
  case object Lookup extends State
  case object Miss extends State

  final case class Entry(valid: Boolean, state: State, lineAddr: BigInt = 0, mask: BigInt = 0, data: BigInt = 0) {
    def full: Boolean = mask == ((BigInt(1) << 64) - 1)
  }
  final case class Request(valid: Boolean, addr: BigInt, data: BigInt, size: Int, stqIndex: Int, last: Boolean)
  private final case class Selected(index: Int, fromRetry: Boolean, retryBlocked: Boolean)
  final case class Result(
      modelBatchReady: Boolean,
      acceptedMask: BigInt,
      stalledMask: BigInt,
      structuralBlockedMask: BigInt,
      commitFreeMask: BigInt,
      wakeupMasks: Seq[BigInt],
      lookupFire: Boolean,
      lookupMask: BigInt,
      freeMask: BigInt,
      missMask: BigInt,
      l2RequestValid: Boolean,
      l2Upgrade: Boolean,
      l2Write: Boolean,
      dcacheUpdateMask: BigInt,
      stateError: Boolean,
      responseRetryQueue: Seq[Int],
      nextEntries: Seq[Entry])

  final class Model(entries: Int, requestCount: Int) {
    private var rows = Vector.fill(entries)(Entry(valid = false, state = Empty))
    private var retryQueue = Vector.empty[Int]

    def seed(seedRows: Seq[Entry]): Unit = {
      require(seedRows.size <= entries)
      rows = seedRows.toVector ++ Vector.fill(entries - seedRows.size)(Entry(valid = false, state = Empty))
    }

    def snapshot: Seq[Entry] = rows
    def retrySnapshot: Seq[Int] = retryQueue

    def step(
        reqs: Seq[Request],
        evictEnable: Boolean = false,
        dcacheReady: Boolean = true,
        dcacheWriteHit: Boolean = true,
        dcacheTagHit: Boolean = true,
        l2RequestReady: Boolean = true,
        memRespValid: Boolean = false,
        memRespEntryIndex: Int = 0): Result = {
      require(reqs.size == requestCount)
      require(memRespEntryIndex >= 0 && memRespEntryIndex < entries)
      val preFree = rows.count(!_.valid)
      val modelBatchReady = preFree >= requestCount
      var staged = rows
      var acceptedMask = BigInt(0)
      var structuralBlockedMask = BigInt(0)
      var commitFreeMask = BigInt(0)
      val wakeupMasks = Array.fill(requestCount)(BigInt(0))

      reqs.zipWithIndex.foreach { case (req, lane) =>
        if (req.valid && modelBatchReady) {
          val line = lineAddr(req.addr)
          val hit = staged.indexWhere(row => row.valid && row.state == Valid && row.lineAddr == line)
          val free = staged.indexWhere(!_.valid)
          val target = if (hit >= 0) hit else free
          if (target >= 0) {
            val merged = merge(staged(target), req)
            staged = staged.updated(target, merged)
            acceptedMask |= BigInt(1) << lane
            wakeupMasks(lane) = merged.mask
            if (req.last) {
              commitFreeMask |= BigInt(1) << req.stqIndex
            }
          } else {
            structuralBlockedMask |= BigInt(1) << lane
          }
        }
      }

      val validReqMask = reqs.zipWithIndex.foldLeft(BigInt(0)) {
        case (mask, (req, lane)) => if (req.valid) mask | (BigInt(1) << lane) else mask
      }
      val stalledMask = validReqMask & ~acceptedMask

      val selected = selectLookup(staged, evictEnable)
      val selectedIndex = selected.index
      val selectedValid = selectedIndex >= 0
      val needsL2 = !dcacheWriteHit
      val lookupReady = dcacheReady && (!needsL2 || l2RequestReady)
      val lookupFire = selectedValid && lookupReady
      val lookupMask = if (lookupFire) BigInt(1) << selectedIndex else BigInt(0)
      val freeMask = if (lookupFire && dcacheWriteHit) lookupMask else BigInt(0)
      val missMask = if (lookupFire && !dcacheWriteHit) lookupMask else BigInt(0)
      val dcacheUpdateMask = if (freeMask != 0) staged(selectedIndex).mask else BigInt(0)
      val memRespMask = if (memRespValid) BigInt(1) << memRespEntryIndex else BigInt(0)

      var stateError = false
      val next = staged.zipWithIndex.map { case (entry, idx) =>
        val bit = BigInt(1) << idx
        val free = (freeMask & bit) != 0
        val miss = (missMask & bit) != 0
        val accepted = (lookupMask & bit) != 0
        val resp = (memRespMask & bit) != 0
        val canStart = entry.valid && entry.state == Valid
        val canLookupDone = entry.valid && (entry.state == Valid || entry.state == Lookup)
        val canResp = entry.valid && entry.state == Miss
        val acceptedOnly = accepted && !free && !miss && !resp
        if (selected.retryBlocked || (acceptedOnly && !canStart) || (miss && !canLookupDone) || (free && !canLookupDone) || (resp && !canResp)) {
          stateError = true
        }
        if (free && canLookupDone) Entry(valid = false, state = Empty)
        else if (miss && canLookupDone) entry.copy(state = Miss)
        else if (resp && canResp) entry.copy(state = Lookup)
        else if (accepted && canStart && !free && !miss && !resp) entry.copy(state = Lookup)
        else entry
      }
      rows = next
      if (lookupFire && selected.fromRetry) {
        retryQueue = retryQueue.drop(1)
      }
      if (memRespValid && !stateError) {
        retryQueue = retryQueue :+ memRespEntryIndex
      }

      Result(
        modelBatchReady = modelBatchReady,
        acceptedMask = acceptedMask,
        stalledMask = stalledMask,
        structuralBlockedMask = structuralBlockedMask,
        commitFreeMask = commitFreeMask,
        wakeupMasks = wakeupMasks.toSeq,
        lookupFire = lookupFire,
        lookupMask = lookupMask,
        freeMask = freeMask,
        missMask = missMask,
        l2RequestValid = lookupFire && !dcacheWriteHit,
        l2Upgrade = lookupFire && !dcacheWriteHit && dcacheTagHit,
        l2Write = lookupFire && !dcacheWriteHit && !dcacheTagHit,
        dcacheUpdateMask = dcacheUpdateMask,
        stateError = stateError,
        responseRetryQueue = retryQueue,
        nextEntries = next)
    }

    private def selectLookup(entries: Seq[Entry], evictEnable: Boolean): Selected = {
      retryQueue.headOption match {
        case Some(idx) if entries(idx).valid && entries(idx).state == Lookup =>
          Selected(index = idx, fromRetry = true, retryBlocked = false)
        case Some(idx) =>
          Selected(index = -1, fromRetry = false, retryBlocked = true)
        case None if !evictEnable =>
          Selected(index = -1, fromRetry = false, retryBlocked = false)
        case None =>
        val full = entries.indexWhere(row => row.valid && row.state == Valid && row.full)
          val selected = if (full >= 0) full else entries.indexWhere(row => row.valid && row.state == Valid && !row.full)
          Selected(index = selected, fromRetry = false, retryBlocked = false)
      }
    }

    private def merge(entry: Entry, req: Request): Entry = {
      val off = (req.addr & 0x3f).toInt
      val byteMask = (0 until req.size).foldLeft(BigInt(0)) { case (mask, idx) => mask | (BigInt(1) << (off + idx)) }
      entry.copy(valid = true, state = Valid, lineAddr = lineAddr(req.addr), mask = entry.mask | byteMask)
    }

    private def lineAddr(addr: BigInt): BigInt = addr & ~BigInt(0x3f)
  }
}

class SCBRowBankSpec extends AnyFunSuite with ChiselSim {
  import SCBRowBankReference._

  private def clearReq(req: STQCommitDrainRequest): Unit = {
    req.valid.poke(false.B)
    req.ownsStqRow.poke(false.B)
    req.stqIndex.poke(0.U)
    req.split.poke(false.B)
    req.segment.poke(0.U)
    req.last.poke(false.B)
    req.addr.poke(0.U)
    req.data.poke(0.U)
    req.size.poke(0.U)
    req.stid.poke(0.U)
    req.bid.valid.poke(false.B)
    req.bid.wrap.poke(false.B)
    req.bid.value.poke(0.U)
    req.gid.valid.poke(false.B)
    req.gid.wrap.poke(false.B)
    req.gid.value.poke(0.U)
    req.rid.valid.poke(false.B)
    req.rid.wrap.poke(false.B)
    req.rid.value.poke(0.U)
    req.lsId.poke(0.U)
  }

  private def pokeReq(
      req: STQCommitDrainRequest,
      addr: BigInt,
      stqIndex: Int,
      segment: Int,
      last: Boolean,
      bid: Int,
      gid: Int,
      rid: Int,
      lsId: BigInt): Unit = {
    clearReq(req)
    req.valid.poke(true.B)
    req.ownsStqRow.poke(true.B)
    req.stqIndex.poke(stqIndex.U)
    req.split.poke(true.B)
    req.segment.poke(segment.U)
    req.last.poke(last.B)
    req.addr.poke(addr.U)
    req.data.poke(BigInt("1122334455667788", 16).U)
    req.size.poke(4.U)
    req.stid.poke(7.U)
    req.bid.valid.poke(true.B)
    req.bid.wrap.poke(((bid & 0x8) != 0).B)
    req.bid.value.poke((bid & 0x7).U)
    req.gid.valid.poke(true.B)
    req.gid.wrap.poke(((gid & 0x8) != 0).B)
    req.gid.value.poke((gid & 0x7).U)
    req.rid.valid.poke(true.B)
    req.rid.wrap.poke(((rid & 0x8) != 0).B)
    req.rid.value.poke((rid & 0x7).U)
    req.lsId.poke(lsId.U)
  }

  private def idleRowBank(dut: SCBRowBank): Unit = {
    dut.io.reqs.foreach(clearReq)
    dut.io.evictEnable.poke(false.B)
    dut.io.dcacheReady.poke(true.B)
    dut.io.dcacheWriteHit.poke(true.B)
    dut.io.dcacheTagHit.poke(true.B)
    dut.io.l2RequestReady.poke(true.B)
    dut.io.rawRespValid.poke(false.B)
    dut.io.rawRespTxnId.poke(0.U)
    dut.io.rawRespTransactionValue.poke(0.U)
    dut.io.rawRespTransactionGeneration.poke(0.U)
    dut.io.rawRespError.poke(false.B)
    dut.io.rawRespWrite.poke(false.B)
    dut.io.rawRespUpgrade.poke(false.B)
  }

  test("ingress admission uses model batch gate and frees STQ rows only for accepted last fragments") {
    val bank = new Model(entries = 4, requestCount = 2)
    val result = bank.step(Seq(
      Request(valid = true, addr = 0x1004, data = 0x1122, size = 2, stqIndex = 1, last = true),
      Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)
    ))

    assert(result.modelBatchReady)
    assert(result.acceptedMask == BigInt(1))
    assert(result.stalledMask == BigInt(0))
    assert(result.commitFreeMask == (BigInt(1) << 1))
    assert(result.wakeupMasks.head == (BigInt(3) << 4))
    assert(bank.snapshot.head.state == Valid)
  }

  test("pre-cycle free count controls model batch readiness even if egress frees a row") {
    val bank = new Model(entries = 1, requestCount = 1)
    bank.seed(Seq(Entry(valid = true, state = Valid, lineAddr = 0x2000, mask = BigInt(1))))
    val result = bank.step(
      Seq(Request(valid = true, addr = 0x2040, data = 0x33, size = 1, stqIndex = 0, last = true)),
      evictEnable = true,
      dcacheWriteHit = true)

    assert(!result.modelBatchReady)
    assert(result.acceptedMask == BigInt(0))
    assert(result.stalledMask == BigInt(1))
    assert(result.lookupFire)
    assert(result.freeMask == BigInt(1))
    assert(bank.snapshot.head.state == Empty)
  }

  test("same-cycle ingress merge can be included in the writable-hit DCache update") {
    val bank = new Model(entries = 2, requestCount = 1)
    bank.seed(Seq(Entry(valid = true, state = Valid, lineAddr = 0x3000, mask = BigInt(1))))
    val result = bank.step(
      Seq(Request(valid = true, addr = 0x3001, data = 0x22, size = 1, stqIndex = 2, last = true)),
      evictEnable = true,
      dcacheWriteHit = true)

    assert(result.acceptedMask == BigInt(1))
    assert(result.commitFreeMask == (BigInt(1) << 2))
    assert(result.lookupFire)
    assert(result.dcacheUpdateMask == BigInt(3))
    assert(result.nextEntries.head.state == Empty)
  }

  test("non-writable lookup emits ownership request and leaves the row in miss") {
    val bank = new Model(entries = 2, requestCount = 1)
    bank.seed(Seq(Entry(valid = true, state = Valid, lineAddr = 0x4000, mask = BigInt(0xff))))
    val result = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      evictEnable = true,
      dcacheWriteHit = false,
      dcacheTagHit = true,
      l2RequestReady = true)

    assert(result.lookupFire)
    assert(result.missMask == BigInt(1))
    assert(result.l2RequestValid)
    assert(result.l2Upgrade)
    assert(!result.l2Write)
    assert(bank.snapshot.head.state == Miss)
  }

  test("memory response returns a miss row to lookup without reopening store coalescing") {
    val bank = new Model(entries = 2, requestCount = 1)
    bank.seed(Seq(Entry(valid = true, state = Miss, lineAddr = 0x5000), Entry(valid = false, state = Empty)))
    val result = bank.step(
      Seq(Request(valid = true, addr = 0x5004, data = 0x44, size = 1, stqIndex = 0, last = true)),
      memRespValid = true,
      memRespEntryIndex = 0)

    assert(result.acceptedMask == BigInt(1))
    assert(bank.snapshot.head.state == Lookup)
    assert(bank.retrySnapshot == Seq(0))
    assert(bank.snapshot(1).valid)
    assert(bank.snapshot(1).lineAddr == BigInt(0x5000))
    assert(bank.snapshot(1).state == Valid)
  }

  test("outstanding lookup rows are not merge targets for same-line stores") {
    val bank = new Model(entries = 2, requestCount = 1)
    bank.seed(Seq(Entry(valid = true, state = Lookup, lineAddr = 0x6000, mask = BigInt(1)), Entry(valid = false, state = Empty)))
    val result = bank.step(
      Seq(Request(valid = true, addr = 0x6008, data = 0x55, size = 1, stqIndex = 0, last = true)),
      dcacheReady = false)

    assert(result.acceptedMask == BigInt(1))
    assert(!result.lookupFire)
    assert(bank.snapshot.head.state == Lookup)
    assert(bank.snapshot(1).valid)
    assert(bank.snapshot(1).lineAddr == BigInt(0x6000))
    assert(bank.snapshot(1).mask == (BigInt(1) << 8))
  }

  test("response-returned lookup rows retry before ordinary valid-row eviction") {
    val bank = new Model(entries = 3, requestCount = 1)
    bank.seed(Seq(
      Entry(valid = true, state = Valid, lineAddr = 0x6800, mask = (BigInt(1) << 64) - 1),
      Entry(valid = true, state = Miss, lineAddr = 0x6900, mask = BigInt(0xff)),
      Entry(valid = false, state = Empty)))
    val returned = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      memRespValid = true,
      memRespEntryIndex = 1)
    val result = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      evictEnable = false,
      dcacheWriteHit = true)

    assert(returned.responseRetryQueue == Seq(1))
    assert(result.lookupFire)
    assert(result.lookupMask == BigInt(2))
    assert(result.freeMask == BigInt(2))
    assert(!result.stateError)
    assert(bank.snapshot.head.state == Valid)
    assert(bank.snapshot(1).state == Empty)
  }

  test("response retry queue preserves model resp_list order across row indices") {
    val bank = new Model(entries = 3, requestCount = 1)
    bank.seed(Seq(
      Entry(valid = true, state = Miss, lineAddr = 0x7100, mask = BigInt(0x1)),
      Entry(valid = true, state = Miss, lineAddr = 0x7200, mask = BigInt(0x2)),
      Entry(valid = true, state = Valid, lineAddr = 0x7300, mask = (BigInt(1) << 64) - 1)))

    val firstReturn = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      memRespValid = true,
      memRespEntryIndex = 1)
    val secondReturn = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      dcacheReady = false,
      memRespValid = true,
      memRespEntryIndex = 0)
    val retryFirst = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      evictEnable = true,
      dcacheWriteHit = true)
    val retrySecond = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      evictEnable = true,
      dcacheWriteHit = true)

    assert(firstReturn.responseRetryQueue == Seq(1))
    assert(secondReturn.responseRetryQueue == Seq(1, 0))
    assert(retryFirst.lookupMask == BigInt(2))
    assert(retryFirst.responseRetryQueue == Seq(0))
    assert(retrySecond.lookupMask == BigInt(1))
    assert(retrySecond.responseRetryQueue.isEmpty)
    assert(bank.snapshot(2).state == Valid)
  }

  test("illegal memory responses are surfaced through the composition owner") {
    val bank = new Model(entries = 1, requestCount = 1)
    bank.seed(Seq(Entry(valid = true, state = Valid, lineAddr = 0x7000)))
    val result = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      memRespValid = true,
      memRespEntryIndex = 0)

    assert(result.stateError)
    assert(bank.snapshot.head.state == Valid)
  }

  test("Chisel acceptedReqs are zero on stall and preserve split-store identity on accept") {
    simulate(new SCBRowBank(stqEntries = 8, scbEntries = 2, requestCount = 2, robEntries = 8, lsidWidth = 40)) { dut =>
      idleRowBank(dut)

      pokeReq(dut.io.reqs(0), addr = 0x1000, stqIndex = 3, segment = 0, last = false, bid = 0xb, gid = 0xc, rid = 0xd, lsId = BigInt("100000001", 16))
      pokeReq(dut.io.reqs(1), addr = 0x1040, stqIndex = 3, segment = 1, last = true, bid = 0xb, gid = 0xc, rid = 0xd, lsId = BigInt("100000001", 16))

      dut.io.modelBatchReady.expect(true.B)
      dut.io.acceptedMask.expect("b11".U)
      dut.io.acceptedReqs(0).valid.expect(true.B)
      dut.io.acceptedReqs(0).ownsStqRow.expect(true.B)
      dut.io.acceptedReqs(0).stqIndex.expect(3.U)
      dut.io.acceptedReqs(0).split.expect(true.B)
      dut.io.acceptedReqs(0).segment.expect(0.U)
      dut.io.acceptedReqs(0).last.expect(false.B)
      dut.io.acceptedReqs(0).bid.valid.expect(true.B)
      dut.io.acceptedReqs(0).bid.wrap.expect(true.B)
      dut.io.acceptedReqs(0).bid.value.expect(3.U)
      dut.io.acceptedReqs(0).gid.valid.expect(true.B)
      dut.io.acceptedReqs(0).gid.wrap.expect(true.B)
      dut.io.acceptedReqs(0).gid.value.expect(4.U)
      dut.io.acceptedReqs(0).rid.valid.expect(true.B)
      dut.io.acceptedReqs(0).rid.wrap.expect(true.B)
      dut.io.acceptedReqs(0).rid.value.expect(5.U)
      dut.io.acceptedReqs(0).lsId.expect(BigInt("100000001", 16).U)
      dut.io.acceptedReqs(1).valid.expect(true.B)
      dut.io.acceptedReqs(1).stqIndex.expect(3.U)
      dut.io.acceptedReqs(1).split.expect(true.B)
      dut.io.acceptedReqs(1).segment.expect(1.U)
      dut.io.acceptedReqs(1).last.expect(true.B)
      dut.io.acceptedReqs(1).bid.wrap.expect(true.B)
      dut.io.acceptedReqs(1).bid.value.expect(3.U)
      dut.io.acceptedReqs(1).gid.wrap.expect(true.B)
      dut.io.acceptedReqs(1).gid.value.expect(4.U)
      dut.io.acceptedReqs(1).rid.wrap.expect(true.B)
      dut.io.acceptedReqs(1).rid.value.expect(5.U)
      dut.io.acceptedReqs(1).lsId.expect(BigInt("100000001", 16).U)
      dut.clock.step()

      idleRowBank(dut)
      pokeReq(dut.io.reqs(0), addr = 0x2000, stqIndex = 4, segment = 0, last = true, bid = 1, gid = 2, rid = 3, lsId = 9)

      dut.io.modelBatchReady.expect(false.B)
      dut.io.acceptedMask.expect(0.U)
      dut.io.stalledMask.expect(1.U)
      for (lane <- 0 until 2) {
        dut.io.acceptedReqs(lane).valid.expect(false.B)
        dut.io.acceptedReqs(lane).ownsStqRow.expect(false.B)
        dut.io.acceptedReqs(lane).stqIndex.expect(0.U)
        dut.io.acceptedReqs(lane).split.expect(false.B)
        dut.io.acceptedReqs(lane).segment.expect(0.U)
        dut.io.acceptedReqs(lane).last.expect(false.B)
        dut.io.acceptedReqs(lane).bid.valid.expect(false.B)
        dut.io.acceptedReqs(lane).gid.valid.expect(false.B)
        dut.io.acceptedReqs(lane).rid.valid.expect(false.B)
        dut.io.acceptedReqs(lane).lsId.expect(0.U)
      }
    }
  }

  test("Chisel SCBRowBank elaborates with egress, lookup, and state-update children") {
    val sv = ChiselStage.emitSystemVerilog(new SCBRowBank(stqEntries = 8, scbEntries = 4, requestCount = 2))

    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("SCBEgressSelect"))
    assert(sv.contains("SCBResponseRetryQueue"))
    assert(sv.contains("SCBResponseRetrySelect"))
    assert(sv.contains("SCBLookupControl"))
    assert(sv.contains("SCBResponseBuffer"))
    assert(sv.contains("SCBResponseDecode"))
    assert(sv.contains("SCBStateUpdate"))
    assert(sv.contains("io_commitFreeMask"))
    assert(sv.contains("io_acceptedReqs_0_valid"))
    assert(sv.contains("io_acceptedReqs_0_gid_value"))
    assert(sv.contains("io_acceptedReqs_0_rid_value"))
    assert(sv.contains("io_responseRetryMask"))
    assert(sv.contains("io_responseRetryHeadEntryIndex"))
    assert(sv.contains("io_rawRespTxnId"))
    assert(sv.contains("io_rawRespReady"))
    assert(sv.contains("io_respBufferHeadTxnId"))
    assert(sv.contains("io_respDecodeError"))
    assert(sv.contains("io_stateError"))
  }

  test("SCB quiescence covers resident rows ownership response buffering and retry") {
    simulate(new SCBRowBank(
        stqEntries = 8, scbEntries = 2, requestCount = 1,
        responseBufferDepth = 2, robEntries = 8,
        memoryTransactionIdWidth = 8,
        memoryTransactionGenerationWidth = 4)) { dut =>
      idleRowBank(dut)
      dut.io.quiescent.expect(true.B)

      pokeReq(dut.io.reqs(0), addr = 0x8000, stqIndex = 1,
        segment = 0, last = true, bid = 1, gid = 1, rid = 1, lsId = 1)
      dut.clock.step()
      idleRowBank(dut)
      dut.io.quiescent.expect(false.B)

      dut.io.evictEnable.poke(true.B)
      dut.io.dcacheTagHit.poke(true.B)
      dut.io.dcacheWriteHit.poke(false.B)
      while (!dut.io.l2Request.valid.peek().litToBoolean) dut.clock.step()
      val shortId = dut.io.l2Request.txnTid.peek()
      val value = dut.io.l2Request.transactionValue.peek()
      val generation = dut.io.l2Request.transactionGeneration.peek()
      dut.clock.step()
      idleRowBank(dut)

      dut.io.dcacheReady.poke(false.B)
      dut.io.rawRespTxnId.poke(shortId)
      dut.io.rawRespTransactionValue.poke(value)
      dut.io.rawRespTransactionGeneration.poke(generation)
      dut.io.rawRespUpgrade.poke(true.B)
      dut.io.rawRespValid.poke(true.B)
      dut.clock.step()
      dut.io.rawRespValid.poke(false.B)
      dut.io.quiescent.expect(false.B)
      assert(dut.io.respBufferHeadValid.peek().litToBoolean ||
        dut.io.responseRetryHeadValid.peek().litToBoolean,
        "accepted ownership response must remain represented until retry")

      dut.io.dcacheReady.poke(true.B)
      dut.io.dcacheTagHit.poke(true.B)
      dut.io.dcacheWriteHit.poke(true.B)
      var drainCycles = 0
      while (!dut.io.quiescent.peek().litToBoolean && drainCycles < 16) {
        dut.clock.step()
        drainCycles += 1
      }
      assert(drainCycles < 16, "SCB row/response/retry state did not drain")
    }
  }

  test("physical STQ indices remain wider than ROB identity in a 16 by 8 configuration") {
    val io = new SCBRowBankIO(
      stqEntries = 16,
      scbEntries = 4,
      requestCount = 2,
      responseBufferDepth = 4,
      robEntries = 8,
      lsidWidth = 40)

    assert(io.commitFreeMask.getWidth == 16)
    assert(io.reqs.head.stqIndex.getWidth == 4)
    assert(io.reqs.head.bid.value.getWidth == 3)
    assert(io.acceptedReqs.head.stqIndex.getWidth == 4)
    assert(io.acceptedReqs.head.bid.value.getWidth == 3)
    assert(io.acceptedReqs.head.gid.value.getWidth == 3)
    assert(io.acceptedReqs.head.rid.value.getWidth == 3)
    assert(io.reqs.head.lsId.getWidth == 40)
    assert(io.acceptedReqs.head.lsId.getWidth == 40)

    val sv = ChiselStage.emitSystemVerilog(
      new SCBRowBank(
        stqEntries = 16, scbEntries = 4, requestCount = 2, robEntries = 8, lsidWidth = 40))
    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("io_reqs_0_bid_value"))
    assert(sv.contains("io_acceptedReqs_0_bid_value"))
  }
}
