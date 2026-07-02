package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadInflightLaunchSelectReference {
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
      status: Status = Wait,
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      loadLsId: Id = Id(),
      addr: BigInt = 0,
      size: Int = 4,
      returnSignExtend: Boolean = false,
      validMask: BigInt = 0,
      waitStore: Boolean = false,
      storeBypass: Boolean = false,
      l1Hit: Boolean = false,
      isTile: Boolean = false)

  final case class Result(
      waitMask: BigInt,
      waitStoreBlockedMask: BigInt,
      tileBlockedMask: BigInt,
      unblockedWaitMask: BigInt,
      requestCompleteMask: BigInt,
      dataHitMask: BigInt,
      launchCandidateMask: BigInt,
      launchMask: BigInt,
      launchValid: Boolean,
      launchIndex: Int,
      candidateCount: Int,
      selectedReturnSignExtend: Boolean,
      selectedRequestByteMask: BigInt)

  def select(rows: Seq[Row], enable: Boolean): Result = {
    val wait = rows.map(row => row.valid && row.status == Wait)
    val waitStoreBlocked = rows.zip(wait).map { case (row, isWait) => isWait && row.waitStore }
    val tileBlocked = rows.zip(wait).map { case (row, isWait) => isWait && !row.waitStore && row.isTile }
    val unblockedWait = rows.zip(wait).map { case (row, isWait) => isWait && !row.waitStore && !row.isTile }
    val requestMasks = rows.map(requestByteMask)
    val requestComplete = rows.zip(requestMasks).zip(unblockedWait).map { case ((row, mask), unblocked) =>
      unblocked && mask != 0 && (row.validMask & mask) == mask
    }
    val dataHit = rows.zip(unblockedWait).zip(requestComplete).map { case ((row, unblocked), complete) =>
      unblocked && (row.l1Hit || row.storeBypass || complete)
    }
    val candidates = dataHit.map(enable && _)
    val selectedIndex = rows.indices.find { idx =>
      candidates(idx) && !rows.indices.exists { other =>
        candidates(other) && olderOrTie(rows(other), rows(idx), other, idx)
      }
    }.getOrElse(0)
    val launchValid = candidates.contains(true)

    Result(
      waitMask = mask(wait),
      waitStoreBlockedMask = mask(waitStoreBlocked),
      tileBlockedMask = mask(tileBlocked),
      unblockedWaitMask = mask(unblockedWait),
      requestCompleteMask = mask(requestComplete),
      dataHitMask = mask(dataHit),
      launchCandidateMask = mask(candidates),
      launchMask = if (launchValid) BigInt(1) << selectedIndex else BigInt(0),
      launchValid = launchValid,
      launchIndex = selectedIndex,
      candidateCount = candidates.count(identity),
      selectedReturnSignExtend = launchValid && rows(selectedIndex).returnSignExtend,
      selectedRequestByteMask = if (launchValid) requestMasks(selectedIndex) else BigInt(0))
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

class LoadInflightLaunchSelectSpec extends AnyFunSuite {
  import LoadInflightLaunchSelectReference._
  import STQFlushPruneReference.Id

  private def row(bid: Int, lsId: Int, addr: BigInt = 0x1000, size: Int = 4, validMask: BigInt = 0): Row =
    Row(bid = Id(value = bid), gid = Id(value = 0), rid = Id(value = bid), loadLsId = Id(value = lsId), addr = addr, size = size, validMask = validMask)

  private def bytes(offset: Int, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0)) { case (acc, idx) => acc | (BigInt(1) << (offset + idx)) }

  test("selects the oldest data-hit WAIT row by BID then load LSID") {
    val result = select(
      Seq(
        row(bid = 2, lsId = 1, validMask = bytes(0, 4)),
        row(bid = 1, lsId = 3, validMask = bytes(0, 4)),
        row(bid = 1, lsId = 2, validMask = bytes(0, 4)).copy(returnSignExtend = true)),
      enable = true)

    assert(result.launchValid)
    assert(result.launchIndex == 2)
    assert(result.launchMask == BigInt(4))
    assert(result.launchCandidateMask == BigInt(7))
    assert(result.candidateCount == 3)
    assert(result.selectedReturnSignExtend)
  }

  test("fresh allocation without row-owned requested bytes is not launchable") {
    val result = select(
      Seq(
        row(bid = 0, lsId = 0, validMask = 0),
        row(bid = 0, lsId = 1, validMask = bytes(0, 2)),
        row(bid = 0, lsId = 2, validMask = bytes(0, 4))),
      enable = true)

    assert(result.waitMask == BigInt(7))
    assert(result.requestCompleteMask == BigInt(4))
    assert(result.dataHitMask == BigInt(4))
    assert(result.launchIndex == 2)
    assert(result.selectedRequestByteMask == bytes(0, 4))
  }

  test("store-bypass and refill hits can be candidates even with sparse valid masks") {
    val result = select(
      Seq(
        row(bid = 3, lsId = 0, validMask = 0).copy(storeBypass = true),
        row(bid = 2, lsId = 0, validMask = 0).copy(l1Hit = true),
        row(bid = 1, lsId = 0, validMask = 0)),
      enable = true)

    assert(result.dataHitMask == BigInt(3))
    assert(result.launchIndex == 1)
    assert(result.launchMask == BigInt(2))
  }

  test("wait-store and tile rows are exposed as blocked diagnostics") {
    val result = select(
      Seq(
        row(bid = 0, lsId = 0, validMask = bytes(0, 4)).copy(waitStore = true),
        row(bid = 0, lsId = 1, validMask = bytes(0, 4)).copy(isTile = true),
        row(bid = 0, lsId = 2, validMask = bytes(0, 4))),
      enable = true)

    assert(result.waitStoreBlockedMask == BigInt(1))
    assert(result.tileBlockedMask == BigInt(2))
    assert(result.unblockedWaitMask == BigInt(4))
    assert(result.launchMask == BigInt(4))
  }

  test("enable gates launch without hiding candidate diagnostics") {
    val result = select(Seq(row(bid = 0, lsId = 0, validMask = bytes(0, 4))), enable = false)

    assert(!result.launchValid)
    assert(result.dataHitMask == BigInt(1))
    assert(result.launchCandidateMask == BigInt(0))
    assert(result.launchMask == BigInt(0))
  }

  test("Chisel LoadInflightLaunchSelect elaborates launch masks and selected identity") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightLaunchSelect(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadInflightLaunchSelect"))
    assert(sv.contains("io_requestCompleteMask"))
    assert(sv.contains("io_dataHitMask"))
    assert(sv.contains("io_launchCandidateMask"))
    assert(sv.contains("io_selectedLoadLsId_value"))
    assert(sv.contains("io_selectedRequestByteMask"))
    assert(sv.contains("io_selectedReturnSignExtend"))
    assert(sv.contains("io_selectedSpecWakeup"))
    assert(sv.contains("io_selectedStackValid"))
  }
}
