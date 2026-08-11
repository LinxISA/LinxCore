package linxcore.bctrl

import chisel3._
import chisel3.util._

object SharedTileWriteStatus {
  val width = 4
  val Applied = 0.U(width.W)
  val Noop = 1.U(width.W)
  val InvalidSize = 2.U(width.W)
  val InvalidDescriptor = 3.U(width.W)
  val PayloadSizeMismatch = 4.U(width.W)
  val AllocationExpansion = 5.U(width.W)
  val DescriptorMismatch = 6.U(width.W)
  val WaitingForPayload = 7.U(width.W)
}

class SharedTileDescriptor extends Bundle {
  val dtype = UInt(5.W)
  val validCols = UInt(16.W)
  val validRows = UInt(16.W)
  val cols = UInt(16.W)
  val rows = UInt(16.W)
}

class SharedTileWriteRequest extends Bundle {
  val sharedId = UInt(8.W)
  val peMask = UInt(4.W)
  val sizeCode = UInt(3.W)
  val descriptors = Vec(4, new SharedTileDescriptor)
  val payloadBytes = Vec(4, UInt(14.W))
  val payloadReadyMask = UInt(4.W)
}

class SharedTileCommit extends Bundle {
  val sharedId = UInt(8.W)
  val peMask = UInt(4.W)
  val perPeCapacity = UInt(14.W)
  val allocatedBytes = UInt(16.W)
  val allocationMask = UInt(4.W)
  val initializedMask = UInt(4.W)
}

class SharedTileVersion extends Bundle {
  val allocationMask = UInt(4.W)
  val initializedMask = UInt(4.W)
  val perPeCapacity = UInt(14.W)
  val allocatedBytes = UInt(16.W)
  val dtype = UInt(5.W)
}

class SharedTileStateIO extends Bundle {
  val write = Flipped(Decoupled(new SharedTileWriteRequest))
  val status = Output(UInt(SharedTileWriteStatus.width.W))
  val commit = Valid(new SharedTileCommit)

  val readSharedId = Input(UInt(8.W))
  val readPeId = Input(UInt(2.W))
  val readInitialized = Output(Bool())
  val readDescriptor = Output(new SharedTileDescriptor)
  val version = Output(new SharedTileVersion)
}

/**
  * Core-private owner for the 256 architectural Shared Tile identities.
  *
  * This module owns allocation and descriptor metadata. Payload banks consume
  * the single-cycle `commit` pulse and must update every selected lane on that
  * same edge. The owner withholds that pulse until all selected payload lanes
  * are ready, so metadata and payload cannot become partially visible.
  */
class SharedTileState extends Module {
  val io = IO(new SharedTileStateIO)

  private val allocationMasks = RegInit(VecInit(Seq.fill(256)(0.U(4.W))))
  private val initializedMasks = RegInit(VecInit(Seq.fill(256)(0.U(4.W))))
  private val capacities = RegInit(VecInit(Seq.fill(256)(0.U(14.W))))
  private val allocatedBytes = RegInit(VecInit(Seq.fill(256)(0.U(16.W))))
  private val dtypes = RegInit(VecInit(Seq.fill(256)(0.U(5.W))))
  private val descriptors = RegInit(VecInit(Seq.fill(256)(
    VecInit(Seq.fill(4)(0.U.asTypeOf(new SharedTileDescriptor)))
  )))

  private def selected(mask: UInt, pe: Int): Bool = mask(3 - pe)

  private def descriptorValid(descriptor: SharedTileDescriptor, capacity: UInt): Bool = {
    val elements = descriptor.rows * descriptor.cols
    val rowsPowerOfTwo = (descriptor.rows & (descriptor.rows - 1.U)) === 0.U
    val colsPowerOfTwo = (descriptor.cols & (descriptor.cols - 1.U)) === 0.U
    descriptor.rows =/= 0.U &&
      descriptor.cols =/= 0.U &&
      rowsPowerOfTwo &&
      colsPowerOfTwo &&
      descriptor.validRows <= descriptor.rows &&
      descriptor.validCols <= descriptor.cols &&
      elements =/= 0.U &&
      elements <= (capacity << 1)
  }

  private def descriptorEqual(lhs: SharedTileDescriptor, rhs: SharedTileDescriptor): Bool =
    lhs.dtype === rhs.dtype &&
      lhs.validCols === rhs.validCols &&
      lhs.validRows === rhs.validRows &&
      lhs.cols === rhs.cols &&
      lhs.rows === rhs.rows

  val request = io.write.bits
  val currentAllocation = allocationMasks(request.sharedId)
  val currentInitialized = initializedMasks(request.sharedId)
  val currentCapacity = capacities(request.sharedId)
  val currentDtype = dtypes(request.sharedId)
  val unallocated = currentAllocation === 0.U

  val requestedCapacity = WireDefault(0.U(14.W))
  when(request.sizeCode >= 1.U && request.sizeCode <= 7.U) {
    requestedCapacity := (128.U(14.W) << (request.sizeCode - 1.U))(13, 0)
  }
  val sizeValid = request.sizeCode >= 1.U && request.sizeCode <= 7.U
  val expansion = !unallocated && (request.peMask & ~currentAllocation).orR

  val selectedDescriptorValid = VecInit((0 until 4).map { pe =>
    !selected(request.peMask, pe) || descriptorValid(request.descriptors(pe), requestedCapacity)
  }).asUInt.andR
  val selectedPayloadSizeValid = VecInit((0 until 4).map { pe =>
    !selected(request.peMask, pe) || request.payloadBytes(pe) === requestedCapacity
  }).asUInt.andR
  val selectedDescriptorCompatible = VecInit((0 until 4).map { pe =>
    !selected(request.peMask, pe) ||
      !currentInitialized(3 - pe) ||
      descriptorEqual(request.descriptors(pe), descriptors(request.sharedId)(pe))
  }).asUInt.andR
  val selectedDtypeCompatible = VecInit((0 until 4).map { pe =>
    !selected(request.peMask, pe) || request.descriptors(pe).dtype === currentDtype
  }).asUInt.andR
  val firstSelectedDtype = PriorityMux((0 until 4).map { pe =>
    selected(request.peMask, pe) -> request.descriptors(pe).dtype
  })
  val selectedDtypeUniform = VecInit((0 until 4).map { pe =>
    !selected(request.peMask, pe) || request.descriptors(pe).dtype === firstSelectedDtype
  }).asUInt.andR
  val selectedPayloadReady =
    (request.payloadReadyMask & request.peMask) === request.peMask

  val preflightStatus = WireDefault(SharedTileWriteStatus.Applied)
  when(request.peMask === 0.U) {
    preflightStatus := SharedTileWriteStatus.Noop
  }.elsewhen(!sizeValid) {
    preflightStatus := SharedTileWriteStatus.InvalidSize
  }.elsewhen(expansion) {
    preflightStatus := SharedTileWriteStatus.AllocationExpansion
  }.elsewhen(!selectedDescriptorValid) {
    preflightStatus := SharedTileWriteStatus.InvalidDescriptor
  }.elsewhen(!selectedPayloadSizeValid) {
    preflightStatus := SharedTileWriteStatus.PayloadSizeMismatch
  }.elsewhen(unallocated && !selectedDtypeUniform) {
    preflightStatus := SharedTileWriteStatus.DescriptorMismatch
  }.elsewhen(!unallocated &&
      (requestedCapacity =/= currentCapacity || !selectedDtypeCompatible)) {
    preflightStatus := SharedTileWriteStatus.DescriptorMismatch
  }.elsewhen(!selectedDescriptorCompatible) {
    preflightStatus := SharedTileWriteStatus.DescriptorMismatch
  }

  val waitingForPayload =
    preflightStatus === SharedTileWriteStatus.Applied && !selectedPayloadReady
  io.status := Mux(waitingForPayload, SharedTileWriteStatus.WaitingForPayload, preflightStatus)
  io.write.ready := preflightStatus =/= SharedTileWriteStatus.Applied || selectedPayloadReady

  val nextAllocation = Mux(unallocated, request.peMask, currentAllocation)
  val nextInitialized = currentInitialized | request.peMask
  val nextAllocatedBytes = Mux(
    unallocated,
    (requestedCapacity * PopCount(request.peMask))(15, 0),
    allocatedBytes(request.sharedId)
  )

  io.commit.valid := io.write.fire && preflightStatus === SharedTileWriteStatus.Applied
  io.commit.bits.sharedId := request.sharedId
  io.commit.bits.peMask := request.peMask
  io.commit.bits.perPeCapacity := requestedCapacity
  io.commit.bits.allocatedBytes := nextAllocatedBytes
  io.commit.bits.allocationMask := nextAllocation
  io.commit.bits.initializedMask := nextInitialized

  when(io.commit.valid) {
    when(unallocated) {
      allocationMasks(request.sharedId) := request.peMask
      capacities(request.sharedId) := requestedCapacity
      allocatedBytes(request.sharedId) := nextAllocatedBytes
      dtypes(request.sharedId) := firstSelectedDtype
    }
    initializedMasks(request.sharedId) := nextInitialized
    for (pe <- 0 until 4) {
      when(selected(request.peMask, pe)) {
        descriptors(request.sharedId)(pe) := request.descriptors(pe)
      }
    }
  }

  io.version.allocationMask := allocationMasks(io.readSharedId)
  io.version.initializedMask := initializedMasks(io.readSharedId)
  io.version.perPeCapacity := capacities(io.readSharedId)
  io.version.allocatedBytes := allocatedBytes(io.readSharedId)
  io.version.dtype := dtypes(io.readSharedId)
  io.readInitialized := initializedMasks(io.readSharedId)(3.U - io.readPeId)
  io.readDescriptor := descriptors(io.readSharedId)(io.readPeId)

  assert(!io.commit.valid || io.write.fire)
  assert(!io.commit.valid || selectedPayloadReady)
}

object TileBindingKind {
  val Bior = 0.U(2.W)
  val Biot = 1.U(2.W)
  val Bios = 2.U(2.W)
}

object TileOperandSpace {
  val ScalarAddress = 0.U(2.W)
  val Local = 1.U(2.W)
  val Shared = 2.U(2.W)
}

object TileOperationKind {
  val Tmov = 0.U(2.W)
  val Cube = 1.U(2.W)
  val Tgemv = 2.U(2.W)
}

object TileSharedUse {
  val None = 0.U(2.W)
  val Source = 1.U(2.W)
  val Destination = 2.U(2.W)
}

class TileOperandLegalityIO extends Bundle {
  val bindingKind = Input(UInt(2.W))
  val space = Input(UInt(2.W))
  val bindingLegal = Output(Bool())
  val operation = Input(UInt(2.W))
  val sharedUse = Input(UInt(2.W))
  val sharedMask = Input(UInt(4.W))
  val localMask = Input(UInt(4.W))
  val operationLegal = Output(Bool())
}

class TileOperandLegality extends Module {
  val io = IO(new TileOperandLegalityIO)

  io.bindingLegal :=
    (io.bindingKind === TileBindingKind.Bior && io.space === TileOperandSpace.ScalarAddress) ||
      (io.bindingKind === TileBindingKind.Biot && io.space === TileOperandSpace.Local) ||
      (io.bindingKind === TileBindingKind.Bios && io.space === TileOperandSpace.Shared)

  io.operationLegal := Mux(
    io.sharedMask === 0.U,
    true.B,
    MuxLookup(io.operation, false.B)(Seq(
      TileOperationKind.Tmov ->
        (io.sharedUse =/= TileSharedUse.None && io.sharedMask === io.localMask),
      TileOperationKind.Cube ->
        (io.sharedUse === TileSharedUse.Source && io.sharedMask === "hf".U),
      TileOperationKind.Tgemv -> (io.sharedUse === TileSharedUse.None)
    ))
  )
}
