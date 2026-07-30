package linxcore.lsu

import chisel3._
import chisel3.util._

/** Read projection for one physical STQ data row.
  *
  * Store payload is kept unaligned.  Address and byte-position ownership stays
  * in the STQ metadata row, so STD may arrive before STA without inventing an
  * address dependency in the data array.
  */
class STQDataBankReadRow(
    val dataWidth: Int,
    val lineBytes: Int,
    val leaseGenerationWidth: Int)
    extends Bundle {
  val valid = Bool()
  val generation = UInt(leaseGenerationWidth.W)
  val byteMask = UInt(lineBytes.W)
  val lineData = UInt((lineBytes * 8).W)
  val data = UInt(dataWidth.W)
}

class STQDataBankIO(
    val entries: Int,
    val dataWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val sizeWidth: Int,
    val simtLaneWidth: Int,
    val mapQDepth: Int,
    val robEntries: Int,
    val lsidWidth: Int,
    val nativeBidWidth: Int,
    val ridGenerationWidth: Int,
    val brobGenerationWidth: Int,
    val memberIndexWidth: Int,
    val residentGenerationWidth: Int,
    val leaseGenerationWidth: Int,
    val writePorts: Int,
    val lineBytes: Int)
    extends Bundle {
  val residentRows = Input(Vec(entries, new STQEntryBankRow(
    robEntries,
    dataWidth = dataWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    sizeWidth = sizeWidth,
    simtLaneWidth = simtLaneWidth,
    mapQDepth = mapQDepth,
    lsidWidth = lsidWidth,
    nativeBidWidth = nativeBidWidth,
    ridGenerationWidth = ridGenerationWidth,
    brobGenerationWidth = brobGenerationWidth,
    memberIndexWidth = memberIndexWidth,
    residentGenerationWidth = residentGenerationWidth,
    leaseGenerationWidth = leaseGenerationWidth)))
  val writes = Flipped(Vec(writePorts, Decoupled(new STQStoreRequest(
    robEntries,
    dataWidth = dataWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    sizeWidth = sizeWidth,
    simtLaneWidth = simtLaneWidth,
    mapQDepth = mapQDepth,
    lsidWidth = lsidWidth,
    nativeBidWidth = nativeBidWidth,
    ridGenerationWidth = ridGenerationWidth,
    brobGenerationWidth = brobGenerationWidth,
    memberIndexWidth = memberIndexWidth,
    residentGenerationWidth = residentGenerationWidth,
    leaseGenerationWidth = leaseGenerationWidth,
    physicalStqEntries = entries))))
  val hold = Input(Bool())
  val clearMask = Input(UInt(entries.W))

  val completions = Output(Vec(writePorts,
    Valid(new STQDataCompletion(
      entries, robEntries, peIdWidth, stidWidth, nativeBidWidth, lsidWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth, leaseGenerationWidth))))
  val rows = Output(Vec(entries,
    new STQDataBankReadRow(dataWidth, lineBytes, leaseGenerationWidth)))
  val readyMask = Output(UInt(entries.W))
  val pendingMask = Output(UInt(entries.W))
  val conflict = Output(Vec(writePorts, Bool()))
  val empty = Output(Bool())
}

/** Canonical physical store-data owner.
  *
  * The metadata/status STQ remains the allocation and recovery authority.  A
  * write is admitted only for an exact live lease and is retained through two
  * physical phases: byte-mask write, then data write.  The STQ may publish
  * `dataReady` only from the generation-qualified completion returned here.
  *
  * Two banks model the production 64-byte row layout: bank 0 owns bytes 0-31
  * and bank 1 owns bytes 32-63.  The default two independent write pipelines
  * allow two STD results to progress in the same cycle.
  */
class STQDataBank(
    val entries: Int = 16,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val robEntries: Int = 16,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val writePorts: Int = 2,
    val lineBytes: Int = 64,
    val bankCount: Int = 2)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "STQ data-bank entries must be a power of two greater than one")
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "STQ data-bank ROB identity capacity must be a power of two greater than one")
  require(writePorts == 2,
    "production scalar STQ data bank currently owns exactly two STD write ports")
  require(lineBytes == 64,
    "production scalar STQ data rows are one 64-byte cacheline wide")
  require(bankCount == 2 && lineBytes % bankCount == 0,
    "production scalar STQ data bank uses two equal byte banks")
  require(dataWidth > 0 && dataWidth % 8 == 0 && dataWidth <= lineBytes * 8,
    "store payload width must be a positive byte multiple within one data row")

  private val bankBytes = lineBytes / bankCount
  private val bankBits = bankBytes * 8
  private val dataBytes = dataWidth / 8
  private val ridSlotWidth = log2Ceil(robEntries)

  val io = IO(new STQDataBankIO(
    entries, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth,
    simtLaneWidth, mapQDepth, robEntries, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, writePorts, lineBytes))

  private object WritePhase extends ChiselEnum {
    val Idle, Mask, Data = Value
  }

  val dataBanks = Seq.fill(bankCount)(
    RegInit(VecInit(Seq.fill(entries)(0.U(bankBits.W)))))
  val maskBanks = Seq.fill(bankCount)(
    RegInit(VecInit(Seq.fill(entries)(0.U(bankBytes.W)))))
  val storedValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val storedGeneration = RegInit(VecInit(
    Seq.fill(entries)(0.U(leaseGenerationWidth.W))))
  val storedOwner = RegInit(VecInit(Seq.fill(entries)(
    0.U.asTypeOf(new STQExactOwner(
      peIdWidth, stidWidth, nativeBidWidth, ridSlotWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth)))))
  val storedLsid = RegInit(VecInit(Seq.fill(entries)(0.U(lsidWidth.W))))
  val storedStoreId = RegInit(VecInit(Seq.fill(entries)(0.U(lsidWidth.W))))

  private val phases = RegInit(VecInit(Seq.fill(writePorts)(WritePhase.Idle)))
  private val pending = Reg(Vec(writePorts, chiselTypeOf(io.writes.head.bits)))

  private def exactResident(req: STQStoreRequest): Bool = {
    val row = io.residentRows(req.lease.index)
    req.storeType === STQStoreType.Data &&
      req.lease.valid && row.valid &&
      row.status === STQEntryStatus.Wait && !row.dataReady &&
      row.leaseGeneration === req.lease.generation &&
      req.exactOwner.valid && row.exactOwner.valid &&
      req.exactOwner.asUInt === row.exactOwner.asUInt &&
      req.stid === row.stid && req.lsIdFull === row.lsIdFull &&
      req.storeIdFullValid && row.storeIdFullValid &&
      req.storeIdFull === row.storeIdFull &&
      req.logicalStoreValid && row.logicalStoreValid &&
      req.logicalFirstLsid === row.logicalFirstLsid &&
      req.logicalFirstStoreId === row.logicalFirstStoreId &&
      req.logicalRequestCount === row.logicalRequestCount &&
      req.logicalBeat === row.logicalBeat
  }

  private def requestMask(req: STQStoreRequest): UInt =
    VecInit((0 until lineBytes).map(byte =>
      byte.U < req.size && byte.U < dataBytes.U)).asUInt

  val liveReady = Wire(Vec(entries, Bool()))
  val pendingTargets = Wire(Vec(writePorts, UInt(entries.W)))
  for (port <- 0 until writePorts) {
    pendingTargets(port) := Mux(phases(port) === WritePhase.Idle,
      0.U, UIntToOH(pending(port).lease.index, entries))
  }
  val pendingMask = pendingTargets.reduce(_ | _)

  for (index <- 0 until entries) {
    val row = io.residentRows(index)
    liveReady(index) := storedValid(index) && row.valid &&
      storedGeneration(index) === row.leaseGeneration &&
      storedOwner(index).asUInt === row.exactOwner.asUInt &&
      storedLsid(index) === row.lsIdFull && row.storeIdFullValid &&
      storedStoreId(index) === row.storeIdFull
  }

  val baseWriteReady = Wire(Vec(writePorts, Bool()))
  for (port <- 0 until writePorts) {
    val request = io.writes(port).bits
    val requestOH = UIntToOH(request.lease.index, entries)
    val sizeLegal = request.size =/= 0.U && request.size <= dataBytes.U
    baseWriteReady(port) := !io.hold &&
      phases(port) === WritePhase.Idle && exactResident(request) &&
      sizeLegal && !(pendingMask & requestOH).orR &&
      !(liveReady.asUInt & requestOH).orR &&
      !(io.clearMask & requestOH).orR
  }

  for (port <- 0 until writePorts) {
    val request = io.writes(port).bits
    val earlierCollision = if (port == 0) false.B else {
      (0 until port).map { earlier =>
        io.writes(earlier).valid && baseWriteReady(earlier) &&
          io.writes(earlier).bits.lease.index === request.lease.index
      }.reduce(_ || _)
    }
    io.writes(port).ready := baseWriteReady(port) && !earlierCollision
    io.conflict(port) := io.writes(port).valid && !io.writes(port).ready
  }

  for (port <- 0 until writePorts) {
    io.completions(port).valid := false.B
    io.completions(port).bits := 0.U.asTypeOf(io.completions(port).bits)

    val transaction = pending(port)
    val index = transaction.lease.index
    val killed = phases(port) =/= WritePhase.Idle && io.clearMask(index)
    val stillExact = exactResident(transaction) && !killed
    val byteMask = requestMask(transaction)
    val paddedData = transaction.data.pad(lineBytes * 8)

    when(killed) {
      phases(port) := WritePhase.Idle
    }.elsewhen(!io.hold) {
      switch(phases(port)) {
        is(WritePhase.Mask) {
          when(stillExact) {
            for (bank <- 0 until bankCount) {
              val lo = bank * bankBytes
              val hi = lo + bankBytes - 1
              maskBanks(bank)(index) := byteMask(hi, lo)
            }
            phases(port) := WritePhase.Data
          }.otherwise {
            phases(port) := WritePhase.Idle
          }
        }
        is(WritePhase.Data) {
          when(stillExact) {
            for (bank <- 0 until bankCount) {
              val lo = bank * bankBits
              val hi = lo + bankBits - 1
              dataBanks(bank)(index) := paddedData(hi, lo)
            }
            storedValid(index) := true.B
            storedGeneration(index) := transaction.lease.generation
            storedOwner(index) := transaction.exactOwner
            storedLsid(index) := transaction.lsIdFull
            storedStoreId(index) := transaction.storeIdFull
            io.completions(port).valid := true.B
            io.completions(port).bits.lease := transaction.lease
            io.completions(port).bits.exactOwner := transaction.exactOwner
            io.completions(port).bits.lsIdFull := transaction.lsIdFull
            io.completions(port).bits.storeIdFull := transaction.storeIdFull
          }
          phases(port) := WritePhase.Idle
        }
      }
    }

    when(io.writes(port).fire) {
      pending(port) := io.writes(port).bits
      phases(port) := WritePhase.Mask
    }
  }

  for (index <- 0 until entries) {
    when(io.clearMask(index)) {
      storedValid(index) := false.B
      storedGeneration(index) := 0.U
      storedOwner(index) := 0.U.asTypeOf(storedOwner(index))
      storedLsid(index) := 0.U
      storedStoreId(index) := 0.U
      for (bank <- 0 until bankCount) {
        dataBanks(bank)(index) := 0.U
        maskBanks(bank)(index) := 0.U
      }
    }

    val lineData = Cat(dataBanks.reverse.map(_(index)))
    val byteMask = Cat(maskBanks.reverse.map(_(index)))
    io.rows(index).valid := liveReady(index)
    io.rows(index).generation := storedGeneration(index)
    io.rows(index).byteMask := Mux(liveReady(index), byteMask, 0.U)
    io.rows(index).lineData := Mux(liveReady(index), lineData, 0.U)
    io.rows(index).data := Mux(liveReady(index), lineData(dataWidth - 1, 0), 0.U)
  }

  io.readyMask := liveReady.asUInt
  io.pendingMask := pendingMask
  io.empty := !liveReady.asUInt.orR && !pendingMask.orR

  when(PopCount(io.completions.map(_.valid)) > 1.U) {
    assert(io.completions(0).bits.lease.index =/=
      io.completions(1).bits.lease.index,
      "two STD pipelines may not complete the same physical STQ row")
  }
}
