package linxcore.ooo

import chisel3._
import chisel3.util.log2Ceil

private class OooIexPDataWritePort(tagWidth: Int, dataWidth: Int)
    extends Bundle {
  val requestValid = Input(Bool())
  val commit = Input(Bool())
  val tag = Input(UInt(tagWidth.W))
  val data = Input(UInt(dataWidth.W))
  val ready = Output(Bool())
  val fire = Output(Bool())
  val blockedByHigherSameTag = Output(Bool())
}

private class OooIexPDataFileIO(
    archRegs: Int,
    physRegs: Int,
    dataWidth: Int,
    readPorts: Int,
    writePorts: Int,
    clearPorts: Int)
    extends Bundle {
  private val tagWidth = log2Ceil(physRegs)

  val readValid = Input(Vec(readPorts, Bool()))
  val readTag = Input(Vec(readPorts, UInt(tagWidth.W)))
  val readData = Output(Vec(readPorts, UInt(dataWidth.W)))
  val readReady = Output(Vec(readPorts, Bool()))

  val initValid = Input(Bool())
  val initTag = Input(UInt(tagWidth.W))
  val initData = Input(UInt(dataWidth.W))
  val clearValid = Input(Bool())
  val clearTag = Input(UInt(tagWidth.W))
  val clearSecondValid = Input(Bool())
  val clearSecondTag = Input(UInt(tagWidth.W))
  val additionalClearValid = Input(Vec(clearPorts - 2, Bool()))
  val additionalClearTag = Input(Vec(clearPorts - 2, UInt(tagWidth.W)))

  val write = Vec(writePorts, new OooIexPDataWritePort(tagWidth, dataWidth))

  val readyMask = Output(UInt(physRegs.W))
  val clearWriteCollision = Output(Bool())
  val duplicateWriteCommit = Output(Bool())
  val protocolError = Output(Bool())
}

/** P physical-register data and non-speculative readiness owned by IEX. */
private class OooIexPDataFile(
    archRegs: Int,
    physRegs: Int,
    dataWidth: Int,
    readPorts: Int,
    writePorts: Int,
    clearPorts: Int)
    extends Module {
  require(archRegs == 24,
    "Linx scalar GPR namespace has 24 architectural registers")
  require(physRegs > archRegs && (physRegs & (physRegs - 1)) == 0,
    "physical GPR capacity must be a power of two above the architectural namespace")
  require(dataWidth > 0, "GPR data width must be positive")
  require(readPorts > 0, "GPR read-port count must be positive")
  require(writePorts > 0, "GPR write-port count must be positive")
  require(clearPorts >= 2, "GPR clear-port count must preserve both allocation ports")

  private val tagWidth = log2Ceil(physRegs)
  val io = IO(new OooIexPDataFileIO(
    archRegs, physRegs, dataWidth, readPorts, writePorts, clearPorts))

  private val data = RegInit(VecInit(Seq.fill(physRegs)(0.U(dataWidth.W))))
  private val ready = RegInit(VecInit(
    (0 until physRegs).map(index => (index < archRegs).B)))

  for (port <- 0 until readPorts) {
    io.readData(port) := data(io.readTag(port))
    io.readReady(port) := !io.readValid(port) || ready(io.readTag(port))
  }

  val blockedByHigher = Wire(Vec(writePorts, Bool()))
  val writeFire = Wire(Vec(writePorts, Bool()))
  for (port <- 0 until writePorts) {
    val higherSameTag =
      if (port == 0) false.B
      else VecInit((0 until port).map { higher =>
        io.write(higher).requestValid &&
          io.write(higher).tag === io.write(port).tag
      }).asUInt.orR
    blockedByHigher(port) := io.write(port).requestValid && higherSameTag
    io.write(port).ready := !higherSameTag
    writeFire(port) := io.write(port).requestValid &&
      io.write(port).commit && !higherSameTag
    io.write(port).fire := writeFire(port)
    io.write(port).blockedByHigherSameTag := blockedByHigher(port)
  }

  val anyWriteFire = writeFire.asUInt.orR
  val clearValid = Wire(Vec(clearPorts, Bool()))
  val clearTag = Wire(Vec(clearPorts, UInt(tagWidth.W)))
  clearValid(0) := io.clearValid
  clearTag(0) := io.clearTag
  clearValid(1) := io.clearSecondValid
  clearTag(1) := io.clearSecondTag
  for (port <- 2 until clearPorts) {
    clearValid(port) := io.additionalClearValid(port - 2)
    clearTag(port) := io.additionalClearTag(port - 2)
  }

  val clearWriteCollision = (0 until clearPorts).flatMap { clear =>
    (0 until writePorts).map { write =>
      clearValid(clear) && writeFire(write) &&
        io.write(write).tag === clearTag(clear)
    }
  }.foldLeft(false.B)(_ || _)
  val duplicateClear = (0 until clearPorts).flatMap { left =>
    (left + 1 until clearPorts).map { right =>
      clearValid(left) && clearValid(right) &&
        clearTag(left) === clearTag(right)
    }
  }.foldLeft(false.B)(_ || _)
  val duplicateWriteCommit =
    if (writePorts == 1) false.B
    else VecInit((0 until writePorts).flatMap { left =>
      (left + 1 until writePorts).map { right =>
        writeFire(left) && writeFire(right) &&
          io.write(left).tag === io.write(right).tag
      }
    }).asUInt.orR
  val commitWithoutRequest = VecInit((0 until writePorts).map { port =>
    io.write(port).commit && !io.write(port).requestValid
  }).asUInt.orR

  when(io.initValid) {
    data(io.initTag) := io.initData
    ready(io.initTag) := true.B
  }
  for (port <- 0 until clearPorts) {
    when(clearValid(port)) {
      ready(clearTag(port)) := false.B
    }
  }
  for (port <- 0 until writePorts) {
    when(writeFire(port)) {
      data(io.write(port).tag) := io.write(port).data
      ready(io.write(port).tag) := true.B
    }
  }

  io.readyMask := ready.asUInt
  io.clearWriteCollision := clearWriteCollision
  io.duplicateWriteCommit := duplicateWriteCommit
  io.protocolError := clearWriteCollision || duplicateClear ||
    duplicateWriteCommit || commitWithoutRequest ||
    (io.initValid && anyWriteFire)

  assert(!io.protocolError,
    "P-file mutation requires unique committed write ownership")
}
