package linxcore.top.interface

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import chisel3.util.log2Ceil
import linxcore.params.ParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class InterfaceManifestSpec extends AnyFunSuite {
  private val repoRoot = Paths.get("..").toAbsolutePath.normalize
  private val generatedRoot = repoRoot.resolve("docs/chisel/generated")

  test("generated JSON and Markdown are exact projections of canonical Bundles") {
    val expectedJson = InterfaceManifest.renderJson
    val expectedMarkdown = InterfaceManifest.renderMarkdown
    val actualJson = Files.readString(
      generatedRoot.resolve("top-interface-manifest.json"),
      StandardCharsets.UTF_8)
    val actualMarkdown = Files.readString(
      generatedRoot.resolve("top-interface-manifest.md"),
      StandardCharsets.UTF_8)

    assert(actualJson == expectedJson)
    assert(actualMarkdown == expectedMarkdown)
  }

  test("every endpoint has one contract home and every profile is represented") {
    val manifest = InterfaceManifest.model

    assert(manifest.profiles.map(_.name) == Seq("W2", "W4", "W6", "W8"))
    assert(manifest.profiles.forall(_.endpoints.nonEmpty))
    assert(manifest.profiles.flatMap(_.endpoints).forall(_.contractId.startsWith("IFC-")))
    assert(manifest.profiles.flatMap(_.endpoints).forall(_.ports.nonEmpty))
    assert(manifest.profiles.flatMap(_.endpoints).flatMap(_.ports)
      .exists(port => port.name.endsWith("_instruction") && port.width == 64))
    assert(!manifest.profiles.flatMap(_.endpoints).exists { endpoint =>
      endpoint.name.contains("iex_to_ifu") || endpoint.name.contains("lsu_to_ifu")
    })
    val recoveryEndpoints = manifest.profiles.head.endpoints
      .filter(_.contractId == "IFC-RECOVERY-001")
      .map(_.name)
      .toSet
    assert(recoveryEndpoints == Set(
      "owner_to_ooo_recovery_event",
      "ooo_recovery_prepare",
      "target_to_ooo_recovery_prepared",
      "ooo_recovery_apply",
      "ooo_recovery_abort"))
  }

  test("backend profiles freeze lane multiplicity canonical payload names and identities") {
    InterfaceManifest.model.profiles.foreach { profile =>
      val endpoints = profile.endpoints.map(endpoint => endpoint.name -> endpoint).toMap
      val width = profile.width

      val expected = Map(
        "ooo_to_iex_alu" -> ("OOO", "IEX", 2, "DispatchTxn"),
        "ooo_to_iex_bru" -> ("OOO", "IEX", 1, "DispatchTxn"),
        "ooo_to_iex_agu" -> ("OOO", "IEX", 2, "DispatchTxn"),
        "ooo_to_iex_std" -> ("OOO", "IEX", 2, "StoreDispatchTxn"),
        "ooo_to_iex_system" -> ("OOO", "IEX", 1, "DispatchTxn"),
        "ooo_to_iex_cmd" -> ("OOO", "IEX", 1, "DispatchTxn"),
        "ooo_to_iex_rob_noflush" -> ("OOO", "IEX", 1, "RobNoflushTxn"),
        "iex_to_ooo_rob_noflush_ready" ->
          ("IEX", "OOO", 1, "RobNoflushReadyTxn"),
        "iex_to_ooo_rob_resolve" -> ("IEX", "OOO", width, "RobResolveTxn"),
        "iex_to_ooo_system_issue" -> ("IEX", "OOO", 1, "SystemIssueTxn"),
        "iex_to_ooo_pc_buffer_read_address" ->
          ("IEX", "OOO", profile.endpoints
            .find(_.name == "iex_to_ooo_pc_buffer_read_address").get.lanes,
            "PcBufferReadAddress"),
        "ooo_to_iex_pc_buffer_read_pc_base" ->
          ("OOO", "IEX", profile.endpoints
            .find(_.name == "ooo_to_iex_pc_buffer_read_pc_base").get.lanes,
            "UInt"),
        "iex_to_lsu_load_issue" -> ("IEX", "LSU", 2, "LoadIssueTxn"),
        "lsu_to_iex_load_allocation_preview" ->
          ("LSU", "IEX", 2, "LoadAllocationPreview"),
        "lsu_to_iex_load_launch" -> ("LSU", "IEX", 2, "LoadLaunchTxn"),
        "iex_to_lsu_store_reservation" ->
          ("IEX", "LSU", 2, "StoreReservationTxn"),
        "iex_to_lsu_store_address" -> ("IEX", "LSU", 2, "StoreAddressTxn"),
        "iex_to_lsu_store_data" -> ("IEX", "LSU", 2, "StoreDataTxn"),
        "lsu_to_iex_load_result" -> ("LSU", "IEX", 2, "LoadResultTxn"),
        "lsu_to_iex_load_reissue" -> ("LSU", "IEX", 2, "LoadReissueTxn"),
        "lsu_to_iex_load_repick" -> ("LSU", "IEX", 2, "LoadRepickTxn"),
        "lsu_to_iex_load_cancel" -> ("LSU", "IEX", 2, "LoadCancelTxn"),
        "ooo_to_lsu_store_commit" ->
          ("OOO", "LSU", 1, "StoreCommitAuthorizationTxn"),
        "translation_to_lsu_store_classify" ->
          ("Translation", "LSU", 1, "StoreMemoryClassifyTxn"),
        "external_cmd_issue" -> ("IEX", "External CMD", 1, "CmdIssueTxn"))

      expected.foreach { case (name, (producer, consumer, lanes, payload)) =>
        val endpoint = endpoints(name)
        assert(endpoint.producer == producer, name)
        assert(endpoint.consumer == consumer, name)
        assert(endpoint.lanes == lanes, name)
        assert(endpoint.payload == payload, name)
      }

      val orderPorts = endpoints("ooo_to_iex_alu").ports
        .filter(_.name.startsWith("memoryOrder_"))
        .map(_.name)
        .toSet
      assert(orderPorts == Set(
        "memoryOrder_requestCount", "memoryOrder_firstLsid",
        "memoryOrder_firstLid", "memoryOrder_firstSid",
        "memoryOrder_yostValid", "memoryOrder_yostLsid",
        "memoryOrder_yostSid", "memoryOrder_yoldValid",
        "memoryOrder_yoldLsid", "memoryOrder_yoldLid"))
      assert(!orderPorts.exists(name =>
        name.toLowerCase.contains("transaction") ||
          name.toLowerCase.contains("attempt") ||
          name.toLowerCase.contains("pipe") ||
          name.toLowerCase.contains("slot") ||
          name.toLowerCase.contains("lane")))

      val trapPorts = endpoints("ooo_to_iex_alu").ports
        .filter(_.name.startsWith("trap_"))
        .map(port => port.name -> port.width)
        .toMap
      assert(trapPorts == Map(
        "trap_valid" -> 1,
        "trap_cause" -> ParamProfiles.W4.trapCauseWidth))

      val storeTrapPorts = endpoints("ooo_to_iex_std").ports
        .filter(port => port.name.startsWith("sta_trap_") ||
          port.name.startsWith("std_trap_"))
        .map(port => port.name -> port.width)
        .toMap
      assert(storeTrapPorts == Map(
        "sta_trap_valid" -> 1,
        "sta_trap_cause" -> ParamProfiles.W4.trapCauseWidth,
        "std_trap_valid" -> 1,
        "std_trap_cause" -> ParamProfiles.W4.trapCauseWidth))

      val pcIndexOffsetPorts = endpoints("ooo_to_iex_alu").ports
        .filter(_.name.startsWith("pcBufferIndexOffset_"))
        .map(port => port.name -> port.width)
        .toMap
      val p = ParamProfiles.forWidth(width)
      assert(pcIndexOffsetPorts == Map(
        "pcBufferIndexOffset_valid" -> 1,
        "pcBufferIndexOffset_pcBufferIndex" -> log2Ceil(p.ooo.pcBufferEntries),
        "pcBufferIndexOffset_pcOffset" -> p.ooo.pcOffsetWidth,
        "pcBufferIndexOffset_allocationEpoch" ->
          p.ooo.pcAllocationEpochWidth))

      val readAddressPorts = endpoints("iex_to_ooo_pc_buffer_read_address").ports
        .map(port => port.name -> port.width)
        .toMap
      assert(readAddressPorts == Map(
        "valid" -> 1,
        "stid" -> math.max(1, log2Ceil(p.ooo.stidCount)),
        "pcBufferIndex" -> log2Ceil(p.ooo.pcBufferEntries),
        "allocationEpoch" -> p.ooo.pcAllocationEpochWidth))
      assert(endpoints("ooo_to_iex_pc_buffer_read_pc_base").ports ==
        Seq(ManifestPort("", p.pcWidth)))

      val issueIdentityPorts = endpoints("iex_to_lsu_load_issue").ports
        .filter(_.name.startsWith("identity_"))
        .map(_.name)
        .toSet
      assert(issueIdentityPorts.contains("identity_transaction_value"))
      assert(issueIdentityPorts.contains("identity_transaction_generation"))
      assert(issueIdentityPorts.contains("identity_lsid"))
      assert(issueIdentityPorts.contains("identity_attemptGeneration"))
      assert(issueIdentityPorts.contains("identity_pipeId"))
    }

    val w4 = InterfaceManifest.model.profiles.find(_.name == "W4").get
    val endpoints = w4.endpoints.map(endpoint => endpoint.name -> endpoint).toMap
    assert(endpoints("ooo_to_iex_alu").lanes == 2)
    assert(endpoints("ooo_to_iex_bru").lanes == 1)
    assert(endpoints("ooo_to_iex_agu").lanes == 2)
    assert(endpoints("ooo_to_iex_std").lanes == 2)
    assert(endpoints("ooo_to_iex_system").lanes == 1)
    assert(endpoints("ooo_to_iex_cmd").lanes == 1)
    assert(endpoints("iex_to_lsu_load_issue").lanes == 2)
    assert(endpoints("iex_to_lsu_store_address").lanes == 2)
    assert(endpoints("iex_to_lsu_store_data").lanes == 2)
  }

  test("internal CMD queue multiplicity is independent of the singleton external endpoint") {
    val base = ParamProfiles.W4
    val p = base.copy(iex = base.iex.copy(cmdIssueQueues = 2))
    val profile = InterfaceManifest.profileFor("CUSTOM", p)
    val endpoints = profile.endpoints.map(endpoint => endpoint.name -> endpoint).toMap

    assert(new OOOIEXIO(p).cmdDispatch.length == 2)
    assert(endpoints("ooo_to_iex_cmd").lanes == 2)
    assert(endpoints("external_cmd_issue").lanes == 1)
  }

  test("memory lifecycle manifest leaves preserve independent identity widths") {
    val base = ParamProfiles.W4
    val p = base.copy(
      ooo = base.ooo.copy(robGroupsPerStid = 32),
      lsu = base.lsu.copy(
        loadPipes = 8,
        loadQueueEntries = 16,
        loadReturnQueueEntries = 8),
      lsidWidth = 37,
      ridGenerationWidth = 7,
      residentGenerationWidth = 9,
      memoryTransactionIdWidth = 41,
      memoryTransactionGenerationWidth = 11,
      memoryAttemptGenerationWidth = 13)
    val profile = InterfaceManifest.profileFor("IDENTITY", p)
    val endpoints = profile.endpoints.map(endpoint => endpoint.name -> endpoint).toMap
    val expectedSuffixWidths = Map(
      "rob_peId" -> p.peIdWidth,
      "rob_stid" -> 1,
      "rob_ridSlot" -> 5,
      "rob_ridGeneration" -> 7,
      "rob_memberIndex" -> 2,
      "rob_residentGeneration" -> 9,
      "rob_bid" -> p.nativeBidWidth,
      "rob_brobGeneration" -> p.brobGenerationWidth,
      "transaction_value" -> 41,
      "transaction_generation" -> 11,
      "lsid" -> 37,
      "attemptGeneration" -> 13,
      "pipeId" -> 3)

    def assertIdentity(endpointName: String, prefix: String): Unit = {
      val actual = endpoints(endpointName).ports.collect {
        case port if port.name.startsWith(prefix) =>
          port.name.stripPrefix(prefix) -> port.width
      }.toMap
      assert(actual == expectedSuffixWidths, s"$endpointName:$prefix")
    }

    assertIdentity("iex_to_lsu_load_issue", "identity_")
    assertIdentity("lsu_to_iex_load_reissue", "currentIdentity_")
    assertIdentity("lsu_to_iex_load_reissue", "nextIdentity_")
    assertIdentity("lsu_to_iex_load_repick", "currentIdentity_")
    assertIdentity("lsu_to_iex_load_repick", "nextIdentity_")
    assertIdentity("lsu_to_iex_load_cancel", "currentIdentity_")
  }
}
