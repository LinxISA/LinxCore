package linxcore.execute

import chisel3._

import linxcore.common.InterfaceParams

class ReducedScalarRegisterFileIO(
    val p: InterfaceParams = InterfaceParams(),
    val archRegs: Int = 24,
    val physRegs: Int = 64)
    extends Bundle {
  val readValid = Input(Vec(3, Bool()))
  val readTags = Input(Vec(3, UInt(p.physRegWidth.W)))
  val readData = Output(Vec(3, UInt(p.immWidth.W)))
  val readReady = Output(Vec(3, Bool()))
  val allReadReady = Output(Bool())
  val auxReadValid = Input(Vec(3, Bool()))
  val auxReadTags = Input(Vec(3, UInt(p.physRegWidth.W)))
  val auxReadData = Output(Vec(3, UInt(p.immWidth.W)))
  val auxReadReady = Output(Vec(3, Bool()))

  val initValid = Input(Bool())
  val initArchTag = Input(UInt(p.archRegWidth.W))
  val initData = Input(UInt(p.immWidth.W))

  val clearValid = Input(Bool())
  val clearTag = Input(UInt(p.physRegWidth.W))

  val writeValid = Input(Bool())
  val writeTag = Input(UInt(p.physRegWidth.W))
  val writeData = Input(UInt(p.immWidth.W))

  val readyMask = Output(UInt(physRegs.W))
  val stateError = Output(Bool())
}

class ReducedScalarRegisterFile(
    val p: InterfaceParams = InterfaceParams(),
    val archRegs: Int = 24,
    val physRegs: Int = 64)
    extends Module {
  require(archRegs == 24, "LinxCoreModel scalar GPR namespace has 24 architectural registers")
  require(physRegs == (1 << p.physRegWidth), "reduced RF expects the bring-up physical tag width to address every entry")
  require(archRegs <= physRegs, "architectural identity tags must fit in the physical RF")

  val io = IO(new ReducedScalarRegisterFileIO(p, archRegs, physRegs))
  val gpr = Module(new ScalarGPRFile(
    archRegs = archRegs,
    physRegs = physRegs,
    dataWidth = p.immWidth,
    readPorts = 6,
    writePorts = 1
  ))

  val initTagInRange = io.initArchTag < archRegs.U
  val initPhysTag = io.initArchTag.pad(p.physRegWidth)(p.physRegWidth - 1, 0)

  for (idx <- 0 until 3) {
    gpr.io.readValid(idx) := io.readValid(idx)
    gpr.io.readTag(idx) := io.readTags(idx)
    io.readData(idx) := gpr.io.readData(idx)
    io.readReady(idx) := gpr.io.readReady(idx)
    gpr.io.readValid(idx + 3) := io.auxReadValid(idx)
    gpr.io.readTag(idx + 3) := io.auxReadTags(idx)
    io.auxReadData(idx) := gpr.io.readData(idx + 3)
    io.auxReadReady(idx) := gpr.io.readReady(idx + 3)
  }

  io.allReadReady := io.readReady.reduce(_ && _)
  io.readyMask := gpr.io.readyMask
  io.stateError := (io.initValid && !initTagInRange) || gpr.io.protocolError

  gpr.io.initValid := io.initValid && initTagInRange
  gpr.io.initTag := initPhysTag
  gpr.io.initData := io.initData
  gpr.io.clearValid := io.clearValid
  gpr.io.clearTag := io.clearTag
  gpr.io.write(0).requestValid := io.writeValid
  gpr.io.write(0).commit := io.writeValid
  gpr.io.write(0).tag := io.writeTag
  gpr.io.write(0).data := io.writeData
}
