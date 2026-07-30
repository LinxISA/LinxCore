package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}

object LoadStructuralBlockDisposition extends ChiselEnum {
  val WaitStore, RetrySnapshot, Unsupported = Value
}

object LoadStructuralBlockReason {
  val Width = 8
  val UnknownOlder = 0
  val StaleSnapshot = 1
  val FullLsIdMissing = 2
  val FullLsIdAmbiguous = 3
  val CrossLineStore = 4
  val QueryIdentityInvalid = 5
  val LoadCrossesLine = 6
  val InvalidStructuralShape = 7
}

/** Compact retry transaction produced from one retained structural forwarding
  * result.  It carries only lifecycle identity and the optional exact store
  * wakeup key; forwarding data remains owned by the normal E3/E4 path.
  */
class LoadStructuralBlockRetry(
    val robEntries: Int,
    val stqEntries: Int,
    val pcWidth: Int = 64,
    val lsidWidth: Int = 32) extends Bundle {
  val loadId = new LoadCanonicalRowIdentity
  val current = new LoadAttemptIdentity
  val next = new LoadAttemptIdentity
  val returnPipeIndex = UInt(STQLoadForwardQuery.ReturnPipeIndexWidth.W)
  val waitStore = Bool()
  val waitStoreInfo = new LoadStoreForwardWait(
    robEntries, stqEntries, pcWidth, lsidWidth)
}

class LoadStructuralBlockPolicyIO(
    val robEntries: Int,
    val stqEntries: Int,
    val addrWidth: Int,
    val stidWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val lsidWidth: Int,
    val tokenWidth: Int) extends Bundle {
  val hardFlush = Input(Bool())
  val recoveryKill = Input(Bool())
  val recoveryFire = Input(Bool())
  val hardBlock = Flipped(Decoupled(new STQLoadForwardResponse(
    robEntries, stqEntries, addrWidth, stidWidth, pcWidth, lineBytes,
    lsidWidth, tokenWidth)))
  val retry = Decoupled(new LoadStructuralBlockRetry(
    robEntries, stqEntries, pcWidth, lsidWidth))

  val pending = Output(Bool())
  val unsupported = Output(Bool())
  val disposition = Output(LoadStructuralBlockDisposition())
  val reason = Output(UInt(LoadStructuralBlockReason.Width.W))
  val loadId = Output(new LoadCanonicalRowIdentity)
  val attempt = Output(new LoadAttemptIdentity)
  val recoveryReady = Output(Bool())
  val empty = Output(Bool())
  val protocolError = Output(Bool())
}

/** Sole retained consumer of structural STQ forwarding uncertainty.
  *
  * Retryable records can leave only through the canonical OOO/LIQ rebind
  * transaction. Unsupported records remain fail closed until hard flush or an
  * exact typed recovery proves that their owning load is killed.
  */
class LoadStructuralBlockPolicy(
    val robEntries: Int = 64,
    val liqEntries: Int = 16,
    val stqEntries: Int = 16,
    val addrWidth: Int = 64,
    val stidWidth: Int = 8,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val tokenWidth: Int = 64) extends Module {
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "structural-block policy ROB projection must be a power of two")
  LoadCanonicalRowIdentity.requireBridgeFits(liqEntries)
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "structural-block policy STQ capacity must be a power of two")
  require(log2Ceil(stqEntries) <= LoadAttemptIdentity.IndexWidth,
    "structural-block store index must fit canonical identity protocol")

  val io = IO(new LoadStructuralBlockPolicyIO(
    robEntries, stqEntries, addrWidth, stidWidth, pcWidth, lineBytes,
    lsidWidth, tokenWidth))

  val residentValid = RegInit(false.B)
  val residentRetry = Reg(new LoadStructuralBlockRetry(
    robEntries, stqEntries, pcWidth, lsidWidth))
  val residentDisposition = RegInit(LoadStructuralBlockDisposition.Unsupported)
  val residentReason = RegInit(0.U(LoadStructuralBlockReason.Width.W))

  val response = io.hardBlock.bits
  val unknownOlder = response.unknownOlderMask.orR
  val staleSnapshot = response.staleSnapshotMask.orR
  val fullLsIdMissing = response.fullLsIdMissingMask.orR
  val fullLsIdAmbiguous = response.fullLsIdAmbiguousMask.orR
  val crossLineStore = response.crossLineStoreMask.orR
  val queryIdentityInvalid = response.queryIdentityInvalid ||
    !response.query.loadId.valid ||
    !LoadCanonicalRowIdentity.wellFormed(response.query.loadId, liqEntries) ||
    !response.query.attempt.valid ||
    !LoadAttemptIdentity.wellFormed(response.query.attempt)
  val unknownWaitExact = response.unknownWaitStore.valid &&
    response.unknownWaitStore.storeId.valid &&
    response.unknownWaitStore.storeLsId.valid &&
    response.unknownWaitStore.storeLsIdFullValid
  val invalidStructuralShape =
    (unknownOlder && !unknownWaitExact) ||
      !(unknownOlder || staleSnapshot || fullLsIdMissing ||
        fullLsIdAmbiguous || crossLineStore || response.loadCrossesLine ||
        queryIdentityInvalid)
  val unsupportedNow = fullLsIdMissing || fullLsIdAmbiguous ||
    crossLineStore || response.loadCrossesLine || queryIdentityInvalid ||
    invalidStructuralShape
  val dispositionNow = Mux(unsupportedNow,
    LoadStructuralBlockDisposition.Unsupported,
    Mux(staleSnapshot,
      LoadStructuralBlockDisposition.RetrySnapshot,
      LoadStructuralBlockDisposition.WaitStore))

  val reasonNow = Wire(Vec(LoadStructuralBlockReason.Width, Bool()))
  reasonNow := VecInit(Seq.fill(LoadStructuralBlockReason.Width)(false.B))
  reasonNow(LoadStructuralBlockReason.UnknownOlder) := unknownOlder
  reasonNow(LoadStructuralBlockReason.StaleSnapshot) := staleSnapshot
  reasonNow(LoadStructuralBlockReason.FullLsIdMissing) := fullLsIdMissing
  reasonNow(LoadStructuralBlockReason.FullLsIdAmbiguous) := fullLsIdAmbiguous
  reasonNow(LoadStructuralBlockReason.CrossLineStore) := crossLineStore
  reasonNow(LoadStructuralBlockReason.QueryIdentityInvalid) :=
    queryIdentityInvalid
  reasonNow(LoadStructuralBlockReason.LoadCrossesLine) :=
    response.loadCrossesLine
  reasonNow(LoadStructuralBlockReason.InvalidStructuralShape) :=
    invalidStructuralShape

  io.hardBlock.ready := !residentValid && !io.hardFlush && !io.recoveryFire
  io.retry.valid := residentValid &&
    (residentDisposition =/= LoadStructuralBlockDisposition.Unsupported) &&
    !io.hardFlush && !io.recoveryFire
  io.retry.bits := residentRetry

  when(io.hardFlush) {
    residentValid := false.B
  }.elsewhen(io.recoveryFire && io.recoveryKill) {
    residentValid := false.B
  }.elsewhen(io.retry.fire) {
    residentValid := false.B
  }.elsewhen(io.hardBlock.fire) {
    residentValid := true.B
    residentDisposition := dispositionNow
    residentReason := reasonNow.asUInt
    residentRetry := 0.U.asTypeOf(residentRetry)
    residentRetry.loadId := response.query.loadId
    residentRetry.current := response.query.attempt
    residentRetry.next := response.query.attempt
    residentRetry.next.generation := response.query.attempt.generation +% 1.U
    residentRetry.returnPipeIndex := response.query.returnPipeIndex
    residentRetry.waitStore :=
      dispositionNow === LoadStructuralBlockDisposition.WaitStore
    when(dispositionNow === LoadStructuralBlockDisposition.WaitStore) {
      residentRetry.waitStoreInfo := response.unknownWaitStore
    }
  }

  io.pending := residentValid
  io.unsupported := residentValid &&
    residentDisposition === LoadStructuralBlockDisposition.Unsupported
  io.disposition := residentDisposition
  io.reason := residentReason
  io.loadId := residentRetry.loadId
  io.attempt := residentRetry.current
  io.recoveryReady := !residentValid || io.recoveryKill
  io.empty := !residentValid
  io.protocolError := io.unsupported

  when(io.retry.valid) {
    assert(LoadCanonicalRowIdentity.wellFormed(
      io.retry.bits.loadId, liqEntries),
      "structural retry must retain a well-formed canonical load ID")
    assert(LoadAttemptIdentity.wellFormed(io.retry.bits.current) &&
      LoadAttemptIdentity.wellFormed(io.retry.bits.next),
      "structural retry attempts must remain well formed")
    assert(io.retry.bits.next.producer.asUInt ===
      io.retry.bits.current.producer.asUInt &&
      io.retry.bits.next.generation ===
        (io.retry.bits.current.generation +% 1.U),
      "structural retry must preserve producer and increment generation once")
    assert(!io.retry.bits.waitStore ||
      (io.retry.bits.waitStoreInfo.valid &&
        io.retry.bits.waitStoreInfo.storeId.valid &&
        io.retry.bits.waitStoreInfo.storeLsId.valid &&
        io.retry.bits.waitStoreInfo.storeLsIdFullValid),
      "wait-store structural retry requires an exact full-LSID wakeup key")
  }
  assert(!io.unsupported || !io.retry.valid,
    "unsupported structural state must never dequeue as an ordinary retry")
}
