package linxcore.lsu

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
      nextEntries: Seq[Entry])

  final class Model(entries: Int, requestCount: Int) {
    private var rows = Vector.fill(entries)(Entry(valid = false, state = Empty))

    def seed(seedRows: Seq[Entry]): Unit = {
      require(seedRows.size <= entries)
      rows = seedRows.toVector ++ Vector.fill(entries - seedRows.size)(Entry(valid = false, state = Empty))
    }

    def snapshot: Seq[Entry] = rows

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

      val selectedIndex = selectLookup(staged, evictEnable)
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
        if ((acceptedOnly && !canStart) || (miss && !canLookupDone) || (free && !canLookupDone) || (resp && !canResp)) {
          stateError = true
        }
        if (free && canLookupDone) Entry(valid = false, state = Empty)
        else if (miss && canLookupDone) entry.copy(state = Miss)
        else if (resp && canResp) entry.copy(state = Lookup)
        else if (accepted && canStart && !free && !miss && !resp) entry.copy(state = Lookup)
        else entry
      }
      rows = next

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
        nextEntries = next)
    }

    private def selectLookup(entries: Seq[Entry], evictEnable: Boolean): Int = {
      val retry = entries.indexWhere(row => row.valid && row.state == Lookup)
      if (retry >= 0) retry
      else if (!evictEnable) -1
      else {
        val full = entries.indexWhere(row => row.valid && row.state == Valid && row.full)
        if (full >= 0) full else entries.indexWhere(row => row.valid && row.state == Valid && !row.full)
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

class SCBRowBankSpec extends AnyFunSuite {
  import SCBRowBankReference._

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
      Entry(valid = true, state = Lookup, lineAddr = 0x6900, mask = BigInt(0xff)),
      Entry(valid = false, state = Empty)))
    val result = bank.step(
      Seq(Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)),
      evictEnable = false,
      dcacheWriteHit = true)

    assert(result.lookupFire)
    assert(result.lookupMask == BigInt(2))
    assert(result.freeMask == BigInt(2))
    assert(!result.stateError)
    assert(bank.snapshot.head.state == Valid)
    assert(bank.snapshot(1).state == Empty)
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

  test("Chisel SCBRowBank elaborates with egress, lookup, and state-update children") {
    val sv = ChiselStage.emitSystemVerilog(new SCBRowBank(stqEntries = 8, scbEntries = 4, requestCount = 2))

    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("SCBEgressSelect"))
    assert(sv.contains("SCBResponseRetrySelect"))
    assert(sv.contains("SCBLookupControl"))
    assert(sv.contains("SCBResponseBuffer"))
    assert(sv.contains("SCBResponseDecode"))
    assert(sv.contains("SCBStateUpdate"))
    assert(sv.contains("io_commitFreeMask"))
    assert(sv.contains("io_responseRetryMask"))
    assert(sv.contains("io_rawRespTxnId"))
    assert(sv.contains("io_rawRespReady"))
    assert(sv.contains("io_respBufferHeadTxnId"))
    assert(sv.contains("io_respDecodeError"))
    assert(sv.contains("io_stateError"))
  }
}
