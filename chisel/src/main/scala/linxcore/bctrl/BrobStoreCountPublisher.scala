package linxcore.bctrl

import chisel3._
import chisel3.util.{Mux1H, log2Ceil}

class BrobStoreCountPublisherIO(
    val entries: Int,
    val bidWidth: Int,
    val stidWidth: Int,
    val stidCount: Int,
    val storeCountWidth: Int)
    extends Bundle {
  private val liveCountWidth = log2Ceil(entries + 1)

  val scalarValid = Input(Bool())
  val scalarBid = Input(UInt(bidWidth.W))
  val scalarStid = Input(UInt(stidWidth.W))
  val scalarInputAccepted = Output(Bool())
  val scalarInputCanceled = Output(Bool())
  val scalarPendingCanceled = Output(Bool())
  val scalarOverflow = Output(Bool())

  val explicitValid = Input(Bool())
  val explicitReady = Output(Bool())
  val explicitBid = Input(UInt(bidWidth.W))
  val explicitStid = Input(UInt(stidWidth.W))
  val explicitValue = Input(UInt(storeCountWidth.W))
  val explicitInputAccepted = Output(Bool())
  val explicitInputCanceled = Output(Bool())
  val explicitPendingCanceled = Output(Bool())
  val explicitBlockedByLiveWindow = Output(Bool())

  val orderHeadBid = Input(Vec(stidCount, UInt(bidWidth.W)))
  val orderLiveCount = Input(Vec(stidCount, UInt(liveCountWidth.W)))
  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(stidWidth.W))
  val recoveryFirstKilledBid = Input(UInt(bidWidth.W))

  val publishValid = Output(Bool())
  val publishBid = Output(UInt(bidWidth.W))
  val publishStid = Output(UInt(stidWidth.W))
  val publishUseValue = Output(Bool())
  val publishValue = Output(UInt(storeCountWidth.W))
  val sinkAccepted = Input(Bool())
  val sinkDuplicateMatch = Input(Bool())
  val sinkConflict = Input(Bool())

  val scalarPublishFire = Output(Bool())
  val explicitPublishFire = Output(Bool())
  val scalarRedundantWithExplicit = Output(Bool())
  val sameBlockCollision = Output(Bool())
  val differentBlockCollision = Output(Bool())
  val sinkConflictHeld = Output(Bool())
  val scalarPending = Output(Bool())
  val explicitPending = Output(Bool())
}

/** Retains scalar and authoritative explicit block store-count publications. */
class BrobStoreCountPublisher(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val stidCount: Int = 1,
    val storeCountWidth: Int = 64)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "BROB store-count publisher entries must be a power of two")
  require(bidWidth > log2Ceil(entries), "BROB store-count BID must include uniqueness bits")
  require(stidCount > 0, "BROB store-count publisher must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "BROB store-count STID count must fit")
  require(storeCountWidth > 0, "BROB store count width must be positive")

  private val liveCountWidth = log2Ceil(entries + 1)
  val io = IO(new BrobStoreCountPublisherIO(entries, bidWidth, stidWidth, stidCount, storeCountWidth))

  val scalarPendingValid = RegInit(false.B)
  val scalarPendingBid = Reg(UInt(bidWidth.W))
  val scalarPendingStid = Reg(UInt(stidWidth.W))
  val explicitPendingValid = RegInit(false.B)
  val explicitPendingBid = Reg(UInt(bidWidth.W))
  val explicitPendingStid = Reg(UInt(stidWidth.W))
  val explicitPendingValue = Reg(UInt(storeCountWidth.W))

  private def laneMatch(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(lane => stid === lane.U(stidWidth.W)))

  private def selectedHead(stid: UInt): UInt = {
    val matches = laneMatch(stid)
    Mux(matches.asUInt.orR, Mux1H(matches, io.orderHeadBid), 0.U)
  }

  private def selectedLiveCount(stid: UInt): UInt = {
    val matches = laneMatch(stid)
    Mux(matches.asUInt.orR, Mux1H(matches, io.orderLiveCount), 0.U)
  }

  private def isLive(stid: UInt, bid: UInt): Bool = {
    val matches = laneMatch(stid)
    val distance = bid - selectedHead(stid)
    matches.asUInt.orR && distance < selectedLiveCount(stid).pad(bidWidth)
  }

  private def killedByRecovery(stid: UInt, bid: UInt): Bool = {
    val matches = laneMatch(stid)
    val candidateDistance = bid - selectedHead(stid)
    val firstKilledDistance = io.recoveryFirstKilledBid - selectedHead(stid)
    io.recoveryValid && matches.asUInt.orR && io.recoveryStid === stid &&
      candidateDistance < selectedLiveCount(stid).pad(bidWidth) &&
      candidateDistance >= firstKilledDistance
  }

  val scalarPendingKilled = scalarPendingValid && killedByRecovery(scalarPendingStid, scalarPendingBid)
  val explicitPendingKilled = explicitPendingValid && killedByRecovery(explicitPendingStid, explicitPendingBid)
  val pendingSameBlock = scalarPendingValid && explicitPendingValid &&
    scalarPendingStid === explicitPendingStid && scalarPendingBid === explicitPendingBid
  val pendingDifferentBlock = scalarPendingValid && explicitPendingValid && !pendingSameBlock

  val selectExplicit = explicitPendingValid && !explicitPendingKilled &&
    (!scalarPendingValid || scalarPendingKilled || pendingSameBlock)
  val selectScalar = scalarPendingValid && !scalarPendingKilled && !selectExplicit
  val sinkTerminal = io.sinkAccepted || io.sinkDuplicateMatch
  val explicitTerminal = selectExplicit && sinkTerminal
  val scalarTerminal = selectScalar && sinkTerminal
  val scalarRedundant = pendingSameBlock && selectExplicit && sinkTerminal
  val scalarWillClear = scalarPendingKilled || scalarTerminal || scalarRedundant
  val explicitWillClear = explicitPendingKilled || explicitTerminal

  val incomingScalarLive = isLive(io.scalarStid, io.scalarBid)
  val incomingScalarKilled = io.scalarValid && killedByRecovery(io.scalarStid, io.scalarBid)
  val scalarCapacity = !scalarPendingValid || scalarWillClear
  val scalarInputAccepted = io.scalarValid && scalarCapacity && (incomingScalarLive || incomingScalarKilled)
  val scalarInputCanceled = scalarInputAccepted && incomingScalarKilled
  val scalarCapture = scalarInputAccepted && !incomingScalarKilled

  val incomingExplicitLive = isLive(io.explicitStid, io.explicitBid)
  val incomingExplicitKilled = io.explicitValid && killedByRecovery(io.explicitStid, io.explicitBid)
  val explicitCapacity = !explicitPendingValid || explicitWillClear
  val explicitReady = explicitCapacity && (incomingExplicitLive || incomingExplicitKilled)
  val explicitInputAccepted = io.explicitValid && explicitReady
  val explicitInputCanceled = explicitInputAccepted && incomingExplicitKilled
  val explicitCapture = explicitInputAccepted && !incomingExplicitKilled

  io.publishValid := selectExplicit || selectScalar
  io.publishBid := Mux(selectExplicit, explicitPendingBid, scalarPendingBid)
  io.publishStid := Mux(selectExplicit, explicitPendingStid, scalarPendingStid)
  io.publishUseValue := selectExplicit
  io.publishValue := Mux(selectExplicit, explicitPendingValue, 0.U)
  io.scalarInputAccepted := scalarInputAccepted
  io.scalarInputCanceled := scalarInputCanceled
  io.scalarPendingCanceled := scalarPendingKilled
  io.scalarOverflow := io.scalarValid && !scalarInputAccepted
  io.explicitReady := explicitReady
  io.explicitInputAccepted := explicitInputAccepted
  io.explicitInputCanceled := explicitInputCanceled
  io.explicitPendingCanceled := explicitPendingKilled
  io.explicitBlockedByLiveWindow := io.explicitValid && !incomingExplicitLive && !incomingExplicitKilled
  io.scalarPublishFire := scalarTerminal
  io.explicitPublishFire := explicitTerminal
  io.scalarRedundantWithExplicit := scalarRedundant
  io.sameBlockCollision := pendingSameBlock
  io.differentBlockCollision := pendingDifferentBlock
  io.sinkConflictHeld := io.publishValid && io.sinkConflict
  io.scalarPending := scalarPendingValid
  io.explicitPending := explicitPendingValid

  when(scalarCapture) {
    scalarPendingValid := true.B
    scalarPendingBid := io.scalarBid
    scalarPendingStid := io.scalarStid
  }.elsewhen(scalarWillClear) {
    scalarPendingValid := false.B
  }

  when(explicitCapture) {
    explicitPendingValid := true.B
    explicitPendingBid := io.explicitBid
    explicitPendingStid := io.explicitStid
    explicitPendingValue := io.explicitValue
  }.elsewhen(explicitWillClear) {
    explicitPendingValid := false.B
  }
}
