package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams, OperandClass}
import linxcore.rob.{ROBIDReference, ROBIDValue}
import org.scalatest.funsuite.AnyFunSuite

object TULinkRenameReference {
  final case class Entry(
      valid: Boolean,
      retired: Boolean,
      bid: ROBIDValue,
      rid: ROBIDValue,
      gid: ROBIDValue,
      seq: ROBIDValue,
      physTag: Int)
  final case class BankState(
      allocSeq: ROBIDValue,
      deallocSeq: ROBIDValue,
      allocPhys: Int,
      usedEntries: Int,
      usedPhys: Int,
      mapQ: Vector[Entry])
  final case class ResolvedSource(hit: Boolean, underflow: Boolean, seq: ROBIDValue, physTag: Int)

  def initial(mapQDepth: Int): BankState =
    BankState(
      allocSeq = ROBIDValue(),
      deallocSeq = ROBIDValue(),
      allocPhys = 0,
      usedEntries = 0,
      usedPhys = 0,
      mapQ = Vector.fill(mapQDepth)(
        Entry(valid = false, retired = false, ROBIDValue(), ROBIDValue(), ROBIDValue(), ROBIDValue(), 0)))

  def checkStall(state: BankState, localRegs: Int): Boolean =
    state.usedEntries + 1 > state.mapQ.length || state.usedPhys + 1 > localRegs

  def allocate(state: BankState, bid: ROBIDValue, rid: ROBIDValue, gid: ROBIDValue, localRegs: Int): (BankState, Int, ROBIDValue) = {
    require(!checkStall(state, localRegs))
    val seq = state.allocSeq
    val phys = state.allocPhys
    val next = state.copy(
      allocSeq = ROBIDReference.inc(state.allocSeq, state.mapQ.length),
      allocPhys = (state.allocPhys + 1) % localRegs,
      usedEntries = state.usedEntries + 1,
      usedPhys = state.usedPhys + 1,
      mapQ = state.mapQ.updated(seq.value, Entry(valid = true, retired = false, bid, rid, gid, seq, phys))
    )
    (next, phys, seq)
  }

  def dispatch(state: BankState, offset: Int): ResolvedSource = {
    if (offset + 1 > state.mapQ.length || offset + 1 > state.usedEntries) {
      ResolvedSource(hit = false, underflow = true, ROBIDValue(valid = false), physTag = 0)
    } else {
      val seq = ROBIDReference.sub(state.allocSeq, offset + 1, state.mapQ.length)
      val entry = state.mapQ(seq.value)
      ResolvedSource(hit = entry.valid, underflow = !entry.valid, seq = seq, physTag = if (entry.valid) entry.physTag else 0)
    }
  }

  private def lessEqualBidRid(srcBid: ROBIDValue, srcRid: ROBIDValue, dstBid: ROBIDValue, dstRid: ROBIDValue): Boolean =
    ROBIDReference.less(srcBid, dstBid) || (srcBid == dstBid && ROBIDReference.lessEqual(srcRid, dstRid))

  def reportRetired(state: BankState, seq: ROBIDValue, dealloc: Boolean): BankState = {
    val entry = state.mapQ(seq.value)
    require(entry.valid)
    val marked = entry.copy(retired = true)
    if (!dealloc) {
      state.copy(mapQ = state.mapQ.updated(seq.value, marked))
    } else {
      require(seq == state.deallocSeq)
      state.copy(
        deallocSeq = ROBIDReference.inc(state.deallocSeq, state.mapQ.length),
        usedEntries = state.usedEntries - 1,
        usedPhys = state.usedPhys - 1,
        mapQ = state.mapQ.updated(seq.value, marked.copy(valid = false, retired = false))
      )
    }
  }

  def blockCommit(state: BankState, bid: ROBIDValue): (BankState, Set[Int]) = {
    var next = state
    var released = Set.empty[Int]
    var continue = true
    while (continue && next.deallocSeq != next.allocSeq) {
      val seq = next.deallocSeq
      val entry = next.mapQ(seq.value)
      if (entry.valid && entry.bid == bid) {
        require(entry.retired)
        released += entry.physTag
        next = next.copy(
          deallocSeq = ROBIDReference.inc(next.deallocSeq, next.mapQ.length),
          usedEntries = next.usedEntries - 1,
          usedPhys = next.usedPhys - 1,
          mapQ = next.mapQ.updated(seq.value, entry.copy(valid = false, retired = false))
        )
      } else {
        continue = false
      }
    }
    next -> released
  }

  def flush(
      state: BankState,
      flushBid: ROBIDValue,
      flushRid: ROBIDValue,
      flushSeq: ROBIDValue,
      baseOnBid: Boolean): (BankState, Set[Int]) = {
    var mapQ = state.mapQ
    var released = Set.empty[Int]
    for ((entry, idx) <- state.mapQ.zipWithIndex) {
      if (entry.valid) {
        val prune =
          if (baseOnBid) ROBIDReference.lessEqual(flushBid, entry.bid)
          else lessEqualBidRid(flushBid, flushSeq, entry.bid, entry.seq) &&
            lessEqualBidRid(flushBid, flushRid, entry.bid, entry.rid)
        if (prune) {
          released += entry.physTag
          mapQ = mapQ.updated(idx, entry.copy(valid = false, retired = false))
        }
      }
    }
    val remaining = state.usedEntries - released.size
    val allocSeq = ROBIDReference.add(state.deallocSeq, remaining, state.mapQ.length)
    val allocPhys = if (released.nonEmpty) state.mapQ(allocSeq.value).physTag else state.allocPhys
    state.copy(
      allocSeq = allocSeq,
      allocPhys = allocPhys,
      usedEntries = remaining,
      usedPhys = state.usedPhys - released.size,
      mapQ = mapQ
    ) -> released
  }
}

class TULinkRenameSpec extends AnyFunSuite {
  import TULinkRenameReference._

  test("reference captures tSeq before allocation and resolves sources behind alloc pointer") {
    val b1 = ROBIDValue(value = 1)
    val r0 = ROBIDValue(value = 0)
    val gid = ROBIDValue(value = 0)

    val (s1, p0, seq0) = allocate(initial(mapQDepth = 8), b1, r0, gid, localRegs = 4)
    assert(p0 == 0)
    assert(seq0 == ROBIDValue(value = 0))
    assert(s1.allocSeq == ROBIDValue(value = 1))

    val src0 = dispatch(s1, offset = 0)
    assert(src0.hit)
    assert(!src0.underflow)
    assert(src0.seq == seq0)
    assert(src0.physTag == 0)

    val (s2, p1, seq1) = allocate(s1, b1, ROBIDValue(value = 1), gid, localRegs = 4)
    assert(p1 == 1)
    assert(seq1 == ROBIDValue(value = 1))
    assert(dispatch(s2, offset = 0).physTag == 1)
    assert(dispatch(s2, offset = 1).physTag == 0)
  }

  test("reference keeps T and U banks independent") {
    val b1 = ROBIDValue(value = 1)
    val (t1, tPhys, tSeq) = allocate(initial(mapQDepth = 8), b1, ROBIDValue(value = 0), ROBIDValue(value = 0), localRegs = 4)
    val (u1, uPhys, uSeq) = allocate(initial(mapQDepth = 8), b1, ROBIDValue(value = 1), ROBIDValue(value = 0), localRegs = 4)

    assert(tPhys == 0)
    assert(uPhys == 0)
    assert(tSeq == ROBIDValue(value = 0))
    assert(uSeq == ROBIDValue(value = 0))
    assert(dispatch(t1, offset = 0).physTag == 0)
    assert(dispatch(u1, offset = 0).physTag == 0)
  }

  test("reference reports source underflow instead of silently wrapping an empty or shallow queue") {
    val empty = initial(mapQDepth = 8)
    assert(dispatch(empty, offset = 0).underflow)

    val (s1, _, _) = allocate(empty, ROBIDValue(value = 1), ROBIDValue(value = 0), ROBIDValue(value = 0), localRegs = 4)
    assert(dispatch(s1, offset = 0).hit)
    assert(dispatch(s1, offset = 1).underflow)
    assert(dispatch(s1, offset = 8).underflow)
  }

  test("reference stalls independently on mapQ and local physical count pressure") {
    val b1 = ROBIDValue(value = 1)
    val r0 = ROBIDValue(value = 0)
    val gid = ROBIDValue(value = 0)

    val (phys1, _, _) = allocate(initial(mapQDepth = 4), b1, r0, gid, localRegs = 2)
    val (phys2, _, _) = allocate(phys1, b1, ROBIDValue(value = 1), gid, localRegs = 2)
    assert(checkStall(phys2, localRegs = 2))

    val (map1, _, _) = allocate(initial(mapQDepth = 2), b1, r0, gid, localRegs = 8)
    val (map2, _, _) = allocate(map1, b1, ROBIDValue(value = 1), gid, localRegs = 8)
    assert(checkStall(map2, localRegs = 8))
  }

  test("reference marks retired rows before later block commit frees the dealloc head") {
    val bid = ROBIDValue(value = 1)
    val (s1, p0, seq0) = allocate(initial(mapQDepth = 8), bid, ROBIDValue(value = 0), ROBIDValue(), localRegs = 4)
    val (s2, p1, seq1) = allocate(s1, bid, ROBIDValue(value = 1), ROBIDValue(), localRegs = 4)
    val s3 = reportRetired(reportRetired(s2, seq0, dealloc = false), seq1, dealloc = false)
    val (s4, released) = blockCommit(s3, bid)

    assert(Set(p0, p1) == Set(0, 1))
    assert(released == Set(0, 1))
    assert(s4.usedEntries == 0)
    assert(s4.usedPhys == 0)
    assert(s4.deallocSeq == s4.allocSeq)
  }

  test("reference direct dealloc release frees only the current dealloc head") {
    val bid = ROBIDValue(value = 1)
    val (s1, _, seq0) = allocate(initial(mapQDepth = 8), bid, ROBIDValue(value = 0), ROBIDValue(), localRegs = 4)
    val s2 = reportRetired(s1, seq0, dealloc = true)

    assert(s2.usedEntries == 0)
    assert(s2.usedPhys == 0)
    assert(s2.deallocSeq == ROBIDValue(value = 1))
    assert(s2.mapQ(seq0.value).valid == false)
  }

  test("reference flush prunes younger rows and reuses the first pruned physical tag") {
    val bid1 = ROBIDValue(value = 1)
    val bid2 = ROBIDValue(value = 2)
    val (s1, _, _) = allocate(initial(mapQDepth = 8), bid1, ROBIDValue(value = 0), ROBIDValue(), localRegs = 4)
    val (s2, _, seq1) = allocate(s1, bid1, ROBIDValue(value = 1), ROBIDValue(), localRegs = 4)
    val (s3, _, _) = allocate(s2, bid2, ROBIDValue(value = 0), ROBIDValue(), localRegs = 4)
    val (s4, released) = flush(
      s3,
      flushBid = bid1,
      flushRid = ROBIDValue(value = 1),
      flushSeq = seq1,
      baseOnBid = false)

    assert(released == Set(1, 2))
    assert(s4.usedEntries == 1)
    assert(s4.allocSeq == ROBIDValue(value = 1))
    assert(s4.allocPhys == 1)
    assert(dispatch(s4, offset = 0).physTag == 0)
  }

  test("IO exposes separate T/U source, destination, sequence, and pressure surfaces") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkRenameIO(p, localRegsT = 8, localRegsU = 8, mapQDepth = 8)

    assert(io.src.length == 3)
    assert(io.src(0).physTag.getWidth == p.physRegWidth)
    assert(io.src(0).seq.value.getWidth == 3)
    assert(io.dst.seq.value.getWidth == 3)
    assert(io.tSeq.value.getWidth == 3)
    assert(io.uSeq.value.getWidth == 3)
    assert(io.tDeallocSeq.value.getWidth == 3)
    assert(io.uDeallocSeq.value.getWidth == 3)
    assert(io.tMapQValidMask.getWidth == 8)
    assert(io.uMapQValidMask.getWidth == 8)
    assert(io.tReleasedMask.getWidth == 8)
    assert(io.uReleasedMask.getWidth == 8)
    assert(io.tFlushedMask.getWidth == 8)
    assert(io.uFlushedMask.getWidth == 8)
    assert(io.sourceUnderflowMask.getWidth == 3)
  }

  test("TULinkRename elaborates as a standalone owner without scalar GPR rename") {
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkRename(p = InterfaceParams(robEntries = 8), localRegsT = 8, localRegsU = 8, mapQDepth = 8)
    )

    assert(sv.contains("module TULinkRename"))
    assert(sv.contains("io_tSeq_value"))
    assert(sv.contains("io_uSeq_value"))
    assert(sv.contains("io_tDeallocSeq_value"))
    assert(sv.contains("io_sourceUnderflowMask"))
    assert(sv.contains("io_tReleasedMask"))
    assert(sv.contains("io_tFlushedMask"))
    assert(!sv.contains("GPRRenameCheckpoint"))
  }

  test("enum values used by the T/U owner stay stable") {
    assert(OperandClass.T.asUInt.litValue == 2)
    assert(OperandClass.U.asUInt.litValue == 3)
    assert(DestinationKind.T.asUInt.litValue == 2)
    assert(DestinationKind.U.asUInt.litValue == 3)
  }
}
