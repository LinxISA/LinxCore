package linxcore.iex

import chisel3._
import chisel3.util.{DecoupledIO, Fill, Mux1H, MuxLookup, PriorityEncoderOH,
  Queue, RRArbiter, UIntToOH, is, switch}
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.lsu.{LoadAttemptIdentity, LoadCanonicalRowIdentity}
import linxcore.ooo.{OooIexExecuteTransaction, OooIexExecutionPipeline,
  OooIexIssuePolicy, OooIexWakeupKind, OooMemoryAddressMode,
  OooMemoryIndexMode}
import linxcore.params.CoreParams
import linxcore.top.interface.{IEXIO, LoadReissueTxn, OperandKind,
  RecoveryEvent, TraceKind, TraceSource}

/** Public state-free composition boundary for the canonical issue/execute graph.
  *
  * Architectural state remains in the retained private IQ, operand-file,
  * execution, terminal, and load-metadata owners instantiated below.
  */
private final class OooIexBoundaryOwner(val p: CoreParams) extends Module {
  val io = IO(new IEXIO(p))
  private val implementation = Module(new OooIexExecutionPipeline(
    p, requireStoreReservation = true))

  private val committedPTagCount = p.ooo.stidCount * p.ooo.gprArchRegs
  private val bootstrapSeen = RegInit(0.U(committedPTagCount.W))
  private val bootstrapEpochValid = RegInit(VecInit(Seq.fill(
    p.ooo.stidCount)(false.B)))
  private val bootstrapEpoch = Reg(Vec(p.ooo.stidCount,
    UInt(p.epochWidth.W)))
  private val bootstrapDone = RegInit(false.B)
  private val initStidInRange = io.pInit.bits.stid < p.ooo.stidCount.U
  private val initAtagInRange = io.pInit.bits.atag < p.ooo.gprArchRegs.U
  private val expectedPTag = io.pInit.bits.stid * p.ooo.gprArchRegs.U +
    io.pInit.bits.atag
  private val initPTagExact = initStidInRange && initAtagInRange &&
    io.pInit.bits.ptag === expectedPTag && io.pInit.bits.generation === 0.U
  private val safeInitPTag = Mux(initPTagExact, expectedPTag, 0.U)
  private val initPTagOH = UIntToOH(safeInitPTag, committedPTagCount)
  private val initUnseen = !(bootstrapSeen & initPTagOH).orR
  private val initEpochExact = if (p.ooo.stidCount == 1) {
    !bootstrapEpochValid.head || bootstrapEpoch.head === io.pInit.bits.epoch
  } else {
    !bootstrapEpochValid(io.pInit.bits.stid) ||
      bootstrapEpoch(io.pInit.bits.stid) === io.pInit.bits.epoch
  }
  io.pInit.ready := !bootstrapDone && initPTagExact && initUnseen &&
    initEpochExact
  implementation.io.pInit.valid := io.pInit.fire
  implementation.io.pInit.bits := 0.U.asTypeOf(implementation.io.pInit.bits)
  implementation.io.pInit.bits.key.stid := io.pInit.bits.stid
  implementation.io.pInit.bits.key.epoch := io.pInit.bits.epoch
  implementation.io.pInit.bits.key.ptag := io.pInit.bits.ptag
  implementation.io.pInit.bits.key.generation := io.pInit.bits.generation
  implementation.io.pInit.bits.data := io.pInit.bits.value
  when(io.pInit.fire) {
    bootstrapSeen := bootstrapSeen | initPTagOH
    if (p.ooo.stidCount == 1) {
      bootstrapEpochValid.head := true.B
      bootstrapEpoch.head := io.pInit.bits.epoch
    } else {
      bootstrapEpochValid(io.pInit.bits.stid) := true.B
      bootstrapEpoch(io.pInit.bits.stid) := io.pInit.bits.epoch
    }
  }
  val completeMapSeen = bootstrapSeen.andR
  when(io.bootstrapComplete && completeMapSeen) { bootstrapDone := true.B }
  io.bootstrapReady := bootstrapDone
  assert(!(io.bootstrapComplete && !completeMapSeen),
    "IEX bootstrap cannot complete before every committed P map row is initialized")
  assert(!(io.pInit.valid && !io.pInit.ready),
    "IEX P initialization must be exact, unique, epoch-consistent, and boot-only")

  private def bootGated[T <: Data](source: DecoupledIO[T],
      sink: DecoupledIO[T]): Unit = {
    sink.valid := source.valid && bootstrapDone
    sink.bits := source.bits
    source.ready := sink.ready && bootstrapDone
  }
  for (lane <- io.ooo.aluDispatch.indices) {
    bootGated(io.ooo.aluDispatch(lane),
      implementation.io.dispatch.aluDispatch(lane))
  }
  for (lane <- io.ooo.bruDispatch.indices) {
    bootGated(io.ooo.bruDispatch(lane),
      implementation.io.dispatch.bruDispatch(lane))
  }
  for (lane <- io.ooo.aguDispatch.indices) {
    bootGated(io.ooo.aguDispatch(lane),
      implementation.io.dispatch.aguDispatch(lane))
  }
  for (lane <- io.ooo.storeDispatch.indices) {
    bootGated(io.ooo.storeDispatch(lane),
      implementation.io.dispatch.storeDispatch(lane))
  }
  for (lane <- io.ooo.systemDispatch.indices) {
    bootGated(io.ooo.systemDispatch(lane),
      implementation.io.dispatch.systemDispatch(lane))
  }
  for (lane <- io.ooo.cmdDispatch.indices) {
    bootGated(io.ooo.cmdDispatch(lane),
      implementation.io.dispatch.cmdDispatch(lane))
  }
  implementation.io.recovery <> io.ooo.recovery
  implementation.io.issuePolicy := 0.U.asTypeOf(new OooIexIssuePolicy(
    implementation.p))

  require(io.ooo.allocationClear.length == implementation.io.pClear.length)
  for (port <- io.ooo.allocationClear.indices) {
    val clear = io.ooo.allocationClear(port)
    val destination = clear.bits.destination

    implementation.io.pClear(port).valid := clear.valid &&
      destination.kind === OperandKind.Gpr && destination.ptagValid
    implementation.io.pClear(port).bits.stid := clear.bits.rob.stid
    implementation.io.pClear(port).bits.epoch := clear.bits.epoch
    implementation.io.pClear(port).bits.ptag := destination.ptag
    implementation.io.pClear(port).bits.generation := destination.pGeneration

    implementation.io.tClear(port).valid := clear.valid &&
      destination.kind === OperandKind.T && destination.ttagValid
    implementation.io.tClear(port).bits.stid := clear.bits.rob.stid
    implementation.io.tClear(port).bits.epoch := clear.bits.epoch
    implementation.io.tClear(port).bits.tag := destination.ttag
    implementation.io.tClear(port).bits.sequence.valid := true.B
    implementation.io.tClear(port).bits.sequence.index :=
      destination.tSeqIndex
    implementation.io.tClear(port).bits.sequence.generation :=
      destination.tSeqGeneration

    implementation.io.uClear(port).valid := clear.valid &&
      destination.kind === OperandKind.U && destination.utagValid
    implementation.io.uClear(port).bits.stid := clear.bits.rob.stid
    implementation.io.uClear(port).bits.epoch := clear.bits.epoch
    implementation.io.uClear(port).bits.tag := destination.utag
    implementation.io.uClear(port).bits.sequence.valid := true.B
    implementation.io.uClear(port).bits.sequence.index :=
      destination.uSeqIndex
    implementation.io.uClear(port).bits.sequence.generation :=
      destination.uSeqGeneration
  }
  assert(!io.ooo.allocationClear.map(_.valid).reduce(_ || _) || bootstrapDone,
    "rename allocation clears are the only post-bootstrap P ownership changes")
  private def projectMember(
      target: linxcore.ooo.RobMemberKey,
      source: linxcore.top.interface.RobIdentity): Unit = {
    target.group.valid := true.B
    target.group.peId := source.peId
    target.group.stid := source.stid
    target.group.ridSlot := source.ridSlot
    target.group.ridGeneration := source.ridGeneration
    target.bid.valid := true.B
    target.bid.value := source.bid
    target.brobGeneration := source.brobGeneration
    target.memberIndex := source.memberIndex
    target.residentGeneration := source.residentGeneration
  }
  implementation.io.fastWriteback.valid := io.ooo.fastWriteback.valid
  io.ooo.fastWriteback.ready := implementation.io.fastWriteback.ready
  implementation.io.fastWriteback.bits :=
    0.U.asTypeOf(implementation.io.fastWriteback.bits)
  projectMember(implementation.io.fastWriteback.bits.member,
    io.ooo.fastWriteback.bits.rob)
  implementation.io.fastWriteback.bits.stid :=
    io.ooo.fastWriteback.bits.rob.stid
  implementation.io.fastWriteback.bits.epoch :=
    io.ooo.fastWriteback.bits.epoch
  implementation.io.fastWriteback.bits.ptag :=
    io.ooo.fastWriteback.bits.destination.ptag
  implementation.io.fastWriteback.bits.ptagGeneration :=
    io.ooo.fastWriteback.bits.destination.pGeneration
  implementation.io.fastWriteback.bits.data := io.ooo.fastWriteback.bits.value

  implementation.io.fastWakeup.valid := io.ooo.fastWakeup.valid
  io.ooo.fastWakeup.ready := implementation.io.fastWakeup.ready
  implementation.io.fastWakeup.bits :=
    0.U.asTypeOf(implementation.io.fastWakeup.bits)
  implementation.io.fastWakeup.bits.kind := OooIexWakeupKind.Committed
  implementation.io.fastWakeup.bits.stid := io.ooo.fastWakeup.bits.rob.stid
  implementation.io.fastWakeup.bits.epoch := io.ooo.fastWakeup.bits.epoch
  implementation.io.fastWakeup.bits.operandClass := OperandClass.P
  implementation.io.fastWakeup.bits.ptag :=
    io.ooo.fastWakeup.bits.destination.ptag
  implementation.io.fastWakeup.bits.ptagGeneration :=
    io.ooo.fastWakeup.bits.destination.pGeneration
  implementation.io.stageCancels.foreach(_.foreach { cancel =>
    cancel.valid := false.B
    cancel.bits := 0.U.asTypeOf(cancel.bits)
  })

  for (port <- io.ooo.pcBufferReadAddress.indices) {
    io.ooo.pcBufferReadAddress(port) :=
      implementation.io.pcReadRequests(port).bits
    io.ooo.pcBufferReadAddress(port).valid :=
      implementation.io.pcReadRequests(port).valid
    implementation.io.pcReadResponses(port) :=
      io.ooo.pcBufferReadPcBase(port)
  }

  io.ooo.robNoflushReady <> implementation.io.robNoflushReady
  implementation.io.robNoflush <> io.ooo.robNoflush
  for (port <- io.ooo.systemIssue.indices) {
    io.ooo.systemIssue(port) <> implementation.io.systemIssue(port)
  }

  for (port <- io.ooo.robResolve.indices) {
    io.ooo.robResolve(port).valid := false.B
    io.ooo.robResolve(port).bits := 0.U.asTypeOf(io.ooo.robResolve(port).bits)
  }
  private val resolveTransport = Seq.fill(implementation.io.robResolve.length)(
    Module(new Queue(chiselTypeOf(implementation.io.robResolve.head.bits), 1,
      pipe = false, flow = false)))
  for (port <- implementation.io.robResolve.indices) {
    resolveTransport(port).io.enq <> implementation.io.robResolve(port)
    io.ooo.robResolve(port) <> resolveTransport(port).io.deq
  }
  val systemResolvePort = implementation.io.robResolve.length
  if (systemResolvePort < io.ooo.robResolve.length) {
    io.ooo.robResolve(systemResolvePort) <>
      implementation.io.systemCmdResolve
  } else {
    implementation.io.systemCmdResolve.ready := false.B
  }

  private val recoveryArbiter = Module(new RRArbiter(
    new RecoveryEvent(p), implementation.io.recoveryEvent.length))
  for (lane <- implementation.io.recoveryEvent.indices) {
    recoveryArbiter.io.in(lane) <> implementation.io.recoveryEvent(lane)
  }
  io.ooo.recoveryEvent <> recoveryArbiter.io.out
  implementation.io.bctrl.foreach(_.ready := false.B)

  io.cmdIssue <> implementation.io.cmdIssue
  private val terminalTraceSources =
    implementation.io.trace.toSeq :+ implementation.io.systemCmdTrace
  private val traceArbiter = Module(new RRArbiter(
    chiselTypeOf(implementation.io.trace.head.bits),
    terminalTraceSources.length))
  private val traceTransport = Seq.fill(terminalTraceSources.length)(
    Module(new Queue(chiselTypeOf(implementation.io.trace.head.bits), 1,
      pipe = false, flow = false)))
  for (lane <- terminalTraceSources.indices) {
    traceTransport(lane).io.enq <> terminalTraceSources(lane)
    traceArbiter.io.in(lane) <> traceTransport(lane).io.deq
  }
  io.trace.valid := traceArbiter.io.out.valid
  traceArbiter.io.out.ready := io.trace.ready
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
  io.trace.bits.count := 1.U
  private val terminalTrace = traceArbiter.io.out.bits
  private val terminalTraceEntry = io.trace.bits.entries.head
  private val gprWriteMask = VecInit(terminalTrace.writebacks.map(writeback =>
    writeback.valid && writeback.destination.kind === DestinationKind.Gpr))
  terminalTraceEntry.source := TraceSource.Iex
  terminalTraceEntry.kind := Mux(terminalTrace.trapValid, TraceKind.Trap,
    Mux(gprWriteMask.asUInt.orR, TraceKind.Commit, TraceKind.Pipeline))
  terminalTraceEntry.instructionValid :=
    terminalTrace.uopKey.primaryParent.valid
  terminalTraceEntry.instruction.peId :=
    terminalTrace.uopKey.primaryParent.peId
  terminalTraceEntry.instruction.stid :=
    terminalTrace.uopKey.primaryParent.stid
  terminalTraceEntry.instruction.instructionId :=
    terminalTrace.uopKey.primaryParent.instructionId
  terminalTraceEntry.instruction.epoch :=
    terminalTrace.uopKey.primaryParent.epoch
  terminalTraceEntry.robValid := terminalTrace.member.group.valid &&
    terminalTrace.member.bid.valid
  terminalTraceEntry.rob.peId := terminalTrace.member.group.peId
  terminalTraceEntry.rob.stid := terminalTrace.member.group.stid
  terminalTraceEntry.rob.ridSlot := terminalTrace.member.group.ridSlot
  terminalTraceEntry.rob.ridGeneration :=
    terminalTrace.member.group.ridGeneration
  terminalTraceEntry.rob.memberIndex := terminalTrace.member.memberIndex
  terminalTraceEntry.rob.residentGeneration :=
    terminalTrace.member.residentGeneration
  terminalTraceEntry.rob.bid := terminalTrace.member.bid.value
  terminalTraceEntry.rob.brobGeneration := terminalTrace.member.brobGeneration
  terminalTraceEntry.opcode := terminalTrace.opcode
  terminalTraceEntry.payload := Mux(gprWriteMask.asUInt.orR,
    Mux1H(gprWriteMask, terminalTrace.writebacks.map(_.data)), 0.U)
  io.terminalPWrite.valid := traceArbiter.io.out.fire &&
    gprWriteMask.asUInt.orR
  io.terminalPWrite.bits := 0.U.asTypeOf(io.terminalPWrite.bits)
  io.terminalPWrite.bits.rob := terminalTraceEntry.rob
  io.terminalPWrite.bits.ptag := Mux1H(gprWriteMask,
    terminalTrace.writebacks.map(_.destination.ptag))
  io.terminalPWrite.bits.generation := Mux1H(gprWriteMask,
    terminalTrace.writebacks.map(_.destination.ptagGeneration))
  io.terminalPWrite.bits.value := Mux1H(gprWriteMask,
    terminalTrace.writebacks.map(_.data))

  // Reservation is the same acceptance dependency used by the retained IQ
  // owner; it cannot fire separately from the logical STA/STD pair.
  io.lsu.storeReservation.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  private val reservation = implementation.io.storeReserve.get
  private val publicReservation = io.lsu.storeReservation.head
  publicReservation.valid := reservation.valid
  reservation.ready := publicReservation.ready
  publicReservation.bits.transactionId := reservation.bits.transactionId
  publicReservation.bits.rob.peId := reservation.bits.member.group.peId
  publicReservation.bits.rob.stid := reservation.bits.member.group.stid
  publicReservation.bits.rob.ridSlot := reservation.bits.member.group.ridSlot
  publicReservation.bits.rob.ridGeneration :=
    reservation.bits.member.group.ridGeneration
  publicReservation.bits.rob.memberIndex := reservation.bits.member.memberIndex
  publicReservation.bits.rob.residentGeneration :=
    reservation.bits.member.residentGeneration
  publicReservation.bits.rob.bid := reservation.bits.member.bid.value
  publicReservation.bits.rob.brobGeneration :=
    reservation.bits.member.brobGeneration
  publicReservation.bits.memoryOrder := 0.U.asTypeOf(
    publicReservation.bits.memoryOrder)
  publicReservation.bits.memoryOrder.requestCount :=
    reservation.bits.memoryOrder.requestCount
  publicReservation.bits.memoryOrder.firstLsid :=
    reservation.bits.memoryOrder.firstLsid
  publicReservation.bits.memoryOrder.firstSid :=
    reservation.bits.memoryOrder.firstTypeId
  publicReservation.bits.memoryOrder.yostValid :=
    reservation.bits.memoryOrder.before.youngestStoreLsidValid
  publicReservation.bits.memoryOrder.yostLsid :=
    reservation.bits.memoryOrder.before.youngestStoreLsid
  publicReservation.bits.memoryOrder.yostSid :=
    reservation.bits.memoryOrder.before.storeId - 1.U
  publicReservation.bits.requestCount :=
    reservation.bits.memoryOrder.requestCount
  publicReservation.bits.pair :=
    reservation.bits.memoryOrder.requestCount === 2.U
  publicReservation.bits.sizeBytes := reservation.bits.memory.accessBytes
  publicReservation.bits.aguPipe := 0.U
  publicReservation.bits.stdPipe := 0.U

  private def projectRob(target: linxcore.top.interface.RobIdentity,
      execute: OooIexExecuteTransaction): Unit = {
    val member = execute.i2.row.member
    target.peId := member.group.peId
    target.stid := member.group.stid
    target.ridSlot := member.group.ridSlot
    target.ridGeneration := member.group.ridGeneration
    target.memberIndex := member.memberIndex
    target.residentGeneration := member.residentGeneration
    target.bid := member.bid.value
    target.brobGeneration := member.brobGeneration
  }

  private def projectMemoryIdentity(
      target: linxcore.top.interface.MemoryIdentity,
      execute: OooIexExecuteTransaction, pipe: Int): Unit = {
    val row = execute.i2.row
    target := 0.U.asTypeOf(target)
    projectRob(target.rob, execute)
    target.transaction.value := row.memoryTransaction.value
    target.transaction.generation := row.memoryTransaction.generation
    target.lsid := row.memoryOrder.firstLsid
    target.attemptGeneration := row.initialLoadAttemptGeneration
    target.pipeId := pipe.U
  }

  private def projectMemoryOrder(
      target: linxcore.top.interface.MemoryOrderMeta,
      execute: OooIexExecuteTransaction): Unit = {
    val order = execute.i2.row.memoryOrder
    target := 0.U.asTypeOf(target)
    target.requestCount := order.requestCount
    target.firstLsid := order.firstLsid
    target.firstSid := order.firstTypeId
    target.yostValid := order.before.youngestStoreLsidValid
    target.yostLsid := order.before.youngestStoreLsid
    target.yostSid := order.before.storeId - 1.U
  }

  private def maskedValue(mask: UInt, values: Vec[UInt], second: Bool): UInt = {
    val first = PriorityEncoderOH(mask)
    Mux1H(Mux(second, PriorityEncoderOH(mask & ~first), first), values)
  }

  private def transformedIndex(execute: OooIexExecuteTransaction,
      raw: UInt): UInt = {
    val memory = execute.i2.row.memory
    val transformed = WireDefault(raw)
    switch(memory.indexMode) {
      is(OooMemoryIndexMode.SignExtend32) {
        transformed := Fill(p.pcWidth - 32, raw(31)) ## raw(31, 0)
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

  private def storeBaseAddress(execute: OooIexExecuteTransaction): UInt = {
    val memory = execute.i2.row.memory
    val base = maskedValue(memory.addressSourceMask,
      execute.i2.sourceData, false.B)
    val index = maskedValue(memory.addressSourceMask,
      execute.i2.sourceData, true.B)
    MuxLookup(memory.addressMode.asUInt, 0.U)(Seq(
      OooMemoryAddressMode.BaseIndex.asUInt ->
        (base + transformedIndex(execute, index)),
      OooMemoryAddressMode.BaseOffset.asUInt -> (base + memory.offset),
      OooMemoryAddressMode.PcOffset.asUInt ->
        (execute.i2.pc + memory.offset)))
  }

  // Publish semantic store halves. Pair expansion and physical lease lookup
  // remain exclusively inside LSU.
  io.lsu.loadAddress.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  val privateLoad = implementation.io.load.liqAlloc
  val publicLoadBits = Wire(chiselTypeOf(io.lsu.loadAddress.head.bits))
  publicLoadBits := 0.U.asTypeOf(publicLoadBits)
  publicLoadBits.identity.rob.peId := privateLoad.bits.attempt.producer.peId
  publicLoadBits.identity.rob.stid := privateLoad.bits.attempt.producer.stid
  publicLoadBits.identity.rob.ridSlot :=
    privateLoad.bits.attempt.producer.ridSlot
  publicLoadBits.identity.rob.ridGeneration :=
    privateLoad.bits.attempt.producer.ridGeneration
  publicLoadBits.identity.rob.memberIndex :=
    privateLoad.bits.attempt.producer.memberIndex
  publicLoadBits.identity.rob.residentGeneration :=
    privateLoad.bits.attempt.producer.residentGeneration
  publicLoadBits.identity.rob.bid := privateLoad.bits.attempt.producer.nativeBid
  publicLoadBits.identity.rob.brobGeneration :=
    privateLoad.bits.attempt.producer.brobGeneration
  publicLoadBits.identity.lsid := privateLoad.bits.loadLsIdFull
  publicLoadBits.identity.transaction.value := privateLoad.bits.attempt.transactionValue
  publicLoadBits.identity.transaction.generation :=
    privateLoad.bits.attempt.transactionGeneration
  publicLoadBits.identity.attemptGeneration := privateLoad.bits.attempt.generation
  publicLoadBits.identity.pipeId := privateLoad.bits.returnPipeIndex
  publicLoadBits.pc := privateLoad.bits.pc
  publicLoadBits.address := privateLoad.bits.addr
  publicLoadBits.sizeBytes := privateLoad.bits.size
  publicLoadBits.signed := privateLoad.bits.returnSignExtend
  publicLoadBits.destination.valid := privateLoad.bits.dst.valid
  publicLoadBits.destination.kind := OperandKind.Gpr
  publicLoadBits.destination.atag := privateLoad.bits.dst.archTag
  publicLoadBits.destination.ptag := privateLoad.bits.dst.physTag
  publicLoadBits.destination.previousPtag := privateLoad.bits.dst.oldPhysTag
  publicLoadBits.destination.previousPtagValid := privateLoad.bits.dst.valid
  publicLoadBits.destination.ptagValid := privateLoad.bits.dst.valid
  publicLoadBits.destinationRelativeIndex := privateLoad.bits.dst.relTag
  publicLoadBits.youngestStoreValid :=
    privateLoad.bits.youngestStoreLsIdFullValid
  publicLoadBits.youngestStoreLsid := privateLoad.bits.youngestStoreLsIdFull
  publicLoadBits.youngestStoreId := privateLoad.bits.youngestStoreId.value
  for (lane <- io.lsu.loadAddress.indices) {
    val publicLoad = io.lsu.loadAddress(lane)
    publicLoad.valid := privateLoad.valid &&
      privateLoad.bits.returnPipeIndex === lane.U
    publicLoad.bits := publicLoadBits
  }
  privateLoad.ready := Mux1H(
    UIntToOH(privateLoad.bits.returnPipeIndex, p.lsu.loadPipes),
    io.lsu.loadAddress.map(_.ready))

  // LSU's allocation ID is a combinational preview beside alloc-ready.  It is
  // meaningful before the request is asserted, which lets metadata validate
  // the prospective row without making request-valid depend on the returned
  // Valid marker and forming an IEX/LSU combinational cycle.
  implementation.io.load.liqAllocLoadId.valid := true.B
  implementation.io.load.liqAllocLoadId.value :=
    io.lsu.loadAllocation.head.bits.allocationId.value
  implementation.io.load.liqAllocLoadId.wrap :=
    io.lsu.loadAllocation.head.bits.allocationId.generation(0)

  val loadLaunchValid = VecInit(io.lsu.loadLaunch.map(_.valid))
  val selectedLoadLaunch = Mux1H(loadLaunchValid,
    io.lsu.loadLaunch.map(_.bits))
  implementation.io.load.attemptLaunch.valid := loadLaunchValid.asUInt.orR
  implementation.io.load.attemptLaunch.bits := 0.U.asTypeOf(
    implementation.io.load.attemptLaunch.bits)
  implementation.io.load.attemptLaunch.bits.loadId.valid :=
    loadLaunchValid.asUInt.orR
  implementation.io.load.attemptLaunch.bits.loadId.slot :=
    selectedLoadLaunch.allocationId.value
  implementation.io.load.attemptLaunch.bits.loadId.generation :=
    selectedLoadLaunch.allocationId.generation
  implementation.io.load.attemptLaunch.bits.attempt.valid := true.B
  implementation.io.load.attemptLaunch.bits.attempt.producer.valid := true.B
  implementation.io.load.attemptLaunch.bits.attempt.producer.peId :=
    selectedLoadLaunch.identity.rob.peId
  implementation.io.load.attemptLaunch.bits.attempt.producer.stid :=
    selectedLoadLaunch.identity.rob.stid
  implementation.io.load.attemptLaunch.bits.attempt.producer.nativeBidValid := true.B
  implementation.io.load.attemptLaunch.bits.attempt.producer.nativeBid :=
    selectedLoadLaunch.identity.rob.bid
  implementation.io.load.attemptLaunch.bits.attempt.producer.brobGeneration :=
    selectedLoadLaunch.identity.rob.brobGeneration
  implementation.io.load.attemptLaunch.bits.attempt.producer.ridSlot :=
    selectedLoadLaunch.identity.rob.ridSlot
  implementation.io.load.attemptLaunch.bits.attempt.producer.ridGeneration :=
    selectedLoadLaunch.identity.rob.ridGeneration
  implementation.io.load.attemptLaunch.bits.attempt.producer.memberIndex :=
    selectedLoadLaunch.identity.rob.memberIndex
  implementation.io.load.attemptLaunch.bits.attempt.producer.residentGeneration :=
    selectedLoadLaunch.identity.rob.residentGeneration
  implementation.io.load.attemptLaunch.bits.attempt.transactionValue :=
    selectedLoadLaunch.identity.transaction.value
  implementation.io.load.attemptLaunch.bits.attempt.transactionGeneration :=
    selectedLoadLaunch.identity.transaction.generation
  implementation.io.load.attemptLaunch.bits.attempt.generation :=
    selectedLoadLaunch.identity.attemptGeneration
  for (lane <- io.lsu.storeAddress.indices) {
    val privateAddress = implementation.io.storeAddress(lane)
    val publicAddress = io.lsu.storeAddress(lane)
    publicAddress.valid := privateAddress.valid
    privateAddress.ready := publicAddress.ready
    publicAddress.bits := 0.U.asTypeOf(publicAddress.bits)
    projectMemoryIdentity(publicAddress.bits.identity,
      privateAddress.bits, lane)
    projectMemoryOrder(publicAddress.bits.memoryOrder, privateAddress.bits)
    publicAddress.bits.requestCount :=
      privateAddress.bits.i2.row.memoryOrder.requestCount
    publicAddress.bits.pair := publicAddress.bits.requestCount === 2.U
    publicAddress.bits.address := storeBaseAddress(privateAddress.bits)
    publicAddress.bits.sizeBytes :=
      privateAddress.bits.i2.row.memory.accessBytes
  }
  for (lane <- io.lsu.storeData.indices) {
    val privateData = implementation.io.storeData(lane)
    val publicData = io.lsu.storeData(lane)
    publicData.valid := privateData.valid
    privateData.ready := publicData.ready
    publicData.bits := 0.U.asTypeOf(publicData.bits)
    projectMemoryIdentity(publicData.bits.identity, privateData.bits, lane)
    projectMemoryOrder(publicData.bits.memoryOrder, privateData.bits)
    publicData.bits.requestCount := privateData.bits.i2.row.memoryOrder.requestCount
    publicData.bits.pair := publicData.bits.requestCount === 2.U
    publicData.bits.sizeBytes := privateData.bits.i2.row.memory.accessBytes
    for (beat <- 0 until p.maxMemoryRequestsPerInstruction) {
      publicData.bits.data(beat) := maskedValue(
        privateData.bits.i2.row.memory.dataSourceMask,
        privateData.bits.i2.sourceData, beat.U === 1.U)
      val bytes = privateData.bits.i2.row.memory.accessBytes
      publicData.bits.byteMask(beat) :=
        ((1.U << bytes) - 1.U)(p.dataWidth / 8 - 1, 0)
    }
  }
  val loadReturnArbiter = Module(new RRArbiter(
    chiselTypeOf(io.lsu.loadResult.head.bits), p.lsu.loadPipes))
  for (lane <- io.lsu.loadResult.indices) {
    loadReturnArbiter.io.in(lane) <> io.lsu.loadResult(lane)
  }
  implementation.io.load.completion.valid := loadReturnArbiter.io.out.valid
  loadReturnArbiter.io.out.ready := implementation.io.load.completion.ready
  implementation.io.load.completion.bits := 0.U.asTypeOf(
    implementation.io.load.completion.bits)
  val returned = loadReturnArbiter.io.out.bits
  implementation.io.load.completion.bits.peId := returned.identity.rob.peId
  implementation.io.load.completion.bits.stid := returned.identity.rob.stid
  implementation.io.load.completion.bits.tid := returned.identity.rob.stid
  implementation.io.load.completion.bits.payload.valid := true.B
  implementation.io.load.completion.bits.payload.loadId.valid := true.B
  implementation.io.load.completion.bits.payload.loadId.slot :=
    returned.allocationId.value
  implementation.io.load.completion.bits.payload.loadId.generation :=
    returned.allocationId.generation
  implementation.io.load.completion.bits.payload.attempt.valid := true.B
  implementation.io.load.completion.bits.payload.attempt.producer.valid := true.B
  implementation.io.load.completion.bits.payload.attempt.producer.peId :=
    returned.identity.rob.peId
  implementation.io.load.completion.bits.payload.attempt.producer.stid :=
    returned.identity.rob.stid
  implementation.io.load.completion.bits.payload.attempt.producer.nativeBidValid := true.B
  implementation.io.load.completion.bits.payload.attempt.producer.nativeBid :=
    returned.identity.rob.bid
  implementation.io.load.completion.bits.payload.attempt.producer.brobGeneration :=
    returned.identity.rob.brobGeneration
  implementation.io.load.completion.bits.payload.attempt.producer.ridSlot :=
    returned.identity.rob.ridSlot
  implementation.io.load.completion.bits.payload.attempt.producer.ridGeneration :=
    returned.identity.rob.ridGeneration
  implementation.io.load.completion.bits.payload.attempt.producer.memberIndex :=
    returned.identity.rob.memberIndex
  implementation.io.load.completion.bits.payload.attempt.producer.residentGeneration :=
    returned.identity.rob.residentGeneration
  implementation.io.load.completion.bits.payload.attempt.transactionValue :=
    returned.identity.transaction.value
  implementation.io.load.completion.bits.payload.attempt.transactionGeneration :=
    returned.identity.transaction.generation
  implementation.io.load.completion.bits.payload.attempt.generation :=
    returned.identity.attemptGeneration
  implementation.io.load.completion.bits.payload.transactionValid := true.B
  implementation.io.load.completion.bits.payload.transactionValue :=
    returned.identity.transaction.value
  implementation.io.load.completion.bits.payload.transactionGeneration :=
    returned.identity.transaction.generation
  implementation.io.load.completion.bits.payload.pipeIndex :=
    returned.identity.pipeId
  implementation.io.load.completion.bits.payload.dst.valid :=
    returned.destination.valid
  implementation.io.load.completion.bits.payload.dst.kind := DestinationKind.Gpr
  implementation.io.load.completion.bits.payload.dst.archTag :=
    returned.destination.atag
  implementation.io.load.completion.bits.payload.dst.relTag :=
    returned.destinationRelativeIndex
  implementation.io.load.completion.bits.payload.dst.physTag :=
    returned.destination.ptag
  implementation.io.load.completion.bits.payload.dst.oldPhysTag :=
    returned.destination.previousPtag
  implementation.io.load.completion.bits.payload.data := returned.data
  implementation.io.load.completion.bits.payload.faultValid := returned.trap.valid
  implementation.io.load.completion.bits.payload.faultCause := returned.trap.cause
  private def projectAttempt(target: LoadAttemptIdentity,
      source: linxcore.top.interface.MemoryIdentity): Unit = {
    target := 0.U.asTypeOf(target)
    target.valid := true.B
    target.producer.valid := true.B
    target.producer.peId := source.rob.peId
    target.producer.stid := source.rob.stid
    target.producer.nativeBidValid := true.B
    target.producer.nativeBid := source.rob.bid
    target.producer.brobGeneration := source.rob.brobGeneration
    target.producer.ridSlot := source.rob.ridSlot
    target.producer.ridGeneration := source.rob.ridGeneration
    target.producer.memberIndex := source.rob.memberIndex
    target.producer.residentGeneration := source.rob.residentGeneration
    target.transactionValue := source.transaction.value
    target.transactionGeneration := source.transaction.generation
    target.generation := source.attemptGeneration
  }
  private def projectLoad(target: linxcore.ooo.OooIexLoadGeneration,
      source: linxcore.top.interface.MemoryIdentity): Unit = {
    target := 0.U.asTypeOf(target)
    target.valid := true.B
    projectMember(target.producer, source.rob)
    target.transaction.value := source.transaction.value
    target.transaction.generation := source.transaction.generation
    target.generation := source.attemptGeneration
  }
  io.lsu.loadRebindApply.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  val reissueArbiter = Module(new RRArbiter(
    new LoadReissueTxn(p), p.lsu.loadPipes * 2))
  for (lane <- 0 until p.lsu.loadPipes) {
    reissueArbiter.io.in(lane) <> io.lsu.loadReissue(lane)
    val repick = io.lsu.loadRepick(lane)
    val repickTransition = reissueArbiter.io.in(p.lsu.loadPipes + lane)
    repickTransition.valid := repick.valid
    repickTransition.bits := 0.U.asTypeOf(repickTransition.bits)
    repickTransition.bits.allocationId := repick.bits.allocationId
    repickTransition.bits.currentIdentity := repick.bits.currentIdentity
    repickTransition.bits.nextIdentity := repick.bits.nextIdentity
    repick.ready := repickTransition.ready
  }
  val publicReissue = reissueArbiter.io.out
  val selectedRebindLane = Mux(
    reissueArbiter.io.chosen < p.lsu.loadPipes.U,
    reissueArbiter.io.chosen,
    reissueArbiter.io.chosen - p.lsu.loadPipes.U)
  val selectedRebindReady = Mux1H(
    UIntToOH(selectedRebindLane, p.lsu.loadPipes),
    io.lsu.loadRebindApply.map(_.ready))
  implementation.io.load.rebind.valid := publicReissue.valid
  publicReissue.ready := implementation.io.load.rebind.ready &&
    selectedRebindReady
  implementation.io.load.rebind.bits := 0.U.asTypeOf(
    implementation.io.load.rebind.bits)
  implementation.io.load.rebind.bits.loadId.valid := true.B
  implementation.io.load.rebind.bits.loadId.slot :=
    publicReissue.bits.allocationId.value
  implementation.io.load.rebind.bits.loadId.generation :=
    publicReissue.bits.allocationId.generation
  projectAttempt(implementation.io.load.rebind.bits.currentAttempt,
    publicReissue.bits.currentIdentity)
  projectAttempt(implementation.io.load.rebind.bits.nextAttempt,
    publicReissue.bits.nextIdentity)
  projectLoad(implementation.io.load.rebind.bits.currentLoad,
    publicReissue.bits.currentIdentity)
  projectLoad(implementation.io.load.rebind.bits.nextLoad,
    publicReissue.bits.nextIdentity)
  for (lane <- 0 until p.lsu.loadPipes) {
    val publicRebind = io.lsu.loadRebindApply(lane)
    publicRebind.valid := implementation.io.load.liqRebind.valid &&
      selectedRebindLane === lane.U
    publicRebind.bits := publicReissue.bits
  }
  implementation.io.load.liqRebind.ready := selectedRebindReady
  val publicRebindFire = VecInit(io.lsu.loadRebindApply.map(_.fire)).asUInt.orR
  when(publicReissue.fire || implementation.io.load.rebind.fire ||
      implementation.io.load.liqRebind.fire || publicRebindFire) {
    assert(publicReissue.fire && implementation.io.load.rebind.fire &&
      implementation.io.load.liqRebind.fire && publicRebindFire,
      "load lifecycle transition must atomically rebind metadata and LIQ")
  }
  io.lsu.loadCancel.foreach(_.ready := true.B)
  io.lsu.recoveryEvent.ready := true.B

  implementation.io.multiCycleAlu.foreach(_.ready := false.B)
  implementation.io.pointerAuth.foreach(_.ready := false.B)
  implementation.io.floatingVector.ready := false.B
  implementation.io.bctrl.foreach(_.ready := false.B)
}

/** Stable public IEX box. All retained state and arbitration live below this
  * wiring-only shell in the private canonical boundary owner.
  */
class IEX(val p: CoreParams) extends Module {
  val io = IO(new IEXIO(p))
  private val owner = Module(new OooIexBoundaryOwner(p))
  io <> owner.io
}
