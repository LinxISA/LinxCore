package linxcore.top.interface

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
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

      assert(endpoints("ooo_to_iex_alu").lanes == 2)
      assert(endpoints("ooo_to_iex_bru").lanes == 1)
      assert(endpoints("ooo_to_iex_agu").lanes == 2)
      assert(endpoints("ooo_to_iex_std").lanes == 2)
      assert(endpoints("iex_to_ooo_rob_resolve").lanes == width)
      assert(endpoints("iex_to_lsu_load_issue").lanes == 2)
      assert(endpoints("iex_to_lsu_store_address").lanes == 2)
      assert(endpoints("iex_to_lsu_store_data").lanes == 2)
      assert(endpoints("lsu_to_iex_load_reissue").lanes == 2)
      assert(endpoints("lsu_to_iex_load_repick").lanes == 2)
      assert(endpoints("lsu_to_iex_load_cancel").lanes == 2)
      assert(endpoints("external_cmd_issue").lanes == 1)

      assert(endpoints("iex_to_ooo_rob_resolve").payload == "RobResolveTxn")
      assert(endpoints("iex_to_lsu_load_issue").payload == "LoadIssueTxn")
      assert(endpoints("lsu_to_iex_load_reissue").payload == "LoadReissueTxn")
      assert(endpoints("lsu_to_iex_load_repick").payload == "LoadRepickTxn")
      assert(endpoints("lsu_to_iex_load_cancel").payload == "LoadCancelTxn")
      assert(endpoints("ooo_to_iex_rob_noflush").payload == "RobNoflushTxn")
      assert(endpoints("iex_to_ooo_system_issue").payload == "SystemIssueTxn")
      assert(endpoints("external_cmd_issue").payload == "CmdIssueTxn")

      val orderPorts = endpoints("ooo_to_iex_alu").ports
        .filter(_.name.startsWith("memoryOrder_"))
        .map(_.name)
        .toSet
      assert(orderPorts == Set(
        "memoryOrder_requestCount", "memoryOrder_firstLsid",
        "memoryOrder_firstTypeId", "memoryOrder_youngestStoreValid",
        "memoryOrder_youngestStoreLsid", "memoryOrder_youngestStoreId"))
      assert(!orderPorts.exists(name =>
        name.toLowerCase.contains("transaction") ||
          name.toLowerCase.contains("attempt") ||
          name.toLowerCase.contains("pipe") ||
          name.toLowerCase.contains("slot") ||
          name.toLowerCase.contains("lane")))

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
    assert(endpoints("ooo_to_iex_agu").lanes == 2)
    assert(endpoints("ooo_to_iex_std").lanes == 2)
    assert(endpoints("iex_to_lsu_load_issue").lanes == 2)
    assert(endpoints("iex_to_lsu_store_address").lanes == 2)
    assert(endpoints("iex_to_lsu_store_data").lanes == 2)
  }
}
