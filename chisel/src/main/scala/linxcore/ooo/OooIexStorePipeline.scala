package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, Mux1H, MuxLookup, PriorityEncoderOH,
  Valid, is, log2Ceil, switch}

import linxcore.common.DestinationKind
import linxcore.lsu.{STQPhysicalLease, STQStoreRequest, STQStoreType}
import linxcore.params.CoreParams
import linxcore.top.interface.RecoveryPlan

class OooStqLeaseSet(
    val p: OooParams = OooParams(),
    val stqEntries: Int = 16)
    extends Bundle {
  val valid = Bool()
  val logicalMember = new RobMemberKey(p)
  val requestCount = UInt(p.memoryDemandWidth.W)
  val firstLsid = UInt(p.lsidWidth.W)
  val firstStoreId = UInt(p.lsidWidth.W)
  val leases = Vec(p.maxMemoryRequestsPerInstruction,
    new STQPhysicalLease(stqEntries, p.executeSlotGenerationWidth))
}

class OooIexStoreExecute(
    val p: OooParams = OooParams(),
    val stqEntries: Int = 16)
    extends Bundle {
  val execute = new OooIexExecuteTransaction(p)
  val lease = new OooStqLeaseSet(p, stqEntries)
}

class OooIexStorePipelineIO(
    val core: CoreParams,
    val stqEntries: Int)
    extends Bundle {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  val sta = Flipped(Decoupled(new OooIexStoreExecute(p, stqEntries)))
  val std = Flipped(Decoupled(new OooIexStoreExecute(p, stqEntries)))
  val fill = Decoupled(new STQStoreRequest(
    p.robIdentityGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth,
    physicalStqEntries = stqEntries,
    transactionIdWidth = p.transactionIdWidth))
  val recoveryApply = Flipped(Valid(new RecoveryPlan(core)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val staRejected = Output(Bool())
  val stdRejected = Output(Bool())
  val occupied = Output(Bool())
}

/** Independent retained STA and STD owners.  They never merge store state;
  * each accepted half writes the generation-qualified canonical STQ row.
  */
class OooIexStorePipeline(
    val core: CoreParams,
    val stqEntries: Int = 16)
    extends Module {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  OooRecoveryMembership.requireCompatible(p, core)
  require(p.maxMemoryRequestsPerInstruction == 2,
    "store pipeline currently supports one or two beats")

  val io = IO(new OooIexStorePipelineIO(core, stqEntries))
  private val beatWidth = math.max(1,
    log2Ceil(p.maxMemoryRequestsPerInstruction))

  private def killedByRecovery(value: OooIexStoreExecute): Bool =
    io.recoveryApply.valid &&
      OooRecoveryMembership.memberKilled(
        p, core, io.recoveryApply.bits, value.execute.i2.row.member)

  private def canceledByLoad(value: OooIexStoreExecute): Bool =
    io.loadCancel.map { cancel =>
      val row = value.execute.i2.row
      val sourceMatch = row.sources.map { source =>
        source.valid && source.specReady && source.load.valid &&
          cancel.bits.load.valid &&
          source.load.asUInt === cancel.bits.load.asUInt
      }.reduce(_ || _)
      cancel.valid && cancel.bits.stid === row.stid &&
        cancel.bits.epoch === row.epoch && sourceMatch
    }.reduce(_ || _)

  private def childMatches(
      value: OooIexStoreExecute): Bool = {
    val member = value.execute.i2.row.member
    val logical = value.lease.logicalMember
    member.group.asUInt === logical.group.asUInt &&
      member.bid.asUInt === logical.bid.asUInt &&
      member.brobGeneration === logical.brobGeneration &&
      member.residentGeneration === logical.residentGeneration &&
      member.memberIndex === logical.memberIndex
  }

  private def commonShape(value: OooIexStoreExecute): Bool = {
    val row = value.execute.i2.row
    val count = row.memoryOrder.requestCount
    val pair = row.recipe.recipeKind === OooOpcodeRecipeKind.PairStore.U
    row.valid && row.member.group.valid && row.member.bid.valid &&
      row.recipe.valid &&
      row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      row.recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U &&
      ((row.recipe.recipeKind === OooOpcodeRecipeKind.ScalarStore.U) || pair) &&
      row.memory.valid && row.memory.isStore && !row.memory.isLoad &&
      row.memoryOrder.valid && row.memoryOrder.memoryValid &&
      row.memoryOrder.isStore && !row.memoryOrder.isLoad &&
      ((count === 1.U) || (count === 2.U)) &&
      (pair === (count === 2.U)) && value.lease.valid &&
      value.lease.logicalMember.group.valid &&
      value.lease.logicalMember.bid.valid &&
      value.lease.requestCount === count &&
      value.lease.firstLsid === row.memoryOrder.firstLsid &&
      value.lease.firstStoreId === row.memoryOrder.firstTypeId &&
      value.lease.leases(0).valid &&
      ((count === 1.U) || value.lease.leases(1).valid)
  }

  private def staShape(value: OooIexStoreExecute): Bool = {
    val execute = value.execute
    val row = execute.i2.row
    commonShape(value) && childMatches(value) &&
      execute.ownerClass === OooUopClass.Agu &&
      row.reservation.uopClass === OooUopClass.Agu &&
      row.childIndex === 0.U &&
      execute.i2.sourceMask === row.memory.addressSourceMask
  }

  private def stdShape(value: OooIexStoreExecute): Bool = {
    val execute = value.execute
    val row = execute.i2.row
    commonShape(value) && childMatches(value) &&
      execute.ownerClass === OooUopClass.Std &&
      row.reservation.uopClass === OooUopClass.Std &&
      row.childIndex === 1.U &&
      execute.i2.sourceMask === row.memory.dataSourceMask
  }

  private def maskedValue(
      mask: UInt,
      values: Vec[UInt],
      second: Bool): UInt = {
    val firstOH = PriorityEncoderOH(mask)
    val selectedOH = Mux(second, PriorityEncoderOH(mask & ~firstOH), firstOH)
    Mux1H((0 until p.maxSourceOperands).map(index =>
      selectedOH(index) -> values(index)))
  }

  private def transformedIndex(
      memory: OooMemoryControl,
      raw: UInt): UInt = {
    val transformed = WireDefault(raw)
    switch(memory.indexMode) {
      is(OooMemoryIndexMode.SignExtend32) {
        transformed := Cat(Fill(p.pcWidth - 32, raw(31)), raw(31, 0))
      }
      is(OooMemoryIndexMode.ZeroExtend32) {
        transformed := raw(31, 0).pad(p.pcWidth)
      }
      is(OooMemoryIndexMode.Negate) {
        transformed := 0.U(p.pcWidth.W) - raw
      }
    }
    (transformed << memory.indexShift)(p.pcWidth - 1, 0)
  }

  val staValid = RegInit(false.B)
  val stdValid = RegInit(false.B)
  val staValue = Reg(new OooIexStoreExecute(p, stqEntries))
  val stdValue = Reg(new OooIexStoreExecute(p, stqEntries))
  val staBeat = RegInit(0.U(beatWidth.W))
  val stdBeat = RegInit(0.U(beatWidth.W))
  val preferStd = RegInit(false.B)

  val staKilled = staValid &&
    (killedByRecovery(staValue) || canceledByLoad(staValue))
  val stdKilled = stdValid &&
    (killedByRecovery(stdValue) || canceledByLoad(stdValue))
  val chooseSta = staValid && !staKilled &&
    (!stdValid || stdKilled || !preferStd)
  val chooseStd = stdValid && !stdKilled && !chooseSta
  val selected = Mux(chooseSta, staValue, stdValue)
  val selectedBeat = Mux(chooseSta, staBeat, stdBeat)
  val selectedLast =
    (selectedBeat +& 1.U) === selected.lease.requestCount

  private def makeFill(
      value: OooIexStoreExecute,
      beat: UInt,
      addressHalf: Bool): STQStoreRequest = {
    val req = Wire(chiselTypeOf(io.fill.bits))
    val execute = value.execute
    val row = execute.i2.row
    val memory = row.memory
    val logical = value.lease.logicalMember
    val addressMask = memory.addressSourceMask
    val base = maskedValue(addressMask, execute.i2.sourceData, false.B)
    val index = maskedValue(addressMask, execute.i2.sourceData, true.B)
    val baseAddress = MuxLookup(memory.addressMode.asUInt, 0.U)(Seq(
      OooMemoryAddressMode.BaseIndex.asUInt ->
        (base + transformedIndex(memory, index)),
      OooMemoryAddressMode.BaseOffset.asUInt -> (base + memory.offset),
      OooMemoryAddressMode.PcOffset.asUInt -> (execute.i2.pc + memory.offset)))
    val beatOffset = beat * memory.accessBytes
    val data = maskedValue(memory.dataSourceMask, execute.i2.sourceData,
      beat === 1.U)
    val fullLsid = value.lease.firstLsid + beat
    val fullStoreId = value.lease.firstStoreId + beat
    req := 0.U.asTypeOf(req)
    req.transactionId := row.transactionId
    req.storeType := Mux(addressHalf, STQStoreType.Addr, STQStoreType.Data)
    req.peId := logical.group.peId
    req.stid := logical.group.stid
    req.tid := logical.group.stid
    req.bid.valid := logical.bid.valid
    req.bid.value := logical.bid.value
    req.bid.wrap := logical.brobGeneration(0)
    req.gid.valid := logical.group.valid
    req.gid.value := logical.group.ridSlot
    req.gid.wrap := logical.group.ridGeneration(0)
    req.rid := req.gid
    req.lsId.valid := true.B
    req.lsId.value := fullLsid(p.ridSlotWidth - 1, 0)
    req.lsId.wrap := fullLsid(p.ridSlotWidth)
    req.lsIdFull := fullLsid
    req.storeIdFullValid := true.B
    req.storeIdFull := fullStoreId
    req.logicalStoreValid := true.B
    req.logicalFirstLsid := value.lease.firstLsid
    req.logicalFirstStoreId := value.lease.firstStoreId
    req.logicalRequestCount := value.lease.requestCount
    req.logicalBeat := beat
    req.exactOwner.valid := logical.group.valid
    req.exactOwner.peId := logical.group.peId
    req.exactOwner.stid := logical.group.stid
    req.exactOwner.nativeBidValid := logical.bid.valid
    req.exactOwner.nativeBid := logical.bid.value
    req.exactOwner.brobGeneration := logical.brobGeneration
    req.exactOwner.ridSlot := logical.group.ridSlot
    req.exactOwner.ridGeneration := logical.group.ridGeneration
    req.exactOwner.memberIndex := logical.memberIndex
    req.exactOwner.residentGeneration := logical.residentGeneration
    req.lease := value.lease.leases(beat)
    req.tSeq.valid := false.B
    req.uSeq.valid := false.B
    req.tuDstValid := false.B
    req.tuDstKind := DestinationKind.None
    req.pc := Mux(execute.i2.pcValid, execute.i2.pc, 0.U)
    req.addr := baseAddress + beatOffset
    req.data := data
    req.size := memory.accessBytes
    req.stackValid := false.B
    req.scalarIex := true.B
    req.simtLane := 0.U
    req
  }

  io.fill.valid := (chooseSta || chooseStd) && !io.recoveryApply.valid
  io.fill.bits := makeFill(selected, selectedBeat, chooseSta)
  val fillFire = io.fill.fire
  val staFinishing = fillFire && chooseSta && selectedLast
  val stdFinishing = fillFire && chooseStd && selectedLast
  val staIncomingKilled = killedByRecovery(io.sta.bits) ||
    canceledByLoad(io.sta.bits)
  val stdIncomingKilled = killedByRecovery(io.std.bits) ||
    canceledByLoad(io.std.bits)
  val staExact = staShape(io.sta.bits)
  val stdExact = stdShape(io.std.bits)
  io.sta.ready := (!staValid || staKilled || staFinishing) &&
    staExact && !staIncomingKilled
  io.std.ready := (!stdValid || stdKilled || stdFinishing) &&
    stdExact && !stdIncomingKilled
  io.staRejected := io.sta.valid && (!staExact || staIncomingKilled)
  io.stdRejected := io.std.valid && (!stdExact || stdIncomingKilled)
  io.occupied := (staValid && !staKilled) || (stdValid && !stdKilled)

  when(staKilled || staFinishing) {
    staValid := false.B
    staBeat := 0.U
  }.elsewhen(fillFire && chooseSta) {
    staBeat := staBeat + 1.U
  }
  when(stdKilled || stdFinishing) {
    stdValid := false.B
    stdBeat := 0.U
  }.elsewhen(fillFire && chooseStd) {
    stdBeat := stdBeat + 1.U
  }
  when(fillFire) {
    preferStd := chooseSta
  }
  when(io.sta.fire) {
    staValid := true.B
    staValue := io.sta.bits
    staBeat := 0.U
  }
  when(io.std.fire) {
    stdValid := true.B
    stdValue := io.std.bits
    stdBeat := 0.U
  }
}
