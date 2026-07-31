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
}
