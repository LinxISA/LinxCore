package linxcore.rename

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.{DestinationKind, DispatchTarget, InterfaceParams, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

object ScalarDecodeRenameBridgeReference {
  final case class Operand(valid: Boolean, cls: String, tag: Int)
  final case class Destination(valid: Boolean, kind: String, tag: Int)
  final case class Decision(
      accepted: Boolean,
      unsupportedSrcMask: Int,
      unsupportedDst: Boolean,
      unsupportedOperandClass: Boolean,
      blockedByMaintenance: Boolean,
      blockedByRename: Boolean,
      blockedByRob: Boolean,
      blockedByOutput: Boolean)

  private val ScalarArchRegs = 24

  def decide(
      valid: Boolean,
      src: Seq[Operand],
      dst: Destination,
      renameReady: Boolean,
      robReady: Boolean,
      outReady: Boolean,
      maintenanceBusy: Boolean): Decision = {
    require(src.length == 3)

    val unsupportedSrcBits = src.zipWithIndex.foldLeft(0) { case (mask, (op, idx)) =>
      if (op.valid && op.cls == "P" && op.tag >= ScalarArchRegs) mask | (1 << idx) else mask
    }
    val unsupportedDst = dst.valid && dst.kind == "Gpr" && dst.tag >= ScalarArchRegs
    val unsupportedOperandClass =
      src.exists(op => op.valid && op.cls != "P") ||
        (dst.valid && dst.kind != "Gpr")
    val unsupported = unsupportedSrcBits != 0 || unsupportedDst || unsupportedOperandClass
    val needsRename = dst.valid && dst.kind == "Gpr" && dst.tag < ScalarArchRegs
    val canRename = !needsRename || renameReady
    val accepted = valid && !maintenanceBusy && !unsupported && canRename && robReady && outReady

    Decision(
      accepted = accepted,
      unsupportedSrcMask = unsupportedSrcBits,
      unsupportedDst = unsupportedDst,
      unsupportedOperandClass = unsupportedOperandClass,
      blockedByMaintenance = valid && maintenanceBusy,
      blockedByRename = valid && !maintenanceBusy && !unsupported && needsRename && !renameReady,
      blockedByRob = valid && !maintenanceBusy && !unsupported && canRename && !robReady,
      blockedByOutput = valid && !maintenanceBusy && !unsupported && canRename && robReady && !outReady
    )
  }

  def initialPhysForArch(tag: Int): Int = {
    require(tag >= 0 && tag < ScalarArchRegs)
    tag
  }

  def firstAllocatedPhys: Int = ScalarArchRegs
}

class ScalarDecodeRenameBridgeSpec extends AnyFunSuite {
  import ScalarDecodeRenameBridgeReference._

  test("reference accepts one scalar GPR uop only when rename, ROB, and output are ready") {
    val src = Seq(Operand(valid = true, "P", 1), Operand(valid = true, "P", 2), Operand(valid = false, "P", 0))
    val dst = Destination(valid = true, "Gpr", 3)

    assert(decide(valid = true, src, dst, renameReady = true, robReady = true, outReady = true, maintenanceBusy = false).accepted)
    assert(decide(valid = true, src, dst, renameReady = false, robReady = true, outReady = true, maintenanceBusy = false).blockedByRename)
    assert(decide(valid = true, src, dst, renameReady = true, robReady = false, outReady = true, maintenanceBusy = false).blockedByRob)
    assert(decide(valid = true, src, dst, renameReady = true, robReady = true, outReady = false, maintenanceBusy = false).blockedByOutput)
    assert(decide(valid = true, src, dst, renameReady = true, robReady = true, outReady = true, maintenanceBusy = true).blockedByMaintenance)
  }

  test("reference rejects reg6 aliases outside the scalar GPR owner") {
    val srcAlias = Seq(Operand(valid = true, "P", 24), Operand(valid = true, "P", 2), Operand(valid = false, "P", 0))
    val dstAlias = Destination(valid = true, "Gpr", 31)
    val srcBadClass = Seq(Operand(valid = true, "T", 1), Operand(valid = false, "P", 0), Operand(valid = false, "P", 0))
    val dst = Destination(valid = true, "Gpr", 3)

    val srcDecision = decide(true, srcAlias, dst, renameReady = true, robReady = true, outReady = true, maintenanceBusy = false)
    assert(!srcDecision.accepted)
    assert(srcDecision.unsupportedSrcMask == 0x1)

    val dstDecision = decide(true, srcAlias.updated(0, Operand(valid = true, "P", 1)), dstAlias, renameReady = true, robReady = true, outReady = true, maintenanceBusy = false)
    assert(!dstDecision.accepted)
    assert(dstDecision.unsupportedDst)

    val classDecision = decide(true, srcBadClass, dst, renameReady = true, robReady = true, outReady = true, maintenanceBusy = false)
    assert(!classDecision.accepted)
    assert(classDecision.unsupportedOperandClass)
  }

  test("reference mirrors initial scalar GPR map and first free physical tag") {
    assert(initialPhysForArch(0) == 0)
    assert(initialPhysForArch(23) == 23)
    assert(firstAllocatedPhys == 24)
  }

  test("IO exposes decode-to-rename, ROB allocation, and cleanup handoff signals") {
    val p = InterfaceParams()
    val trace = CommitTraceParams(robValueWidth = p.robIndexWidth)
    val io = new ScalarDecodeRenameBridgeIO(p, trace)

    assert(io.out.valid.getWidth == 1)
    assert(io.in.peId.getWidth == 8)
    assert(io.out.peId.getWidth == 8)
    assert(io.out.threadId.getWidth == 8)
    assert(io.robAllocAttemptValid.getWidth == 1)
    assert(io.robAllocRow.identity.bid.getWidth == 32)
    assert(io.robAllocRow.pc.getWidth == 64)
    assert(io.commitBlockBid.getWidth == 64)
    assert(io.unsupportedSrcMask.getWidth == 3)
    assert(io.srcPhysTags.length == 3)
    assert(io.dstPhysTag.getWidth == 6)
    assert(io.gprFreeCount.getWidth == 7)
    assert(io.gprMapQFreeCount.getWidth == 6)
  }

  test("ScalarDecodeRenameBridge elaborates through GPRRenameCheckpoint") {
    val p = InterfaceParams(robEntries = 8)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarDecodeRenameBridge(p = p, traceParams = trace, mapQDepth = 8)
    )

    assert(sv.contains("module ScalarDecodeRenameBridge"))
    assert(sv.contains("GPRRenameCheckpoint"))
    assert(sv.contains("io_robAllocAttemptValid"))
    assert(sv.contains("io_robAllocValid"))
    assert(sv.contains("io_out_peId"))
    assert(sv.contains("io_unsupportedDst"))
    assert(sv.contains("io_blockedByRename"))
    assert(sv.contains("io_out_dispatchTarget"))
  }

  test("enum values used by the bridge stay stable for scalar payloads") {
    assert(OperandClass.P.asUInt.litValue == 1)
    assert(DestinationKind.Gpr.asUInt.litValue == 1)
    assert(DispatchTarget.Alu.asUInt.litValue == 2)
    assert(DispatchTarget.Lsu.asUInt.litValue == 4)
  }
}
