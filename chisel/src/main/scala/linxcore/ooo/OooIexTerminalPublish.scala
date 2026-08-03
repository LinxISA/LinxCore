package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.params.CoreParams
import linxcore.top.interface.{CmdIssueTxn, InstructionIdentity, RecoveryCause,
  RecoveryEvent, RecoveryPhase, RecoveryPlan, RecoveryPlanContract,
  RecoveryTargetIO, RobIdentity, RobNoflushReadyTxn, RobNoflushTxn,
  RobResolveTxn, SystemIssueTxn, TrapEvent, TrapKind}

object OooIexTerminalSource extends ChiselEnum {
  val Alu, Bru, Load, System, Cmd = Value
}

class OooIexTerminalWriteback(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val destination = new OooIexDestinationState(p)
  val data = UInt(p.pcWidth.W)
}

/** One normalized terminal transaction before architectural publication. */
class OooIexTerminalRequest(val p: OooParams = OooParams()) extends Bundle {
  val source = OooIexTerminalSource()
  val transactionId = UInt(p.transactionIdWidth.W)
  val member = new RobMemberKey(p)
  val uopKey = new CanonicalUopKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val writebacks = Vec(p.maxDestinationOperands,
    new OooIexTerminalWriteback(p))
  val bctrl = new OooIexBctrlUpdate(p)
  val load = new OooIexLoadGeneration(p)
  val trapValid = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
}

class OooIexTerminalBctrl(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val uopKey = new CanonicalUopKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val update = new OooIexBctrlUpdate(p)
}

/** Execution-terminal trace. This is not the later architectural commit row. */
class OooIexTerminalTrace(val p: OooParams = OooParams()) extends Bundle {
  val source = OooIexTerminalSource()
  val member = new RobMemberKey(p)
  val uopKey = new CanonicalUopKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val writebacks = Vec(p.maxDestinationOperands,
    new OooIexTerminalWriteback(p))
  val load = new OooIexLoadGeneration(p)
  val trapValid = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
}

class OooIexTerminalReject(val p: OooParams = OooParams()) extends Bundle {
  val source = OooIexTerminalSource()
  val member = new RobMemberKey(p)
  val identityExact = Bool()
  val shapeExact = Bool()
  val duplicateDestination = Bool()
}

class OooIexTerminalPublishIO(val core: CoreParams) extends Bundle {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  val alu = Flipped(Decoupled(new OooIexAluTerminalTransaction(p)))
  val bru = Flipped(Decoupled(new OooIexBruTerminalTransaction(p)))
  val load = Flipped(Decoupled(new OooIexLoadResult(p)))

  val pWrite = Vec(p.maxDestinationOperands,
    Decoupled(new OooIexPFileWrite(p)))
  val tWrite = Vec(p.maxDestinationOperands,
    Decoupled(new OooIexLocalFileWrite(p)))
  val uWrite = Vec(p.maxDestinationOperands,
    Decoupled(new OooIexLocalFileWrite(p)))
  val wakeup = Vec(p.maxDestinationOperands,
    Decoupled(new OooIexWakeup(p)))
  val bctrl = Decoupled(new OooIexTerminalBctrl(p))
  val trace = Decoupled(new OooIexTerminalTrace(p))
  val robResolve = Decoupled(new RobResolveTxn(core))
  val recoveryEvent = Decoupled(new RecoveryEvent(core))
  val recovery = Flipped(new RecoveryTargetIO(core))

  val terminalFire = Output(Bool())
  val rejected = Vec(3, Valid(new OooIexTerminalReject(p)))
}

class OooIexSystemCmdCandidate(val p: OooParams) extends Bundle {
  val cmd = Bool()
  val execute = new OooIexExecuteTransaction(p)
}

class OooIexSystemCmdTerminalIO(
    val core: CoreParams,
    val profile: OooIexPhysicalProfile) extends Bundle {
  val p: OooParams = profile.params
  private def capabilityCount(capability: Int): Int =
    profile.pickerFunctions.count(_.hasCapability(capability))
  private val systemCount = capabilityCount(OooIexDomainCapability.System)
  private val cmdCount = capabilityCount(OooIexDomainCapability.EngineCommand)

  val system = Flipped(Vec(systemCount,
    Decoupled(new OooIexExecuteTransaction(p))))
  val cmd = Flipped(Vec(cmdCount,
    Decoupled(new OooIexExecuteTransaction(p))))
  val robNoflushReady = Decoupled(new RobNoflushReadyTxn(core))
  val robNoflush = Flipped(Decoupled(new RobNoflushTxn(core)))
  val systemIssue = Vec(systemCount, Decoupled(new SystemIssueTxn(core)))
  val cmdIssue = Decoupled(new CmdIssueTxn(core))
  val robResolve = Decoupled(new RobResolveTxn(core))
  val trace = Decoupled(new OooIexTerminalTrace(p))
  val recovery = Flipped(new RecoveryTargetIO(core))
  val terminalFire = Output(Bool())
  val empty = Output(Bool())
}

/** Head-authorized no-destination System/CMD terminal rendezvous.
  *
  * The upstream E1 transfer slot remains the sole resident-uop owner. This
  * module only arbitrates the retained candidates, publishes an exact NFRDY
  * proof, and releases one candidate when the matching ROB authorization,
  * side-effect sink, and no-value ROB resolve all fire together. Unsupported
  * or destination-producing rows remain resident and fail closed.
  */
class OooIexSystemCmdTerminal(val core: CoreParams) extends Module {
  val profile = OooIexPhysicalProfile.fromCoreParams(core)
  val p: OooParams = profile.params
  private val systemCount = profile.pickerFunctions.count(
    _.hasCapability(OooIexDomainCapability.System))
  private val cmdCount = profile.pickerFunctions.count(
    _.hasCapability(OooIexDomainCapability.EngineCommand))
  require(systemCount == 1 && cmdCount == 1,
    "System/CMD terminal requires one System pipe and one CMD pipe")

  val io = IO(new OooIexSystemCmdTerminalIO(core, profile))

  private def toRobIdentity(execute: OooIexExecuteTransaction): RobIdentity =
    OooRecoveryMembership.robIdentity(p, core, execute.i2.row.member)

  private def toInstructionIdentity(
      execute: OooIexExecuteTransaction): InstructionIdentity = {
    val result = Wire(new InstructionIdentity(core))
    result := 0.U.asTypeOf(result)
    result.peId := execute.i2.row.uopKey.primaryParent.peId
    result.stid := execute.i2.row.uopKey.primaryParent.stid
    result.instructionId :=
      execute.i2.row.uopKey.primaryParent.instructionId
    result.epoch := execute.i2.row.epoch
    result
  }

  private def identityExact(execute: OooIexExecuteTransaction): Bool = {
    val row = execute.i2.row
    row.valid && row.stid < p.stidCount.U && row.member.group.valid &&
      row.member.bid.valid && row.member.group.peId === row.peId &&
      row.member.group.stid === row.stid &&
      row.member.memberIndex < core.ooo.maxInstructionsPerRobGroup.U &&
      row.uopKey.primaryParent.valid &&
      row.uopKey.primaryParent.peId === row.peId &&
      row.uopKey.primaryParent.stid === row.stid
  }

  private def noDestinations(execute: OooIexExecuteTransaction): Bool =
    !VecInit(execute.i2.row.destinations.map(_.valid)).asUInt.orR

  private def sourceMaskExact(execute: OooIexExecuteTransaction): Bool =
    execute.i2.sourceMask ===
      VecInit(execute.i2.row.sources.map(_.valid)).asUInt

  private def routeExact(
      execute: OooIexExecuteTransaction,
      ownerClass: OooUopClass.Type,
      dispatchClass: Int): Bool = {
    val row = execute.i2.row
    execute.ownerClass === ownerClass && row.reservation.valid &&
      row.reservation.uopClass === ownerClass && row.recipe.valid &&
      row.recipe.opcode === row.opcode &&
      row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      row.recipe.dispatchClass === dispatchClass.U &&
      row.recipe.sideEffectOwner === OooSideEffectOwner.Commit.U &&
      !row.preciseTrap && noDestinations(execute) && sourceMaskExact(execute)
  }

  private def memberKilled(
      plan: RecoveryPlan,
      execute: OooIexExecuteTransaction): Bool =
    RecoveryPlanContract.suffixMember(plan, toRobIdentity(execute))

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val retainedRecovery = Reg(new RecoveryPlan(core))
  val matchingApply = recoveryPending && io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val matchingAbort = recoveryPending && io.recovery.abort.valid &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)

  io.recovery.prepare.ready := !recoveryPending
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := retainedRecovery
  val prepareFire = io.recovery.prepare.fire
  when(prepareFire) {
    recoveryPending := true.B
    preparedValid := true.B
    retainedRecovery := io.recovery.prepare.bits
  }.elsewhen(matchingApply || matchingAbort) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  private def recoveryBlocked(execute: OooIexExecuteTransaction): Bool =
    (prepareFire && memberKilled(io.recovery.prepare.bits, execute)) ||
      (recoveryPending && memberKilled(retainedRecovery, execute))

  private def recoveryKilled(execute: OooIexExecuteTransaction): Bool =
    matchingApply && memberKilled(io.recovery.apply.bits, execute)

  val systemExact = identityExact(io.system.head.bits) &&
    routeExact(io.system.head.bits, OooUopClass.Sys, OooDispatchClass.Sys) &&
    !io.system.head.bits.i2.sourceMask.orR
  val cmdExact = identityExact(io.cmd.head.bits) &&
    routeExact(io.cmd.head.bits, OooUopClass.Cmd, OooDispatchClass.Cmd)
  val systemKilled = io.system.head.valid && recoveryKilled(io.system.head.bits)
  val cmdKilled = io.cmd.head.valid && recoveryKilled(io.cmd.head.bits)
  val systemEligible = io.system.head.valid && systemExact &&
    !recoveryBlocked(io.system.head.bits) && !systemKilled
  val cmdEligible = io.cmd.head.valid && cmdExact &&
    !recoveryBlocked(io.cmd.head.bits) && !cmdKilled

  val arbiter = Module(new RRArbiter(new OooIexSystemCmdCandidate(p), 2))
  arbiter.io.in(0).valid := systemEligible
  arbiter.io.in(0).bits.cmd := false.B
  arbiter.io.in(0).bits.execute := io.system.head.bits
  arbiter.io.in(1).valid := cmdEligible
  arbiter.io.in(1).bits.cmd := true.B
  arbiter.io.in(1).bits.execute := io.cmd.head.bits
  arbiter.io.out.ready := io.robNoflushReady.ready

  val selected = arbiter.io.out
  val selectedRob = toRobIdentity(selected.bits.execute)
  val selectedInstruction = toInstructionIdentity(selected.bits.execute)

  io.robNoflushReady.valid := selected.valid
  io.robNoflushReady.bits := 0.U.asTypeOf(io.robNoflushReady.bits)
  io.robNoflushReady.bits.transactionId :=
    selected.bits.execute.i2.row.transactionId
  io.robNoflushReady.bits.instruction := selectedInstruction
  io.robNoflushReady.bits.rob := selectedRob

  val permitExact = io.robNoflush.bits.transactionId ===
    io.robNoflushReady.bits.transactionId &&
    io.robNoflush.bits.instruction.asUInt === selectedInstruction.asUInt &&
    io.robNoflush.bits.rob.asUInt === selectedRob.asUInt
  val authorized = selected.valid && io.robNoflush.valid && permitExact

  io.systemIssue.foreach { issue =>
    issue.valid := false.B
    issue.bits := 0.U.asTypeOf(issue.bits)
    issue.bits.transactionId := selected.bits.execute.i2.row.transactionId
    issue.bits.instruction := selectedInstruction
    issue.bits.rob := selectedRob
    issue.bits.opcode := selected.bits.execute.i2.row.opcode
    issue.bits.immediate := selected.bits.execute.i2.row.immediate
  }
  io.systemIssue.head.valid := authorized && !selected.bits.cmd &&
    io.robResolve.ready && io.trace.ready

  io.cmdIssue.valid := authorized && selected.bits.cmd && io.robResolve.ready &&
    io.trace.ready
  io.cmdIssue.bits := 0.U.asTypeOf(io.cmdIssue.bits)
  io.cmdIssue.bits.transactionId := selected.bits.execute.i2.row.transactionId
  io.cmdIssue.bits.instruction := selectedInstruction
  io.cmdIssue.bits.rob := selectedRob
  io.cmdIssue.bits.opcode := selected.bits.execute.i2.row.opcode
  io.cmdIssue.bits.sourceValid := selected.bits.execute.i2.sourceMask
  for (source <- 0 until p.maxSourceOperands) {
    io.cmdIssue.bits.sourceValues(source) :=
      selected.bits.execute.i2.sourceData(source)
  }

  val sideEffectReady = Mux(selected.bits.cmd,
    io.cmdIssue.ready, io.systemIssue.head.ready)
  val sideEffectFire = Mux(selected.bits.cmd,
    io.cmdIssue.fire, io.systemIssue.head.fire)

  io.robResolve.valid := authorized && sideEffectReady && io.trace.ready
  io.robResolve.bits := 0.U.asTypeOf(io.robResolve.bits)
  io.robResolve.bits.transactionId := selected.bits.execute.i2.row.transactionId
  io.robResolve.bits.rob := selectedRob
  io.robResolve.bits.destinationValid := false.B
  io.robResolve.bits.destinationIndex := 0.U
  io.robResolve.bits.value := 0.U
  io.robResolve.bits.trap := 0.U.asTypeOf(io.robResolve.bits.trap)

  io.trace.valid := authorized && sideEffectReady && io.robResolve.ready
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
  io.trace.bits.source := Mux(selected.bits.cmd,
    OooIexTerminalSource.Cmd, OooIexTerminalSource.System)
  io.trace.bits.member := selected.bits.execute.i2.row.member
  io.trace.bits.uopKey := selected.bits.execute.i2.row.uopKey
  io.trace.bits.opcode := selected.bits.execute.i2.row.opcode

  io.robNoflush.ready := selected.valid && permitExact && sideEffectReady &&
    io.robResolve.ready && io.trace.ready
  val atomicFire = selected.valid && io.robNoflush.fire &&
    io.robNoflushReady.fire && sideEffectFire && io.robResolve.fire &&
    io.trace.fire
  io.system.head.ready := systemKilled || (!selected.bits.cmd && atomicFire)
  io.cmd.head.ready := cmdKilled || (selected.bits.cmd && atomicFire)
  io.terminalFire := atomicFire
  io.empty := !recoveryPending && !io.system.head.valid && !io.cmd.head.valid

  when(io.robNoflush.fire || sideEffectFire || io.robResolve.fire ||
    io.trace.fire) {
    assert(atomicFire,
      "System/CMD permit, side effect, resolve, trace, proof, and owner release must fire atomically")
  }
  when(atomicFire) {
    assert(!selected.bits.execute.i2.row.destinations.map(_.valid).reduce(_ || _),
      "destination-producing System/CMD operations must fail closed")
  }
}

/** Fair ALU/BRU/load terminal arbiter with one atomic publication event.
  *
  * A selected owner is released only when every required physical-file write,
  * committed wakeup, ROB resolve, trace record, and optional BCTRL update
  * fires together. No endpoint can consume a partial transaction while a peer
  * endpoint is backpressured.
  */
class OooIexTerminalPublish(val core: CoreParams) extends Module {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  val io = IO(new OooIexTerminalPublishIO(core))

  private def toRobIdentity(member: RobMemberKey): RobIdentity = {
    OooRecoveryMembership.robIdentity(p, core, member)
  }

  private def toInstructionIdentity(
      request: OooIexTerminalRequest): InstructionIdentity = {
    val result = Wire(new InstructionIdentity(core))
    result := 0.U.asTypeOf(result)
    result.peId := request.uopKey.primaryParent.peId
    result.stid := request.uopKey.primaryParent.stid
    result.instructionId := request.uopKey.primaryParent.instructionId
    result.epoch := request.epoch
    result
  }

  private def trapEvent(request: OooIexTerminalRequest): TrapEvent = {
    val result = Wire(new TrapEvent(core))
    result := 0.U.asTypeOf(result)
    result.valid := request.trapValid
    result.kind := Mux(request.trapValid, TrapKind.Exception, TrapKind.None)
    result.instruction := toInstructionIdentity(request)
    result.rob := toRobIdentity(request.member)
    result.cause := request.trapCause
    result
  }

  val recoveryPending = RegInit(false.B)
  val retainedRecovery = Reg(new RecoveryPlan(core))
  val preparedValid = RegInit(false.B)

  io.recovery.prepare.ready := !recoveryPending
  val directPrepared = io.recovery.prepare.valid && io.recovery.prepare.ready
  io.recovery.prepared.valid := preparedValid || directPrepared
  io.recovery.prepared.bits := Mux(preparedValid, retainedRecovery,
    io.recovery.prepare.bits)

  val matchingApply = recoveryPending && io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val matchingAbort = recoveryPending && io.recovery.abort.valid &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)

  when(io.recovery.prepare.fire) {
    recoveryPending := true.B
    retainedRecovery := io.recovery.prepare.bits
    preparedValid := !io.recovery.prepared.ready
  }.elsewhen(matchingApply || matchingAbort) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  private def identityExact(execute: OooIexExecuteTransaction): Bool = {
    val row = execute.i2.row
    row.valid && row.stid < p.stidCount.U && row.member.group.valid &&
      row.member.bid.valid && row.member.group.peId === row.peId &&
      row.member.group.stid === row.stid &&
      row.member.memberIndex < core.ooo.maxInstructionsPerRobGroup.U
  }

  private def routeExact(
      execute: OooIexExecuteTransaction,
      ownerClass: OooUopClass.Type,
      dispatchClass: Int,
      sideEffectOwner: Int): Bool = {
    val row = execute.i2.row
    execute.ownerClass === ownerClass && row.reservation.valid &&
      row.reservation.uopClass === ownerClass && row.recipe.valid &&
      row.recipe.opcode === row.opcode &&
      row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      row.recipe.dispatchClass === dispatchClass.U &&
      row.recipe.sideEffectOwner === sideEffectOwner.U
  }

  private def destinationExact(destination: OooIexDestinationState): Bool = {
    val pExact = destination.kind === DestinationKind.Gpr &&
      destination.ptag < p.pPhysRegs.U
    val tExact = destination.kind === DestinationKind.T &&
      destination.localTag < p.tPhysRegs.U && destination.localSequence.valid
    val uExact = destination.kind === DestinationKind.U &&
      destination.localTag < p.uPhysRegs.U && destination.localSequence.valid
    destination.valid && (pExact || tExact || uExact)
  }

  private def sameDestination(
      left: OooIexTerminalWriteback,
      right: OooIexTerminalWriteback): Bool = {
    val sameP = left.destination.kind === DestinationKind.Gpr &&
      right.destination.kind === DestinationKind.Gpr &&
      left.destination.ptag === right.destination.ptag &&
      left.destination.ptagGeneration === right.destination.ptagGeneration
    val sameT = left.destination.kind === DestinationKind.T &&
      right.destination.kind === DestinationKind.T &&
      left.destination.localTag === right.destination.localTag &&
      left.destination.localSequence.asUInt ===
        right.destination.localSequence.asUInt
    val sameU = left.destination.kind === DestinationKind.U &&
      right.destination.kind === DestinationKind.U &&
      left.destination.localTag === right.destination.localTag &&
      left.destination.localSequence.asUInt ===
        right.destination.localSequence.asUInt
    left.valid && right.valid && (sameP || sameT || sameU)
  }

  private def duplicateDestination(
      writebacks: Vec[OooIexTerminalWriteback]): Bool =
    (0 until p.maxDestinationOperands).flatMap { left =>
      (left + 1 until p.maxDestinationOperands).map { right =>
        sameDestination(writebacks(left), writebacks(right))
      }
    }.foldLeft(false.B)(_ || _)

  private def destinationsExact(
      writebacks: Vec[OooIexTerminalWriteback]): Bool =
    writebacks.map(writeback =>
      !writeback.valid || destinationExact(writeback.destination)).reduce(_ && _)

  private def normalizeExecute(
      source: OooIexTerminalSource.Type,
      execute: OooIexExecuteTransaction): OooIexTerminalRequest = {
    val request = Wire(new OooIexTerminalRequest(p))
    request := 0.U.asTypeOf(request)
    request.source := source
    request.transactionId := execute.i2.row.transactionId
    request.member := execute.i2.row.member
    request.uopKey := execute.i2.row.uopKey
    request.opcode := execute.i2.row.opcode
    request.stid := execute.i2.row.stid
    request.epoch := execute.i2.row.epoch
    request
  }

  val aluRequest = normalizeExecute(OooIexTerminalSource.Alu,
    io.alu.bits.execute)
  for (index <- 0 until p.maxDestinationOperands) {
    aluRequest.writebacks(index).valid := io.alu.bits.writebacks(index).valid
    aluRequest.writebacks(index).destination :=
      io.alu.bits.writebacks(index).destination
    aluRequest.writebacks(index).data := io.alu.bits.writebacks(index).data
  }
  val aluIdentityExact = identityExact(io.alu.bits.execute) &&
    routeExact(io.alu.bits.execute, OooUopClass.Alu,
      OooDispatchClass.Alu, OooSideEffectOwner.Iex)
  val aluDestinationMask = VecInit(
    io.alu.bits.execute.i2.row.destinations.map(_.valid)).asUInt
  val aluWritebackMask = VecInit(io.alu.bits.writebacks.map(_.valid)).asUInt
  val aluShapeExact = aluWritebackMask.orR &&
    aluWritebackMask === aluDestinationMask &&
    (0 until p.maxDestinationOperands).map { index =>
      !io.alu.bits.writebacks(index).valid ||
        io.alu.bits.writebacks(index).destination.asUInt ===
          io.alu.bits.execute.i2.row.destinations(index).asUInt
    }.reduce(_ && _) && destinationsExact(aluRequest.writebacks)
  val aluDuplicate = duplicateDestination(aluRequest.writebacks)
  val aluExact = aluIdentityExact && aluShapeExact && !aluDuplicate

  val bruRequest = normalizeExecute(OooIexTerminalSource.Bru,
    io.bru.bits.execute)
  bruRequest.writebacks(0).valid := io.bru.bits.writeback.valid
  bruRequest.writebacks(0).destination := io.bru.bits.writeback.destination
  bruRequest.writebacks(0).data := io.bru.bits.writeback.data
  bruRequest.bctrl := io.bru.bits.bctrl
  val bruIdentityExact = identityExact(io.bru.bits.execute) &&
    routeExact(io.bru.bits.execute, OooUopClass.Bru,
      OooDispatchClass.Bru, OooSideEffectOwner.Bctrl)
  val bruDestinationMask = VecInit(
    io.bru.bits.execute.i2.row.destinations.map(_.valid)).asUInt
  val bruExpectedMask = Mux(io.bru.bits.writeback.valid, 1.U, 0.U)
  val bruShapeExact = (io.bru.bits.writeback.valid ||
    io.bru.bits.bctrl.valid) && bruDestinationMask === bruExpectedMask &&
    (!io.bru.bits.writeback.valid || (
      io.bru.bits.writeback.destination.asUInt ===
        io.bru.bits.execute.i2.row.destinations(0).asUInt &&
      destinationExact(io.bru.bits.writeback.destination)))
  val bruDuplicate = duplicateDestination(bruRequest.writebacks)
  val bruExact = bruIdentityExact && bruShapeExact && !bruDuplicate

  val loadRequest = normalizeExecute(OooIexTerminalSource.Load,
    io.load.bits.agu.execute)
  loadRequest.writebacks(0).valid := !io.load.bits.faultValid
  loadRequest.writebacks(0).destination := io.load.bits.agu.destination
  loadRequest.writebacks(0).data := io.load.bits.data
  loadRequest.load := io.load.bits.load
  loadRequest.trapValid := io.load.bits.faultValid
  loadRequest.trapCause := io.load.bits.faultCause
  val loadIdentityExact = identityExact(io.load.bits.agu.execute) &&
    routeExact(io.load.bits.agu.execute, OooUopClass.Agu,
      OooDispatchClass.Agu, OooSideEffectOwner.Lsu) &&
    io.load.bits.load.valid && io.load.bits.load.producer.asUInt ===
      io.load.bits.agu.execute.i2.row.member.asUInt
  val loadDestinationMask = VecInit(
    io.load.bits.agu.execute.i2.row.destinations.map(_.valid)).asUInt
  val loadShapeExact = loadDestinationMask === 1.U &&
    io.load.bits.agu.destination.asUInt ===
      io.load.bits.agu.execute.i2.row.destinations(0).asUInt &&
    destinationExact(io.load.bits.agu.destination)
  val loadDuplicate = duplicateDestination(loadRequest.writebacks)
  val loadExact = loadIdentityExact && loadShapeExact && !loadDuplicate

  val arbiter = Module(new RRArbiter(new OooIexTerminalRequest(p), 3))
  arbiter.io.in(0).valid := io.alu.valid && aluExact
  arbiter.io.in(0).bits := aluRequest
  arbiter.io.in(1).valid := io.bru.valid && bruExact
  arbiter.io.in(1).bits := bruRequest
  arbiter.io.in(2).valid := io.load.valid && loadExact
  arbiter.io.in(2).bits := loadRequest
  io.alu.ready := arbiter.io.in(0).ready && aluExact
  io.bru.ready := arbiter.io.in(1).ready && bruExact
  io.load.ready := arbiter.io.in(2).ready && loadExact

  val exact = Seq(aluExact, bruExact, loadExact)
  val identity = Seq(aluIdentityExact, bruIdentityExact, loadIdentityExact)
  val shape = Seq(aluShapeExact, bruShapeExact, loadShapeExact)
  val duplicate = Seq(aluDuplicate, bruDuplicate, loadDuplicate)
  val members = Seq(aluRequest.member, bruRequest.member, loadRequest.member)
  for (index <- 0 until 3) {
    io.rejected(index).valid := Seq(io.alu.valid, io.bru.valid,
      io.load.valid)(index) && !exact(index)
    io.rejected(index).bits.source := OooIexTerminalSource(index.U)
    io.rejected(index).bits.member := members(index)
    io.rejected(index).bits.identityExact := identity(index)
    io.rejected(index).bits.shapeExact := shape(index)
    io.rejected(index).bits.duplicateDestination := duplicate(index)
  }

  val selected = arbiter.io.out
  val writeRequired = VecInit(selected.bits.writebacks.map(_.valid))
  val writeReady = Wire(Vec(p.maxDestinationOperands, Bool()))
  val destinationReady = Wire(Vec(p.maxDestinationOperands, Bool()))
  for (index <- 0 until p.maxDestinationOperands) {
    val writeback = selected.bits.writebacks(index)
    writeReady(index) := Mux(writeback.destination.kind === DestinationKind.Gpr,
      io.pWrite(index).ready,
      Mux(writeback.destination.kind === DestinationKind.T,
        io.tWrite(index).ready, io.uWrite(index).ready))
    destinationReady(index) := !writeRequired(index) ||
      (writeReady(index) && io.wakeup(index).ready)
  }
  val allDestinationReady = destinationReady.reduce(_ && _)
  val branchRedirect = selected.bits.bctrl.valid &&
    selected.bits.opcode === FrontendOpcodeDecodeTable.OP_J.U &&
    selected.bits.bctrl.kind === OooIexBctrlUpdateKind.Target &&
    selected.bits.bctrl.targetValid
  val bctrlRequired = selected.bits.bctrl.valid && !branchRedirect
  val bctrlReady = !bctrlRequired || io.bctrl.ready
  val recoveryEventRequired = selected.bits.trapValid || branchRedirect
  val recoveryEventReady = !recoveryEventRequired || io.recoveryEvent.ready
  val selectedRob = toRobIdentity(selected.bits.member)
  val recoveryKillsSelected = matchingApply && selected.valid &&
    RecoveryPlanContract.suffixMember(io.recovery.apply.bits, selectedRob)
  val recoveryBlocksSelected = selected.valid && (
    (recoveryPending && !matchingApply && !matchingAbort &&
      RecoveryPlanContract.suffixMember(retainedRecovery, selectedRob)) ||
    (directPrepared && RecoveryPlanContract.suffixMember(
      io.recovery.prepare.bits, selectedRob)))
  val publicationEnabled = selected.valid && !recoveryKillsSelected &&
    !recoveryBlocksSelected
  val allReady = io.robResolve.ready && io.trace.ready && bctrlReady &&
    allDestinationReady && recoveryEventReady
  selected.ready := recoveryKillsSelected || (!recoveryBlocksSelected && allReady)

  io.robResolve.valid := publicationEnabled && io.trace.ready && bctrlReady &&
    allDestinationReady && recoveryEventReady
  io.robResolve.bits := 0.U.asTypeOf(io.robResolve.bits)
  io.robResolve.bits.transactionId := selected.bits.transactionId
  io.robResolve.bits.rob := selectedRob
  io.robResolve.bits.destinationValid :=
    selected.bits.writebacks(0).valid && !selected.bits.trapValid
  io.robResolve.bits.destinationIndex := 0.U
  io.robResolve.bits.value := Mux(
    io.robResolve.bits.destinationValid,
    selected.bits.writebacks(0).data,
    0.U)
  io.robResolve.bits.trap := trapEvent(selected.bits)

  io.recoveryEvent.valid := publicationEnabled && recoveryEventRequired &&
    io.robResolve.ready && io.trace.ready && bctrlReady &&
    allDestinationReady
  io.recoveryEvent.bits := 0.U.asTypeOf(io.recoveryEvent.bits)
  io.recoveryEvent.bits.transactionId := selected.bits.transactionId
  io.recoveryEvent.bits.cause := Mux(branchRedirect,
    RecoveryCause.Branch, RecoveryCause.Exception)
  io.recoveryEvent.bits.trigger := selectedRob
  io.recoveryEvent.bits.instruction := toInstructionIdentity(selected.bits)
  io.recoveryEvent.bits.redirectPc := Mux(branchRedirect,
    selected.bits.bctrl.target, 0.U)
  io.recoveryEvent.bits.trap := trapEvent(selected.bits)

  io.trace.valid := publicationEnabled && io.robResolve.ready &&
    recoveryEventReady && bctrlReady && allDestinationReady
  io.trace.bits.source := selected.bits.source
  io.trace.bits.member := selected.bits.member
  io.trace.bits.uopKey := selected.bits.uopKey
  io.trace.bits.opcode := selected.bits.opcode
  io.trace.bits.writebacks := selected.bits.writebacks
  io.trace.bits.load := selected.bits.load
  io.trace.bits.trapValid := selected.bits.trapValid
  io.trace.bits.trapCause := selected.bits.trapCause

  io.bctrl.valid := publicationEnabled && bctrlRequired &&
    io.robResolve.ready && io.trace.ready && recoveryEventReady &&
    allDestinationReady
  io.bctrl.bits.member := selected.bits.member
  io.bctrl.bits.uopKey := selected.bits.uopKey
  io.bctrl.bits.opcode := selected.bits.opcode
  io.bctrl.bits.update := selected.bits.bctrl

  for (index <- 0 until p.maxDestinationOperands) {
    val writeback = selected.bits.writebacks(index)
    val otherDestinationsReady = (0 until p.maxDestinationOperands)
      .filter(_ != index).map(destinationReady).foldLeft(true.B)(_ && _)
    val commonPeerReady = io.robResolve.ready && io.trace.ready &&
      recoveryEventReady && bctrlReady && otherDestinationsReady
    val writeValid = publicationEnabled && writeRequired(index) &&
      commonPeerReady && io.wakeup(index).ready
    val wakeValid = publicationEnabled && writeRequired(index) &&
      commonPeerReady && writeReady(index)

    io.pWrite(index).valid := writeValid &&
      writeback.destination.kind === DestinationKind.Gpr
    io.pWrite(index).bits.commit := true.B
    io.pWrite(index).bits.key.stid := selected.bits.stid
    io.pWrite(index).bits.key.epoch := selected.bits.epoch
    io.pWrite(index).bits.key.ptag := writeback.destination.ptag
    io.pWrite(index).bits.key.generation :=
      writeback.destination.ptagGeneration
    io.pWrite(index).bits.data := writeback.data

    io.tWrite(index).valid := writeValid &&
      writeback.destination.kind === DestinationKind.T
    io.tWrite(index).bits.commit := true.B
    io.tWrite(index).bits.key.stid := selected.bits.stid
    io.tWrite(index).bits.key.epoch := selected.bits.epoch
    io.tWrite(index).bits.key.tag := writeback.destination.localTag
    io.tWrite(index).bits.key.sequence := writeback.destination.localSequence
    io.tWrite(index).bits.data := writeback.data

    io.uWrite(index).valid := writeValid &&
      writeback.destination.kind === DestinationKind.U
    io.uWrite(index).bits.commit := true.B
    io.uWrite(index).bits.key.stid := selected.bits.stid
    io.uWrite(index).bits.key.epoch := selected.bits.epoch
    io.uWrite(index).bits.key.tag := writeback.destination.localTag
    io.uWrite(index).bits.key.sequence := writeback.destination.localSequence
    io.uWrite(index).bits.data := writeback.data

    io.wakeup(index).valid := wakeValid
    io.wakeup(index).bits := 0.U.asTypeOf(io.wakeup(index).bits)
    io.wakeup(index).bits.kind := OooIexWakeupKind.Committed
    io.wakeup(index).bits.stid := selected.bits.stid
    io.wakeup(index).bits.epoch := selected.bits.epoch
    io.wakeup(index).bits.operandClass := Mux(
      writeback.destination.kind === DestinationKind.Gpr, OperandClass.P,
      Mux(writeback.destination.kind === DestinationKind.T,
        OperandClass.T, OperandClass.U))
    io.wakeup(index).bits.ptag := writeback.destination.ptag
    io.wakeup(index).bits.ptagGeneration :=
      writeback.destination.ptagGeneration
    io.wakeup(index).bits.localTag := writeback.destination.localTag
    io.wakeup(index).bits.localSequence :=
      writeback.destination.localSequence
    io.wakeup(index).bits.load := selected.bits.load
  }

  val publicationFire = selected.fire && !recoveryKillsSelected
  io.terminalFire := publicationFire
  when(publicationFire) {
    assert(io.robResolve.fire && io.trace.fire,
      "terminal ROB resolve and trace must share the owner release")
    when(recoveryEventRequired) {
      assert(io.recoveryEvent.fire,
        "required recovery event must share the terminal owner release")
    }
    when(bctrlRequired) {
      assert(io.bctrl.fire,
        "BCTRL mutation must share the terminal owner release")
    }
    for (index <- 0 until p.maxDestinationOperands) {
      when(writeRequired(index)) {
        assert((io.pWrite(index).fire || io.tWrite(index).fire ||
          io.uWrite(index).fire) && io.wakeup(index).fire,
          "RF write and committed wakeup must share terminal release")
      }
    }
  }
  when(recoveryKillsSelected) {
    assert(selected.fire && !io.robResolve.fire && !io.trace.fire &&
      !io.recoveryEvent.fire,
      "recovery-killed terminal ownership must release without publication")
  }
}
