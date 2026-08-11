package linxcore.params

/** Memory attributes owned by the natural simulation platform, not the core. */
object NaturalPlatformParams {
  private val pageMask = BigInt("fffffffffffff000", 16)
  private val normalMemoryAttributes = MemoryAccessAttributes(
    readable = true, writable = true, cacheable = false, device = false)
  private val deviceAttributes = MemoryAccessAttributes(
    readable = true, writable = true, cacheable = false, device = true)

  val physicalMemoryRegions: Seq[PhysicalMemoryRegion] = Seq(
    PhysicalMemoryRegion(
      base = BigInt("10000000", 16),
      mask = pageMask,
      attributes = deviceAttributes),
    PhysicalMemoryRegion(
      base = BigInt("10009000", 16),
      mask = pageMask,
      attributes = deviceAttributes))

  def apply(base: CoreParams): CoreParams =
    base.copy(lsu = base.lsu.copy(
      defaultMemoryAttributes = normalMemoryAttributes,
      physicalMemoryRegions = physicalMemoryRegions))
}
