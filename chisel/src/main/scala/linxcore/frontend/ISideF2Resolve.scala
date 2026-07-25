package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, log2Ceil}
import linxcore.common.InterfaceParams

class ISideF2ResolveIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096)
    extends Bundle {
  val translation = Flipped(Decoupled(new ISideTranslationResult(p, lineBytes, pageBytes)))
  val cacheCandidate = Flipped(Decoupled(new ISideCacheCandidate(p, lineBytes)))
  val result = Decoupled(new ISideF2Result(p, lineBytes))
  val innerFlush = Decoupled(new IfuInnerFlush(p))
  val externalFlush = Input(new IfuInnerFlush(p))

  val joined = Output(Bool())
  val identityMatch = Output(Bool())
}

class ISideF2Resolve(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096)
    extends Module {
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)
  require(pageBytes > lineBytes && (pageBytes & (pageBytes - 1)) == 0)

  private val pageOffsetBits = log2Ceil(pageBytes)
  private val lineOffsetBits = log2Ceil(lineBytes)

  val io = IO(new ISideF2ResolveIO(p, lineBytes, pageBytes))

  val translationValid = RegInit(false.B)
  val translation = RegInit(0.U.asTypeOf(new ISideTranslationResult(p, lineBytes, pageBytes)))
  val cacheValid = RegInit(false.B)
  val cache = RegInit(0.U.asTypeOf(new ISideCacheCandidate(p, lineBytes)))

  val killTranslation =
    translationValid &&
      IfuFlushContract.kills(
        translation.request.identity,
        translation.request.transactionId,
        io.externalFlush)
  val killCache =
    cacheValid &&
      IfuFlushContract.kills(
        cache.request.identity,
        cache.request.transactionId,
        io.externalFlush)

  val joined = translationValid && cacheValid
  val identityMatch =
    translation.request.identity.peId === cache.request.identity.peId &&
      translation.request.transactionId === cache.request.transactionId &&
      translation.request.identity.threadId === cache.request.identity.threadId &&
      translation.request.identity.fetchPacketUid === cache.request.identity.fetchPacketUid &&
      translation.request.identity.fetchSeq === cache.request.identity.fetchSeq &&
      translation.request.identity.checkpointId === cache.request.identity.checkpointId &&
      translation.request.identity.epoch === cache.request.identity.epoch &&
      translation.request.pc === cache.request.pc &&
      translation.request.lineVa === cache.request.lineVa

  val pageOffset = translation.request.lineVa(pageOffsetBits - 1, 0)
  val physicalAddress = Cat(translation.ppn, pageOffset)
  val physicalLine = Cat(
    physicalAddress(p.pcWidth - 1, lineOffsetBits),
    0.U(lineOffsetBits.W))
  val physicalTag = physicalAddress(p.pcWidth - 1, lineOffsetBits)
  val cacheHit =
    cache.candidateValid &&
      cache.physicalTag === physicalTag

  val status = WireDefault(ISideF2Status.Stale)
  when(identityMatch) {
    when(!translation.hit) {
      status := ISideF2Status.ItlbMiss
    }.elsewhen(translation.accessFault) {
      status := ISideF2Status.AccessFault
    }.elsewhen(!cacheHit) {
      status := ISideF2Status.L1IMiss
    }.otherwise {
      status := ISideF2Status.Hit
    }
  }

  val requiresInnerFlush = joined && identityMatch && status === ISideF2Status.ItlbMiss
  val publishReady = io.result.ready && (!requiresInnerFlush || io.innerFlush.ready)

  io.result.valid := joined && (!requiresInnerFlush || io.innerFlush.ready)
  io.result.bits := 0.U.asTypeOf(io.result.bits)
  io.result.bits.request := translation.request
  io.result.bits.status := status
  io.result.bits.linePa := Mux(identityMatch && translation.hit, physicalLine, 0.U)
  io.result.bits.lineData := Mux(status === ISideF2Status.Hit, cache.lineData, 0.U)

  io.innerFlush.valid := requiresInnerFlush && io.result.ready
  io.innerFlush.bits := 0.U.asTypeOf(io.innerFlush.bits)
  io.innerFlush.bits.valid := requiresInnerFlush
  io.innerFlush.bits.peId := translation.request.identity.peId
  io.innerFlush.bits.threadId := translation.request.identity.threadId
  io.innerFlush.bits.transactionId := translation.request.transactionId
  io.innerFlush.bits.fetchSeq := translation.request.identity.fetchSeq
  io.innerFlush.bits.oldEpoch := translation.request.identity.epoch
  io.innerFlush.bits.restartPc := translation.request.pc
  io.innerFlush.bits.checkpointId := translation.request.identity.checkpointId
  io.innerFlush.bits.newEpoch := translation.request.identity.epoch + 1.U
  io.innerFlush.bits.reason := IfuInnerFlushReason.ItlbMiss
  io.innerFlush.bits.scope := IfuPruneScope.KillTriggerAndYounger

  io.joined := joined
  io.identityMatch := identityMatch

  val resultFire = io.result.valid && io.result.ready
  io.translation.ready := !translationValid || killTranslation || resultFire
  io.cacheCandidate.ready := !cacheValid || killCache || resultFire

  when(killTranslation || resultFire) {
    translationValid := false.B
  }
  when(killCache || resultFire) {
    cacheValid := false.B
  }
  when(io.translation.fire) {
    translationValid := true.B
    translation := io.translation.bits
  }
  when(io.cacheCandidate.fire) {
    cacheValid := true.B
    cache := io.cacheCandidate.bits
  }
}
