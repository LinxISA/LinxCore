package linxcore.params

import chisel3.util.log2Ceil

final case class OOOParams(
    stidCount: Int = 1,
    stidIdentityEntries: Int = 2,
    decodeWidth: Int = 4,
    renameWidth: Int = 4,
    dispatchWidth: Int = 4,
    d3PrefixWidth: Int = 4,
    retireWidth: Int = 4,
    storeCommitBufferEntries: Int = 64,
    robGroupsPerStid: Int = 64,
    robIdentityGroupsPerStid: Int = 64,
    maxInstructionsPerRobGroup: Int = 4,
    robIdentityMembersPerGroup: Int = 4,
    maxUopsPerInstruction: Int = 32,
    uopIdentityEntriesPerInstruction: Int = 32,
    robBankCount: Int = 8,
    brobEntriesPerStid: Int = 256,
    brobIdentityEntriesPerStid: Int = 256,
    pcBufferEntries: Int = 64,
    pcBankCount: Int = 4,
    pcRecoveryScanGroupsPerCycle: Int = 4,
    pcOffsetWidth: Int = 7,
    pcWritePorts: Int = 3,
    pcReadPorts: Int = 6,
    pcReadReplicaCount: Int = 3,
    pcAllocationEpochWidth: Int = 16,
    gprArchRegs: Int = 24,
    gprPhysRegs: Int = 128,
    gprTagIdentityEntries: Int = 128,
    gprTagGenerationWidth: Int = 16,
    gprMapQDepthPerStid: Int = 256,
    tPhysRegs: Int = 32,
    tTagIdentityEntries: Int = 32,
    uPhysRegs: Int = 32,
    uTagIdentityEntries: Int = 32,
    localSeqGenerationWidth: Int = 16,
    tuMapQDepthPerStid: Int = 32) {
  def robCapacityPerStid: Int =
    robGroupsPerStid * maxInstructionsPerRobGroup
  def stidWidth: Int = math.max(1, log2Ceil(stidIdentityEntries))
  def ridSlotWidth: Int = math.max(1, log2Ceil(robIdentityGroupsPerStid))
  def robMemberIndexWidth: Int = math.max(1,
    log2Ceil(robIdentityMembersPerGroup))
  def recipeUopIndexWidth: Int = math.max(1,
    log2Ceil(uopIdentityEntriesPerInstruction))
  def recipeUopCountWidth: Int = math.max(1,
    log2Ceil(uopIdentityEntriesPerInstruction + 1))
  def nativeBidWidth: Int = math.max(1,
    log2Ceil(brobIdentityEntriesPerStid))
  def gprTagWidth: Int = math.max(1, log2Ceil(gprTagIdentityEntries))
  def tTagWidth: Int = math.max(1, log2Ceil(tTagIdentityEntries))
  def uTagWidth: Int = math.max(1, log2Ceil(uTagIdentityEntries))
}
