package linxcore.ifu

import linxcore.common.InterfaceParams
import linxcore.frontend.BSidePredictionPipeline

/** Public B-SIDE prediction owner boundary.
  *
  * This module intentionally reuses the existing prediction pipeline as its
  * implementation body so BTB/TAGE/BIM/RAS/loop/history state has one owner.
  */
class BSide(
    p: InterfaceParams = InterfaceParams(),
    lineBytes: Int = 64,
    threadCount: Int = 1,
    boundaryEntries: Int = 16,
    responseEntries: Int = 16,
    trainingEntries: Int = 8,
    historyEntries: Int = 16,
    nanoEntries: Int = 8,
    ubtbEntries: Int = 16,
    pbtbEntries: Int = 32,
    bimEntries: Int = 64,
    tageEntries: Int = 32,
    ibtbEntries: Int = 16,
    loopEntries: Int = 16,
    rasDepth: Int = 8)
    extends BSidePredictionPipeline(
      p = p,
      lineBytes = lineBytes,
      threadCount = threadCount,
      boundaryEntries = boundaryEntries,
      responseEntries = responseEntries,
      trainingEntries = trainingEntries,
      historyEntries = historyEntries,
      nanoEntries = nanoEntries,
      ubtbEntries = ubtbEntries,
      pbtbEntries = pbtbEntries,
      bimEntries = bimEntries,
      tageEntries = tageEntries,
      ibtbEntries = ibtbEntries,
      loopEntries = loopEntries,
      rasDepth = rasDepth)
