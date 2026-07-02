package linxcore.top

import linxcore.common.CoreParams

class LinxCoreFrontendFetchRfAluReducedStoreTraceTop(
    coreParams: CoreParams = CoreParams(),
    decRenQueueDepth: Int = 4,
    issueQueueDepth: Int = 4,
    denseSlotQueueDepth: Int = 8,
    storeDispatchQueueDepth: Int = 4,
    storeExecBufferEntries: Int = 4,
    mapQDepth: Int = 32,
    gprMapQDepth: Int = 32,
    archRegs: Int = 24,
    physRegs: Int = 64)
    extends LinxCoreFrontendFetchRfAluTraceTop(
      coreParams = coreParams,
      decRenQueueDepth = decRenQueueDepth,
      issueQueueDepth = issueQueueDepth,
      denseSlotQueueDepth = denseSlotQueueDepth,
      storeDispatchQueueDepth = storeDispatchQueueDepth,
      storeExecBufferEntries = storeExecBufferEntries,
      mapQDepth = mapQDepth,
      gprMapQDepth = gprMapQDepth,
      archRegs = archRegs,
      physRegs = physRegs,
      useReducedStoreDispatchStq = true)

object EmitLinxCoreFrontendFetchRfAluReducedStoreTraceTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendFetchRfAluReducedStoreTraceTop(
      CoreParams(robEntries = 8, commitWidth = 2),
      mapQDepth = 32,
      gprMapQDepth = 256,
      physRegs = 128),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-rf-alu-reduced-store-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
