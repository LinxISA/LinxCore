package linxcore.top

import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

object LinxCoreFentryFretNaturalObservationReference {
  val FentryPc: BigInt = BigInt("11370", 16)
  val FentryRaw: BigInt = BigInt("29350041", 16)
  val FretPc: BigInt = BigInt("11dba", 16)
  val FretRaw: BigInt = BigInt("29353041", 16)
  val OuterCallPc: BigInt = BigInt("11eb4", 16)
  val OuterCallFirstRaw: BigInt = BigInt("fd2f4001", 16)
  val OuterCallSetretRaw: BigInt = BigInt("5516", 16)
  val OuterCallBytes: Seq[Int] = Seq(0x01, 0x40, 0x2f, 0xfd, 0x16, 0x55)
  val OuterReturnPc: BigInt = BigInt("11ee0", 16)
  val NewSp: BigInt = BigInt(231248)
  val OldSp: BigInt = BigInt(231408)
  val Uimm: Int = 160
  val RaSlot: BigInt = BigInt(231400)
  val LastSlot: BigInt = BigInt(231328)
  val ArchitecturalA0: Int = 2
  val ArchitecturalX3: Int = 23

  def decodeDstBegin(raw: BigInt): Int = ((raw >> 15) & 0x1f).toInt

  def decodeDstEnd(raw: BigInt): Int = ((raw >> 20) & 0x1f).toInt

  def decodeImmediate(raw: BigInt): Int =
    ((((raw >> 7) & 0x1f) << 10) | (((raw >> 25) & 0x7f) << 3)).toInt

  def decodeOuterCallTargetPc(pc: BigInt, raw32: BigInt): BigInt = {
    require((raw32 & BigInt("7fff", 16)) == BigInt("4001", 16))
    val imm17 = ((raw32 >> 15) & BigInt("1ffff", 16)).toInt
    val signed = if ((imm17 & 0x10000) != 0) imm17 - 0x20000 else imm17
    pc + signed * 2
  }

  def decodeOuterCallReturnPc(pc: BigInt, setretRaw: BigInt): BigInt = {
    require((setretRaw & BigInt("f83f", 16)) == BigInt("5016", 16))
    pc + 4 + (((setretRaw >> 6) & 0x1f).toInt * 2)
  }

  def templateRegisters(begin: Int, end: Int): Seq[Int] = {
    require(begin >= ArchitecturalA0 && begin <= ArchitecturalX3)
    require(end >= ArchitecturalA0 && end <= ArchitecturalX3)
    Iterator
      .iterate(begin)(reg => if (reg == ArchitecturalX3) ArchitecturalA0 else reg + 1)
      .take(ArchitecturalX3 - ArchitecturalA0 + 1)
      .takeWhile(_ != end)
      .toSeq :+ end
  }

  def saveSlots(newSp: BigInt, uimm: Int, registerCount: Int): Seq[BigInt] =
    (0 until registerCount).map(index => newSp + uimm - 8 - index * 8)
}

class LinxCoreFentryFretNaturalObservationSpec extends AnyFunSuite {
  test("public autonomous top exposes every verifier-owned observation") {
    val core = CoreParams(
      robEntries = LinxCoreBenchmarkAutonomousTop.BenchmarkRobEntries,
      commitWidth = 1
    )
    val p = LinxCoreBenchmarkAutonomousTop.interfaceParamsFor(core)
    val traceParams = LinxCoreBenchmarkAutonomousTop.traceParamsFor(p)
    val io = new LinxCoreBenchmarkAutonomousTopIO(p, traceParams)

    assert(io.commit.rows.length == 1)
    assert(io.commit.rows.head.identity.bid.getWidth == 32)
    assert(io.commit.rows.head.identity.gid.getWidth == 32)
    assert(io.commit.rows.head.identity.rid.getWidth == 32)
    assert(io.commit.rows.head.rob.value.getWidth == p.robIndexWidth)
    assert(io.commit.rows.head.blockBid.getWidth == p.blockBidWidth)
    assert(io.commit.rows.head.wb.reg.getWidth == traceParams.regWidth)
    assert(io.storeObserveValid.getWidth == 1)
    assert(io.storeObserveBid.getWidth == 32)
    assert(io.storeObserveGid.getWidth == 32)
    assert(io.storeObserveRid.getWidth == 32)
    assert(io.storeObserveRobValue.getWidth == p.robIndexWidth)
    assert(io.storeObserveBlockBid.getWidth == p.blockBidWidth)
    assert(io.loadLookupValid.getWidth == 1)
    assert(io.loadLookupAddr.getWidth == 64)
    assert(io.loadLookupPc.getWidth == 64)
    assert(io.loadLookupData.getWidth == 64)
    assert(io.debugExecuteAcceptedIdentityValid.getWidth == 1)
    assert(io.debugExecuteAcceptedBidValue.getWidth == p.robIndexWidth)
    assert(io.debugExecuteAcceptedRidValue.getWidth == p.robIndexWidth)
    assert(io.debugExecuteAcceptedStid.getWidth == p.threadIdWidth)
    assert(io.debugLocalExecuteCompleteValid.getWidth == 1)
    assert(io.debugExecuteCompleteRobValue.getWidth == p.robIndexWidth)
    assert(io.debugMarkerRedirectFire.getWidth == 1)
    assert(io.debugMarkerRedirectPc.getWidth == 64)
    assert(io.sourceRestartValid.getWidth == 1)
    assert(io.sourceRestartPc.getWidth == 64)
  }

  test("independent decode and CTGen register ring establish same-frame shape") {
    import LinxCoreFentryFretNaturalObservationReference._

    val raw32 = OuterCallBytes.take(4).zipWithIndex
      .map { case (byte, index) => BigInt(byte) << (8 * index) }.sum
    val setret = OuterCallBytes.drop(4).zipWithIndex
      .map { case (byte, index) => BigInt(byte) << (8 * index) }.sum

    assert(raw32 == OuterCallFirstRaw)
    assert(setret == OuterCallSetretRaw)
    assert(decodeOuterCallTargetPc(OuterCallPc, raw32) == FentryPc)
    assert(decodeOuterCallReturnPc(OuterCallPc, setret) == OuterReturnPc)
    assert(decodeDstBegin(FentryRaw) == 10)
    assert(decodeDstEnd(FentryRaw) == 19)
    assert(decodeImmediate(FentryRaw) == Uimm)
    assert(decodeDstBegin(FretRaw) == 10)
    assert(decodeDstEnd(FretRaw) == 19)
    assert(decodeImmediate(FretRaw) == Uimm)

    val normal = templateRegisters(10, 19)
    assert(normal == 10.to(19))
    val slots = saveSlots(NewSp, Uimm, normal.length)
    assert(OldSp - NewSp == Uimm)
    assert(slots.head == RaSlot)
    assert(slots.last == LastSlot)

    assert(templateRegisters(22, 3) == Seq(22, 23, 2, 3))
    assertThrows[IllegalArgumentException](templateRegisters(1, 3))
    assertThrows[IllegalArgumentException](templateRegisters(22, 24))
  }
}
