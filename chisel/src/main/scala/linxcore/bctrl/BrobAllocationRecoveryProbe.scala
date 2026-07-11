package linxcore.bctrl

import chisel3._

class BrobAllocationRecoveryProbeIO extends Bundle {
  val advanceValid = Input(Bool())
  val advanceStid = Input(UInt(2.W))
  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(2.W))
  val recoveryPivotBid = Input(UInt(16.W))
  val recoveryInclusive = Input(Bool())
  val queryStid = Input(UInt(2.W))
  val admissionAllocValid = Input(Bool())
  val admissionUsesExistingBlock = Input(Bool())
  val admissionStidInRange = Input(Bool())
  val admissionBrobReady = Input(Bool())
  val admissionRobReady = Input(Bool())
  val admissionRecoveryValid = Input(Bool())

  val nextBid = Output(UInt(16.W))
  val queryInRange = Output(Bool())
  val advanceInRange = Output(Bool())
  val recoveryInRange = Output(Bool())
  val recoveryFirstKilledBid = Output(UInt(16.W))
  val recoveryOldAllocBid = Output(UInt(16.W))
  val recoveryApplied = Output(Bool())
  val cursor0 = Output(UInt(16.W))
  val cursor1 = Output(UInt(16.W))
  val admissionAllocReady = Output(Bool())
  val admissionAllocFire = Output(Bool())
  val admissionRobAllocValid = Output(Bool())
  val admissionBrobAllocValid = Output(Bool())
}

class BrobAllocationRecoveryProbe extends Module {
  val io = IO(new BrobAllocationRecoveryProbeIO)
  val owner = Module(new BrobAllocationRecovery(
    bidWidth = 16,
    stidWidth = 2,
    stidCount = 2
  ))

  owner.io.advanceValid := io.advanceValid
  owner.io.advanceStid := io.advanceStid
  owner.io.recoveryValid := io.recoveryValid
  owner.io.recoveryStid := io.recoveryStid
  owner.io.recoveryPivotBid := io.recoveryPivotBid
  owner.io.recoveryInclusive := io.recoveryInclusive
  owner.io.queryStid := io.queryStid

  io.nextBid := owner.io.nextBid
  io.queryInRange := owner.io.queryInRange
  io.advanceInRange := owner.io.advanceInRange
  io.recoveryInRange := owner.io.recoveryInRange
  io.recoveryFirstKilledBid := owner.io.recoveryFirstKilledBid
  io.recoveryOldAllocBid := owner.io.recoveryOldAllocBid
  io.recoveryApplied := owner.io.recoveryApplied
  io.cursor0 := owner.io.cursor(0)
  io.cursor1 := owner.io.cursor(1)

  val admission = Module(new BrobRobAllocationAdmission)
  admission.io.allocValid := io.admissionAllocValid
  admission.io.usesExistingBlock := io.admissionUsesExistingBlock
  admission.io.stidInRange := io.admissionStidInRange
  admission.io.brobReady := io.admissionBrobReady
  admission.io.robReady := io.admissionRobReady
  admission.io.recoveryValid := io.admissionRecoveryValid
  io.admissionAllocReady := admission.io.allocReady
  io.admissionAllocFire := admission.io.allocFire
  io.admissionRobAllocValid := admission.io.robAllocValid
  io.admissionBrobAllocValid := admission.io.brobAllocValid
}

object EmitBrobAllocationRecoveryProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new BrobAllocationRecoveryProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/brob-allocation-recovery-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
