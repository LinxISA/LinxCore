package linxcore.params

object ParamProfiles {
  private def uniform(width: Int): CoreParams = {
    val widths = WidthParams.uniform(width)
    CoreParams(
      widths = widths,
      ifu = IFUParams(fetchWidth = width, ctuTransferWidth = width),
      ctu = CTUParams(inputWidth = width, outputWidth = width),
      ooo = OOOParams(
        decodeWidth = width,
        renameWidth = width,
        dispatchWidth = width,
        d3PrefixWidth = width,
        retireWidth = width),
      iex = IEXParams(issueWidth = width),
      dtu = DTUParams(traceWidth = width))
  }

  val W2: CoreParams = uniform(2)
  val W4: CoreParams = uniform(4)
  val W6: CoreParams = uniform(6)
  val W8: CoreParams = uniform(8)
  val Default: CoreParams = W4

  def forWidth(width: Int): CoreParams = width match {
    case 2 => W2
    case 4 => W4
    case 6 => W6
    case 8 => W8
    case _ =>
      throw new IllegalArgumentException(
        s"unsupported width $width; supported widths are 2, 4, 6, and 8")
  }
}
