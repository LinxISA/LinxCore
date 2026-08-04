package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, MuxLookup, PopCount, log2Ceil}

import linxcore.bctrl.BID
import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.rob.ROBID

class ScalarLrScIdentity(
    val robEntries: Int,
    val bidWidth: Int,
    val lsidWidth: Int)
    extends Bundle {
  val bid = UInt(bidWidth.W)
  val gid = new ROBID(robEntries)
  val rid = new ROBID(robEntries)
  val lsIdFull = UInt(lsidWidth.W)
}

class ScalarLrScReservationOwnerIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  private val stidCountWidth = log2Ceil(p.stidCount + 1)

  val enable = Input(Bool())
  val contextInvalidate = Input(Bool())
  val flushAll = Input(Bool())
  val flushValid = Input(Bool())
  val flushStid = Input(UInt(p.stidWidth.W))
  val flushIdentityValid = Input(Bool())
  val flushIdentity = Input(new ScalarLrScIdentity(coreParams.robEntries, BID.DefaultWidth, coreParams.lsidWidth))

  val lrCompleteValid = Input(Bool())
  val lrCompleteAccepted = Input(Bool())
  val lrStid = Input(UInt(p.stidWidth.W))
  val lrLineAddr = Input(UInt(p.addrWidth.W))
  val lrSize = Input(UInt(p.loadSizeWidth.W))
  val lrRawData = Input(UInt(p.dataWidth.W))
  val lrIdentity = Input(new ScalarLrScIdentity(coreParams.robEntries, BID.DefaultWidth, coreParams.lsidWidth))

  val scReqValid = Input(Bool())
  val scReqReady = Output(Bool())
  val scReqAccepted = Output(Bool())
  val scReqStid = Input(UInt(p.stidWidth.W))
  val scReqLineAddr = Input(UInt(p.addrWidth.W))
  val scReqSize = Input(UInt(p.loadSizeWidth.W))
  val scReqData = Input(UInt(p.dataWidth.W))
  val scReqIdentity = Input(new ScalarLrScIdentity(coreParams.robEntries, BID.DefaultWidth, coreParams.lsidWidth))
  val scCommitReady = Input(Bool())
  val scCompleteFire = Output(Bool())
  val scSuccess = Output(Bool())
  val scStatus = Output(UInt(p.dataWidth.W))
  val scStoreValid = Output(Bool())
  val scStoreData = Output(UInt(p.dataWidth.W))
  val scStoreMask = Output(UInt((p.dataWidth / 8).W))
  val scStoreLineAddr = Output(UInt(p.addrWidth.W))
  val scStoreIdentity = Output(new ScalarLrScIdentity(coreParams.robEntries, BID.DefaultWidth, coreParams.lsidWidth))
  val scUnsupportedValid = Output(Bool())

  val committedStoreInvalidateValid = Input(Bool())
  val committedStoreInvalidateStid = Input(UInt(p.stidWidth.W))
  val committedStoreInvalidateLineAddr = Input(UInt(p.addrWidth.W))

  val reservationValidByStid = Output(Vec(p.stidCount, Bool()))
  val reservationLineByStid = Output(Vec(p.stidCount, UInt(p.addrWidth.W)))
  val reservationCount = Output(UInt(stidCountWidth.W))
  val lrData = Output(UInt(p.dataWidth.W))
  val lrSetAccepted = Output(Bool())
  val protocolError = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByScResident = Output(Bool())
}

class ScalarLrScReservationOwner(val coreParams: CoreParams = CoreParams()) extends Module {
  private val p = coreParams.scalarLsu
  require(p.dataWidth == 64, "LR/SC first slice expects 64-bit scalar data")
  require(p.lineBytes == 64, "LR/SC reservation granularity is one 64-byte line")
  require(p.stidCount > 0, "LR/SC reservation owner needs at least one STID lane")

  val io = IO(new ScalarLrScReservationOwnerIO(coreParams, p))

  private val stidIndexWidth = math.max(1, log2Ceil(p.stidCount))
  private def stidInRange(stid: UInt): Bool = stid < p.stidCount.U
  private def stidIndex(stid: UInt): UInt = stid(stidIndexWidth - 1, 0)
  private def supportedSize(size: UInt): Bool =
    size === 1.U || size === 2.U || size === 4.U || size === 8.U
  private def signExtend(raw: UInt, bits: Int): UInt =
    if (bits == p.dataWidth) raw else Cat(Fill(p.dataWidth - bits, raw(bits - 1)), raw(bits - 1, 0))
  private def zeroExtend(raw: UInt, bits: Int): UInt =
    if (bits == p.dataWidth) raw else Cat(0.U((p.dataWidth - bits).W), raw(bits - 1, 0))
  private def robIdMatches(lhs: ROBID, rhs: ROBID): Bool =
    lhs.valid && rhs.valid && ROBID.equal(lhs, rhs)
  private def identityMatches(lhs: ScalarLrScIdentity, rhs: ScalarLrScIdentity): Bool =
    lhs.bid === rhs.bid &&
      robIdMatches(lhs.gid, rhs.gid) &&
      robIdMatches(lhs.rid, rhs.rid) &&
      lhs.lsIdFull === rhs.lsIdFull

  val reservationValid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val reservationLine = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.addrWidth.W))))
  val reservationSize = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.loadSizeWidth.W))))
  val reservationIdentity = Reg(Vec(p.stidCount, new ScalarLrScIdentity(coreParams.robEntries, BID.DefaultWidth, coreParams.lsidWidth)))
  for (idx <- 0 until p.stidCount) {
    when(!reservationValid(idx)) {
      reservationIdentity(idx) := 0.U.asTypeOf(reservationIdentity(idx))
    }
  }

  val scResident = RegInit(false.B)
  val scStid = RegInit(0.U(p.stidWidth.W))
  val scLineAddr = RegInit(0.U(p.addrWidth.W))
  val scSize = RegInit(0.U(p.loadSizeWidth.W))
  val scData = RegInit(0.U(p.dataWidth.W))
  val scIdentity = Reg(new ScalarLrScIdentity(coreParams.robEntries, BID.DefaultWidth, coreParams.lsidWidth))
  when(!scResident) {
    scIdentity := 0.U.asTypeOf(scIdentity)
  }

  val flushMatchesLrIdentity =
    io.flushValid && io.flushIdentityValid && identityMatches(io.lrIdentity, io.flushIdentity)
  val flushMatchesScReqIdentity =
    io.flushValid && io.flushIdentityValid && identityMatches(io.scReqIdentity, io.flushIdentity)
  val flushMatchesResidentScIdentity =
    io.flushValid && io.flushIdentityValid && identityMatches(scIdentity, io.flushIdentity)
  val flushMatchesSc = io.flushAll || flushMatchesResidentScIdentity
  val lrFlushBlocked = io.contextInvalidate || io.flushAll || flushMatchesLrIdentity
  val scReqFlushBlocked = io.contextInvalidate || io.flushAll || flushMatchesScReqIdentity
  val lrSupported = supportedSize(io.lrSize)
  val scSupported = supportedSize(scSize)

  io.lrData := MuxLookup(io.lrSize, 0.U(p.dataWidth.W))(Seq(
    1.U -> zeroExtend(io.lrRawData, 8),
    2.U -> zeroExtend(io.lrRawData, 16),
    4.U -> signExtend(io.lrRawData, 32),
    8.U -> io.lrRawData
  ))

  val lrSetFire =
    io.enable && io.lrCompleteValid && io.lrCompleteAccepted && stidInRange(io.lrStid) && lrSupported && !lrFlushBlocked
  io.lrSetAccepted := lrSetFire

  io.scReqReady := io.enable && !scResident && !scReqFlushBlocked && stidInRange(io.scReqStid) && supportedSize(io.scReqSize)
  io.scReqAccepted := io.scReqValid && io.scReqReady
  when(io.scReqAccepted) {
    scResident := true.B
    scStid := io.scReqStid
    scLineAddr := io.scReqLineAddr
    scSize := io.scReqSize
    scData := io.scReqData
    scIdentity := io.scReqIdentity
  }

  val scStidOk = stidInRange(scStid)
  val scStidIdx = if (p.stidCount == 1) 0.U(stidIndexWidth.W) else stidIndex(scStid)
  val storeInvalidateIdx =
    if (p.stidCount == 1) 0.U(stidIndexWidth.W) else stidIndex(io.committedStoreInvalidateStid)
  val flushStidIdx =
    if (p.stidCount == 1) 0.U(stidIndexWidth.W) else stidIndex(io.flushStid)
  val lrStidIdx = if (p.stidCount == 1) 0.U(stidIndexWidth.W) else stidIndex(io.lrStid)
  val scInvalidatedThisCycle =
    io.committedStoreInvalidateValid &&
      stidInRange(io.committedStoreInvalidateStid) &&
      scStidOk &&
      io.committedStoreInvalidateStid === scStid &&
      io.committedStoreInvalidateLineAddr === scLineAddr
  val scReservationHit =
    scResident && scStidOk && reservationValid(scStidIdx) && reservationLine(scStidIdx) === scLineAddr
  val flushMatchesReservation = io.flushValid && io.flushIdentityValid &&
    stidInRange(io.flushStid) && reservationValid(flushStidIdx) &&
    identityMatches(reservationIdentity(flushStidIdx), io.flushIdentity)
  val scSuccessNow = scReservationHit && !scInvalidatedThisCycle
  val scCompleteFire = io.enable && scResident && scSupported && io.scCommitReady && !flushMatchesSc && !io.contextInvalidate
  io.scCompleteFire := scCompleteFire
  io.scSuccess := scSuccessNow
  io.scStatus := Mux(scSuccessNow, 0.U, 1.U)
  io.scStoreValid := scCompleteFire && scSuccessNow
  io.scStoreData := scData
  io.scStoreMask := MuxLookup(scSize, 0.U((p.dataWidth / 8).W))(Seq(
    1.U -> "h01".U((p.dataWidth / 8).W),
    2.U -> "h03".U((p.dataWidth / 8).W),
    4.U -> "h0f".U((p.dataWidth / 8).W),
    8.U -> "hff".U((p.dataWidth / 8).W)
  ))
  io.scStoreLineAddr := scLineAddr
  io.scStoreIdentity := scIdentity
  io.scUnsupportedValid := scResident && !scSupported

  when(io.contextInvalidate || io.flushAll) {
    for (idx <- 0 until p.stidCount) {
      reservationValid(idx) := false.B
    }
  }.elsewhen(flushMatchesReservation) {
    reservationValid(flushStidIdx) := false.B
  }

  when(io.committedStoreInvalidateValid && stidInRange(io.committedStoreInvalidateStid)) {
    when(reservationValid(storeInvalidateIdx) &&
        reservationLine(storeInvalidateIdx) === io.committedStoreInvalidateLineAddr) {
      reservationValid(storeInvalidateIdx) := false.B
    }
  }

  when(scCompleteFire && scStidOk) {
    reservationValid(scStidIdx) := false.B
  }

  when(lrSetFire) {
    reservationValid(lrStidIdx) := true.B
    reservationLine(lrStidIdx) := io.lrLineAddr
    reservationSize(lrStidIdx) := io.lrSize
    reservationIdentity(lrStidIdx) := io.lrIdentity
  }

  when(scResident && (flushMatchesSc || io.contextInvalidate)) {
    scResident := false.B
  }.elsewhen(scCompleteFire) {
    scResident := false.B
  }

  io.reservationValidByStid := reservationValid
  io.reservationLineByStid := reservationLine
  io.reservationCount := PopCount(reservationValid)
  io.protocolError :=
    (io.lrCompleteValid && (!stidInRange(io.lrStid) || !lrSupported)) ||
      (io.scReqValid && !scResident && (!stidInRange(io.scReqStid) || !supportedSize(io.scReqSize))) ||
      (scResident && !scStidOk) ||
      (io.committedStoreInvalidateValid && !stidInRange(io.committedStoreInvalidateStid))
  io.blockedByDisabled := !io.enable && (io.lrCompleteValid || io.scReqValid || scResident)
  io.blockedByFlush := lrFlushBlocked || scReqFlushBlocked || (scResident && flushMatchesSc)
  io.blockedByScResident := io.scReqValid && scResident
}
