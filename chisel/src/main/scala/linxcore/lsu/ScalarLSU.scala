package linxcore.lsu

import chisel3._

import linxcore.common.{CoreParams, ScalarLsuParams}

class ScalarLSUIO(val coreParams: CoreParams, val lsuParams: ScalarLsuParams) extends Bundle {
  val store = ScalarLSU.storePathIO(coreParams, lsuParams)
  val load = new ScalarLSULoadPathIO(coreParams, lsuParams)
}

class ScalarLSU(val coreParams: CoreParams = CoreParams()) extends Module {
  private val lsuParams = coreParams.scalarLsu
  val io = IO(new ScalarLSUIO(coreParams, lsuParams))

  val storeCommitPath = Module(ScalarLSU.storeCommitPath(coreParams, lsuParams))
  val loadPath = Module(new ScalarLSULoadPath(coreParams))
  storeCommitPath.io <> io.store
  loadPath.io <> io.load
}

object ScalarLSU {
  def storePathIO(coreParams: CoreParams, lsuParams: ScalarLsuParams): STQSCBCommitPathIO =
    new STQSCBCommitPathIO(
      entries = lsuParams.stqEntries,
      queueEntries = lsuParams.commitQueueEntries,
      issueWidth = lsuParams.commitIssueWidth,
      scbEntries = lsuParams.scbEntries,
      scbResponseBufferDepth = lsuParams.scbResponseBufferDepth,
      addrWidth = lsuParams.addrWidth,
      dataWidth = lsuParams.dataWidth,
      peIdWidth = lsuParams.peIdWidth,
      stidWidth = lsuParams.stidWidth,
      tidWidth = lsuParams.tidWidth,
      sizeWidth = lsuParams.sizeWidth,
      simtLaneWidth = lsuParams.simtLaneWidth,
      lineBytes = lsuParams.lineBytes,
      mapQDepth = lsuParams.mapQDepth,
      robEntries = coreParams.robEntries
    )

  def storeCommitPath(coreParams: CoreParams, lsuParams: ScalarLsuParams): STQSCBCommitPath =
    new STQSCBCommitPath(
      entries = lsuParams.stqEntries,
      queueEntries = lsuParams.commitQueueEntries,
      issueWidth = lsuParams.commitIssueWidth,
      scbEntries = lsuParams.scbEntries,
      scbResponseBufferDepth = lsuParams.scbResponseBufferDepth,
      addrWidth = lsuParams.addrWidth,
      dataWidth = lsuParams.dataWidth,
      peIdWidth = lsuParams.peIdWidth,
      stidWidth = lsuParams.stidWidth,
      tidWidth = lsuParams.tidWidth,
      sizeWidth = lsuParams.sizeWidth,
      simtLaneWidth = lsuParams.simtLaneWidth,
      lineBytes = lsuParams.lineBytes,
      mapQDepth = lsuParams.mapQDepth,
      robEntries = coreParams.robEntries
    )
}
