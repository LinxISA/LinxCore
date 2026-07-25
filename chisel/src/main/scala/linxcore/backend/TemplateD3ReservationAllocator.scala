package linxcore.backend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup, PopCount, log2Ceil}
import linxcore.common.{
  InterfaceParams,
  TemplateD3Constants,
  TemplateD3InterfaceParams,
  TemplateRejectReason,
  TemplateReserveRequest,
  TemplateReserveResponse
}
import linxcore.recovery.{FlushBus, FlushControl}
import linxcore.rob.{ROBEntryStatus, ROBID}

class TemplateD3ReservationAllocatorIO(
    val p: InterfaceParams,
    val tp: TemplateD3InterfaceParams)
    extends Bundle {
  private val sizeWidth = log2Ceil(p.robEntries + 1)

  val flush = Input(new FlushBus(p.robEntries, p.peIdWidth, p.threadIdWidth, p.threadIdWidth, p.lsidWidth))
  val reserveReq = Flipped(Decoupled(new TemplateReserveRequest(p, tp)))
  val reserveResp = Decoupled(new TemplateReserveResponse(p, tp))

  val reservedMask = Output(UInt(p.robEntries.W))
  val liveCount = Output(UInt(sizeWidth.W))
  val freeCount = Output(UInt(sizeWidth.W))
  val allocValue = Output(UInt(p.robIndexWidth.W))
  val allocWrap = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val flushApplied = Output(Bool())
  val flushPruneMask = Output(UInt(p.robEntries.W))
  val headStatus = Output(ROBEntryStatus())
}

class TemplateD3ReservationAllocator(
    val p: InterfaceParams = InterfaceParams(robEntries = 32),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Module {
  require(p.robEntries > 1, "template D3 reservation ROB entries must be greater than one")
  require((p.robEntries & (p.robEntries - 1)) == 0, "template D3 reservation ROB entries must be a power of two")
  tp.requireFullProfileRob(p)

  private val entries = p.robEntries
  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)

  val io = IO(new TemplateD3ReservationAllocatorIO(p, tp))

  private def zeroRobId: ROBID =
    0.U.asTypeOf(new ROBID(entries))

  private def advance(value: UInt, wrap: Bool, amount: UInt): (UInt, Bool) = {
    val sum = value +& amount
    val entryCount = entries.U(sum.getWidth.W)
    val wraps = sum >= entryCount
    val nextValue = Mux(wraps, sum - entryCount, sum)(ptrWidth - 1, 0)
    (nextValue, wrap ^ wraps)
  }

  private def advanceRobId(base: ROBID, amount: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    val (value, wrap) = advance(base.value, base.wrap, amount)
    out.valid := base.valid
    out.value := value
    out.wrap := wrap
    out
  }

  private def descriptorFrom(req: TemplateReserveRequest, firstRid: ROBID, rowCount: UInt): TemplateReserveResponse = {
    val resp = Wire(new TemplateReserveResponse(p, tp))
    resp := 0.U.asTypeOf(resp)

    val lastRid = advanceRobId(firstRid, rowCount - 1.U)
    val generation = req.parent.pc ^ req.parent.raw

    resp.accepted := true.B
    resp.rejected := false.B
    resp.rejectReason := TemplateRejectReason.None
    resp.firstRid := firstRid.value
    resp.lastRid := lastRid.value
    resp.firstRidRobid := firstRid
    resp.lastRidRobid := lastRid
    resp.reservedMask := VecInit((0 until entries).map { slot =>
      VecInit((0 until TemplateD3Constants.MaxRows).map { ordinal =>
        val ordinalU = ordinal.U(5.W)
        val rid = advanceRobId(firstRid, ordinalU)
        ordinalU < rowCount && rid.value === slot.U
      }).asUInt.orR
    }).asUInt

    resp.descriptor.valid := true.B
    resp.descriptor.lxcpuId := 0.U
    resp.descriptor.lxcpuContextGeneration := 0.U
    resp.descriptor.peId := req.parent.peId
    resp.descriptor.stid := req.parent.stid
    resp.descriptor.engineLocalTid := req.parent.engineLocalTid
    resp.descriptor.bid := req.parent.bid.value
    resp.descriptor.bidRobid := req.parent.bid
    resp.descriptor.gid := req.parent.gid.value
    resp.descriptor.gidRobid := req.parent.gid
    resp.descriptor.groupBaseRid := firstRid.value
    resp.descriptor.groupBaseRidRobid := firstRid
    resp.descriptor.groupBaseRobSlot := firstRid.value
    resp.descriptor.groupRowCount := rowCount
    resp.descriptor.checkpointId := req.parent.checkpointId
    resp.descriptor.templateGeneration := generation
    resp.descriptor.sourcePc := req.parent.pc
    resp.descriptor.sourceRaw := req.parent.raw
    resp.descriptor.formId := req.demand.formId
    resp.descriptor.encodedN := req.demand.encodedN
    resp.descriptor.firstRid := firstRid.value
    resp.descriptor.firstRidRobid := firstRid
    resp.descriptor.firstRobSlot := firstRid.value
    resp.descriptor.rowCount := rowCount
    resp.descriptor.lastRid := lastRid.value
    resp.descriptor.lastRidRobid := lastRid
    resp.descriptor.leaseValid := true.B
    resp.descriptor.fatalPoisoned := false.B

    resp.creditTokenBank.valid := true.B
    resp.creditTokenBank.descriptorGeneration := generation
    resp.creditTokenBank.groupOwner := resp.descriptor
    resp.creditTokenBank.tokenBase := 0.U
    resp.creditTokenBank.tokenCount := rowCount
    resp.creditTokenBank.rowHandleBase := 0.U
    resp.creditTokenBank.rowHandleCount := rowCount
    resp.creditTokenBank.domainPresentMask := 1.U << 0

    for (ordinal <- 0 until TemplateD3Constants.MaxRows) {
      val ordinalU = ordinal.U(5.W)
      val valid = ordinalU < rowCount
      val rid = advanceRobId(firstRid, ordinalU)
      resp.tokens(ordinal).valid := valid
      resp.tokens(ordinal).descriptorGeneration := generation
      resp.tokens(ordinal).childOrdinal := ordinalU
      resp.tokens(ordinal).rid := rid
      resp.tokens(ordinal).rowKind := TemplateD3Constants.rowKind(req.demand.formId, req.demand.encodedN, ordinalU)
      resp.tokens(ordinal).resourceCreditMask := Mux(valid, 1.U(TemplateD3Constants.CreditDomainCount.W), 0.U)
      resp.tokens(ordinal).compositeHandle.valid := valid
      resp.tokens(ordinal).compositeHandle.descriptorGeneration := generation
      resp.tokens(ordinal).compositeHandle.childOrdinal := ordinalU
      resp.tokens(ordinal).compositeHandle.firstHandleIndex := ordinalU
      resp.tokens(ordinal).compositeHandle.handleCount := 1.U
      resp.tokens(ordinal).compositeHandle.domainMask := Mux(valid, 1.U(TemplateD3Constants.CreditDomainCount.W), 0.U)
      for (domain <- 0 until TemplateD3Constants.CreditDomainCount) {
        resp.tokens(ordinal).creditTokenHandles(domain).valid := valid && domain.U === 0.U
        resp.tokens(ordinal).creditTokenHandles(domain).bankId := 0.U
        resp.tokens(ordinal).creditTokenHandles(domain).entryIndex := ordinalU
        resp.tokens(ordinal).creditTokenHandles(domain).domain := MuxLookup(
          domain.U,
          linxcore.common.TemplateCreditDomain.ROB_ROW
        )(
          Seq(
            0.U -> linxcore.common.TemplateCreditDomain.ROB_ROW,
            1.U -> linxcore.common.TemplateCreditDomain.BROB_RANGE,
            2.U -> linxcore.common.TemplateCreditDomain.CHECKPOINT,
            3.U -> linxcore.common.TemplateCreditDomain.GPR_PHYS_DEST,
            4.U -> linxcore.common.TemplateCreditDomain.MAPQ,
            5.U -> linxcore.common.TemplateCreditDomain.IQ_ENTRY,
            6.U -> linxcore.common.TemplateCreditDomain.LIQ_ENTRY,
            7.U -> linxcore.common.TemplateCreditDomain.LOAD_ID,
            8.U -> linxcore.common.TemplateCreditDomain.STQ_ENTRY,
            9.U -> linxcore.common.TemplateCreditDomain.STORE_ID,
            10.U -> linxcore.common.TemplateCreditDomain.LSID,
            11.U -> linxcore.common.TemplateCreditDomain.VALIDATION,
            12.U -> linxcore.common.TemplateCreditDomain.TARGET_PUBLISH,
            13.U -> linxcore.common.TemplateCreditDomain.LEASE_FINAL,
            14.U -> linxcore.common.TemplateCreditDomain.INVALIDATION_TXN
          ))
        resp.tokens(ordinal).creditTokenHandles(domain).tokenGeneration := generation + ordinalU
        resp.tokens(ordinal).creditTokenHandles(domain).descriptorGeneration := generation
      }
    }
    resp
  }

  private def reject(reason: TemplateRejectReason.Type): TemplateReserveResponse = {
    val resp = Wire(new TemplateReserveResponse(p, tp))
    resp := 0.U.asTypeOf(resp)
    resp.accepted := false.B
    resp.rejected := true.B
    resp.rejectReason := reason
    resp
  }

  val rowValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val rowBid = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(entries)))))
  val rowRid = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(entries)))))
  val rowPeId = RegInit(VecInit(Seq.fill(entries)(0.U(p.peIdWidth.W))))
  val rowStid = RegInit(VecInit(Seq.fill(entries)(0.U(p.threadIdWidth.W))))
  val rowTid = RegInit(VecInit(Seq.fill(entries)(0.U(p.threadIdWidth.W))))
  val allocValue = RegInit(0.U(ptrWidth.W))
  val allocWrap = RegInit(false.B)
  val liveCount = RegInit(0.U(sizeWidth.W))
  val respValid = RegInit(false.B)
  val respBits = Reg(new TemplateReserveResponse(p, tp))

  private def rowInScope(idx: Int): Bool =
    (rowStid(idx) === io.flush.req.stid) &&
      (!io.flush.baseOnPE || (rowPeId(idx) === io.flush.req.peId)) &&
      (!io.flush.baseOnThread || (rowTid(idx) === io.flush.req.tid))

  val pruneMaskVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val direct = io.flush.req.valid && rowValid(idx) && rowInScope(idx) &&
      Mux(
        io.flush.baseOnBid,
        ROBID.lessEqual(io.flush.req.bid, rowBid(idx)),
        FlushControl.lessEqualBidRid(io.flush.req.bid, io.flush.req.rid, rowBid(idx), rowRid(idx))
      )
    pruneMaskVec(idx) := direct
  }
  val pruneMask = pruneMaskVec.asUInt
  val flushActive = io.flush.req.valid
  val flushApplied = pruneMask.orR
  val prunedCount = PopCount(pruneMaskVec)
  val oldestPrunedVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    oldestPrunedVec(idx) := pruneMaskVec(idx) && !VecInit((0 until entries).map { other =>
      pruneMaskVec(other) && ROBID.less(rowRid(other), rowRid(idx))
    }).asUInt.orR
  }
  val oldestPrunedRid = Wire(new ROBID(entries))
  oldestPrunedRid := Mux1H(oldestPrunedVec, rowRid)

  val reqRowCount = io.reserveReq.bits.demand.rowCount
  val demandWellFormed =
    TemplateD3Constants.demandWellFormed(
      io.reserveReq.bits.demand.formId,
      io.reserveReq.bits.demand.encodedN,
      reqRowCount)
  val freeCount = entries.U(sizeWidth.W) - liveCount
  val baseRid = Wire(new ROBID(entries))
  baseRid.valid := true.B
  baseRid.value := allocValue
  baseRid.wrap := allocWrap
  val targetIntervalOccupiedVec = Wire(Vec(entries, Bool()))
  for (ordinal <- 0 until entries) {
    val ordinalU = ordinal.U(sizeWidth.W)
    val targetRid = advanceRobId(baseRid, ordinalU)
    targetIntervalOccupiedVec(ordinal) := (ordinalU < reqRowCount) && rowValid(targetRid.value)
  }
  val targetIntervalClear = !targetIntervalOccupiedVec.asUInt.orR
  val canReserve =
    !flushActive && demandWellFormed && reqRowCount =/= 0.U && reqRowCount <= freeCount && targetIntervalClear
  val rejectReason =
    Mux(
      !TemplateD3Constants.formSupported(io.reserveReq.bits.demand.formId),
      TemplateRejectReason.UnsupportedForm,
      Mux(
        !TemplateD3Constants.encodedNSupported(io.reserveReq.bits.demand.encodedN) ||
          (reqRowCount =/= TemplateD3Constants.rowCount(io.reserveReq.bits.demand.formId, io.reserveReq.bits.demand.encodedN)),
        TemplateRejectReason.MalformedN,
        TemplateRejectReason.RobUnavailable))

  io.reserveReq.ready := !respValid && !flushActive
  val reqFire = io.reserveReq.valid && io.reserveReq.ready
  val acceptedFire = reqFire && canReserve
  val rejectedFire = reqFire && !canReserve

  val acceptedResp = descriptorFrom(io.reserveReq.bits, baseRid, reqRowCount)
  val rejectedResp = reject(rejectReason)
  val newResp = Mux(acceptedFire, acceptedResp, rejectedResp)

  io.reserveResp.valid := respValid
  io.reserveResp.bits := respBits
  io.reservedMask := rowValid.asUInt
  io.liveCount := liveCount
  io.freeCount := freeCount
  io.allocValue := allocValue
  io.allocWrap := allocWrap
  io.full := liveCount === entries.U
  io.empty := liveCount === 0.U
  io.flushApplied := flushApplied
  io.flushPruneMask := pruneMask
  io.headStatus := Mux(rowValid(0), ROBEntryStatus.ReservedUnfilled, ROBEntryStatus.Free)

  when(respValid && io.reserveResp.ready) {
    respValid := false.B
  }

  when(flushActive) {
    respValid := false.B
    respBits := 0.U.asTypeOf(respBits)
    for (idx <- 0 until entries) {
      when(pruneMaskVec(idx)) {
        rowValid(idx) := false.B
        rowBid(idx) := zeroRobId
        rowRid(idx) := zeroRobId
        rowPeId(idx) := 0.U
        rowStid(idx) := 0.U
        rowTid(idx) := 0.U
      }
    }
    liveCount := liveCount - prunedCount
    when(flushApplied) {
      allocValue := oldestPrunedRid.value
      allocWrap := oldestPrunedRid.wrap
    }
  }.elsewhen(reqFire) {
    respBits := newResp
    respValid := true.B
    when(acceptedFire) {
      for (idx <- 0 until entries) {
        val offset = idx.U(sizeWidth.W)
        val nextRid = advanceRobId(baseRid, offset)
        when(offset < reqRowCount) {
          rowValid(nextRid.value) := true.B
          rowBid(nextRid.value) := io.reserveReq.bits.parent.bid
          rowRid(nextRid.value) := nextRid
          rowPeId(nextRid.value) := io.reserveReq.bits.parent.peId
          rowStid(nextRid.value) := io.reserveReq.bits.parent.stid
          rowTid(nextRid.value) := io.reserveReq.bits.parent.engineLocalTid
        }
      }
      val (nextAllocValue, nextAllocWrap) = advance(allocValue, allocWrap, reqRowCount)
      allocValue := nextAllocValue
      allocWrap := nextAllocWrap
      liveCount := liveCount + reqRowCount
    }
  }
}
