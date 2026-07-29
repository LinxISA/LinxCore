package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}
import linxcore.common.{DestinationKind, OperandClass}

object OooIexTerminalSource extends ChiselEnum {
  val Alu, Bru, Load = Value
}

class OooIexTerminalWriteback(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val destination = new OooIexDestinationState(p)
  val data = UInt(p.pcWidth.W)
}

/** One normalized terminal transaction before architectural publication. */
class OooIexTerminalRequest(val p: OooParams = OooParams()) extends Bundle {
  val source = OooIexTerminalSource()
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

class OooIexTerminalPublishIO(val p: OooParams = OooParams()) extends Bundle {
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
  val completion = Decoupled(new OooRobMemberCompletion(p))

  val terminalFire = Output(Bool())
  val rejected = Vec(3, Valid(new OooIexTerminalReject(p)))
}

/** Fair ALU/BRU/load terminal arbiter with one atomic publication event.
  *
  * A selected owner is released only when every required physical-file write,
  * committed wakeup, ROB completion, trace record, and optional BCTRL update
  * fires together. No endpoint can consume a partial transaction while a peer
  * endpoint is backpressured.
  */
class OooIexTerminalPublish(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexTerminalPublishIO(p))

  private def identityExact(execute: OooIexExecuteTransaction): Bool = {
    val row = execute.i2.row
    row.valid && row.stid < p.stidCount.U && row.member.group.valid &&
      row.member.bid.valid && row.member.group.peId === row.peId &&
      row.member.group.stid === row.stid
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
  val bctrlReady = !selected.bits.bctrl.valid || io.bctrl.ready
  val allReady = io.completion.ready && io.trace.ready && bctrlReady &&
    allDestinationReady
  selected.ready := allReady

  io.completion.valid := selected.valid && io.trace.ready && bctrlReady &&
    allDestinationReady
  io.completion.bits.key := selected.bits.member
  io.completion.bits.faultValid := selected.bits.trapValid
  io.completion.bits.faultCause := selected.bits.trapCause

  io.trace.valid := selected.valid && io.completion.ready && bctrlReady &&
    allDestinationReady
  io.trace.bits.source := selected.bits.source
  io.trace.bits.member := selected.bits.member
  io.trace.bits.uopKey := selected.bits.uopKey
  io.trace.bits.opcode := selected.bits.opcode
  io.trace.bits.writebacks := selected.bits.writebacks
  io.trace.bits.load := selected.bits.load
  io.trace.bits.trapValid := selected.bits.trapValid
  io.trace.bits.trapCause := selected.bits.trapCause

  io.bctrl.valid := selected.valid && selected.bits.bctrl.valid &&
    io.completion.ready && io.trace.ready && allDestinationReady
  io.bctrl.bits.member := selected.bits.member
  io.bctrl.bits.uopKey := selected.bits.uopKey
  io.bctrl.bits.opcode := selected.bits.opcode
  io.bctrl.bits.update := selected.bits.bctrl

  for (index <- 0 until p.maxDestinationOperands) {
    val writeback = selected.bits.writebacks(index)
    val otherDestinationsReady = (0 until p.maxDestinationOperands)
      .filter(_ != index).map(destinationReady).foldLeft(true.B)(_ && _)
    val commonPeerReady = io.completion.ready && io.trace.ready &&
      bctrlReady && otherDestinationsReady
    val writeValid = selected.valid && writeRequired(index) &&
      commonPeerReady && io.wakeup(index).ready
    val wakeValid = selected.valid && writeRequired(index) &&
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

  io.terminalFire := selected.fire
  when(selected.fire) {
    assert(io.completion.fire && io.trace.fire,
      "terminal completion and trace must share the owner release")
    when(selected.bits.bctrl.valid) {
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
}
