package linxcore.bctrl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common._
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

class TemplateD3RowFillProbeIO(
    val p: InterfaceParams,
    val tp: TemplateD3InterfaceParams)
    extends Bundle {
  val loadValid = Input(Bool())
  val loadReady = Output(Bool())
  val fillValid = Input(Bool())
  val fillReady = Output(Bool())
  val formId = Input(UInt(8.W))
  val encodedN = Input(UInt(5.W))
  val childOrdinal = Input(UInt(5.W))
  val baseRidValue = Input(UInt(p.robIndexWidth.W))
  val baseRidWrap = Input(Bool())
  val bidValue = Input(UInt(p.robIndexWidth.W))
  val bidWrap = Input(Bool())
  val corruptOwnerRid = Input(Bool())
  val corruptTokenRid = Input(Bool())
  val corruptRowKind = Input(Bool())
  val corruptMemoryShape = Input(Bool())
  val cancelValid = Input(Bool())
  val recoveryValid = Input(Bool())

  val ackValid = Output(Bool())
  val ackOrdinal = Output(UInt(5.W))
  val ackRidValue = Output(UInt(p.robIndexWidth.W))
  val live = Output(Bool())
  val nextOrdinal = Output(UInt(5.W))
  val consumedMask = Output(UInt(TemplateD3Constants.MaxRows.W))
  val unfilledCount = Output(UInt(5.W))
  val recoveryAckValid = Output(Bool())
  val killedMask = Output(UInt(TemplateD3Constants.MaxRows.W))
  val retainedMask = Output(UInt(TemplateD3Constants.MaxRows.W))
  val fatalValid = Output(Bool())
  val fatalReason = Output(UInt(3.W))
  val fatalCode = Output(UInt(5.W))
}

class TemplateD3RowFillProbe(
    val p: InterfaceParams = InterfaceParams(robEntries = 32),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Module {
  val io = IO(new TemplateD3RowFillProbeIO(p, tp))
  private val dut = Module(new TemplateD3RowFill(p, tp))

  private def robid(value: UInt, wrap: Bool): ROBID = {
    val out = Wire(new ROBID(p.robEntries))
    out.valid := true.B
    out.value := value
    out.wrap := wrap
    out
  }

  private def descriptor: TemplateGroupDescriptor = {
    val desc = Wire(new TemplateGroupDescriptor(p))
    desc := 0.U.asTypeOf(desc)
    val rowCount = TemplateD3Constants.rowCount(io.formId, io.encodedN)
    val base = robid(io.baseRidValue, io.baseRidWrap)
    val last = ROBID.add(base, rowCount - 1.U)

    desc.valid := true.B
    desc.peId := 1.U
    desc.stid := 2.U
    desc.engineLocalTid := 3.U
    desc.bid := io.bidValue
    desc.bidRobid := robid(io.bidValue, io.bidWrap)
    desc.gid := io.bidValue
    desc.gidRobid := robid(io.bidValue, io.bidWrap)
    desc.groupBaseRid := io.baseRidValue
    desc.groupBaseRidRobid := base
    desc.groupBaseRobSlot := io.baseRidValue
    desc.groupRowCount := rowCount
    desc.checkpointId := 5.U
    desc.templateGeneration := 0x55aa.U
    desc.sourcePc := 0x1000.U
    desc.sourceRaw := 0x13.U
    desc.formId := io.formId
    desc.encodedN := io.encodedN
    desc.firstRid := io.baseRidValue
    desc.firstRidRobid := base
    desc.firstRobSlot := io.baseRidValue
    desc.rowCount := rowCount
    desc.lastRid := last.value
    desc.lastRidRobid := last
    desc.leaseValid := true.B
    desc
  }

  private def tokenFor(desc: TemplateGroupDescriptor, ordinal: UInt): TemplateFillToken = {
    val token = Wire(new TemplateFillToken(p, tp))
    token := 0.U.asTypeOf(token)
    val rid = ROBID.add(desc.groupBaseRidRobid, ordinal)

    token.valid := true.B
    token.descriptorGeneration := desc.templateGeneration
    token.childOrdinal := ordinal
    token.rid := rid
    token.rowKind := TemplateD3Constants.rowKind(desc.formId, desc.encodedN, ordinal)
    token.resourceCreditMask := 1.U
    token.compositeHandle.valid := true.B
    token.compositeHandle.descriptorGeneration := desc.templateGeneration
    token.compositeHandle.childOrdinal := ordinal
    token.compositeHandle.firstHandleIndex := ordinal
    token.compositeHandle.handleCount := 1.U
    token.compositeHandle.domainMask := 1.U
    token.creditTokenHandles(0).valid := true.B
    token.creditTokenHandles(0).entryIndex := ordinal
    token.creditTokenHandles(0).domain := TemplateCreditDomain.ROB_ROW
    token.creditTokenHandles(0).tokenGeneration := desc.templateGeneration + ordinal
    token.creditTokenHandles(0).descriptorGeneration := desc.templateGeneration
    token
  }

  private def ownerFor(desc: TemplateGroupDescriptor, ordinal: UInt): TemplateOwnerID = {
    val owner = Wire(new TemplateOwnerID(p))
    owner := 0.U.asTypeOf(owner)
    val expectedRid = ROBID.add(desc.groupBaseRidRobid, ordinal)
    val kind = TemplateD3Constants.rowKind(desc.formId, desc.encodedN, ordinal)

    owner.lxcpuId := desc.lxcpuId
    owner.lxcpuContextGeneration := desc.lxcpuContextGeneration
    owner.peId := desc.peId
    owner.stid := desc.stid
    owner.engineLocalTid := desc.engineLocalTid
    owner.bid := desc.bid
    owner.bidRobid := desc.bidRobid
    owner.gid := desc.gid
    owner.gidRobid := desc.gidRobid
    owner.groupBaseRid := desc.groupBaseRid
    owner.groupBaseRidRobid := desc.groupBaseRidRobid
    owner.groupBaseRobSlot := desc.groupBaseRobSlot
    owner.groupRowCount := desc.rowCount
    owner.checkpointId := desc.checkpointId
    owner.templateGeneration := desc.templateGeneration
    owner.sourcePc := desc.sourcePc
    owner.sourceRaw := desc.sourceRaw
    owner.formId := desc.formId
    owner.encodedN := desc.encodedN
    owner.rowPresent := true.B
    owner.rowKind := kind
    owner.childOrdinal := ordinal
    owner.rid := expectedRid.value
    owner.ridRobid := expectedRid
    owner.robSlot := expectedRid.value
    owner.rowGeneration := desc.templateGeneration + ordinal
    when(kind === TemplateRowKind.VLOAD.asUInt || kind === TemplateRowKind.LOAD.asUInt) {
      owner.lsidValid := true.B
      owner.lsidValue := 100.U + ordinal
      owner.lsidWrapOrGeneration := desc.templateGeneration
      owner.loadIdValid := true.B
      owner.loadIdValue := 200.U + ordinal
      owner.loadIdGeneration := desc.templateGeneration
      owner.loadReplayGeneration := desc.templateGeneration + 1.U
    }.elsewhen(kind === TemplateRowKind.STORE.asUInt) {
      owner.lsidValid := true.B
      owner.lsidValue := 300.U + ordinal
      owner.lsidWrapOrGeneration := desc.templateGeneration
      owner.storeIdValid := true.B
      owner.storeIdValue := 400.U + ordinal
      owner.storeIdGeneration := desc.templateGeneration
    }
    owner
  }

  val desc = descriptor
  val selectedToken = tokenFor(desc, io.childOrdinal)
  val selectedOwner = ownerFor(desc, io.childOrdinal)
  val corruptedOwner = Wire(new TemplateOwnerID(p))
  corruptedOwner := selectedOwner
  when(io.corruptOwnerRid) {
    corruptedOwner.rid := selectedOwner.rid + 1.U
    corruptedOwner.ridRobid.value := selectedOwner.ridRobid.value + 1.U
  }
  when(io.corruptRowKind) {
    corruptedOwner.rowKind := TemplateRowKind.Invalid.asUInt
  }
  when(io.corruptMemoryShape) {
    corruptedOwner.lsidValid := false.B
    corruptedOwner.loadIdValid := false.B
    corruptedOwner.storeIdValid := true.B
    corruptedOwner.storeIdValue := 99.U
  }

  val corruptedToken = Wire(new TemplateFillToken(p, tp))
  corruptedToken := selectedToken
  when(io.corruptTokenRid) {
    corruptedToken.rid.value := selectedToken.rid.value + 1.U
  }

  dut.io.reserveResp.valid := io.loadValid
  dut.io.reserveResp.bits := 0.U.asTypeOf(dut.io.reserveResp.bits)
  dut.io.reserveResp.bits.accepted := true.B
  dut.io.reserveResp.bits.descriptor := desc
  dut.io.reserveResp.bits.firstRid := desc.firstRid
  dut.io.reserveResp.bits.firstRidRobid := desc.firstRidRobid
  dut.io.reserveResp.bits.lastRid := desc.lastRid
  dut.io.reserveResp.bits.lastRidRobid := desc.lastRidRobid
  dut.io.reserveResp.bits.reservedMask := 0.U
  for (idx <- 0 until TemplateD3Constants.MaxRows) {
    dut.io.reserveResp.bits.tokens(idx) := tokenFor(desc, idx.U)
  }

  dut.io.rowFill.valid := io.fillValid
  dut.io.rowFill.bits := 0.U.asTypeOf(dut.io.rowFill.bits)
  dut.io.rowFill.bits.owner := corruptedOwner
  dut.io.rowFill.bits.token := corruptedToken
  dut.io.rowFill.bits.creditTokenHandle := corruptedToken.compositeHandle
  dut.io.rowFill.bits.isFinal := corruptedOwner.rowKind === TemplateRowKind.FINAL.asUInt
  dut.io.cancel.valid := io.cancelValid
  dut.io.cancel.bits.owner := desc
  dut.io.cancel.bits.valid := io.cancelValid
  dut.io.recovery.valid := io.recoveryValid
  dut.io.recovery.bits.valid := io.recoveryValid
  dut.io.recovery.bits.stid := desc.stid
  dut.io.recovery.bits.firstKilledBid := desc.bidRobid
  dut.io.recovery.bits.inclusive := true.B

  io.loadReady := dut.io.reserveResp.ready
  io.fillReady := dut.io.rowFill.ready
  io.ackValid := dut.io.rowFillAck.valid
  io.ackOrdinal := dut.io.rowFillAck.bits.childOrdinal
  io.ackRidValue := dut.io.rowFillAck.bits.rid
  io.live := dut.io.live
  io.nextOrdinal := dut.io.nextOrdinal
  io.consumedMask := dut.io.consumedMask
  io.unfilledCount := dut.io.unfilledCount
  io.recoveryAckValid := dut.io.recoveryAck.valid
  io.killedMask := dut.io.recoveryAck.bits.killedMask
  io.retainedMask := dut.io.recoveryAck.bits.retainedMask
  io.fatalValid := dut.io.fatal.valid
  io.fatalReason := dut.io.fatal.reason.asUInt
  io.fatalCode := dut.io.fatal.code.asUInt
}

class TemplateD3RowFillSpec extends AnyFunSuite with ChiselSim {
  private val fentry = TemplateForm.FENTRY.asUInt.litValue
  private val fretStk = TemplateForm.FRET_STK.asUInt.litValue

  private def clear(dut: TemplateD3RowFillProbe): Unit = {
    dut.io.loadValid.poke(false.B)
    dut.io.fillValid.poke(false.B)
    dut.io.formId.poke(0.U)
    dut.io.encodedN.poke(0.U)
    dut.io.childOrdinal.poke(0.U)
    dut.io.baseRidValue.poke(0.U)
    dut.io.baseRidWrap.poke(false.B)
    dut.io.bidValue.poke(1.U)
    dut.io.bidWrap.poke(false.B)
    dut.io.corruptOwnerRid.poke(false.B)
    dut.io.corruptTokenRid.poke(false.B)
    dut.io.corruptRowKind.poke(false.B)
    dut.io.corruptMemoryShape.poke(false.B)
    dut.io.cancelValid.poke(false.B)
    dut.io.recoveryValid.poke(false.B)
  }

  private def loadGroup(
      dut: TemplateD3RowFillProbe,
      formId: BigInt,
      encodedN: Int,
      baseRid: Int = 0,
      baseWrap: Boolean = false,
      bid: Int = 4): Unit = {
    clear(dut)
    dut.io.loadValid.poke(true.B)
    dut.io.formId.poke(formId.U)
    dut.io.encodedN.poke(encodedN.U)
    dut.io.baseRidValue.poke(baseRid.U)
    dut.io.baseRidWrap.poke(baseWrap.B)
    dut.io.bidValue.poke(bid.U)
    dut.io.loadReady.expect(true.B)
    dut.clock.step()
    dut.io.live.expect(true.B)
    dut.io.nextOrdinal.expect(0.U)
    dut.io.loadValid.poke(false.B)
  }

  private def fillOrdinal(dut: TemplateD3RowFillProbe, ordinal: Int, expectRid: Int): Unit = {
    dut.io.fillValid.poke(true.B)
    dut.io.childOrdinal.poke(ordinal.U)
    dut.io.fillReady.expect(true.B)
    dut.io.ackValid.expect(true.B)
    dut.io.ackOrdinal.expect(ordinal.U)
    dut.io.ackRidValue.expect(expectRid.U)
    dut.clock.step()
    dut.io.fillValid.poke(false.B)
  }

  test("Chisel TemplateD3RowFill elaborates the standalone row fill ABI") {
    val sv = ChiselStage.emitSystemVerilog(new TemplateD3RowFill())

    assert(sv.contains("module TemplateD3RowFill"))
    assert(sv.contains("io_reserveResp_ready"))
    assert(sv.contains("io_rowFill_ready"))
    assert(sv.contains("io_rowFillAck_valid"))
    assert(sv.contains("io_recoveryAck_valid"))
    assert(sv.contains("io_fatal_valid"))
  }

  test("sim consumes a directed FENTRY token stream in ordinal RID order") {
    simulate(new TemplateD3RowFillProbe()) { dut =>
      loadGroup(dut, fentry, encodedN = 3)

      for (ordinal <- 0 until TemplateD3Constants.exactRowCount(1, 3)) {
        fillOrdinal(dut, ordinal, expectRid = ordinal)
        dut.io.nextOrdinal.expect((ordinal + 1).U)
      }

      dut.io.live.expect(false.B)
      dut.io.consumedMask.expect(0x3f.U)
      dut.io.fatalValid.expect(false.B)
    }
  }

  test("sim consumes a wrapped directed FRET_STK max-profile stream with exact RIDs") {
    simulate(new TemplateD3RowFillProbe()) { dut =>
      loadGroup(dut, fretStk, encodedN = 22, baseRid = 8, bid = 7)

      for (ordinal <- 0 until TemplateD3Constants.exactRowCount(4, 22)) {
        fillOrdinal(dut, ordinal, expectRid = (8 + ordinal) & 31)
      }

      dut.io.live.expect(false.B)
      dut.io.consumedMask.expect(((BigInt(1) << 28) - 1).U)
      dut.io.fatalValid.expect(false.B)
    }
  }

  test("sim latches fatal and suppresses ack for stale token RID identity") {
    simulate(new TemplateD3RowFillProbe()) { dut =>
      loadGroup(dut, fentry, encodedN = 1)

      dut.io.fillValid.poke(true.B)
      dut.io.childOrdinal.poke(0.U)
      dut.io.corruptTokenRid.poke(true.B)
      dut.io.fillReady.expect(true.B)
      dut.io.ackValid.expect(false.B)
      dut.clock.step()
      dut.io.fatalValid.expect(true.B)
      dut.io.fatalReason.expect(TemplateFatalReason.BadOwnerIdentity.asUInt)
      dut.io.fatalCode.expect(TemplateFatalCode.BadOwner.asUInt)
      dut.io.nextOrdinal.expect(0.U)
    }
  }

  test("sim latches fatal for bad STORE memory identity before mutation") {
    simulate(new TemplateD3RowFillProbe()) { dut =>
      loadGroup(dut, fentry, encodedN = 1)
      fillOrdinal(dut, 0, expectRid = 0)
      fillOrdinal(dut, 1, expectRid = 1)

      dut.io.fillValid.poke(true.B)
      dut.io.childOrdinal.poke(2.U)
      dut.io.corruptMemoryShape.poke(true.B)
      dut.io.fillReady.expect(true.B)
      dut.io.ackValid.expect(false.B)
      dut.clock.step()
      dut.io.fatalValid.expect(true.B)
      dut.io.fatalReason.expect(TemplateFatalReason.BadMemoryIdentity.asUInt)
      dut.io.nextOrdinal.expect(2.U)
    }
  }

  test("sim gives cancel priority over same-cycle fill and reports unfilled rows") {
    simulate(new TemplateD3RowFillProbe()) { dut =>
      loadGroup(dut, fentry, encodedN = 2)
      fillOrdinal(dut, 0, expectRid = 0)

      dut.io.fillValid.poke(true.B)
      dut.io.childOrdinal.poke(1.U)
      dut.io.cancelValid.poke(true.B)
      dut.io.fillReady.expect(false.B)
      dut.io.ackValid.expect(false.B)
      dut.io.recoveryAckValid.expect(true.B)
      dut.io.killedMask.expect(0x1e.U)
      dut.io.retainedMask.expect(0x1.U)
      dut.clock.step()
      dut.io.live.expect(false.B)
      dut.io.fatalValid.expect(false.B)
    }
  }

  test("sim gives accepted recovery priority over same-cycle fill") {
    simulate(new TemplateD3RowFillProbe()) { dut =>
      loadGroup(dut, fretStk, encodedN = 2, bid = 9)
      fillOrdinal(dut, 0, expectRid = 0)
      fillOrdinal(dut, 1, expectRid = 1)

      dut.io.fillValid.poke(true.B)
      dut.io.childOrdinal.poke(2.U)
      dut.io.recoveryValid.poke(true.B)
      dut.io.fillReady.expect(false.B)
      dut.io.ackValid.expect(false.B)
      dut.io.recoveryAckValid.expect(true.B)
      dut.io.killedMask.expect(0xfc.U)
      dut.io.retainedMask.expect(0x3.U)
      dut.clock.step()
      dut.io.live.expect(false.B)
      dut.io.fatalValid.expect(false.B)
    }
  }
}
