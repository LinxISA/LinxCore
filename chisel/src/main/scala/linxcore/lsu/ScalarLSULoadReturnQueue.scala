package linxcore.lsu

import chisel3._
import chisel3.util.{PopCount, RRArbiter, log2Ceil}

import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class LoadReplayReturnLretEntry(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))
  private val sourceTraceParams =
    linxcore.commit.CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = dataWidth)

  val valid = Bool()
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val dst = new LoadReplayDestination(archRegWidth, physRegWidth)
  val sourceTraceValid = Bool()
  val source0 = new linxcore.commit.CommitOperandTrace(sourceTraceParams)
  val source1 = new linxcore.commit.CommitOperandTrace(sourceTraceParams)
  val data = UInt(dataWidth.W)
  val pipeIndex = UInt(returnPipeIndexWidth.W)
  val specWakeup = Bool()
  val stackValid = Bool()
}

class ScalarLSULoadReturnEntry(
    val idEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val dataWidth: Int,
    val sizeWidth: Int,
    val returnPipeCount: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val payload = new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  )
}

class ScalarLSULoadReturnQueueIO(
    val idEntries: Int,
    val depth: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val dataWidth: Int,
    val sizeWidth: Int,
    val returnPipeCount: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val enqueueValid = Input(Bool())
  val enqueue = Input(new ScalarLSULoadReturnEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val dequeueReady = Input(Bool())
  val dequeueValid = Output(Bool())
  val dequeue = Output(new ScalarLSULoadReturnEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val dequeueFire = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val precisePruneMask = Output(UInt(depth.W))
  val precisePruneCount = Output(UInt(countWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByPreciseFlush = Output(Bool())
  val blockedByFull = Output(Bool())
}

class ScalarLSULoadReturnQueue(
    val idEntries: Int = 16,
    val depth: Int = 2,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(idEntries > 1 && (idEntries & (idEntries - 1)) == 0,
    "idEntries must be a power of two greater than one")
  require(depth > 0, "load-return queue depth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val countWidth = log2Ceil(depth + 1)
  val io = IO(new ScalarLSULoadReturnQueueIO(
    idEntries,
    depth,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  private def zeroEntry: ScalarLSULoadReturnEntry = {
    val entry = Wire(chiselTypeOf(io.enqueue))
    entry := 0.U.asTypeOf(entry)
    entry.payload.bid := ROBID.disabled(idEntries)
    entry.payload.gid := ROBID.disabled(idEntries)
    entry.payload.rid := ROBID.disabled(idEntries)
    entry.payload.loadLsId := ROBID.disabled(idEntries)
    entry.payload.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
    entry
  }

  private def toPruneEntry(entry: ScalarLSULoadReturnEntry): STQFlushPruneEntry = {
    val row = Wire(new STQFlushPruneEntry(idEntries, peIdWidth, stidWidth, tidWidth))
    row.valid := entry.payload.valid
    row.status := STQEntryStatus.Wait
    row.peId := entry.peId
    row.stid := entry.stid
    row.tid := entry.tid
    row.bid := entry.payload.bid
    row.gid := entry.payload.gid
    row.lsId := entry.payload.loadLsId
    row.lsIdFull := 0.U
    row
  }

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroEntry)))
  val count = RegInit(0.U(countWidth.W))
  val baseActive = io.enable && !io.flush
  val precisePruneActive = baseActive && io.preciseFlush.req.valid
  val active = baseActive && !precisePruneActive
  val residentHeadValid = count =/= 0.U
  val popResident = active && residentHeadValid && io.dequeueReady
  val enqueueReady = active && ((count =/= depth.U) || popResident)
  val enqueueAccepted = io.enqueueValid && io.enqueue.payload.valid && enqueueReady
  val dequeueValid = active && residentHeadValid
  val dequeueEntry = entries(0)
  val dequeueFire = dequeueValid && io.dequeueReady
  val storeEnqueue = enqueueAccepted

  val precisePruneVec = Wire(Vec(depth, Bool()))
  val removeVec = Wire(Vec(depth, Bool()))
  val keptVec = Wire(Vec(depth, Bool()))
  val keptRank = Wire(Vec(depth, UInt(countWidth.W)))
  val compacted = Wire(Vec(depth, chiselTypeOf(io.enqueue)))

  for (slot <- 0 until depth) {
    val resident = slot.U < count
    precisePruneVec(slot) :=
      resident && precisePruneActive &&
        STQFlushPrune.matchesFlushProjected(io.preciseFlush, toPruneEntry(entries(slot)))
    removeVec(slot) := precisePruneVec(slot) || (popResident && slot.U === 0.U)
    keptVec(slot) := resident && !removeVec(slot)
    keptRank(slot) := (if (slot == 0) 0.U else PopCount((0 until slot).map(keptVec(_))))
  }

  for (dst <- 0 until depth) {
    compacted(dst) := zeroEntry
    for (src <- 0 until depth) {
      when(keptVec(src) && keptRank(src) === dst.U) {
        compacted(dst) := entries(src)
      }
    }
  }

  val keptCount = PopCount(keptVec)
  val nextCount = keptCount + storeEnqueue.asUInt
  val nextEntries = Wire(Vec(depth, chiselTypeOf(io.enqueue)))
  for (dst <- 0 until depth) {
    nextEntries(dst) := compacted(dst)
    when(storeEnqueue && keptCount === dst.U) {
      nextEntries(dst) := io.enqueue
    }
  }

  when(io.flush || !io.enable) {
    entries := VecInit(Seq.fill(depth)(zeroEntry))
    count := 0.U
  }.otherwise {
    entries := nextEntries
    count := nextCount
  }

  io.enqueueReady := enqueueReady
  io.enqueueAccepted := enqueueAccepted
  io.enqueueDropped := io.enqueueValid && io.enqueue.payload.valid && !io.flush && !enqueueReady
  io.dequeueValid := dequeueValid
  io.dequeue := Mux(dequeueValid, dequeueEntry, zeroEntry)
  io.dequeueFire := dequeueFire
  io.count := count
  io.precisePruneMask := precisePruneVec.asUInt
  io.precisePruneCount := PopCount(precisePruneVec)
  io.blockedByDisabled := !io.enable && io.enqueueValid
  io.blockedByFlush := io.enable && io.flush && io.enqueueValid
  io.blockedByPreciseFlush := precisePruneActive && io.enqueueValid
  io.blockedByFull := active && io.enqueueValid && io.enqueue.payload.valid && !enqueueReady
}

class ScalarLSULoadReturnQueueBankIO(
    val idEntries: Int,
    val stidCount: Int,
    val returnPipeCount: Int,
    val queueDepth: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val dataWidth: Int,
    val sizeWidth: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val laneCount = stidCount * returnPipeCount
  private val pipeWidth = math.max(1, log2Ceil(returnPipeCount))
  private val laneWidth = math.max(1, log2Ceil(laneCount))
  private val queueCountWidth = log2Ceil(queueDepth + 1)
  private val totalCountWidth = log2Ceil(laneCount * queueDepth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val enqueueValid = Input(Bool())
  val enqueuePeId = Input(UInt(peIdWidth.W))
  val enqueueStid = Input(UInt(stidWidth.W))
  val enqueueTid = Input(UInt(tidWidth.W))
  val enqueuePipeIndex = Input(UInt(pipeWidth.W))
  val enqueue = Input(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val enqueueReady = Output(Bool())
  val preEnqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val enqueueTargetValid = Output(Bool())
  val drainReady = Input(Bool())
  val drainValid = Output(Bool())
  val drain = Output(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val drainPeId = Output(UInt(peIdWidth.W))
  val drainStid = Output(UInt(stidWidth.W))
  val drainTid = Output(UInt(tidWidth.W))
  val drainPipeIndex = Output(UInt(pipeWidth.W))
  val drainLane = Output(UInt(laneWidth.W))
  val drainFire = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val laneCountState = Output(Vec(laneCount, UInt(queueCountWidth.W)))
  val totalCount = Output(UInt(totalCountWidth.W))
  val precisePruneCount = Output(UInt(totalCountWidth.W))
  val blockedByInvalidStid = Output(Bool())
  val blockedByInvalidPipe = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByPreciseFlush = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByFull = Output(Bool())
  val blockedByDrain = Output(Bool())
}

class ScalarLSULoadReturnQueueBank(
    val idEntries: Int = 16,
    val stidCount: Int = 1,
    val returnPipeCount: Int = 1,
    val queueDepth: Int = 2,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(stidCount > 0, "stidCount must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(queueDepth > 0, "queueDepth must be positive")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "stidCount must fit stidWidth")

  private val laneCount = stidCount * returnPipeCount
  private val pipeWidth = math.max(1, log2Ceil(returnPipeCount))
  private val laneWidth = math.max(1, log2Ceil(laneCount))
  val io = IO(new ScalarLSULoadReturnQueueBankIO(
    idEntries,
    stidCount,
    returnPipeCount,
    queueDepth,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  val queues = Seq.fill(laneCount)(Module(new ScalarLSULoadReturnQueue(
    idEntries,
    queueDepth,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  )))
  val arbiter = Module(new RRArbiter(chiselTypeOf(queues.head.io.dequeue), laneCount))
  val stidInRange = io.enqueueStid < stidCount.U
  val pipeInRange = io.enqueuePipeIndex < returnPipeCount.U
  val targetValid = stidInRange && pipeInRange
  val targetLane = Wire(UInt(laneWidth.W))
  targetLane := io.enqueueStid * returnPipeCount.U + io.enqueuePipeIndex

  for ((queue, lane) <- queues.zipWithIndex) {
    queue.io.enable := io.enable
    queue.io.flush := io.flush
    queue.io.preciseFlush := io.preciseFlush
    queue.io.enqueueValid := io.enqueueValid && targetValid && targetLane === lane.U
    queue.io.enqueue.peId := io.enqueuePeId
    queue.io.enqueue.stid := io.enqueueStid
    queue.io.enqueue.tid := io.enqueueTid
    queue.io.enqueue.payload := io.enqueue
    queue.io.enqueue.payload.pipeIndex := io.enqueuePipeIndex
    queue.io.dequeueReady := arbiter.io.in(lane).ready
    arbiter.io.in(lane).valid := queue.io.dequeueValid
    arbiter.io.in(lane).bits := queue.io.dequeue
    io.laneCountState(lane) := queue.io.count
  }

  arbiter.io.out.ready := io.drainReady
  val laneReady = VecInit(queues.map(_.io.enqueueReady))
  val selectedReady =
    if (laneCount == 1) laneReady(0) else Mux(targetValid, laneReady(targetLane), false.B)
  val selectedStidAnyReady = VecInit((0 until stidCount).map { stid =>
    (0 until returnPipeCount).map { pipe =>
      queues(stid * returnPipeCount + pipe).io.enqueueReady
    }.reduce(_ || _)
  })
  val preEnqueueReady =
    if (stidCount == 1) selectedStidAnyReady(0)
    else Mux(
      stidInRange,
      selectedStidAnyReady(io.enqueueStid(log2Ceil(stidCount) - 1, 0)),
      false.B)
  val totalCount = queues.map(_.io.count).reduce(_ +& _)
  val precisePruneCount = queues.map(_.io.precisePruneCount).reduce(_ +& _)
  val allLanesFull = queues.map(_.io.count === queueDepth.U).reduce(_ && _)
  val selectedLaneBlocked =
    io.enable && !io.flush && !io.preciseFlush.req.valid && io.enqueueValid &&
      io.enqueue.valid && targetValid && !selectedReady

  io.enqueueTargetValid := targetValid
  io.preEnqueueReady :=
    io.enable && !io.flush && !io.preciseFlush.req.valid && stidInRange && preEnqueueReady
  io.enqueueReady := io.enable && !io.flush && targetValid && selectedReady
  io.enqueueAccepted := io.enqueueValid && io.enqueue.valid && io.enqueueReady
  io.enqueueDropped := io.enqueueValid && io.enqueue.valid && !io.flush && !io.enqueueReady
  io.drainValid := arbiter.io.out.valid
  io.drain := arbiter.io.out.bits.payload
  io.drainPeId := arbiter.io.out.bits.peId
  io.drainStid := arbiter.io.out.bits.stid
  io.drainTid := arbiter.io.out.bits.tid
  io.drainPipeIndex := arbiter.io.out.bits.payload.pipeIndex
  io.drainLane := arbiter.io.chosen
  io.drainFire := arbiter.io.out.fire
  io.pending := totalCount =/= 0.U
  io.empty := totalCount === 0.U
  io.totalCount := totalCount
  io.precisePruneCount := precisePruneCount
  io.full := allLanesFull
  io.blockedByInvalidStid := io.enqueueValid && !stidInRange
  io.blockedByInvalidPipe := io.enqueueValid && stidInRange && !pipeInRange
  io.blockedByDisabled := !io.enable && io.enqueueValid
  io.blockedByFlush := io.enable && io.flush && io.enqueueValid
  io.blockedByPreciseFlush := io.enable && !io.flush && io.preciseFlush.req.valid && io.enqueueValid
  io.blockedByNoPayload := io.enqueueValid && !io.enqueue.valid
  io.blockedByFull := selectedLaneBlocked
  io.blockedByDrain := io.drainValid && !io.drainReady
}
