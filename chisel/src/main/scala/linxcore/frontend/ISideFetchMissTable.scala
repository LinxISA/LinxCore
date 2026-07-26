package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, PriorityEncoder, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class ISideFetchMissTableIO(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 4,
    val lineBytes: Int = 64)
    extends Bundle {
  val allocate = Flipped(Decoupled(new ISideF2Result(p, lineBytes)))
  val refill = Flipped(Decoupled(new ISideLineResponse(p, lineBytes)))
  val l1iRefill = Valid(new ISideL1IRefill(p, lineBytes))
  val retry = Decoupled(new ISideFetchRequest(p, lineBytes))
  val innerFlush = Input(new IfuInnerFlush(p))

  val validMask = Output(UInt(entries.W))
  val orphanMask = Output(UInt(entries.W))
  val staleRefill = Output(Bool())
}

class ISideFetchMissTable(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 4,
    val lineBytes: Int = 64)
    extends Module {
  require(entries > 0 && (entries & (entries - 1)) == 0)
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)

  private val indexWidth = math.max(1, log2Ceil(entries))

  val io = IO(new ISideFetchMissTableIO(p, entries, lineBytes))

  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val orphan = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val refilled = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val requests = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ISideFetchRequest(p, lineBytes)))))
  val linePas = RegInit(VecInit(Seq.fill(entries)(0.U(p.pcWidth.W))))

  val freeMask = VecInit(valid.map(v => !v)).asUInt
  val freeValid = freeMask.orR
  val freeIndex = PriorityEncoder(freeMask)

  io.allocate.ready := freeValid && !io.innerFlush.valid
  val allocateFire = io.allocate.valid && io.allocate.ready

  val refillMatch = Wire(Vec(entries, Bool()))
  for (entry <- 0 until entries) {
    refillMatch(entry) :=
      valid(entry) &&
        !refilled(entry) &&
        requests(entry).identity.peId === io.refill.bits.peId &&
        requests(entry).transactionId === io.refill.bits.transactionId &&
        requests(entry).identity.threadId === io.refill.bits.threadId &&
        requests(entry).identity.fetchPacketUid === io.refill.bits.fetchPacketUid &&
        requests(entry).identity.fetchSeq === io.refill.bits.fetchSeq &&
        requests(entry).identity.checkpointId === io.refill.bits.checkpointId &&
        requests(entry).identity.epoch === io.refill.bits.epoch &&
        requests(entry).lineVa === io.refill.bits.lineVa &&
        linePas(entry) === io.refill.bits.linePa
  }
  val refillMatchMask = refillMatch.asUInt
  val refillMatchValid = refillMatchMask.orR
  val refillIndex = PriorityEncoder(refillMatchMask)

  io.refill.ready := refillMatchValid && !io.innerFlush.valid
  val refillFire = io.refill.valid && io.refill.ready
  io.l1iRefill.valid := refillFire
  io.l1iRefill.bits.linePa := io.refill.bits.linePa
  io.l1iRefill.bits.lineData := io.refill.bits.lineData

  val retryCandidates = Wire(Vec(entries, Bool()))
  for (entry <- 0 until entries) {
    retryCandidates(entry) := valid(entry) && refilled(entry) && !orphan(entry)
  }
  val retryMask = retryCandidates.asUInt
  val retryValid = retryMask.orR
  val retryIndex = PriorityEncoder(retryMask)

  io.retry.valid := retryValid && !io.innerFlush.valid
  io.retry.bits := Mux(retryValid, requests(retryIndex), 0.U.asTypeOf(io.retry.bits))
  val retryFire = io.retry.valid && io.retry.ready

  io.validMask := valid.asUInt
  io.orphanMask := orphan.asUInt
  io.staleRefill := io.refill.valid && !refillMatchValid

  when(io.innerFlush.valid) {
    for (entry <- 0 until entries) {
      when(
        valid(entry) &&
          IfuFlushContract.kills(
            requests(entry).identity,
            requests(entry).transactionId,
            io.innerFlush)) {
        when(refilled(entry)) {
          valid(entry) := false.B
          orphan(entry) := false.B
          refilled(entry) := false.B
        }.otherwise {
          orphan(entry) := true.B
        }
      }
    }
  }.otherwise {
    when(allocateFire) {
      assert(io.allocate.bits.status === ISideF2Status.L1IMiss)
      valid(freeIndex) := true.B
      orphan(freeIndex) := false.B
      refilled(freeIndex) := false.B
      requests(freeIndex) := io.allocate.bits.request
      linePas(freeIndex) := io.allocate.bits.linePa
    }

    when(refillFire) {
      when(orphan(refillIndex)) {
        valid(refillIndex) := false.B
        orphan(refillIndex) := false.B
        refilled(refillIndex) := false.B
      }.otherwise {
        refilled(refillIndex) := true.B
      }
    }

    when(retryFire) {
      valid(retryIndex) := false.B
      orphan(retryIndex) := false.B
      refilled(retryIndex) := false.B
    }
  }
}
