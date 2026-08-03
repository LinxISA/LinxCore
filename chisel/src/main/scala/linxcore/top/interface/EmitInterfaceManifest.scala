package linxcore.top.interface

import chisel3._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import linxcore.params.{CoreParams, ParamProfiles}

final case class ManifestPort(name: String, width: Int)

final case class ManifestEndpoint(
    name: String,
    contractId: String,
    producer: String,
    consumer: String,
    lanes: Int,
    payload: String,
    ports: Seq[ManifestPort])

final case class ManifestProfile(
    name: String,
    width: Int,
    endpoints: Seq[ManifestEndpoint])

final case class InterfaceManifestModel(
    schema: Int,
    profiles: Seq[ManifestProfile])

object InterfaceManifest {
  private final case class EndpointDefinition(
      name: String,
      contractId: String,
      producer: String,
      consumer: String,
      lanes: CoreParams => Int,
      payload: CoreParams => Data)

  private val endpointDefinitions = Seq(
    EndpointDefinition("ifu_to_ctu", "IFC-IFU-CTU-001", "IFU", "CTU",
      _.widths.fetchWidth, p => new FetchedPacket(p)),
    EndpointDefinition("ctu_to_ooo", "IFC-CTU-OOO-001", "CTU", "OOO",
      _.widths.ctuOutputWidth, p => new D1Packet(p)),
    EndpointDefinition("ooo_to_iex_alu", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.iex.aluPipes, p => new DispatchTxn(p)),
    EndpointDefinition("ooo_to_iex_bru", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.iex.bruPipes, p => new DispatchTxn(p)),
    EndpointDefinition("ooo_to_iex_agu", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.iex.aguPipes, p => new DispatchTxn(p)),
    EndpointDefinition("ooo_to_iex_std", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.iex.stdPipes, p => new StoreDispatchTxn(p)),
    EndpointDefinition("ooo_to_iex_system", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.iex.systemMulticycleQueues, p => new DispatchTxn(p)),
    EndpointDefinition("ooo_to_iex_cmd", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.iex.cmdIssueQueues, p => new DispatchTxn(p)),
    EndpointDefinition("ooo_to_iex_rob_noflush", "IFC-OOO-IEX-001", "OOO", "IEX",
      _ => 1, p => new RobNoflushTxn(p)),
    EndpointDefinition("iex_to_ooo_rob_noflush_ready", "IFC-OOO-IEX-001", "IEX", "OOO",
      _ => 1, p => new RobNoflushReadyTxn(p)),
    EndpointDefinition("iex_to_ooo_rob_resolve", "IFC-OOO-IEX-001", "IEX", "OOO",
      _.widths.issueWidth, p => new RobResolveTxn(p)),
    EndpointDefinition("iex_to_ooo_system_issue", "IFC-OOO-IEX-001", "IEX", "OOO",
      _.iex.systemMulticycleQueues, p => new SystemIssueTxn(p)),
    EndpointDefinition("iex_to_ooo_pc_buffer_read_address", "IFC-OOO-IEX-001", "IEX", "OOO",
      _.ooo.pcReadPorts, p => new PcBufferReadAddress(p)),
    EndpointDefinition("ooo_to_iex_pc_buffer_read_pc_base", "IFC-OOO-IEX-001", "OOO", "IEX",
      _.ooo.pcReadPorts, p => UInt(p.pcWidth.W)),
    EndpointDefinition("iex_to_lsu_load_issue", "IFC-IEX-LSU-001", "IEX", "LSU",
      _.lsu.loadPipes, p => new LoadIssueTxn(p)),
    EndpointDefinition("iex_to_lsu_store_address", "IFC-IEX-LSU-001", "IEX", "LSU",
      _.lsu.storePipes, p => new StoreAddressTxn(p)),
    EndpointDefinition("iex_to_lsu_store_data", "IFC-IEX-LSU-001", "IEX", "LSU",
      _.lsu.storePipes, p => new StoreDataTxn(p)),
    EndpointDefinition("lsu_to_iex_load_result", "IFC-IEX-LSU-001", "LSU", "IEX",
      _.lsu.loadPipes, p => new LoadResultTxn(p)),
    EndpointDefinition("lsu_to_iex_load_reissue", "IFC-IEX-LSU-001", "LSU", "IEX",
      _.lsu.loadPipes, p => new LoadReissueTxn(p)),
    EndpointDefinition("lsu_to_iex_load_repick", "IFC-IEX-LSU-001", "LSU", "IEX",
      _.lsu.loadPipes, p => new LoadRepickTxn(p)),
    EndpointDefinition("lsu_to_iex_load_cancel", "IFC-IEX-LSU-001", "LSU", "IEX",
      _.lsu.loadPipes, p => new LoadCancelTxn(p)),
    EndpointDefinition("external_cmd_issue", "IFC-TOP-EXT-001", "IEX", "External CMD",
      _ => 1, p => new CmdIssueTxn(p)),
    EndpointDefinition("owner_to_ooo_recovery_event", "IFC-RECOVERY-001", "IEX/LSU",
      "OOO", _ => 1, p => new RecoveryEvent(p)),
    EndpointDefinition("ooo_recovery_prepare", "IFC-RECOVERY-001", "OOO", "IFU/CTU/IEX/LSU",
      _ => 1, p => new RecoveryPlan(p)),
    EndpointDefinition("target_to_ooo_recovery_prepared", "IFC-RECOVERY-001",
      "IFU/CTU/IEX/LSU", "OOO", _ => 1, p => new RecoveryPlan(p)),
    EndpointDefinition("ooo_recovery_apply", "IFC-RECOVERY-001", "OOO", "IFU/CTU/IEX/LSU",
      _ => 1, p => new RecoveryPlan(p)),
    EndpointDefinition("ooo_recovery_abort", "IFC-RECOVERY-001", "OOO", "IFU/CTU/IEX/LSU",
      _ => 1, p => new RecoveryPlan(p)),
    EndpointDefinition("ooo_commit", "IFC-COMMIT-001", "OOO", "TOP/DTU",
      _.widths.retireWidth, p => new CommitTxn(p)),
    EndpointDefinition("box_to_dtu_trace", "IFC-DTU-001", "TOP/IFU/CTU/OOO/IEX/LSU",
      "DTU", _.dtu.traceWidth, p => new TracePacket(p)),
    EndpointDefinition("instruction_memory_request", "IFC-MEMORY-001", "IFU", "Memory",
      _ => 1, p => new MemoryRequestTxn(p)),
    EndpointDefinition("instruction_memory_response", "IFC-MEMORY-001", "Memory", "IFU",
      _ => 1, p => new MemoryResponseTxn(p)),
    EndpointDefinition("data_memory_request", "IFC-MEMORY-001", "LSU", "Memory",
      p => p.lsu.loadPipes + p.lsu.storePipes, p => new MemoryRequestTxn(p)),
    EndpointDefinition("data_memory_response", "IFC-MEMORY-001", "Memory", "LSU",
      p => p.lsu.loadPipes + p.lsu.storePipes, p => new MemoryResponseTxn(p)))

  private def flatten(data: Data, prefix: String = ""): Seq[ManifestPort] =
    data match {
      case bundle: Bundle =>
        bundle.elements.toSeq.sortBy(_._1).flatMap { case (name, element) =>
          flatten(element, if (prefix.isEmpty) name else s"${prefix}_${name}")
        }
      case vector: Vec[_] =>
        vector.zipWithIndex.flatMap { case (element, index) =>
          flatten(element, if (prefix.isEmpty) index.toString
            else s"${prefix}_${index}")
        }
      case element: Element =>
        Seq(ManifestPort(prefix, element.getWidth))
    }

  private[interface] def profileFor(name: String, p: CoreParams): ManifestProfile =
    {
      require(
        new RecoveryTargetIO(p).elements.keySet ==
          Set("prepare", "prepared", "apply", "abort"),
        "recovery manifest must be updated when RecoveryTargetIO changes")
      ManifestProfile(
        name = name,
        width = p.widths.decodeWidth,
        endpoints = endpointDefinitions.map { definition =>
          val payload = definition.payload(p)
          ManifestEndpoint(
            name = definition.name,
            contractId = definition.contractId,
            producer = definition.producer,
            consumer = definition.consumer,
            lanes = definition.lanes(p),
            payload = payload.getClass.getSimpleName,
            ports = flatten(payload))
        })
    }

  lazy val model: InterfaceManifestModel =
    InterfaceManifestModel(
      schema = 1,
      profiles = Seq(
        profileFor("W2", ParamProfiles.W2),
        profileFor("W4", ParamProfiles.W4),
        profileFor("W6", ParamProfiles.W6),
        profileFor("W8", ParamProfiles.W8)))

  private def jsonString(value: String): String = {
    val escaped = value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case character => character.toString
    }
    s"\"$escaped\""
  }

  def renderJson: String = {
    val profiles = model.profiles.map { profile =>
      val endpoints = profile.endpoints.map { endpoint =>
        val ports = endpoint.ports.map { port =>
          s"""{"name":${jsonString(port.name)},"width":${port.width}}"""
        }.mkString("[", ",", "]")
        s"""{"name":${jsonString(endpoint.name)},"contract_id":${jsonString(endpoint.contractId)},"producer":${jsonString(endpoint.producer)},"consumer":${jsonString(endpoint.consumer)},"lanes":${endpoint.lanes},"payload":${jsonString(endpoint.payload)},"ports":$ports}"""
      }.mkString("[", ",", "]")
      s"""{"name":${jsonString(profile.name)},"width":${profile.width},"endpoints":$endpoints}"""
    }.mkString("[", ",", "]")
    s"""{"schema":${model.schema},"profiles":$profiles}
"""
  }

  def renderMarkdown: String = {
    val rows = model.profiles.flatMap { profile =>
      profile.endpoints.map { endpoint =>
        val bits = endpoint.ports.map(_.width).sum
        s"| ${profile.name} | `${endpoint.name}` | [[${endpoint.contractId}]] | ${endpoint.producer} | ${endpoint.consumer} | ${endpoint.lanes} | `${endpoint.payload}` | ${endpoint.ports.size} | $bits |"
      }
    }
    Seq(
      "# Generated TOP interface manifest",
      "",
      "This file is generated from the canonical Scala Bundle types. Do not edit it by hand.",
      "",
      "| Profile | Endpoint | Contract | Producer | Consumer | Lanes | Payload | Leaf ports | Payload bits |",
      "|---|---|---|---|---|---:|---|---:|---:|") ++ rows mkString "\n"
  } + "\n"
}

object EmitInterfaceManifest extends App {
  private def argument(name: String): Path = {
    val index = args.indexOf(name)
    require(index >= 0 && index + 1 < args.length, s"missing $name path")
    Paths.get(args(index + 1))
  }

  private def write(path: Path, contents: String): Unit = {
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
    Files.writeString(path, contents, StandardCharsets.UTF_8)
  }

  write(argument("--json"), InterfaceManifest.renderJson)
  write(argument("--markdown"), InterfaceManifest.renderMarkdown)
}
