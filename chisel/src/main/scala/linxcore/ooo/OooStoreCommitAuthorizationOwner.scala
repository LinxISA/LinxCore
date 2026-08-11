package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.{CommitTxn, StoreCommitAuthorizationTxn}

class OooStoreCommitAuthorizationOwnerIO(val p: CoreParams) extends Bundle {
  val commit = Flipped(Valid(new CommitTxn(p)))
  val canAccept = Output(Bool())
  val applied = Input(Bool())
  val storeCommit = Decoupled(new StoreCommitAuthorizationTxn(p))
  val drained = Output(Bool())
}

/** Atomically captures every store beat on the architectural commit fire, then
  * serializes the accepted tokens to LSU. Before `applied`, this owner has no
  * externally visible store side effect. Once captured, recovery cannot cancel
  * or regenerate a committed token.
  */
class OooStoreCommitAuthorizationOwner(val p: CoreParams) extends Module {
  val io = IO(new OooStoreCommitAuthorizationOwnerIO(p))
  private val entries = p.ooo.storeCommitBufferEntries
  private val candidateSlots = p.widths.retireWidth *
    p.maxMemoryRequestsPerInstruction
  private val indexWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val payloads = Wire(Vec(candidateSlots,
    new StoreCommitAuthorizationTxn(p)))
  val candidates = Wire(Vec(candidateSlots, Bool()))
  for (slot <- 0 until candidateSlots) {
    val lane = slot / p.maxMemoryRequestsPerInstruction
    val beat = slot % p.maxMemoryRequestsPerInstruction
    val entry = io.commit.bits.entries(lane)
    candidates(slot) := io.commit.valid && lane.U < io.commit.bits.count &&
      entry.memoryValid && entry.memoryStore &&
      beat.U < entry.memoryOrder.requestCount
    payloads(slot) := 0.U.asTypeOf(payloads(slot))
    payloads(slot).rob := entry.rob
    payloads(slot).transaction := entry.memory.transaction
    payloads(slot).logicalFirstLsid := entry.memoryOrder.firstLsid
    payloads(slot).logicalFirstStoreId := entry.memoryOrder.firstSid
    payloads(slot).requestCount := entry.memoryOrder.requestCount
    payloads(slot).beat := beat.U
  }

  val buffer = Reg(Vec(entries, new StoreCommitAuthorizationTxn(p)))
  val head = RegInit(0.U(indexWidth.W))
  val tail = RegInit(0.U(indexWidth.W))
  val count = RegInit(0.U(countWidth.W))
  val candidateCount = PopCount(candidates)

  io.canAccept := count +& candidateCount <= entries.U
  io.storeCommit.valid := count =/= 0.U
  io.storeCommit.bits := buffer(head)
  io.drained := count === 0.U

  when(io.applied) {
    assert(io.commit.valid,
      "store-token capture requires the matching retained commit transaction")
    assert(io.canAccept,
      "architectural commit requires capacity for its complete store-token batch")
    for (slot <- 0 until candidateSlots) {
      when(candidates(slot)) {
        val rank = if (slot == 0) 0.U else PopCount(candidates.take(slot))
        buffer((tail + rank)(indexWidth - 1, 0)) := payloads(slot)
      }
    }
    tail := (tail + candidateCount)(indexWidth - 1, 0)
  }
  when(io.storeCommit.fire) {
    head := head + 1.U
  }

  count := count + Mux(io.applied, candidateCount, 0.U) -
    io.storeCommit.fire.asUInt
}
