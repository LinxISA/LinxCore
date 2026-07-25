package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

class ReducedTemplateSnapshotTableIO(
    val entries: Int,
    val archRegs: Int,
    val physRegWidth: Int)
    extends Bundle {
  private val indexWidth = log2Ceil(entries)

  val flush = Input(Bool())
  val writeValid = Input(Bool())
  val writeRid = Input(UInt(indexWidth.W))
  val writeMap = Input(Vec(archRegs, UInt(physRegWidth.W)))

  val captureRid = Input(UInt(indexWidth.W))
  val captureValid = Output(Bool())
  val captureMap = Output(Vec(archRegs, UInt(physRegWidth.W)))

  val restoreRid = Input(UInt(indexWidth.W))
  val restoreValid = Output(Bool())
  val restoreMap = Output(Vec(archRegs, UInt(physRegWidth.W)))
}

class ReducedTemplateSnapshotTable(
    val entries: Int = 32,
    val archRegs: Int = 24,
    val physRegWidth: Int = 6)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "template snapshot table follows power-of-two ROB indexing")
  require(archRegs == 24, "Linx scalar template snapshot contains 24 GPR mappings")

  val io = IO(new ReducedTemplateSnapshotTableIO(entries, archRegs, physRegWidth))
  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val maps = Reg(Vec(entries, Vec(archRegs, UInt(physRegWidth.W))))

  when(io.flush) {
    valid := VecInit(Seq.fill(entries)(false.B))
  }.elsewhen(io.writeValid) {
    valid(io.writeRid) := true.B
    maps(io.writeRid) := io.writeMap
  }

  io.captureValid := valid(io.captureRid)
  io.captureMap := maps(io.captureRid)
  io.restoreValid := valid(io.restoreRid)
  io.restoreMap := maps(io.restoreRid)
}
