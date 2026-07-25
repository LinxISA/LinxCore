package linxcore.common

import chisel3._
import chisel3.util.{Decoupled, log2Ceil, MuxCase, MuxLookup, Valid}
import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.rob.ROBID

final case class TemplateD3InterfaceParams(
    archRegs: Int = 32,
    sourceOperandCount: Int = 3,
    templateCreditBankIdWidth: Int = 2,
    templateCreditBankEntries: Int = 128,
    templateCreditHandleEntries: Int = 128,
    templateFillMaxCycles: Int = TemplateD3Constants.MaxRows,
    fatalRecordEntries: Int = 16,
    fullProfileSupported: Boolean = true) {
  require(archRegs > 0, "archRegs must be positive")
  require(sourceOperandCount > 0, "sourceOperandCount must be positive")
  require(templateCreditBankIdWidth > 0, "templateCreditBankIdWidth must be positive")
  require(templateCreditBankEntries >= TemplateD3Constants.CreditDomainCount, "token bank must cover all domains")
  require(
    templateCreditHandleEntries >= TemplateD3Constants.CreditDomainCount,
    "handle table must cover all domains")
  require(templateFillMaxCycles >= TemplateD3Constants.MaxRows, "templateFillMaxCycles must cover a 28-row group")
  require(fatalRecordEntries > 0, "fatalRecordEntries must be positive")

  def templateCreditBankIndexWidth: Int = log2Ceil(templateCreditBankEntries)
  def templateCreditBankCountWidth: Int = log2Ceil(templateCreditBankEntries + 1)
  def templateCreditHandleIndexWidth: Int = log2Ceil(templateCreditHandleEntries)
  def templateCreditHandleCountWidth: Int = log2Ceil(templateCreditHandleEntries + 1)
  def fatalRecordIndexWidth: Int = math.max(1, log2Ceil(fatalRecordEntries))

  def requireFullProfileRob(p: InterfaceParams): Unit =
    require(!fullProfileSupported || p.robEntries >= TemplateD3Constants.FullProfileMinRobEntries,
      "full-profile template D3 reservation requires robEntries >= 32")
}

object TemplateD3Constants {
  val MinEncodedN = 1
  val MaxEncodedN = 22
  val MaxRows = 28
  val FullProfileMinRobEntries = 32
  val CreditDomainCount = 15
  val FatalOwnerCount = 13

  def exactRowCount(formId: Int, encodedN: Int): Int = {
    require(encodedN >= MinEncodedN && encodedN <= MaxEncodedN, "encodedN must be in 1..22")
    formId match {
      case 1 => encodedN + 3
      case 2 => encodedN + 3
      case 3 => encodedN + 5
      case 4 => encodedN + 6
      case _ => throw new IllegalArgumentException("unsupported TemplateForm")
    }
  }

  def exactRowKind(formId: Int, encodedN: Int, childOrdinal: Int): Int = {
    if (encodedN < MinEncodedN || encodedN > MaxEncodedN || childOrdinal < 0) {
      TemplateRowKind.Invalid.asUInt.litValue.toInt
    } else {
      val rowCountValue = try {
        exactRowCount(formId, encodedN)
      } catch {
        case _: IllegalArgumentException => 0
      }
      if (childOrdinal >= rowCountValue) {
        TemplateRowKind.Invalid.asUInt.litValue.toInt
      } else {
        formId match {
          case 1 =>
            if (childOrdinal == 0) TemplateRowKind.VFORM.asUInt.litValue.toInt
            else if (childOrdinal == 1) TemplateRowKind.SP_SUB.asUInt.litValue.toInt
            else if (childOrdinal == encodedN + 2) TemplateRowKind.FINAL.asUInt.litValue.toInt
            else TemplateRowKind.STORE.asUInt.litValue.toInt
          case 2 =>
            if (childOrdinal == 0) TemplateRowKind.VFORM.asUInt.litValue.toInt
            else if (childOrdinal == 1) TemplateRowKind.SP_ADD.asUInt.litValue.toInt
            else if (childOrdinal == encodedN + 2) TemplateRowKind.FINAL.asUInt.litValue.toInt
            else TemplateRowKind.LOAD.asUInt.litValue.toInt
          case 3 =>
            if (childOrdinal == 0) TemplateRowKind.VFORM.asUInt.litValue.toInt
            else if (childOrdinal == 1) TemplateRowKind.VTGT.asUInt.litValue.toInt
            else if (childOrdinal == 2) TemplateRowKind.TARGET_PUBLISH.asUInt.litValue.toInt
            else if (childOrdinal == 3) TemplateRowKind.SP_ADD.asUInt.litValue.toInt
            else if (childOrdinal == encodedN + 4) TemplateRowKind.FINAL.asUInt.litValue.toInt
            else TemplateRowKind.LOAD.asUInt.litValue.toInt
          case 4 =>
            if (childOrdinal == 0) TemplateRowKind.VFORM.asUInt.litValue.toInt
            else if (childOrdinal == 1) TemplateRowKind.VLOAD.asUInt.litValue.toInt
            else if (childOrdinal == 2) TemplateRowKind.VTGT.asUInt.litValue.toInt
            else if (childOrdinal == 3) TemplateRowKind.SP_ADD.asUInt.litValue.toInt
            else if (childOrdinal == 4) TemplateRowKind.RESTORE_R10.asUInt.litValue.toInt
            else if (childOrdinal == 5) TemplateRowKind.TARGET_PUBLISH.asUInt.litValue.toInt
            else if (childOrdinal == encodedN + 5) TemplateRowKind.FINAL.asUInt.litValue.toInt
            else TemplateRowKind.LOAD.asUInt.litValue.toInt
          case _ => TemplateRowKind.Invalid.asUInt.litValue.toInt
        }
      }
    }
  }

  def formSupported(formId: UInt): Bool =
    (formId === TemplateForm.FENTRY.asUInt) ||
      (formId === TemplateForm.FEXIT.asUInt) ||
      (formId === TemplateForm.FRET_RA.asUInt) ||
      (formId === TemplateForm.FRET_STK.asUInt)

  def encodedNSupported(encodedN: UInt): Bool =
    encodedN >= MinEncodedN.U && encodedN <= MaxEncodedN.U

  def rowCount(formId: UInt, encodedN: UInt): UInt =
    MuxLookup(formId, 0.U(5.W))(
      Seq(
        TemplateForm.FENTRY.asUInt -> (encodedN + 3.U),
        TemplateForm.FEXIT.asUInt -> (encodedN + 3.U),
        TemplateForm.FRET_RA.asUInt -> (encodedN + 5.U),
        TemplateForm.FRET_STK.asUInt -> (encodedN + 6.U)))

  def demandWellFormed(formId: UInt, encodedN: UInt, rowCountValue: UInt): Bool =
    formSupported(formId) && encodedNSupported(encodedN) && (rowCountValue === rowCount(formId, encodedN))

  def rowKind(formId: UInt, encodedN: UInt, childOrdinal: UInt): UInt = {
    def kind(value: UInt): UInt = value.pad(8)

    val invalid = kind(TemplateRowKind.Invalid.asUInt)
    val validN = encodedNSupported(encodedN)
    val validForm = formSupported(formId)
    val validOrdinal = childOrdinal < rowCount(formId, encodedN)
    val wellFormed = validForm && validN && validOrdinal

    val fentryKind = MuxCase(
      TemplateRowKind.STORE.asUInt,
      Seq(
        (childOrdinal === 0.U) -> TemplateRowKind.VFORM.asUInt,
        (childOrdinal === 1.U) -> TemplateRowKind.SP_SUB.asUInt,
        (childOrdinal === encodedN + 2.U) -> TemplateRowKind.FINAL.asUInt))

    val fexitKind = MuxCase(
      TemplateRowKind.LOAD.asUInt,
      Seq(
        (childOrdinal === 0.U) -> TemplateRowKind.VFORM.asUInt,
        (childOrdinal === 1.U) -> TemplateRowKind.SP_ADD.asUInt,
        (childOrdinal === encodedN + 2.U) -> TemplateRowKind.FINAL.asUInt))

    val fretRaKind = MuxCase(
      TemplateRowKind.LOAD.asUInt,
      Seq(
        (childOrdinal === 0.U) -> TemplateRowKind.VFORM.asUInt,
        (childOrdinal === 1.U) -> TemplateRowKind.VTGT.asUInt,
        (childOrdinal === 2.U) -> TemplateRowKind.TARGET_PUBLISH.asUInt,
        (childOrdinal === 3.U) -> TemplateRowKind.SP_ADD.asUInt,
        (childOrdinal === encodedN + 4.U) -> TemplateRowKind.FINAL.asUInt))

    val fretStkKind = MuxCase(
      TemplateRowKind.LOAD.asUInt,
      Seq(
        (childOrdinal === 0.U) -> TemplateRowKind.VFORM.asUInt,
        (childOrdinal === 1.U) -> TemplateRowKind.VLOAD.asUInt,
        (childOrdinal === 2.U) -> TemplateRowKind.VTGT.asUInt,
        (childOrdinal === 3.U) -> TemplateRowKind.SP_ADD.asUInt,
        (childOrdinal === 4.U) -> TemplateRowKind.RESTORE_R10.asUInt,
        (childOrdinal === 5.U) -> TemplateRowKind.TARGET_PUBLISH.asUInt,
        (childOrdinal === encodedN + 5.U) -> TemplateRowKind.FINAL.asUInt))

    val selected = MuxLookup(formId, TemplateRowKind.Invalid.asUInt)(
      Seq(
        TemplateForm.FENTRY.asUInt -> fentryKind,
        TemplateForm.FEXIT.asUInt -> fexitKind,
        TemplateForm.FRET_RA.asUInt -> fretRaKind,
        TemplateForm.FRET_STK.asUInt -> fretStkKind))

    Mux(wellFormed, kind(selected), invalid)
  }

  def advanceROBID(base: ROBID, childOrdinal: UInt): ROBID =
    ROBID.add(base, childOrdinal)

  def ownerProjectionMatches(owner: TemplateOwnerID): Bool =
    (owner.bid === owner.bidRobid.value) &&
      (owner.gid === owner.gidRobid.value) &&
      (owner.groupBaseRid === owner.groupBaseRidRobid.value) &&
      (owner.groupBaseRobSlot === owner.groupBaseRidRobid.value) &&
      (owner.rid === owner.ridRobid.value) &&
      (owner.robSlot === owner.ridRobid.value) &&
      ROBID.equal(owner.ridRobid, advanceROBID(owner.groupBaseRidRobid, owner.childOrdinal))
}

object TemplateForm extends ChiselEnum {
  val Invalid, FENTRY, FEXIT, FRET_RA, FRET_STK = Value
}

object TemplateRowKind extends ChiselEnum {
  val Invalid, VFORM, SP_SUB, STORE, FINAL, SP_ADD, LOAD, VTGT, TARGET_PUBLISH, VLOAD, RESTORE_R10 = Value
}

object TemplateRejectReason extends ChiselEnum {
  val None, UnsupportedForm, MalformedN, RobTooSmall, RobUnavailable, DuplicateIdentity, CreditUnavailable,
      RecoveryBusy, FatalBusy = Value
}

object TemplateCreditDomain extends ChiselEnum {
  val ROB_ROW, BROB_RANGE, CHECKPOINT, GPR_PHYS_DEST, MAPQ, IQ_ENTRY, LIQ_ENTRY, LOAD_ID, STQ_ENTRY,
      STORE_ID, LSID, VALIDATION, TARGET_PUBLISH, LEASE_FINAL, INVALIDATION_TXN = Value
}

object TemplateCreditTokenState extends ChiselEnum {
  val Free, Reserved, Consumed, Released, Quarantined = Value
}

object TemplateMemoryKind extends ChiselEnum {
  val None, Load, Store = Value
}

object TemplateValidationKind extends ChiselEnum {
  val None, VFORM, VLOAD, VTGT = Value
}

object TemplateReleasePolicy extends ChiselEnum {
  val Release, Quarantine = Value
}

object TemplateFatalReason extends ChiselEnum {
  val BadOwnerIdentity, BadRowPlan, BadMemoryIdentity, CreditLedgerViolation, ProtocolViolation,
      TimeoutOrNestedFatal = Value
}

object TemplateFatalCode extends ChiselEnum {
  val None, BadOwner, BadRowKind, BadOrdinal, BadMemoryShape, TokenReuse, OutOfOrderFill, LostCredit,
      DuplicateFill, FillAfterRecovery, FillTimeout, FatalWhileFatal = Value
}

object TemplateFatalSourcePort extends ChiselEnum {
  val reserveReq, reserveResp, rowFill, rowFillAck, cancel, recovery, trace, ownerAck, assertion = Value
}

object TemplateFatalOwnerIndex extends ChiselEnum {
  val ROB, BROBRange, RenameCheckpointMapQ, GprWriteback, IssueWakeup, LsuMemoryIds, ScbReplayMemoryResponse,
      TargetRedirectTransfer, CtuFillTokenBank, RecoveryCancelInvalidation, DescriptorLeaseFinal, TraceDfx,
      FatalRecord = Value
}

object TemplateFatalTeardownPolicy extends ChiselEnum {
  val ReleaseUnconsumedAndQuarantineConsumed, QuarantineAll, ResetPolicy = Value
}

object TemplateDescriptorState extends ChiselEnum {
  val Invalid, Live, ReleasedAfterAbort = Value
}

class TemplateOwnerID(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val lxcpuId = UInt(32.W)
  val lxcpuContextGeneration = UInt(64.W)
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.threadIdWidth.W)
  val engineLocalTid = UInt(p.threadIdWidth.W)
  val bid = UInt(p.robIndexWidth.W)
  val bidRobid = new ROBID(p.robEntries)
  val gid = UInt(p.robIndexWidth.W)
  val gidRobid = new ROBID(p.robEntries)
  val groupBaseRid = UInt(p.robIndexWidth.W)
  val groupBaseRidRobid = new ROBID(p.robEntries)
  val groupBaseRobSlot = UInt(p.robIndexWidth.W)
  val groupRowCount = UInt(5.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val templateGeneration = UInt(64.W)
  val sourcePc = UInt(p.pcWidth.W)
  val sourceRaw = UInt(p.insnWidth.W)
  val formId = UInt(8.W)
  val encodedN = UInt(5.W)
  val rowPresent = Bool()
  val rowKind = UInt(8.W)
  val childOrdinal = UInt(5.W)
  val rid = UInt(p.robIndexWidth.W)
  val ridRobid = new ROBID(p.robEntries)
  val robSlot = UInt(p.robIndexWidth.W)
  val rowGeneration = UInt(64.W)
  val lsidValid = Bool()
  val lsidValue = UInt(p.lsidWidth.W)
  val lsidWrapOrGeneration = UInt(64.W)
  val loadIdValid = Bool()
  val loadIdValue = UInt(64.W)
  val loadIdGeneration = UInt(64.W)
  val loadReplayGeneration = UInt(64.W)
  val storeIdValid = Bool()
  val storeIdValue = UInt(64.W)
  val storeIdGeneration = UInt(64.W)
}

class TemplateGroupDescriptor(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val lxcpuId = UInt(32.W)
  val lxcpuContextGeneration = UInt(64.W)
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.threadIdWidth.W)
  val engineLocalTid = UInt(p.threadIdWidth.W)
  val bid = UInt(p.robIndexWidth.W)
  val bidRobid = new ROBID(p.robEntries)
  val gid = UInt(p.robIndexWidth.W)
  val gidRobid = new ROBID(p.robEntries)
  val groupBaseRid = UInt(p.robIndexWidth.W)
  val groupBaseRidRobid = new ROBID(p.robEntries)
  val groupBaseRobSlot = UInt(p.robIndexWidth.W)
  val groupRowCount = UInt(5.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val templateGeneration = UInt(64.W)
  val sourcePc = UInt(p.pcWidth.W)
  val sourceRaw = UInt(p.insnWidth.W)
  val formId = UInt(8.W)
  val encodedN = UInt(5.W)
  val firstRid = UInt(p.robIndexWidth.W)
  val firstRidRobid = new ROBID(p.robEntries)
  val firstRobSlot = UInt(p.robIndexWidth.W)
  val rowCount = UInt(5.W)
  val lastRid = UInt(p.robIndexWidth.W)
  val lastRidRobid = new ROBID(p.robEntries)
  val leaseValid = Bool()
  val fatalPoisoned = Bool()
}

class TemplateReserveDemand extends Bundle {
  val formId = UInt(8.W)
  val encodedN = UInt(5.W)
  val rowCount = UInt(5.W)
  val brobRangeCredits = UInt(1.W)
  val gprDestCredits = UInt(5.W)
  val mapqCredits = UInt(5.W)
  val iqCredits = UInt(5.W)
  val liqCredits = UInt(5.W)
  val loadIdCredits = UInt(5.W)
  val stqCredits = UInt(5.W)
  val storeIdCredits = UInt(5.W)
  val lsidCredits = UInt(5.W)
  val targetPublishCredits = UInt(1.W)
  val validationCredits = UInt(2.W)
  val checkpointCredits = UInt(1.W)
  val finalCredits = UInt(1.W)
  val leaseCredits = UInt(1.W)
  val invalidationTxnCredits = UInt(1.W)
}

class TemplateD3ParentPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pc = UInt(p.pcWidth.W)
  val raw = UInt(p.insnWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val immediate = UInt(p.immWidth.W)
  val regRangeBase = UInt(p.archRegWidth.W)
  val regRangeCount = UInt(5.W)
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.threadIdWidth.W)
  val engineLocalTid = UInt(p.threadIdWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
}

class TemplateReserveSourceOperand(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val archTag = UInt(p.archRegWidth.W)
  val physTag = UInt(p.physRegWidth.W)
  val value = UInt(p.immWidth.W)
  val ready = Bool()
}

class TemplateReserveRequest(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  tp.requireFullProfileRob(p)
  val parent = new TemplateD3ParentPayload(p)
  val demand = new TemplateReserveDemand
  val smapSnapshot = Vec(tp.archRegs, UInt(p.physRegWidth.W))
  val cmapSnapshot = Vec(tp.archRegs, UInt(p.physRegWidth.W))
  val sourceOperands = Vec(tp.sourceOperandCount, new TemplateReserveSourceOperand(p))
}

class TemplateCreditTokenHandle(val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams()) extends Bundle {
  val valid = Bool()
  val bankId = UInt(tp.templateCreditBankIdWidth.W)
  val entryIndex = UInt(tp.templateCreditBankIndexWidth.W)
  val domain = TemplateCreditDomain()
  val tokenGeneration = UInt(64.W)
  val descriptorGeneration = UInt(64.W)
}

class TemplateCreditCompositeHandle(val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams()) extends Bundle {
  val valid = Bool()
  val descriptorGeneration = UInt(64.W)
  val childOrdinal = UInt(5.W)
  val firstHandleIndex = UInt(tp.templateCreditHandleIndexWidth.W)
  val handleCount = UInt(5.W)
  val domainMask = UInt(TemplateD3Constants.CreditDomainCount.W)
}

class TemplateFillToken(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val valid = Bool()
  val descriptorGeneration = UInt(64.W)
  val childOrdinal = UInt(5.W)
  val rid = new ROBID(p.robEntries)
  val rowKind = UInt(8.W)
  val resourceCreditMask = UInt(TemplateD3Constants.CreditDomainCount.W)
  val creditTokenHandles = Vec(TemplateD3Constants.CreditDomainCount, new TemplateCreditTokenHandle(tp))
  val compositeHandle = new TemplateCreditCompositeHandle(tp)
}

class TemplateCreditTokenBankDescriptor(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val valid = Bool()
  val descriptorGeneration = UInt(64.W)
  val groupOwner = new TemplateGroupDescriptor(p)
  val tokenBase = UInt(tp.templateCreditBankIndexWidth.W)
  val tokenCount = UInt(tp.templateCreditBankCountWidth.W)
  val rowHandleBase = UInt(tp.templateCreditHandleIndexWidth.W)
  val rowHandleCount = UInt(tp.templateCreditHandleCountWidth.W)
  val domainPresentMask = UInt(TemplateD3Constants.CreditDomainCount.W)
}

class TemplateReserveResponse(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  tp.requireFullProfileRob(p)
  val accepted = Bool()
  val rejected = Bool()
  val rejectReason = TemplateRejectReason()
  val descriptor = new TemplateGroupDescriptor(p)
  val tokens = Vec(TemplateD3Constants.MaxRows, new TemplateFillToken(p, tp))
  val creditTokenBank = new TemplateCreditTokenBankDescriptor(p, tp)
  val reservedMask = UInt(p.robEntries.W)
  val firstRid = UInt(p.robIndexWidth.W)
  val lastRid = UInt(p.robIndexWidth.W)
  val firstRidRobid = new ROBID(p.robEntries)
  val lastRidRobid = new ROBID(p.robEntries)
}

class TemplateCreditRobRowPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val ridRobid = new ROBID(p.robEntries)
  val robSlot = UInt(p.robIndexWidth.W)
  val reservedUnfilledGeneration = UInt(64.W)
  val reservedMaskBit = Bool()
}

class TemplateCreditBrobRangePayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val bidRobid = new ROBID(p.robEntries)
  val gidRobid = new ROBID(p.robEntries)
  val rangeFirstRidRobid = new ROBID(p.robEntries)
  val rangeLastRidRobid = new ROBID(p.robEntries)
  val rowCount = UInt(5.W)
  val brobRangeGeneration = UInt(64.W)
}

class TemplateCreditCheckpointPayload(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val checkpointId = UInt(p.checkpointWidth.W)
  val checkpointGeneration = UInt(64.W)
  val smapSnapshotHandle = UInt(tp.templateCreditHandleIndexWidth.W)
  val cmapSnapshotHandle = UInt(tp.templateCreditHandleIndexWidth.W)
}

class TemplateCreditGprPhysDestPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val physTag = UInt(p.physRegWidth.W)
  val architecturalReg = UInt(p.archRegWidth.W)
  val oldPhysTag = UInt(p.physRegWidth.W)
  val destGeneration = UInt(64.W)
  val writesGpr = Bool()
}

class TemplateCreditMapQPayload(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val mapqSlot = UInt(tp.templateCreditHandleIndexWidth.W)
  val architecturalReg = UInt(p.archRegWidth.W)
  val newPhysTag = UInt(p.physRegWidth.W)
  val oldPhysTag = UInt(p.physRegWidth.W)
  val mapqGeneration = UInt(64.W)
}

class TemplateCreditIqPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val iqIndex = UInt(p.iqIndexWidth.W)
  val issueClass = DispatchTarget()
  val wakeupMask = UInt(p.issueWidth.W)
  val iqGeneration = UInt(64.W)
}

class TemplateCreditLiqPayload(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val liqIndex = UInt(tp.templateCreditHandleIndexWidth.W)
  val loadIdValue = UInt(64.W)
  val loadIdGeneration = UInt(64.W)
  val lsidValue = UInt(p.lsidWidth.W)
  val liqGeneration = UInt(64.W)
}

class TemplateCreditLoadIdPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val loadIdValue = UInt(64.W)
  val loadIdGeneration = UInt(64.W)
  val loadReplayGeneration = UInt(64.W)
  val lsidValue = UInt(p.lsidWidth.W)
}

class TemplateCreditStqPayload(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val stqIndex = UInt(tp.templateCreditHandleIndexWidth.W)
  val storeIdValue = UInt(64.W)
  val storeIdGeneration = UInt(64.W)
  val lsidValue = UInt(p.lsidWidth.W)
  val stqGeneration = UInt(64.W)
}

class TemplateCreditStoreIdPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val storeIdValue = UInt(64.W)
  val storeIdGeneration = UInt(64.W)
  val lsidValue = UInt(p.lsidWidth.W)
}

class TemplateCreditLsidPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val lsidValue = UInt(p.lsidWidth.W)
  val lsidWrapOrGeneration = UInt(64.W)
  val memoryKind = TemplateMemoryKind()
  val loadIdValue = UInt(64.W)
  val storeIdValue = UInt(64.W)
}

class TemplateCreditValidationPayload(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val validationSlot = UInt(tp.templateCreditHandleIndexWidth.W)
  val validationKind = TemplateValidationKind()
  val expectedRowKind = UInt(8.W)
  val validationGeneration = UInt(64.W)
}

class TemplateCreditTargetPublishPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val targetSlot = UInt(p.robIndexWidth.W)
  val targetGeneration = UInt(64.W)
  val targetSourceOrdinal = UInt(5.W)
  val redirectOwner = new TemplateOwnerID(p)
}

class TemplateCreditLeaseFinalPayload(
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val leaseSlot = UInt(tp.templateCreditHandleIndexWidth.W)
  val finalOrdinal = UInt(5.W)
  val leaseGeneration = UInt(64.W)
  val descriptorLeaseValid = Bool()
}

class TemplateCreditInvalidationTxnPayload(
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val txnSlot = UInt(tp.templateCreditHandleIndexWidth.W)
  val requiredOwnerMask = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val ackBitmap = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val txnGeneration = UInt(64.W)
  val releaseOrQuarantinePolicy = TemplateReleasePolicy()
}

class TemplateCreditDomainPayload(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val robRow = new TemplateCreditRobRowPayload(p)
  val brobRange = new TemplateCreditBrobRangePayload(p)
  val checkpoint = new TemplateCreditCheckpointPayload(p, tp)
  val gprPhysDest = new TemplateCreditGprPhysDestPayload(p)
  val mapq = new TemplateCreditMapQPayload(p, tp)
  val iqEntry = new TemplateCreditIqPayload(p)
  val liqEntry = new TemplateCreditLiqPayload(p, tp)
  val loadId = new TemplateCreditLoadIdPayload(p)
  val stqEntry = new TemplateCreditStqPayload(p, tp)
  val storeId = new TemplateCreditStoreIdPayload(p)
  val lsid = new TemplateCreditLsidPayload(p)
  val validation = new TemplateCreditValidationPayload(p, tp)
  val targetPublish = new TemplateCreditTargetPublishPayload(p)
  val leaseFinal = new TemplateCreditLeaseFinalPayload(tp)
  val invalidationTxn = new TemplateCreditInvalidationTxnPayload(tp)
}

class TemplateCreditTokenHeader(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val domain = TemplateCreditDomain()
  val state = TemplateCreditTokenState()
  val groupOwner = new TemplateGroupDescriptor(p)
  val rowOwner = new TemplateOwnerID(p)
  val rowScoped = Bool()
  val childOrdinal = UInt(5.W)
  val amount = UInt(6.W)
  val tokenGeneration = UInt(64.W)
  val ownerGeneration = UInt(64.W)
  val descriptorGeneration = UInt(64.W)
}

class TemplateCreditToken(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val header = new TemplateCreditTokenHeader(p)
  val payload = new TemplateCreditDomainPayload(p, tp)
}

class TemplateCreditTokenConsume(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val creditTokenHandle = new TemplateCreditTokenHandle(tp)
  val expectedOwner = new TemplateOwnerID(p)
  val expectedDomain = TemplateCreditDomain()
  val expectedState = TemplateCreditTokenState()
  val consumeGeneration = UInt(64.W)
}

class TemplateCreditTokenRelease(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val creditTokenHandle = new TemplateCreditTokenHandle(tp)
  val expectedOwner = new TemplateOwnerID(p)
  val expectedDomain = TemplateCreditDomain()
  val releaseState = TemplateCreditTokenState()
  val releaseGeneration = UInt(64.W)
}

class TemplateCreditTokenChannels(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val creditTokenLookup = Decoupled(new TemplateCreditTokenHandle(tp))
  val creditTokenLookupResp = Flipped(Decoupled(new TemplateCreditToken(p, tp)))
  val creditTokenConsume = Decoupled(new TemplateCreditTokenConsume(p, tp))
  val creditTokenRelease = Decoupled(new TemplateCreditTokenRelease(p, tp))
}

class TemplateRowFill(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val owner = new TemplateOwnerID(p)
  val token = new TemplateFillToken(p, tp)
  val row = new CommitTraceRow(traceParams)
  val renamedUop = new RenamedUop(p)
  val creditTokenHandle = new TemplateCreditCompositeHandle(tp)
  val dstReservation = new TemplateCreditGprPhysDestPayload(p)
  val iqReservation = new TemplateCreditIqPayload(p)
  val liqReservation = new TemplateCreditLiqPayload(p, tp)
  val stqReservation = new TemplateCreditStqPayload(p, tp)
  val targetPublish = new TemplateCreditTargetPublishPayload(p)
  val isFinal = Bool()
}

class TemplateCancelRequest(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val owner = new TemplateGroupDescriptor(p)
}

class TemplateRecoveryRequest(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.threadIdWidth.W)
  val firstKilledBid = new ROBID(p.robEntries)
  val inclusive = Bool()
}

class TemplateCancelRecoveryAck extends Bundle {
  val valid = Bool()
  val killedMask = UInt(TemplateD3Constants.MaxRows.W)
  val retainedMask = UInt(TemplateD3Constants.MaxRows.W)
}

class TemplateCancelRecovery(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val cancel = new TemplateCancelRequest(p)
  val recovery = new TemplateRecoveryRequest(p)
  val ack = new TemplateCancelRecoveryAck
}

class TemplateTrace(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val reserveAccepted = Bool()
  val reserveRejected = Bool()
  val fillAccepted = Bool()
  val reservedUnfilledCount = UInt(5.W)
  val owner = new TemplateOwnerID(p)
  val descriptor = new TemplateGroupDescriptor(p)
  val fatalValid = Bool()
}

class TemplateFatalObservedExpected(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val value = UInt(64.W)
  val owner = new TemplateOwnerID(p)
  val descriptor = new TemplateGroupDescriptor(p)
}

class TemplateFatalSourceContext(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val sourceOwner = new TemplateOwnerID(p)
  val sourceDescriptor = new TemplateGroupDescriptor(p)
  val sourcePort = TemplateFatalSourcePort()
  val sourceGeneration = UInt(64.W)
  val sourceCycle = UInt(64.W)
  val sourcePc = UInt(p.pcWidth.W)
  val sourceRaw = UInt(p.insnWidth.W)
  val observed = new TemplateFatalObservedExpected(p)
  val expected = new TemplateFatalObservedExpected(p)
}

class TemplateFatal(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val owner = new TemplateOwnerID(p)
  val descriptorKey = new TemplateGroupDescriptor(p)
  val reason = TemplateFatalReason()
  val code = TemplateFatalCode()
  val sourceContext = new TemplateFatalSourceContext(p)
  val ackBitmap = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val quiescent = Bool()
  val teardownAck = Bool()
}

class TemplateFatalQuiesceReq(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val generation = UInt(64.W)
  val descriptorKey = new TemplateGroupDescriptor(p)
  val reason = TemplateFatalReason()
  val sourceContext = new TemplateFatalSourceContext(p)
  val sourceOwner = new TemplateOwnerID(p)
  val sourcePort = TemplateFatalSourcePort()
  val requiredOwnerMask = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val teardownPolicy = TemplateFatalTeardownPolicy()
}

class TemplateFatalQuiesceAck(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val generation = UInt(64.W)
  val descriptorKey = new TemplateGroupDescriptor(p)
  val ownerIndex = UInt(log2Ceil(TemplateD3Constants.FatalOwnerCount).W)
  val ownerMaskBit = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val stateReleased = Bool()
  val stateQuarantined = Bool()
  val inFlightClear = Bool()
  val creditAckMask = UInt(TemplateD3Constants.CreditDomainCount.W)
  val lastSeenSourceGeneration = UInt(64.W)
}

class TemplateFatalTeardown(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val generation = UInt(64.W)
  val descriptorKey = new TemplateGroupDescriptor(p)
  val requiredOwnerMask = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val ackBitmap = UInt(TemplateD3Constants.FatalOwnerCount.W)
  val creditReleaseMask = UInt(TemplateD3Constants.CreditDomainCount.W)
  val creditQuarantineMask = UInt(TemplateD3Constants.CreditDomainCount.W)
  val publishFatalRecord = Bool()
}

class TemplateFatalTeardownAck(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val valid = Bool()
  val generation = UInt(64.W)
  val descriptorKey = new TemplateGroupDescriptor(p)
  val releasedMask = UInt(TemplateD3Constants.CreditDomainCount.W)
  val quarantinedMask = UInt(TemplateD3Constants.CreditDomainCount.W)
  val descriptorState = TemplateDescriptorState()
  val fatalRecordIndex = UInt(tp.fatalRecordIndexWidth.W)
}

class TemplateD3ReservationFillInterface(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val reserveReq = Flipped(Decoupled(new TemplateReserveRequest(p, tp)))
  val reserveResp = Decoupled(new TemplateReserveResponse(p, tp))
  val fillToken = Output(Vec(TemplateD3Constants.MaxRows, new TemplateFillToken(p, tp)))
  val rowFill = Flipped(Decoupled(new TemplateRowFill(p, tp, traceParams)))
  val rowFillAck = Valid(new TemplateOwnerID(p))
  val cancel = Flipped(Valid(new TemplateCancelRequest(p)))
  val recovery = Flipped(Valid(new TemplateRecoveryRequest(p)))
  val recoveryAck = Valid(new TemplateCancelRecoveryAck)
  val credit = new TemplateCreditTokenChannels(p, tp)
  val trace = Output(new TemplateTrace(p))
  val fatal = Output(new TemplateFatal(p))
  val quiesceReq = Output(Vec(TemplateD3Constants.FatalOwnerCount, Valid(new TemplateFatalQuiesceReq(p))))
  val quiesceAck = Flipped(Decoupled(new TemplateFatalQuiesceAck(p)))
  val fatalTeardown = Output(Valid(new TemplateFatalTeardown(p)))
  val fatalTeardownAck = Flipped(Valid(new TemplateFatalTeardownAck(p, tp)))
}
