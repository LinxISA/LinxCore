package linxcore.common

import chisel3._
import chisel3.util.log2Ceil
import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

class TemplateD3BundleProbeIO(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Bundle {
  val owner = Input(new TemplateOwnerID(p))
  val formId = Input(UInt(8.W))
  val encodedN = Input(UInt(5.W))
  val childOrdinal = Input(UInt(5.W))
  val rowCount = Output(UInt(5.W))
  val rowKind = Output(UInt(8.W))
  val demandOk = Output(Bool())
  val projectionOk = Output(Bool())
  val advancedRid = Output(new ROBID(p.robEntries))
  val reserveReq = Input(new TemplateReserveRequest(p, tp))
  val reserveResp = Output(new TemplateReserveResponse(p, tp))
  val rowFill = Input(new TemplateRowFill(p, tp, CommitTraceParams(robValueWidth = p.robIndexWidth + 1)))
  val rowOwner = Output(new TemplateOwnerID(p))
  val fatalReq = Output(new TemplateFatalQuiesceReq(p))
  val fatalAck = Input(new TemplateFatalQuiesceAck(p))
  val teardown = Output(new TemplateFatalTeardown(p))
  val teardownAck = Input(new TemplateFatalTeardownAck(p, tp))
}

class TemplateD3BundleProbe(
    val p: InterfaceParams = InterfaceParams(),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Module {
  val io = IO(new TemplateD3BundleProbeIO(p, tp))

  io.rowCount := TemplateD3Constants.rowCount(io.formId, io.encodedN)
  io.rowKind := TemplateD3Constants.rowKind(io.formId, io.encodedN, io.childOrdinal)
  io.demandOk := TemplateD3Constants.demandWellFormed(io.formId, io.encodedN, io.rowCount)
  io.projectionOk := TemplateD3Constants.ownerProjectionMatches(io.owner)
  io.advancedRid := TemplateD3Constants.advanceROBID(io.owner.groupBaseRidRobid, io.owner.childOrdinal)

  io.reserveResp := 0.U.asTypeOf(io.reserveResp)
  io.reserveResp.accepted := io.reserveReq.demand.rowCount === io.rowCount
  io.reserveResp.rejected := !io.reserveResp.accepted
  io.reserveResp.rejectReason := TemplateRejectReason.MalformedN
  io.reserveResp.descriptor.rowCount := io.rowCount
  io.reserveResp.descriptor.groupRowCount := io.rowCount
  io.reserveResp.descriptor.firstRidRobid := io.owner.groupBaseRidRobid
  io.reserveResp.descriptor.lastRidRobid := ROBID.add(io.owner.groupBaseRidRobid, io.rowCount - 1.U)
  io.reserveResp.tokens(0) := io.rowFill.token
  io.reserveResp.creditTokenBank.valid := io.reserveResp.accepted

  io.rowOwner := io.rowFill.owner

  io.fatalReq := 0.U.asTypeOf(io.fatalReq)
  io.fatalReq.valid := true.B
  io.fatalReq.generation := io.fatalAck.generation
  io.fatalReq.descriptorKey := io.reserveResp.descriptor
  io.fatalReq.reason := TemplateFatalReason.BadOwnerIdentity
  io.fatalReq.sourceContext.sourceOwner := io.rowFill.owner
  io.fatalReq.sourceContext.sourceGeneration := io.fatalAck.lastSeenSourceGeneration
  io.fatalReq.sourceOwner := io.rowFill.owner
  io.fatalReq.sourcePort := TemplateFatalSourcePort.ownerAck
  io.fatalReq.requiredOwnerMask := io.fatalAck.ownerMaskBit
  io.fatalReq.teardownPolicy := TemplateFatalTeardownPolicy.ReleaseUnconsumedAndQuarantineConsumed

  io.teardown := 0.U.asTypeOf(io.teardown)
  io.teardown.generation := io.teardownAck.generation
  io.teardown.descriptorKey := io.teardownAck.descriptorKey
  io.teardown.requiredOwnerMask := io.fatalReq.requiredOwnerMask
  io.teardown.ackBitmap := io.fatalAck.ownerMaskBit
  io.teardown.creditReleaseMask := io.teardownAck.releasedMask
  io.teardown.creditQuarantineMask := io.teardownAck.quarantinedMask
  io.teardown.publishFatalRecord := io.teardownAck.valid
}

class TemplateD3ReservationFillBundlesSpec extends AnyFunSuite {
  private val invalid = TemplateRowKind.Invalid.asUInt.litValue.toInt
  private val vform = TemplateRowKind.VFORM.asUInt.litValue.toInt
  private val spSub = TemplateRowKind.SP_SUB.asUInt.litValue.toInt
  private val store = TemplateRowKind.STORE.asUInt.litValue.toInt
  private val finalRow = TemplateRowKind.FINAL.asUInt.litValue.toInt
  private val spAdd = TemplateRowKind.SP_ADD.asUInt.litValue.toInt
  private val load = TemplateRowKind.LOAD.asUInt.litValue.toInt
  private val vtgt = TemplateRowKind.VTGT.asUInt.litValue.toInt
  private val targetPublish = TemplateRowKind.TARGET_PUBLISH.asUInt.litValue.toInt
  private val vload = TemplateRowKind.VLOAD.asUInt.litValue.toInt
  private val restoreR10 = TemplateRowKind.RESTORE_R10.asUInt.litValue.toInt

  private def expectedSequence(formId: Int, encodedN: Int): Seq[Int] =
    formId match {
      case 1 =>
        Seq(vform, spSub) ++ Seq.fill(encodedN)(store) :+ finalRow
      case 2 =>
        Seq(vform, spAdd) ++ Seq.fill(encodedN)(load) :+ finalRow
      case 3 =>
        Seq(vform, vtgt, targetPublish, spAdd) ++ Seq.fill(encodedN)(load) :+ finalRow
      case 4 =>
        Seq(vform, vload, vtgt, spAdd, restoreR10, targetPublish) ++ Seq.fill(encodedN - 1)(load) :+ finalRow
    }

  test("Template D3 constants preserve accepted R1050 row formulas and profile bounds") {
    assert(TemplateD3Constants.MinEncodedN == 1)
    assert(TemplateD3Constants.MaxEncodedN == 22)
    assert(TemplateD3Constants.MaxRows == 28)
    assert(TemplateD3Constants.FullProfileMinRobEntries == 32)
    assert(TemplateD3Constants.exactRowCount(1, 22) == 25)
    assert(TemplateD3Constants.exactRowCount(2, 22) == 25)
    assert(TemplateD3Constants.exactRowCount(3, 22) == 27)
    assert(TemplateD3Constants.exactRowCount(4, 22) == 28)
    assertThrows[IllegalArgumentException](TemplateD3Constants.exactRowCount(1, 0))
    assertThrows[IllegalArgumentException](TemplateD3Constants.exactRowCount(1, 23))
    assertThrows[IllegalArgumentException](TemplateD3Constants.exactRowCount(0, 1))
  }

  test("Template D3 row-order helper preserves every accepted boundary sequence") {
    for {
      formId <- 1 to 4
      encodedN <- Seq(TemplateD3Constants.MinEncodedN, TemplateD3Constants.MaxEncodedN)
    } {
      val expected = expectedSequence(formId, encodedN)
      assert(TemplateD3Constants.exactRowCount(formId, encodedN) == expected.length)
      assert(expected.length <= TemplateD3Constants.MaxRows)
      val actual = expected.indices.map { childOrdinal =>
        TemplateD3Constants.exactRowKind(formId, encodedN, childOrdinal)
      }
      assert(actual == expected, s"formId=$formId encodedN=$encodedN")
    }
  }

  test("Template D3 FRET_STK order keeps the accepted prefix, load ordinals, and final position") {
    val encodedN = TemplateD3Constants.MaxEncodedN
    val sequence = expectedSequence(4, encodedN)

    assert(sequence.take(6) == Seq(
      vform,
      vload,
      vtgt,
      spAdd,
      restoreR10,
      targetPublish))
    assert(sequence.slice(6, sequence.length - 1) == Seq.fill(encodedN - 1)(load))
    assert(sequence.last == finalRow)
    assert(sequence.length == encodedN + 6)
    assert(TemplateD3Constants.exactRowKind(4, encodedN, encodedN + 5) == finalRow)
  }

  test("Template D3 row-order helper fails closed for illegal form, N, and ordinal") {
    assert(TemplateD3Constants.exactRowKind(0, 1, 0) == invalid)
    assert(TemplateD3Constants.exactRowKind(5, 1, 0) == invalid)
    assert(TemplateD3Constants.exactRowKind(1, 0, 0) == invalid)
    assert(TemplateD3Constants.exactRowKind(1, 23, 0) == invalid)
    assert(TemplateD3Constants.exactRowKind(1, 1, -1) == invalid)
    assert(TemplateD3Constants.exactRowKind(1, 1, TemplateD3Constants.exactRowCount(1, 1)) == invalid)
    assert(TemplateD3Constants.exactRowKind(4, 22, TemplateD3Constants.exactRowCount(4, 22)) == invalid)
  }

  test("Template D3 enums keep the accepted numeric ABI") {
    assert(TemplateForm.FENTRY.asUInt.litValue == 1)
    assert(TemplateForm.FEXIT.asUInt.litValue == 2)
    assert(TemplateForm.FRET_RA.asUInt.litValue == 3)
    assert(TemplateForm.FRET_STK.asUInt.litValue == 4)
    assert(TemplateCreditDomain.ROB_ROW.asUInt.litValue == 0)
    assert(TemplateCreditDomain.INVALIDATION_TXN.asUInt.litValue == 14)
    assert(TemplateCreditTokenState.Free.asUInt.litValue == 0)
    assert(TemplateCreditTokenState.Quarantined.asUInt.litValue == 4)
    assert(TemplateFatalReason.BadOwnerIdentity.asUInt.litValue == 0)
    assert(TemplateFatalReason.TimeoutOrNestedFatal.asUInt.litValue == 5)
  }

  test("TemplateOwnerID carries narrow and full ROBID projections plus row memory identity") {
    val p = InterfaceParams(robEntries = 64)
    val owner = new TemplateOwnerID(p)

    assert(owner.bid.getWidth == p.robIndexWidth)
    assert(owner.bidRobid.value.getWidth == p.robIndexWidth)
    assert(owner.gid.getWidth == p.robIndexWidth)
    assert(owner.gidRobid.value.getWidth == p.robIndexWidth)
    assert(owner.groupBaseRid.getWidth == p.robIndexWidth)
    assert(owner.groupBaseRidRobid.value.getWidth == p.robIndexWidth)
    assert(owner.rid.getWidth == p.robIndexWidth)
    assert(owner.ridRobid.value.getWidth == p.robIndexWidth)
    assert(owner.groupRowCount.getWidth == 5)
    assert(owner.childOrdinal.getWidth == 5)
    assert(owner.encodedN.getWidth == 5)
    assert(owner.lsidValue.getWidth == p.lsidWidth)
    assert(owner.loadIdValue.getWidth == 64)
    assert(owner.storeIdValue.getWidth == 64)
  }

  test("credit transport uses compact handles and descriptor-backed bank metadata") {
    val p = InterfaceParams()
    val tp = TemplateD3InterfaceParams(templateCreditBankEntries = 128, templateCreditHandleEntries = 128)
    val token = new TemplateFillToken(p, tp)
    val bank = new TemplateCreditTokenBankDescriptor(p, tp)
    val channels = new TemplateCreditTokenChannels(p, tp)

    assert(token.creditTokenHandles.length == TemplateD3Constants.CreditDomainCount)
    assert(token.resourceCreditMask.getWidth == TemplateD3Constants.CreditDomainCount)
    assert(token.compositeHandle.domainMask.getWidth == TemplateD3Constants.CreditDomainCount)
    assert(bank.tokenBase.getWidth == tp.templateCreditBankIndexWidth)
    assert(bank.tokenCount.getWidth == tp.templateCreditBankCountWidth)
    assert(bank.rowHandleBase.getWidth == tp.templateCreditHandleIndexWidth)
    assert(bank.rowHandleCount.getWidth == tp.templateCreditHandleCountWidth)
    assert(channels.creditTokenLookup.bits.entryIndex.getWidth == tp.templateCreditBankIndexWidth)
    assert(channels.creditTokenConsume.bits.expectedOwner.rowPresent.getWidth == 1)
    assert(channels.creditTokenRelease.bits.releaseGeneration.getWidth == 64)
  }

  test("fatal quiesce and teardown bundles retain owner mask, owner index, generation, and descriptor identity") {
    val p = InterfaceParams()
    val tp = TemplateD3InterfaceParams()
    val req = new TemplateFatalQuiesceReq(p)
    val ack = new TemplateFatalQuiesceAck(p)
    val teardown = new TemplateFatalTeardown(p)
    val teardownAck = new TemplateFatalTeardownAck(p, tp)

    assert(req.requiredOwnerMask.getWidth == TemplateD3Constants.FatalOwnerCount)
    assert(req.generation.getWidth == 64)
    assert(req.descriptorKey.templateGeneration.getWidth == 64)
    assert(ack.ownerIndex.getWidth == log2Ceil(TemplateD3Constants.FatalOwnerCount))
    assert(ack.ownerMaskBit.getWidth == TemplateD3Constants.FatalOwnerCount)
    assert(ack.creditAckMask.getWidth == TemplateD3Constants.CreditDomainCount)
    assert(teardown.requiredOwnerMask.getWidth == TemplateD3Constants.FatalOwnerCount)
    assert(teardown.ackBitmap.getWidth == TemplateD3Constants.FatalOwnerCount)
    assert(teardownAck.releasedMask.getWidth == TemplateD3Constants.CreditDomainCount)
    assert(teardownAck.quarantinedMask.getWidth == TemplateD3Constants.CreditDomainCount)
    assert(teardownAck.fatalRecordIndex.getWidth == tp.fatalRecordIndexWidth)
  }

  test("Template D3 passive ABI elaborates projection, advance, reserve, fill, and fatal paths") {
    val p = InterfaceParams(robEntries = 64)
    val tp = TemplateD3InterfaceParams()
    ChiselStage.emitSystemVerilog(new TemplateD3BundleProbe(p, tp))
  }

  test("full-profile parameter rejects ROB configurations below the accepted minimum") {
    assertThrows[IllegalArgumentException] {
      new TemplateReserveRequest(InterfaceParams(robEntries = 16), TemplateD3InterfaceParams(fullProfileSupported = true))
    }
    new TemplateReserveRequest(InterfaceParams(robEntries = 16), TemplateD3InterfaceParams(fullProfileSupported = false))
  }
}
