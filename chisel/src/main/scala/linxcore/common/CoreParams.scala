package linxcore.common

final case class CoreParams(
  robEntries: Int = 128,
  commitWidth: Int = 4
) {
  require(robEntries > 1, "robEntries must be greater than one")
  require((robEntries & (robEntries - 1)) == 0, "robEntries must be a power of two")
  require(commitWidth > 0, "commitWidth must be positive")
}
