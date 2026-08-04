package linxcore.lsu

import chisel3._
import chisel3.util.{PopCount, PriorityEncoder, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface.MemoryTransactionIdentity

class LSULowerTransactionRecoveryIO(
    val p: CoreParams,
    val lanes: Int,
    val entries: Int)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val prepareFire = Input(Bool())
  val applyFire = Input(Bool())
  val abortFire = Input(Bool())
  val requestFire = Input(Vec(lanes, Bool()))
  val requestIdentity = Input(Vec(lanes,
    new MemoryTransactionIdentity(p)))
  val responseFire = Input(Vec(lanes, Bool()))
  val responseIdentity = Input(Vec(lanes,
    new MemoryTransactionIdentity(p)))

  val fenced = Output(Bool())
  val quiescent = Output(Bool())
  val outstandingCount = Output(UInt(countWidth.W))
  val staleResponse = Output(Bool())
  val protocolError = Output(Bool())
}

/** Exact lower-memory transaction ledger used by LSU recovery.
  *
  * Prepare fences new request fire without deleting accepted transactions.
  * Only a full value-plus-generation response match drains a ledger row;
  * therefore an Apply can never publish quiescence while an old response can
  * still mutate MissQ/refill/store-drain state.
  */
class LSULowerTransactionRecovery(
    val p: CoreParams,
    val lanes: Int,
    val entries: Int)
    extends Module {
  require(lanes > 0)
  require(entries >= lanes)
  require(entries % lanes == 0)

  val io = IO(new LSULowerTransactionRecoveryIO(p, lanes, entries))

  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val identity = Reg(Vec(entries, new MemoryTransactionIdentity(p)))
  val fenced = RegInit(false.B)

  when(io.prepareFire) { fenced := true.B }
  when(io.applyFire || io.abortFire) { fenced := false.B }

  private val entriesPerLane = entries / lanes
  private val entryIndexWidth = log2Ceil(entries)
  val duplicateRequest = Wire(Vec(lanes, Bool()))
  val allocationFailure = Wire(Vec(lanes, Bool()))
  val staleResponse = Wire(Vec(lanes, Bool()))
  duplicateRequest := VecInit(Seq.fill(lanes)(false.B))
  allocationFailure := VecInit(Seq.fill(lanes)(false.B))
  staleResponse := VecInit(Seq.fill(lanes)(false.B))

  for (lane <- 0 until lanes) {
    val base = lane * entriesPerLane
    val responseMatches = VecInit((0 until entriesPerLane).map { offset =>
      val index = base + offset
      valid(index) &&
        identity(index).value === io.responseIdentity(lane).value &&
        identity(index).generation ===
          io.responseIdentity(lane).generation
    })
    val responseMatchCount = PopCount(responseMatches)
    val sameCycleCompletion = io.requestFire(lane) &&
      io.responseFire(lane) && responseMatchCount === 0.U &&
      io.requestIdentity(lane).value === io.responseIdentity(lane).value &&
      io.requestIdentity(lane).generation ===
        io.responseIdentity(lane).generation
    when(io.responseFire(lane)) {
      when(responseMatchCount === 1.U) {
        val index = base.U(entryIndexWidth.W) +
          PriorityEncoder(responseMatches).pad(entryIndexWidth)
        valid(index) := false.B
      }.elsewhen(!sameCycleCompletion) {
        staleResponse(lane) := true.B
      }
    }

    val requestMatches = VecInit((0 until entriesPerLane).map { offset =>
      val index = base + offset
      valid(index) &&
        identity(index).value === io.requestIdentity(lane).value &&
        identity(index).generation === io.requestIdentity(lane).generation
    })
    val free = VecInit((0 until entriesPerLane).map(offset =>
      !valid(base + offset)))
    when(io.requestFire(lane) && !sameCycleCompletion) {
      when(requestMatches.asUInt.orR) {
        duplicateRequest(lane) := true.B
      }.elsewhen(free.asUInt.orR) {
        val index = base.U(entryIndexWidth.W) +
          PriorityEncoder(free).pad(entryIndexWidth)
        valid(index) := true.B
        identity(index) := io.requestIdentity(lane)
      }.otherwise {
        allocationFailure(lane) := true.B
      }
    }
  }

  io.fenced := fenced
  io.quiescent := !valid.asUInt.orR
  io.outstandingCount := PopCount(valid)
  io.staleResponse := staleResponse.asUInt.orR
  io.protocolError := duplicateRequest.asUInt.orR ||
    allocationFailure.asUInt.orR
}
