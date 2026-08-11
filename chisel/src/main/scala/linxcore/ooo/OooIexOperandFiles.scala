package linxcore.ooo

import chisel3._
import chisel3.util.{PopCount, Valid, log2Ceil}
import linxcore.common.OperandClass

class OooIexPFileKey(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val ptag = UInt(p.pTagWidth.W)
  val generation = UInt(p.pTagGenerationWidth.W)
}

class OooIexPFileInit(val p: OooParams = OooParams()) extends Bundle {
  val key = new OooIexPFileKey(p)
  val data = UInt(p.pcWidth.W)
}

class OooIexPFileWrite(val p: OooParams = OooParams()) extends Bundle {
  val commit = Bool()
  val key = new OooIexPFileKey(p)
  val data = UInt(p.pcWidth.W)
}

class OooIexLocalFileKey(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val tag = UInt(p.localTagWidth.W)
  val sequence = new OooLocalSeq(p)
}

class OooIexLocalFileWrite(val p: OooParams = OooParams()) extends Bundle {
  val commit = Bool()
  val key = new OooIexLocalFileKey(p)
  val data = UInt(p.pcWidth.W)
}

class OooIexOperandFilesIO(val p: OooParams = OooParams()) extends Bundle {
  val pReadRequests = Input(Vec(p.iexPReadPorts,
    Valid(new OooIexOperandReadPortRequest(p))))
  val pReadResponses = Output(Vec(p.iexPReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val tReadRequests = Input(Vec(p.iexTReadPorts,
    Valid(new OooIexOperandReadPortRequest(p))))
  val tReadResponses = Output(Vec(p.iexTReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val uReadRequests = Input(Vec(p.iexUReadPorts,
    Valid(new OooIexOperandReadPortRequest(p))))
  val uReadResponses = Output(Vec(p.iexUReadPorts,
    Valid(UInt(p.pcWidth.W))))

  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val pClear = Flipped(Vec(p.pTagAllocationWidth,
    Valid(new OooIexPFileKey(p))))
  val pWrite = Flipped(Vec(p.iexPWritePorts,
    Valid(new OooIexPFileWrite(p))))
  val pWriteReady = Output(Vec(p.iexPWritePorts, Bool()))
  val pWriteFire = Output(Vec(p.iexPWritePorts, Bool()))
  val pReadyMask = Output(UInt(p.pPhysRegs.W))

  val tClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val uClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val tWrite = Flipped(Vec(p.iexTWritePorts,
    Valid(new OooIexLocalFileWrite(p))))
  val uWrite = Flipped(Vec(p.iexUWritePorts,
    Valid(new OooIexLocalFileWrite(p))))
  val tWriteReady = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteReady = Output(Vec(p.iexUWritePorts, Bool()))
  val tWriteFire = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteFire = Output(Vec(p.iexUWritePorts, Bool()))

  val tAllocatedCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.tPhysRegs).W)))
  val uAllocatedCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.uPhysRegs).W)))
  val tReadyCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.tPhysRegs).W)))
  val uReadyCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.uPhysRegs).W)))
  val pProtocolError = Output(Bool())
  val localProtocolError = Output(Bool())
  val readyBits = Output(new OooIexOperandReadyBits(p))
}

/** Canonical physical operand data owners for the formal OOO path.
  *
  * P data and non-speculative readiness are private to this canonical owner.
  * T and U are independent STID-local arrays whose allocation
  * identity is the complete `{tag, sequence, epoch}` tuple. Allocation clear
  * installs a new identity as not-ready; only an exact committed write makes
  * its data readable. Recovery does not roll data back: a later allocation
  * clear changes the visible identity and makes stale responses unreachable.
  */
class OooIexOperandFiles(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexOperandFilesIO(p))
  private val pTagIndexWidth = log2Ceil(p.pPhysRegs)
  private val tTagIndexWidth = log2Ceil(p.tPhysRegs)
  private val uTagIndexWidth = log2Ceil(p.uPhysRegs)

  private val pFile = Module(new OooIexPDataFile(
    archRegs = p.pArchRegs,
    physRegs = p.pPhysRegs,
    dataWidth = p.pcWidth,
    readPorts = p.iexPReadPorts,
    writePorts = p.iexPWritePorts,
    clearPorts = p.pTagAllocationWidth))

  val pOwnerValid = RegInit(VecInit(Seq.fill(p.pPhysRegs)(false.B)))
  val pOwnerStid = Reg(Vec(p.pPhysRegs, UInt(p.stidWidth.W)))
  val pOwnerEpoch = Reg(Vec(p.pPhysRegs, UInt(p.epochWidth.W)))
  val pOwnerGeneration = Reg(Vec(p.pPhysRegs,
    UInt(p.pTagGenerationWidth.W)))

  for (port <- 0 until p.iexPReadPorts) {
    val request = io.pReadRequests(port)
    val stidInRange = request.bits.stid < p.stidCount.U
    val tagInRange = request.bits.source.ptag < p.pPhysRegs.U
    val safeTag = Mux(tagInRange,
      request.bits.source.ptag, 0.U)(pTagIndexWidth - 1, 0)
    val ownerExact = stidInRange && tagInRange &&
      pOwnerValid(safeTag) &&
      pOwnerStid(safeTag) === request.bits.stid &&
      pOwnerGeneration(safeTag) === request.bits.source.ptagGeneration
    pFile.io.readValid(port) := request.valid &&
      request.bits.source.operandClass === OperandClass.P &&
      stidInRange && tagInRange
    pFile.io.readTag(port) := request.bits.source.ptag
    io.pReadResponses(port).valid := request.valid &&
      request.bits.source.operandClass === OperandClass.P &&
      ownerExact && pFile.io.readReady(port)
    io.pReadResponses(port).bits := Mux(
      io.pReadResponses(port).valid, pFile.io.readData(port), 0.U)
  }

  val pInitStidInRange = io.pInit.bits.key.stid < p.stidCount.U
  val pInitTagInRange = io.pInit.bits.key.ptag < p.pPhysRegs.U
  val safePInitTag = Mux(pInitTagInRange,
    io.pInit.bits.key.ptag, 0.U)(pTagIndexWidth - 1, 0)
  val invalidPInit = io.pInit.valid &&
    !(pInitStidInRange && pInitTagInRange)
  pFile.io.initValid := io.pInit.valid && !invalidPInit
  pFile.io.initTag := io.pInit.bits.key.ptag
  pFile.io.initData := io.pInit.bits.data
  when(io.pInit.valid && !invalidPInit) {
    pOwnerValid(safePInitTag) := true.B
    pOwnerStid(safePInitTag) := io.pInit.bits.key.stid
    pOwnerEpoch(safePInitTag) := io.pInit.bits.key.epoch
    pOwnerGeneration(safePInitTag) := io.pInit.bits.key.generation
  }

  val invalidPClear = Wire(Vec(p.pTagAllocationWidth, Bool()))
  for (port <- 0 until p.pTagAllocationWidth) {
    val key = io.pClear(port).bits
    val stidInRange = key.stid < p.stidCount.U
    val tagInRange = key.ptag < p.pPhysRegs.U
    val safeTag = Mux(tagInRange, key.ptag, 0.U)(pTagIndexWidth - 1, 0)
    invalidPClear(port) := io.pClear(port).valid &&
      !(stidInRange && tagInRange)
    when(io.pClear(port).valid && !invalidPClear(port)) {
      pOwnerValid(safeTag) := true.B
      pOwnerStid(safeTag) := key.stid
      pOwnerEpoch(safeTag) := key.epoch
      pOwnerGeneration(safeTag) := key.generation
    }
  }
  val duplicatePClear = (0 until p.pTagAllocationWidth).flatMap { left =>
    (left + 1 until p.pTagAllocationWidth).map { right =>
      io.pClear(left).valid && io.pClear(right).valid &&
        io.pClear(left).bits.ptag === io.pClear(right).bits.ptag
    }
  }.foldLeft(false.B)(_ || _)
  pFile.io.clearValid := io.pClear(0).valid && !invalidPClear(0)
  pFile.io.clearTag := io.pClear(0).bits.ptag
  pFile.io.clearSecondValid := io.pClear(1).valid && !invalidPClear(1)
  pFile.io.clearSecondTag := io.pClear(1).bits.ptag
  for (port <- 2 until p.pTagAllocationWidth) {
    pFile.io.additionalClearValid(port - 2) :=
      io.pClear(port).valid && !invalidPClear(port)
    pFile.io.additionalClearTag(port - 2) := io.pClear(port).bits.ptag
  }

  val stalePWrite = Wire(Vec(p.iexPWritePorts, Bool()))
  val pWriteFire = Wire(Vec(p.iexPWritePorts, Bool()))
  val duplicatePWrite = (0 until p.iexPWritePorts).flatMap { left =>
    (left + 1 until p.iexPWritePorts).map { right =>
      io.pWrite(left).valid && io.pWrite(left).bits.commit &&
        io.pWrite(right).valid && io.pWrite(right).bits.commit &&
        io.pWrite(left).bits.key.ptag === io.pWrite(right).bits.key.ptag
    }
  }.foldLeft(false.B)(_ || _)
  for (port <- 0 until p.iexPWritePorts) {
    val request = io.pWrite(port)
    val key = request.bits.key
    val stidInRange = key.stid < p.stidCount.U
    val tagInRange = key.ptag < p.pPhysRegs.U
    val safeTag = Mux(tagInRange, key.ptag, 0.U)(pTagIndexWidth - 1, 0)
    val ownerExact = stidInRange && tagInRange &&
      pOwnerValid(safeTag) && pOwnerStid(safeTag) === key.stid &&
      pOwnerEpoch(safeTag) === key.epoch &&
      pOwnerGeneration(safeTag) === key.generation
    // Ready is an owner preflight and must not depend on peer request valids.
    // The global duplicate guard below makes an invalid same-target vector
    // fail closed without creating a valid-to-ready combinational path.
    pFile.io.write(port).requestValid := request.valid && ownerExact &&
      !duplicatePWrite
    pFile.io.write(port).commit := request.valid &&
      request.bits.commit && ownerExact && !duplicatePWrite
    pFile.io.write(port).tag := request.bits.key.ptag
    pFile.io.write(port).data := io.pWrite(port).bits.data
    io.pWriteReady(port) := ownerExact
    pWriteFire(port) := request.valid && request.bits.commit &&
      ownerExact && pFile.io.write(port).fire
    io.pWriteFire(port) := pWriteFire(port)
    stalePWrite(port) := request.valid && !ownerExact
  }
  io.pReadyMask := pFile.io.readyMask
  for (tag <- 0 until p.pPhysRegs) {
    io.readyBits.ptag(tag).valid := pOwnerValid(tag)
    io.readyBits.ptag(tag).ready := pFile.io.readyMask(tag)
    io.readyBits.ptag(tag).stid := pOwnerStid(tag)
    io.readyBits.ptag(tag).epoch := pOwnerEpoch(tag)
    io.readyBits.ptag(tag).generation := pOwnerGeneration(tag)
  }
  val pInitClearCollision = io.pInit.valid &&
    (0 until p.pTagAllocationWidth).map { port =>
    io.pClear(port).valid &&
      io.pInit.bits.key.ptag === io.pClear(port).bits.ptag
  }.foldLeft(false.B)(_ || _)
  val pClearWriteCollision = (0 until p.pTagAllocationWidth).flatMap { clear =>
    (0 until p.iexPWritePorts).map { write =>
      io.pClear(clear).valid && pWriteFire(write) &&
        io.pClear(clear).bits.ptag === io.pWrite(write).bits.key.ptag
    }
  }.foldLeft(false.B)(_ || _)
  val pInitWriteCollision = io.pInit.valid &&
    (0 until p.iexPWritePorts).map { write =>
      pWriteFire(write) &&
        io.pInit.bits.key.ptag === io.pWrite(write).bits.key.ptag
    }.foldLeft(false.B)(_ || _)
  io.pProtocolError := pFile.io.protocolError || invalidPInit ||
    invalidPClear.asUInt.orR || duplicatePClear ||
    stalePWrite.asUInt.orR || duplicatePWrite || pInitClearCollision ||
    pClearWriteCollision || pInitWriteCollision
  assert(!io.pProtocolError,
    "P data mutation requires one exact generation-qualified owner")

  val tAllocated = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tPhysRegs)(false.B)))))
  val tReady = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tPhysRegs)(false.B)))))
  val tEpoch = Reg(Vec(p.stidCount,
    Vec(p.tPhysRegs, UInt(p.epochWidth.W))))
  val tSequence = Reg(Vec(p.stidCount,
    Vec(p.tPhysRegs, new OooLocalSeq(p))))
  val tData = Reg(Vec(p.stidCount,
    Vec(p.tPhysRegs, UInt(p.pcWidth.W))))

  val uAllocated = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.uPhysRegs)(false.B)))))
  val uReady = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.uPhysRegs)(false.B)))))
  val uEpoch = Reg(Vec(p.stidCount,
    Vec(p.uPhysRegs, UInt(p.epochWidth.W))))
  val uSequence = Reg(Vec(p.stidCount,
    Vec(p.uPhysRegs, new OooLocalSeq(p))))
  val uData = Reg(Vec(p.stidCount,
    Vec(p.uPhysRegs, UInt(p.pcWidth.W))))

  // A single-STID profile must retain the full STID range check without
  // elaborating a dynamic index into Vec(1).  These accessors select the
  // resident bank statically for that profile and keep the multi-STID shape
  // unchanged.
  private def tAllocatedAt(stid: UInt, tag: UInt): Bool =
    if (p.stidCount == 1) tAllocated(0)(tag) else tAllocated(stid)(tag)
  private def tReadyAt(stid: UInt, tag: UInt): Bool =
    if (p.stidCount == 1) tReady(0)(tag) else tReady(stid)(tag)
  private def tEpochAt(stid: UInt, tag: UInt): UInt =
    if (p.stidCount == 1) tEpoch(0)(tag) else tEpoch(stid)(tag)
  private def tSequenceAt(stid: UInt, tag: UInt): OooLocalSeq =
    if (p.stidCount == 1) tSequence(0)(tag) else tSequence(stid)(tag)
  private def tDataAt(stid: UInt, tag: UInt): UInt =
    if (p.stidCount == 1) tData(0)(tag) else tData(stid)(tag)

  private def uAllocatedAt(stid: UInt, tag: UInt): Bool =
    if (p.stidCount == 1) uAllocated(0)(tag) else uAllocated(stid)(tag)
  private def uReadyAt(stid: UInt, tag: UInt): Bool =
    if (p.stidCount == 1) uReady(0)(tag) else uReady(stid)(tag)
  private def uEpochAt(stid: UInt, tag: UInt): UInt =
    if (p.stidCount == 1) uEpoch(0)(tag) else uEpoch(stid)(tag)
  private def uSequenceAt(stid: UInt, tag: UInt): OooLocalSeq =
    if (p.stidCount == 1) uSequence(0)(tag) else uSequence(stid)(tag)
  private def uDataAt(stid: UInt, tag: UInt): UInt =
    if (p.stidCount == 1) uData(0)(tag) else uData(stid)(tag)

  for (stid <- 0 until p.stidCount; tag <- 0 until p.tPhysRegs) {
    io.readyBits.ttag(stid)(tag).allocated := tAllocated(stid)(tag)
    io.readyBits.ttag(stid)(tag).ready := tReady(stid)(tag)
    io.readyBits.ttag(stid)(tag).epoch := tEpoch(stid)(tag)
    io.readyBits.ttag(stid)(tag).sequence := tSequence(stid)(tag)
  }
  for (stid <- 0 until p.stidCount; tag <- 0 until p.uPhysRegs) {
    io.readyBits.utag(stid)(tag).allocated := uAllocated(stid)(tag)
    io.readyBits.utag(stid)(tag).ready := uReady(stid)(tag)
    io.readyBits.utag(stid)(tag).epoch := uEpoch(stid)(tag)
    io.readyBits.utag(stid)(tag).sequence := uSequence(stid)(tag)
  }

  private def sameLocalTarget(
      left: OooIexLocalFileKey,
      right: OooIexLocalFileKey): Bool =
    left.stid === right.stid && left.tag === right.tag

  val invalidTClear = Wire(Vec(p.tuAllocationWidth, Bool()))
  val invalidUClear = Wire(Vec(p.tuAllocationWidth, Bool()))
  for (port <- 0 until p.tuAllocationWidth) {
    val tKey = io.tClear(port).bits
    val tStidInRange = tKey.stid < p.stidCount.U
    val tTagInRange = tKey.tag < p.tPhysRegs.U
    val safeTTag = Mux(tTagInRange, tKey.tag, 0.U)(tTagIndexWidth - 1, 0)
    invalidTClear(port) := io.tClear(port).valid &&
      !(tStidInRange && tTagInRange && tKey.sequence.valid)
    when(io.tClear(port).valid && !invalidTClear(port)) {
      if (p.stidCount == 1) {
        tAllocated(0)(safeTTag) := true.B
        tReady(0)(safeTTag) := false.B
        tEpoch(0)(safeTTag) := tKey.epoch
        tSequence(0)(safeTTag) := tKey.sequence
      } else {
        val safeTStid = Mux(tStidInRange, tKey.stid, 0.U)
        tAllocated(safeTStid)(safeTTag) := true.B
        tReady(safeTStid)(safeTTag) := false.B
        tEpoch(safeTStid)(safeTTag) := tKey.epoch
        tSequence(safeTStid)(safeTTag) := tKey.sequence
      }
    }

    val uKey = io.uClear(port).bits
    val uStidInRange = uKey.stid < p.stidCount.U
    val uTagInRange = uKey.tag < p.uPhysRegs.U
    val safeUTag = Mux(uTagInRange, uKey.tag, 0.U)(uTagIndexWidth - 1, 0)
    invalidUClear(port) := io.uClear(port).valid &&
      !(uStidInRange && uTagInRange && uKey.sequence.valid)
    when(io.uClear(port).valid && !invalidUClear(port)) {
      if (p.stidCount == 1) {
        uAllocated(0)(safeUTag) := true.B
        uReady(0)(safeUTag) := false.B
        uEpoch(0)(safeUTag) := uKey.epoch
        uSequence(0)(safeUTag) := uKey.sequence
      } else {
        val safeUStid = Mux(uStidInRange, uKey.stid, 0.U)
        uAllocated(safeUStid)(safeUTag) := true.B
        uReady(safeUStid)(safeUTag) := false.B
        uEpoch(safeUStid)(safeUTag) := uKey.epoch
        uSequence(safeUStid)(safeUTag) := uKey.sequence
      }
    }
  }

  val duplicateTClear = (0 until p.tuAllocationWidth).flatMap { left =>
    (left + 1 until p.tuAllocationWidth).map { right =>
      io.tClear(left).valid && io.tClear(right).valid &&
        sameLocalTarget(io.tClear(left).bits, io.tClear(right).bits)
    }
  }.foldLeft(false.B)(_ || _)
  val duplicateUClear = (0 until p.tuAllocationWidth).flatMap { left =>
    (left + 1 until p.tuAllocationWidth).map { right =>
      io.uClear(left).valid && io.uClear(right).valid &&
        sameLocalTarget(io.uClear(left).bits, io.uClear(right).bits)
    }
  }.foldLeft(false.B)(_ || _)

  val staleTWrite = Wire(Vec(p.iexTWritePorts, Bool()))
  val staleUWrite = Wire(Vec(p.iexUWritePorts, Bool()))
  val tWriteFire = Wire(Vec(p.iexTWritePorts, Bool()))
  val uWriteFire = Wire(Vec(p.iexUWritePorts, Bool()))
  val duplicateTWrite = (0 until p.iexTWritePorts).flatMap { left =>
    (left + 1 until p.iexTWritePorts).map { right =>
      io.tWrite(left).valid && io.tWrite(left).bits.commit &&
        io.tWrite(right).valid && io.tWrite(right).bits.commit &&
        sameLocalTarget(io.tWrite(left).bits.key,
          io.tWrite(right).bits.key)
    }
  }.foldLeft(false.B)(_ || _)
  val duplicateUWrite = (0 until p.iexUWritePorts).flatMap { left =>
    (left + 1 until p.iexUWritePorts).map { right =>
      io.uWrite(left).valid && io.uWrite(left).bits.commit &&
        io.uWrite(right).valid && io.uWrite(right).bits.commit &&
        sameLocalTarget(io.uWrite(left).bits.key,
          io.uWrite(right).bits.key)
    }
  }.foldLeft(false.B)(_ || _)
  for (port <- 0 until p.iexTWritePorts) {
    val request = io.tWrite(port)
    val key = request.bits.key
    val stidInRange = key.stid < p.stidCount.U
    val tagInRange = key.tag < p.tPhysRegs.U
    val safeStid = Mux(stidInRange, key.stid, 0.U)
    val safeTag = Mux(tagInRange, key.tag, 0.U)(tTagIndexWidth - 1, 0)
    val ownerExact = stidInRange && tagInRange && key.sequence.valid &&
      tAllocatedAt(safeStid, safeTag) &&
      tEpochAt(safeStid, safeTag) === key.epoch &&
      tSequenceAt(safeStid, safeTag).asUInt === key.sequence.asUInt
    io.tWriteReady(port) := ownerExact
    staleTWrite(port) := request.valid && !ownerExact
    tWriteFire(port) := request.valid && request.bits.commit &&
      io.tWriteReady(port) && !duplicateTWrite
    io.tWriteFire(port) := tWriteFire(port)
    when(tWriteFire(port)) {
      if (p.stidCount == 1) {
        tData(0)(safeTag) := request.bits.data
        tReady(0)(safeTag) := true.B
      } else {
        tData(safeStid)(safeTag) := request.bits.data
        tReady(safeStid)(safeTag) := true.B
      }
    }
  }
  for (port <- 0 until p.iexUWritePorts) {
    val request = io.uWrite(port)
    val key = request.bits.key
    val stidInRange = key.stid < p.stidCount.U
    val tagInRange = key.tag < p.uPhysRegs.U
    val safeStid = Mux(stidInRange, key.stid, 0.U)
    val safeTag = Mux(tagInRange, key.tag, 0.U)(uTagIndexWidth - 1, 0)
    val ownerExact = stidInRange && tagInRange && key.sequence.valid &&
      uAllocatedAt(safeStid, safeTag) &&
      uEpochAt(safeStid, safeTag) === key.epoch &&
      uSequenceAt(safeStid, safeTag).asUInt === key.sequence.asUInt
    io.uWriteReady(port) := ownerExact
    staleUWrite(port) := request.valid && !ownerExact
    uWriteFire(port) := request.valid && request.bits.commit &&
      io.uWriteReady(port) && !duplicateUWrite
    io.uWriteFire(port) := uWriteFire(port)
    when(uWriteFire(port)) {
      if (p.stidCount == 1) {
        uData(0)(safeTag) := request.bits.data
        uReady(0)(safeTag) := true.B
      } else {
        uData(safeStid)(safeTag) := request.bits.data
        uReady(safeStid)(safeTag) := true.B
      }
    }
  }

  for (port <- 0 until p.iexTReadPorts) {
    val request = io.tReadRequests(port)
    val key = request.bits.source
    val stidInRange = request.bits.stid < p.stidCount.U
    val tagInRange = key.localTag < p.tPhysRegs.U
    val safeStid = Mux(stidInRange, request.bits.stid, 0.U)
    val safeTag = Mux(tagInRange, key.localTag, 0.U)(tTagIndexWidth - 1, 0)
    val exact = request.valid && key.operandClass === OperandClass.T &&
      stidInRange && tagInRange && key.localSequence.valid &&
      tAllocatedAt(safeStid, safeTag) && tReadyAt(safeStid, safeTag) &&
      tEpochAt(safeStid, safeTag) === request.bits.epoch &&
      tSequenceAt(safeStid, safeTag).asUInt === key.localSequence.asUInt
    io.tReadResponses(port).valid := exact
    io.tReadResponses(port).bits := Mux(exact,
      tDataAt(safeStid, safeTag), 0.U)
  }
  for (port <- 0 until p.iexUReadPorts) {
    val request = io.uReadRequests(port)
    val key = request.bits.source
    val stidInRange = request.bits.stid < p.stidCount.U
    val tagInRange = key.localTag < p.uPhysRegs.U
    val safeStid = Mux(stidInRange, request.bits.stid, 0.U)
    val safeTag = Mux(tagInRange, key.localTag, 0.U)(uTagIndexWidth - 1, 0)
    val exact = request.valid && key.operandClass === OperandClass.U &&
      stidInRange && tagInRange && key.localSequence.valid &&
      uAllocatedAt(safeStid, safeTag) && uReadyAt(safeStid, safeTag) &&
      uEpochAt(safeStid, safeTag) === request.bits.epoch &&
      uSequenceAt(safeStid, safeTag).asUInt === key.localSequence.asUInt
    io.uReadResponses(port).valid := exact
    io.uReadResponses(port).bits := Mux(exact,
      uDataAt(safeStid, safeTag), 0.U)
  }

  val tClearWriteCollision = (0 until p.tuAllocationWidth).flatMap { clear =>
    (0 until p.iexTWritePorts).map { write =>
      io.tClear(clear).valid && tWriteFire(write) &&
        sameLocalTarget(io.tClear(clear).bits, io.tWrite(write).bits.key)
    }
  }.foldLeft(false.B)(_ || _)
  val uClearWriteCollision = (0 until p.tuAllocationWidth).flatMap { clear =>
    (0 until p.iexUWritePorts).map { write =>
      io.uClear(clear).valid && uWriteFire(write) &&
        sameLocalTarget(io.uClear(clear).bits, io.uWrite(write).bits.key)
    }
  }.foldLeft(false.B)(_ || _)

  io.localProtocolError := invalidTClear.asUInt.orR ||
    invalidUClear.asUInt.orR || duplicateTClear || duplicateUClear ||
    duplicateTWrite || duplicateUWrite ||
    staleTWrite.asUInt.orR || staleUWrite.asUInt.orR ||
    tClearWriteCollision || uClearWriteCollision
  assert(!io.localProtocolError,
    "T/U data mutation requires one exact local sequence owner")

  for (stid <- 0 until p.stidCount) {
    io.tAllocatedCount(stid) := PopCount(tAllocated(stid))
    io.uAllocatedCount(stid) := PopCount(uAllocated(stid))
    io.tReadyCount(stid) := PopCount(tReady(stid))
    io.uReadyCount(stid) := PopCount(uReady(stid))
  }
}
