package linxcore.params

final case class OOOParams(
    stidCount: Int = 1,
    decodeWidth: Int = 4,
    renameWidth: Int = 4,
    dispatchWidth: Int = 4,
    d3PrefixWidth: Int = 4,
    retireWidth: Int = 4,
    robGroupsPerStid: Int = 64,
    maxInstructionsPerRobGroup: Int = 4,
    robBankCount: Int = 8,
    brobEntriesPerStid: Int = 256,
    gprArchRegs: Int = 24,
    gprPhysRegs: Int = 128,
    gprTagGenerationWidth: Int = 16,
    gprMapQDepthPerStid: Int = 256,
    tPhysRegs: Int = 32,
    uPhysRegs: Int = 32,
    localSeqGenerationWidth: Int = 16,
    tuMapQDepthPerStid: Int = 32) {
  def robCapacityPerStid: Int =
    robGroupsPerStid * maxInstructionsPerRobGroup
}
