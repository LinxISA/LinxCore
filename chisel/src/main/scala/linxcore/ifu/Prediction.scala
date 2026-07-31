package linxcore.ifu

import chisel3._
import linxcore.frontend.BSideStage

/** Side-effect-free prediction helpers for the public IFU package. */
object Prediction {
  val ProviderRankWidth = 3

  /** Higher rank wins. Backend recovery is intentionally absent from this list. */
  def providerRank(stage: BSideStage.Type): UInt =
    Mux(
      stage === BSideStage.BF4,
      5.U(ProviderRankWidth.W),
      Mux(
        stage === BSideStage.BF3,
        4.U(ProviderRankWidth.W),
        Mux(
          stage === BSideStage.BF2,
          3.U(ProviderRankWidth.W),
          Mux(
            stage === BSideStage.BF1,
            2.U(ProviderRankWidth.W),
            Mux(stage === BSideStage.BF0, 1.U(ProviderRankWidth.W), 0.U(ProviderRankWidth.W))))))
}
