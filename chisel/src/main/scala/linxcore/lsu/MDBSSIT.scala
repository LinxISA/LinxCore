package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount, PriorityEncoder}

import linxcore.common.LSIDOrder
import linxcore.rob.ROBID

class MDBSSITEntry(
    val robEntries: Int,
    val pcWidth: Int = 64,
    val weightWidth: Int = 2,
    val confWidth: Int = 2,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val loadPc = UInt(pcWidth.W)
  val storePc = UInt(pcWidth.W)
  val bidOff = UInt(log2Ceil(robEntries).W)
  val lsIdOff = UInt(lsidWidth.W)
  val conf = UInt(confWidth.W)
  val weight = UInt(weightWidth.W)
  val nukeValid = Bool()
  val nukeBid = new ROBID(robEntries)
}

class MDBSSITLookupRequest(val robEntries: Int, val pcWidth: Int = 64) extends Bundle {
  val valid = Bool()
  val loadPc = UInt(pcWidth.W)
  val loadBid = new ROBID(robEntries)
}

class MDBSSITRecordRequest(
    val robEntries: Int,
    val pcWidth: Int = 64,
    val confWidth: Int = 2,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val loadPc = UInt(pcWidth.W)
  val loadBid = new ROBID(robEntries)
  val loadLsId = new ROBID(robEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
  val storePc = UInt(pcWidth.W)
  val storeBid = new ROBID(robEntries)
  val storeLsId = new ROBID(robEntries)
  val storeLsIdFullValid = Bool()
  val storeLsIdFull = UInt(lsidWidth.W)
  val conf = UInt(confWidth.W)
}

class MDBSSITDeleteRequest(val pcWidth: Int = 64) extends Bundle {
  val valid = Bool()
  val loadPc = UInt(pcWidth.W)
  val storePc = UInt(pcWidth.W)
}

class MDBSSITIO(
    val robEntries: Int,
    val ssitEntries: Int,
    val pcWidth: Int = 64,
    val weightWidth: Int = 2,
    val confWidth: Int = 2,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val tableIndexWidth = log2Ceil(ssitEntries)
  private val countWidth = log2Ceil(ssitEntries + 1)

  val lookup = Input(new MDBSSITLookupRequest(robEntries, pcWidth))
  val delete = Input(new MDBSSITDeleteRequest(pcWidth))
  val record = Input(new MDBSSITRecordRequest(robEntries, pcWidth, confWidth, lsidWidth))

  val lookupResponseValid = Output(Bool())
  val lookupTableHit = Output(Bool())
  val lookupHit = Output(Bool())
  val lookupFirstAfterNuke = Output(Bool())
  val lookupConfBlocked = Output(Bool())
  val lookupWeightBlocked = Output(Bool())
  val lookupIndex = Output(UInt(tableIndexWidth.W))
  val lookupStorePc = Output(UInt(pcWidth.W))
  val lookupStoreBid = Output(new ROBID(robEntries))
  val lookupWeight = Output(UInt(weightWidth.W))
  val lookupConf = Output(UInt(confWidth.W))

  val deleteMatched = Output(Bool())
  val deleteReleased = Output(Bool())
  val deleteDroppedBelowStall = Output(Bool())
  val deleteIndex = Output(UInt(tableIndexWidth.W))
  val deleteWeightAfter = Output(UInt(weightWidth.W))

  val recordAccepted = Output(Bool())
  val recordAllocated = Output(Bool())
  val recordReplaced = Output(Bool())
  val recordReinforced = Output(Bool())
  val recordDecremented = Output(Bool())
  val recordOverflow = Output(Bool())
  val recordOrderIllegal = Output(Bool())
  val recordIndex = Output(UInt(tableIndexWidth.W))

  val validMask = Output(UInt(ssitEntries.W))
  val entryCount = Output(UInt(countWidth.W))
  val table = Output(Vec(ssitEntries, new MDBSSITEntry(
    robEntries, pcWidth, weightWidth, confWidth, lsidWidth)))
}

class MDBSSIT(
    val robEntries: Int = 16,
    val ssitEntries: Int = 16,
    val pcWidth: Int = 64,
    val mdbReleaseWeight: Int = 25,
    val mdbMaxWeight: Int = 3,
    val mdbIncStep: Int = 1,
    val confWidth: Int = 2,
    val lsidWidth: Int = 32)
    extends Module {
  require(robEntries > 1, "ROB entries must be greater than one")
  require((robEntries & (robEntries - 1)) == 0, "ROB entries must be a power of two")
  require(ssitEntries > 1, "MDB SSIT entries must be greater than one")
  require((ssitEntries & (ssitEntries - 1)) == 0, "MDB SSIT entries must be a power of two")
  require(pcWidth > 0, "PC width must be nonzero")
  require(mdbReleaseWeight >= 0 && mdbReleaseWeight <= 100, "release weight must be a percentage")
  require(mdbMaxWeight > 0, "max weight must be nonzero")
  require(mdbIncStep > 0, "weight increment must be nonzero")
  require(confWidth > 0, "confidence width must be nonzero")
  require((1 << confWidth) > 3, "confidence width must represent the model saturation value 3")

  private val weightWidth = log2Ceil(mdbMaxWeight + 1).max(1)
  private val tableIndexWidth = log2Ceil(ssitEntries)
  private val offsetWidth = log2Ceil(robEntries)
  private val initWeightValue = (mdbMaxWeight + 1) * mdbReleaseWeight / 100
  private val stallThresholdValue = initWeightValue + 1
  private val maxConfValue = (1 << confWidth) - 1

  require(initWeightValue <= mdbMaxWeight, "initial MDB weight must fit max weight")
  require(stallThresholdValue <= (mdbMaxWeight + 1), "stall threshold must fit max weight plus one")

  val io = IO(new MDBSSITIO(
    robEntries, ssitEntries, pcWidth, weightWidth, confWidth, lsidWidth))

  private def zeroEntry: MDBSSITEntry = {
    val entry = Wire(new MDBSSITEntry(
      robEntries, pcWidth, weightWidth, confWidth, lsidWidth))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def stallWeight(weight: UInt): Bool =
    weight >= stallThresholdValue.U

  private def weightInc(weight: UInt): UInt = {
    val stepped = weight +& mdbIncStep.U
    Mux(stepped > mdbMaxWeight.U, mdbMaxWeight.U, stepped)(weightWidth - 1, 0)
  }

  private def confInc(conf: UInt): UInt =
    Mux(conf === maxConfValue.U, maxConfValue.U, conf + 1.U)(confWidth - 1, 0)

  private def confDec(conf: UInt): UInt =
    Mux(conf === 0.U, 0.U, conf - 1.U)(confWidth - 1, 0)

  private def offset(newer: ROBID, older: ROBID): UInt =
    ROBID.gap(newer, older)(offsetWidth - 1, 0)

  val table = RegInit(VecInit(Seq.fill(ssitEntries)(zeroEntry)))

  val lookupMatchVec = VecInit(table.map(entry => entry.valid && (entry.loadPc === io.lookup.loadPc)))
  val lookupRawHit = lookupMatchVec.asUInt.orR
  val lookupIndex = PriorityEncoder(lookupMatchVec.asUInt)
  val lookupEntry = Mux1H(lookupMatchVec, table)
  val lookupFirstAfterNuke = lookupRawHit && lookupEntry.nukeValid && ROBID.equal(lookupEntry.nukeBid, io.lookup.loadBid)
  val lookupConfBlocked = lookupRawHit && (lookupEntry.conf === 0.U)
  val lookupWeightBlocked = lookupRawHit && !stallWeight(lookupEntry.weight)
  val lookupHit = io.lookup.valid && lookupRawHit && !lookupFirstAfterNuke && !lookupConfBlocked && !lookupWeightBlocked

  io.lookupResponseValid := io.lookup.valid
  io.lookupTableHit := io.lookup.valid && lookupRawHit
  io.lookupHit := lookupHit
  io.lookupFirstAfterNuke := io.lookup.valid && lookupFirstAfterNuke
  io.lookupConfBlocked := io.lookup.valid && lookupConfBlocked
  io.lookupWeightBlocked := io.lookup.valid && lookupWeightBlocked
  io.lookupIndex := lookupIndex
  io.lookupStorePc := Mux(io.lookup.valid && lookupRawHit, lookupEntry.storePc, 0.U)
  io.lookupStoreBid := Mux(io.lookup.valid && lookupRawHit, ROBID.sub(io.lookup.loadBid, lookupEntry.bidOff), ROBID.disabled(robEntries))
  io.lookupWeight := Mux(io.lookup.valid && lookupRawHit, lookupEntry.weight, 0.U)
  io.lookupConf := Mux(io.lookup.valid && lookupRawHit, lookupEntry.conf, 0.U)

  val afterLookup = Wire(Vec(ssitEntries, new MDBSSITEntry(
    robEntries, pcWidth, weightWidth, confWidth, lsidWidth)))
  afterLookup := table
  for (idx <- 0 until ssitEntries) {
    when(io.lookup.valid && lookupMatchVec(idx)) {
      afterLookup(idx).nukeValid := false.B
    }
  }

  val deleteMatchVec = VecInit(afterLookup.map(entry =>
    entry.valid && (entry.loadPc === io.delete.loadPc) && (entry.storePc === io.delete.storePc)))
  val deleteRawHit = deleteMatchVec.asUInt.orR
  val deleteIndex = PriorityEncoder(deleteMatchVec.asUInt)
  val deleteEntry = Mux1H(deleteMatchVec, afterLookup)
  val deleteWeightAfter = Mux(deleteEntry.weight === 0.U, 0.U, deleteEntry.weight - 1.U)
  val deleteReleased = io.delete.valid && deleteRawHit && (deleteEntry.weight === 0.U)
  val deleteDroppedBelowStall = io.delete.valid && deleteRawHit && !deleteReleased && !stallWeight(deleteWeightAfter)

  io.deleteMatched := io.delete.valid && deleteRawHit
  io.deleteReleased := deleteReleased
  io.deleteDroppedBelowStall := deleteDroppedBelowStall
  io.deleteIndex := deleteIndex
  io.deleteWeightAfter := Mux(io.delete.valid && deleteRawHit, deleteWeightAfter, 0.U)

  val afterDelete = Wire(Vec(ssitEntries, new MDBSSITEntry(
    robEntries, pcWidth, weightWidth, confWidth, lsidWidth)))
  afterDelete := afterLookup
  for (idx <- 0 until ssitEntries) {
    when(io.delete.valid && deleteMatchVec(idx)) {
      when(deleteEntry.weight === 0.U) {
        afterDelete(idx) := zeroEntry
      }.otherwise {
        afterDelete(idx).weight := deleteWeightAfter
      }
    }
  }

  val recordOrderLegal = ROBID.less(io.record.storeBid, io.record.loadBid) ||
    (ROBID.equal(io.record.storeBid, io.record.loadBid) &&
      io.record.storeLsIdFullValid && io.record.loadLsIdFullValid &&
      LSIDOrder.lessEqual(io.record.storeLsIdFull, io.record.loadLsIdFull))
  val recordMatchVec = VecInit(afterDelete.map(entry => entry.valid && (entry.loadPc === io.record.loadPc)))
  val recordRawHit = recordMatchVec.asUInt.orR
  val freeVec = VecInit(afterDelete.map(entry => !entry.valid))
  val hasFree = freeVec.asUInt.orR
  val recordIndex = Mux(recordRawHit, PriorityEncoder(recordMatchVec.asUInt), PriorityEncoder(freeVec.asUInt))
  val recordEntry = Mux1H(recordMatchVec, afterDelete)
  val recordAccepted = io.record.valid && recordOrderLegal && (recordRawHit || hasFree)
  val recordOverflow = io.record.valid && recordOrderLegal && !recordRawHit && !hasFree
  val recordAllocated = recordAccepted && !recordRawHit
  val recordBidOff = offset(io.record.loadBid, io.record.storeBid)
  val recordLsIdOff = io.record.loadLsIdFull - io.record.storeLsIdFull
  val recordDifferentStore = recordRawHit && (recordEntry.storePc =/= io.record.storePc)
  val recordReplace =
    recordAccepted && recordRawHit && recordDifferentStore &&
      ((recordEntry.conf < 1.U) ||
        (recordBidOff < recordEntry.bidOff) ||
        ((recordBidOff === recordEntry.bidOff) && (recordLsIdOff < recordEntry.lsIdOff)))
  val recordReinforce = recordAccepted && recordRawHit && !recordDifferentStore
  val recordDecrement = recordAccepted && recordRawHit && recordDifferentStore && !recordReplace

  io.recordAccepted := recordAccepted
  io.recordAllocated := recordAllocated
  io.recordReplaced := recordReplace
  io.recordReinforced := recordReinforce
  io.recordDecremented := recordDecrement
  io.recordOverflow := recordOverflow
  io.recordOrderIllegal := io.record.valid && !recordOrderLegal
  io.recordIndex := recordIndex

  val afterRecord = Wire(Vec(ssitEntries, new MDBSSITEntry(
    robEntries, pcWidth, weightWidth, confWidth, lsidWidth)))
  afterRecord := afterDelete
  for (idx <- 0 until ssitEntries) {
    when(recordAccepted && (recordIndex === idx.U)) {
      when(recordAllocated || recordReplace) {
        afterRecord(idx).valid := true.B
        afterRecord(idx).loadPc := io.record.loadPc
        afterRecord(idx).storePc := io.record.storePc
        afterRecord(idx).bidOff := recordBidOff
        afterRecord(idx).lsIdOff := recordLsIdOff
        afterRecord(idx).conf := io.record.conf
        afterRecord(idx).weight := initWeightValue.U
        afterRecord(idx).nukeValid := true.B
        afterRecord(idx).nukeBid := io.record.loadBid
      }.otherwise {
        afterRecord(idx).nukeValid := true.B
        afterRecord(idx).nukeBid := io.record.loadBid
        when(recordReinforce) {
          afterRecord(idx).conf := confInc(afterDelete(idx).conf)
          when(recordBidOff < afterDelete(idx).bidOff) {
            afterRecord(idx).bidOff := recordBidOff
          }
          afterRecord(idx).weight := weightInc(afterDelete(idx).weight)
        }.elsewhen(recordDecrement) {
          afterRecord(idx).conf := confDec(afterDelete(idx).conf)
        }
      }
    }
  }

  table := afterRecord

  val validVec = VecInit(afterRecord.map(_.valid))
  io.validMask := validVec.asUInt
  io.entryCount := PopCount(validVec)
  for (idx <- 0 until ssitEntries) {
    io.table(idx) := afterRecord(idx)
  }
}
