package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, Mux1H, PriorityEncoder,
  PriorityEncoderOH, Valid}

class OooIexOldestReadyPickerIO(val p: OooParams = OooParams())
    extends Bundle {
  val classBankEnables = Input(Vec(p.iqClassCount,
    UInt(p.iqBankCount.W)))
  val stidBlock = Input(UInt(p.stidCount.W))
  val candidates = Input(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iqEntriesPerBank, new OooIexPickCandidate(p)))))
  val pick = Decoupled(new OooIexPickToken(p))
  val malformed = Valid(new OooIexPickReject(p))
  val blockedCanceled = Valid(new OooIexPickToken(p))
  val held = Output(Bool())
  val roundRobin = Output(UInt(p.stidWidth.W))
}

/** Retained oldest-ready selection for one issue domain.
  *
  * An instance covers one physical execution domain. Its class-indexed bank
  * masks allow one pipe to own different bank subsets for ALU, STD, SYS, or
  * another compatible class without creating duplicate scheduling state. It
  * projects only canonical IQ schedule state, selects the oldest exact member
  * independently for every STID across the complete domain, then uses
  * work-conserving round-robin arbitration across STIDs. The selected token is
  * retained under P1 backpressure; no IQ row or payload is copied.
  * After a pick fires, the canonical IQ owner must remove that row from the
  * eligible projection by setting its in-flight state. Same-edge refill masks
  * the fired token locally only to bridge that owner update edge.
  *
  * Same-STID age is the modular concatenation of RID generation, RID slot,
  * and member index. The live ROB/IQ window is far smaller than half of that
  * namespace, so subtraction remains unambiguous across RID wrap. Physical
  * class/bank/entry order is only a deterministic tie break for malformed
  * duplicate ages and never replaces architectural member age.
  */
class OooIexOldestReadyPicker(val p: OooParams = OooParams())
    extends Module {
  val io = IO(new OooIexOldestReadyPickerIO(p))

  private val ageWidth =
    p.ridGenerationWidth + p.ridSlotWidth + p.robMemberIndexWidth
  private val candidateCount =
    p.iqClassCount * p.iqBankCount * p.iqEntriesPerBank

  val heldValid = RegInit(false.B)
  val heldToken = Reg(new OooIexPickToken(p))
  val roundRobin = RegInit(0.U(p.stidWidth.W))

  private def ageKey(token: OooIexPickToken): UInt =
    Cat(token.candidate.member.group.ridGeneration,
      token.candidate.member.group.ridSlot,
      token.candidate.member.memberIndex)

  private def physicalKey(token: OooIexPickToken): UInt =
    Cat(token.query.uopClass.asUInt, token.query.bank, token.query.entry)

  private def older(left: OooIexPickToken, right: OooIexPickToken): Bool = {
    val delta = (ageKey(right) - ageKey(left))(ageWidth - 1, 0)
    delta.orR && !delta(ageWidth - 1)
  }

  private def sameExact(
      left: OooIexPickToken,
      right: OooIexPickToken): Bool =
    left.candidate.member.asUInt === right.candidate.member.asUInt &&
      left.candidate.reservation.asUInt ===
        right.candidate.reservation.asUInt

  private def chooseOldest(
      left: (Bool, OooIexPickToken),
      right: (Bool, OooIexPickToken)): (Bool, OooIexPickToken) = {
    val sameAge = ageKey(left._2) === ageKey(right._2)
    val rightWins = right._1 && (!left._1 || older(right._2, left._2) ||
      (sameAge && physicalKey(right._2) < physicalKey(left._2)))
    val result = Wire(new OooIexPickToken(p))
    result := Mux(rightWins, right._2, left._2)
    (left._1 || right._1, result)
  }

  private def selectTree(
      values: Seq[(Bool, OooIexPickToken)]):
      (Bool, OooIexPickToken) = {
    require(values.nonEmpty)
    if (values.size == 1) values.head
    else {
      val (left, right) = values.splitAt(values.size / 2)
      chooseOldest(selectTree(left), selectTree(right))
    }
  }

  private def stidBlocked(stid: UInt): Bool =
    if (p.stidCount == 1) io.stidBlock(0) else io.stidBlock(stid)

  val safeHeldStid = Mux(heldValid, heldToken.candidate.stid, 0.U)
  val heldBlocked = heldValid && stidBlocked(safeHeldStid)
  io.pick.valid := heldValid && !heldBlocked
  io.pick.bits := heldToken
  val pickFire = io.pick.valid && io.pick.ready
  io.blockedCanceled.valid := heldBlocked
  io.blockedCanceled.bits := heldToken
  io.held := heldValid && !heldBlocked
  io.roundRobin := roundRobin

  val tokens = Wire(Vec(p.iqClassCount, Vec(p.iqBankCount,
    Vec(p.iqEntriesPerBank, new OooIexPickToken(p)))))
  val identityExact = Wire(Vec(p.iqClassCount, Vec(p.iqBankCount,
    Vec(p.iqEntriesPerBank, Bool()))))
  val reservationExact = Wire(Vec(p.iqClassCount, Vec(p.iqBankCount,
    Vec(p.iqEntriesPerBank, Bool()))))
  val selectable = Wire(Vec(p.iqClassCount, Vec(p.iqBankCount,
    Vec(p.iqEntriesPerBank, Bool()))))

  for (classIndex <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    val candidate = io.candidates(classIndex)(bank)(entry)
    val token = tokens(classIndex)(bank)(entry)
    token.query.uopClass := OooUopClass.all(classIndex)
    token.query.bank := bank.U
    token.query.entry := entry.U
    token.candidate := candidate

    identityExact(classIndex)(bank)(entry) :=
      candidate.member.group.valid &&
      candidate.member.bid.valid && candidate.stid < p.stidCount.U &&
      candidate.peId === candidate.member.group.peId &&
      candidate.stid === candidate.member.group.stid
    reservationExact(classIndex)(bank)(entry) :=
      candidate.reservation.valid &&
      candidate.reservation.uopClass === OooUopClass.all(classIndex) &&
      candidate.reservation.bank === bank.U &&
      candidate.reservation.writePort < p.iqWritePortsPerBank.U &&
      candidate.reservation.speculativeSlot === entry.U
    val firedAgain = pickFire && sameExact(token, heldToken)
    val candidateStidBlocked = candidate.stid < p.stidCount.U &&
      stidBlocked(candidate.stid)
    selectable(classIndex)(bank)(entry) :=
      io.classBankEnables(classIndex)(bank) && candidate.eligible &&
      identityExact(classIndex)(bank)(entry) &&
      reservationExact(classIndex)(bank)(entry) &&
      !candidateStidBlocked && !firedAgain
  }

  val flatMalformed = Wire(Vec(candidateCount, Bool()))
  val flatTokens = Wire(Vec(candidateCount, new OooIexPickToken(p)))
  val flatIdentityExact = Wire(Vec(candidateCount, Bool()))
  val flatReservationExact = Wire(Vec(candidateCount, Bool()))
  for (classIndex <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    val flatIndex = (classIndex * p.iqBankCount + bank) *
      p.iqEntriesPerBank + entry
    flatTokens(flatIndex) := tokens(classIndex)(bank)(entry)
    flatIdentityExact(flatIndex) := identityExact(classIndex)(bank)(entry)
    flatReservationExact(flatIndex) :=
      reservationExact(classIndex)(bank)(entry)
    flatMalformed(flatIndex) :=
      io.classBankEnables(classIndex)(bank) &&
      io.candidates(classIndex)(bank)(entry).eligible &&
      !(identityExact(classIndex)(bank)(entry) &&
        reservationExact(classIndex)(bank)(entry))
  }
  val malformedMask = flatMalformed.asUInt
  val malformedIndex = PriorityEncoder(malformedMask)
  io.malformed.valid := malformedMask.orR
  io.malformed.bits.token := Mux(malformedMask.orR,
    flatTokens(malformedIndex), 0.U.asTypeOf(io.malformed.bits.token))
  io.malformed.bits.identityExact := malformedMask.orR &&
    flatIdentityExact(malformedIndex)
  io.malformed.bits.reservationExact := malformedMask.orR &&
    flatReservationExact(malformedIndex)

  val perStidWinner = Wire(Vec(p.stidCount,
    Valid(new OooIexPickToken(p))))
  for (stid <- 0 until p.stidCount) {
    val values = (0 until p.iqClassCount).flatMap { classIndex =>
      (0 until p.iqBankCount).flatMap { bank =>
        (0 until p.iqEntriesPerBank).map { entry =>
          (selectable(classIndex)(bank)(entry) &&
            io.candidates(classIndex)(bank)(entry).stid === stid.U,
            tokens(classIndex)(bank)(entry))
        }
      }
    }
    val winner = selectTree(values)
    perStidWinner(stid).valid := winner._1
    perStidWinner(stid).bits := winner._2
  }

  // A terminal fire advances the fairness base before selecting the same-edge
  // replacement. This permits one pick per cycle after the initial retained
  // selection without immediately recapturing the row that just fired.
  val arbitrationBase = Mux(pickFire,
    heldToken.candidate.stid + 1.U, roundRobin)
  val rotated = Wire(Vec(p.stidCount, Valid(new OooIexPickToken(p))))
  for (offset <- 0 until p.stidCount) {
    if (p.stidCount == 1) {
      rotated(offset) := perStidWinner(0)
    } else {
      val stid = (arbitrationBase + offset.U)(p.stidWidth - 1, 0)
      rotated(offset) := perStidWinner(stid)
    }
  }
  val rotatedMask = VecInit(rotated.map(_.valid)).asUInt
  val selectedOH = PriorityEncoderOH(rotatedMask)
  val selectedValid = rotatedMask.orR
  val selectedToken = Wire(new OooIexPickToken(p))
  selectedToken := Mux(selectedValid,
    Mux1H(selectedOH, rotated.map(_.bits)),
    0.U.asTypeOf(selectedToken))

  val canCapture = !heldValid || heldBlocked || pickFire
  when(canCapture) {
    heldValid := selectedValid
    when(selectedValid) {
      heldToken := selectedToken
    }
  }
  when(pickFire) {
    roundRobin := heldToken.candidate.stid + 1.U
  }

  // This key is intentionally fixed-width. Keep the guard close to the
  // comparator so future geometry changes cannot silently make modular age
  // ambiguous within the maximum live IQ population.
  require(BigInt(p.iqClassCount) * p.iqBankCount * p.iqEntriesPerBank <
    (BigInt(1) << (ageWidth - 1)),
    "live IQ population must fit within half of the modular member-age space")
}
