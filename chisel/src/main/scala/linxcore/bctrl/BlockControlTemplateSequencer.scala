package linxcore.bctrl

import chisel3._
import chisel3.util._
import linxcore.common.InterfaceParams
import linxcore.frontend.FrontendOpcodeDecodeTable

object TemplateSequencerState extends ChiselEnum {
  val Idle, CaptureParent, GatherChildren, WaitCompletionSelect, WaitParentCommit,
      WaitOlderStores, StoreBurst, RfBurst, DrainDone, Cancel = Value
}

class TemplateRfReadRequest(
    val p: InterfaceParams,
    val physTagWidth: Int,
    val childIndexWidth: Int)
    extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val archReg = UInt(p.archRegWidth.W)
  val physTag = UInt(physTagWidth.W)
}

class TemplateRfReadResponse(
    val p: InterfaceParams,
    val physTagWidth: Int,
    val childIndexWidth: Int)
    extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val physTag = UInt(physTagWidth.W)
  val data = UInt(p.immWidth.W)
}

class TemplateLoadRequest(val p: InterfaceParams, val childIndexWidth: Int) extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val archReg = UInt(p.archRegWidth.W)
  val addr = UInt(p.immWidth.W)
  val size = UInt(p.memSizeWidth.W)
}

class TemplateLoadResponse(val p: InterfaceParams, val childIndexWidth: Int) extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val addr = UInt(p.immWidth.W)
  val data = UInt(p.immWidth.W)
}

class TemplateCompletion(
    val p: InterfaceParams,
    val retainedCountWidth: Int)
    extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val parentSlot = UInt(p.robIndexWidth.W)
  val result = UInt(p.immWidth.W)
  val newSp = UInt(p.immWidth.W)
  val nextPc = UInt(p.pcWidth.W)
  val redirectValid = Bool()
  val retainedStoreCount = UInt(retainedCountWidth.W)
  val retainedRfCount = UInt(retainedCountWidth.W)
}

class TemplateScbRequest(
    val p: InterfaceParams,
    val stqEntries: Int,
    val lsidWidth: Int,
    val childIndexWidth: Int)
    extends Bundle {
  val ownsStqRow = Bool()
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val childLast = Bool()
  val addr = UInt(p.immWidth.W)
  val data = UInt(p.immWidth.W)
  val size = UInt(p.memSizeWidth.W)
  val lsId = UInt(lsidWidth.W)
  val stqIndex = UInt(log2Ceil(stqEntries).W)
}

class TemplateRfWriteRequest(
    val p: InterfaceParams,
    val physTagWidth: Int,
    val childIndexWidth: Int)
    extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val childLast = Bool()
  val archReg = UInt(p.archRegWidth.W)
  val physTag = UInt(physTagWidth.W)
  val data = UInt(p.immWidth.W)
}

class TemplateStoreObservation(
    val p: InterfaceParams,
    val childIndexWidth: Int)
    extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val addr = UInt(p.immWidth.W)
  val data = UInt(p.immWidth.W)
  val size = UInt(p.memSizeWidth.W)
}

class TemplateLookupObservation(
    val p: InterfaceParams,
    val childIndexWidth: Int)
    extends Bundle {
  val parent = new TemplateParentIdentity(p)
  val childIndex = UInt(childIndexWidth.W)
  val addr = UInt(p.immWidth.W)
  val data = UInt(p.immWidth.W)
}

class BlockControlTemplateSequencerIO(
    val p: InterfaceParams,
    val archRegs: Int,
    val physTagWidth: Int,
    val stqEntries: Int,
    val lsidWidth: Int,
    val maxChildren: Int)
    extends Bundle {
  private val childIndexWidth = math.max(1, log2Ceil(maxChildren))
  private val retainedCountWidth = math.max(1, log2Ceil(maxChildren + 1))

  val parentRequest = Flipped(Decoupled(new TemplateParentRequest(p, archRegs, physTagWidth)))
  val rfReadRequest = Decoupled(new TemplateRfReadRequest(p, physTagWidth, childIndexWidth))
  val rfReadResponse = Flipped(Decoupled(new TemplateRfReadResponse(p, physTagWidth, childIndexWidth)))
  val loadRequest = Decoupled(new TemplateLoadRequest(p, childIndexWidth))
  val loadResponse = Flipped(Decoupled(new TemplateLoadResponse(p, childIndexWidth))
  )

  val selectedTemplate = Input(Bool())
  val parentCommit = Flipped(Valid(new TemplateParentIdentity(p)))
  val storeGrant = Input(Bool())
  val recovery = Flipped(Valid(new TemplateParentIdentity(p)))
  val recoveryKillsActive = Input(Bool())
  val globalClear = Input(Bool())

  val completion = Valid(new TemplateCompletion(p, retainedCountWidth))
  val storeRequest = Decoupled(new TemplateScbRequest(p, stqEntries, lsidWidth, childIndexWidth))
  val rfWriteRequest = Decoupled(new TemplateRfWriteRequest(p, physTagWidth, childIndexWidth))
  val storeObservation = Valid(new TemplateStoreObservation(p, childIndexWidth))
  val lookupObservation = Valid(new TemplateLookupObservation(p, childIndexWidth))

  val activeValid = Output(Bool())
  val activeIdentity = Output(new TemplateParentIdentity(p))
  val activeMap = Output(new TemplateMapSnapshot(archRegs, physTagWidth))
  val activeOldSp = Output(UInt(p.immWidth.W))
  val activeSrcData = Output(Vec(3, UInt(p.immWidth.W)))
  val state = Output(TemplateSequencerState())
  val decodeFence = Output(Bool())
  val issueFence = Output(Bool())
  val memoryFence = Output(Bool())
  val parentIssueRelease = Output(Bool())
  val terminal = Output(Bool())
  val committed = Output(Bool())
  val spPublishValid = Output(Bool())
  val spPublishValue = Output(UInt(p.immWidth.W))
  val nextPc = Output(UInt(p.pcWidth.W))
  val selfRestartObserved = Output(Bool())
  val illegalDiscardAttempt = Output(Bool())
  val cancelObserved = Output(Bool())
  val retainedStoreCount = Output(UInt(retainedCountWidth.W))
  val retainedRfCount = Output(UInt(retainedCountWidth.W))
  val tailZero = Output(Bool())
  val staleResponseDropCount = Output(UInt(32.W))
  val acceptedStoreCount = Output(UInt(32.W))
  val acceptedRfWriteCount = Output(UInt(32.W))
}

object BlockControlTemplateSequencer {
  val FirstLegalRegister = 2
  val LastLegalRegister = 23
  val RegisterNamespaceSize = 32

  def legalRing(m: Int, n: Int): Seq[Int] = {
    require(m >= FirstLegalRegister && m <= LastLegalRegister)
    require(n >= FirstLegalRegister && n <= LastLegalRegister)
    val upper = if (m <= n) n else n + RegisterNamespaceSize
    (m to upper).map(_ % RegisterNamespaceSize).filter(idx =>
      idx >= FirstLegalRegister && idx <= LastLegalRegister)
  }

  def frameAmount(imm: BigInt): BigInt = imm & 0x7fff
  def nextSp(opcode: Int, oldSp: BigInt, imm: BigInt, width: Int = 64): BigInt = {
    val mask = (BigInt(1) << width) - 1
    val frame = frameAmount(imm)
    if (opcode == FrontendOpcodeDecodeTable.OP_FENTRY) (oldSp - frame) & mask
    else (oldSp + frame) & mask
  }

  def childAddresses(opcode: Int, oldSp: BigInt, imm: BigInt, count: Int): Seq[BigInt] = {
    val sp = nextSp(opcode, oldSp, imm)
    if (opcode == FrontendOpcodeDecodeTable.OP_FENTRY) {
      (0 until count).map(idx => (sp + frameAmount(imm) - 8 * (idx + 1)) & ((BigInt(1) << 64) - 1))
    } else {
      (0 until count).map(idx => (sp - 8 * (idx + 1)) & ((BigInt(1) << 64) - 1))
    }
  }
}

class BlockControlTemplateSequencer(
    val p: InterfaceParams = InterfaceParams(),
    val archRegs: Int = 32,
    val physTagWidth: Int = 6,
    val stqEntries: Int = 16,
    val lsidWidth: Int = 32,
    val maxChildren: Int = 22)
    extends Module {
  require(archRegs >= 24, "template sequencer requires architectural registers 2 through 23")
  require(maxChildren >= 22, "template sequencer must retain the complete legal register ring")
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0, "STQ entries must be a power of two")
  require(lsidWidth >= 32, "template LSID must preserve the full memory-order namespace")

  private val childIndexWidth = math.max(1, log2Ceil(maxChildren))
  private val retainedCountWidth = math.max(1, log2Ceil(maxChildren + 1))

  val io = IO(new BlockControlTemplateSequencerIO(
    p,
    archRegs,
    physTagWidth,
    stqEntries,
    lsidWidth,
    maxChildren
  ))

  val state = RegInit(TemplateSequencerState.Idle)
  val activeValid = RegInit(false.B)
  val active = RegInit(0.U.asTypeOf(new TemplateParentRequest(p, archRegs, physTagWidth)))
  val newSp = RegInit(0.U(p.immWidth.W))
  val targetPc = RegInit(0.U(p.pcWidth.W))
  val currentArch = RegInit(0.U(p.archRegWidth.W))
  val childTotal = RegInit(0.U(retainedCountWidth.W))
  val gatherIndex = RegInit(0.U(childIndexWidth.W))
  val drainIndex = RegInit(0.U(childIndexWidth.W))
  val requestOutstanding = RegInit(false.B)
  val completed = RegInit(false.B)
  val committed = RegInit(false.B)
  val decodeFence = RegInit(false.B)
  val issueFence = RegInit(false.B)
  val memoryFence = RegInit(false.B)

  val childArch = Reg(Vec(maxChildren, UInt(p.archRegWidth.W)))
  val childTag = Reg(Vec(maxChildren, UInt(physTagWidth.W)))
  val childAddr = Reg(Vec(maxChildren, UInt(p.immWidth.W)))
  val childData = Reg(Vec(maxChildren, UInt(p.immWidth.W)))

  val staleResponseDropCount = RegInit(0.U(32.W))
  val acceptedStoreCount = RegInit(0.U(32.W))
  val acceptedRfWriteCount = RegInit(0.U(32.W))

  val opcode = active.sidecar.identity.opcode
  val isFentry = opcode === FrontendOpcodeDecodeTable.OP_FENTRY.U
  val isFexit = opcode === FrontendOpcodeDecodeTable.OP_FEXIT.U
  val isFretRa = opcode === FrontendOpcodeDecodeTable.OP_FRET_RA.U
  val isFretStk = opcode === FrontendOpcodeDecodeTable.OP_FRET_STK.U
  val isRestore = isFexit || isFretRa || isFretStk

  val rangeM = active.sidecar.rangeM
  val rangeN = active.sidecar.rangeN
  val wrappedCount = (24.U - rangeM) + (rangeN - 1.U)
  val normalCount = rangeN - rangeM + 1.U
  val computedChildTotal = Mux(rangeM <= rangeN, normalCount, wrappedCount)
  val frameAmount = active.sidecar.src0Imm(14, 0)
  val extendedFrameAmount = Wire(UInt(p.immWidth.W))
  extendedFrameAmount := frameAmount
  val computedNewSp = Mux(isFentry, active.oldSp - extendedFrameAmount, active.oldSp + extendedFrameAmount)

  private def currentChildLast: Bool =
    gatherIndex === childTotal - 1.U

  private def nextLegalArch(arch: UInt): UInt =
    Mux(arch === BlockControlTemplateSequencer.LastLegalRegister.U, 2.U, arch + 1.U)

  private def childOffset(index: UInt): UInt = {
    val wide = Wire(UInt(p.immWidth.W))
    wide := (index + 1.U) << 3
    wide
  }

  val gatherAddress = Mux(
    isFentry,
    newSp + extendedFrameAmount - childOffset(gatherIndex),
    newSp - childOffset(gatherIndex)
  )
  val gatherTag = active.sidecar.map.smap(currentArch(log2Ceil(archRegs) - 1, 0))

  val recoverySelf =
    io.recovery.valid && activeValid &&
      TemplateParentIdentity.sameKey(io.recovery.bits, active.sidecar.identity)
  val recoveryCancel =
    io.recovery.valid && activeValid && !recoverySelf && io.recoveryKillsActive && !completed
  val dominantClearOrCancel = io.globalClear || recoveryCancel

  io.parentRequest.ready :=
    state === TemplateSequencerState.Idle && !dominantClearOrCancel

  io.rfReadRequest.valid :=
    state === TemplateSequencerState.GatherChildren && isFentry && !requestOutstanding &&
      !dominantClearOrCancel
  io.rfReadRequest.bits.parent := active.sidecar.identity
  io.rfReadRequest.bits.childIndex := gatherIndex
  io.rfReadRequest.bits.archReg := currentArch
  io.rfReadRequest.bits.physTag := gatherTag

  io.loadRequest.valid :=
    state === TemplateSequencerState.GatherChildren && isRestore && !requestOutstanding &&
      !dominantClearOrCancel
  io.loadRequest.bits.parent := active.sidecar.identity
  io.loadRequest.bits.childIndex := gatherIndex
  io.loadRequest.bits.archReg := currentArch
  io.loadRequest.bits.addr := gatherAddress
  io.loadRequest.bits.size := 3.U

  // Stale responses are consumed outside a dominant clear/cancel edge so a
  // generation-reused producer cannot wedge the shared response path.
  io.rfReadResponse.ready := !dominantClearOrCancel
  io.loadResponse.ready := !dominantClearOrCancel

  val rfReadResponseExact =
    io.rfReadResponse.fire &&
      state === TemplateSequencerState.GatherChildren &&
      isFentry &&
      requestOutstanding &&
      TemplateParentIdentity.sameKey(io.rfReadResponse.bits.parent, active.sidecar.identity) &&
      io.rfReadResponse.bits.childIndex === gatherIndex &&
      io.rfReadResponse.bits.physTag === gatherTag
  val loadResponseExact =
    io.loadResponse.fire &&
      state === TemplateSequencerState.GatherChildren &&
      isRestore &&
      requestOutstanding &&
      TemplateParentIdentity.sameKey(io.loadResponse.bits.parent, active.sidecar.identity) &&
      io.loadResponse.bits.childIndex === gatherIndex &&
      io.loadResponse.bits.addr === gatherAddress

  val retainedChildren = childTotal - drainIndex
  val retainedStores = Mux(activeValid && isFentry, retainedChildren, 0.U)
  val retainedRfWrites = Mux(activeValid && isRestore, retainedChildren, 0.U)
  val completionNextPc = Mux(isFretRa || isFretStk, targetPc, active.sidecar.identity.pc + 4.U)

  io.completion.valid :=
    state === TemplateSequencerState.WaitCompletionSelect && !dominantClearOrCancel
  io.completion.bits.parent := active.sidecar.identity
  io.completion.bits.parentSlot := active.sidecar.identity.robSlot
  io.completion.bits.result := newSp
  io.completion.bits.newSp := newSp
  io.completion.bits.nextPc := completionNextPc
  io.completion.bits.redirectValid := isFretRa || isFretStk
  io.completion.bits.retainedStoreCount := retainedStores
  io.completion.bits.retainedRfCount := retainedRfWrites

  val completionSelected = io.completion.valid && io.selectedTemplate
  val commitMatch =
    io.parentCommit.valid &&
      state === TemplateSequencerState.WaitParentCommit &&
      TemplateParentIdentity.sameKey(io.parentCommit.bits, active.sidecar.identity) &&
      !dominantClearOrCancel
  val illegalDiscardAttempt =
    io.recovery.valid && activeValid && !recoverySelf && io.recoveryKillsActive && completed

  io.storeRequest.valid :=
    state === TemplateSequencerState.StoreBurst && !dominantClearOrCancel
  io.storeRequest.bits.ownsStqRow := false.B
  io.storeRequest.bits.parent := active.sidecar.identity
  io.storeRequest.bits.childIndex := drainIndex
  io.storeRequest.bits.childLast := drainIndex === childTotal - 1.U
  io.storeRequest.bits.addr := childAddr(drainIndex)
  io.storeRequest.bits.data := childData(drainIndex)
  io.storeRequest.bits.size := 3.U
  val templateLsId = Wire(UInt(lsidWidth.W))
  templateLsId := Cat(active.sidecar.identity.commitIdentityRid, drainIndex)
  io.storeRequest.bits.lsId := templateLsId
  io.storeRequest.bits.stqIndex := 0.U

  io.rfWriteRequest.valid :=
    state === TemplateSequencerState.RfBurst && !dominantClearOrCancel
  io.rfWriteRequest.bits.parent := active.sidecar.identity
  io.rfWriteRequest.bits.childIndex := drainIndex
  io.rfWriteRequest.bits.childLast := drainIndex === childTotal - 1.U
  io.rfWriteRequest.bits.archReg := childArch(drainIndex)
  io.rfWriteRequest.bits.physTag := childTag(drainIndex)
  io.rfWriteRequest.bits.data := childData(drainIndex)

  io.storeObservation.valid := io.storeRequest.fire
  io.storeObservation.bits.parent := io.storeRequest.bits.parent
  io.storeObservation.bits.childIndex := io.storeRequest.bits.childIndex
  io.storeObservation.bits.addr := io.storeRequest.bits.addr
  io.storeObservation.bits.data := io.storeRequest.bits.data
  io.storeObservation.bits.size := io.storeRequest.bits.size

  io.lookupObservation.valid := loadResponseExact && (isFretRa || isFretStk)
  io.lookupObservation.bits.parent := active.sidecar.identity
  io.lookupObservation.bits.childIndex := gatherIndex
  io.lookupObservation.bits.addr := gatherAddress
  io.lookupObservation.bits.data := io.loadResponse.bits.data

  when(io.rfReadRequest.fire || io.loadRequest.fire) {
    requestOutstanding := true.B
  }

  when(io.globalClear) {
    state := TemplateSequencerState.Idle
    activeValid := false.B
    requestOutstanding := false.B
    completed := false.B
    committed := false.B
    decodeFence := false.B
    issueFence := false.B
    memoryFence := false.B
    childTotal := 0.U
    gatherIndex := 0.U
    drainIndex := 0.U
  }.elsewhen(recoveryCancel) {
    state := TemplateSequencerState.Cancel
    activeValid := false.B
    requestOutstanding := false.B
    completed := false.B
    committed := false.B
    decodeFence := false.B
    issueFence := false.B
    memoryFence := false.B
    childTotal := 0.U
    gatherIndex := 0.U
    drainIndex := 0.U
  }.otherwise {
    switch(state) {
      is(TemplateSequencerState.Idle) {
        when(io.parentRequest.fire) {
          active := io.parentRequest.bits
          activeValid := true.B
          completed := false.B
          committed := false.B
          decodeFence := true.B
          issueFence := true.B
          memoryFence := true.B
          gatherIndex := 0.U
          drainIndex := 0.U
          requestOutstanding := false.B
          state := TemplateSequencerState.CaptureParent
        }
      }
      is(TemplateSequencerState.CaptureParent) {
        newSp := computedNewSp
        currentArch := rangeM
        childTotal := computedChildTotal
        when(isFretRa) {
          targetPc := active.srcData0
        }.otherwise {
          targetPc := 0.U
        }
        state := TemplateSequencerState.GatherChildren
      }
      is(TemplateSequencerState.GatherChildren) {
        when(rfReadResponseExact) {
          childArch(gatherIndex) := currentArch
          childTag(gatherIndex) := gatherTag
          childAddr(gatherIndex) := gatherAddress
          childData(gatherIndex) := io.rfReadResponse.bits.data
          requestOutstanding := false.B
          when(currentChildLast) {
            state := TemplateSequencerState.WaitCompletionSelect
          }.otherwise {
            gatherIndex := gatherIndex + 1.U
            currentArch := nextLegalArch(currentArch)
          }
        }.elsewhen(loadResponseExact) {
          childArch(gatherIndex) := currentArch
          childTag(gatherIndex) := gatherTag
          childAddr(gatherIndex) := gatherAddress
          childData(gatherIndex) := io.loadResponse.bits.data
          when(isFretStk && gatherIndex === 0.U) {
            targetPc := io.loadResponse.bits.data
          }
          requestOutstanding := false.B
          when(currentChildLast) {
            state := TemplateSequencerState.WaitCompletionSelect
          }.otherwise {
            gatherIndex := gatherIndex + 1.U
            currentArch := nextLegalArch(currentArch)
          }
        }
      }
      is(TemplateSequencerState.WaitCompletionSelect) {
        when(completionSelected) {
          completed := true.B
          state := TemplateSequencerState.WaitParentCommit
        }
      }
      is(TemplateSequencerState.WaitParentCommit) {
        when(commitMatch) {
          committed := true.B
          drainIndex := 0.U
          state := Mux(isFentry, TemplateSequencerState.WaitOlderStores, TemplateSequencerState.RfBurst)
        }
      }
      is(TemplateSequencerState.WaitOlderStores) {
        // Integration owns the exact global STQ/queue/issue/request/drain
        // emptiness predicate and converts it into this one-cycle grant.
        when(io.storeGrant) {
          state := TemplateSequencerState.StoreBurst
        }
      }
      is(TemplateSequencerState.StoreBurst) {
        when(io.storeRequest.fire) {
          acceptedStoreCount := acceptedStoreCount + 1.U
          when(io.storeRequest.bits.childLast) {
            drainIndex := childTotal
            decodeFence := false.B
            issueFence := false.B
            memoryFence := false.B
            state := TemplateSequencerState.DrainDone
          }.otherwise {
            drainIndex := drainIndex + 1.U
          }
        }
      }
      is(TemplateSequencerState.RfBurst) {
        when(io.rfWriteRequest.fire) {
          acceptedRfWriteCount := acceptedRfWriteCount + 1.U
          when(io.rfWriteRequest.bits.childLast) {
            drainIndex := childTotal
            decodeFence := false.B
            issueFence := false.B
            memoryFence := false.B
            state := TemplateSequencerState.DrainDone
          }.otherwise {
            drainIndex := drainIndex + 1.U
          }
        }
      }
      is(TemplateSequencerState.DrainDone) {
        state := TemplateSequencerState.Idle
        activeValid := false.B
        requestOutstanding := false.B
        completed := false.B
        committed := false.B
        childTotal := 0.U
        gatherIndex := 0.U
        drainIndex := 0.U
      }
      is(TemplateSequencerState.Cancel) {
        state := TemplateSequencerState.Idle
      }
    }
  }

  when(io.rfReadResponse.fire && !rfReadResponseExact) {
    staleResponseDropCount := staleResponseDropCount + 1.U
  }
  when(io.loadResponse.fire && !loadResponseExact) {
    staleResponseDropCount := staleResponseDropCount + 1.U
  }

  val supportedParent =
    io.parentRequest.bits.sidecar.identity.opcode === FrontendOpcodeDecodeTable.OP_FENTRY.U ||
      io.parentRequest.bits.sidecar.identity.opcode === FrontendOpcodeDecodeTable.OP_FEXIT.U ||
      io.parentRequest.bits.sidecar.identity.opcode === FrontendOpcodeDecodeTable.OP_FRET_RA.U ||
      io.parentRequest.bits.sidecar.identity.opcode === FrontendOpcodeDecodeTable.OP_FRET_STK.U
  when(io.parentRequest.fire) {
    assert(supportedParent)
    assert(io.parentRequest.bits.sidecar.rangeM >= 2.U && io.parentRequest.bits.sidecar.rangeM <= 23.U)
    assert(io.parentRequest.bits.sidecar.rangeN >= 2.U && io.parentRequest.bits.sidecar.rangeN <= 23.U)
  }
  assert(!io.selectedTemplate || io.completion.valid || dominantClearOrCancel)
  assert(!io.storeRequest.valid || io.storeRequest.bits.ownsStqRow === false.B)
  assert(!(io.storeRequest.fire && io.rfWriteRequest.fire))
  assert(!(state === TemplateSequencerState.DrainDone &&
    (retainedStores =/= 0.U || retainedRfWrites =/= 0.U)))
  when(dominantClearOrCancel) {
    assert(!io.parentRequest.fire)
    assert(!io.rfReadRequest.valid)
    assert(!io.loadRequest.valid)
    assert(!io.rfReadResponse.ready)
    assert(!io.loadResponse.ready)
    assert(!io.completion.valid)
    assert(!io.storeRequest.valid)
    assert(!io.rfWriteRequest.valid)
    assert(!io.storeObservation.valid)
    assert(!io.lookupObservation.valid)
    assert(!completionSelected)
    assert(!commitMatch)
    assert(!io.parentIssueRelease)
    assert(!io.terminal)
    assert(!io.spPublishValid)
  }

  io.activeValid := activeValid
  io.activeIdentity := active.sidecar.identity
  io.activeMap := active.sidecar.map
  io.activeOldSp := active.oldSp
  io.activeSrcData(0) := active.srcData0
  io.activeSrcData(1) := active.srcData1
  io.activeSrcData(2) := active.srcData2
  io.state := state
  io.decodeFence := decodeFence
  io.issueFence := issueFence
  io.memoryFence := memoryFence
  io.parentIssueRelease := completionSelected
  io.terminal := completionSelected
  io.committed := committed
  io.spPublishValid := commitMatch
  io.spPublishValue := newSp
  io.nextPc := completionNextPc
  io.selfRestartObserved := recoverySelf
  io.illegalDiscardAttempt := illegalDiscardAttempt
  io.cancelObserved := recoveryCancel
  io.retainedStoreCount := retainedStores
  io.retainedRfCount := retainedRfWrites
  io.tailZero := state === TemplateSequencerState.DrainDone
  io.staleResponseDropCount := staleResponseDropCount
  io.acceptedStoreCount := acceptedStoreCount
  io.acceptedRfWriteCount := acceptedRfWriteCount
}
