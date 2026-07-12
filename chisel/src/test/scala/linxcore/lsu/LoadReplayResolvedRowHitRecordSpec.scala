package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayResolvedRowHitRecordReference {
  import STQFlushPruneReference.Id

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
      loadId: Id = Id(value = 0),
      bid: Id = Id(value = 1),
      gid: Id = Id(value = 2),
      rid: Id = Id(value = 3),
      loadLsId: Id = Id(value = 4),
      pc: BigInt = 0x2000,
      addr: BigInt = 0x1048,
      size: Int = 8,
      loadByteMask: BigInt = BigInt("ff", 16) << 8,
      validMask: BigInt = BigInt("ff", 16) << 8,
      lineData: BigInt = 0x11223344,
      forwardMask: BigInt = 0x3,
      dataComplete: Boolean = true,
      sourcesReturned: Boolean = true,
      scbReturned: Boolean = true,
      stqReturned: Boolean = true,
      waitStore: Boolean = false,
      isTile: Boolean = false)

  final case class ResolvedImage(
      lineData: BigInt,
      validMask: BigInt,
      dataComplete: Boolean,
      sourcesReturned: Boolean,
      scbReturned: Boolean,
      stqReturned: Boolean)

  final case class Record(
      loadId: Id,
      bid: Id,
      gid: Id,
      rid: Id,
      loadLsId: Id,
      pc: BigInt,
      addr: BigInt,
      lineAddr: BigInt,
      size: Int,
      byteMask: BigInt,
      data: BigInt,
      forwardedMask: BigInt)

  final case class Result(
      valid: Boolean,
      record: Option[Record],
      blockedByInvalidRow: Boolean,
      blockedByNotRepick: Boolean,
      blockedByIncompleteReturn: Boolean,
      blockedByWaitStore: Boolean,
      blockedByTile: Boolean)

  private def requestByteMask(row: Row, lineBytes: Int): BigInt = {
    if (!row.valid || row.size == 0) {
      BigInt(0)
    } else {
      val offset = (row.addr & (lineBytes - 1)).toInt
      val end = offset + row.size
      (0 until lineBytes).foldLeft(BigInt(0)) { case (mask, byte) =>
        if (byte >= offset && byte < end) mask | (BigInt(1) << byte) else mask
      }
    }
  }

  def apply(
      row: Row,
      enable: Boolean,
      lineBytes: Int = 64,
      resolvedImage: Option[ResolvedImage] = None): Result = {
    val candidateDataComplete = resolvedImage.map(_.dataComplete).getOrElse(row.dataComplete)
    val candidateSourcesReturned = resolvedImage.map(_.sourcesReturned).getOrElse(row.sourcesReturned)
    val candidateScbReturned = resolvedImage.map(_.scbReturned).getOrElse(row.scbReturned)
    val candidateStqReturned = resolvedImage.map(_.stqReturned).getOrElse(row.stqReturned)
    val candidateValidMask = resolvedImage.map(_.validMask).getOrElse(row.validMask)
    val candidateByteMask =
      if (row.loadByteMask != 0 || resolvedImage.isEmpty) row.loadByteMask else requestByteMask(row, lineBytes)
    val completeReturn =
      candidateDataComplete &&
        candidateSourcesReturned &&
        candidateScbReturned &&
        candidateStqReturned &&
        candidateByteMask != 0 &&
        ((candidateValidMask & candidateByteMask) == candidateByteMask)
    val recordValid =
      enable &&
        row.valid &&
        row.status == Repick &&
        completeReturn &&
        !row.waitStore &&
        !row.isTile
    val record =
      if (recordValid) {
        Some(Record(
          loadId = row.loadId,
          bid = row.bid,
          gid = row.gid,
          rid = row.rid,
          loadLsId = row.loadLsId,
          pc = row.pc,
          addr = row.addr,
          lineAddr = row.addr & ~(BigInt(lineBytes) - 1),
          size = row.size,
          byteMask = candidateByteMask,
          data = resolvedImage.map(_.lineData).getOrElse(row.lineData),
          forwardedMask = row.forwardMask))
      } else {
        None
      }

    Result(
      valid = recordValid,
      record = record,
      blockedByInvalidRow = enable && !row.valid,
      blockedByNotRepick = enable && row.valid && row.status != Repick,
      blockedByIncompleteReturn = enable && row.valid && row.status == Repick && !completeReturn,
      blockedByWaitStore = enable && row.valid && row.status == Repick && completeReturn && row.waitStore,
      blockedByTile = enable && row.valid && row.status == Repick && completeReturn && !row.waitStore && row.isTile)
  }
}

class LoadReplayResolvedRowHitRecordSpec extends AnyFunSuite {
  import LoadReplayResolvedRowHitRecordReference._

  test("publishes complete scalar Repick rows as LoadHitRecord payloads") {
    val row = Row()
    val result = LoadReplayResolvedRowHitRecordReference(row, enable = true)

    assert(result.valid)
    assert(result.record.contains(Record(
      loadId = row.loadId,
      bid = row.bid,
      gid = row.gid,
      rid = row.rid,
      loadLsId = row.loadLsId,
      pc = row.pc,
      addr = row.addr,
      lineAddr = 0x1040,
      size = row.size,
      byteMask = row.loadByteMask,
      data = row.lineData,
      forwardedMask = row.forwardMask)))
  }

  test("blocks rows that are not model-equivalent resolved replay returns") {
    assert(LoadReplayResolvedRowHitRecordReference(Row(valid = false), enable = true).blockedByInvalidRow)
    assert(LoadReplayResolvedRowHitRecordReference(Row(status = Wait), enable = true).blockedByNotRepick)
    assert(LoadReplayResolvedRowHitRecordReference(Row(stqReturned = false), enable = true).blockedByIncompleteReturn)
    assert(LoadReplayResolvedRowHitRecordReference(Row(loadByteMask = 0), enable = true).blockedByIncompleteReturn)
    assert(LoadReplayResolvedRowHitRecordReference(Row(waitStore = true), enable = true).blockedByWaitStore)
    assert(LoadReplayResolvedRowHitRecordReference(Row(isTile = true), enable = true).blockedByTile)
    assert(!LoadReplayResolvedRowHitRecordReference(Row(), enable = false).valid)
  }

  test("publishes row-mutation resolved image when current row has not stored the returned line yet") {
    val row = Row(
      loadByteMask = 0,
      lineData = 0,
      dataComplete = false,
      sourcesReturned = false,
      stqReturned = false)
    val image = ResolvedImage(
      lineData = BigInt("deadbeef", 16),
      validMask = BigInt("ff", 16) << 8,
      dataComplete = true,
      sourcesReturned = true,
      scbReturned = true,
      stqReturned = true)
    val result = LoadReplayResolvedRowHitRecordReference(row, enable = true, resolvedImage = Some(image))

    assert(result.valid)
    assert(result.record.exists(_.byteMask == (BigInt("ff", 16) << 8)))
    assert(result.record.exists(_.data == image.lineData))
  }

  test("Chisel LoadReplayResolvedRowHitRecord elaborates replay ResolveQ payload outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayResolvedRowHitRecord(
        liqEntries = 4, idEntries = 8, storeEntries = 4, lsidWidth = 40))

    assert(sv.contains("module LoadReplayResolvedRowHitRecord"))
    assert(sv.contains("io_recordValid"))
    assert(sv.contains("io_record_lineAddr"))
    assert(sv.contains("io_useResolvedImage"))
    assert(sv.contains("io_blockedByTile"))
  }
}
