package linxcore.top.interface

import chisel3._
import chisel3.util.log2Ceil
import linxcore.params.CoreParams

object InterfaceWidth {
  def index(entries: Int): Int = math.max(1, log2Ceil(entries))
}

/** Architectural parent identity, independent of ROB residency. */
class InstructionIdentity(val p: CoreParams) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.ooo.stidWidth.W)
  val instructionId = UInt(p.instructionIdWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

/** Complete identity of one resident logical uop member.
  *
  * Native BID and BROB generation intentionally remain separate domains.
  */
class RobIdentity(val p: CoreParams) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.ooo.stidWidth.W)
  val ridSlot = UInt(p.ooo.ridSlotWidth.W)
  val ridGeneration = UInt(p.ridGenerationWidth.W)
  val memberIndex = UInt(p.ooo.robMemberIndexWidth.W)
  val residentGeneration = UInt(p.residentGenerationWidth.W)
  val bid = UInt(p.nativeBidWidth.W)
  val brobGeneration = UInt(p.brobGenerationWidth.W)
}

/** Wrap-qualified identity for an asynchronous memory transaction. */
class MemoryTransactionIdentity(val p: CoreParams) extends Bundle {
  val value = UInt(p.memoryTransactionIdWidth.W)
  val generation = UInt(p.memoryTransactionGenerationWidth.W)
}

/** Exact identity retained across LSU queues, attempts, and return pipes. */
class MemoryIdentity(val p: CoreParams) extends Bundle {
  val rob = new RobIdentity(p)
  val transaction = new MemoryTransactionIdentity(p)
  val lsid = UInt(p.lsidWidth.W)
  val attemptGeneration = UInt(p.memoryAttemptGenerationWidth.W)
  val pipeId = UInt(InterfaceWidth.index(
    math.max(p.lsu.loadPipes, p.lsu.storePipes)).W)
}
