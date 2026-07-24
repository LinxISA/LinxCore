package linxcore.system

import chisel3._
import chisel3.util._

import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

class ReducedServiceRenameSnapshotIdentity(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val stid = UInt(p.threadIdWidth.W)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
}

class ReducedServiceRenameSnapshotIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val captureValid = Input(Bool())
  val captureIdentity = Input(new ReducedServiceRenameSnapshotIdentity(p))
  val capturePhysTags = Input(Vec(7, UInt(p.physRegWidth.W)))

  val lookupValid = Input(Bool())
  val lookupIdentity = Input(new ReducedServiceRenameSnapshotIdentity(p))
  val lookupMatch = Output(Bool())
  val lookupPhysTags = Output(Vec(7, UInt(p.physRegWidth.W)))

  val clearValid = Input(Bool())
  val clearIdentity = Input(new ReducedServiceRenameSnapshotIdentity(p))
  val flush = Input(Bool())

  val occupied = Output(Bool())
  val captureBlocked = Output(Bool())
  val lookupMismatch = Output(Bool())
}

class ReducedServiceRenameSnapshot(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedServiceRenameSnapshotIO(p))

  private val occupiedReg = RegInit(false.B)
  private val identityReg = Reg(new ReducedServiceRenameSnapshotIdentity(p))
  private val physTagsReg = Reg(Vec(7, UInt(p.physRegWidth.W)))

  private def robIdMatches(lhs: ROBID, rhs: ROBID): Bool = {
    lhs.valid && rhs.valid && ROBID.equal(lhs, rhs)
  }

  private def identityMatches(lhs: ReducedServiceRenameSnapshotIdentity, rhs: ReducedServiceRenameSnapshotIdentity): Bool = {
    (lhs.stid === rhs.stid) &&
      robIdMatches(lhs.bid, rhs.bid) &&
      robIdMatches(lhs.gid, rhs.gid) &&
      robIdMatches(lhs.rid, rhs.rid)
  }

  val lookupHit = occupiedReg && io.lookupValid && identityMatches(identityReg, io.lookupIdentity)
  val clearHit = occupiedReg && io.clearValid && identityMatches(identityReg, io.clearIdentity)
  val captureFire = io.captureValid && !occupiedReg && !io.flush

  io.lookupMatch := lookupHit
  io.lookupPhysTags := Mux(lookupHit, physTagsReg, 0.U.asTypeOf(Vec(7, UInt(p.physRegWidth.W))))
  io.occupied := occupiedReg
  io.captureBlocked := io.captureValid && occupiedReg && !io.flush
  io.lookupMismatch := io.lookupValid && occupiedReg && !lookupHit

  when(io.flush) {
    occupiedReg := false.B
  }.elsewhen(clearHit) {
    occupiedReg := false.B
  }.elsewhen(captureFire) {
    occupiedReg := true.B
    identityReg := io.captureIdentity
    physTagsReg := io.capturePhysTags
  }
}
