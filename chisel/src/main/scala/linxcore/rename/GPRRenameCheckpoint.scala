package linxcore.rename

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder}

import linxcore.bctrl.BID
import linxcore.recovery.{FlushControl, RecoveryCleanupIntent}
import linxcore.rob.ROBID

class GPRRenameMapQueueEntry(
    val entries: Int,
    val archTagWidth: Int,
    val physTagWidth: Int)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(entries)
  val rid = new ROBID(entries)
  val gid = new ROBID(entries)
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
    val tidWidth: Int)
    extends Bundle {
  private val archTagWidth = math.max(1, log2Ceil(archRegs))
  private val physTagWidth = math.max(1, log2Ceil(physRegs))
  private val freeCountWidth = log2Ceil(physRegs + 1)
  private val mapQCountWidth = log2Ceil(mapQDepth + 1)

  val srcArchTags = Input(Vec(3, UInt(archTagWidth.W)))

  val renameValid = Input(Bool())
  val renameArchTag = Input(UInt(archTagWidth.W))
  val renameBid = Input(new ROBID(entries))
  val renameRid = Input(new ROBID(entries))
  val renameGid = Input(new ROBID(entries))

  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(entries))

  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(entries))

  val cleanup = Input(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))

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
  val mapQFreeCount = Output(UInt(mapQCountWidth.W))
  val checkpointValidMask = Output(UInt(entries.W))
  val renamePtr = Output(new ROBID(entries))
  val smap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val cmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val committedMapQMask = Output(UInt(mapQDepth.W))
  val prunedMapQMask = Output(UInt(mapQDepth.W))
  val releasedPhysMask = Output(UInt(physRegs.W))
  val stateError = Output(Bool())
}

class GPRRenameCheckpoint(
    val entries: Int = 64,
    val archRegs: Int = 24,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0, "rename checkpoint entries must be a power of two")
  require(archRegs == 24, "current scalar GPR rename owner follows LinxCoreModel GPR_COUNT=24")
  require(physRegs > archRegs, "GPR rename requires physical tags beyond the architectural identity tags")
  require(physRegs <= 64, "current bring-up ready/free masks are limited to 64 physical tags")
  require(mapQDepth > 0, "GPR rename mapQ depth must be nonzero")

  private val archTagWidth = math.max(1, log2Ceil(archRegs))
  private val physTagWidth = math.max(1, log2Ceil(physRegs))
  private val freeCountWidth = log2Ceil(physRegs + 1)
  private val mapQCountWidth = log2Ceil(mapQDepth + 1)

  val io = IO(new GPRRenameCheckpointIO(entries, archRegs, physRegs, mapQDepth, bidWidth, stidWidth, peIdWidth, tidWidth))

  private val identityMap = VecInit((0 until archRegs).map(_.U(physTagWidth.W)))
  private val freeInit = VecInit((0 until physRegs).map(idx => (idx >= archRegs).B))

  val smap = RegInit(identityMap)
  val cmap = RegInit(identityMap)
  val checkpointMap = RegInit(VecInit(Seq.fill(entries)(identityMap)))
  val checkpointValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val renamePtr = RegInit(0.U.asTypeOf(new ROBID(entries)))
  val freeList = RegInit(freeInit)
  val mapQ = RegInit(VecInit(Seq.fill(mapQDepth)(
    0.U.asTypeOf(new GPRRenameMapQueueEntry(entries, archTagWidth, physTagWidth)))))

  val mapQValidVec = VecInit(mapQ.map(_.valid))
  val mapQValidMask = mapQValidVec.asUInt
  val freeMask = freeList.asUInt
  val firstFreePhys = PriorityEncoder(freeMask)
  val firstFreeMapQ = PriorityEncoder(~mapQValidMask)
  val hasFreePhys = freeMask.orR
  val hasFreeMapQ = !mapQValidVec.asUInt.andR

  val cleanupValid = io.cleanup.valid && (io.cleanup.renameFlushValid || io.cleanup.renameReplayValid)
  val cleanupTargetsStid0 = io.cleanup.flush.req.stid === 0.U
  val flushFire = io.cleanup.valid && io.cleanup.renameFlushValid && cleanupTargetsStid0
  val replayFire = io.cleanup.valid && !io.cleanup.renameFlushValid && io.cleanup.renameReplayValid && cleanupTargetsStid0
  val commitFire = !cleanupValid && io.commitValid
  val checkpointFire = !cleanupValid && !io.commitValid && io.checkpointValid
  val renameCanFire = !cleanupValid && !io.commitValid && !io.checkpointValid && hasFreePhys && hasFreeMapQ
  val renameAccepted = io.renameValid && renameCanFire

  val restoreBid = ROBID.sub(io.cleanup.flush.req.bid, 1.U)
  val restoreNeeded = ROBID.lessEqual(restoreBid, renamePtr)
  val restoreCheckpointValid = checkpointValid(restoreBid.value)
  val restoreBase = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  restoreBase := Mux(restoreCheckpointValid, checkpointMap(restoreBid.value), cmap)

  val flushPruneVec = Wire(Vec(mapQDepth, Bool()))
  val flushSameBidSurvivorVec = Wire(Vec(mapQDepth, Bool()))
  val commitHitVec = Wire(Vec(mapQDepth, Bool()))
  val commitHitHasLaterSameArch = Wire(Vec(mapQDepth, Bool()))
  for (idx <- 0 until mapQDepth) {
    val e = mapQ(idx)
    flushPruneVec(idx) :=
      e.valid && Mux(
        io.cleanup.flush.baseOnBid,
        ROBID.lessEqual(io.cleanup.flush.req.bid, e.bid),
        FlushControl.lessEqualBidRid(io.cleanup.flush.req.bid, io.cleanup.flush.req.rid, e.bid, e.rid))
    flushSameBidSurvivorVec(idx) :=
      e.valid && !flushPruneVec(idx) && !io.cleanup.flush.baseOnBid && ROBID.equal(io.cleanup.flush.req.bid, e.bid)
    commitHitVec(idx) := e.valid && ROBID.equal(e.bid, io.commitBid)
    val laterSameArch =
      if (idx + 1 >= mapQDepth) {
        false.B
      } else {
        (idx + 1 until mapQDepth)
          .map(j => commitHitVec(j) && (mapQ(j).archTag === e.archTag))
          .reduce(_ || _)
      }
    commitHitHasLaterSameArch(idx) := laterSameArch
  }

  val committedMapQMask = commitHitVec.asUInt
  val prunedMapQMask = flushPruneVec.asUInt

  val commitReleaseVec = Wire(Vec(physRegs, Bool()))
  val flushReleaseVec = Wire(Vec(physRegs, Bool()))
  for (phys <- 0 until physRegs) {
    commitReleaseVec(phys) :=
      (0 until archRegs).map(arch =>
        commitHitVec.asUInt.orR &&
          (cmap(arch) === phys.U) &&
          (0 until mapQDepth).map(idx => commitHitVec(idx) && (mapQ(idx).archTag === arch.U)).reduce(_ || _)
      ).reduce(_ || _) ||
        (0 until mapQDepth).map(idx =>
          commitHitVec(idx) && commitHitHasLaterSameArch(idx) && (mapQ(idx).physTag === phys.U)
        ).reduce(_ || _)
    flushReleaseVec(phys) :=
      (0 until mapQDepth).map(idx => flushPruneVec(idx) && (mapQ(idx).physTag === phys.U)).reduce(_ || _)
  }

  val nextSmap = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  val nextCmap = Wire(Vec(archRegs, UInt(physTagWidth.W)))
  val nextCheckpointMap = Wire(Vec(entries, Vec(archRegs, UInt(physTagWidth.W))))
  val nextCheckpointValid = Wire(Vec(entries, Bool()))
  val nextRenamePtr = Wire(new ROBID(entries))
  val nextFreeList = Wire(Vec(physRegs, Bool()))
  val nextMapQ = Wire(Vec(mapQDepth, new GPRRenameMapQueueEntry(entries, archTagWidth, physTagWidth)))

  nextSmap := smap
  nextCmap := cmap
  nextCheckpointMap := checkpointMap
  nextCheckpointValid := checkpointValid
  nextRenamePtr := renamePtr
  nextFreeList := freeList
  nextMapQ := mapQ

  when(flushFire) {
    when(restoreNeeded) {
      nextRenamePtr := restoreBid
      nextSmap := restoreBase
    }
    for (idx <- 0 until mapQDepth) {
      when(flushPruneVec(idx)) {
        nextMapQ(idx).valid := false.B
        nextFreeList(mapQ(idx).physTag) := true.B
      }
    }
    for (idx <- 0 until mapQDepth) {
      when(flushSameBidSurvivorVec(idx)) {
        nextSmap(mapQ(idx).archTag) := mapQ(idx).physTag
      }
    }
  }.elsewhen(commitFire) {
    for (phys <- 0 until physRegs) {
      when(commitReleaseVec(phys)) {
        nextFreeList(phys) := true.B
      }
    }
    for (idx <- 0 until mapQDepth) {
      when(commitHitVec(idx)) {
        nextMapQ(idx).valid := false.B
      }
    }
    for (arch <- 0 until archRegs) {
      for (idx <- 0 until mapQDepth) {
        when(commitHitVec(idx) && (mapQ(idx).archTag === arch.U)) {
          nextCmap(arch) := mapQ(idx).physTag
        }
      }
    }
  }.elsewhen(checkpointFire) {
    nextCheckpointMap(io.checkpointBid.value) := smap
    nextCheckpointValid(io.checkpointBid.value) := true.B
    nextRenamePtr := io.checkpointBid
  }.elsewhen(renameAccepted) {
    nextSmap(io.renameArchTag) := firstFreePhys
    nextFreeList(firstFreePhys) := false.B
    nextMapQ(firstFreeMapQ).valid := true.B
    nextMapQ(firstFreeMapQ).bid := io.renameBid
    nextMapQ(firstFreeMapQ).rid := io.renameRid
    nextMapQ(firstFreeMapQ).gid := io.renameGid
    nextMapQ(firstFreeMapQ).archTag := io.renameArchTag
    nextMapQ(firstFreeMapQ).physTag := firstFreePhys
  }

  smap := nextSmap
  cmap := nextCmap
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
  io.checkpointAccepted := checkpointFire
  io.commitAccepted := commitFire
  io.cleanupReady := true.B
  io.cleanupFlushApplied := flushFire
  io.cleanupReplayObserved := replayFire
  io.restoreFromCheckpoint := flushFire && restoreNeeded && restoreCheckpointValid
  io.restoreFromCommitMap := flushFire && restoreNeeded && !restoreCheckpointValid
  io.cleanupThreadMismatch := cleanupValid && !cleanupTargetsStid0

  io.freeMask := freeMask
  io.freeCount := PopCount(freeList).asUInt(freeCountWidth - 1, 0)
  io.mapQValidMask := mapQValidMask
  io.mapQFreeCount := (mapQDepth.U - PopCount(mapQValidVec))(mapQCountWidth - 1, 0)
  io.checkpointValidMask := checkpointValid.asUInt
  io.renamePtr := renamePtr
  io.smap := smap
  io.cmap := cmap
  io.committedMapQMask := Mux(commitFire, committedMapQMask, 0.U(mapQDepth.W))
  io.prunedMapQMask := Mux(flushFire, prunedMapQMask, 0.U(mapQDepth.W))
  io.releasedPhysMask := Mux(commitFire, commitReleaseVec.asUInt, 0.U(physRegs.W)) |
    Mux(flushFire, flushReleaseVec.asUInt, 0.U(physRegs.W))
  io.stateError := cleanupValid && !cleanupTargetsStid0
}
