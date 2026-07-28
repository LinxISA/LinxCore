package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, PriorityEncoder, log2Ceil}

class OooHierarchicalFreeSlotSelectIO(val entries: Int) extends Bundle {
  val available = Input(UInt(entries.W))
  val selectedValid = Output(Bool())
  val selectedIndex = Output(UInt(math.max(1, log2Ceil(entries)).W))
}

/** Bounded two-level first-free selector for one physical IQ bank.
  *
  * The lowest nonempty group wins, followed by the lowest free entry inside
  * that group. This preserves the functional first-free contract without one
  * priority chain spanning the complete bank depth.
  */
class OooHierarchicalFreeSlotSelect(
    val entries: Int,
    val groupEntries: Int) extends Module {
  require(entries > 0 && (entries & (entries - 1)) == 0,
    "hierarchical selector entries must be a positive power of two")
  require(groupEntries > 0 && (groupEntries & (groupEntries - 1)) == 0 &&
    groupEntries <= entries && entries % groupEntries == 0,
    "hierarchical selector group size must be a power-of-two divisor of entries")

  val io = IO(new OooHierarchicalFreeSlotSelectIO(entries))

  private val groupCount = entries / groupEntries
  private val groupIndexWidth = math.max(1, log2Ceil(groupCount))
  private val localIndexWidth = math.max(1, log2Ceil(groupEntries))

  val groupHasFree = Wire(Vec(groupCount, Bool()))
  val groupLocalIndex = Wire(Vec(groupCount, UInt(localIndexWidth.W)))
  for (group <- 0 until groupCount) {
    val upper = (group + 1) * groupEntries - 1
    val lower = group * groupEntries
    val localAvailable = io.available(upper, lower)
    groupHasFree(group) := localAvailable.orR
    groupLocalIndex(group) :=
      (if (groupEntries == 1) 0.U else PriorityEncoder(localAvailable))
  }

  val selectedGroup = Wire(UInt(groupIndexWidth.W))
  selectedGroup :=
    (if (groupCount == 1) 0.U else PriorityEncoder(groupHasFree.asUInt))
  val selectedLocal =
    if (groupCount == 1) groupLocalIndex.head
    else groupLocalIndex(selectedGroup)

  io.selectedValid := groupHasFree.asUInt.orR
  io.selectedIndex :=
    (if (groupCount == 1) selectedLocal
     else if (groupEntries == 1) selectedGroup
     else Cat(selectedGroup, selectedLocal))
}
