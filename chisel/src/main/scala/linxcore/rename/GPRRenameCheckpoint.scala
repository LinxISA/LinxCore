package linxcore.rename

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder, UIntToOH}

import linxcore.bctrl.BID
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class GPRRenameMapQueueEntry(
    val entries: Int,
    val bidWidth: Int,
    val archTagWidth: Int,
    val physTagWidth: Int,
    val orderWidth: Int)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(entries)
  val fullBid = UInt(bidWidth.W)
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
  val renameRid = Input(new ROBID(entries))
  val renameGid = Input(new ROBID(entries))
  val renameOrder = Input(UInt(orderWidth.W))

  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(entries))
  val postRenameCheckpointValid = Input(Bool())
  val postRenameCheckpointBid = Input(new ROBID(entries))

  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(entries))
  val commitBlockBid = Input(UInt(bidWidth.W))

  val cleanup = Input(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val cleanupOrderValid = Input(Bool())
  val cleanupOrder = Input(UInt(orderWidth.W))

  val srcPhysTags = Output(Vec(3, UInt(physTagWidth.W)))
  val renameReady = Output(Bool())
  val renameAccepted = Output(Bool())
  val renamePhysTag = Output(UInt(physTagWidth.W))
  val checkpointAccepted = Output(Bool())
  val commitAccepted = Output(Bool())
  val cleanupReady = Output(Bool())
  val cleanupFlushApplied = Output(Bool())
  val cleanupReplayObserved = Output(Bool())
  val restoreFromCheckpoint = Output(Bool())
  val restoreFromCommitMap = Output(Bool())
  val cleanupThreadMismatch = Output(Bool())

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
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val orderWidth: Int = 64)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0, "rename checkpoint entries must be a power of two")
  require(archRegs == 24, "current scalar GPR rename owner follows LinxCoreModel GPR_COUNT=24")
  require(physRegs > archRegs, "GPR rename requires physical tags beyond the architectural identity tags")
  require(mapQDepth > 0, "GPR rename mapQ depth must be nonzero")
  require(orderWidth > 0, "GPR rename row-order width must be nonzero")

  private val archTagWidth = math.max(1, log2Ceil(archRegs))
  private val physTagWidth = math.max(1, log2Ceil(physRegs))
  private val freeCountWidth = log2Ceil(physRegs + 1)
  private val mapQCountWidth = log2Ceil(mapQDepth + 1)
  private val allocatablePhysMask =
    (((BigInt(1) << physRegs) - 1) ^ ((BigInt(1) << archRegs) - 1)).U(physRegs.W)

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

  private val identityMap = VecInit((0 until archRegs).map(_.U(physTagWidth.W)))
  private val freeInit = VecInit((0 until physRegs).map(idx => (idx >= archRegs).B))

  val smap = RegInit(identityMap)
  val cmap = RegInit(identityMap)
  val cmapFullBid = RegInit(VecInit(Seq.fill(archRegs)(0.U(bidWidth.W))))
  val cmapRid = RegInit(VecInit(Seq.fill(archRegs)(0.U.asTypeOf(new ROBID(entries)))))
  val cmapOrder = RegInit(VecInit(Seq.fill(archRegs)(0.U(orderWidth.W))))
  val checkpointMap = RegInit(VecInit(Seq.fill(entries)(identityMap)))
  val checkpointValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val renamePtr = RegInit(0.U.asTypeOf(new ROBID(entries)))
  val freeList = RegInit(freeInit)
  val mapQ = RegInit(VecInit(Seq.fill(mapQDepth)(
    0.U.asTypeOf(new GPRRenameMapQueueEntry(entries, bidWidth, archTagWidth, physTagWidth, orderWidth)))))

  val mapQValidVec = VecInit(mapQ.map(_.valid))
  val mapQValidMask = mapQValidVec.asUInt
  val freeMask = freeList.asUInt
  val currentSmapLiveMask =
    (0 until archRegs).map(arch => UIntToOH(smap(arch), physRegs)).reduce(_ | _)
  val currentCmapLiveMask =
    (0 until archRegs).map(arch => UIntToOH(cmap(arch), physRegs)).reduce(_ | _)
  val currentMapQLiveMask =
    (0 until mapQDepth)
      .map(idx => Mux(mapQ(idx).valid, UIntToOH(mapQ(idx).physTag, physRegs), 0.U(physRegs.W)))
      .reduce(_ | _)
  val currentLivePhysMask = currentSmapLiveMask | currentCmapLiveMask | currentMapQLiveMask
  val currentFreeFromLiveMask = (~currentLivePhysMask).asUInt & allocatablePhysMask
  val currentFreeListMismatchMask = (freeList.asUInt ^ currentFreeFromLiveMask) & allocatablePhysMask
  val firstFreePhys = PriorityEncoder(freeMask)
  val firstFreeMapQ = PriorityEncoder(~mapQValidMask)
  val hasFreePhys = freeMask.orR
  val hasFreeMapQ = !mapQValidVec.asUInt.andR
  val mapQArchTagVec = Wire(Vec(mapQDepth, UInt(archTagWidth.W)))
  val mapQFullBidVec = Wire(Vec(mapQDepth, UInt(bidWidth.W)))
  val mapQRidVec = Wire(Vec(mapQDepth, new ROBID(entries)))
  val mapQOrderVec = Wire(Vec(mapQDepth, UInt(orderWidth.W)))
  val mapQPhysTagVec = Wire(Vec(mapQDepth, UInt(physTagWidth.W)))
  for (idx <- 0 until mapQDepth) {
    mapQArchTagVec(idx) := mapQ(idx).archTag
    mapQFullBidVec(idx) := mapQ(idx).fullBid
    mapQRidVec(idx) := mapQ(idx).rid
    mapQOrderVec(idx) := mapQ(idx).order
    mapQPhysTagVec(idx) := mapQ(idx).physTag
  }

  val cleanupValid = io.cleanup.valid && (io.cleanup.renameFlushValid || io.cleanup.renameReplayValid)
  val cleanupTargetsStid0 = io.cleanup.flush.req.stid === 0.U
  val flushFire = io.cleanup.valid && io.cleanup.renameFlushValid && cleanupTargetsStid0
  val replayFire = io.cleanup.valid && !io.cleanup.renameFlushValid && io.cleanup.renameReplayValid && cleanupTargetsStid0
  val commitFire = !cleanupValid && io.commitValid
  val checkpointFire = !cleanupValid && !io.commitValid && io.checkpointValid
  val postRenameCheckpointFire =
    !cleanupValid && !io.commitValid && !io.checkpointValid && io.postRenameCheckpointValid
  val renameCanFire = !cleanupValid && !io.commitValid && !io.checkpointValid && hasFreePhys && hasFreeMapQ
  val renameAccepted = io.renameValid && renameCanFire

  val restoreBid = ROBID.sub(io.cleanup.flush.req.bid, 1.U)
  val restoreNeeded = ROBID.lessEqual(restoreBid, renamePtr)
  val restoreCheckpointValid = checkpointValid(restoreBid.value)
  val restoreFromCheckpoint = restoreNeeded && restoreCheckpointValid
  val restoreBase = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  restoreBase := Mux(restoreCheckpointValid, checkpointMap(restoreBid.value), cmap)

  val flushPruneVec = Wire(Vec(mapQDepth, Bool()))
  val flushSameBidSurvivorVec = Wire(Vec(mapQDepth, Bool()))
  val commitHitVec = Wire(Vec(mapQDepth, Bool()))
  val commitHitHasLaterSameArch = Wire(Vec(mapQDepth, Bool()))
  for (idx <- 0 until mapQDepth) {
    val e = mapQ(idx)
    val sameBlockPrune =
      Mux(io.cleanupOrderValid, io.cleanupOrder < e.order, ROBID.lessEqual(io.cleanup.flush.req.rid, e.rid))
    flushPruneVec(idx) :=
      e.valid && Mux(
        io.cleanup.flush.baseOnBid,
        io.cleanup.blockFlushBid <= e.fullBid,
        (io.cleanup.blockFlushBid < e.fullBid) ||
          ((io.cleanup.blockFlushBid === e.fullBid) && sameBlockPrune))
    flushSameBidSurvivorVec(idx) :=
      e.valid && !flushPruneVec(idx) && !io.cleanup.flush.baseOnBid && (io.cleanup.blockFlushBid === e.fullBid)
    commitHitVec(idx) := e.valid && (e.fullBid === io.commitBlockBid)
  }
  for (idx <- 0 until mapQDepth) {
    val hasNewerSameArch = (0 until mapQDepth)
      .map { other =>
        commitHitVec(other) &&
          (mapQ(other).archTag === mapQ(idx).archTag) &&
          (mapQ(idx).order < mapQ(other).order)
      }
      .reduce(_ || _)
    commitHitHasLaterSameArch(idx) := commitHitVec(idx) && hasNewerSameArch
  }

  val committedMapQMask = commitHitVec.asUInt
  val prunedMapQMask = flushPruneVec.asUInt
  val flushSurvivorVec = Wire(Vec(mapQDepth, Bool()))
  for (idx <- 0 until mapQDepth) {
    flushSurvivorVec(idx) := mapQ(idx).valid && !flushPruneVec(idx)
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
    replaySelect.io.mapQArchTag := mapQArchTagVec
    replaySelect.io.mapQFullBid := mapQFullBidVec
    replaySelect.io.mapQRid := mapQRidVec
    replaySelect.io.mapQOrder := mapQOrderVec
    replaySelect.io.mapQPhysTag := mapQPhysTagVec
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
    commitSelect.io.mapQArchTag := mapQArchTagVec
    commitSelect.io.mapQRid := mapQRidVec
    commitSelect.io.mapQOrder := mapQOrderVec
    commitSelect.io.mapQPhysTag := mapQPhysTagVec
    commitAnyForArch(arch) := commitSelect.io.any
    commitPhysForArch(arch) := commitSelect.io.physTag
    commitRidForArch(arch) := commitSelect.io.rid
    commitOrderForArch(arch) := commitSelect.io.order
  }
  val commitCmapReleaseMask =
    (0 until archRegs)
      .map(arch => Mux(commitAnyForArch(arch), UIntToOH(cmap(arch), physRegs), 0.U(physRegs.W)))
      .reduce(_ | _)
  val commitIntermediateReleaseMask =
    (0 until mapQDepth)
      .map(idx =>
        Mux(commitHitVec(idx) && commitHitHasLaterSameArch(idx), UIntToOH(mapQ(idx).physTag, physRegs), 0.U(physRegs.W)))
      .reduce(_ | _)
  val commitReleaseMask = (commitCmapReleaseMask | commitIntermediateReleaseMask) & allocatablePhysMask
  val flushReleaseMask =
    (0 until mapQDepth)
      .map(idx => Mux(flushPruneVec(idx), UIntToOH(mapQ(idx).physTag, physRegs), 0.U(physRegs.W)))
      .reduce(_ | _) & allocatablePhysMask

  val nextSmap = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  val nextCmap = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  val nextCmapFullBid = Wire(Vec(archRegs, UInt(bidWidth.W)))
  val nextCmapRid = Wire(Vec(archRegs, new ROBID(entries)))
  val nextCmapOrder = Wire(Vec(archRegs, UInt(orderWidth.W)))
  val nextCheckpointMap = Wire(Vec(entries, Vec(archRegs, UInt(physTagWidth.W))))
  val nextCheckpointValid = Wire(Vec(entries, Bool()))
  val nextRenamePtr = Wire(new ROBID(entries))
  val nextFreeList = Wire(Vec(physRegs, Bool()))
  val nextMapQ = Wire(Vec(mapQDepth, new GPRRenameMapQueueEntry(entries, bidWidth, archTagWidth, physTagWidth, orderWidth)))
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
  smapAfterRename := smap
  smapAfterRename(io.renameArchTag) := firstFreePhys

  when(flushFire) {
    when(restoreNeeded) {
      nextRenamePtr := restoreBid
    }
    when(restoreFromCheckpoint) {
      nextSmap := restoreBase
    }.elsewhen(restoreNeeded) {
      nextSmap := replaySurvivorMap
    }
    for (idx <- 0 until mapQDepth) {
      when(flushPruneVec(idx)) {
        nextMapQ(idx).valid := false.B
      }
    }
    for (arch <- 0 until archRegs) {
      val committedBeforeFlush =
        !io.cleanup.flush.baseOnBid &&
          ((cmapFullBid(arch) < io.cleanup.blockFlushBid) ||
            ((cmapFullBid(arch) === io.cleanup.blockFlushBid) &&
              Mux(io.cleanupOrderValid, cmapOrder(arch) <= io.cleanupOrder, ROBID.less(cmapRid(arch), io.cleanup.flush.req.rid))))
      when(
        restoreFromCheckpoint && committedBeforeFlush
      ) {
        nextSmap(arch) := cmap(arch)
      }
    }
    for (idx <- 0 until mapQDepth) {
      when(restoreFromCheckpoint && flushSameBidSurvivorVec(idx)) {
        nextSmap(mapQ(idx).archTag) := mapQ(idx).physTag
      }
    }
  }.elsewhen(commitFire) {
    for (idx <- 0 until mapQDepth) {
      when(commitHitVec(idx)) {
        nextMapQ(idx).valid := false.B
      }
    }
    for (arch <- 0 until archRegs) {
      when(commitAnyForArch(arch)) {
        nextCmap(arch) := commitPhysForArch(arch)
        nextCmapFullBid(arch) := io.commitBlockBid
        nextCmapRid(arch) := commitRidForArch(arch)
        nextCmapOrder(arch) := commitOrderForArch(arch)
      }
    }
  }.elsewhen(checkpointFire) {
    nextCheckpointMap(io.checkpointBid.value) := smap
    nextCheckpointValid(io.checkpointBid.value) := true.B
    nextRenamePtr := io.checkpointBid
  }.elsewhen(renameAccepted) {
    nextSmap := smapAfterRename
    nextMapQ(firstFreeMapQ).valid := true.B
    nextMapQ(firstFreeMapQ).bid := io.renameBid
    nextMapQ(firstFreeMapQ).fullBid := io.renameBlockBid
    nextMapQ(firstFreeMapQ).rid := io.renameRid
    nextMapQ(firstFreeMapQ).gid := io.renameGid
    nextMapQ(firstFreeMapQ).order := io.renameOrder
    nextMapQ(firstFreeMapQ).archTag := io.renameArchTag
    nextMapQ(firstFreeMapQ).physTag := firstFreePhys
    when(io.postRenameCheckpointValid) {
      nextCheckpointMap(io.postRenameCheckpointBid.value) := smapAfterRename
      nextCheckpointValid(io.postRenameCheckpointBid.value) := true.B
      nextRenamePtr := io.postRenameCheckpointBid
    }
  }.elsewhen(postRenameCheckpointFire) {
    nextCheckpointMap(io.postRenameCheckpointBid.value) := smap
    nextCheckpointValid(io.postRenameCheckpointBid.value) := true.B
    nextRenamePtr := io.postRenameCheckpointBid
  }
  val nextSmapLiveMask =
    (0 until archRegs).map(arch => UIntToOH(nextSmap(arch), physRegs)).reduce(_ | _)
  val nextCmapLiveMask =
    (0 until archRegs).map(arch => UIntToOH(nextCmap(arch), physRegs)).reduce(_ | _)
  val nextMapQLiveMask =
    (0 until mapQDepth)
      .map(idx => Mux(nextMapQ(idx).valid, UIntToOH(nextMapQ(idx).physTag, physRegs), 0.U(physRegs.W)))
      .reduce(_ | _)
  val nextLivePhysMask = nextSmapLiveMask | nextCmapLiveMask | nextMapQLiveMask
  val nextMapQValidVec = VecInit(nextMapQ.map(_.valid))
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
    io.srcPhysTags(idx) := smap(io.srcArchTags(idx))
  }

  io.renameReady := renameCanFire
  io.renameAccepted := renameAccepted
  io.renamePhysTag := firstFreePhys
  io.checkpointAccepted := checkpointFire || (renameAccepted && io.postRenameCheckpointValid) ||
    (postRenameCheckpointFire && !io.renameValid)
  io.commitAccepted := commitFire
  io.cleanupReady := true.B
  io.cleanupFlushApplied := flushFire
  io.cleanupReplayObserved := replayFire
  io.restoreFromCheckpoint := flushFire && restoreFromCheckpoint
  io.restoreFromCommitMap := flushFire && !restoreFromCheckpoint
  io.cleanupThreadMismatch := cleanupValid && !cleanupTargetsStid0

  io.freeMask := freeMask
  io.freeCount := PopCount(freeList).asUInt(freeCountWidth - 1, 0)
  io.mapQValidMask := mapQValidMask
  io.mapQValidCount := PopCount(mapQValidVec).asUInt(mapQCountWidth - 1, 0)
  io.mapQFreeCount := (mapQDepth.U - PopCount(mapQValidVec))(mapQCountWidth - 1, 0)
  io.checkpointValidMask := checkpointValid.asUInt
  io.renamePtr := renamePtr
  io.smap := smap
  io.cmap := cmap
  io.smapLiveCount := PopCount(currentSmapLiveMask).asUInt(freeCountWidth - 1, 0)
  io.cmapLiveCount := PopCount(currentCmapLiveMask).asUInt(freeCountWidth - 1, 0)
  io.mapQLiveCount := PopCount(currentMapQLiveMask).asUInt(freeCountWidth - 1, 0)
  io.livePhysCount := PopCount(currentLivePhysMask).asUInt(freeCountWidth - 1, 0)
  io.freeFromLiveCount := PopCount(currentFreeFromLiveMask).asUInt(freeCountWidth - 1, 0)
  io.freeListMismatchCount := PopCount(currentFreeListMismatchMask).asUInt(freeCountWidth - 1, 0)
  io.nextMapQValidCount := PopCount(nextMapQValidVec).asUInt(mapQCountWidth - 1, 0)
  io.nextMapQLiveCount := PopCount(nextMapQLiveMask).asUInt(freeCountWidth - 1, 0)
  io.nextLivePhysCount := PopCount(nextLivePhysMask).asUInt(freeCountWidth - 1, 0)
  io.nextFreeFromLiveCount := PopCount(nextFreeFromLiveMask).asUInt(freeCountWidth - 1, 0)
  io.committedMapQMask := Mux(commitFire, committedMapQMask, 0.U(mapQDepth.W))
  io.prunedMapQMask := Mux(flushFire, prunedMapQMask, 0.U(mapQDepth.W))
  io.releasedPhysMask := Mux(commitFire, commitReleaseMask, 0.U(physRegs.W)) |
    Mux(flushFire, flushReleaseMask, 0.U(physRegs.W))
  io.stateError := cleanupValid && !cleanupTargetsStid0
}
