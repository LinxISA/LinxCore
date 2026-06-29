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

  val data = RegInit(VecInit(Seq.fill(physRegs)(0.U(p.immWidth.W))))
  val ready = RegInit(VecInit((0 until physRegs).map(idx => (idx < archRegs).B)))

  val initTagInRange = io.initArchTag < archRegs.U
  val clearTagInRange = io.clearTag < physRegs.U
  val writeTagInRange = io.writeTag < physRegs.U

  for (idx <- 0 until 3) {
    io.readData(idx) := data(io.readTags(idx))
    io.readReady(idx) := !io.readValid(idx) || ready(io.readTags(idx))
  }

  io.allReadReady := io.readReady.reduce(_ && _)
  io.readyMask := ready.asUInt
  io.stateError :=
    (io.initValid && !initTagInRange) ||
      (io.clearValid && !clearTagInRange) ||
      (io.writeValid && !writeTagInRange)

  when(io.initValid && initTagInRange) {
    data(io.initArchTag) := io.initData
    ready(io.initArchTag) := true.B
  }

  when(io.clearValid && clearTagInRange) {
    ready(io.clearTag) := false.B
  }

  when(io.writeValid && writeTagInRange) {
    data(io.writeTag) := io.writeData
    ready(io.writeTag) := true.B
  }
}
