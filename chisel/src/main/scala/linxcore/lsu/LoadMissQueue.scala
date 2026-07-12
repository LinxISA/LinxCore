package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, PopCount, PriorityEncoder, Queue, UIntToOH, log2Ceil}

import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class LoadMissDependent(
    val liqEntries: Int,
    val idEntries: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int)
    extends Bundle {
  val valid = Bool()
  val loadIndex = UInt(log2Ceil(liqEntries).W)
  val loadId = new ROBID(liqEntries)
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
}

class LoadMissLowerRequest(
    val missEntries: Int,
    val addrWidth: Int)
    extends Bundle {
  val missId = new ROBID(missEntries)
  val lineAddr = UInt(addrWidth.W)
  val isRead = Bool()
}

class LoadMissLowerResponse(
    val missEntries: Int,
    val addrWidth: Int,
    val lineBytes: Int)
    extends Bundle {
  val missId = new ROBID(missEntries)
  val lineAddr = UInt(addrWidth.W)
  val isRead = Bool()
  val data = UInt((lineBytes * 8).W)
  val l2Miss = Bool()
}

class LoadMissQueueEntry(
    val missEntries: Int,
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int)
    extends Bundle {
  val valid = Bool()
  val issued = Bool()
  val missId = new ROBID(missEntries)
  val lineAddr = UInt(addrWidth.W)
  val dependents = Vec(liqEntries, new LoadMissDependent(
    liqEntries, idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
}

class LoadMissQueueIO(
    val missEntries: Int,
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val returnPipeCount: Int,
    val lsidWidth: Int)
    extends Bundle {
  private val missCountWidth = log2Ceil(missEntries + 1)
  private val dependentCountWidth = log2Ceil(missEntries * liqEntries + 1)

  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))

  val missValid = Input(Bool())
  val missIndex = Input(UInt(log2Ceil(liqEntries).W))
  val missRow = Input(new LoadInflightRow(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount,
    lsidWidth
  ))
  val missReady = Output(Bool())
  val missAccepted = Output(Bool())
  val missAllocated = Output(Bool())
  val missCoalesced = Output(Bool())
  val missSatisfiedByResponse = Output(Bool())
  val missBlockedByCapacity = Output(Bool())
  val missBlockedByIndexCollision = Output(Bool())

  val requestValid = Output(Bool())
  val requestReady = Input(Bool())
  val request = Output(new LoadMissLowerRequest(missEntries, addrWidth))
  val requestAccepted = Output(Bool())
  val requestDroppedNoDependents = Output(Bool())

  val responseValid = Input(Bool())
  val responseReady = Output(Bool())
  val response = Input(new LoadMissLowerResponse(missEntries, addrWidth, lineBytes))
  val responseAccepted = Output(Bool())
  val responseMatched = Output(Bool())
  val responseStale = Output(Bool())
  val responseWrongType = Output(Bool())
  val responseDuplicateMatch = Output(Bool())

  val refillValid = Output(Bool())
  val refillReady = Input(Bool())
  val refill = Output(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val responseBlockedByRefill = Output(Bool())

  val entries = Output(Vec(missEntries, new LoadMissQueueEntry(
    missEntries,
    liqEntries,
    idEntries,
    addrWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  )))
  val validMask = Output(UInt(missEntries.W))
  val issuedMask = Output(UInt(missEntries.W))
  val orphanMask = Output(UInt(missEntries.W))
  val validCount = Output(UInt(missCountWidth.W))
  val freeCount = Output(UInt(missCountWidth.W))
  val dependentCount = Output(UInt(dependentCountWidth.W))
  val precisePruneCount = Output(UInt(dependentCountWidth.W))
  val empty = Output(Bool())
  val pending = Output(Bool())
  val protocolError = Output(Bool())
}

class LoadMissQueue(
    val missEntries: Int = 8,
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 7,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val returnPipeCount: Int = 1,
    val lsidWidth: Int = 32)
    extends Module {
  require(missEntries > 1 && (missEntries & (missEntries - 1)) == 0,
    "missEntries must be a power of two greater than one")
  require(liqEntries > 1 && (liqEntries & (liqEntries - 1)) == 0,
    "liqEntries must be a power of two greater than one")
  require(idEntries > 1 && (idEntries & (idEntries - 1)) == 0,
    "idEntries must be a power of two greater than one")
  require(storeEntries > 1 && (storeEntries & (storeEntries - 1)) == 0,
    "storeEntries must be a power of two greater than one")
  require(lineBytes > 1 && (lineBytes & (lineBytes - 1)) == 0,
    "lineBytes must be a power of two greater than one")
  require(addrWidth > log2Ceil(lineBytes), "addrWidth must include a cache-line tag")

  private val missIndexWidth = log2Ceil(missEntries)
  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadMissQueueIO(
    missEntries,
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount,
    lsidWidth
  ))

  private def zeroEntry: LoadMissQueueEntry =
    0.U.asTypeOf(new LoadMissQueueEntry(
      missEntries,
      liqEntries,
      idEntries,
      addrWidth,
      peIdWidth,
      stidWidth,
      tidWidth,
      lsidWidth))

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  private def sameDependent(a: LoadMissDependent, row: LoadInflightRow, index: UInt): Bool =
    a.valid &&
      a.loadIndex === index &&
      ROBID.equal(a.loadId, row.loadId) &&
      a.peId === row.peId &&
      a.stid === row.stid &&
      a.tid === row.tid &&
      ROBID.equal(a.bid, row.bid) &&
      ROBID.equal(a.gid, row.gid) &&
      ROBID.equal(a.rid, row.rid) &&
      ROBID.equal(a.loadLsId, row.loadLsId) &&
      a.loadLsIdFullValid === row.loadLsIdFullValid &&
      (!a.loadLsIdFullValid || a.loadLsIdFull === row.loadLsIdFull)

  val entries = RegInit(VecInit(Seq.fill(missEntries)(zeroEntry)))
  val generations = RegInit(VecInit(Seq.fill(missEntries)(false.B)))
  val issueQ = Module(new Queue(UInt(missIndexWidth.W), missEntries, pipe = false, flow = false))

  val flushCycle = io.flush || io.preciseFlush.req.valid
  val candidateLineAddr = lineAddr(io.missRow.addr)
  val candidateUsable =
    io.missValid &&
      io.missRow.valid &&
      io.missRow.size =/= 0.U &&
      !io.missRow.isTile

  val responseMatchVec = Wire(Vec(missEntries, Bool()))
  for (idx <- 0 until missEntries) {
    responseMatchVec(idx) :=
      io.response.missId.valid &&
        entries(idx).valid &&
        entries(idx).issued &&
        ROBID.equal(entries(idx).missId, io.response.missId) &&
        entries(idx).lineAddr === io.response.lineAddr
  }
  val responseMatchMask = responseMatchVec.asUInt
  val responseMatchCount = PopCount(responseMatchVec)
  val responseUniqueMatch = responseMatchCount === 1.U
  val responseMatchIndex = PriorityEncoder(responseMatchMask)
  val responseNeedsRefill = io.responseValid && responseUniqueMatch && io.response.isRead
  val responseAccepted = io.responseValid && io.responseReady
  val responseReadMatch = responseAccepted && responseUniqueMatch && io.response.isRead
  val responseFree = responseReadMatch

  val existingVec = Wire(Vec(missEntries, Bool()))
  val freeVec = Wire(Vec(missEntries, Bool()))
  for (idx <- 0 until missEntries) {
    existingVec(idx) :=
      entries(idx).valid &&
        entries(idx).lineAddr === candidateLineAddr &&
        !(responseFree && responseMatchIndex === idx.U)
    freeVec(idx) := !entries(idx).valid
  }
  val existingMask = existingVec.asUInt
  val existingCount = PopCount(existingVec)
  val existingMatch = existingCount === 1.U
  val existingIndex = PriorityEncoder(existingMask)
  val freeMask = freeVec.asUInt
  val hasFree = freeMask.orR
  val freeIndex = PriorityEncoder(freeMask)

  val candidateDependent = Wire(new LoadMissDependent(
    liqEntries, idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  candidateDependent := 0.U.asTypeOf(candidateDependent)
  candidateDependent.valid := candidateUsable
  candidateDependent.loadIndex := io.missIndex
  candidateDependent.loadId := io.missRow.loadId
  candidateDependent.peId := io.missRow.peId
  candidateDependent.stid := io.missRow.stid
  candidateDependent.tid := io.missRow.tid
  candidateDependent.bid := io.missRow.bid
  candidateDependent.gid := io.missRow.gid
  candidateDependent.rid := io.missRow.rid
  candidateDependent.loadLsId := io.missRow.loadLsId
  candidateDependent.loadLsIdFullValid := io.missRow.loadLsIdFullValid
  candidateDependent.loadLsIdFull := io.missRow.loadLsIdFull

  val existingSlotOccupied = existingMatch && entries(existingIndex).dependents(io.missIndex).valid
  val existingSlotExact = existingMatch &&
    sameDependent(entries(existingIndex).dependents(io.missIndex), io.missRow, io.missIndex)
  val dependentElsewhereVec = Wire(Vec(missEntries, Bool()))
  for (idx <- 0 until missEntries) {
    dependentElsewhereVec(idx) :=
      entries(idx).valid &&
        entries(idx).dependents(io.missIndex).valid &&
        (!existingMatch || idx.U =/= existingIndex)
  }
  val indexCollision =
    candidateUsable &&
      ((existingSlotOccupied && !existingSlotExact) || dependentElsewhereVec.asUInt.orR)

  val responseSatisfiesCandidate =
    candidateUsable &&
      responseReadMatch &&
      io.response.lineAddr === candidateLineAddr
  val canCoalesce = existingMatch && !indexCollision
  val canAllocate = !existingMask.orR && hasFree && issueQ.io.enq.ready && !indexCollision
  val missReady = !flushCycle && candidateUsable &&
    (responseSatisfiesCandidate || canCoalesce || canAllocate)
  val missAccepted = io.missValid && missReady
  val missSatisfiedByResponse = missAccepted && responseSatisfiesCandidate
  val missCoalesced = missAccepted && !responseSatisfiesCandidate && canCoalesce
  val missAllocated = missAccepted && !responseSatisfiesCandidate && !canCoalesce && canAllocate

  issueQ.io.enq.valid := missAllocated
  issueQ.io.enq.bits := freeIndex

  val issueHeadIndex = issueQ.io.deq.bits
  val issueHead = entries(issueHeadIndex)
  val coalesceAddsHead = missCoalesced && existingIndex === issueHeadIndex
  val issueHeadHasDependents = issueHead.dependents.map(_.valid).reduce(_ || _) || coalesceAddsHead
  val issueHeadLive =
    issueQ.io.deq.valid && issueHead.valid && !issueHead.issued && issueHeadHasDependents
  val issueHeadDrop =
    issueQ.io.deq.valid && (!issueHead.valid || issueHead.issued || !issueHeadHasDependents)

  io.requestValid := issueHeadLive && !flushCycle
  io.request := 0.U.asTypeOf(io.request)
  io.request.missId := issueHead.missId
  io.request.lineAddr := issueHead.lineAddr
  io.request.isRead := true.B
  val requestAccepted = io.requestValid && io.requestReady
  val requestDropped = issueHeadDrop && !flushCycle
  issueQ.io.deq.ready := requestAccepted || requestDropped

  io.responseReady := !flushCycle && (!responseNeedsRefill || io.refillReady)
  io.refillValid := responseReadMatch
  io.refill := 0.U.asTypeOf(io.refill)
  io.refill.isRead := responseReadMatch
  io.refill.lineAddr := io.response.lineAddr
  io.refill.data := io.response.data
  io.refill.l2Miss := io.response.l2Miss

  val precisePruneVec = Wire(Vec(missEntries, Vec(liqEntries, Bool())))
  for (entryIdx <- 0 until missEntries) {
    for (loadIdx <- 0 until liqEntries) {
      val dep = entries(entryIdx).dependents(loadIdx)
      precisePruneVec(entryIdx)(loadIdx) := LoadQueueFlushMatch(
        io.preciseFlush,
        dep.valid,
        dep.peId,
        dep.stid,
        dep.tid,
        dep.bid,
        dep.gid,
        dep.loadLsId,
        dep.loadLsIdFullValid,
        dep.loadLsIdFull)
    }
  }

  when(flushCycle) {
    for (entryIdx <- 0 until missEntries) {
      for (loadIdx <- 0 until liqEntries) {
        when(io.flush || precisePruneVec(entryIdx)(loadIdx)) {
          entries(entryIdx).dependents(loadIdx).valid := false.B
        }
      }
    }
  }.otherwise {
    when(requestAccepted) {
      entries(issueHeadIndex).issued := true.B
    }

    when(requestDropped && issueHead.valid && !issueHead.issued) {
      entries(issueHeadIndex) := zeroEntry
      generations(issueHeadIndex) := !generations(issueHeadIndex)
    }

    when(responseFree) {
      entries(responseMatchIndex) := zeroEntry
      generations(responseMatchIndex) := !generations(responseMatchIndex)
    }

    when(missAllocated) {
      entries(freeIndex) := zeroEntry
      entries(freeIndex).valid := true.B
      entries(freeIndex).issued := false.B
      entries(freeIndex).missId.valid := true.B
      entries(freeIndex).missId.wrap := generations(freeIndex)
      entries(freeIndex).missId.value := freeIndex
      entries(freeIndex).lineAddr := candidateLineAddr
      entries(freeIndex).dependents(io.missIndex) := candidateDependent
    }

    when(missCoalesced) {
      entries(existingIndex).dependents(io.missIndex) := candidateDependent
    }
  }

  val validVec = Wire(Vec(missEntries, Bool()))
  val issuedVec = Wire(Vec(missEntries, Bool()))
  val orphanVec = Wire(Vec(missEntries, Bool()))
  val dependentValidVec = Wire(Vec(missEntries, Vec(liqEntries, Bool())))
  for (entryIdx <- 0 until missEntries) {
    val hasDependents = entries(entryIdx).dependents.map(_.valid).reduce(_ || _)
    validVec(entryIdx) := entries(entryIdx).valid
    issuedVec(entryIdx) := entries(entryIdx).valid && entries(entryIdx).issued
    orphanVec(entryIdx) := entries(entryIdx).valid && entries(entryIdx).issued && !hasDependents
    for (loadIdx <- 0 until liqEntries) {
      dependentValidVec(entryIdx)(loadIdx) := entries(entryIdx).dependents(loadIdx).valid
    }
    io.entries(entryIdx) := entries(entryIdx)
  }

  val validCount = PopCount(validVec)
  val dependentCount = PopCount(VecInit(dependentValidVec.flatMap(_.toSeq)))
  val precisePruneCount = PopCount(VecInit(precisePruneVec.flatMap(_.toSeq)))
  val duplicateLineError = candidateUsable && existingCount > 1.U
  val responseDuplicateMatch = responseAccepted && responseMatchCount > 1.U
  val responseStale = responseAccepted && !responseUniqueMatch
  val responseWrongType = responseAccepted && responseUniqueMatch && !io.response.isRead

  io.missReady := missReady
  io.missAccepted := missAccepted
  io.missAllocated := missAllocated
  io.missCoalesced := missCoalesced
  io.missSatisfiedByResponse := missSatisfiedByResponse
  io.missBlockedByCapacity := candidateUsable && !flushCycle && !indexCollision &&
    !responseSatisfiesCandidate && !canCoalesce && !canAllocate
  io.missBlockedByIndexCollision := candidateUsable && indexCollision
  io.requestAccepted := requestAccepted
  io.requestDroppedNoDependents := requestDropped && issueHead.valid && !issueHead.issued
  io.responseAccepted := responseAccepted
  io.responseMatched := responseAccepted && responseUniqueMatch
  io.responseStale := responseStale
  io.responseWrongType := responseWrongType
  io.responseDuplicateMatch := responseDuplicateMatch
  io.responseBlockedByRefill := responseNeedsRefill && !flushCycle && !io.refillReady
  io.validMask := validVec.asUInt
  io.issuedMask := issuedVec.asUInt
  io.orphanMask := orphanVec.asUInt
  io.validCount := validCount
  io.freeCount := missEntries.U - validCount
  io.dependentCount := dependentCount
  io.precisePruneCount := precisePruneCount
  io.empty := !validVec.asUInt.orR && !issueQ.io.deq.valid
  io.pending := validVec.asUInt.orR || issueQ.io.deq.valid
  io.protocolError :=
    duplicateLineError ||
      indexCollision ||
      responseDuplicateMatch ||
      responseStale ||
      responseWrongType ||
      (requestDropped && issueHead.valid && issueHead.issued)
}
