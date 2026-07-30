package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Decoupled, log2Ceil}

import linxcore.common.LSIDOrder
import linxcore.rob.ROBID

/** One production load lookup presented to the canonical STQ image. */
class STQLoadForwardQuery(
    val robEntries: Int,
    val addrWidth: Int = 64,
    val stidWidth: Int = 8,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val tokenWidth: Int = 64)
    extends Bundle {
  val token = UInt(tokenWidth.W)
  val stid = UInt(stidWidth.W)
  val loadBid = new ROBID(robEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
  val address = UInt(addrWidth.W)
  val size = UInt(7.W)
  val isTile = Bool()
  val baseLineData = UInt((lineBytes * 8).W)
  val baseValidMask = UInt(lineBytes.W)
  val loadDataReturned = Bool()
  val scbReturned = Bool()
}

/** Retained E3 result from one replicated STQ load lookup pipe. */
class STQLoadForwardResponse(
    val robEntries: Int,
    val stqEntries: Int,
    val addrWidth: Int = 64,
    val stidWidth: Int = 8,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val tokenWidth: Int = 64)
    extends Bundle {
  val query = new STQLoadForwardQuery(
    robEntries, addrWidth, stidWidth, lineBytes, lsidWidth, tokenWidth)
  val loadByteMask = UInt(lineBytes.W)
  val tagMatchMask = UInt(stqEntries.W)
  val eligibleStoreMask = UInt(stqEntries.W)
  val unknownOlderMask = UInt(stqEntries.W)
  val staleSnapshotMask = UInt(stqEntries.W)
  val fullLsIdMissingMask = UInt(stqEntries.W)
  val fullLsIdAmbiguousMask = UInt(stqEntries.W)
  val crossLineStoreMask = UInt(stqEntries.W)
  val queryIdentityInvalid = Bool()
  val loadCrossesLine = Bool()
  val forwardMask = UInt(lineBytes.W)
  val waitMask = UInt(lineBytes.W)
  val uncoveredLoadMask = UInt(lineBytes.W)
  val mergedLineData = UInt((lineBytes * 8).W)
  val waitStore = new LoadStoreForwardWait(
    robEntries, stqEntries, pcWidth, lsidWidth)
  val unknownWaitStore = new LoadStoreForwardWait(
    robEntries, stqEntries, pcWidth, lsidWidth)
  val bypassComplete = Bool()
  val blocked = Bool()
}

class STQLoadForwardingPipelineIO(
    val loadPipes: Int,
    val stqEntries: Int,
    val robEntries: Int,
    val addrWidth: Int,
    val dataWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val sizeWidth: Int,
    val simtLaneWidth: Int,
    val mapQDepth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val lsidWidth: Int,
    val nativeBidWidth: Int,
    val ridGenerationWidth: Int,
    val brobGenerationWidth: Int,
    val memberIndexWidth: Int,
    val residentGenerationWidth: Int,
    val leaseGenerationWidth: Int,
    val tokenWidth: Int)
    extends Bundle {
  val hold = Input(Bool())
  val flush = Input(Bool())
  val metadataRows = Input(Vec(stqEntries, new STQEntryBankRow(
    robEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, pcWidth, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val dataRows = Input(Vec(stqEntries,
    new STQDataBankReadRow(dataWidth, lineBytes, leaseGenerationWidth)))
  val queries = Flipped(Vec(loadPipes, Decoupled(new STQLoadForwardQuery(
    robEntries, addrWidth, stidWidth, lineBytes, lsidWidth, tokenWidth))))
  val responses = Vec(loadPipes, Decoupled(new STQLoadForwardResponse(
    robEntries, stqEntries, addrWidth, stidWidth, pcWidth, lineBytes,
    lsidWidth, tokenWidth)))
  val occupied = Output(UInt(loadPipes.W))
}

/** Replicated E1-tag/E3-data lookup owner for production store forwarding.
  *
  * Every load pipe snapshots the complete canonical STQ tag image at E1.
  * E3 reads only the physical data owner and revalidates generation plus exact
  * semantic owner against the live metadata row.  Unknown older addresses and
  * stale row reuse are reported as hard blocks; they can never be interpreted
  * as cache misses or uncovered bytes.
  */
class STQLoadForwardingPipeline(
    val loadPipes: Int = 3,
    val stqEntries: Int = 16,
    val robEntries: Int = 64,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val tokenWidth: Int = 64)
    extends Module {
  require(loadPipes > 0, "STQ forwarding needs at least one load pipe")
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "STQ forwarding capacity must be a power of two greater than one")
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "STQ forwarding ROB identity capacity must be a power of two")
  require(lineBytes == 64,
    "production scalar STQ forwarding currently uses 64-byte lines")
  require(dataWidth == 64,
    "production scalar STQ forwarding currently consumes 64-bit stores")
  require(lsidWidth >= 2,
    "full LSID must support modular serial ordering")

  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new STQLoadForwardingPipelineIO(
    loadPipes, stqEntries, robEntries, addrWidth, dataWidth, peIdWidth,
    stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, pcWidth,
    lineBytes, lsidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth, tokenWidth))

  private def lineAddress(address: UInt): UInt =
    Cat(address(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  private def beforeLoad(row: STQEntryBankRow, query: STQLoadForwardQuery): Bool =
    ROBID.less(row.bid, query.loadBid) ||
      (ROBID.equal(row.bid, query.loadBid) && query.loadLsIdFullValid &&
        LSIDOrder.less(row.lsIdFull, query.loadLsIdFull))

  private def rowStable(
      snapshot: STQEntryBankRow,
      live: STQEntryBankRow): Bool =
    live.valid && live.status === STQEntryStatus.Wait &&
      live.leaseGeneration === snapshot.leaseGeneration &&
      live.exactOwner.asUInt === snapshot.exactOwner.asUInt &&
      live.stid === snapshot.stid && live.bid.asUInt === snapshot.bid.asUInt &&
      live.lsIdFull === snapshot.lsIdFull &&
      live.storeIdFullValid === snapshot.storeIdFullValid &&
      live.storeIdFull === snapshot.storeIdFull &&
      live.addrReady === snapshot.addrReady && live.addr === snapshot.addr &&
      live.size === snapshot.size

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(
      robEntries, stqEntries, pcWidth, lsidWidth))

  val e2Valid = RegInit(VecInit(Seq.fill(loadPipes)(false.B)))
  val e2Query = Reg(Vec(loadPipes, chiselTypeOf(io.queries.head.bits)))
  val e2Rows = Reg(Vec(loadPipes, Vec(stqEntries,
    chiselTypeOf(io.metadataRows.head))))
  val responseValid = RegInit(VecInit(Seq.fill(loadPipes)(false.B)))
  val response = Reg(Vec(loadPipes, chiselTypeOf(io.responses.head.bits)))

  for (pipe <- 0 until loadPipes) {
    val query = e2Query(pipe)
    val snapshots = e2Rows(pipe)
    val stores = Wire(Vec(stqEntries, new LoadStoreForwardStore(
      robEntries, stqEntries, addrWidth, pcWidth, lineBytes, lsidWidth)))
    val tagMatch = Wire(Vec(stqEntries, Bool()))
    val unknownOlder = Wire(Vec(stqEntries, Bool()))
    val staleSnapshot = Wire(Vec(stqEntries, Bool()))
    val fullLsIdMissing = Wire(Vec(stqEntries, Bool()))
    val fullLsIdAmbiguous = Wire(Vec(stqEntries, Bool()))
    val crossLineStore = Wire(Vec(stqEntries, Bool()))

    for (index <- 0 until stqEntries) {
      val tag = snapshots(index)
      val live = io.metadataRows(index)
      val physical = io.dataRows(index)
      val waitRow = tag.valid && tag.status === STQEntryStatus.Wait &&
        tag.scalarIex && tag.stid === query.stid
      val sameBid = ROBID.equal(tag.bid, query.loadBid)
      val missingAuthority = waitRow && sameBid && !query.loadLsIdFullValid
      val ambiguousAuthority = waitRow && sameBid &&
        query.loadLsIdFullValid &&
        LSIDOrder.ambiguous(tag.lsIdFull, query.loadLsIdFull)
      val older = waitRow && !missingAuthority && !ambiguousAuthority &&
        beforeLoad(tag, query)
      val tagEnd = tag.addr(lineOffsetWidth - 1, 0) +& tag.size
      val crosses = tagEnd > lineBytes.U
      val loadEnd = query.address +& query.size
      val storeEnd = tag.addr +& tag.size
      val overlap = tag.size =/= 0.U && query.size =/= 0.U &&
        tag.addr < loadEnd && query.address < storeEnd
      val matchAtE1 = older && tag.addrReady && overlap && !crosses
      val stable = rowStable(tag, live)
      val dataExact = physical.valid &&
        physical.generation === tag.leaseGeneration
      val offset = tag.addr(lineOffsetWidth - 1, 0)
      val positionedMask = (physical.byteMask << offset)(lineBytes - 1, 0)
      val positionedData = (physical.lineData << (offset << 3))(
        lineBytes * 8 - 1, 0)

      stores(index) := 0.U.asTypeOf(stores(index))
      stores(index).valid := matchAtE1 && stable
      stores(index).working := matchAtE1 && stable
      stores(index).addrReady := true.B
      stores(index).dataReady := live.dataReady && dataExact
      stores(index).isTile := false.B
      stores(index).storeIndex := index.U
      stores(index).storeId := tag.bid
      stores(index).storeLsId := tag.lsId
      stores(index).storeLsIdFullValid := tag.valid
      stores(index).storeLsIdFull := tag.lsIdFull
      stores(index).pc := tag.pc
      stores(index).lineAddr := lineAddress(tag.addr)
      stores(index).byteMask := positionedMask
      stores(index).data := positionedData

      tagMatch(index) := matchAtE1
      unknownOlder(index) := older && !tag.addrReady
      staleSnapshot(index) := older && (!stable ||
        (matchAtE1 && live.dataReady && !dataExact))
      fullLsIdMissing(index) := missingAuthority
      fullLsIdAmbiguous(index) := ambiguousAuthority
      crossLineStore(index) := older && tag.addrReady && overlap && crosses
    }

    val forward = Module(new LoadStoreForwarding(
      robEntries, stqEntries, addrWidth, pcWidth, lineBytes,
      sizeWidth = 7, lsidWidth = lsidWidth))
    forward.io.query.valid := e2Valid(pipe)
    forward.io.query.lineAddr := lineAddress(query.address)
    forward.io.query.byteOffset := query.address(lineOffsetWidth - 1, 0)
    forward.io.query.size := query.size
    forward.io.query.youngestStoreId := query.loadBid
    forward.io.query.youngestStoreLsId := ROBID.disabled(robEntries)
    forward.io.query.youngestStoreLsIdFullValid := query.loadLsIdFullValid
    forward.io.query.youngestStoreLsIdFull := query.loadLsIdFull
    forward.io.query.isTile := query.isTile
    forward.io.stores := stores
    forward.io.cacheData := query.baseLineData

    var unknownValid: Bool = false.B
    var unknownIndex: UInt = 0.U(log2Ceil(stqEntries).W)
    var unknownBid: ROBID = ROBID.disabled(robEntries)
    var unknownLsid: ROBID = ROBID.disabled(robEntries)
    var unknownFull: UInt = 0.U(lsidWidth.W)
    var unknownPc: UInt = 0.U(pcWidth.W)
    for (index <- 0 until stqEntries) {
      val row = snapshots(index)
      val nearer = !unknownValid || ROBID.less(unknownBid, row.bid) ||
        (ROBID.equal(unknownBid, row.bid) &&
          LSIDOrder.less(unknownFull, row.lsIdFull))
      val take = unknownOlder(index) && nearer
      unknownIndex = Mux(take, index.U, unknownIndex)
      unknownBid = Mux(take, row.bid, unknownBid)
      unknownLsid = Mux(take, row.lsId, unknownLsid)
      unknownFull = Mux(take, row.lsIdFull, unknownFull)
      unknownPc = Mux(take, row.pc, unknownPc)
      unknownValid = unknownValid || unknownOlder(index)
    }
    val unknownWait = Wire(chiselTypeOf(forward.io.waitStore))
    unknownWait := zeroWait
    unknownWait.valid := unknownValid
    unknownWait.storeIndex := unknownIndex
    unknownWait.storeId := unknownBid
    unknownWait.storeLsId := unknownLsid
    unknownWait.storeLsIdFullValid := unknownValid
    unknownWait.storeLsIdFull := unknownFull
    unknownWait.pc := unknownPc

    val computed = Wire(chiselTypeOf(io.responses(pipe).bits))
    val queryIdentityInvalid = !query.loadBid.valid ||
      !query.loadLsIdFullValid || query.size === 0.U
    val loadCrossesLine =
      (query.address(lineOffsetWidth - 1, 0) +& query.size) > lineBytes.U
    computed := 0.U.asTypeOf(computed)
    computed.query := query
    computed.loadByteMask := forward.io.loadByteMask
    computed.tagMatchMask := tagMatch.asUInt
    computed.eligibleStoreMask := forward.io.eligibleStoreMask
    computed.unknownOlderMask := unknownOlder.asUInt
    computed.staleSnapshotMask := staleSnapshot.asUInt
    computed.fullLsIdMissingMask := fullLsIdMissing.asUInt |
      forward.io.fullLsIdMissingMask
    computed.fullLsIdAmbiguousMask := fullLsIdAmbiguous.asUInt |
      forward.io.fullLsIdAmbiguousMask
    computed.crossLineStoreMask := crossLineStore.asUInt
    computed.queryIdentityInvalid := queryIdentityInvalid
    computed.loadCrossesLine := loadCrossesLine
    computed.forwardMask := forward.io.forwardMask
    computed.waitMask := forward.io.waitMask
    computed.uncoveredLoadMask := forward.io.uncoveredLoadMask
    computed.mergedLineData := forward.io.mergedData
    computed.waitStore := forward.io.waitStore
    computed.unknownWaitStore := unknownWait
    computed.bypassComplete := forward.io.storeBypassComplete &&
      !unknownOlder.asUInt.orR && !staleSnapshot.asUInt.orR &&
      !fullLsIdMissing.asUInt.orR && !fullLsIdAmbiguous.asUInt.orR &&
      !crossLineStore.asUInt.orR && !queryIdentityInvalid &&
      !loadCrossesLine
    computed.blocked := unknownOlder.asUInt.orR ||
      staleSnapshot.asUInt.orR || fullLsIdMissing.asUInt.orR ||
      fullLsIdAmbiguous.asUInt.orR || crossLineStore.asUInt.orR ||
      forward.io.waitMask.orR || queryIdentityInvalid || loadCrossesLine

    val responseReady = !responseValid(pipe) || io.responses(pipe).ready
    io.queries(pipe).ready := !io.hold && !io.flush && responseReady
    io.responses(pipe).valid := responseValid(pipe) && !io.hold && !io.flush
    io.responses(pipe).bits := response(pipe)

    when(io.flush) {
      e2Valid(pipe) := false.B
      responseValid(pipe) := false.B
    }.elsewhen(responseReady && !io.hold) {
      responseValid(pipe) := e2Valid(pipe)
      when(e2Valid(pipe)) {
        response(pipe) := computed
      }
      e2Valid(pipe) := io.queries(pipe).fire
      when(io.queries(pipe).fire) {
        e2Query(pipe) := io.queries(pipe).bits
        e2Rows(pipe) := io.metadataRows
      }
    }
  }

  io.occupied := VecInit((0 until loadPipes).map(pipe =>
    e2Valid(pipe) || responseValid(pipe))).asUInt
}
