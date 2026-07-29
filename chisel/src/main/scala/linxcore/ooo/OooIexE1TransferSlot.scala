package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

class OooIexE1TransferReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
  val shapeExact = Bool()
  val incomingKilled = Bool()
}

class OooIexE1TransferSlotIO(val p: OooParams = OooParams()) extends Bundle {
  val i2 = Flipped(Decoupled(new OooIexI2Transaction(p)))
  val issueRelease = Decoupled(new OooIexIssueRelease(p))
  val e1 = Decoupled(new OooIexExecuteTransaction(p))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val rejected = Valid(new OooIexE1TransferReject(p))
  val killed = Valid(new OooIexExecuteTransaction(p))
  val occupied = Output(Bool())
}

/** One physical-domain retained I2-to-E1 ownership-transfer slot.
  *
  * I2 acceptance and exact IQ/dispatch release are one atomic fire. Before
  * that edge the issue lane owns recovery; after it, this slot owns the full
  * transaction and must suppress matching recovery or load-cancel events.
  * The slot never interprets opcode semantics and never publishes completion,
  * RF writes, wakeups, or external side effects.
  */
class OooIexE1TransferSlot(
    val p: OooParams = OooParams(),
    val acceptedClassBankEnables: Seq[BigInt] = Seq.empty,
    val ownerLane: Int = 0) extends Module {
  private val defaultClassBankEnables = Seq.tabulate(p.iqClassCount) {
    classIndex =>
      if (classIndex == OooUopClass.Alu.asUInt.litValue.toInt)
        (BigInt(1) << p.iqBankCount) - 1
      else BigInt(0)
  }
  private val classBankEnables =
    if (acceptedClassBankEnables.isEmpty) defaultClassBankEnables
    else acceptedClassBankEnables
  require(classBankEnables.length == p.iqClassCount,
    "E1 transfer projection must define every physical IQ class")
  require(classBankEnables.exists(_ != 0) &&
    classBankEnables.forall(mask => mask >= 0 &&
      mask < (BigInt(1) << p.iqBankCount)),
    "E1 transfer projection needs nonempty in-range class/bank ownership")
  require(ownerLane >= 0 && ownerLane < p.iexIssueDomainCount,
    "E1 transfer owner lane must fit the issue-domain topology")

  val io = IO(new OooIexE1TransferSlotIO(p))

  val slotValid = RegInit(false.B)
  val slot = Reg(new OooIexExecuteTransaction(p))
  val nextGeneration = RegInit(0.U(p.executeSlotGenerationWidth.W))

  private def killedByRecovery(i2: OooIexI2Transaction): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits,
        i2.row.member)

  private def canceledByLoad(i2: OooIexI2Transaction): Bool =
    io.loadCancel.map { cancel =>
      val sourceMatch = i2.row.sources.map { source =>
        source.valid && source.specReady && source.load.valid &&
          cancel.bits.load.valid &&
          source.load.asUInt === cancel.bits.load.asUInt
      }.reduce(_ || _)
      cancel.valid && cancel.bits.stid === i2.row.stid &&
        cancel.bits.epoch === i2.row.epoch && sourceMatch
    }.reduce(_ || _)

  val incoming = io.i2.bits
  val incomingClass = incoming.row.reservation.uopClass.asUInt
  val incomingClassInRange = incomingClass < p.iqClassCount.U
  val incomingBankInRange =
    incoming.row.reservation.bank < p.iqBankCount.U
  val safeIncomingClass = Mux(incomingClassInRange, incomingClass, 0.U)
  val safeIncomingBank = Mux(
    incomingBankInRange, incoming.row.reservation.bank, 0.U)
  val acceptedProjection = VecInit(classBankEnables.map(
    _.U(p.iqBankCount.W)))
  val domainExact = incomingClassInRange && incomingBankInRange &&
    acceptedProjection(safeIncomingClass)(safeIncomingBank)
  val logicalSourceMask = VecInit(incoming.row.sources.map(_.valid)).asUInt
  // The lane captures its row on the canonical pick/claim edge, so its local
  // schedule snapshot may still contain the pre-claim inFlight value. The IQ
  // release sink is the sole authority that revalidates live inFlight state;
  // its ready/fire is already coupled to this slot's acceptance.
  val shapeExact = incoming.row.valid &&
    incoming.row.member.group.valid && incoming.row.member.bid.valid &&
    incoming.row.reservation.valid &&
    domainExact &&
    incoming.row.peId === incoming.row.member.group.peId &&
    incoming.row.stid === incoming.row.member.group.stid &&
    incoming.sourceMask === logicalSourceMask &&
    (incoming.bypassMask & ~incoming.sourceMask).orR === false.B
  val incomingKilled = killedByRecovery(incoming) || canceledByLoad(incoming)

  val slotKilled = slotValid &&
    (killedByRecovery(slot.i2) || canceledByLoad(slot.i2))
  val e1WillDrain = slotValid && !slotKilled && io.e1.ready
  val canAccept = !slotValid || slotKilled || e1WillDrain

  io.issueRelease.valid := io.i2.valid && canAccept && shapeExact &&
    !incomingKilled
  io.issueRelease.bits.member := incoming.row.member
  io.issueRelease.bits.dispatch.peId := incoming.row.peId
  io.issueRelease.bits.dispatch.stid := incoming.row.stid
  io.issueRelease.bits.dispatch.epoch := incoming.row.epoch
  io.issueRelease.bits.dispatch.transactionId := incoming.row.transactionId
  io.issueRelease.bits.dispatch.member := incoming.row.member
  io.issueRelease.bits.dispatch.reservation := incoming.row.reservation
  io.i2.ready := io.issueRelease.ready && canAccept && shapeExact &&
    !incomingKilled

  io.e1.valid := slotValid && !slotKilled
  io.e1.bits := slot
  io.rejected.valid := io.i2.valid && (!shapeExact || incomingKilled)
  io.rejected.bits.member := incoming.row.member
  io.rejected.bits.reservation := incoming.row.reservation
  io.rejected.bits.shapeExact := shapeExact
  io.rejected.bits.incomingKilled := incomingKilled
  io.killed.valid := slotKilled
  io.killed.bits := slot
  io.occupied := slotValid && !slotKilled

  when(e1WillDrain || slotKilled) {
    slotValid := false.B
  }
  when(io.i2.fire) {
    assert(io.issueRelease.fire,
      "I2 ownership transfer and exact issue release must be atomic")
    slotValid := true.B
    slot.ownerClass := incoming.row.reservation.uopClass
    slot.ownerLane := ownerLane.U
    slot.slotGeneration := nextGeneration
    slot.i2 := incoming
    nextGeneration := nextGeneration + 1.U
  }
}
