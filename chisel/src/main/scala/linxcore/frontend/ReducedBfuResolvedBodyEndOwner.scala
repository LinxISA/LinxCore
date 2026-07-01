package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuResolvedBodyEndOwnerIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val headerActive = Input(Bool())
  val activeHeaderPc = Input(UInt(p.pcWidth.W))
  val resolvedValid = Input(Bool())
  val resolvedHeaderPc = Input(UInt(p.pcWidth.W))
  val resolvedHSizeBytes = Input(UInt(p.pcWidth.W))
  val resolvedBodyEndPc = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val geometryHeaderPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
  val bodyEndPc = Output(UInt(p.pcWidth.W))

  val accepted = Output(Bool())
  val headerMismatch = Output(Bool())
  val inactiveDrop = Output(Bool())
  val flushDrop = Output(Bool())
  val bodyEndUnderflow = Output(Bool())
}

class ReducedBfuResolvedBodyEndOwner(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuResolvedBodyEndOwnerIO(p))

  val bodyBasePc = (io.activeHeaderPc + 2.U)(p.pcWidth - 1, 0)
  val headerMatch = io.resolvedHeaderPc === io.activeHeaderPc
  val accepted = io.resolvedValid && !io.flushValid && io.headerActive && headerMatch
  val bsize = Mux(io.resolvedBodyEndPc > bodyBasePc, (io.resolvedBodyEndPc - bodyBasePc)(p.pcWidth - 1, 0), 0.U)

  io.geometryValid := accepted
  io.geometryHeaderPc := io.activeHeaderPc
  io.hsizeBytes := Mux(accepted, io.resolvedHSizeBytes, 0.U)
  io.bsizeBytes := Mux(accepted, bsize, 0.U)
  io.bodyEndPc := Mux(accepted, io.resolvedBodyEndPc, 0.U)

  io.accepted := accepted
  io.headerMismatch := io.resolvedValid && !io.flushValid && io.headerActive && !headerMatch
  io.inactiveDrop := io.resolvedValid && !io.flushValid && !io.headerActive
  io.flushDrop := io.resolvedValid && io.flushValid
  io.bodyEndUnderflow := accepted && io.resolvedBodyEndPc <= bodyBasePc
}
