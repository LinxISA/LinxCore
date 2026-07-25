package linxcore.execute

import chisel3._
import chisel3.util.{Mux1H, PopCount, PriorityEncoder, log2Ceil}

import linxcore.common.{InterfaceParams, OperandClass, RenamedUop, ScalarSpAccess}
import linxcore.rob.ROBID

class ScalarIssueFabricIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8,
    val bankCount: Int = 2,
    val stidCount: Int = 1)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)
  private val indexWidth = log2Ceil(depth)
  private val bankWidth = log2Ceil(bankCount)

  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val flushValid = Input(Bool())

  val releaseValid = Input(Bool())
  val releaseBid = Input(new ROBID(p.robEntries))
  val releaseRid = Input(new ROBID(p.robEntries))
  val releaseStid = Input(UInt(p.threadIdWidth.W))
  val secondaryReleaseValid = Input(Bool())
  val secondaryReleaseBid = Input(new ROBID(p.robEntries))
  val secondaryReleaseRid = Input(new ROBID(p.robEntries))
  val secondaryReleaseStid = Input(UInt(p.threadIdWidth.W))
  val tertiaryReleaseValid = Input(Bool())
  val tertiaryReleaseBid = Input(new ROBID(p.robEntries))
  val tertiaryReleaseRid = Input(new ROBID(p.robEntries))
  val tertiaryReleaseStid = Input(UInt(p.threadIdWidth.W))
  val externalControlFenceValid = Input(Bool())
  val externalControlFenceBid = Input(new ROBID(p.robEntries))
  val externalControlFenceRid = Input(new ROBID(p.robEntries))
  val externalControlFenceStid = Input(UInt(p.threadIdWidth.W))
  val scalarSpHeadValidByStid = Input(Vec(stidCount, Bool()))
  val scalarSpHeadBidByStid = Input(Vec(stidCount, new ROBID(p.robEntries)))
  val scalarSpHeadRidByStid = Input(Vec(stidCount, new ROBID(p.robEntries)))
  val scalarSpSnapshotByStid = Input(Vec(stidCount, UInt(p.immWidth.W)))

  val readyMask = Input(UInt((1 << p.physRegWidth).W))
  val pWakeupValid = Input(Bool())
  val pWakeupTag = Input(UInt(p.physRegWidth.W))
  val localTReadyMask = Input(UInt(4.W))
  val localUReadyMask = Input(UInt(4.W))
  val readValid = Output(Vec(3, Bool()))
  val readTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val readOperandClass = Output(Vec(3, OperandClass()))
  val readRelTag = Output(Vec(3, UInt(p.archRegWidth.W)))
  val readData = Input(Vec(3, UInt(p.immWidth.W)))

  val issueValid = Output(Bool())
  val issueReady = Input(Bool())
  val issueUop = Output(new RenamedUop(p))
  val issueSrcData = Output(Vec(3, UInt(p.immWidth.W)))
  val scalarSpReadSnapshot = Output(UInt(p.immWidth.W))
  val scalarSpIssueSnapshot = Output(UInt(p.immWidth.W))

  val inputAcceptFire = Output(Bool())
  val inputAcceptUop = Output(new RenamedUop(p))
  val inputAcceptDstValid = Output(Bool())
  val inputAcceptDstTag = Output(UInt(p.physRegWidth.W))
  val enqueueFire = Output(Bool())
  val enqueueCount = Output(UInt(log2Ceil(bankCount + 1).W))
  val bankEnqueueFireMask = Output(UInt(bankCount.W))
  val bankEnqueueUop = Output(Vec(bankCount, new RenamedUop(p)))
  val pickFire = Output(Bool())
  val issueFire = Output(Bool())
  val cancelFire = Output(Bool())
  val releaseFire = Output(Bool())
  val enqueueDstValid = Output(Bool())
  val enqueueDstTag = Output(UInt(p.physRegWidth.W))

  val empty = Output(Bool())
  val full = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val issuedCount = Output(UInt(countWidth.W))
  val notIssuedCount = Output(UInt(countWidth.W))
  val headValid = Output(Bool())
  val headIssued = Output(Bool())
  val headPc = Output(UInt(p.pcWidth.W))
  val headOpcode = Output(UInt(p.opcodeWidth.W))
  val headStid = Output(UInt(p.threadIdWidth.W))
  val headBid = Output(new ROBID(p.robEntries))
  val headRid = Output(new ROBID(p.robEntries))
  val headSrcValidMask = Output(UInt(3.W))
  val headSrcOperandClass = Output(Vec(3, OperandClass()))
  val headSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val headSrcRelTag = Output(Vec(3, UInt(p.archRegWidth.W)))
  val sourceReadyMask = Output(UInt(3.W))
  val allSourcesReady = Output(Bool())
  val pWakeupMatched = Output(Bool())
  val pWakeupMatchCount = Output(UInt(log2Ceil(depth * 3 + 1).W))
  val selectedValid = Output(Bool())
  val selectedIndex = Output(UInt(indexWidth.W))
  val selectedReadReady = Output(Bool())
  val i1Valid = Output(Bool())
  val i2Valid = Output(Bool())
  val stageBusy = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByRead = Output(Bool())
  val blockedByOutput = Output(Bool())
  val blockedByIssued = Output(Bool())

  val enqueueBank = Output(UInt(bankWidth.W))
  val readGrantBank = Output(UInt(bankWidth.W))
  val issueGrantBank = Output(UInt(bankWidth.W))
  val bankOccupancy = Output(Vec(bankCount, UInt(log2Ceil(depth / bankCount + 1).W)))
  val bankPickMask = Output(UInt(bankCount.W))
  val bankReadAttemptMask = Output(UInt(bankCount.W))
  val bankReadGrantMask = Output(UInt(bankCount.W))
  val bankIssueValidMask = Output(UInt(bankCount.W))
  val bankIssueGrantMask = Output(UInt(bankCount.W))
  val simultaneousPick = Output(Bool())
  val readContention = Output(Bool())
  val readArbitrationLoss = Output(Bool())
  val issueContention = Output(Bool())
  val controlFenceActive = Output(Bool())
  val controlFenceBlocked = Output(Bool())
  val bankControlBlockedMask = Output(UInt(bankCount.W))
  val storeOrderBlocked = Output(Bool())
  val bankStoreOrderBlockedMask = Output(UInt(bankCount.W))
  val scalarSpOrderBlocked = Output(Bool())
  val bankScalarSpOrderBlockedMask = Output(UInt(bankCount.W))
  val protocolError = Output(Bool())
}

class ScalarIssueFabric(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8,
    val bankCount: Int = 2,
    val stidCount: Int = 1)
    extends Module {
  require(depth >= bankCount * 2, "each scalar issue bank needs at least two entries")
  require((depth & (depth - 1)) == 0, "scalar issue fabric depth must be a power of two")
  require(bankCount > 1 && (bankCount & (bankCount - 1)) == 0,
    "scalar issue bank count must be a power of two greater than one")
  require(bankCount == 2, "scalar issue ingress skid currently supports exactly two banks")
  require(depth % bankCount == 0, "scalar issue depth must divide evenly across banks")
  require(stidCount > 0, "scalar issue fabric must expose at least one STID")

  private val bankDepth = depth / bankCount
  private val bankWidth = log2Ceil(bankCount)
  private val countWidth = log2Ceil(depth + 1)
  private val indexWidth = log2Ceil(depth)
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))
  private def stidIndex(stid: UInt): UInt =
    if (stidCount == 1) 0.U(stidIndexWidth.W) else stid(stidIndexWidth - 1, 0)

  val io = IO(new ScalarIssueFabricIO(p, depth, bankCount, stidCount))

  val banks = Seq.fill(bankCount)(Module(new ReducedScalarIssueQueue(p, bankDepth, stidCount)))
  val ingress = Module(new ScalarIssueIngressSkid2(p))
  for (bank <- banks) {
    bank.io.flushValid := io.flushValid
    bank.io.releaseValid := io.releaseValid
    bank.io.releaseBid := io.releaseBid
    bank.io.releaseRid := io.releaseRid
    bank.io.releaseStid := io.releaseStid
    bank.io.secondaryReleaseValid := io.secondaryReleaseValid
    bank.io.secondaryReleaseBid := io.secondaryReleaseBid
    bank.io.secondaryReleaseRid := io.secondaryReleaseRid
    bank.io.secondaryReleaseStid := io.secondaryReleaseStid
    bank.io.tertiaryReleaseValid := io.tertiaryReleaseValid
    bank.io.tertiaryReleaseBid := io.tertiaryReleaseBid
    bank.io.tertiaryReleaseRid := io.tertiaryReleaseRid
    bank.io.tertiaryReleaseStid := io.tertiaryReleaseStid
    bank.io.scalarSpHeadValidByStid := io.scalarSpHeadValidByStid
    bank.io.scalarSpHeadBidByStid := io.scalarSpHeadBidByStid
    bank.io.scalarSpHeadRidByStid := io.scalarSpHeadRidByStid
    bank.io.readyMask := io.readyMask
    bank.io.pWakeupValid := io.pWakeupValid
    bank.io.pWakeupTag := io.pWakeupTag
    bank.io.localTReadyMask := io.localTReadyMask
    bank.io.localUReadyMask := io.localUReadyMask
  }

  val routeValidStages = Wire(Vec(bankCount + 1, Bool()))
  val routeBankStages = Wire(Vec(bankCount + 1, UInt(bankWidth.W)))
  val routeCountStages = Wire(Vec(bankCount + 1, UInt(log2Ceil(depth + 1).W)))
  routeValidStages(0) := false.B
  routeBankStages(0) := 0.U
  routeCountStages(0) := depth.U
  for (idx <- 0 until bankCount) {
    val effectiveCount =
      (banks(idx).io.count.pad(countWidth) + ingress.io.bankOccupancy(idx).pad(countWidth))(countWidth - 1, 0)
    val take = !routeValidStages(idx) || effectiveCount < routeCountStages(idx)
    routeValidStages(idx + 1) := true.B
    routeBankStages(idx + 1) := Mux(take, idx.U, routeBankStages(idx))
    routeCountStages(idx + 1) := Mux(take, effectiveCount, routeCountStages(idx))
  }
  val routeBank = routeBankStages(bankCount)

  ingress.io.inValid := io.inValid
  ingress.io.in := io.in
  ingress.io.inBank := routeBank
  ingress.io.flushValid := io.flushValid
  for (idx <- 0 until bankCount) {
    ingress.io.drainCanConsume(idx) := banks(idx).io.inReady
    banks(idx).io.inValid := ingress.io.drainFire(idx)
    banks(idx).io.in := ingress.io.drainPreview(idx)
  }
  io.inReady := ingress.io.inReady

  val readArbiter = Module(new ScalarIssueCandidateArbiter(p, bankCount))
  val issueArbiter = Module(new ScalarIssueCandidateArbiter(p, bankCount))
  val controlBlocked = Wire(Vec(bankCount, Bool()))
  val storeOrderBlocked = Wire(Vec(bankCount, Bool()))
  val scalarSpOrderBlocked = Wire(Vec(bankCount, Bool()))
  for (idx <- 0 until bankCount) {
    val candidate = banks(idx).io.readUop
    val candidateRedirectsControl =
      candidate.dispatchTarget === linxcore.common.DispatchTarget.Bru ||
        candidate.opcode === linxcore.frontend.FrontendOpcodeDecodeTable.OP_FRET_STK.U(p.opcodeWidth.W)
    val candidateSpAccess = ScalarSpAccess.classify(candidate)
    val candidateStid = stidIndex(candidate.threadId)
    val candidateMatchesScalarSpHead =
      !candidateSpAccess.valid ||
        (io.scalarSpHeadValidByStid(candidateStid) &&
          ROBID.equal(candidate.bid, io.scalarSpHeadBidByStid(candidateStid)) &&
          ROBID.equal(candidate.rid, io.scalarSpHeadRidByStid(candidateStid)))
    val blockedByOlderControl = VecInit(banks.flatMap { bank =>
      (0 until bankDepth).map { row =>
        val control = bank.io.residentControlUop(row)
        bank.io.residentControlValid(row) &&
          (control.threadId === candidate.threadId) &&
          ROBID.greater(candidate.rid, control.rid)
      }
    }).asUInt.orR ||
      (io.externalControlFenceValid &&
        (io.externalControlFenceStid === candidate.threadId) &&
        ROBID.greater(candidate.rid, io.externalControlFenceRid))
    // A redirecting uop is also the tail of its own recovery boundary.  It
    // must not bypass an older resident row in another bank: otherwise the
    // redirect flush can discard that already-issued older row before it
    // reaches writeback.  RID is the global order within one STID, so this
    // comparison remains valid across BID changes and RID wrap-around.
    //
    // This reduced guard intentionally observes only each physical FIFO head.
    // That is complete for the current single-STID natural top because every
    // bank head is the oldest resident row in that bank.  With multiple STIDs,
    // an older same-STID row can sit behind another STID's head; full multi-STID
    // closure therefore needs a per-STID oldest-resident export from each bank.
    val redirectBlockedByOlderResident = candidateRedirectsControl && VecInit(banks.map { bank =>
      bank.io.headValid &&
        (bank.io.headStid === candidate.threadId) &&
        ROBID.greater(candidate.rid, bank.io.headRid)
    }).asUInt.orR
    controlBlocked(idx) :=
      banks(idx).io.readAttemptValid && (blockedByOlderControl || redirectBlockedByOlderResident)
    val blockedByOlderStore = candidate.isStore && VecInit(banks.flatMap { bank =>
      (0 until bankDepth).map { row =>
        val store = bank.io.residentStoreUop(row)
        bank.io.residentStoreValid(row) &&
          (store.threadId === candidate.threadId) &&
          ROBID.greater(candidate.rid, store.rid)
      }
    }).asUInt.orR
    storeOrderBlocked(idx) := banks(idx).io.readAttemptValid && blockedByOlderStore
    scalarSpOrderBlocked(idx) := banks(idx).io.readAttemptValid && candidateSpAccess.valid && !candidateMatchesScalarSpHead
    readArbiter.io.valid(idx) := banks(idx).io.readAttemptValid &&
      !blockedByOlderControl && !redirectBlockedByOlderResident &&
        !blockedByOlderStore && !scalarSpOrderBlocked(idx)
    readArbiter.io.stid(idx) := banks(idx).io.readUop.threadId
    readArbiter.io.rid(idx) := banks(idx).io.readUop.rid
    banks(idx).io.readGrant := readArbiter.io.grant(idx)

    issueArbiter.io.valid(idx) := banks(idx).io.issueValid
    issueArbiter.io.stid(idx) := banks(idx).io.issueUop.threadId
    issueArbiter.io.rid(idx) := banks(idx).io.issueUop.rid
    banks(idx).io.issueReady := issueArbiter.io.grant(idx) && io.issueReady
  }
  readArbiter.io.advance := VecInit(banks.map(_.io.readFire)).asUInt.orR
  issueArbiter.io.advance := VecInit(banks.map(_.io.issueFire)).asUInt.orR

  for (lane <- 0 until 3) {
    io.readValid(lane) := Mux1H(readArbiter.io.grant, banks.map(_.io.readValid(lane)))
    io.readTags(lane) := Mux1H(readArbiter.io.grant, banks.map(_.io.readTags(lane)))
    io.readOperandClass(lane) := OperandClass.P
    io.readRelTag(lane) := Mux1H(readArbiter.io.grant, banks.map(_.io.readRelTag(lane)))
    for (idx <- 0 until bankCount) {
      when(readArbiter.io.grant(idx)) {
        io.readOperandClass(lane) := banks(idx).io.readOperandClass(lane)
      }
      banks(idx).io.readData(lane) := Mux(readArbiter.io.grant(idx), io.readData(lane), 0.U)
    }
    io.issueSrcData(lane) := Mux1H(issueArbiter.io.grant, banks.map(_.io.issueSrcData(lane)))
  }
  io.scalarSpReadSnapshot := Mux1H((0 until bankCount).map { idx =>
    readArbiter.io.grant(idx) -> io.scalarSpSnapshotByStid(stidIndex(banks(idx).io.readUop.threadId))
  })
  io.scalarSpIssueSnapshot := Mux1H((0 until bankCount).map { idx =>
    issueArbiter.io.grant(idx) -> io.scalarSpSnapshotByStid(stidIndex(banks(idx).io.issueUop.threadId))
  })
  io.issueValid := issueArbiter.io.selectedValid
  io.issueUop := 0.U.asTypeOf(new RenamedUop(p))
  for (idx <- 0 until bankCount) {
    when(issueArbiter.io.grant(idx)) {
      io.issueUop := banks(idx).io.issueUop
    }
  }

  val headBankValid = VecInit(banks.map(_.io.headValid))
  val headBank = PriorityEncoder(headBankValid.asUInt)
  val headSelect = VecInit((0 until bankCount).map(idx => headBankValid(idx) && headBank === idx.U))
  val selectedBankValid = VecInit(banks.map(_.io.selectedValid))
  val selectedBank = PriorityEncoder(selectedBankValid.asUInt)
  val selectedSelect = VecInit((0 until bankCount).map(idx => selectedBankValid(idx) && selectedBank === idx.U))

  val totalCount = banks.map(_.io.count.pad(countWidth)).reduce(_ + _)
  val totalIssued = banks.map(_.io.issuedCount.pad(countWidth)).reduce(_ + _)
  val totalNotIssued = banks.map(_.io.notIssuedCount.pad(countWidth)).reduce(_ + _)
  val wakeupCount = banks.map(_.io.pWakeupMatchCount.pad(log2Ceil(depth * 3 + 1))).reduce(_ + _)
  val pickMask = VecInit(banks.map(_.io.pickFire)).asUInt
  val readAttemptMask = VecInit(banks.map(_.io.readAttemptValid)).asUInt
  val issueValidMask = VecInit(banks.map(_.io.issueValid)).asUInt

  val bankEnqueueMask = VecInit(banks.map(_.io.enqueueFire)).asUInt
  val inputAcceptFire = io.inValid && io.inReady
  io.inputAcceptFire := inputAcceptFire
  io.inputAcceptUop := io.in
  io.inputAcceptDstValid := inputAcceptFire && io.in.dst(0).valid && (io.in.dst(0).kind === linxcore.common.DestinationKind.Gpr)
  io.inputAcceptDstTag := io.in.dst(0).physTag
  io.enqueueFire := io.inputAcceptFire
  io.enqueueCount := PopCount(bankEnqueueMask)
  io.bankEnqueueFireMask := bankEnqueueMask
  for (idx <- 0 until bankCount) {
    io.bankEnqueueUop(idx) := Mux(banks(idx).io.enqueueFire, banks(idx).io.in, 0.U.asTypeOf(new RenamedUop(p)))
  }
  io.pickFire := pickMask.orR
  io.issueFire := VecInit(banks.map(_.io.issueFire)).asUInt.orR
  io.cancelFire := VecInit(banks.map(_.io.cancelFire)).asUInt.orR
  io.releaseFire := VecInit(banks.map(_.io.releaseFire)).asUInt.orR
  io.enqueueDstValid := io.inputAcceptDstValid
  io.enqueueDstTag := io.inputAcceptDstTag
  io.empty := totalCount === 0.U && ingress.io.empty
  io.full := ingress.io.full
  io.count := totalCount + ingress.io.count.pad(countWidth)
  io.issuedCount := totalIssued
  io.notIssuedCount := totalNotIssued
  io.headValid := headBankValid.asUInt.orR
  io.headIssued := Mux1H(headSelect, banks.map(_.io.headIssued))
  io.headPc := Mux1H(headSelect, banks.map(_.io.headPc))
  io.headOpcode := Mux1H(headSelect, banks.map(_.io.headOpcode))
  io.headStid := Mux1H(headSelect, banks.map(_.io.headStid))
  io.headBid := Mux1H(headSelect, banks.map(_.io.headBid))
  io.headRid := Mux1H(headSelect, banks.map(_.io.headRid))
  io.headSrcValidMask := Mux1H(headSelect, banks.map(_.io.headSrcValidMask))
  for (lane <- 0 until 3) {
    io.headSrcOperandClass(lane) := OperandClass.P
    for (idx <- 0 until bankCount) {
      when(headSelect(idx)) {
        io.headSrcOperandClass(lane) := banks(idx).io.headSrcOperandClass(lane)
      }
    }
    io.headSrcPhysTag(lane) := Mux1H(headSelect, banks.map(_.io.headSrcPhysTag(lane)))
    io.headSrcRelTag(lane) := Mux1H(headSelect, banks.map(_.io.headSrcRelTag(lane)))
  }
  io.sourceReadyMask := Mux1H(headSelect, banks.map(_.io.sourceReadyMask))
  io.allSourcesReady := Mux1H(headSelect, banks.map(_.io.allSourcesReady))
  io.pWakeupMatched := wakeupCount =/= 0.U
  io.pWakeupMatchCount := wakeupCount
  io.selectedValid := selectedBankValid.asUInt.orR
  io.selectedIndex := Mux1H(selectedSelect, (0 until bankCount).map { idx =>
    (idx * bankDepth).U(indexWidth.W) + banks(idx).io.selectedIndex.pad(indexWidth)
  })
  io.selectedReadReady := Mux1H(selectedSelect, banks.map(_.io.selectedReadReady))
  io.i1Valid := VecInit(banks.map(_.io.i1Valid)).asUInt.orR
  io.i2Valid := VecInit(banks.map(_.io.i2Valid)).asUInt.orR
  io.stageBusy := VecInit(banks.map(_.io.stageBusy)).asUInt.orR
  io.blockedBySource := VecInit(banks.map(_.io.blockedBySource)).asUInt.orR
  io.blockedByRead := VecInit(banks.map(_.io.blockedByRead)).asUInt.orR
  io.blockedByOutput := VecInit(banks.map(_.io.blockedByOutput)).asUInt.orR
  io.blockedByIssued := VecInit(banks.map(_.io.blockedByIssued)).asUInt.orR

  io.enqueueBank := routeBank
  io.readGrantBank := readArbiter.io.selectedIndex
  io.issueGrantBank := issueArbiter.io.selectedIndex
  for (idx <- 0 until bankCount) {
    io.bankOccupancy(idx) := banks(idx).io.count
  }
  io.bankPickMask := pickMask
  io.bankReadAttemptMask := readAttemptMask
  io.bankReadGrantMask := readArbiter.io.grant.asUInt
  io.bankIssueValidMask := issueValidMask
  io.bankIssueGrantMask := issueArbiter.io.grant.asUInt
  io.simultaneousPick := PopCount(pickMask) > 1.U
  io.readContention := readArbiter.io.contended
  io.readArbitrationLoss := readArbiter.io.contended && readArbiter.io.selectedValid
  io.issueContention := issueArbiter.io.contended
  io.controlFenceActive := io.externalControlFenceValid || VecInit(banks.flatMap { bank =>
    (0 until bankDepth).map(bank.io.residentControlValid(_))
  }).asUInt.orR
  io.controlFenceBlocked := controlBlocked.asUInt.orR
  io.bankControlBlockedMask := controlBlocked.asUInt
  io.storeOrderBlocked := storeOrderBlocked.asUInt.orR
  io.bankStoreOrderBlockedMask := storeOrderBlocked.asUInt
  io.scalarSpOrderBlocked := scalarSpOrderBlocked.asUInt.orR
  io.bankScalarSpOrderBlockedMask := scalarSpOrderBlocked.asUInt
  io.protocolError := readArbiter.io.invalidRid || issueArbiter.io.invalidRid ||
    (io.externalControlFenceValid &&
      (!io.externalControlFenceBid.valid || !io.externalControlFenceRid.valid))
}
