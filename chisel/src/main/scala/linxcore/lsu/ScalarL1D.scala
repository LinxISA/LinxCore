package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Mux1H, PopCount, PriorityEncoder, log2Ceil}

class ScalarL1DRefill(val addrWidth: Int, val lineBytes: Int) extends Bundle {
  val valid = Bool()
  val lineAddr = UInt(addrWidth.W)
  val data = UInt((lineBytes * 8).W)
  val writable = Bool()
}

class ScalarL1DEviction(val addrWidth: Int, val lineBytes: Int) extends Bundle {
  val valid = Bool()
  val lineAddr = UInt(addrWidth.W)
  val data = UInt((lineBytes * 8).W)
  val dirty = Bool()
}

class ScalarL1DLookupResult(val addrWidth: Int, val lineBytes: Int, val ways: Int)
    extends Bundle {
  private val wayWidth = log2Ceil(ways)
  val tagHit = Bool()
  val readHit = Bool()
  val writeHit = Bool()
  val way = UInt(wayWidth.W)
  val data = UInt((lineBytes * 8).W)
}

class ScalarL1DIO(
    val sets: Int,
    val ways: Int,
    val scbEntries: Int,
    val addrWidth: Int,
    val lineBytes: Int)
    extends Bundle {
  private val residentWidth = log2Ceil(sets * ways + 1)

  val loadLookupValid = Input(Bool())
  val loadLookupLineAddr = Input(UInt(addrWidth.W))
  val loadLookup = Output(new ScalarL1DLookupResult(addrWidth, lineBytes, ways))

  val storeLookupValid = Input(Bool())
  val storeLookupLineAddr = Input(UInt(addrWidth.W))
  val storeLookup = Output(new ScalarL1DLookupResult(addrWidth, lineBytes, ways))
  val grantWriteValid = Input(Bool())
  val grantWriteLineAddr = Input(UInt(addrWidth.W))
  val storeUpdate = Input(new SCBDCacheUpdate(scbEntries, addrWidth, lineBytes))

  val refill = Input(new ScalarL1DRefill(addrWidth, lineBytes))
  val refillReady = Output(Bool())
  val refillAccepted = Output(Bool())
  val refillDuplicate = Output(Bool())
  val refillData = Output(UInt((lineBytes * 8).W))
  val eviction = Output(new ScalarL1DEviction(addrWidth, lineBytes))
  val evictionReady = Input(Bool())

  val arrayReady = Output(Bool())
  val residentCount = Output(UInt(residentWidth.W))
  val dirtyCount = Output(UInt(residentWidth.W))
  val protocolError = Output(Bool())
}

class ScalarL1D(
    val sets: Int = 64,
    val ways: Int = 4,
    val scbEntries: Int = 16,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(sets > 1 && (sets & (sets - 1)) == 0,
    "ScalarL1D sets must be a power of two greater than one")
  require(ways > 1 && (ways & (ways - 1)) == 0,
    "ScalarL1D ways must be a power of two greater than one")
  require(addrWidth > log2Ceil(lineBytes) + log2Ceil(sets),
    "ScalarL1D address width must cover line offset, set index, and tag")
  require(lineBytes > 1 && (lineBytes & (lineBytes - 1)) == 0,
    "ScalarL1D lineBytes must be a power of two greater than one")

  private val lineOffsetWidth = log2Ceil(lineBytes)
  private val setWidth = log2Ceil(sets)
  private val wayWidth = log2Ceil(ways)
  private val ageWidth = log2Ceil(ways)
  private val residentWidth = log2Ceil(sets * ways + 1)

  val io = IO(new ScalarL1DIO(sets, ways, scbEntries, addrWidth, lineBytes))

  val valid = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  val writable = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  val dirty = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  val tags = Reg(Vec(sets, Vec(ways, UInt(addrWidth.W))))
  val data = Reg(Vec(sets, Vec(ways, UInt((lineBytes * 8).W))))
  val age = RegInit(VecInit((0 until sets).map(_ =>
    VecInit((0 until ways).map(_.U(ageWidth.W))))))

  private def aligned(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  private def setIndex(addr: UInt): UInt =
    addr(lineOffsetWidth + setWidth - 1, lineOffsetWidth)

  private def lookup(lineAddr: UInt): ScalarL1DLookupResult = {
    val result = Wire(new ScalarL1DLookupResult(addrWidth, lineBytes, ways))
    val set = setIndex(lineAddr)
    val hits = VecInit((0 until ways).map(way =>
      valid(set)(way) && tags(set)(way) === aligned(lineAddr)))
    val hit = hits.asUInt.orR
    val selected = PriorityEncoder(hits)
    result.tagHit := hit
    result.readHit := hit
    result.writeHit := hit && Mux1H(hits, writable(set))
    result.way := selected
    result.data := Mux(hit, Mux1H(hits, data(set)), 0.U)
    result
  }

  val loadResult = lookup(io.loadLookupLineAddr)
  val storeResult = lookup(io.storeLookupLineAddr)
  io.loadLookup := loadResult
  io.storeLookup := storeResult

  val refillSet = setIndex(io.refill.lineAddr)
  val refillHits = VecInit((0 until ways).map(way =>
    valid(refillSet)(way) && tags(refillSet)(way) === aligned(io.refill.lineAddr)))
  val refillDuplicate = refillHits.asUInt.orR
  val refillHitWay = PriorityEncoder(refillHits)
  val invalidWays = VecInit((0 until ways).map(way => !valid(refillSet)(way)))
  val hasInvalid = invalidWays.asUInt.orR
  val invalidWay = PriorityEncoder(invalidWays)
  val oldestWays = VecInit((0 until ways).map(way => age(refillSet)(way) === (ways - 1).U))
  val oldestWay = PriorityEncoder(oldestWays)
  val victimWay = Mux(hasInvalid, invalidWay, oldestWay)
  val replacementNeedsEviction = !refillDuplicate && !hasInvalid && valid(refillSet)(victimWay)
  val refillReady = refillDuplicate || !replacementNeedsEviction || io.evictionReady
  val refillAccepted = io.refill.valid && refillReady

  io.eviction.valid := io.refill.valid && replacementNeedsEviction
  io.eviction.lineAddr := tags(refillSet)(victimWay)
  io.eviction.data := data(refillSet)(victimWay)
  io.eviction.dirty := dirty(refillSet)(victimWay)
  io.refillReady := refillReady
  io.refillAccepted := refillAccepted
  io.refillDuplicate := io.refill.valid && refillDuplicate
  io.refillData := Mux(refillDuplicate, data(refillSet)(refillHitWay), io.refill.data)
  io.arrayReady := !io.refill.valid

  val storeUpdateSet = setIndex(io.storeUpdate.lineAddr)
  val storeUpdateHits = VecInit((0 until ways).map(way =>
    valid(storeUpdateSet)(way) && tags(storeUpdateSet)(way) === aligned(io.storeUpdate.lineAddr)))
  val storeUpdateWay = PriorityEncoder(storeUpdateHits)
  val storeUpdateLegal = io.storeUpdate.valid && storeUpdateHits.asUInt.orR &&
    writable(storeUpdateSet)(storeUpdateWay) && io.storeUpdate.byteMask.orR && !io.refill.valid
  val grantWriteSet = setIndex(io.grantWriteLineAddr)
  val grantWriteHits = VecInit((0 until ways).map(way =>
    valid(grantWriteSet)(way) && tags(grantWriteSet)(way) === aligned(io.grantWriteLineAddr)))
  val grantWriteWay = PriorityEncoder(grantWriteHits)
  val grantWriteLegal = io.grantWriteValid && grantWriteHits.asUInt.orR && !io.refill.valid

  val touchValid = WireDefault(false.B)
  val touchNewLine = WireDefault(false.B)
  val touchSet = WireDefault(0.U(setWidth.W))
  val touchWay = WireDefault(0.U(wayWidth.W))
  when(refillAccepted) {
    touchValid := true.B
    touchNewLine := !refillDuplicate
    touchSet := refillSet
    touchWay := Mux(refillDuplicate, refillHitWay, victimWay)
  }.elsewhen(storeUpdateLegal) {
    touchValid := true.B
    touchSet := storeUpdateSet
    touchWay := storeUpdateWay
  }.elsewhen(grantWriteLegal) {
    touchValid := true.B
    touchSet := grantWriteSet
    touchWay := grantWriteWay
  }.elsewhen(io.storeLookupValid && storeResult.tagHit) {
    touchValid := true.B
    touchSet := setIndex(io.storeLookupLineAddr)
    touchWay := storeResult.way
  }.elsewhen(io.loadLookupValid && loadResult.tagHit) {
    touchValid := true.B
    touchSet := setIndex(io.loadLookupLineAddr)
    touchWay := loadResult.way
  }

  when(refillAccepted) {
    when(refillDuplicate) {
      writable(refillSet)(refillHitWay) := writable(refillSet)(refillHitWay) || io.refill.writable
    }.otherwise {
      valid(refillSet)(victimWay) := true.B
      writable(refillSet)(victimWay) := io.refill.writable
      dirty(refillSet)(victimWay) := false.B
      tags(refillSet)(victimWay) := aligned(io.refill.lineAddr)
      data(refillSet)(victimWay) := io.refill.data
    }
  }

  when(storeUpdateLegal) {
    val bytes = Wire(Vec(lineBytes, UInt(8.W)))
    for (byte <- 0 until lineBytes) {
      bytes(byte) := Mux(
        io.storeUpdate.byteMask(byte),
        io.storeUpdate.data(8 * byte + 7, 8 * byte),
        data(storeUpdateSet)(storeUpdateWay)(8 * byte + 7, 8 * byte))
    }
    data(storeUpdateSet)(storeUpdateWay) := bytes.asUInt
    dirty(storeUpdateSet)(storeUpdateWay) :=
      dirty(storeUpdateSet)(storeUpdateWay) || io.storeUpdate.byteMask.orR
  }

  when(grantWriteLegal) {
    writable(grantWriteSet)(grantWriteWay) := true.B
  }

  when(touchValid) {
    val touchedAge = age(touchSet)(touchWay)
    for (way <- 0 until ways) {
      when(way.U === touchWay) {
        age(touchSet)(way) := 0.U
      }.elsewhen(
        valid(touchSet)(way) &&
          age(touchSet)(way) < Mux(touchNewLine, (ways - 1).U, touchedAge)) {
        age(touchSet)(way) := age(touchSet)(way) + 1.U
      }
    }
  }

  val validBits = VecInit(valid.flatMap(_.toSeq))
  val dirtyBits = VecInit((0 until sets).flatMap(set =>
    (0 until ways).map(way => valid(set)(way) && dirty(set)(way))))
  io.residentCount := PopCount(validBits)
  io.dirtyCount := PopCount(dirtyBits)
  io.protocolError :=
    (io.storeUpdate.valid && !storeUpdateLegal) ||
      (io.grantWriteValid && !grantWriteLegal) ||
      PopCount(refillHits) > 1.U ||
      PopCount(grantWriteHits) > 1.U ||
      PopCount(storeUpdateHits) > 1.U ||
      (io.loadLookupValid && PopCount(VecInit((0 until ways).map(way =>
        valid(setIndex(io.loadLookupLineAddr))(way) &&
          tags(setIndex(io.loadLookupLineAddr))(way) === aligned(io.loadLookupLineAddr)))) > 1.U) ||
      (io.storeLookupValid && PopCount(VecInit((0 until ways).map(way =>
        valid(setIndex(io.storeLookupLineAddr))(way) &&
          tags(setIndex(io.storeLookupLineAddr))(way) === aligned(io.storeLookupLineAddr)))) > 1.U)
}
