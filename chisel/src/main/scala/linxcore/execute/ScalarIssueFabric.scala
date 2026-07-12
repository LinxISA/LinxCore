package linxcore.execute

import chisel3._
import chisel3.util.{Mux1H, PopCount, PriorityEncoder, log2Ceil}

import linxcore.common.{InterfaceParams, OperandClass, RenamedUop}
import linxcore.rob.ROBID

class ScalarIssueFabricIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8,
    val bankCount: Int = 2)
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
  val externalControlFenceValid = Input(Bool())
  val externalControlFenceBid = Input(new ROBID(p.robEntries))
  val externalControlFenceRid = Input(new ROBID(p.robEntries))
  val externalControlFenceStid = Input(UInt(p.threadIdWidth.W))

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

  val enqueueFire = Output(Bool())
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
  val protocolError = Output(Bool())
}

class ScalarIssueFabric(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8,
    val bankCount: Int = 2)
    extends Module {
  require(depth >= bankCount * 2, "each scalar issue bank needs at least two entries")
  require((depth & (depth - 1)) == 0, "scalar issue fabric depth must be a power of two")
  require(bankCount > 1 && (bankCount & (bankCount - 1)) == 0,
    "scalar issue bank count must be a power of two greater than one")
  require(depth % bankCount == 0, "scalar issue depth must divide evenly across banks")

  private val bankDepth = depth / bankCount
  private val bankWidth = log2Ceil(bankCount)
  private val countWidth = log2Ceil(depth + 1)
  private val indexWidth = log2Ceil(depth)
  val io = IO(new ScalarIssueFabricIO(p, depth, bankCount))

  val banks = Seq.fill(bankCount)(Module(new ReducedScalarIssueQueue(p, bankDepth)))
  for (bank <- banks) {
    bank.io.in := io.in
    bank.io.flushValid := io.flushValid
    bank.io.releaseValid := io.releaseValid
    bank.io.releaseBid := io.releaseBid
    bank.io.releaseRid := io.releaseRid
    bank.io.releaseStid := io.releaseStid
    bank.io.secondaryReleaseValid := io.secondaryReleaseValid
    bank.io.secondaryReleaseBid := io.secondaryReleaseBid
    bank.io.secondaryReleaseRid := io.secondaryReleaseRid
    bank.io.secondaryReleaseStid := io.secondaryReleaseStid
    bank.io.readyMask := io.readyMask
    bank.io.pWakeupValid := io.pWakeupValid
    bank.io.pWakeupTag := io.pWakeupTag
    bank.io.localTReadyMask := io.localTReadyMask
    bank.io.localUReadyMask := io.localUReadyMask
  }

  val routeValidStages = Wire(Vec(bankCount + 1, Bool()))
  val routeBankStages = Wire(Vec(bankCount + 1, UInt(bankWidth.W)))
  val routeCountStages = Wire(Vec(bankCount + 1, UInt(log2Ceil(bankDepth + 1).W)))
  routeValidStages(0) := false.B
  routeBankStages(0) := 0.U
  routeCountStages(0) := bankDepth.U
  for (idx <- 0 until bankCount) {
    val take = banks(idx).io.inReady &&
      (!routeValidStages(idx) || banks(idx).io.count < routeCountStages(idx))
    routeValidStages(idx + 1) := routeValidStages(idx) || banks(idx).io.inReady
    routeBankStages(idx + 1) := Mux(take, idx.U, routeBankStages(idx))
    routeCountStages(idx + 1) := Mux(take, banks(idx).io.count, routeCountStages(idx))
  }
  val routeValid = routeValidStages(bankCount)
  val routeBank = routeBankStages(bankCount)
  for (idx <- 0 until bankCount) {
    banks(idx).io.inValid := io.inValid && routeValid && routeBank === idx.U
  }
  io.inReady := routeValid

  val readArbiter = Module(new ScalarIssueCandidateArbiter(p, bankCount))
  val issueArbiter = Module(new ScalarIssueCandidateArbiter(p, bankCount))
  val controlBlocked = Wire(Vec(bankCount, Bool()))
  val storeOrderBlocked = Wire(Vec(bankCount, Bool()))
  for (idx <- 0 until bankCount) {
    val candidate = banks(idx).io.readUop
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
    controlBlocked(idx) := banks(idx).io.readAttemptValid && blockedByOlderControl
    val blockedByOlderStore = candidate.isStore && VecInit(banks.flatMap { bank =>
      (0 until bankDepth).map { row =>
        val store = bank.io.residentStoreUop(row)
        bank.io.residentStoreValid(row) &&
          (store.threadId === candidate.threadId) &&
          ROBID.greater(candidate.rid, store.rid)
      }
    }).asUInt.orR
    storeOrderBlocked(idx) := banks(idx).io.readAttemptValid && blockedByOlderStore
    readArbiter.io.valid(idx) := banks(idx).io.readAttemptValid &&
      !blockedByOlderControl && !blockedByOlderStore
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

  io.enqueueFire := VecInit(banks.map(_.io.enqueueFire)).asUInt.orR
  io.pickFire := pickMask.orR
  io.issueFire := VecInit(banks.map(_.io.issueFire)).asUInt.orR
  io.cancelFire := VecInit(banks.map(_.io.cancelFire)).asUInt.orR
  io.releaseFire := VecInit(banks.map(_.io.releaseFire)).asUInt.orR
  io.enqueueDstValid := VecInit(banks.map(_.io.enqueueDstValid)).asUInt.orR
  io.enqueueDstTag := io.in.dst(0).physTag
  io.empty := totalCount === 0.U
  io.full := !routeValid
  io.count := totalCount
  io.issuedCount := totalIssued
  io.notIssuedCount := totalNotIssued
  io.headValid := headBankValid.asUInt.orR
  io.headIssued := Mux1H(headSelect, banks.map(_.io.headIssued))
  io.headPc := Mux1H(headSelect, banks.map(_.io.headPc))
  io.headOpcode := Mux1H(headSelect, banks.map(_.io.headOpcode))
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
  io.protocolError := readArbiter.io.invalidRid || issueArbiter.io.invalidRid ||
    (io.externalControlFenceValid &&
      (!io.externalControlFenceBid.valid || !io.externalControlFenceRid.valid))
}
