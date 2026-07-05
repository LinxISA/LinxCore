package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnCompleteRepickSelectReference {
  import STQFlushPruneReference.{Id, less, lessEqual}

  sealed trait Status
  case object Idle extends Status
  case object Wait extends Status
  case object Repick extends Status
  case object L1DcMiss extends Status
  case object L2Wait extends Status
  case object Resolved extends Status

  final case class Row(
      valid: Boolean = true,
      status: Status = Repick,
      bid: Id = Id(),
      loadLsId: Id = Id(),
      addr: BigInt = 0,
      size: Int = 8,
      validMask: BigInt = 0,
      dataComplete: Boolean = true,
      sourcesReturned: Boolean = true,
      scbReturned: Boolean = true,
      stqReturned: Boolean = true,
      waitStore: Boolean = false,
      isTile: Boolean = false,
      returnSignExtend: Boolean = false)

  final case class Result(
      repickMask: BigInt,
      sourceReturnedMask: BigInt,
      dataCompleteMask: BigInt,
      requestCompleteMask: BigInt,
      returnCandidateMask: BigInt,
      returnMask: BigInt,
      returnValid: Boolean,
      returnIndex: Int,
      candidateCount: Int,
      selectedReturnSignExtend: Boolean,
      selectedRequestByteMask: BigInt)

  def select(rows: Seq[Row], enable: Boolean): Result = {
    val requestMasks = rows.map(requestByteMask)
    val repick = rows.map(row => row.valid && row.status == Repick)
    val sourceReturned = rows.zip(repick).map { case (row, isRepick) =>
      isRepick && row.sourcesReturned && row.scbReturned && row.stqReturned
    }
    val requestComplete = rows.zip(repick).zip(requestMasks).map { case ((row, isRepick), mask) =>
      isRepick && mask != 0 && (row.validMask & mask) == mask
    }
    val dataComplete = rows.zip(repick).zip(requestComplete).map { case ((row, isRepick), complete) =>
      isRepick && row.dataComplete && complete
    }
    val candidates = rows.indices.map { idx =>
      enable && repick(idx) && sourceReturned(idx) && dataComplete(idx) &&
        !rows(idx).waitStore && !rows(idx).isTile
    }
    val selectedIndex = rows.indices.find { idx =>
      candidates(idx) && !rows.indices.exists { other =>
        candidates(other) && olderOrTie(rows(other), rows(idx), other, idx)
      }
    }.getOrElse(0)
    val valid = candidates.contains(true)

    Result(
      repickMask = mask(repick),
      sourceReturnedMask = mask(sourceReturned),
      dataCompleteMask = mask(dataComplete),
      requestCompleteMask = mask(requestComplete),
      returnCandidateMask = mask(candidates),
      returnMask = if (valid) BigInt(1) << selectedIndex else BigInt(0),
      returnValid = valid,
      returnIndex = selectedIndex,
      candidateCount = candidates.count(identity),
      selectedReturnSignExtend = valid && rows(selectedIndex).returnSignExtend,
      selectedRequestByteMask = if (valid) requestMasks(selectedIndex) else BigInt(0))
  }

  private def olderOrTie(lhs: Row, rhs: Row, lhsIndex: Int, rhsIndex: Int): Boolean = {
    val sameOrder = lhs.bid == rhs.bid && lhs.loadLsId == rhs.loadLsId
    val strictlyOlder =
      less(lhs.bid, rhs.bid) || (lhs.bid == rhs.bid && lessEqual(lhs.loadLsId, rhs.loadLsId) && !sameOrder)
    strictlyOlder || (sameOrder && lhsIndex < rhsIndex)
  }

  private def requestByteMask(row: Row): BigInt = {
    val offset = (row.addr & 0x3f).toInt
    if (!row.valid || row.size == 0) {
      BigInt(0)
    } else {
      (0 until 64).foldLeft(BigInt(0)) { case (acc, byte) =>
        if (byte >= offset && byte < offset + row.size) acc | (BigInt(1) << byte) else acc
      }
    }
  }

  private def mask(bits: Seq[Boolean]): BigInt =
    bits.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (bit, idx)) => if (bit) acc | (BigInt(1) << idx) else acc }
}

class LoadReplayReturnCompleteRepickSelectSpec extends AnyFunSuite {
  import LoadReplayReturnCompleteRepickSelectReference._
  import STQFlushPruneReference.Id

  private def bytes(offset: Int, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0)) { case (acc, idx) => acc | (BigInt(1) << (offset + idx)) }

  private def row(bid: Int, lsId: Int, addr: BigInt = 0x1000, size: Int = 8): Row =
    Row(bid = Id(value = bid), loadLsId = Id(value = lsId), addr = addr, size = size, validMask = bytes((addr & 0x3f).toInt, size))

  test("selects oldest complete source-returned Repick row by BID then load LSID") {
    val result = select(
      Seq(
        row(bid = 2, lsId = 1),
        row(bid = 1, lsId = 3),
        row(bid = 1, lsId = 2).copy(returnSignExtend = true)),
      enable = true)

    assert(result.returnValid)
    assert(result.returnIndex == 2)
    assert(result.returnMask == BigInt(4))
    assert(result.returnCandidateMask == BigInt(7))
    assert(result.candidateCount == 3)
    assert(result.selectedReturnSignExtend)
  }

  test("requires Repick status, all source returns, data complete, and requested bytes") {
    val result = select(
      Seq(
        row(bid = 0, lsId = 0).copy(status = Wait),
        row(bid = 0, lsId = 1).copy(scbReturned = false),
        row(bid = 0, lsId = 2).copy(dataComplete = false),
        row(bid = 0, lsId = 3).copy(validMask = 0),
        row(bid = 0, lsId = 4)),
      enable = true)

    assert(result.repickMask == BigInt("11110", 2))
    assert(result.sourceReturnedMask == BigInt("11100", 2))
    assert(result.dataCompleteMask == BigInt("10010", 2))
    assert(result.requestCompleteMask == BigInt("10110", 2))
    assert(result.returnCandidateMask == BigInt("10000", 2))
    assert(result.returnIndex == 4)
  }

  test("wait-store, tile, and disabled rows stay out of the return candidate mask") {
    val blocked = select(
      Seq(
        row(bid = 0, lsId = 0).copy(waitStore = true),
        row(bid = 0, lsId = 1).copy(isTile = true)),
      enable = true)
    assert(!blocked.returnValid)
    assert(blocked.returnCandidateMask == BigInt(0))

    val disabled = select(Seq(row(bid = 0, lsId = 0)), enable = false)
    assert(!disabled.returnValid)
    assert(disabled.returnCandidateMask == BigInt(0))
    assert(disabled.repickMask == BigInt(1))
  }

  test("Chisel LoadReplayReturnCompleteRepickSelect elaborates complete-Repick return outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnCompleteRepickSelect(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadReplayReturnCompleteRepickSelect"))
    assert(sv.contains("io_returnCandidateMask"))
    assert(sv.contains("io_returnIndex"))
    assert(sv.contains("io_selectedLineData"))
    assert(sv.contains("io_selectedValidMask"))
  }
}
