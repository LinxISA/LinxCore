package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Mux1H, PopCount, Valid, log2Ceil}
import linxcore.common.OperandClass
import linxcore.params.CoreParams
import linxcore.top.interface.PcBufferReadAddress

class OooIexOperandReadPortRequest(val p: OooParams = OooParams())
    extends Bundle {
  val domain = UInt(p.iexIssueDomainWidth.W)
  val sourceIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxSourceOperands)).W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val source = new OooIexSourceState(p)
}

class OooIexAtomicReadArbiterIO(val core: CoreParams, val p: OooParams)
    extends Bundle {
  val attempts = Input(Vec(p.iexIssueDomainCount,
    Valid(new OooIexI1ReadAttempt(p))))

  val decisionValid = Output(Vec(p.iexIssueDomainCount, Bool()))
  val grant = Output(Vec(p.iexIssueDomainCount, Bool()))
  val sourceDataValid = Output(Vec(p.iexIssueDomainCount,
    UInt(p.maxSourceOperands.W)))
  val sourceData = Output(Vec(p.iexIssueDomainCount,
    Vec(p.maxSourceOperands, UInt(p.pcWidth.W))))
  val pcDataValid = Output(Vec(p.iexIssueDomainCount, Bool()))
  val pcData = Output(Vec(p.iexIssueDomainCount, UInt(p.pcWidth.W)))

  val pReadRequests = Output(Vec(p.iexPReadPorts,
    Valid(new OooIexOperandReadPortRequest(p))))
  val pReadResponses = Input(Vec(p.iexPReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val tReadRequests = Output(Vec(p.iexTReadPorts,
    Valid(new OooIexOperandReadPortRequest(p))))
  val tReadResponses = Input(Vec(p.iexTReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val uReadRequests = Output(Vec(p.iexUReadPorts,
    Valid(new OooIexOperandReadPortRequest(p))))
  val uReadResponses = Input(Vec(p.iexUReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val pcReadRequests = Output(Vec(p.pcReadPorts,
    Valid(new PcBufferReadAddress(core))))
  val pcReadResponses = Input(Vec(p.pcReadPorts,
    Valid(UInt(p.pcWidth.W))))

  val shapeExact = Output(Vec(p.iexIssueDomainCount, Bool()))
  val pDemand = Output(Vec(p.iexIssueDomainCount,
    UInt(p.sourceCountWidth.W)))
  val tDemand = Output(Vec(p.iexIssueDomainCount,
    UInt(p.sourceCountWidth.W)))
  val uDemand = Output(Vec(p.iexIssueDomainCount,
    UInt(p.sourceCountWidth.W)))
  val selectedMask = Output(UInt(p.iexIssueDomainCount.W))
  val deniedMask = Output(UInt(p.iexIssueDomainCount.W))
  val roundRobinStid = Output(UInt(p.stidWidth.W))
}

/** Atomic multi-domain I1 P/T/U/PC read-port allocator.
  *
  * Every candidate contributes one complete operand group. The arbiter ranks
  * exact requests by same-STID member age and cross-STID round-robin order,
  * then greedily accepts each complete group that fits the remaining four
  * resources. This is the lexicographic optimum without enumerating 2^N
  * subsets; no source from a denied group is presented to a read owner.
  *
  * Port responses are readyless. A granted group with a missing or stale
  * response retains the grant but exposes an incomplete valid mask, allowing
  * the existing I1 lane to reject and repick the exact resident row.
  */
class OooIexAtomicReadArbiter(
    val core: CoreParams,
    val p: OooParams)
    extends Module {
  def this(core: CoreParams) =
    this(core, OooIexPhysicalProfile.fromCoreParams(core).params)
  OooRecoveryMembership.requireCompatible(p, core)
  val io = IO(new OooIexAtomicReadArbiterIO(core, p))

  private val domainCount = p.iexIssueDomainCount
  private val ageWidth =
    p.ridGenerationWidth + p.ridSlotWidth + p.robMemberIndexWidth
  private val rankWidth = math.max(1, log2Ceil(domainCount))
  private val resourceCountWidth = p.countWidth(
    domainCount * (p.maxSourceOperands + 1))

  private def popCountOrZero(values: Seq[Bool]): UInt =
    if (values.isEmpty) 0.U else PopCount(VecInit(values))

  val roundRobinStid = RegInit(0.U(p.stidWidth.W))
  io.roundRobinStid := roundRobinStid

  private def ageKey(attempt: OooIexI1ReadAttempt): UInt =
    Cat(attempt.member.group.ridGeneration,
      attempt.member.group.ridSlot,
      attempt.member.memberIndex)

  private def older(
      left: OooIexI1ReadAttempt,
      right: OooIexI1ReadAttempt): Bool = {
    val delta = (ageKey(right) - ageKey(left))(ageWidth - 1, 0)
    delta.orR && !delta(ageWidth - 1)
  }

  private def higherPriority(left: Int, right: Int): Bool = {
    val leftAttempt = io.attempts(left).bits
    val rightAttempt = io.attempts(right).bits
    val sameStid = leftAttempt.stid === rightAttempt.stid
    val sameAge = ageKey(leftAttempt) === ageKey(rightAttempt)
    val leftStidDistance =
      (leftAttempt.stid - roundRobinStid)(p.stidWidth - 1, 0)
    val rightStidDistance =
      (rightAttempt.stid - roundRobinStid)(p.stidWidth - 1, 0)
    Mux(sameStid,
      older(leftAttempt, rightAttempt) || (sameAge && (left < right).B),
      leftStidDistance < rightStidDistance)
  }

  val sourceMasksExact = Wire(Vec(domainCount, Bool()))
  for (domain <- 0 until domainCount) {
    val attempt = io.attempts(domain).bits
    val sourceValidMask = VecInit(attempt.sources.map(_.valid)).asUInt
    // sourceMask contains only sources that still need an RF port after the
    // lane's exact bypass selection. It must remain a subset of logical
    // sources, but need not equal the complete logical-source mask.
    sourceMasksExact(domain) :=
      (attempt.sourceMask & ~sourceValidMask).orR === false.B

    val sourceShapeExact = attempt.sources.zipWithIndex.map {
      case (source, sourceIndex) =>
        val active = attempt.sourceMask(sourceIndex)
        val isP = source.operandClass === OperandClass.P
        val isT = source.operandClass === OperandClass.T
        val isU = source.operandClass === OperandClass.U
        !active || (source.valid && (isP || isT || isU) &&
          (!isP || source.ptag < p.pPhysRegs.U) &&
          (!isT || source.localTag < p.tPhysRegs.U) &&
          (!isU || source.localTag < p.uPhysRegs.U))
    }.reduce(_ && _)
    io.shapeExact(domain) := sourceMasksExact(domain) && sourceShapeExact &&
      attempt.member.group.valid && attempt.member.bid.valid &&
      attempt.reservation.valid && attempt.stid < p.stidCount.U &&
      attempt.stid === attempt.member.group.stid &&
      (!attempt.pcRequired || attempt.pcToken.valid)
    io.pDemand(domain) := PopCount(VecInit(attempt.sources.zipWithIndex.map {
      case (source, sourceIndex) =>
        attempt.sourceMask(sourceIndex) &&
          source.operandClass === OperandClass.P
    }))
    io.tDemand(domain) := PopCount(VecInit(attempt.sources.zipWithIndex.map {
      case (source, sourceIndex) =>
        attempt.sourceMask(sourceIndex) &&
          source.operandClass === OperandClass.T
    }))
    io.uDemand(domain) := PopCount(VecInit(attempt.sources.zipWithIndex.map {
      case (source, sourceIndex) =>
        attempt.sourceMask(sourceIndex) &&
          source.operandClass === OperandClass.U
    }))
  }

  // The exact lexicographic optimum is a greedy prefix over the total
  // priority order: accept the highest-priority complete group when it fits,
  // then repeat with the remaining independent resources.  Computing each
  // eligible domain's rank keeps this polynomial in the physical pipe count;
  // enumerating 2^N subsets would make the formal 12-domain profile an
  // elaboration-time and timing liability.
  val eligible = VecInit(io.attempts.zip(io.shapeExact).map {
    case (attempt, exact) => attempt.valid && exact
  })
  val priorityRank = Wire(Vec(domainCount, UInt(rankWidth.W)))
  for (domain <- 0 until domainCount) {
    priorityRank(domain) := popCountOrZero(
      (0 until domainCount).filter(_ != domain).map { peer =>
        eligible(peer) && higherPriority(peer, domain)
      })
  }

  var bestMask = 0.U(domainCount.W)
  var pUsed = 0.U(resourceCountWidth.W)
  var tUsed = 0.U(resourceCountWidth.W)
  var uUsed = 0.U(resourceCountWidth.W)
  var pcUsed = 0.U(resourceCountWidth.W)
  for (rank <- 0 until domainCount) {
    val rankOneHot = VecInit((0 until domainCount).map { domain =>
      eligible(domain) && priorityRank(domain) === rank.U
    })
    assert(PopCount(rankOneHot) <= 1.U,
      "I1 read arbitration priority order must be total")
    val present = rankOneHot.asUInt.orR
    val pNext = pUsed +& Mux1H(rankOneHot, io.pDemand)
    val tNext = tUsed +& Mux1H(rankOneHot, io.tDemand)
    val uNext = uUsed +& Mux1H(rankOneHot, io.uDemand)
    val pcNext = pcUsed +& Mux1H(rankOneHot,
      io.attempts.map(_.bits.pcRequired.asUInt))
    val fits = pNext <= p.iexPReadPorts.U &&
      tNext <= p.iexTReadPorts.U &&
      uNext <= p.iexUReadPorts.U &&
      pcNext <= p.pcReadPorts.U
    val accept = present && fits
    bestMask = bestMask | Mux(accept, rankOneHot.asUInt, 0.U)
    pUsed = Mux(accept, pNext, pUsed)
    tUsed = Mux(accept, tNext, tUsed)
    uUsed = Mux(accept, uNext, uUsed)
    pcUsed = Mux(accept, pcNext, pcUsed)
  }
  io.selectedMask := bestMask
  val validMask = VecInit(io.attempts.map(attempt => attempt.valid)).asUInt
  io.deniedMask := validMask & ~bestMask

  val sourceDataValidBySource = Wire(Vec(domainCount,
    Vec(p.maxSourceOperands, Bool())))
  for (domain <- 0 until domainCount) {
    io.decisionValid(domain) := io.attempts(domain).valid
    io.grant(domain) := bestMask(domain)
    sourceDataValidBySource(domain) := VecInit(
      Seq.fill(p.maxSourceOperands)(false.B))
    io.sourceDataValid(domain) := sourceDataValidBySource(domain).asUInt
    io.sourceData(domain) := VecInit(Seq.fill(p.maxSourceOperands)(
      0.U(p.pcWidth.W)))
    io.pcDataValid(domain) := false.B
    io.pcData(domain) := 0.U
  }
  io.pReadRequests := VecInit(Seq.fill(p.iexPReadPorts)(
    0.U.asTypeOf(Valid(new OooIexOperandReadPortRequest(p)))))
  io.tReadRequests := VecInit(Seq.fill(p.iexTReadPorts)(
    0.U.asTypeOf(Valid(new OooIexOperandReadPortRequest(p)))))
  io.uReadRequests := VecInit(Seq.fill(p.iexUReadPorts)(
    0.U.asTypeOf(Valid(new OooIexOperandReadPortRequest(p)))))
  io.pcReadRequests := VecInit(Seq.fill(p.pcReadPorts)(
    0.U.asTypeOf(Valid(new PcBufferReadAddress(core)))))

  val flatSources = for {
    domain <- 0 until domainCount
    sourceIndex <- 0 until p.maxSourceOperands
  } yield (domain, sourceIndex)

  for (((domain, sourceIndex), flatIndex) <- flatSources.zipWithIndex) {
    val attempt = io.attempts(domain).bits
    val source = attempt.sources(sourceIndex)
    val active = bestMask(domain) && attempt.sourceMask(sourceIndex)
    val activeP = active && source.operandClass === OperandClass.P
    val activeT = active && source.operandClass === OperandClass.T
    val activeU = active && source.operandClass === OperandClass.U
    val prior = flatSources.take(flatIndex)
    val pPort = popCountOrZero(prior.map { case (priorDomain, priorSource) =>
      bestMask(priorDomain) &&
        io.attempts(priorDomain).bits.sourceMask(priorSource) &&
        io.attempts(priorDomain).bits.sources(priorSource).operandClass ===
          OperandClass.P
    })
    val tPort = popCountOrZero(prior.map { case (priorDomain, priorSource) =>
      bestMask(priorDomain) &&
        io.attempts(priorDomain).bits.sourceMask(priorSource) &&
        io.attempts(priorDomain).bits.sources(priorSource).operandClass ===
          OperandClass.T
    })
    val uPort = popCountOrZero(prior.map { case (priorDomain, priorSource) =>
      bestMask(priorDomain) &&
        io.attempts(priorDomain).bits.sourceMask(priorSource) &&
        io.attempts(priorDomain).bits.sources(priorSource).operandClass ===
          OperandClass.U
    })

    for (port <- 0 until p.iexPReadPorts) {
      when(activeP && pPort === port.U) {
        io.pReadRequests(port).valid := true.B
        io.pReadRequests(port).bits.domain := domain.U
        io.pReadRequests(port).bits.sourceIndex := sourceIndex.U
        io.pReadRequests(port).bits.stid := attempt.stid
        io.pReadRequests(port).bits.epoch := attempt.epoch
        io.pReadRequests(port).bits.source := source
        sourceDataValidBySource(domain)(sourceIndex) :=
          io.pReadResponses(port).valid
        io.sourceData(domain)(sourceIndex) := io.pReadResponses(port).bits
      }
    }
    for (port <- 0 until p.iexTReadPorts) {
      when(activeT && tPort === port.U) {
        io.tReadRequests(port).valid := true.B
        io.tReadRequests(port).bits.domain := domain.U
        io.tReadRequests(port).bits.sourceIndex := sourceIndex.U
        io.tReadRequests(port).bits.stid := attempt.stid
        io.tReadRequests(port).bits.epoch := attempt.epoch
        io.tReadRequests(port).bits.source := source
        sourceDataValidBySource(domain)(sourceIndex) :=
          io.tReadResponses(port).valid
        io.sourceData(domain)(sourceIndex) := io.tReadResponses(port).bits
      }
    }
    for (port <- 0 until p.iexUReadPorts) {
      when(activeU && uPort === port.U) {
        io.uReadRequests(port).valid := true.B
        io.uReadRequests(port).bits.domain := domain.U
        io.uReadRequests(port).bits.sourceIndex := sourceIndex.U
        io.uReadRequests(port).bits.stid := attempt.stid
        io.uReadRequests(port).bits.epoch := attempt.epoch
        io.uReadRequests(port).bits.source := source
        sourceDataValidBySource(domain)(sourceIndex) :=
          io.uReadResponses(port).valid
        io.sourceData(domain)(sourceIndex) := io.uReadResponses(port).bits
      }
    }
  }

  for (domain <- 0 until domainCount) {
    val pcActive = bestMask(domain) && io.attempts(domain).bits.pcRequired
    val pcPort = popCountOrZero((0 until domain).map { priorDomain =>
      bestMask(priorDomain) && io.attempts(priorDomain).bits.pcRequired
    })
    for (port <- 0 until p.pcReadPorts) {
      when(pcActive && pcPort === port.U) {
        io.pcReadRequests(port).valid := true.B
        io.pcReadRequests(port).bits.valid := true.B
        io.pcReadRequests(port).bits.stid := io.attempts(domain).bits.stid
        io.pcReadRequests(port).bits.pcBufferIndex :=
          io.attempts(domain).bits.pcToken.index
        io.pcReadRequests(port).bits.allocationEpoch :=
          io.attempts(domain).bits.pcToken.allocationEpoch
        io.pcDataValid(domain) := io.pcReadResponses(port).valid
        io.pcData(domain) := io.pcReadResponses(port).bits
      }
    }
  }

  val highestGranted = Wire(Vec(domainCount, Bool()))
  for (domain <- 0 until domainCount) {
    val higherGranted = (0 until domainCount).filter(_ != domain).map {
      peer => bestMask(peer) && higherPriority(peer, domain)
    }.foldLeft(false.B)(_ || _)
    highestGranted(domain) := bestMask(domain) && !higherGranted
    when(highestGranted(domain)) {
      roundRobinStid := io.attempts(domain).bits.stid + 1.U
    }
  }
  assert(PopCount(highestGranted) <= 1.U,
    "I1 read arbitration must produce one highest-priority granted group")
}
