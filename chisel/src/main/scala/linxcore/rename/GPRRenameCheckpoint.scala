package linxcore.rename

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount, PriorityEncoder, UIntToOH}

import linxcore.bctrl.BID
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class GPRRenameMapQueueEntry(
    val entries: Int,
    val bidWidth: Int,
    val stidWidth: Int,
    val archTagWidth: Int,
    val physTagWidth: Int,
    val orderWidth: Int)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(entries)
  val fullBid = UInt(bidWidth.W)
  val stid = UInt(stidWidth.W)
  val rid = new ROBID(entries)
  val gid = new ROBID(entries)
  val order = UInt(orderWidth.W)
  val archTag = UInt(archTagWidth.W)
  val physTag = UInt(physTagWidth.W)
}

class GPRRenameCheckpointIO(
    val entries: Int,
    val archRegs: Int,
    val physRegs: Int,
    val mapQDepth: Int,
    val bidWidth: Int,
    val stidWidth: Int,
    val peIdWidth: Int,
    val tidWidth: Int,
    val orderWidth: Int)
    extends Bundle {
  private val archTagWidth = math.max(1, log2Ceil(archRegs))
  private val physTagWidth = math.max(1, log2Ceil(physRegs))
  private val freeCountWidth = log2Ceil(physRegs + 1)
  private val mapQCountWidth = log2Ceil(mapQDepth + 1)

  val srcArchTags = Input(Vec(3, UInt(archTagWidth.W)))

  val renameValid = Input(Bool())
  val renameArchTag = Input(UInt(archTagWidth.W))
  val renameBid = Input(new ROBID(entries))
  val renameBlockBid = Input(UInt(bidWidth.W))
  val renameStid = Input(UInt(stidWidth.W))
  val renameRid = Input(new ROBID(entries))
  val renameGid = Input(new ROBID(entries))
  val renameOrder = Input(UInt(orderWidth.W))

  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(entries))
  val checkpointStid = Input(UInt(stidWidth.W))
  val postRenameCheckpointValid = Input(Bool())
  val postRenameCheckpointBid = Input(new ROBID(entries))
  val postRenameCheckpointStid = Input(UInt(stidWidth.W))

  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(entries))
  val commitBlockBid = Input(UInt(bidWidth.W))
  val commitStid = Input(UInt(stidWidth.W))
  val queryStid = Input(UInt(stidWidth.W))

  val cleanup = Input(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val cleanupOrderValid = Input(Bool())
  val cleanupOrder = Input(UInt(orderWidth.W))

  val srcPhysTags = Output(Vec(3, UInt(physTagWidth.W)))
  val renameReady = Output(Bool())
  val renameAccepted = Output(Bool())
  val renamePhysTag = Output(UInt(physTagWidth.W))
  val renameOldPhysTag = Output(UInt(physTagWidth.W))
  val checkpointAccepted = Output(Bool())
  val commitAccepted = Output(Bool())
  val cleanupReady = Output(Bool())
  val cleanupFlushApplied = Output(Bool())
  val cleanupReplayObserved = Output(Bool())
  val restoreFromCheckpoint = Output(Bool())
  val restoreFromCommitMap = Output(Bool())
  val cleanupThreadMismatch = Output(Bool())
  val renameStidInRange = Output(Bool())
  val checkpointStidInRange = Output(Bool())
  val commitStidInRange = Output(Bool())
  val queryStidInRange = Output(Bool())

  val freeMask = Output(UInt(physRegs.W))
  val freeCount = Output(UInt(freeCountWidth.W))
  val mapQValidMask = Output(UInt(mapQDepth.W))
  val mapQValidCount = Output(UInt(mapQCountWidth.W))
  val mapQFreeCount = Output(UInt(mapQCountWidth.W))
  val checkpointValidMask = Output(UInt(entries.W))
  val renamePtr = Output(new ROBID(entries))
  val smap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val cmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val smapLiveCount = Output(UInt(freeCountWidth.W))
  val cmapLiveCount = Output(UInt(freeCountWidth.W))
  val mapQLiveCount = Output(UInt(freeCountWidth.W))
  val livePhysCount = Output(UInt(freeCountWidth.W))
  val freeFromLiveCount = Output(UInt(freeCountWidth.W))
  val freeListMismatchCount = Output(UInt(freeCountWidth.W))
  val nextMapQValidCount = Output(UInt(mapQCountWidth.W))
  val nextMapQLiveCount = Output(UInt(freeCountWidth.W))
  val nextLivePhysCount = Output(UInt(freeCountWidth.W))
  val nextFreeFromLiveCount = Output(UInt(freeCountWidth.W))
  val committedMapQMask = Output(UInt(mapQDepth.W))
  val prunedMapQMask = Output(UInt(mapQDepth.W))
  val releasedPhysMask = Output(UInt(physRegs.W))
  val stateError = Output(Bool())
}

class GPRRenameReplaySurvivorSelectIO(
    val entries: Int,
    val mapQDepth: Int,
    val bidWidth: Int,
    val archTagWidth: Int,
    val physTagWidth: Int,
    val orderWidth: Int)
    extends Bundle {
  val archTag = Input(UInt(archTagWidth.W))
  val restorePhys = Input(UInt(physTagWidth.W))
  val survivorValid = Input(Vec(mapQDepth, Bool()))
  val mapQArchTag = Input(Vec(mapQDepth, UInt(archTagWidth.W)))
  val mapQFullBid = Input(Vec(mapQDepth, UInt(bidWidth.W)))
  val mapQRid = Input(Vec(mapQDepth, new ROBID(entries)))
  val mapQOrder = Input(Vec(mapQDepth, UInt(orderWidth.W)))
  val mapQPhysTag = Input(Vec(mapQDepth, UInt(physTagWidth.W)))
  val physTag = Output(UInt(physTagWidth.W))
}

class GPRRenameReplaySurvivorSelect(
    val entries: Int,
    val mapQDepth: Int,
    val bidWidth: Int,
    val archTagWidth: Int,
    val physTagWidth: Int,
    val orderWidth: Int)
    extends Module {
  val io = IO(new GPRRenameReplaySurvivorSelectIO(entries, mapQDepth, bidWidth, archTagWidth, physTagWidth, orderWidth))

  val bestValid = Wire(Vec(mapQDepth + 1, Bool()))
  val bestFullBid = Wire(Vec(mapQDepth + 1, UInt(bidWidth.W)))
  val bestRid = Wire(Vec(mapQDepth + 1, new ROBID(entries)))
  val bestOrder = Wire(Vec(mapQDepth + 1, UInt(orderWidth.W)))
  val bestPhys = Wire(Vec(mapQDepth + 1, UInt(physTagWidth.W)))

  bestValid(0) := false.B
  bestFullBid(0) := 0.U
  bestRid(0) := 0.U.asTypeOf(new ROBID(entries))
  bestOrder(0) := 0.U
  bestPhys(0) := io.restorePhys

  for (idx <- 0 until mapQDepth) {
    val hit = io.survivorValid(idx) && (io.mapQArchTag(idx) === io.archTag)
    val newerThanBest =
      !bestValid(idx) ||
        (bestFullBid(idx) < io.mapQFullBid(idx)) ||
        ((bestFullBid(idx) === io.mapQFullBid(idx)) && (bestOrder(idx) < io.mapQOrder(idx)))
    val take = hit && newerThanBest
    bestValid(idx + 1) := bestValid(idx) || hit
    bestFullBid(idx + 1) := Mux(take, io.mapQFullBid(idx), bestFullBid(idx))
    bestRid(idx + 1) := Mux(take, io.mapQRid(idx), bestRid(idx))
    bestOrder(idx + 1) := Mux(take, io.mapQOrder(idx), bestOrder(idx))
    bestPhys(idx + 1) := Mux(take, io.mapQPhysTag(idx), bestPhys(idx))
  }

  io.physTag := bestPhys(mapQDepth)
}

class GPRRenameCommitArchSelectIO(
    val entries: Int,
    val mapQDepth: Int,
    val archTagWidth: Int,
    val physTagWidth: Int,
    val orderWidth: Int)
    extends Bundle {
  val archTag = Input(UInt(archTagWidth.W))
  val commitHit = Input(Vec(mapQDepth, Bool()))
  val mapQArchTag = Input(Vec(mapQDepth, UInt(archTagWidth.W)))
  val mapQRid = Input(Vec(mapQDepth, new ROBID(entries)))
  val mapQOrder = Input(Vec(mapQDepth, UInt(orderWidth.W)))
  val mapQPhysTag = Input(Vec(mapQDepth, UInt(physTagWidth.W)))
  val any = Output(Bool())
  val physTag = Output(UInt(physTagWidth.W))
  val rid = Output(new ROBID(entries))
  val order = Output(UInt(orderWidth.W))
}

class GPRRenameCommitArchSelect(
    val entries: Int,
    val mapQDepth: Int,
    val archTagWidth: Int,
    val physTagWidth: Int,
    val orderWidth: Int)
    extends Module {
  val io = IO(new GPRRenameCommitArchSelectIO(entries, mapQDepth, archTagWidth, physTagWidth, orderWidth))

  val any = Wire(Vec(mapQDepth + 1, Bool()))
  val phys = Wire(Vec(mapQDepth + 1, UInt(physTagWidth.W)))
  val rid = Wire(Vec(mapQDepth + 1, new ROBID(entries)))
  val order = Wire(Vec(mapQDepth + 1, UInt(orderWidth.W)))

  any(0) := false.B
  phys(0) := 0.U
  rid(0) := 0.U.asTypeOf(new ROBID(entries))
  order(0) := 0.U
  for (idx <- 0 until mapQDepth) {
    val hit = io.commitHit(idx) && (io.mapQArchTag(idx) === io.archTag)
    val take = hit && (!any(idx) || (order(idx) < io.mapQOrder(idx)))
    any(idx + 1) := any(idx) || hit
    phys(idx + 1) := Mux(take, io.mapQPhysTag(idx), phys(idx))
    rid(idx + 1) := Mux(take, io.mapQRid(idx), rid(idx))
    order(idx + 1) := Mux(take, io.mapQOrder(idx), order(idx))
  }

  io.any := any(mapQDepth)
  io.physTag := phys(mapQDepth)
  io.rid := rid(mapQDepth)
  io.order := order(mapQDepth)
}

class GPRRenameCheckpoint(
    val entries: Int = 64,
    val archRegs: Int = 24,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val stidCount: Int = 1,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val orderWidth: Int = 64)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0, "rename checkpoint entries must be a power of two")
  require(archRegs == 24, "current scalar GPR rename owner follows LinxCoreModel GPR_COUNT=24")
  require(physRegs > archRegs, "GPR rename requires physical tags beyond the architectural identity tags")
  require(mapQDepth > 0, "GPR rename mapQ depth must be nonzero")
  require(orderWidth > 0, "GPR rename row-order width must be nonzero")
  require(stidCount > 0, "GPR rename must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "GPR rename STID count must fit stidWidth")
  require(physRegs > stidCount * archRegs,
    "GPR rename requires shared physical tags beyond every STID identity map")

  private val archTagWidth = math.max(1, log2Ceil(archRegs))
  private val physTagWidth = math.max(1, log2Ceil(physRegs))
  private val freeCountWidth = log2Ceil(physRegs + 1)
  private val mapQCountWidth = log2Ceil(mapQDepth + 1)
  private val allocatablePhysMask =
    (((BigInt(1) << physRegs) - 1) ^ ((BigInt(1) << (stidCount * archRegs)) - 1)).U(physRegs.W)

  val io = IO(new GPRRenameCheckpointIO(
    entries,
    archRegs,
    physRegs,
    mapQDepth,
    bidWidth,
    stidWidth,
    peIdWidth,
    tidWidth,
    orderWidth))

  private def identityMap(stid: Int): Vec[UInt] =
    VecInit((0 until archRegs).map(arch => (stid * archRegs + arch).U(physTagWidth.W)))
  private val freeInit = VecInit((0 until physRegs).map(idx => (idx >= stidCount * archRegs).B))

  val smap = RegInit(VecInit((0 until stidCount).map(identityMap)))
  val cmap = RegInit(VecInit((0 until stidCount).map(identityMap)))
  val cmapFullBid = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(archRegs)(0.U(bidWidth.W))))))
  val cmapRid = RegInit(VecInit(Seq.fill(stidCount)(
    VecInit(Seq.fill(archRegs)(0.U.asTypeOf(new ROBID(entries)))))))
  val cmapOrder = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(archRegs)(0.U(orderWidth.W))))))
  val checkpointMap = RegInit(VecInit((0 until stidCount).map(stid =>
    VecInit(Seq.fill(entries)(identityMap(stid))))))
  val checkpointValid = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(entries)(false.B)))))
  val renamePtr = RegInit(VecInit(Seq.fill(stidCount)(0.U.asTypeOf(new ROBID(entries)))))
  val freeList = RegInit(freeInit)
  val mapQ = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(mapQDepth)(
    0.U.asTypeOf(new GPRRenameMapQueueEntry(
      entries, bidWidth, stidWidth, archTagWidth, physTagWidth, orderWidth)))))))

  private def matchesStid(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(idx => stid === idx.U(stidWidth.W)))

  val renameStidMatch = matchesStid(io.renameStid)
  val checkpointStidMatch = matchesStid(io.checkpointStid)
  val postRenameCheckpointStidMatch = matchesStid(io.postRenameCheckpointStid)
  val commitStidMatch = matchesStid(io.commitStid)
  val cleanupStidMatch = matchesStid(io.cleanup.flush.req.stid)
  val queryStidMatch = matchesStid(io.queryStid)
  val renameStidInRange = renameStidMatch.asUInt.orR
  val checkpointStidInRange = checkpointStidMatch.asUInt.orR
  val postRenameCheckpointStidInRange = postRenameCheckpointStidMatch.asUInt.orR
  val commitStidInRange = commitStidMatch.asUInt.orR
  val cleanupStidInRange = cleanupStidMatch.asUInt.orR
  val queryStidInRange = queryStidMatch.asUInt.orR

  val renameSmap = Mux1H(renameStidMatch, smap)
  val querySmap = Mux1H(queryStidMatch, smap)
  val queryCmap = Mux1H(queryStidMatch, cmap)
  val cleanupCmap = Mux1H(cleanupStidMatch, cmap)
  val cleanupCmapFullBid = Mux1H(cleanupStidMatch, cmapFullBid)
  val cleanupCmapRid = Mux1H(cleanupStidMatch, cmapRid)
  val cleanupCmapOrder = Mux1H(cleanupStidMatch, cmapOrder)
  val commitCmap = Mux1H(commitStidMatch, cmap)
  val renameMapQ = Mux1H(renameStidMatch, mapQ)
  val cleanupMapQ = Mux1H(cleanupStidMatch, mapQ)
  val commitMapQ = Mux1H(commitStidMatch, mapQ)
  val queryMapQ = Mux1H(queryStidMatch, mapQ)
  val renameMapQValidVec = VecInit(renameMapQ.map(_.valid))
  val queryMapQValidVec = VecInit(queryMapQ.map(_.valid))
  val queryMapQValidMask = queryMapQValidVec.asUInt
  val freeMask = freeList.asUInt
  val currentSmapLiveMask =
    (0 until stidCount).flatMap(stid =>
      (0 until archRegs).map(arch => UIntToOH(smap(stid)(arch), physRegs))).reduce(_ | _)
  val currentCmapLiveMask =
    (0 until stidCount).flatMap(stid =>
      (0 until archRegs).map(arch => UIntToOH(cmap(stid)(arch), physRegs))).reduce(_ | _)
  val currentMapQLiveMask =
    (0 until stidCount).flatMap(stid => (0 until mapQDepth)
      .map(idx => Mux(mapQ(stid)(idx).valid, UIntToOH(mapQ(stid)(idx).physTag, physRegs), 0.U(physRegs.W))))
      .reduce(_ | _)
  val currentLivePhysMask = currentSmapLiveMask | currentCmapLiveMask | currentMapQLiveMask
  val currentFreeFromLiveMask = (~currentLivePhysMask).asUInt & allocatablePhysMask
  val currentFreeListMismatchMask = (freeList.asUInt ^ currentFreeFromLiveMask) & allocatablePhysMask
  val firstFreePhys = PriorityEncoder(freeMask)
  val firstFreeMapQ = PriorityEncoder(~renameMapQValidVec.asUInt)
  val hasFreePhys = freeMask.orR
  val hasFreeMapQ = !renameMapQValidVec.asUInt.andR
  val cleanupMapQArchTagVec = Wire(Vec(mapQDepth, UInt(archTagWidth.W)))
  val cleanupMapQFullBidVec = Wire(Vec(mapQDepth, UInt(bidWidth.W)))
  val cleanupMapQRidVec = Wire(Vec(mapQDepth, new ROBID(entries)))
  val cleanupMapQOrderVec = Wire(Vec(mapQDepth, UInt(orderWidth.W)))
  val cleanupMapQPhysTagVec = Wire(Vec(mapQDepth, UInt(physTagWidth.W)))
  val commitMapQArchTagVec = Wire(Vec(mapQDepth, UInt(archTagWidth.W)))
  val commitMapQRidVec = Wire(Vec(mapQDepth, new ROBID(entries)))
  val commitMapQOrderVec = Wire(Vec(mapQDepth, UInt(orderWidth.W)))
  val commitMapQPhysTagVec = Wire(Vec(mapQDepth, UInt(physTagWidth.W)))
  for (idx <- 0 until mapQDepth) {
    cleanupMapQArchTagVec(idx) := cleanupMapQ(idx).archTag
    cleanupMapQFullBidVec(idx) := cleanupMapQ(idx).fullBid
    cleanupMapQRidVec(idx) := cleanupMapQ(idx).rid
    cleanupMapQOrderVec(idx) := cleanupMapQ(idx).order
    cleanupMapQPhysTagVec(idx) := cleanupMapQ(idx).physTag
    commitMapQArchTagVec(idx) := commitMapQ(idx).archTag
    commitMapQRidVec(idx) := commitMapQ(idx).rid
    commitMapQOrderVec(idx) := commitMapQ(idx).order
    commitMapQPhysTagVec(idx) := commitMapQ(idx).physTag
  }

  val cleanupValid = io.cleanup.valid && (io.cleanup.renameFlushValid || io.cleanup.renameReplayValid)
  val flushFire = io.cleanup.valid && io.cleanup.renameFlushValid && cleanupStidInRange
  val replayFire = io.cleanup.valid && !io.cleanup.renameFlushValid &&
    io.cleanup.renameReplayValid && cleanupStidInRange
  val commitFire = !cleanupValid && io.commitValid && commitStidInRange
  val checkpointFire = !cleanupValid && !io.commitValid && io.checkpointValid && checkpointStidInRange
  val postRenameCheckpointMatchesRename = io.postRenameCheckpointStid === io.renameStid
  val postRenameCheckpointFire =
    !cleanupValid && !io.commitValid && !io.checkpointValid && io.postRenameCheckpointValid &&
      postRenameCheckpointStidInRange
  val renameCanFire = !cleanupValid && !io.commitValid && !io.checkpointValid &&
    renameStidInRange && hasFreePhys && hasFreeMapQ
  val renameAccepted = io.renameValid && renameCanFire

  val restoreBid = ROBID.sub(io.cleanup.flush.req.bid, 1.U)
  val cleanupRenamePtr = Mux1H(cleanupStidMatch, renamePtr)
  val cleanupCheckpointMap = Mux1H(cleanupStidMatch, checkpointMap)
  val cleanupCheckpointValid = Mux1H(cleanupStidMatch, checkpointValid)
  val restoreNeeded = ROBID.lessEqual(restoreBid, cleanupRenamePtr)
  val restoreCheckpointValid = cleanupCheckpointValid(restoreBid.value)
  val restoreFromCheckpoint = restoreNeeded && restoreCheckpointValid
  val restoreBase = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  restoreBase := Mux(restoreCheckpointValid, cleanupCheckpointMap(restoreBid.value), cleanupCmap)

  val flushPruneVec = Wire(Vec(mapQDepth, Bool()))
  val flushSameBidSurvivorVec = Wire(Vec(mapQDepth, Bool()))
  val commitHitVec = Wire(Vec(mapQDepth, Bool()))
  val commitHitHasLaterSameArch = Wire(Vec(mapQDepth, Bool()))
  for (idx <- 0 until mapQDepth) {
    val flushEntry = cleanupMapQ(idx)
    val commitEntry = commitMapQ(idx)
    val sameBlockPrune =
      Mux(io.cleanupOrderValid, io.cleanupOrder < flushEntry.order,
        ROBID.lessEqual(io.cleanup.flush.req.rid, flushEntry.rid))
    flushPruneVec(idx) :=
      flushEntry.valid && Mux(
        io.cleanup.flush.baseOnBid,
        io.cleanup.blockFlushBid <= flushEntry.fullBid,
        (io.cleanup.blockFlushBid < flushEntry.fullBid) ||
          ((io.cleanup.blockFlushBid === flushEntry.fullBid) && sameBlockPrune))
    flushSameBidSurvivorVec(idx) :=
      flushEntry.valid && !flushPruneVec(idx) && !io.cleanup.flush.baseOnBid &&
        (io.cleanup.blockFlushBid === flushEntry.fullBid)
    commitHitVec(idx) := commitEntry.valid && (commitEntry.fullBid === io.commitBlockBid)
  }
  for (idx <- 0 until mapQDepth) {
    val hasNewerSameArch = (0 until mapQDepth)
      .map { other =>
        commitHitVec(other) &&
          (commitMapQ(other).archTag === commitMapQ(idx).archTag) &&
          (commitMapQ(idx).order < commitMapQ(other).order)
      }
      .reduce(_ || _)
    commitHitHasLaterSameArch(idx) := commitHitVec(idx) && hasNewerSameArch
  }

  val committedMapQMask = commitHitVec.asUInt
  val prunedMapQMask = flushPruneVec.asUInt
  val flushSurvivorVec = Wire(Vec(mapQDepth, Bool()))
  for (idx <- 0 until mapQDepth) {
    flushSurvivorVec(idx) := cleanupMapQ(idx).valid && !flushPruneVec(idx)
  }

  val replaySurvivorMap = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  for (arch <- 0 until archRegs) {
    val replaySelect = Module(new GPRRenameReplaySurvivorSelect(
      entries = entries,
      mapQDepth = mapQDepth,
      bidWidth = bidWidth,
      archTagWidth = archTagWidth,
      physTagWidth = physTagWidth,
      orderWidth = orderWidth))
    replaySelect.io.archTag := arch.U
    replaySelect.io.restorePhys := restoreBase(arch)
    replaySelect.io.survivorValid := flushSurvivorVec
    replaySelect.io.mapQArchTag := cleanupMapQArchTagVec
    replaySelect.io.mapQFullBid := cleanupMapQFullBidVec
    replaySelect.io.mapQRid := cleanupMapQRidVec
    replaySelect.io.mapQOrder := cleanupMapQOrderVec
    replaySelect.io.mapQPhysTag := cleanupMapQPhysTagVec
    replaySurvivorMap(arch) := replaySelect.io.physTag
  }

  val commitAnyForArch = Wire(Vec(archRegs, Bool()))
  val commitPhysForArch = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  val commitRidForArch = Wire(Vec(archRegs, new ROBID(entries)))
  val commitOrderForArch = Wire(Vec(archRegs, UInt(orderWidth.W)))
  for (arch <- 0 until archRegs) {
    val commitSelect = Module(new GPRRenameCommitArchSelect(
      entries = entries,
      mapQDepth = mapQDepth,
      archTagWidth = archTagWidth,
      physTagWidth = physTagWidth,
      orderWidth = orderWidth))
    commitSelect.io.archTag := arch.U
    commitSelect.io.commitHit := commitHitVec
    commitSelect.io.mapQArchTag := commitMapQArchTagVec
    commitSelect.io.mapQRid := commitMapQRidVec
    commitSelect.io.mapQOrder := commitMapQOrderVec
    commitSelect.io.mapQPhysTag := commitMapQPhysTagVec
    commitAnyForArch(arch) := commitSelect.io.any
    commitPhysForArch(arch) := commitSelect.io.physTag
    commitRidForArch(arch) := commitSelect.io.rid
    commitOrderForArch(arch) := commitSelect.io.order
  }
  val commitCmapReleaseMask =
    (0 until archRegs)
      .map(arch => Mux(commitAnyForArch(arch), UIntToOH(commitCmap(arch), physRegs), 0.U(physRegs.W)))
      .reduce(_ | _)
  val commitIntermediateReleaseMask =
    (0 until mapQDepth)
      .map(idx =>
        Mux(commitHitVec(idx) && commitHitHasLaterSameArch(idx),
          UIntToOH(commitMapQ(idx).physTag, physRegs), 0.U(physRegs.W)))
      .reduce(_ | _)
  val commitReleaseMask = (commitCmapReleaseMask | commitIntermediateReleaseMask) & allocatablePhysMask
  val flushReleaseMask =
    (0 until mapQDepth)
      .map(idx => Mux(flushPruneVec(idx), UIntToOH(cleanupMapQ(idx).physTag, physRegs), 0.U(physRegs.W)))
      .reduce(_ | _) & allocatablePhysMask

  val nextSmap = Wire(Vec(stidCount, Vec(archRegs, UInt(physTagWidth.W))))
  val nextCmap = Wire(Vec(stidCount, Vec(archRegs, UInt(physTagWidth.W))))
  val nextCmapFullBid = Wire(Vec(stidCount, Vec(archRegs, UInt(bidWidth.W))))
  val nextCmapRid = Wire(Vec(stidCount, Vec(archRegs, new ROBID(entries))))
  val nextCmapOrder = Wire(Vec(stidCount, Vec(archRegs, UInt(orderWidth.W))))
  val nextCheckpointMap = Wire(Vec(stidCount, Vec(entries, Vec(archRegs, UInt(physTagWidth.W)))))
  val nextCheckpointValid = Wire(Vec(stidCount, Vec(entries, Bool())))
  val nextRenamePtr = Wire(Vec(stidCount, new ROBID(entries)))
  val nextFreeList = Wire(Vec(physRegs, Bool()))
  val nextMapQ = Wire(Vec(stidCount, Vec(mapQDepth,
    new GPRRenameMapQueueEntry(entries, bidWidth, stidWidth, archTagWidth, physTagWidth, orderWidth))))
  val smapAfterRename = Wire(Vec(archRegs, UInt(physTagWidth.W)))

  nextSmap := smap
  nextCmap := cmap
  nextCmapFullBid := cmapFullBid
  nextCmapRid := cmapRid
  nextCmapOrder := cmapOrder
  nextCheckpointMap := checkpointMap
  nextCheckpointValid := checkpointValid
  nextRenamePtr := renamePtr
  nextFreeList := VecInit(Seq.fill(physRegs)(false.B))
  nextMapQ := mapQ
  smapAfterRename := renameSmap
  smapAfterRename(io.renameArchTag) := firstFreePhys

  when(flushFire) {
    for (stid <- 0 until stidCount) {
      when(cleanupStidMatch(stid)) {
        when(restoreNeeded) {
          nextRenamePtr(stid) := restoreBid
        }
        when(restoreFromCheckpoint) {
          nextSmap(stid) := restoreBase
        }.elsewhen(restoreNeeded) {
          nextSmap(stid) := replaySurvivorMap
        }
        for (idx <- 0 until mapQDepth) {
          when(flushPruneVec(idx)) {
            nextMapQ(stid)(idx).valid := false.B
          }
        }
        for (arch <- 0 until archRegs) {
          val committedBeforeFlush =
            !io.cleanup.flush.baseOnBid &&
              ((cleanupCmapFullBid(arch) < io.cleanup.blockFlushBid) ||
                ((cleanupCmapFullBid(arch) === io.cleanup.blockFlushBid) &&
                  Mux(io.cleanupOrderValid, cleanupCmapOrder(arch) <= io.cleanupOrder,
                    ROBID.less(cleanupCmapRid(arch), io.cleanup.flush.req.rid))))
          when(restoreFromCheckpoint && committedBeforeFlush) {
            nextSmap(stid)(arch) := cleanupCmap(arch)
          }
        }
        for (idx <- 0 until mapQDepth) {
          when(restoreFromCheckpoint && flushSameBidSurvivorVec(idx)) {
            nextSmap(stid)(cleanupMapQ(idx).archTag) := cleanupMapQ(idx).physTag
          }
        }
      }
    }
  }.elsewhen(commitFire) {
    for (stid <- 0 until stidCount) {
      when(commitStidMatch(stid)) {
        for (idx <- 0 until mapQDepth) {
          when(commitHitVec(idx)) {
            nextMapQ(stid)(idx).valid := false.B
          }
        }
        for (arch <- 0 until archRegs) {
          when(commitAnyForArch(arch)) {
            nextCmap(stid)(arch) := commitPhysForArch(arch)
            nextCmapFullBid(stid)(arch) := io.commitBlockBid
            nextCmapRid(stid)(arch) := commitRidForArch(arch)
            nextCmapOrder(stid)(arch) := commitOrderForArch(arch)
          }
        }
      }
    }
  }.elsewhen(checkpointFire) {
    for (stid <- 0 until stidCount) {
      when(checkpointStidMatch(stid)) {
        nextCheckpointMap(stid)(io.checkpointBid.value) := smap(stid)
        nextCheckpointValid(stid)(io.checkpointBid.value) := true.B
        nextRenamePtr(stid) := io.checkpointBid
      }
    }
  }.elsewhen(renameAccepted) {
    for (stid <- 0 until stidCount) {
      when(renameStidMatch(stid)) {
        nextSmap(stid) := smapAfterRename
        nextMapQ(stid)(firstFreeMapQ).valid := true.B
        nextMapQ(stid)(firstFreeMapQ).bid := io.renameBid
        nextMapQ(stid)(firstFreeMapQ).fullBid := io.renameBlockBid
        nextMapQ(stid)(firstFreeMapQ).stid := io.renameStid
        nextMapQ(stid)(firstFreeMapQ).rid := io.renameRid
        nextMapQ(stid)(firstFreeMapQ).gid := io.renameGid
        nextMapQ(stid)(firstFreeMapQ).order := io.renameOrder
        nextMapQ(stid)(firstFreeMapQ).archTag := io.renameArchTag
        nextMapQ(stid)(firstFreeMapQ).physTag := firstFreePhys
        when(io.postRenameCheckpointValid && postRenameCheckpointMatchesRename) {
          nextCheckpointMap(stid)(io.postRenameCheckpointBid.value) := smapAfterRename
          nextCheckpointValid(stid)(io.postRenameCheckpointBid.value) := true.B
          nextRenamePtr(stid) := io.postRenameCheckpointBid
        }
      }
    }
  }.elsewhen(postRenameCheckpointFire) {
    for (stid <- 0 until stidCount) {
      when(postRenameCheckpointStidMatch(stid)) {
        nextCheckpointMap(stid)(io.postRenameCheckpointBid.value) := smap(stid)
        nextCheckpointValid(stid)(io.postRenameCheckpointBid.value) := true.B
        nextRenamePtr(stid) := io.postRenameCheckpointBid
      }
    }
  }
  val nextSmapLiveMask =
    (0 until stidCount).flatMap(stid =>
      (0 until archRegs).map(arch => UIntToOH(nextSmap(stid)(arch), physRegs))).reduce(_ | _)
  val nextCmapLiveMask =
    (0 until stidCount).flatMap(stid =>
      (0 until archRegs).map(arch => UIntToOH(nextCmap(stid)(arch), physRegs))).reduce(_ | _)
  val nextMapQLiveMask =
    (0 until stidCount).flatMap(stid => (0 until mapQDepth)
      .map(idx => Mux(nextMapQ(stid)(idx).valid,
        UIntToOH(nextMapQ(stid)(idx).physTag, physRegs), 0.U(physRegs.W))))
      .reduce(_ | _)
  val nextLivePhysMask = nextSmapLiveMask | nextCmapLiveMask | nextMapQLiveMask
  val nextQueryMapQ = Mux1H(queryStidMatch, nextMapQ)
  val nextQueryMapQValidVec = VecInit(nextQueryMapQ.map(_.valid))
  val nextFreeFromLiveMask = (~nextLivePhysMask).asUInt & allocatablePhysMask
  for (phys <- 0 until physRegs) {
    nextFreeList(phys) := !nextLivePhysMask(phys) && allocatablePhysMask(phys).asBool
  }

  smap := nextSmap
  cmap := nextCmap
  cmapFullBid := nextCmapFullBid
  cmapRid := nextCmapRid
  cmapOrder := nextCmapOrder
  checkpointMap := nextCheckpointMap
  checkpointValid := nextCheckpointValid
  renamePtr := nextRenamePtr
  freeList := nextFreeList
  mapQ := nextMapQ

  for (idx <- 0 until 3) {
    io.srcPhysTags(idx) := Mux(renameStidInRange, renameSmap(io.srcArchTags(idx)), 0.U)
  }

  io.renameReady := renameCanFire
  io.renameAccepted := renameAccepted
  io.renamePhysTag := firstFreePhys
  io.renameOldPhysTag := Mux(renameStidInRange, renameSmap(io.renameArchTag), 0.U)
  io.checkpointAccepted := checkpointFire ||
    (renameAccepted && io.postRenameCheckpointValid && postRenameCheckpointMatchesRename) ||
    (postRenameCheckpointFire && !io.renameValid)
  io.commitAccepted := commitFire
  io.cleanupReady := true.B
  io.cleanupFlushApplied := flushFire
  io.cleanupReplayObserved := replayFire
  io.restoreFromCheckpoint := flushFire && restoreFromCheckpoint
  io.restoreFromCommitMap := flushFire && !restoreFromCheckpoint
  io.cleanupThreadMismatch := cleanupValid && !cleanupStidInRange
  io.renameStidInRange := renameStidInRange
  io.checkpointStidInRange := checkpointStidInRange
  io.commitStidInRange := commitStidInRange
  io.queryStidInRange := queryStidInRange

  io.freeMask := freeMask
  io.freeCount := PopCount(freeList).asUInt(freeCountWidth - 1, 0)
  io.mapQValidMask := Mux(queryStidInRange, queryMapQValidMask, 0.U)
  io.mapQValidCount := PopCount(queryMapQValidVec).asUInt(mapQCountWidth - 1, 0)
  io.mapQFreeCount := Mux(queryStidInRange,
    (mapQDepth.U - PopCount(queryMapQValidVec))(mapQCountWidth - 1, 0), 0.U)
  val queryCheckpointValid = Mux1H(queryStidMatch, checkpointValid)
  io.checkpointValidMask := Mux(queryStidInRange, queryCheckpointValid.asUInt, 0.U)
  io.renamePtr := Mux1H(queryStidMatch, renamePtr)
  io.smap := querySmap
  io.cmap := queryCmap
  io.smapLiveCount := PopCount(currentSmapLiveMask).asUInt(freeCountWidth - 1, 0)
  io.cmapLiveCount := PopCount(currentCmapLiveMask).asUInt(freeCountWidth - 1, 0)
  io.mapQLiveCount := PopCount(currentMapQLiveMask).asUInt(freeCountWidth - 1, 0)
  io.livePhysCount := PopCount(currentLivePhysMask).asUInt(freeCountWidth - 1, 0)
  io.freeFromLiveCount := PopCount(currentFreeFromLiveMask).asUInt(freeCountWidth - 1, 0)
  io.freeListMismatchCount := PopCount(currentFreeListMismatchMask).asUInt(freeCountWidth - 1, 0)
  io.nextMapQValidCount := PopCount(nextQueryMapQValidVec).asUInt(mapQCountWidth - 1, 0)
  io.nextMapQLiveCount := PopCount(nextMapQLiveMask).asUInt(freeCountWidth - 1, 0)
  io.nextLivePhysCount := PopCount(nextLivePhysMask).asUInt(freeCountWidth - 1, 0)
  io.nextFreeFromLiveCount := PopCount(nextFreeFromLiveMask).asUInt(freeCountWidth - 1, 0)
  io.committedMapQMask := Mux(commitFire, committedMapQMask, 0.U(mapQDepth.W))
  io.prunedMapQMask := Mux(flushFire, prunedMapQMask, 0.U(mapQDepth.W))
  io.releasedPhysMask := Mux(commitFire, commitReleaseMask, 0.U(physRegs.W)) |
    Mux(flushFire, flushReleaseMask, 0.U(physRegs.W))
  val renameStidError = io.renameValid && !renameStidInRange
  val checkpointStidError = io.checkpointValid && !checkpointStidInRange
  val commitStidError = io.commitValid && !commitStidInRange
  val postRenameCheckpointError = io.postRenameCheckpointValid &&
    (!postRenameCheckpointStidInRange || (io.renameValid && !postRenameCheckpointMatchesRename))
  io.stateError := (cleanupValid && !cleanupStidInRange) || renameStidError ||
    checkpointStidError || commitStidError || postRenameCheckpointError
}
