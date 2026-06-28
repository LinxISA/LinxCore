package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PriorityEncoder, Queue}

import linxcore.rob.ROBID

class MDBMemInfo(
    val entries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val pc = UInt(pcWidth.W)
  val bid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val stid = UInt(stidWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val waitStorePc = UInt(pcWidth.W)
  val isTile = Bool()
}

class MDBQueueBus(
    val entries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 7,
    val confWidth: Int = 2,
    val weightWidth: Int = 2)
    extends Bundle {
  val valid = Bool()
  val ldInfo = new MDBMemInfo(entries, addrWidth, pcWidth, stidWidth, sizeWidth)
  val stInfo = new MDBMemInfo(entries, addrWidth, pcWidth, stidWidth, sizeWidth)
  val conf = UInt(confWidth.W)
  val hit = Bool()
}

class MDBStoreWakeupEntry(
    val entries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val storeIndex = UInt(log2Ceil(storeEntries).W)
  val pc = UInt(pcWidth.W)
  val bid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val stid = UInt(stidWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val addrReady = Bool()
  val dataReady = Bool()
  val isTile = Bool()
}

class MDBStoreWakeup(
    val entries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val storeIndex = UInt(log2Ceil(storeEntries).W)
  val pc = UInt(pcWidth.W)
  val bid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val stid = UInt(stidWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
}

class MDBQueueFanoutIO(
    val entries: Int,
    val ssitEntries: Int,
    val commandQueueEntries: Int,
    val outputQueueEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 7,
    val confWidth: Int = 2,
    val weightWidth: Int = 2)
    extends Bundle {
  val lookupIn = Input(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
  val lookupInValid = Input(Bool())
  val lookupInReady = Output(Bool())
  val lookupInAccepted = Output(Bool())

  val deleteIn = Input(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
  val deleteInValid = Input(Bool())
  val deleteInReady = Output(Bool())
  val deleteInAccepted = Output(Bool())

  val recordIn = Input(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
  val recordInValid = Input(Bool())
  val recordInReady = Output(Bool())
  val recordInAccepted = Output(Bool())

  val luDequeueReady = Input(Bool())
  val luOutValid = Output(Bool())
  val luOut = Output(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
  val luOutDequeued = Output(Bool())

  val suCheckReady = Input(Bool())
  val suOutValid = Output(Bool())
  val suOut = Output(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
  val suOutDequeued = Output(Bool())

  val storeRows = Input(Vec(storeEntries, new MDBStoreWakeupEntry(entries, storeEntries, addrWidth, pcWidth, stidWidth, sizeWidth)))
  val suMatchedStore = Output(Bool())
  val suMatchedStoreIndex = Output(UInt(log2Ceil(storeEntries).W))
  val suStorePending = Output(Bool())
  val suWakeup = Output(new MDBStoreWakeup(entries, storeEntries, addrWidth, pcWidth, stidWidth, sizeWidth))

  val lookupProcessed = Output(Bool())
  val deleteProcessed = Output(Bool())
  val recordProcessed = Output(Bool())
  val phaseStalledByFanout = Output(Bool())

  val bmdbReportValid = Output(Bool())
  val bmdbLoadBid = Output(new ROBID(entries))
  val bmdbStoreBid = Output(new ROBID(entries))
  val bmdbStoreStid = Output(UInt(stidWidth.W))

  val deleteMatched = Output(Bool())
  val deleteReleased = Output(Bool())
  val deleteDroppedBelowStall = Output(Bool())
  val recordOverflow = Output(Bool())
  val recordOrderIllegal = Output(Bool())

  val ssitValidMask = Output(UInt(ssitEntries.W))
  val ssitTable = Output(Vec(ssitEntries, new MDBSSITEntry(entries, pcWidth, weightWidth, confWidth)))
}

class MDBQueueFanout(
    val entries: Int = 16,
    val ssitEntries: Int = 16,
    val commandQueueEntries: Int = 16,
    val outputQueueEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 7,
    val mdbReleaseWeight: Int = 25,
    val mdbMaxWeight: Int = 3,
    val mdbIncStep: Int = 1,
    val confWidth: Int = 2)
    extends Module {
  require(entries > 1, "ROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROB entries must be a power of two")
  require(ssitEntries > 1, "SSIT entries must be greater than one")
  require((ssitEntries & (ssitEntries - 1)) == 0, "SSIT entries must be a power of two")
  require(commandQueueEntries > 1, "MDB command queues must have more than one entry")
  require(outputQueueEntries > 1, "MDB output queues must have more than one entry")
  require(storeEntries > 1, "MDB store wakeup rows must have more than one entry")

  private val weightWidth = log2Ceil(mdbMaxWeight + 1).max(1)

  val io = IO(new MDBQueueFanoutIO(
    entries,
    ssitEntries,
    commandQueueEntries,
    outputQueueEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    stidWidth,
    sizeWidth,
    confWidth,
    weightWidth
  ))

  private def zeroBus: MDBQueueBus = {
    val bus = Wire(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
    bus := 0.U.asTypeOf(bus)
    bus
  }

  private def zeroWakeup: MDBStoreWakeup = {
    val wakeup = Wire(new MDBStoreWakeup(entries, storeEntries, addrWidth, pcWidth, stidWidth, sizeWidth))
    wakeup := 0.U.asTypeOf(wakeup)
    wakeup
  }

  val lookupQ = Module(new Queue(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth), commandQueueEntries))
  val deleteQ = Module(new Queue(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth), commandQueueEntries))
  val recordQ = Module(new Queue(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth), commandQueueEntries))
  val luOutQ = Module(new Queue(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth), outputQueueEntries))
  val suOutQ = Module(new Queue(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth), outputQueueEntries))

  lookupQ.io.enq.valid := io.lookupInValid
  lookupQ.io.enq.bits := io.lookupIn
  io.lookupInReady := lookupQ.io.enq.ready
  io.lookupInAccepted := lookupQ.io.enq.fire

  deleteQ.io.enq.valid := io.deleteInValid
  deleteQ.io.enq.bits := io.deleteIn
  io.deleteInReady := deleteQ.io.enq.ready
  io.deleteInAccepted := deleteQ.io.enq.fire

  recordQ.io.enq.valid := io.recordInValid
  recordQ.io.enq.bits := io.recordIn
  io.recordInReady := recordQ.io.enq.ready
  io.recordInAccepted := recordQ.io.enq.fire

  val ssit = Module(new MDBSSIT(
    robEntries = entries,
    ssitEntries = ssitEntries,
    pcWidth = pcWidth,
    mdbReleaseWeight = mdbReleaseWeight,
    mdbMaxWeight = mdbMaxWeight,
    mdbIncStep = mdbIncStep,
    confWidth = confWidth
  ))

  val lookupCanFanout = lookupQ.io.deq.valid && luOutQ.io.enq.ready && suOutQ.io.enq.ready
  val phaseStalled = lookupQ.io.deq.valid && !lookupCanFanout
  val deleteCanFire = !phaseStalled && deleteQ.io.deq.valid
  val recordCanFire = !phaseStalled && recordQ.io.deq.valid

  lookupQ.io.deq.ready := lookupCanFanout
  deleteQ.io.deq.ready := deleteCanFire
  recordQ.io.deq.ready := recordCanFire

  ssit.io.lookup.valid := lookupCanFanout
  ssit.io.lookup.loadPc := lookupQ.io.deq.bits.ldInfo.pc
  ssit.io.lookup.loadBid := lookupQ.io.deq.bits.ldInfo.bid

  ssit.io.delete.valid := deleteCanFire
  ssit.io.delete.loadPc := deleteQ.io.deq.bits.ldInfo.pc
  ssit.io.delete.storePc := deleteQ.io.deq.bits.ldInfo.waitStorePc

  ssit.io.record.valid := recordCanFire
  ssit.io.record.loadPc := recordQ.io.deq.bits.ldInfo.pc
  ssit.io.record.loadBid := recordQ.io.deq.bits.ldInfo.bid
  ssit.io.record.loadLsId := recordQ.io.deq.bits.ldInfo.lsId
  ssit.io.record.storePc := recordQ.io.deq.bits.stInfo.pc
  ssit.io.record.storeBid := recordQ.io.deq.bits.stInfo.bid
  ssit.io.record.storeLsId := recordQ.io.deq.bits.stInfo.lsId
  ssit.io.record.conf := recordQ.io.deq.bits.conf

  val lookupResult = Wire(new MDBQueueBus(entries, addrWidth, pcWidth, stidWidth, sizeWidth, confWidth))
  lookupResult := lookupQ.io.deq.bits
  lookupResult.valid := lookupCanFanout
  lookupResult.hit := ssit.io.lookupHit
  lookupResult.stInfo.valid := ssit.io.lookupHit
  lookupResult.stInfo.pc := ssit.io.lookupStorePc
  lookupResult.stInfo.bid := ssit.io.lookupStoreBid
  lookupResult.stInfo.stid := lookupQ.io.deq.bits.ldInfo.stid

  luOutQ.io.enq.valid := lookupCanFanout
  luOutQ.io.enq.bits := lookupResult
  suOutQ.io.enq.valid := lookupCanFanout
  suOutQ.io.enq.bits := lookupResult

  luOutQ.io.deq.ready := io.luDequeueReady
  io.luOutValid := luOutQ.io.deq.valid
  io.luOut := Mux(luOutQ.io.deq.valid, luOutQ.io.deq.bits, zeroBus)
  io.luOutDequeued := luOutQ.io.deq.fire

  suOutQ.io.deq.ready := io.suCheckReady
  io.suOutValid := suOutQ.io.deq.valid
  io.suOut := Mux(suOutQ.io.deq.valid, suOutQ.io.deq.bits, zeroBus)
  io.suOutDequeued := suOutQ.io.deq.fire

  val suBus = suOutQ.io.deq.bits
  val matchVec = Wire(Vec(storeEntries, Bool()))
  val firstMatchVec = Wire(Vec(storeEntries, Bool()))
  val readyVec = Wire(Vec(storeEntries, Bool()))
  for (idx <- 0 until storeEntries) {
    val row = io.storeRows(idx)
    matchVec(idx) :=
      suOutQ.io.deq.fire &&
        suBus.hit &&
        row.valid &&
        !row.isTile &&
        ROBID.equal(row.bid, suBus.stInfo.bid) &&
        (row.pc === suBus.stInfo.pc)

    val priorMatch =
      if (idx == 0) {
        false.B
      } else {
        VecInit((0 until idx).map(matchVec(_))).asUInt.orR
      }
    firstMatchVec(idx) := matchVec(idx) && !priorMatch
    readyVec(idx) := firstMatchVec(idx) && row.addrReady && row.dataReady
  }

  val anyStoreMatch = matchVec.asUInt.orR
  val anyWakeup = readyVec.asUInt.orR
  val wakeupIndex = PriorityEncoder(readyVec.asUInt)
  val matchedIndex = PriorityEncoder(firstMatchVec.asUInt)
  val wakeupRow = io.storeRows(wakeupIndex)

  io.suMatchedStore := anyStoreMatch
  io.suMatchedStoreIndex := matchedIndex
  io.suStorePending := anyStoreMatch && !anyWakeup
  io.suWakeup := zeroWakeup
  io.suWakeup.valid := anyWakeup
  io.suWakeup.storeIndex := wakeupRow.storeIndex
  io.suWakeup.pc := wakeupRow.pc
  io.suWakeup.bid := wakeupRow.bid
  io.suWakeup.lsId := wakeupRow.lsId
  io.suWakeup.stid := wakeupRow.stid
  io.suWakeup.addr := wakeupRow.addr
  io.suWakeup.size := wakeupRow.size

  io.lookupProcessed := lookupCanFanout
  io.deleteProcessed := deleteCanFire
  io.recordProcessed := recordCanFire
  io.phaseStalledByFanout := phaseStalled

  io.bmdbReportValid := recordCanFire && ssit.io.recordAccepted
  io.bmdbLoadBid := recordQ.io.deq.bits.ldInfo.bid
  io.bmdbStoreBid := recordQ.io.deq.bits.stInfo.bid
  io.bmdbStoreStid := recordQ.io.deq.bits.stInfo.stid

  io.deleteMatched := deleteCanFire && ssit.io.deleteMatched
  io.deleteReleased := deleteCanFire && ssit.io.deleteReleased
  io.deleteDroppedBelowStall := deleteCanFire && ssit.io.deleteDroppedBelowStall
  io.recordOverflow := recordCanFire && ssit.io.recordOverflow
  io.recordOrderIllegal := recordCanFire && ssit.io.recordOrderIllegal

  io.ssitValidMask := ssit.io.validMask
  for (idx <- 0 until ssitEntries) {
    io.ssitTable(idx) := ssit.io.table(idx)
  }
}
