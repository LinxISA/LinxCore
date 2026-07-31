package linxcore.params

final case class WidthParams(
    fetchWidth: Int = 4,
    ctuOutputWidth: Int = 4,
    decodeWidth: Int = 4,
    renameWidth: Int = 4,
    dispatchWidth: Int = 4,
    issueWidth: Int = 4,
    retireWidth: Int = 4)

object WidthParams {
  def uniform(width: Int): WidthParams =
    WidthParams(
      fetchWidth = width,
      ctuOutputWidth = width,
      decodeWidth = width,
      renameWidth = width,
      dispatchWidth = width,
      issueWidth = width,
      retireWidth = width)
}
