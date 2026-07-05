package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayRowMutationSourceMuxReference {
  final case class Request(
      valid: Boolean = false,
      targetMask: BigInt = 0,
      targetIndex: Int = 0,
      setWaitStatus: Boolean = false,
      keepRepickStatus: Boolean = false,
      clearReturnState: Boolean = false,
      lineWrite: Boolean = false,
      waitStoreWrite: Boolean = false,
      nextWaitStore: Boolean = false,
      nextDataComplete: Boolean = false,
      nextScbReturned: Boolean = false,
      nextStqReturned: Boolean = false,
      nextStoreSourceReturned: Boolean = false)

  final case class Result(
      out: Request,
      selectedSourceReturn: Boolean,
      selectedMdbWaitPlan: Boolean,
      conflict: Boolean)

  def apply(sourceReturn: Request, mdbWaitPlan: Request): Result = {
    val sameTarget =
      sourceReturn.valid &&
        mdbWaitPlan.valid &&
        sourceReturn.targetMask == mdbWaitPlan.targetMask &&
        sourceReturn.targetIndex == mdbWaitPlan.targetIndex
    val useSource = sourceReturn.valid
    val useMdb = mdbWaitPlan.valid && (!useSource || sameTarget)
    val selected =
      if (sameTarget) {
        sourceReturn.copy(
          valid = true,
          setWaitStatus = sourceReturn.setWaitStatus || mdbWaitPlan.setWaitStatus,
          keepRepickStatus = sourceReturn.keepRepickStatus && !mdbWaitPlan.setWaitStatus,
          clearReturnState = sourceReturn.clearReturnState || mdbWaitPlan.clearReturnState,
          lineWrite = sourceReturn.lineWrite || mdbWaitPlan.lineWrite,
          waitStoreWrite = sourceReturn.waitStoreWrite || mdbWaitPlan.waitStoreWrite,
          nextWaitStore =
            if (mdbWaitPlan.waitStoreWrite) mdbWaitPlan.nextWaitStore else sourceReturn.nextWaitStore,
          nextDataComplete =
            if (mdbWaitPlan.clearReturnState) mdbWaitPlan.nextDataComplete else sourceReturn.nextDataComplete,
          nextScbReturned =
            if (mdbWaitPlan.clearReturnState) mdbWaitPlan.nextScbReturned else sourceReturn.nextScbReturned,
          nextStqReturned =
            if (mdbWaitPlan.clearReturnState) mdbWaitPlan.nextStqReturned else sourceReturn.nextStqReturned,
          nextStoreSourceReturned =
            if (mdbWaitPlan.clearReturnState) {
              mdbWaitPlan.nextStoreSourceReturned
            } else {
              sourceReturn.nextStoreSourceReturned
            })
      } else if (useSource) sourceReturn.copy(valid = true)
      else if (useMdb) mdbWaitPlan.copy(valid = true)
      else Request()

    Result(
      out = selected,
      selectedSourceReturn = useSource,
      selectedMdbWaitPlan = useMdb,
      conflict = sourceReturn.valid && mdbWaitPlan.valid)
  }
}

class LoadReplayRowMutationSourceMuxSpec extends AnyFunSuite {
  import LoadReplayRowMutationSourceMuxReference._

  test("keeps source-return priority for different-target MDB wait-plan conflict") {
    val source = Request(
      valid = true,
      targetMask = 0x2,
      targetIndex = 1,
      clearReturnState = true,
      lineWrite = true)
    val mdb = Request(
      valid = true,
      targetMask = 0x4,
      targetIndex = 2,
      setWaitStatus = true,
      waitStoreWrite = true,
      nextWaitStore = true)
    val result = LoadReplayRowMutationSourceMuxReference(source, mdb)

    assert(result.selectedSourceReturn)
    assert(!result.selectedMdbWaitPlan)
    assert(result.conflict)
    assert(result.out.targetMask == 0x2)
    assert(result.out.targetIndex == 1)
    assert(result.out.clearReturnState)
    assert(!result.out.setWaitStatus)
  }

  test("coalesces same-target source-return and MDB wait-plan mutations") {
    val source = Request(
      valid = true,
      targetMask = 0x4,
      targetIndex = 2,
      keepRepickStatus = true,
      lineWrite = true,
      waitStoreWrite = true,
      nextDataComplete = true,
      nextScbReturned = true,
      nextStqReturned = true,
      nextStoreSourceReturned = true)
    val mdb = Request(
      valid = true,
      targetMask = 0x4,
      targetIndex = 2,
      setWaitStatus = true,
      clearReturnState = true,
      lineWrite = true,
      waitStoreWrite = true,
      nextWaitStore = true)
    val result = LoadReplayRowMutationSourceMuxReference(source, mdb)

    assert(result.selectedSourceReturn)
    assert(result.selectedMdbWaitPlan)
    assert(result.conflict)
    assert(result.out.targetMask == 0x4)
    assert(result.out.targetIndex == 2)
    assert(result.out.setWaitStatus)
    assert(!result.out.keepRepickStatus)
    assert(result.out.clearReturnState)
    assert(result.out.lineWrite)
    assert(result.out.waitStoreWrite)
    assert(result.out.nextWaitStore)
    assert(!result.out.nextDataComplete)
    assert(!result.out.nextScbReturned)
    assert(!result.out.nextStqReturned)
    assert(!result.out.nextStoreSourceReturned)
  }

  test("selects MDB wait-plan mutation when source-return has no request") {
    val source = Request(valid = false)
    val mdb = Request(
      valid = true,
      targetMask = 0x4,
      targetIndex = 2,
      setWaitStatus = true,
      waitStoreWrite = true,
      nextWaitStore = true)
    val result = LoadReplayRowMutationSourceMuxReference(source, mdb)

    assert(!result.selectedSourceReturn)
    assert(result.selectedMdbWaitPlan)
    assert(!result.conflict)
    assert(result.out.targetMask == 0x4)
    assert(result.out.targetIndex == 2)
    assert(result.out.setWaitStatus)
    assert(result.out.waitStoreWrite)
    assert(result.out.nextWaitStore)
  }

  test("elaborates the replay row-mutation source mux") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayRowMutationSourceMux(
      liqEntries = 4,
      idEntries = 16,
      sourceStoreEntries = 16,
      pcWidth = 64,
      lineBytes = 64
    ))

    assert(sv.contains("module LoadReplayRowMutationSourceMux"))
    assert(sv.contains("io_selectedSourceReturn"))
    assert(sv.contains("io_selectedMdbWaitPlan"))
  }
}
