package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

class ScalarGPRWritePort(val tagWidth: Int, val dataWidth: Int) extends Bundle {
  val requestValid = Input(Bool())
  val commit = Input(Bool())
  val tag = Input(UInt(tagWidth.W))
  val data = Input(UInt(dataWidth.W))
  val ready = Output(Bool())
  val fire = Output(Bool())
  val blockedByHigherSameTag = Output(Bool())
}

class ScalarGPRFileIO(
    val archRegs: Int,
    val physRegs: Int,
    val dataWidth: Int,
    val readPorts: Int,
    val writePorts: Int)
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

  val write = Vec(writePorts, new ScalarGPRWritePort(tagWidth, dataWidth))

  val readyMask = Output(UInt(physRegs.W))
  val clearWriteCollision = Output(Bool())
  val duplicateWriteCommit = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarGPRFile(
    val archRegs: Int = 24,
    val physRegs: Int = 128,
    val dataWidth: Int = 64,
    val readPorts: Int = 1,
    val writePorts: Int = 2)
    extends Module {
  require(archRegs == 24,
    "LinxCoreModel scalar GPR namespace has 24 architectural registers")
  require(physRegs > archRegs && (physRegs & (physRegs - 1)) == 0,
    "physical GPR capacity must be a power of two above the architectural namespace")
  require(dataWidth > 0, "GPR data width must be positive")
  require(readPorts > 0, "GPR read-port count must be positive")
  require(writePorts > 0, "GPR write-port count must be positive")

  private val tagWidth = log2Ceil(physRegs)
  val io = IO(new ScalarGPRFileIO(archRegs, physRegs, dataWidth, readPorts, writePorts))

  val data = RegInit(VecInit(Seq.fill(physRegs)(0.U(dataWidth.W))))
  val ready = RegInit(VecInit((0 until physRegs).map(idx => (idx < archRegs).B)))

  for (port <- 0 until readPorts) {
    io.readData(port) := data(io.readTag(port))
    io.readReady(port) := !io.readValid(port) || ready(io.readTag(port))
  }

  val blockedByHigher = Wire(Vec(writePorts, Bool()))
  val writeFire = Wire(Vec(writePorts, Bool()))
  for (port <- 0 until writePorts) {
    val higherSameTag =
      if (port == 0) false.B
      else VecInit((0 until port).map(higher =>
        io.write(higher).requestValid && (io.write(higher).tag === io.write(port).tag))).asUInt.orR
    blockedByHigher(port) := io.write(port).requestValid && higherSameTag
    io.write(port).ready := !higherSameTag
    writeFire(port) := io.write(port).requestValid && io.write(port).commit && !higherSameTag
    io.write(port).fire := writeFire(port)
    io.write(port).blockedByHigherSameTag := blockedByHigher(port)
  }

  val anyWriteFire = writeFire.asUInt.orR
  val clearWriteCollision = io.clearValid && VecInit((0 until writePorts).map(port =>
    writeFire(port) && (io.write(port).tag === io.clearTag))).asUInt.orR
  val duplicateWriteCommit =
    if (writePorts == 1) false.B
    else VecInit((0 until writePorts).flatMap(lhs =>
      (lhs + 1 until writePorts).map(rhs =>
        writeFire(lhs) && writeFire(rhs) && (io.write(lhs).tag === io.write(rhs).tag)))).asUInt.orR
  val commitWithoutRequest = VecInit((0 until writePorts).map(port =>
    io.write(port).commit && !io.write(port).requestValid)).asUInt.orR

  when(io.initValid) {
    data(io.initTag) := io.initData
    ready(io.initTag) := true.B
  }
  when(io.clearValid) {
    ready(io.clearTag) := false.B
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
  io.protocolError := clearWriteCollision || duplicateWriteCommit || commitWithoutRequest ||
    (io.initValid && anyWriteFire)

  assert(!io.protocolError,
    "scalar GPR state mutation requires unique committed write ownership")
}
