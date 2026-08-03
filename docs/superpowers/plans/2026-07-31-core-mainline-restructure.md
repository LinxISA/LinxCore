# Core Mainline Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立唯一的 `TOP -> IFU -> CTU -> OOO -> IEX -> LSU` Chisel 主链，接入 `DTU`，支持主要宽度 2/4/6/8 配置，跑通无指令预录和无提交回放的 Dhrystone、CoreMark，并删除旧顶层、`Reduced*` 实现和过期改进文档。

**Architecture:** 采用并行新链替换方式：先冻结参数、跨 box typed Bundle 和状态所有权，再逐个接入 IFU、CTU、OOO、IEX、LSU、DTU，最后用自然 ELF、提交对比和恢复压力测试证明新链闭合后删除旧链。`TOP` 只实例化和路由；ROB/提交/精确恢复裁决属于 OOO，各 box 只拥有本地状态及本地清理动作。

**Tech Stack:** Scala 2.13、Chisel 7.3、sbt、ScalaTest/chiseltest、Verilator、Python 3 标准库、Linx QEMU、LinxCoreModel、仓内自然 ELF harness。

## Global Constraints

- 对外公开的主要 Chisel module 名严格为 `TOP`、`IFU`、`CTU`、`OOO`、`IEX`、`LSU`、`DTU`；这些名字不添加 `LinxCore` 前缀。
- Scala package 继续使用 `linxcore`，避免无关的全仓 package 迁移。
- 参数定义只放在 `chisel/src/main/scala/linxcore/params/`。
- 跨 box 的 payload、协议和 box IO 只放在 `chisel/src/main/scala/linxcore/top/interface/`；box 私有 entry、queue row、arbiter state 不得放入该目录。
- IFU、OOO、IEX 的主要宽度必须能以 2、4、6、8 四种 profile elaboration；默认 profile 为 W4。
- IEX 默认物理拓扑为 2 ALU、1 BRU、2 AGU、2 STD、1 system/multicycle queue、1 独立 CMD IQ。
- LSU 默认物理拓扑为 2 load pipe、2 store pipe。
- IFU 对 CTU 输出已经拼成 64-bit 容器的 16/32/48/64-bit 指令，不输出 `is_upper` 半指令协议。
- CTU 位于 IFU 和 OOO 之间，拥有模板展开与 Instruction Buffer；FENTRY、FEXIT 和模板块展开成规范化 uop 后进入 Instruction Buffer。
- OOO 范围固定为 D1、D2、D3、S1，以及 DEC、RENU、ROB、BROB；S1 后的 issue/read/execute 属于 IEX。
- RENU 必须保留 atag，并分别输出 ptag、ttag、utag；P 绝对索引和 T/U 相对索引使用两套不同的 SMAP/CMAP/MAPQ 机制。
- DTU 仅拥有 debug、trace 和性能观测；精确恢复、interrupt/trap 和 commit control 分布在 OOO、相关 box 与 TOP 路由中。
- `a.txt`、`b.txt`、`superscalarNPU` 只提供机制、拓扑和接口组织参考，
  不定义 Linx ISA 语义。机制进入 `docs/spec/` 时必须改写为项目自身条款，
  不保留来源文件名或外部架构叙事。
- NDF 只作为规范组织方法；不增加外部 NDF 工具依赖。
- 不新增第三方依赖。规范检查器只使用 Python 3 标准库。
- 每个状态只有一个 owner；迁移期 adapter 不得复制队列、map、ROB、cache、predictor 或恢复状态。
- 所有可反压 payload 在 `valid && !ready` 时保持稳定；状态改变只发生在 owner 的 `fire`。
- 跨周期、跨队列和异步返回必须携带完整、带 generation/wrap 的身份；只按 slot、低位 RID 或 PC 匹配必须失败关闭。
- 实现任务采用测试先行；每个任务完成窄 UT、邻接 IT、生成 RTL lint 和 Lore 格式提交后，才能进入下一个依赖任务。

---

## 1. Authority, References, and Decision Rules

### 1.1 Authoritative sources

发生冲突时按以下顺序裁决：

1. Linx ISA 仓内规范、生成 opcode metadata 和当前架构契约。
2. Linx QEMU 的架构可见行为与 LinxCoreModel 的微架构行为。
3. 本计划后续建立的 `docs/spec/` 稳定条款和已批准决策记录。
4. 当前 Chisel 中已经通过 exact-identity、recovery 和 cross-check 门禁的模块。
5. 外部参考资料。

任何外部参考与前四项冲突时，必须在 `docs/spec/decisions/` 记录“采纳、改写或拒绝”，禁止静默选择外部行为。

### 1.2 Reference identities

| Reference | Frozen identity | Allowed use |
|---|---|---|
| `/Users/zhoubot/Documents/a.txt` | SHA-256 `693ee46c63799d64982cc98ddc9dbf1405d64d9ad5bf9918429ac6d8ca880f62` | OOO、IEX、LSU 的队列、级间边界、rename、ROB、dispatch、replay 机制参考 |
| `/Users/zhoubot/Documents/b.txt` | SHA-256 `98b1cc8277088508cbe5ce6f3d5d39643b1252ab64e5ca9e154d0eebb2e93405` | IFU decoupled engines、预测、checkpoint、buffer 和恢复机制参考 |
| `hengliao1972/normative_language` | `main@09cfe646931183caee82dd913f77f516b82134df` | 条款 ID、精化层、外部投影、决策记录和验证覆盖组织方法 |
| `TerryMarvoloYuan/superscalarNPU` | `origin/main@3ae82dbc2bd68346255bfb6d8175495490ae2d3a` | 按方向划分接口组、TOP 只连线、CMD 独立接口、LDA/STA/STD 分流和接口变更 fanout 参考 |

### 1.3 Explicitly rejected copying

- 不复制 superscalarNPU 固定四路的 `lane0...lane3` 扁平端口；改用参数化 `Vec`。
- 不复制 superscalarNPU 的 32-bit `inst + is_upper` 跨槽协议；IFU 边界为完整 64-bit 容器。
- 不复制 IEX 直接控制 IFU 的第二条恢复路径；IEX/LSU 报告事件，OOO 裁决，TOP 分发。
- 不复制按目的 box 重复展开的 flush wire；使用统一 typed recovery transaction。
- 不复制 `a.txt`、`b.txt` 中的外部 ISA 指令、异常级、condition flags、
  barrier、exclusive monitor 或寄存器命名。
- 不把外部资料的固定资源数当作本项目常量；所有采纳值必须进入 `params/` 并通过本项目约束检查。

## 2. Target Source Layout

```text
chisel/src/main/scala/linxcore/
├── params/
│   ├── CoreParams.scala
│   ├── WidthParams.scala
│   ├── IFUParams.scala
│   ├── CTUParams.scala
│   ├── OOOParams.scala
│   ├── IEXParams.scala
│   ├── LSUParams.scala
│   ├── DTUParams.scala
│   ├── ParamProfiles.scala
│   └── ParamChecks.scala
├── top/
│   ├── TOP.scala
│   ├── EmitTOP.scala
│   └── interface/
│       ├── Identity.scala
│       ├── Packet.scala
│       ├── IFUCTU.scala
│       ├── CTUOOO.scala
│       ├── OOOIEX.scala
│       ├── IEXLSU.scala
│       ├── Commit.scala
│       ├── Recovery.scala
│       ├── TrapInterrupt.scala
│       ├── Memory.scala
│       ├── DTU.scala
│       ├── TOPIO.scala
│       ├── IFUIO.scala
│       ├── CTUIO.scala
│       ├── OOOIO.scala
│       ├── IEXIO.scala
│       ├── LSUIO.scala
│       └── DTUIO.scala
├── ifu/
│   ├── IFU.scala
│   ├── ISide.scala
│   ├── BSide.scala
│   ├── Prediction.scala
│   ├── FetchBuffer.scala
│   └── IFURecovery.scala
├── ctu/
│   ├── CTU.scala
│   ├── TemplateDecode.scala
│   ├── TemplateExpand.scala
│   └── InstructionBuffer.scala
├── ooo/
│   ├── OOO.scala
│   ├── DEC.scala
│   ├── RENU.scala
│   ├── PRename.scala
│   ├── TURename.scala
│   ├── ROB.scala
│   ├── BROB.scala
│   ├── Dispatch.scala
│   ├── CommitControl.scala
│   └── RecoveryControl.scala
├── iex/
│   ├── IEX.scala
│   ├── IssueFabric.scala
│   ├── RegisterFiles.scala
│   ├── ALUPipes.scala
│   ├── BRUPipe.scala
│   ├── AGUPipes.scala
│   ├── STDPipes.scala
│   ├── SystemMulticycleQueue.scala
│   ├── CMDIssueQueue.scala
│   └── TerminalFabric.scala
├── lsu/
│   ├── LSU.scala
│   ├── LoadPipes.scala
│   ├── StorePipes.scala
│   ├── LoadQueues.scala
│   ├── StoreQueues.scala
│   ├── MemoryDependency.scala
│   ├── Translation.scala
│   ├── L1D.scala
│   └── LSURecovery.scala
└── dtu/
    ├── DTU.scala
    ├── DebugControl.scala
    ├── TraceExport.scala
    └── PerformanceCounters.scala
```

Box 私有实现可以继续拆分成小文件；上表给出必须存在的 owner 文件和目录边界，不要求把现有经过验证的小模块重新合并成大文件。

本计划的 Task 10–20 受
[`2026-08-01-production-owner-atomic-cutover-design.md`](../specs/2026-08-01-production-owner-atomic-cutover-design.md)
约束。目标文件名定义 public box 和 owner 边界，不要求为了匹配文件名重写
已经通过 production 证据的内部机制。IFU、OOO、IEX、LSU 的后续工作必须
优先原地升级仓内 production mechanism；禁止创建第二个有状态 owner 再长期
并行验证。

每个 subsystem 在 public interface 切换前可以执行 behavior-preserving preparation，
但 preparation 不得改变 active boundary、不得增加第二个状态 owner。interface
cutover 必须在一个 loop 内同时完成 production mechanism 改造、所有调用者更新、
canonical box 接线、同层级 replacement evidence、旧入口删除、commit 和 push。
临时 adapter 只能存在于未提交工作树，必须无状态且不得进入 cutover commit。

## 3. Canonical Transaction Shapes

以下类型名构成唯一 canonical vocabulary。基础类型由 Task 3 创建；
`DecodedMemoryControl`、`MemoryOrderMeta`、load-attempt lifecycle、system
commit 和 engine-command 类型由 Task 13 在对应决策获批后追加。后续任务
不得另造同义类型：

```scala
final case class WidthParams(
  fetchWidth: Int,
  ctuOutputWidth: Int,
  decodeWidth: Int,
  renameWidth: Int,
  dispatchWidth: Int,
  issueWidth: Int,
  retireWidth: Int
)

class InstructionIdentity(p: CoreParams) extends Bundle
class RobIdentity(p: CoreParams) extends Bundle
class MemoryIdentity(p: CoreParams) extends Bundle
class PredictionMeta(p: CoreParams) extends Bundle
class FetchedInstruction(p: CoreParams) extends Bundle
class FetchedPacket(p: CoreParams) extends Bundle
class FrontEndOp(p: CoreParams) extends Bundle
class D1Packet(p: CoreParams) extends Bundle
class DecodedMemoryControl(p: CoreParams) extends Bundle
class DecodedUop(p: CoreParams) extends Bundle
class RenamedUop(p: CoreParams) extends Bundle
class MemoryOrderMeta(p: CoreParams) extends Bundle
class DispatchTxn(p: CoreParams) extends Bundle
class RobResolveTxn(p: CoreParams) extends Bundle
class LoadIssueTxn(p: CoreParams) extends Bundle
class StoreAddressTxn(p: CoreParams) extends Bundle
class StoreDataTxn(p: CoreParams) extends Bundle
class LoadResultTxn(p: CoreParams) extends Bundle
class LoadReissueTxn(p: CoreParams) extends Bundle
class LoadRepickTxn(p: CoreParams) extends Bundle
class LoadCancelTxn(p: CoreParams) extends Bundle
class RobNoflushTxn(p: CoreParams) extends Bundle
class SystemIssueTxn(p: CoreParams) extends Bundle
class CmdIssueTxn(p: CoreParams) extends Bundle
class RecoveryEvent(p: CoreParams) extends Bundle
class RecoveryPlan(p: CoreParams) extends Bundle
class CommitTxn(p: CoreParams) extends Bundle
class TrapEvent(p: CoreParams) extends Bundle
class TraceEvent(p: CoreParams) extends Bundle
```

Packet 统一使用 `count + Vec(maxWidth, payload)` 表示 slot0 最老的连续前缀；禁止同时维护另一份可产生洞的 valid mask。若外部 trace 需要 mask，只能从 `count` 派生。

`FrontEndOp` 是 CTU 到 OOO 的 tagged union：

```scala
object FrontEndOpKind extends ChiselEnum {
  val Encoded64, TemplateUop = Value
}

class FrontEndOp(p: CoreParams) extends Bundle {
  val kind = FrontEndOpKind()
  val parent = new FetchedInstruction(p)
  val templateOrdinal = UInt(8.W)
  val templateCount = UInt(8.W)
  val templateOpcode = UInt(p.opcodeWidth.W)
  val templateImmediate = UInt(p.dataWidth.W)
}
```

`Encoded64` 的 64-bit 指令容器、原始 2/4/6/8-byte 长度、预测、fault 和
architectural identity 都由 `parent` 携带，并在 OOO DEC 中完成 decode。
`TemplateUop` 由 CTU 用显式的 ordinal/count/opcode/immediate 描述，禁止在
`FrontEndOp` 中嵌套 `DecodedUop` 或递归解码 parent；它仍经过 D1/D2 的统一
合法性、ROB identity intent 和反压处理，随后与 encoded 路径汇合成唯一的
`DecodedUop`。本节的 Scala 名称必须与生成 interface manifest 一致；字段表
以 Bundle elaboration 为准，计划和 NDF 条款不得维护另一份冲突的宽度真值。

## 4. NDF-Style Contract Spine

本计划采用受限 NDF profile，不依赖外部 CLI：

```text
docs/spec/
├── ndf.yaml
├── 00-charter/{scope.md,glossary.md}
├── 10-architecture/{top.md,ownership.md,pipeline.md}
├── 20-behavior/{ifu.md,ctu.md,ooo.md,iex.md,lsu.md,dtu.md,recovery.md,commit-trap.md}
├── 30-interfaces/{top-external.md,ifu-ctu.md,ctu-ooo.md,ooo-iex.md,iex-lsu.md,recovery.md,commit.md,dtu.md,memory.md}
├── 40-constraints/{parameters.md,resources.md,timing.md}
├── 50-verification/{interface-conformance.md,module-coverage.md,benchmark-acceptance.md}
├── decisions/
├── open/
└── refs/{superscalar-npu.md,ndf.md}
```

条款前缀固定为：

```text
ARC-TOP     IFC-TOP-EXT  IFC-IFU-CTU  IFC-CTU-OOO  IFC-OOO-IEX
IFC-IEX-LSU IFU          CTU          OOO          IEX
LSU         DTU          REC          CMT          PRM
VER         D            Q            REF-SNPU     REF-NDF
```

层次固定为 L0 intent、L1 observable contract、L2 mechanism、L3 executable check。每个 `level=must layer=L1` 条款在主链启用前必须被至少一个 `VER-*` 条款通过 `verifies=` 覆盖。

### 4.1 Interface contracts as an NDF refinement graph

接口设计同时维护三种结构：

1. **Tree:** 每个跨 box 边界只有一个 `docs/spec/30-interfaces/*.md`
   归属文件，每个 payload 和完整 box IO 只有一个
   `linxcore/top/interface/*.scala` 定义位置。
2. **Graph:** L2 Bundle/协议条款通过 `refines=` 指向 L1 可观察契约，
   通过 `depends-on=` 指向 identity、parameter、recovery 或 ownership 条款；
   L3 检查通过 `verifies=` 指回被覆盖条款。跨边界宽度和资源耦合使用
   `couples-with=`，不在散文中隐式表达。
3. **History:** 接口条款 ID 跨文件移动和标题重命名保持稳定。破坏性字段、
   方向或时序变更必须在同一提交中更新 Bundle、所有 producer/consumer、
   generated manifest、验证条款和 `decisions/` 记录；被替代条款使用
   `superseded-by`，不得静默删除或复用 ID。

每个接口文件必须按以下精化层组织：

| Layer | Required content | Acceptance evidence |
|---|---|---|
| L1 observable contract | producer、consumer、顺序、原子 fire、反压、恢复后的可观察结果 | adjacent-box IT |
| L2 mechanism | 唯一 Bundle 类型、方向、`count + Vec` 形状、完整 identity、owner、prepare/apply 处理 | Bundle shape UT、protocol assertions |
| L3 executable check | W2/W4/W6/W8 elaboration、stall stability、identity round trip、stale response rejection | ScalaTest/chiseltest + generated manifest check |

每个 L1 公共接口契约都必须记录以下 NDF 风格事实。这些内容是语义要求，
不是第二份手工维护的端口表：

| 契约事实 | 必须明确的决策 |
|---|---|
| 稳定身份 | 永久 clause ID、状态、`since`，以及被替代时的 `superseded-by` |
| 端点 | 精确 producer、consumer、方向和唯一 contract-home 文件 |
| 时钟域 | 时钟域、reset 置位/释放语义，以及输入是否为 raw/asynchronous |
| 传输 | handshake、原子 `fire`、顺序、反压保持、取消和重试行为 |
| 所有权 | 接受时分配或修改的状态，以及负责该变化的唯一 owner |
| 架构控制 | recovery、commit、trap/interrupt 和 debug 影响，包括适用处的 prepare/apply |
| 身份 | 拒绝 stale work 所需的完整 transaction identity 和 generation/wrap 字段 |
| 参数耦合 | 决定 lane 或位宽的中央参数；固定协议事实不得写成 `option` 条款 |
| 证据 | L2 Bundle/owner 投影，以及至少一个 L3 elaboration、assertion、UT 或 adjacent-box IT |

初始 canonical core 只使用一个同步 clock/reset domain。未来任何 raw 或
asynchronous 外部输入都必须在 `IFC-TOP-EXT-*` 中显式标注，由 TOP 边界上
唯一、明确归属的 conditioner 终止，并在进入 box owner 前完成验证。
仅有 `Flipped`、`Valid` 或 `Decoupled` 声明不能替代 CDC 契约。

L1/L2 文档不得复制 Scala 字段表。Scala Bundle elaboration 是字段名、位宽、
嵌套和方向的可执行来源；生成的 JSON/Markdown manifest 是其可评审投影。
NDF 条款规定语义、所有权和协议不变量，并链接 manifest 和测试证据。

两份外部参考承担不同职责：superscalarNPU 只帮助确定 typed transaction、
方向分组和 TOP 连线的代码组织；NDF 只帮助确定接口契约如何被稳定 ID、精化边、
版本历史和可执行证据追踪。前者不能定义 Linx payload 语义，后者不能替代 Scala
Bundle 或 RTL。最终唯一真值仍是 Linx `docs/spec` 条款、中央参数、可 elaboration
Bundle 以及由 Bundle 生成的 manifest。

### 4.2 Interface boundary matrix

Task 3 先冻结八个 box/owner 边界，再允许 box 实现依赖它们；
Task 18 在唯一 `TOP` 切换时冻结 `top-external.md` 组合边界：

| Contract home | Producer -> consumer | Data plane | Control/return plane | Required identity |
|---|---|---|---|---|
| `top-external.md` | Platform/harness <-> TOP | `TOPIO` composition of instruction/data memory, interrupt/debug ingress, commit/trap/trace egress | per-channel handshake plus declared clock/reset/CDC policy; no architectural state in TOP | each leaf retains the complete identity required by its home contract |
| `ifu-ctu.md` | IFU -> CTU | fixed-64-bit `FetchedPacket` continuous prefix | CTU backpressure; OOO-authored recovery routed through TOP | fetch generation, `stid`, block/instruction identity, prediction checkpoint |
| `ctu-ooo.md` | CTU -> OOO | `D1Packet[FrontEndOp]` after Instruction Buffer retention | OOO admission backpressure and recovery pruning | original instruction identity plus expansion member identity |
| `ooo-iex.md` | OOO -> IEX | atomic `DispatchTxn` routed by uop class | completion, fault and redirect events return to OOO | full ROB generation, `bid/rid/uid`, renamed tags |
| `iex-lsu.md` | IEX -> LSU | separate load-address, store-address and store-data transactions | load result, memory fault, retry/replay event | ROB identity plus LSID generation and split/access member |
| `recovery.md` | OOO -> all owners | typed `RecoveryPlan` prepare/apply phases | per-owner acknowledgement and stale-work rejection | recovery generation and precise cutoff identity |
| `commit.md` | OOO -> owners/TOP | ordered `CommitTxn` prefix and side-effect authorization | completion/ack only where architecturally required | ROB generation and architectural parent identity |
| `dtu.md` | boxes/TOP -> DTU | loss-tolerant trace/performance events | debug requests enter through typed TOP/OOO control path | event sequence plus originating instruction identity when applicable |
| `memory.md` | IFU/LSU <-> TOP memory adapters | independent instruction/data request and response channels | credit/backpressure/error return | transaction generation; never address-only matching |

所有 Decoupled payload 都遵守 `valid && !ready` 全字段稳定；`fire` 之前
receiver 不得消费或分配状态。数据流与 recovery/commit/debug 控制流使用不同
transaction 类型，禁止用复用 opcode 或 sideband boolean 创建第二套隐式协议。
IEX/LSU 只能报告事件给 OOO，不能形成直达 IFU 的恢复控制路径。

### 4.3 Parameter options and interface coupling

主要宽度使用 NDF `option` 条款记录 `default=4`、`explore=2,4,6,8`，
并以 `couples-with` 显式连接 IFU transfer、CTU output、OOO
decode/rename/dispatch、IEX issue 和 packet manifest。执行单元数、LSU pipe
数、队列深度及 identity 位宽是独立参数，但任何组合必须在生成接口之前通过
`ParamChecks`。已选默认值与仍允许 elaboration 的集合都保留在条款中，
避免把当前默认拓扑误写成不可配置常量。

### 4.4 IFU -> CTU -> OOO refinement chain

前端接口不以一张扁平字段表作为设计源，而是按可观察行为、实现机制和验证证据
形成一条可追踪的精化链：

| Refinement layer | IFU -> CTU | CTU -> OOO |
|---|---|---|
| L1 observable contract | `IFC-IFU-CTU-001`: IFU 交付按程序序排列的完整指令前缀；stall 时保持稳定；恢复后不泄漏被清理指令 | `IFC-CTU-OOO-001`: CTU 交付保序的 encoded/template-op 前缀；OOO admission 反压不得丢失或重复成员 |
| L2 mechanism | `MEC-IFU-CTU-001`: `FetchedPacket(count, entries)`，每项携带完整 64-bit 容器、原始长度、fetch/prediction/fault identity；不携带临时 ROB/BROB allocation | `MEC-CTU-OOO-001`: `D1Packet(count, entries)`，成员为 `FrontEndOp` tagged union；模板展开成员保留 parent/member identity |
| L3 executable check | `IFUISideSpec`、`IFUCTUIntegrationSpec`、W2/W4/W6/W8 manifest、stall/recovery assertions | `CTUSpec`、`CTUOOOIntegrationSpec`、W2/W4/W6/W8 admission、identity round trip 和 recovery pruning |

状态所有权沿该链只向前移动一次：IFU `FetchBuffer` 拥有已拼接
`FetchedInstruction`；CTU `InstructionBuffer` 拥有已分类/展开的
`FrontEndOp`；OOO D1/D2 才执行统一 decode、uop classification 和
ROB/BROB identity admission。预测 checkpoint、fetch fault 和 instruction
identity 必须穿过 CTU 保持原值；CTU 不得预分配 ROB/BROB，也不得根据 IEX
资源限制裁剪 uop。恢复使用独立 typed transaction，不把 flush、redirect 或
commit 掩码塞进数据 payload。

### 4.5 Interface change work order

Task 3 之后的每个接口相关 implementation loop 必须以一份可复现工作单开始：

1. 记录 `docs/spec` 所在 LinxCore commit 作为 spec baseline，并列出本任务
   直接修改或实现的 L1/L2 clause ID。
2. 读取这些 clause 在 `refines`、`depends-on`、`couples-with` 和
   `verifies` 图上的一跳邻域；禁止只看 Scala Bundle 后猜测语义。
3. 在写 RTL 前列出 producer、consumer、TOP wiring、parameter check、
   clock/reset/CDC policy、recovery/commit handling、generated manifest 和 adjacent-box test 的
   fanout。任何一项不适用都要写出理由。
4. 新字段或新通道必须先归入现有 contract home；若改变 observable
   contract，则同一提交增加 decision record。替换既有条款时保留原 ID 并用
   `superseded-by` 指向新条款，不静默删除。
5. 提交前运行 NDF checker、Bundle/manifest check 和被影响的 adjacent-box
   integration test，并在 loop ledger 中记录 clause delta 与剩余未覆盖边界。

接口评审使用“语义 diff”而不只看文本 diff：逐项检查条款新增/修改/替代、
typed edge 变化、L1 coverage 变化、Bundle leaf 变化及 endpoint fanout。
测试发现的歧义先落为 `Q-*` open clause，再由 decision record 关闭；不得只在
测试或会话记录中保留未决设计。

## 5. Execution Tasks

### Task 1: Establish the contract spine and reference boundary

**Files:**
- Create: `docs/spec/ndf.yaml`
- Create: `docs/spec/00-charter/scope.md`
- Create: `docs/spec/00-charter/glossary.md`
- Create: `docs/spec/10-architecture/top.md`
- Create: `docs/spec/10-architecture/ownership.md`
- Create: `docs/spec/10-architecture/pipeline.md`
- Create: `docs/spec/50-verification/contract-spine.md`
- Create: `docs/spec/refs/superscalar-npu.md`
- Create: `docs/spec/refs/ndf.md`
- Create: `tools/spec/check_ndf_profile.py`
- Create: `tests/test_ndf_profile.py`
- Create: `docs/chisel/mainline-loop-ledger.md`

**Interfaces:**
- Consumes: 本计划、仓内架构文档和两个结构方法 reference identity。
- Produces: 稳定 clause ID、owner 词汇表、外部参考投影规则和 `check_ndf_profile.py` CI 入口。

- [ ] **Step 1: Write failing checker tests**

测试至少构造重复 ID、悬空 `[[ID]]`、缺少 `kind/level/layer/status`、L1 MUST 无 `verifies=`、错误 reference hash 五种失败样例，以及一个通过样例。

```python
def test_rejects_unverified_l1_must(self):
    result = run_checker(fixture("unverified_l1_must"))
    self.assertNotEqual(result.returncode, 0)
    self.assertIn("missing verifies edge", result.stderr)
```

- [ ] **Step 2: Run the failing tests**

Run: `python3 -m unittest tests.test_ndf_profile -v`

Expected: FAIL because `tools/spec/check_ndf_profile.py` does not exist.

- [ ] **Step 3: Implement the minimal checker and contract files**

Checker CLI 固定为
`python3 tools/spec/check_ndf_profile.py docs/spec [--verify-local-references]`。
内部保持 clause parse、ID/link、L1 coverage 和 reference identity
validation 可独立测试，不把 Markdown 或 YAML parser 暴露为仓外 API。

只实现本计划使用的 metadata 子集；禁止引入 YAML/Markdown parser 依赖。
普通 CI 只检查 reference revision/SHA-256 的格式与必填性，不要求
本地 checkout 存在。开发机可显式传 `--verify-local-references`，
此时核对可用 checkout 中冻结的 identity。

- [ ] **Step 4: Run checker tests and the live spec check**

Run:

```bash
python3 -m unittest tests.test_ndf_profile -v
python3 tools/spec/check_ndf_profile.py docs/spec
```

Expected: PASS；输出 clause 数、L1 MUST 数、已覆盖数、开放 `Q-*` 数和 reference identity 检查结果。

- [ ] **Step 5: Commit**

Commit intent: `Make the core contract independently reviewable before RTL moves`

### Task 2: Create the parameter hierarchy and W2/W4/W6/W8 profiles

**Files:**
- Create: `chisel/src/main/scala/linxcore/params/CoreParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/WidthParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/IFUParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/CTUParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/OOOParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/IEXParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/LSUParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/DTUParams.scala`
- Create: `chisel/src/main/scala/linxcore/params/ParamProfiles.scala`
- Create: `chisel/src/main/scala/linxcore/params/ParamChecks.scala`
- Create: `chisel/src/test/scala/linxcore/params/CoreConfigurationSpec.scala`
- Create: `docs/spec/40-constraints/parameters.md`
- Create: `docs/spec/50-verification/parameter-profiles.md`
- Modify: `chisel/src/main/scala/linxcore/common/CoreParams.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/OooParams.scala`

**Interfaces:**
- Consumes: `PRM-*` clauses.
- Produces: `CoreParams`, `ParamProfiles.W2/W4/W6/W8`, module-specific parameter records and centralized cross-parameter validation.

- [ ] **Step 1: Write failing profile tests**

Tests必须检查四个 profile，默认 W4 拓扑，以及非法 width=3、IFU width 小于 CTU 输出、OOO dispatch 小于连续 D3 prefix、load/store pipe count 为零等失败情况。

```scala
Seq(2, 4, 6, 8).foreach { width =>
  it(s"accepts W$width") {
    ParamChecks.validate(ParamProfiles.forWidth(width))
  }
}
```

- [ ] **Step 2: Run the failing test**

Run: `bash tools/chisel/run_chisel_tests.sh --only CoreConfigurationSpec`

Expected: FAIL because `linxcore.params` does not exist.

- [ ] **Step 3: Implement the parameter records**

核心形状固定为：

```scala
final case class IEXParams(
  issueWidth: Int = 4,
  aluPipes: Int = 2,
  bruPipes: Int = 1,
  aguPipes: Int = 2,
  stdPipes: Int = 2,
  systemMulticycleQueues: Int = 1,
  cmdIssueQueues: Int = 1
)

final case class LSUParams(
  loadPipes: Int = 2,
  storePipes: Int = 2,
  loadQueueEntries: Int = 16,
  storeQueueEntries: Int = 16
)
```

把现有 `common.CoreParams` 和 `ooo.OooParams` 改成临时 value-only
type/constructor adapter；adapter 只转换参数，不能拥有状态。每个 subsystem
cutover 必须在最后一个调用者迁移后立即删除对应 adapter；Task 20 只允许清理
无 active caller 的 residual adapter。

- [ ] **Step 4: Run profile tests and compile**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only CoreConfigurationSpec
bash tools/chisel/build_chisel.sh
```

Expected: PASS for W2/W4/W6/W8；所有非法配置在 elaboration 前给出明确 `require` 消息。

- [ ] **Step 5: Commit**

Commit intent: `Keep every width and resource choice explicit at one boundary`

### Task 3: Create typed TOP interfaces and generated manifests

**Files:**
- Create: every file listed under `top/interface/` in Section 2.
- Create: `chisel/src/test/scala/linxcore/top/interface/TopInterfaceSpec.scala`
- Create: `chisel/src/test/scala/linxcore/top/interface/InterfaceManifestSpec.scala`
- Create: `chisel/src/main/scala/linxcore/top/interface/EmitInterfaceManifest.scala`
- Create: `tools/chisel/render_top_interface_manifest.py`
- Create: `docs/spec/30-interfaces/*.md`
- Create: `docs/chisel/generated/top-interface-manifest.json`
- Create: `docs/chisel/generated/top-interface-manifest.md`
- Modify: `docs/spec/50-verification/interface-conformance.md`

**Interfaces:**
- Consumes: `CoreParams`, Section 3 transaction names, `IFC-*` clauses.
- Produces: all cross-box payloads, all box IO classes, generated interface manifest and protocol assertions.

- [ ] **Step 1: Write L1/L2 interface clauses and the failing coverage check**

为 Section 4.2 的八个 box/owner 边界分别建立唯一归属文件。
每个文件至少包含一个
L1 observable contract、一个 `refines=` 该契约的 L2 mechanism 条款，
以及预先声明的 L3 `VER-*` 覆盖边。先运行 NDF checker，确认缺少对应
Bundle/manifest evidence 时 Task 3 仍未满足验收条件。

- [ ] **Step 2: Write failing Bundle shape and protocol tests**

Tests覆盖四种 width、64-bit instruction、continuous-prefix count、complete identities、CMD 独立通道、2 LDA/2 STA/2 STD 通路、recovery prepare/apply，以及 stall 时 payload 稳定。

```scala
test(new InterfaceHoldProbe(ParamProfiles.W4)) { dut =>
  enqueueOnePacket(dut)
  dut.io.out.ready.poke(false.B)
  val held = snapshot(dut.io.out.bits)
  dut.clock.step(3)
  snapshot(dut.io.out.bits) shouldBe held
}
```

- [ ] **Step 3: Run the failing tests**

Run: `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec`

Expected: FAIL because interface classes do not exist.

- [ ] **Step 4: Implement transaction and box IO types**

每个 transaction 只定义一次。`IFUIO`、`CTUIO`、`OOOIO`、`IEXIO`、`LSUIO`、`DTUIO` 组合这些 transaction；禁止复制字段形成 sender/receiver 两套类。

每个端口必须能追溯到一个 Section 4.2 contract home。producer 与 consumer
复用同一个 payload 类型，只在 IO 组合处改变方向。所有 packet 使用
`count + Vec(maxWidth, payload)`；recovery、commit、trace、memory
response 使用各自 transaction，完整 identity 不得被 adapter 截断。

- [ ] **Step 5: Generate and verify manifests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec
bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec
python3 tools/chisel/render_top_interface_manifest.py --check
python3 tools/spec/check_ndf_profile.py docs/spec
```

Expected: PASS；JSON/Markdown 与 Bundle elaboration 一致，手工修改生成文件会被 `--check` 拒绝。

- [ ] **Step 6: Review the NDF trace and interface fanout**

对每个 L1 `IFC-*` 条款检查至少一个 L2 `refines=` 和一个 L3
`verifies=`；检查每个 manifest endpoint 都有 contract home，且没有
sender/receiver 同义 Bundle、无直接 IEX/LSU-to-IFU 控制、无 slot-only
或 address-only 返回匹配。接口变更 fanout 必须覆盖所有 producer、
consumer、TOP wiring、manifest 和验证条款。

- [ ] **Step 7: Commit**

Commit intent: `Give every box one typed contract and one direction of authority`

### Task 4: Build CTU and the Instruction Buffer boundary

**Files:**
- Create: `chisel/src/main/scala/linxcore/ctu/CTU.scala`
- Create: `chisel/src/main/scala/linxcore/ctu/TemplateDecode.scala`
- Create: `chisel/src/main/scala/linxcore/ctu/TemplateExpand.scala`
- Create: `chisel/src/main/scala/linxcore/ctu/InstructionBuffer.scala`
- Create: `chisel/src/test/scala/linxcore/ctu/CTUSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ctu/InstructionBufferSpec.scala`
- Create: `docs/spec/20-behavior/ctu.md`

**Interfaces:**
- Consumes: `Decoupled[FetchedPacket]` from IFU, typed recovery plan.
- Produces: `Decoupled[D1Packet]` to OOO and CTU trace events.

- [ ] **Step 1: Write failing CTU tests**

Cover ordinary pass-through, FENTRY expansion, FEXIT expansion, template producing more uops than one output packet, no cross-packet reorder, buffer full backpressure, recovery pruning, and W2/W4/W6/W8 packetization.

- [ ] **Step 2: Run failing tests**

Run: `bash tools/chisel/run_chisel_tests.sh --only CTUSpec`

Expected: FAIL because CTU does not exist.

- [ ] **Step 3: Implement CTU**

`TemplateExpand` emits `FrontEndOpKind.TemplateUop`; ordinary instructions emit `Encoded64`。Instruction Buffer owns all retained entries and preserves identity/prediction metadata through backpressure.

- [ ] **Step 4: Run CTU and interface gates**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only CTUSpec
bash tools/chisel/run_chisel_tests.sh --only InstructionBufferSpec
bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec
```

Expected: PASS with no combinational IFU-ready to OOO-ready path through CTU.

- [ ] **Step 5: Commit**

Commit intent: `Make template expansion a retained boundary instead of frontend glue`

### Task 5: Consolidate the IFU I-SIDE and fixed-64-bit delivery

**Files:**
- Create: `chisel/src/main/scala/linxcore/ifu/IFU.scala`
- Create: `chisel/src/main/scala/linxcore/ifu/ISide.scala`
- Create: `chisel/src/main/scala/linxcore/ifu/FetchBuffer.scala`
- Create: `chisel/src/test/scala/linxcore/ifu/IFUISideSpec.scala`
- Create: `docs/spec/20-behavior/ifu.md`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/frontend/ISide*.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala`

**Interfaces:**
- Consumes: I-side memory request/response, redirect/recovery, prediction response.
- Produces: fixed-64-bit `FetchedPacket` to CTU and prediction request to B-SIDE.

- [ ] **Step 1: Write failing I-SIDE tests**

Cover 2/4/6/8 delivery, 2/4/6/8-byte input instruction lengths, cross-line assembly, ITLB miss, I-cache miss/refill, partial packet, output hold under CTU stall, scoped recovery, stale refill rejection and independent STID progress.

- [ ] **Step 2: Run failing tests**

Run: `bash tools/chisel/run_chisel_tests.sh --only IFUISideSpec`

- [ ] **Step 3: Move proven mechanisms behind `IFU`**

Reuse existing ITLB/L1I/miss-table/line-assembler logic by relocating or instantiating it under the new owner。Do not copy it into a second stateful implementation.

- [ ] **Step 4: Run focused and generated RTL gates**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only IFUISideSpec
bash tools/chisel/run_chisel_tests.sh --only CTUSpec
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

- [ ] **Step 5: Commit**

Commit intent: `Make fixed-width instruction delivery independent of fetch geometry`

### Task 6: Consolidate B-SIDE prediction and IFU recovery

**Files:**
- Create: `chisel/src/main/scala/linxcore/ifu/BSide.scala`
- Create: `chisel/src/main/scala/linxcore/ifu/Prediction.scala`
- Create: `chisel/src/main/scala/linxcore/ifu/IFURecovery.scala`
- Create: `chisel/src/test/scala/linxcore/ifu/IFUPredictionSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ifu/IFURecoverySpec.scala`
- Create: `chisel/src/test/scala/linxcore/ifu/IFUCTUIntegrationSpec.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/frontend/BSide*.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/frontend/IfuRedirectArbiter.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridge.scala`

**Interfaces:**
- Consumes: prediction request, OOO redirect/commit/training.
- Produces: prediction response and corrected fetch direction to I-SIDE.

- [ ] **Step 1: Write failing predictor/recovery tests**

Cover provider rank, BTB/TAGE/BIM/RAS/loop history ownership, checkpoint restore, correction before final prediction seal, mispredict redirect, redirect priority, repeated stall, stale training rejection and no direct IEX-to-IFU control.

- [ ] **Step 2: Run failing tests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only IFUPredictionSpec
bash tools/chisel/run_chisel_tests.sh --only IFURecoverySpec
```

- [ ] **Step 3: Implement B-SIDE and recovery composition**

All backend facts arrive through OOO-authored interfaces。IEX branch result may be present inside an OOO completion/recovery event but cannot connect directly to IFU.

- [ ] **Step 4: Run IFU+CTU integration**

Run: `bash tools/chisel/run_chisel_tests.sh --only IFUCTUIntegrationSpec`

Expected: sustained traffic, retained backpressure, redirect and scoped cleanup pass in W2/W4/W6/W8.

- [ ] **Step 5: Commit**

Commit intent: `Keep prediction speculative while recovery authority remains singular`

### Task 7: Build OOO D1/D2 decode and ROB identity admission

**Files:**
- Create: `chisel/src/main/scala/linxcore/ooo/OOO.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/DEC.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/OOODecodeSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/CTUOOOIntegrationSpec.scala`
- Create: `docs/spec/20-behavior/ooo.md`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/ooo/OooD1Decode.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/ooo/OooD2Stage.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/ooo/OooOpcodeRecipeTable.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/backend/D1DecodeRenameROBIngress.scala`

**Interfaces:**
- Consumes: `Decoupled[D1Packet]` from CTU.
- Produces: retained D2 decoded group carrying complete virtual ROB allocation
  intent；physical ROB/BROB publication 仍由后续唯一 owner 完成。

- [ ] **Step 1: Write failing decode tests**

Cover normal 16/32/48/64 encodings represented in 64 bits, template uop bypass, bid/rid/opcode, source/destination valid, atag, prediction metadata, illegal opcode trap, prefix acceptance and D1/D2 backpressure.

- [ ] **Step 2: Run failing tests**

Run: `bash tools/chisel/run_chisel_tests.sh --only OOODecodeSpec`

- [ ] **Step 3: Implement DEC and D1/D2 stage records**

DEC outputs exactly one common `DecodedUop` shape for encoded and CTU-expanded
inputs。D2 只有在整个连续 lane prefix 能被原子保留时才生成完整 RID/group/member
intent；D2 preview 不发布 physical ROB row，也不分配 BROB。已验证的 D3 allocator
保持 RID tail 的唯一可变 owner，BROB 在后续任务绑定 BID/generation。任何 legacy
adapter 都不得把 `CoreParams` 的 16-bit epoch/RID/BROB/resident generation
截断到旧的 8-bit 默认值。

- [ ] **Step 4: Run opcode parity and decode integration**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only OOODecodeSpec
bash tests/test_opcode_parity.sh
bash tools/chisel/run_chisel_tests.sh --only CTUOOOIntegrationSpec
```

- [ ] **Step 5: Commit**

Commit intent: `Normalize every instruction form before speculative ownership begins`

### Task 8: Build RENU with separate P and T/U rename machines

**Files:**
- Create: `chisel/src/main/scala/linxcore/top/interface/OOOD2D3.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/RENU.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/PRename.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/TURename.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/RENUSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/RENUAtomicSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/TURenameSequenceSpec.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/OooParams.scala`
- Modify: `chisel/src/main/scala/linxcore/params/OOOParams.scala`
- Modify: `chisel/src/main/scala/linxcore/params/ParamChecks.scala`
- Modify: `chisel/src/main/scala/linxcore/top/interface/OOOIEX.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/ooo/OooPRename.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/ooo/OooTURename.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/rename/*.scala`

**Interfaces:**
- Consumes: D2 `DecodedUop` prefix, commit releases, recovery plan.
- Produces: D3 `RenamedUop` prefix with atag plus ptag/ttag/utag and reservation claims.

`OOOD2D3.scala` is the canonical public internal-stage contract and defines
`RENUD2D3IO`, `RenameCommitReleaseEntry`, and `RenameCommitReleaseTxn`.
Each D3 lane preserves the Task-7 virtual ROB/group/member intent,
generation-qualified P/T/U rename history, separate T/U physical-tag and
MapQ-sequence generations, and wrap-qualified T/U sequences. Commit release
is a continuous, cross-domain all-or-none prefix; it is per uop and can carry
every destination history row, including zero-destination boundary uops. It
does not reuse the single-destination external `CommitEntry` shape.

RENU is two-phase. D2 acceptance may retain one exact provisional P/T/U lease
per STID, but SMAP, CMAP, MapQ and visible physical ownership change only on the
common D3 publication fire. Task 10 later joins that fire with ROB/BROB and
dispatch readiness. Task 8 MUST NOT advance the physical RID tail, bind BID or
resident generations, allocate issue/LSU state, or choose commit/trap order.
Recovery prepare cannot revoke a D3 transaction already presented under
backpressure: it waits for that target transaction to fire, while an unrelated
presented STID and unrelated admissions remain eligible.

- [x] **Step 1: Write failing rename tests**

Cover 24 architectural P registers, free-list exhaustion, same-cycle dependency forwarding, SMAP/CMAP/MAPQ updates, T/U relative source lookup, sequential T/U allocation, block checkpoint, commit, wrap, replay and scoped recovery.

Also cover W2/W4/W6/W8 behavioral publication, unequal P MapQ/T/U MapQ/ROB and
T/U physical capacities, generation-qualified stale release rejection,
provisional cancellation, stable D3 backpressure, and recovery that rebuilds P
SMAP from CMAP plus survivors while rewinding only the target STID's T/U suffix.
The backpressure test MUST hold one target-STID D3 row, present recovery prepare,
prove `prepare.ready == false` and bit-for-bit D3 stability, then prove prepare
acceptance only after that row fires. A separate two-STID test MUST cancel an
unpublished target lease while an unrelated row remains presented.

- [x] **Step 2: Run failing tests**

Run: `bash tools/chisel/run_chisel_tests.sh --only RENUSpec`

- [x] **Step 3: Implement independent rename owners**

P uses free-list physical allocation；T/U use ordered relative allocation。Shared wrapper performs one atomic prefix preparation and one common publication but never merges their internal maps. Central parameters own PTag and local-sequence generation widths plus independent queue/capacity checks; adapters cannot fall back to historical eight-bit generations.

- [x] **Step 4: Run unequal-capacity and width profile tests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only RENUSpec
bash tools/chisel/run_chisel_tests.sh --only CoreConfigurationSpec
bash tools/chisel/run_chisel_tests.sh --only OooParamsSpec
bash tools/chisel/run_chisel_tests.sh --only CoreParamsInterfaceClosureSpec
```

Expected: tests include unequal P MapQ/TU MapQ/ROB capacities and W2/W4/W6/W8.

- [x] **Step 5: Commit**

Commit intent: `Preserve absolute and relative register semantics with separate owners`

### Task 9: Consolidate ROB, BROB, commit and precise recovery authority

**Files:**
- Create: `chisel/src/main/scala/linxcore/ooo/ROB.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/BROB.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/CommitControl.scala`
- Create: `chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/rob/*.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/bctrl/BROB.scala`
- Migrate mechanisms from: `chisel/src/main/scala/linxcore/recovery/*.scala`

**Interfaces:**
- Consumes: D2 allocation, D3 rename update, IEX/LSU completion and fault events, interrupt requests.
- Produces: commit transactions, trap selection, predictor training, recovery prepare/apply, ROB/BROB releases.

- [x] **Step 1: Write failing owner tests**

Cover grouped ROB allocation, per-STID BROB, exact completion, slot reuse rejection, in-order commit, early D3 completion for no-operand/boundary uops, interrupt at precise boundary, trap priority, branch recovery, LSU recovery, all-owner prepare barrier and apply acknowledgement.

- [x] **Step 2: Run failing tests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec
bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec
```

- [x] **Step 3: Implement the single recovery plan owner**

`RecoveryEvent` is retained at producers；OOO resolves one `RecoveryPlan` containing exact kill set and redirect。TOP distributes the same plan object; no box recomputes global age.

- [x] **Step 4: Run ROB, BROB and recovery cross-gates**

Run:

```bash
bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only
bash tools/chisel/run_chisel_brob_order_state_probe.sh
bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec
bash tests/test_rob_bookkeeping.sh
```

- [x] **Step 5: Commit**

Commit intent: `Make one ROB decision govern commit, traps, and precise cleanup`

### Task 10: Close Task 9 and freeze the production-owner cutover manifest

**Files:**
- Modify: `chisel/src/main/scala/linxcore/ooo/ROB.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/BROB.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala`
- Modify: `chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala`
- Create: `docs/chisel/production-owner-manifest.md`
- Create: `tools/chisel/check_production_owner_manifest.py`
- Create: `tests/test_production_owner_manifest.py`
- Modify: `docs/chisel/mainline-loop-ledger.md`

**Interfaces:**
- Consumes: reviewed Task-9 ROB/BROB/commit/recovery owners and all live
  Chisel emitters.
- Produces: a checked one-owner/call-site/deletion manifest that every later
  atomic cutover must update.

- [x] **Step 1: Close the current Task-9 RED cases**

Require `ROB` to consume and stamp allocator-authored BROB identity on every
active D3 lane, including initially unbound lanes, while `BROB` stores only the
resulting ROB-prepared resident identity. Require `RecoveryControl` to drain a
target prepared beat outside `PrepareTargets` without acknowledgement so a
beat held before Prepare cannot survive abort into a same-transaction retry.

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec
bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec
```

Expected: both suites pass with zero failures; no Task-10 dispatch change is
mixed into this repair.

- [x] **Step 2: Write the owner-manifest checker RED**

Fixtures must reject duplicate ROB/rename/IQ/LSU/cache/recovery owners,
unknown emitters, missing production evidence, stateful adapters and legacy
deletion targets with active callers.

Run: `python3 -m unittest tests.test_production_owner_manifest -v`

Expected: FAIL because the checker and manifest do not exist.

- [x] **Step 3: Record exact owner and call-site decisions**

For every IFU、CTU、OOO、IEX、LSU、DTU state category record：canonical
owner、production mechanism files、public box、active callers、verification
fixtures、cutover task and files deleted at cutover。Classify `Reduced*`、
`*Probe` and old `LinxCore*Top` as non-production entry points.

- [x] **Step 4: Prove the checked baseline**

Run:

```bash
python3 -m unittest tests.test_production_owner_manifest -v
python3 tools/chisel/check_production_owner_manifest.py
python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec
git diff --check
```

- [x] **Step 5: Commit and push before starting Task 11**

Commit intent: `Freeze one production owner before changing another boundary`

The branch must report zero commits ahead of its configured upstream after the
push. If push is unavailable, stop before Task 11 and record the exact remote
blocker in the ledger.

### Task 11: Atomically cut canonical OOO onto production D3/S1 mechanisms

**Files:**
- Modify: `chisel/src/main/scala/linxcore/ooo/OOO.scala`
- Create or modify: `chisel/src/main/scala/linxcore/ooo/Dispatch.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooD3ReservationAllocator.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooDispatch.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooHierarchicalFreeSlotSelect.scala`
- Modify: `chisel/src/test/scala/linxcore/ooo/OOODispatchSpec.scala`
- Modify: `chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala`
- Modify: `docs/chisel/production-owner-manifest.md`
- Delete: displaced old D3/S1 public wrappers and tests named by the manifest.

**Interfaces:**
- Consumes: canonical RENU D3 prefix, ROB/BROB prepared identities and
  side-effect-free IEX/LSU credit previews.
- Produces: classed canonical `DispatchTxn` traffic plus exact early-completion
  publication through the Task-9 ROB owner.

- [ ] **Step 1: Freeze the D3/S1 mapping and write RED tests**

Map every field from the proven reservation/dispatch mechanisms to
`D3RenameGroup` and `DispatchTxn`. Cover continuous-prefix admission, atomic
multi-owner credit, split STA+STD, CMD separation, no-op early completion,
suffix retry and recovery-coincident suppression.

Run: `bash tools/chisel/run_chisel_tests.sh --only OOODispatchSpec`

- [ ] **Step 2: Prepare mechanisms without adding owners**

Refactor the existing selectors/classifiers in place. They may calculate
previews but may not own ROB、BROB、rename、commit or global recovery state.
Do not instantiate `OooO3RenameCoordinator` inside canonical `OOO`.

- [ ] **Step 3: Perform the single OOO cutover**

In one working-tree transaction update `OOO`、all IEX/LSU credit callers、
tests、emitters and docs to the canonical payloads. Any temporary payload
adapter must be removed before the GREEN run.

- [ ] **Step 4: Delete displaced OOO entry points and prove one owner**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only OOODispatchSpec
bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected: CTU through canonical D3/S1 passes W2/W4/W6/W8; the active graph has
one ROB/BROB/rename/recovery owner and no old O3 public wrapper.

- [ ] **Step 5: Commit and push**

Commit intent: `Cut production dispatch onto one canonical OOO owner graph`

### Task 12: Prepare production IEX mechanisms without changing the live boundary

**Files:**
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexIssue*.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIex*Pipeline.scala`
- Modify in place: `chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala`
- Create: `chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala`
- Modify: `docs/chisel/production-owner-manifest.md`

**Interfaces:**
- Consumes: existing production `OooParams`/grouped-S1 behavior and the frozen
  canonical `CoreParams`/`DispatchTxn` mapping.
- Produces: behavior-preserving IEX internals ready for one public-interface
  cutover in joint Task 15; no new public IEX owner is activated in this task.

- [ ] **Step 1: Characterize the production baseline**

Add tests for per-class IQ residency、same-STID age、cross-STID fairness、P/T/U
atomic reads、retry、two ALU、one BRU、two AGU、two STD、terminal retention and
scoped recovery. Record which production child owns each state category.

- [ ] **Step 2: Run the production-mechanism baseline**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline
```

- [ ] **Step 3: Refactor only private boundaries**

Centralize value-only parameter conversion and split oversized private files
only where needed. Preserve the current public boundary for this task; create
no `IEX` state owner and no persistent canonical-to-old payload adapter.

- [ ] **Step 4: Prove behavior preservation and topology**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexPhysicalProfileSpec
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
python3 tools/chisel/check_production_owner_manifest.py
```

- [ ] **Step 5: Commit and push**

Commit intent: `Prepare production IEX owners without creating a second core`

### Task 13: Close the canonical OOO-IEX prerequisites without a live cutover

Task-12 review proved the private IEX mechanisms, but the first public-cutover
RED exposed three missing contracts: canonical D1 memory controls, a live
`OooMemoryOrderAllocator`/ROB recovery-tail join, and an IEX-LSU attempt
lifecycle.  This task closes those prerequisites while the public `IEX` box
remains absent.  The one-time public switch moves to the joint Task-15 cutover,
after LSU has prepared the matching LIQ replay owner.

**Files:**
- Create: `docs/spec/decisions/D-IEX-LSU-LOAD-001.md`
- Create: `docs/spec/decisions/D-IEX-ROB-RESOLVE-001.md`
- Create: `docs/spec/decisions/D-IEX-SYS-CMD-001.md`
- Modify: `chisel/src/main/scala/linxcore/top/interface/CTUOOO.scala`
- Modify: `chisel/src/main/scala/linxcore/top/interface/OOOIEX.scala`
- Modify: `chisel/src/main/scala/linxcore/top/interface/IEXLSU.scala`
- Modify: `chisel/src/main/scala/linxcore/top/interface/IEXIO.scala`
- Modify: `chisel/src/main/scala/linxcore/top/interface/TOPIO.scala`
- Modify: `chisel/src/main/scala/linxcore/top/interface/EmitInterfaceManifest.scala`
- Modify: `chisel/src/test/scala/linxcore/top/interface/TopInterfaceSpec.scala`
- Modify: `chisel/src/test/scala/linxcore/top/interface/InterfaceManifestSpec.scala`
- Modify: `docs/chisel/generated/top-interface-manifest.json`
- Modify: `docs/chisel/generated/top-interface-manifest.md`
- Modify: `docs/spec/30-interfaces/ooo-iex.md`
- Modify: `docs/spec/30-interfaces/iex-lsu.md`
- Create: `docs/spec/30-interfaces/top-external.md`
- Modify: `docs/chisel/linxcore-chisel-microarchitecture-improvement-design.md`
- Modify: `docs/chisel/linxcore-chisel-ooo-improvement-design.md`
- Modify: `docs/chisel/linxcore-chisel-iex-improvement-design.md`
- Modify: `chisel/src/main/scala/linxcore/ooo/DEC.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/OooMemoryOrderAllocator.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/OOOD3S1Graph.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooPcBuffer.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/ROB.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/CommitControl.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexIssue*.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexTerminal*.scala`
- Rename in place: `chisel/src/main/scala/linxcore/ooo/OooIexLoadLiqAllocAdapter.scala`
  to `chisel/src/main/scala/linxcore/iex/LoadIexIssuePipeline.scala`
- Rename in place: `chisel/src/main/scala/linxcore/ooo/OooIexLoadTerminalMetadata.scala`
  to `chisel/src/main/scala/linxcore/iex/OooIexLoadTerminalMetadata.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexCanonicalLoadOwnership.scala`
- Modify in place: `chisel/src/main/scala/linxcore/ooo/OooIexBruPipeline.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/OOOMemoryOrderIntegrationSpec.scala`
- Create: `chisel/src/test/scala/linxcore/ooo/OOOPcBufferIntegrationSpec.scala`
- Create: `chisel/src/test/scala/linxcore/iex/IEXPrivateIngressSpec.scala`
- Create: `chisel/src/test/scala/linxcore/iex/IEXPrivateTerminalSpec.scala`
- Modify: `docs/chisel/production-owner-manifest.md`
- Modify: `docs/superpowers/specs/2026-08-01-production-owner-atomic-cutover-design.md`

**Interfaces:**
- Consumes: Task-11 canonical `DecodedUop`/`DispatchTxn`, Task-12 production
  IEX owners and the common canonical `RecoveryPlan`.
- Produces: frozen memory/order/attempt contracts and private production owners
  that are directly composable by Task 15; it produces no public `IEX` module
  and changes no live TOP boundary.

- [x] **Step 1: Approve and record the three load-bearing decisions**

Decision gate closed on 2026-08-02 with option A for IEX/LSU load-attempt
ownership and the resolve/system/CMD contracts below.

Write the decision records with stable NDF identities and supersession rules.
The recommended contract is:

1. `D-IEX-LSU-LOAD-001`: OOO allocates the full program-order `LSID`, the
   load-only `LID`, the store-only `SID`, and the `YOST`/`YOLD` older-memory
   boundaries; IEX allocates a non-reused transaction for each retained memory
   uop on the Dispatch-to-IQ acceptance edge, shares it across one store's
   STA/STD children, and allocates the initial load attempt on that same edge;
   LSU owns LIQ residency and
   every later reissue、repick and attempt rebind. No physical LIQ/STQ row, pipe lane
   or ROB generation may synthesize any of these identities.
2. `D-IEX-ROB-RESOLVE-001`: one `RobResolveTxn` resolves one ROB member.
   `destinationIndex/value` describe destination ordinal zero only when it is
   architecturally useful; every P/T/U destination still writes and wakes in
   the same terminal rendezvous, while ROB stores resolved/trap state rather
   than a multi-destination value array.
3. `D-IEX-SYS-CMD-001`: system/multicycle and CMD remain separate resident
   queues. Unsupported operations stay resident/fail closed. OOO grants an
   exact head-only `RobNoflushTxn`; a system side effect executes through
   `SystemIssueTxn`, while CMD leaves IEX only through `CmdIssueTxn` on the TOP
   external projection. Neither class may be replaced by immediate-value
   resolve or an always-ready sink. This
   decision refines `IFC-TOP-EXT` and must name the external engine-command
   endpoint before Task 15.

The load-issue decision fixes these event rules:

- IEX allocates one transaction for each retained memory uop, with one store
  transaction shared by its STA/STD children. It also allocates the initial
  attempt for a retained load, before any LSU sink fire. The
  candidate identity remains bit-stable under backpressure and the non-resetting
  serial advances once; cancellation never reuses it.
- `LoadReissueTxn` is LSU-to-IEX `Decoupled` for a LIQ load that still requires
  address translation. It carries the previous and next complete identities;
  ROB identity、transaction and `lsid` remain equal, attempt generation
  advances exactly once, and `pipeId` may change only to the route retained by
  this transition. Fire atomically rebinds the LIQ and retained IEX metadata,
  then reuses the load-address pipe. The colliding normal IEX issue remains unpicked
  or is retained for a later pick.
- `LoadRepickTxn` is LSU-to-IEX `Decoupled` for a LIQ load whose physical
  address is already available. It carries the same current/next identity rule,
  selects and retains its `pipeId` on fire, and uses the load-result pipe
  without consuming the address-translation resource. IEX accepts only a
  matching resident load attempt.
- `LoadCancelTxn` is LSU-to-IEX `Decoupled` and names the exact current attempt whose
  speculative wakeup cannot produce a result. It clears dependent source-ready
  state and cancels dependent pipes, but it does not remove LIQ residency or
  allocate an attempt. The later reissue or repick performs the rebind.
- Recovery termination is not a point-to-point load-cancel protocol. OOO's
  `RecoveryPlan` fences the affected STID during Prepare and prunes the exact
  IEX metadata and LSU residency set only on Apply; Abort preserves both.
- stale、unknown and skipped-generation lifecycle inputs are consumed once into
  a typed rejection event so they cannot deadlock a shared channel; they change
  no owner state and cannot produce resolve、wakeup or memory traffic.
- `LoadResultTxn` must match the latest retained identity after every accepted
  rebind. Return-pipe index, LIQ slot and ROB generation are never substitutes
  for that comparison.
- recovery Prepare fences lifecycle traffic only for the affected STID and
  computes the exact killed identity set without mutation. Apply emits one
  common recovery mutation that prunes both IEX metadata and LSU LIQ state;
  Abort changes neither. Transaction and attempt serial allocators never roll
  back, and peer-STID LIQ rows survive.

The unique IEX identity owner is `OooIexIssue`, where a logical memory uop
first becomes retained. `LoadIexIssuePipeline`, renamed in place from
`OooIexLoadLiqAllocAdapter` and moved under the IEX package in the same commit,
is a non-resident bridge that copies the retained identity into LIQ. Task 13
updates every private caller and leaves no alias, wrapper or old class. Core
reset initializes the transaction value/generation and initial-attempt serials;
recovery Prepare/Apply/Abort never rewinds them. The
owner manifest gains state key `memory_transaction_and_initial_attempt_serial`
with private-mechanism cutover Task 13 and public-box activation Task 15.

The ROB resolve decision fixes every case:

| Case | `RobResolveTxn.fire` | ROB transition | Destination fields | RF/wakeup | Trap/recovery |
|---|---|---|---|---|---|
| ordinary, destination 0 valid | once at terminal rendezvous | unresolved to resolved | `valid=true,index=0,value=writeback(0)` | every valid destination fires atomically | none |
| ordinary, no destination | once at terminal rendezvous | unresolved to resolved | `valid=false,index=0,value=0` | none | none |
| multiple destinations | one member resolve | unresolved to resolved | ordinal 0 only | every valid P/T/U destination fires atomically | none |
| early-resolved | no terminal resolve | marked resolved by ROB admission | all zero/not emitted | none | none |
| precise trap | once at terminal rendezvous | resolved with trap | `valid=false,index=0,value=0` | none | exact `TrapEvent` |
| recovery-killed before fire | never | cleared by recovery apply | not emitted | none | recovery event/plan only |

ROB retains resolved/trap state only and does not treat `value` as a rename or
retirement data owner.

The system/CMD decision fixes ownership and backpressure:

- `RobNoflushTxn` carries the exact ROB/instruction identity and is
  valid only while that unresolved member is the ROB head, all input and
  legality checks have completed without a pending trap, every older effect is
  drained and no recovery prepare targets its STID. It is authorization, not
  a second resolve or retained queue owner. The transaction names one member
  proven safe for the requested side effect; it is not the scalar
  `NOFLUSHRID` boundary itself.
- `RobNoflushReadyTxn` is the exact NFRDY proof from the execution and
  side-effect owners. Its transaction, instruction and ROB identities match
  the per-STID resident head; a stale proof drains without authorization.
  Fired suppression remains attached to that STID and exact resident head, so
  peer-STID arbitration cannot reauthorize it.
- `SystemIssueTxn` carries the same exact identity, opcode and
  immediate, and is legal only for a no-destination recipe whose side effect is
  owned by commit control. IEX keeps the system row resident and the request
  stable until the matching permit and CommitControl ready are present; system
  issue fire, side-effect application and the IEX no-value resolve fire
  form one atomic rendezvous. Any destination-producing system opcode fails
  closed until a response-bearing ISA contract is added.
- `CmdIssueTxn` carries exact ROB/instruction identity, opcode and source
  values. IEX asserts it only while the matching head-only permit is valid and
  keeps the CMD row resident until the TOP external command sink accepts it;
  noflush authorization consumption、CMD issue fire and no-value resolve form one
  atomic rendezvous. Recovery before that fire cancels the permit and preserves
  external side-effect precision. An absent sink backpressures the CMD queue
  rather than dropping or auto-completing the command.
- recovery prepare has priority over issuing a new permit or consuming a
  system/CMD request. Apply removes a killed resident system/CMD row and any
  unconsumed permit; abort preserves both. Once a head-authorized system or CMD
  atomic rendezvous has fired, the external/commit side effect and ROB
  resolve are exactly once and cannot be undone by a younger recovery.

Do not begin Steps 2-5 until these three decisions are accepted.  If the
accepted alternative differs, update the Bundle schemas and tests in this task
before implementation; never preserve two protocol variants.

- [x] **Step 2: Add canonical memory control and order payloads test-first**

Add `DecodedMemoryControl` to `DecodedUop`; its fields are `valid`、`isLoad`、
`isStore`、address mode、access bytes、sign extension、offset、index transform、
address/data source masks、writeback controls and normalized `requestCount`.
Add `MemoryOrderMeta` to
`DispatchTxn`; it carries only stable logical order (`requestCount`、
`firstLsid`、`firstLid`、`firstSid`、`YOST` and `YOLD`), never
`MemoryTransactionIdentity`、attempt generation or physical pipe identity.

Rename the displaced `LoadRequestTxn` to `LoadIssueTxn` and extend `IEXLSUIO`
with `LoadReissueTxn`、`LoadRepickTxn` and `LoadCancelTxn` from Step 1. Add
`SystemIssueTxn` to the OOO-IEX return direction, `RobNoflushTxn` to its
OOO-to-IEX direction, and `CmdIssueTxn` to `IEXIO`/the external TOP projection. Add interface
manifest assertions proving exact field widths, directions, W2/W4/W6/W8 vector
sizes, stable IDs and that `MemoryIdentity` fields are never derived from ROB,
RID, queue slot or lane ordinal.

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec
bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec
python3 tools/chisel/render_top_interface_manifest.py --check
python3 tools/spec/check_ndf_profile.py docs/spec
```

- [x] **Step 3: Restore the unique OOO memory-order owner**

Migrate `OooMemoryOrderAllocator` in place to canonical D2/D3 payloads and
connect it to `OOOD3S1Graph`. DEC supplies normalized demand; allocator prepare,
ROB/BROB/RENU preparation and dispatch credit participate in one D3 fire.
Publication stores memory-before/after snapshots in ROB; recovery restores the
same per-STID tail from the canonical suffix plan. Do not add a serial counter
to `OooDispatch`. Add the exact `RobNoflushReadyTxn` NFRDY proof and extend the
integration fixture with a head-only system/CMD permit, denial for a non-head,
stale proof or recovery-fenced member, exact permit identity stability under
backpressure, per-STID exactly-once suppression and permit withdrawal before
any side effect when recovery prepare wins.

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only OOOMemoryOrderIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec
bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec
```

- [x] **Step 4: Convert private IEX ingress and terminal owners in place**

Change the private IEX admission path to consume classed canonical dispatch
directly. Preserve `OooIexIssue` as the only IQ owner and
`OooIexOperandFiles` as the only P/T/U data/readiness owner; delete the old
grouped-S1 lease, dispatch-release dependency and global P/T/U readiness
shadow. A single accepted `StoreDispatchTxn` creates STA/STD children
atomically with one logical memory-order identity.

Preserve `OooPcBuffer` as the only PC-base residency owner and convert it in
place to the canonical `CoreParams` and `RecoveryPlan` contracts. Its prepare
readiness joins RENU、ROB、BROB、memory-order and dispatch readiness on the one
D3 publication edge; its commit release joins the one commit-control edge;
prepare is side-effect free、Apply removes only the exact suffix and Abort
preserves it. `DispatchTxn` carries one typed base/index/offset/generation
`PcBufferIndexOffset`. IEX retains that PC buffer index and PC offset in the IQ and uses the
existing readyless PC-buffer read during I1→I2. Do not carry a complete PC
through the IQ、instantiate an IEX-side PC table or add a retained compatibility
adapter around the owner.

Retain existing ALU/BRU/AGU and terminal state. Change terminal publication in
place to canonical `RobResolveTxn` and `RecoveryEvent`; RF writes、wakeup、trace、
resolve and any recovery event still rendezvous on one terminal fire.
Recovery consumers use canonical `RecoveryPlan` directly and match apply/abort
with the complete transaction, never through an `OooResidencyRecoveryPlan`
projection. Private fixtures must cover initial identity allocation exactly
once, blocked rebind stability, atomic LSU/IEX rebind, launch of only the latest
attempt, common-edge recovery cancel/prune, abort preservation, peer-STID
survival, ordinal-zero/no-destination/trap/killed resolve cases and
head-authorized system/CMD side-effect fire.

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only IEXPrivateIngressSpec
bash tools/chisel/run_chisel_tests.sh --only IEXPrivateTerminalSpec
bash tools/chisel/run_chisel_tests.sh --only OOOPcBufferIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexTerminalFabricSpec
```

- [x] **Step 5: Prove the prerequisite graph and commit**

Verify no public `IEX` module and no new IQ、RF、terminal、recovery or
memory-order owner exists. W2/W4/W6/W8 must elaborate the private production
graph; W4 remains 2 ALU、1 BRU、2 AGU、2 STD、1 system/multicycle queue and
1 CMD queue.

Run:

```bash
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
git diff --check
```

Commit intent: `Close the canonical prerequisites for the joint IEX LSU cutover`

### Task 14: Prepare LSU mechanisms and resolve the two-pipe contract

**Files:**
- Modify in place: `chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala`
- Modify in place: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Modify in place: existing `STQ*`、`SCB*`、LIQ、ResolveQ、MDB、MissQ、
  refill、load-return and `ScalarL1D` mechanism files.
- Create: `chisel/src/test/scala/linxcore/lsu/LSUMechanismSpec.scala`
- Modify: `docs/chisel/production-owner-manifest.md`
- Modify: `docs/spec/40-constraints/parameters.md`

**Interfaces:**
- Consumes: existing `ScalarLSUIO` behavior and the Task-13 approved
  `IEXLSUIO` issue/reissue/repick/cancel contract plus `LSUIO` mapping.
- Produces: LSU internals ready for one public-boundary cutover in
  Task 15, with independent physical queue, ROB identity and full-LSID sizes;
  no public `LSU` or `IEX` module becomes live in this task.

- [x] **Step 1: Freeze and test the mechanism baseline**

Cover two STA、two STD、two load issues、typed initial attempt binding、exact
reissue/repick rebind and load cancel、STQ/SCB commit、LIQ/MDB/replay、miss/refill、load
return、L1D update and typed recovery. Add unequal-capacity W2/W4/W6/W8
elaboration with 16 STQ rows、8 ROB entries and 40-bit LSID.

- [x] **Step 2: Resolve the three-load-pipe assumption before cutover**

Remove any helper requirement that derives a three-pipe topology
from the old external-forwarding shape. The W4 contract is exactly two load
pipes; queue depth、return-pipe count and ROB identity capacity remain
independent parameters.

- [x] **Step 3: Refactor private LSU mechanisms only**

Centralize value-only parameter conversion and canonical identity helpers
without changing the live `ScalarLSU` public boundary and without adding a
second STQ、SCB、LIQ、MDB、L1D or recovery owner.

- [x] **Step 4: Prove behavior preservation**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only LSUMechanismSpec
bash tools/chisel/run_chisel_tests.sh --only ScalarLSU
bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadPath
bash tools/chisel/run_chisel_tests.sh --only ScalarL1D
bash tools/chisel/run_chisel_store_non_flush_gate_probe.sh
bash tools/chisel/run_chisel_brob_store_range_state_probe.sh
python3 tools/chisel/check_production_owner_manifest.py
```

The two-pipe forwarding IT uses one bounded simulation with explicit reset
between five scenarios. Its generated directory falls from 243 MiB to 47 MiB
while retaining exact result、hard recovery、typed recovery and BID fail-closed
coverage.

- [x] **Step 5: Commit and push**

Commit intent: `Prepare the LSU without cloning its state owners`

### Task 15: Atomically cut OOO-IEX-LSU onto the canonical typed boundaries

**Files:**
- Create: `chisel/src/main/scala/linxcore/iex/IEX.scala`
- Create: `chisel/src/main/scala/linxcore/lsu/LSU.scala`
- Create: `chisel/src/test/scala/linxcore/iex/IEXIssueSpec.scala`
- Create: `chisel/src/test/scala/linxcore/iex/IEXPipesSpec.scala`
- Create: `chisel/src/test/scala/linxcore/iex/IEXTerminalSpec.scala`
- Create: `chisel/src/test/scala/linxcore/iex/OOOIEXIntegrationSpec.scala`
- Create: `chisel/src/test/scala/linxcore/lsu/LSUStoreSpec.scala`
- Create: `chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala`
- Create: `chisel/src/test/scala/linxcore/lsu/IEXLSUIntegrationSpec.scala`
- Create: `chisel/src/test/scala/linxcore/iex/OOOIEXLSUActivationSpec.scala`
- Create: `chisel/src/main/scala/linxcore/iex/EmitOOOIEXLSUActivationProbe.scala`
- Create: `tools/chisel/ooo_iex_lsu_activation_tb.cpp`
- Create: `tools/chisel/run_ooo_iex_lsu_activation_probe.sh`
- Create: `docs/spec/20-behavior/iex.md`
- Create: `docs/spec/20-behavior/lsu.md`
- Modify: `chisel/src/main/scala/linxcore/ooo/OOO.scala`
- Modify: `docs/chisel/production-owner-manifest.md`
- Modify in place: production LSU mechanisms prepared by Task 14.
- Modify in place: production IEX mechanisms prepared by Task 13.
- Delete: old `ScalarLSU` outer public boundary after its production children
  are composed by `LSU`.
- Delete: displaced `ReducedScalar*` and `Reduced*` LSU owners、old IEX/LSU
  wrappers、emitters、tests and docs named by the owner manifest after every
  active caller moves in this same cutover.

**Interfaces:**
- Consumes: canonical classed `DispatchTxn`/`StoreDispatchTxn`, exactly 2
  canonical load-address、2 store-address、2 store-data transactions, the
  approved attempt lifecycle、OOO commit authorization、lower-memory responses
  and canonical recovery.
- Produces: one public `IEX` and one public `LSU`; retained `LoadResultTxn`、
  atomic `RobResolveTxn`/RF write/wakeup、store/load resolve or fault、
  committed memory requests、reservation credits and recovery acknowledgement.

- [x] **Step 1: Write both public boundaries RED**

IEX RED covers stable classed-dispatch backpressure、exact identity retention、
CMD/system independence、load-attempt rebind、two ALU、one BRU、two AGU、two
STD and one atomic completion/RF/wakeup/recovery terminal fire. LSU RED covers
address/data merge、pair operations、two-pipe independence、forwarding、
unresolved older store、MDB replay、miss/refill、stale response rejection、
lane-local backpressure、committed-store visibility and scoped recovery.
Add collisions for `rebind + cancel`, stale lifecycle events, affected-STID
recovery with a peer STID live, `CMD stalled + older BRU recovery`, system/CMD
head-permit withdrawal, and exactly-once head side-effect fire.

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only IEXIssueSpec
bash tools/chisel/run_chisel_tests.sh --only IEXPipesSpec
bash tools/chisel/run_chisel_tests.sh --only IEXTerminalSpec
bash tools/chisel/run_chisel_tests.sh --only LSUStoreSpec
bash tools/chisel/run_chisel_tests.sh --only LSULoadSpec
```

- [x] **Step 2: Perform one joint public-owner change**

Create state-free composition shells `IEX` and `LSU`; change OOO, the prepared
private IEX graph, the prepared private LSU graph and every live caller directly
to canonical `OOOIEXIO`、`IEXLSUIO` and `LSUIO`. `IEX` owns no duplicate IQ、
RF、FU、terminal or recovery state. `LSU` owns no duplicate STQ、SCB、LIQ、MDB、
cache or recovery state. Initial identity binding and every rebind/launch/cancel
must use the approved typed lifecycle; remove every temporary conversion Bundle
before GREEN.

- [x] **Step 3: Run pre-deletion cutover promotion**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only LSUStoreSpec
bash tools/chisel/run_chisel_tests.sh --only LSULoadSpec
bash tools/chisel/run_chisel_tests.sh --only IEXIssueSpec
bash tools/chisel/run_chisel_tests.sh --only IEXPipesSpec
bash tools/chisel/run_chisel_tests.sh --only IEXTerminalSpec
bash tools/chisel/run_chisel_tests.sh --only OOOIEXIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only IEXLSUIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only OOOIEXLSUActivationSpec --jobs 1 --artifact-budget-bytes 8589934592
bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec --jobs 1 --artifact-budget-bytes 8589934592
bash tools/chisel/run_chisel_store_non_flush_gate_probe.sh
bash tools/chisel/run_chisel_brob_store_range_state_probe.sh
bash tools/chisel/run_ooo_iex_lsu_activation_probe.sh
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected: W2/W4/W6/W8 elaborate the one live OOO-IEX-LSU graph. W4 contains
2 ALU、1 BRU、2 AGU、2 STD、1 system/multicycle queue、1 CMD queue、2 load
pipes and 2 store pipes. No active `ReducedScalar*` IEX owner or displaced LSU
owner remains.

The emitted-RTL activation probe is a retained subsystem harness, not a second
core. It drives a bounded canonical uop program through the emitted public
graph, compares completion、RF write、branch target and memory transactions to
literal reference results with zero mismatch, and requires nonzero ALU、BRU、
AGU、STD、load、store、system and CMD activation. It also observes independent
backpressure, performs one exact rebind, and proves one affected-STID recovery
while a peer STID progresses. Old-chain deletion is forbidden unless this
generated-RTL bounded-reference evidence is GREEN.

- [x] **Step 4: Delete both displaced chains and rerun closure**

Only after every Step-3 gate is GREEN, delete the old reduced issue/RF/ALU/
completion graph, old IEX public wrappers, the old `ScalarLSU` public shell and
reduced LSU owners after `rg` proves no active caller. Retain production IEX
helpers and production STQ/SCB/LIQ/MDB/cache helpers even when a historical
private class name remains; naming alone is not authority to rewrite state.

Rerun the generated-RTL reference and structural closure on the post-deletion
tree:

```bash
bash tools/chisel/run_ooo_iex_lsu_activation_probe.sh
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
git diff --check
```

- [x] **Step 5: Commit (push intentionally deferred for review)**

Commit intent: `Switch OOO IEX and LSU once and retire both reduced chains`

**Completion report (2026-08-03):** The atomic cutover is complete. The
post-deletion public suites passed for issue, pipes, terminal, store, load,
OOO-IEX, and IEX-LSU boundaries. `OOOIEXLSUActivationSpec` passed 8/8 cases
under the 8 GiB artifact gate (`4,511,675,564` bytes), and the retained emitted
RTL harness reproduced the exact bounded-reference counters with
`mismatch_count=0`. The production-owner checker reports 25 closed owners, 24
classified emitters, and 6 declared adapters; its 45 tests pass. Chisel compile,
Verilator lint, shell syntax checks, module-index link closure, legacy-symbol
source closure, and `git diff --check` are green. The displaced reduced IEX/LSU
chains and legacy top/probe callers were deleted only after the promotion gates;
generated artifacts were removed before commit. The commit is local only so the
review gate can inspect the atomic change before any push.

### Task 16: Close translation, cache, lower memory and recovery inside the live LSU

**Files:**
- Create or modify: `chisel/src/main/scala/linxcore/lsu/Translation.scala`
- Modify in place: `chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala`
- Create or modify: `chisel/src/main/scala/linxcore/lsu/LSURecovery.scala`
- Modify: `chisel/src/main/scala/linxcore/lsu/LSU.scala`
- Create: `chisel/src/test/scala/linxcore/lsu/LSUMemorySpec.scala`
- Create: `chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala`
- Modify: `docs/chisel/production-owner-manifest.md`
- Delete: residual displaced DTLB/cache/lower-memory/recovery wrappers named by
  the manifest after equivalent evidence passes.

**Interfaces:**
- Consumes: canonical virtual requests、lower-memory responses、commit and the
  common prepare/prepared/apply/abort recovery transaction.
- Produces: physical memory requests、precise access faults、retained responses
  and LSU quiescence from the already-live Task-15 LSU box.

- [ ] **Step 1: Write memory-system RED tests**

Cover DTLB hit/miss/refill、access classification、alignment、PMP/PMA、
cacheable/device routing、LR/SC identity、fence drain、miss retry、response
generation mismatch and complete recovery quiescence.

- [ ] **Step 2: Extend the live LSU without creating a side implementation**

Reuse `ScalarL1D` and existing miss/refill/lower-memory mechanisms in place.
Translation replay and data replay remain separate. Recovery quiescence covers
queues、pipe reservations、MissQ/refill、load returns、store drain and all
outstanding lower-memory transactions.

- [ ] **Step 3: Delete residual displaced memory wrappers**

Update all callers in this loop and remove wrappers that no longer own a live
path. Do not create `L1D.scala` as a copy of `ScalarL1D`; rename only if the
same commit updates every caller and preserves the owner.

- [ ] **Step 4: Run LSU promotion**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only LSUMemorySpec
bash tools/chisel/run_chisel_tests.sh --only LSUIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only IEXLSUIntegrationSpec
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

- [ ] **Step 5: Commit and push**

Commit intent: `Close memory and recovery inside the one live LSU owner`

### Task 17: Integrate distributed trap, interrupt, commit and DTU

**Files:**
- Create: `chisel/src/main/scala/linxcore/dtu/DTU.scala`
- Create: `chisel/src/main/scala/linxcore/dtu/DebugControl.scala`
- Create: `chisel/src/main/scala/linxcore/dtu/TraceExport.scala`
- Create: `chisel/src/main/scala/linxcore/dtu/PerformanceCounters.scala`
- Create: `chisel/src/test/scala/linxcore/dtu/DTUSpec.scala`
- Create: `chisel/src/test/scala/linxcore/top/RecoveryIntegrationSpec.scala`
- Create: `docs/spec/20-behavior/dtu.md`
- Create: `docs/spec/20-behavior/recovery.md`
- Create: `docs/spec/20-behavior/commit-trap.md`
- Modify: `docs/chisel/production-owner-manifest.md`
- Delete: displaced debug/trace/system-control wrappers after canonical DTU
  observation and request paths pass equivalent evidence.

**Interfaces:**
- Consumes: commit/trace events, debug requests, interrupt inputs and distributed recovery acknowledgement.
- Produces: debug halt/resume requests, trace stream and performance counters.

- [ ] **Step 1: Write failing distributed-control tests**

Cover precise interrupt selection, synchronous fault priority, debug halt at commit boundary, trace non-backpressure, recovery prepare/apply fanout, one missing ack stall, unrelated-STID progress and no DTU ownership of commit or recovery state.

- [ ] **Step 2: Run failing tests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only DTUSpec
bash tools/chisel/run_chisel_tests.sh --only RecoveryIntegrationSpec
```

- [ ] **Step 3: Implement DTU and distributed control wiring**

OOO selects the architectural action；each box applies local cleanup；TOP routes；DTU observes and may request debug state changes through typed interfaces.

Integrate existing production trace、counter and system-control mechanisms in
place. DTU may not become a second commit/recovery state machine; temporary
old-to-new control adapters must be absent from the Task-17 commit.

- [ ] **Step 4: Run trace schema gates**

Run:

```bash
bash tests/test_trace_schema_and_mem.sh
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_tests.sh --only DTUSpec
python3 tools/chisel/check_production_owner_manifest.py
```

- [ ] **Step 5: Delete displaced control wrappers, commit and push**

Commit intent: `Keep debug observable without creating a second control machine`

### Task 18: Assemble TOP and the natural ELF harness

**Files:**
- Create: `chisel/src/main/scala/linxcore/top/TOP.scala`
- Create: `chisel/src/main/scala/linxcore/top/EmitTOP.scala`
- Create: `chisel/src/test/scala/linxcore/top/TOPSpec.scala`
- Create: `chisel/src/test/scala/linxcore/top/TOPWidthProfilesSpec.scala`
- Create: `tools/chisel/top_natural_tb.cpp`
- Create: `tools/chisel/run_top_natural.sh`
- Create: `tools/chisel/run_dual_benchmark_gate.sh`
- Create: `tests/test_top_natural.py`
- Modify: `docs/spec/30-interfaces/top-external.md`
- Modify: `chisel/src/main/scala/linxcore/top/interface/EmitInterfaceManifest.scala`
- Modify: `chisel/src/test/scala/linxcore/top/interface/TopInterfaceSpec.scala`
- Modify: `chisel/src/test/scala/linxcore/top/interface/InterfaceManifestSpec.scala`
- Modify: `tools/chisel/render_top_interface_manifest.py`
- Modify: `docs/chisel/generated/top-interface-manifest.json`
- Modify: `docs/chisel/generated/top-interface-manifest.md`
- Modify: `docs/spec/10-architecture/top.md`
- Modify: `docs/spec/50-verification/interface-conformance.md`
- Modify: `docs/spec/50-verification/benchmark-acceptance.md`
- Modify: `docs/chisel/production-owner-manifest.md`
- Modify: `tools/chisel/check_production_owner_manifest.py`
- Delete: `chisel/src/main/scala/linxcore/top/LinxCore*Top.scala`
- Delete: corresponding old top emitters、harness entry points and top-only
  tests after the canonical TOP smoke and bounded comparison pass.

**Interfaces:**
- Consumes: all box IOs, external memory, interrupt and debug pins.
- Produces: emitted `TOP.sv`, the manifested external `TOPIO` contract,
  commit/trace output, UART/finisher and natural-run manifests.

- [ ] **Step 1: Write failing TOP connectivity tests**

Tests instantiate W2/W4/W6/W8 and assert every cross-box field is driven once, no box IO is tied off in W4, no direct IEX-to-IFU control exists, TOP contains no Queue/Reg-based architectural owner and all trace channels are non-blocking. Add a failing manifest assertion that `TOPIO` is present as the external composition endpoint and every leaf resolves to `IFC-TOP-EXT-*`; that clause may depend on the existing memory、commit and DTU leaf contracts without copying their payload definitions. The contract test must also assert the single core clock/reset domain, preserve the Task-13 `CmdIssueTxn` ready/valid endpoint without an always-ready sink, and reject an unqualified raw/asynchronous input.

- [ ] **Step 2: Run failing tests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only TOPSpec
bash tools/chisel/run_chisel_tests.sh --only TOPWidthProfilesSpec
bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec
bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec
```

- [ ] **Step 3: Implement TOP and harness**

`TOP` instantiates exactly one IFU、CTU、OOO、IEX、LSU、DTU。Harness owns ELF memory loading、UART、finisher 和 manifest formatting but no instruction/commit oracle.

This is the one active top-emitter cutover. Update build、CI、Verilator、trace
and benchmark callers、the owner checker inventory、the machine-readable
production entry-point row and its exact evidence in the same loop. The owner
manifest must refer to this as Task 18. No old top may remain callable after
the cutover commit.

- [ ] **Step 4: Run elaboration, lint and harness self-tests**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only TOPSpec
bash tools/chisel/run_chisel_tests.sh --only TOPWidthProfilesSpec
bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec
bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec
python3 tools/chisel/render_top_interface_manifest.py --check
python3 tools/spec/check_ndf_profile.py docs/spec
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
python3 -m unittest tests.test_top_natural -v
```

- [ ] **Step 5: Run bounded canonical smoke, delete old tops, commit and push**

Run a bounded scalar linx-avs ELF through emitted W4 `TOP` and require nonzero
IFU/CTU/OOO/IEX/LSU activation plus zero architectural mismatch for the
captured prefix. Then delete the old tops and rerun:

```bash
python3 tools/chisel/check_production_owner_manifest.py
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Commit intent: `Expose one routable core without moving ownership into TOP`

### Task 19: Run end-to-end functional and benchmark promotion

**Files:**
- Modify: `tools/chisel/run_top_natural.sh`
- Modify: `tools/chisel/run_dual_benchmark_gate.sh`
- Create or modify: `tools/chisel/run_scalar_linx_avs_gate.sh`
- Modify: `tests/test_dhrystone_crosscheck_1000.sh`
- Modify: `tests/test_coremark_crosscheck_1000.sh`
- Create: `tools/chisel/run_top_recovery_stress.sh`
- Create: `docs/chisel/top-mainline-benchmark-evidence.md`
- Modify: `docs/spec/50-verification/module-coverage.md`
- Modify: `docs/spec/50-verification/benchmark-acceptance.md`

**Interfaces:**
- Consumes: emitted W4 TOP and frozen workload ELFs.
- Produces: natural manifests, revision manifests, commit traces, cross-check reports and activation counters for every box.

- [ ] **Step 1: Freeze workload identities**

Record exact scalar linx-avs、Dhrystone and CoreMark ELF paths、SHA-256 values、
runner SHA-256、TOP git SHA、superproject SHA、reset PC/SP and completion
signature before running. All workloads must use the Task-18 active TOP;
reduced replay or old-top evidence is rejected.

- [ ] **Step 2: Run scalar linx-avs promotion first**

Run: `bash tools/chisel/run_scalar_linx_avs_gate.sh --top TOP --profile W4`

Expected: every selected scalar AVS ELF starts from real fetch, terminates
naturally, reports nonzero activation for all required boxes and has zero
bounded architectural mismatch.

- [ ] **Step 3: Run Dhrystone without oracle or replay**

Run:

```bash
bash tools/chisel/run_top_natural.sh \
  --elf ../../workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/dhrystone/dhrystone.elf \
  --build-dir generated/top-mainline/dhrystone \
  --max-cycles 3000000
```

Expected: exit 0, `terminal_status=finisher_pass`, no unsupported service, no unexpected trap, nonzero commit count, and nonzero IFU/CTU/OOO/IEX/LSU activation.

- [ ] **Step 4: Run CoreMark without oracle or replay**

Run:

```bash
bash tools/chisel/run_top_natural.sh \
  --elf ../../workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/coremark/coremark.elf \
  --build-dir generated/top-mainline/coremark \
  --max-cycles 3000000
```

Expected: same functional conditions as Dhrystone.

- [ ] **Step 5: Run bounded architectural cross-checks**

Run:

```bash
bash tests/test_dhrystone_crosscheck_1000.sh
bash tests/test_coremark_crosscheck_1000.sh
```

Expected: 1000 compared architectural commits with zero mismatch for each workload；manifests identify the same ELF bytes used by natural runs.

- [ ] **Step 6: Run the dual benchmark gate**

Run:

```bash
bash tools/chisel/run_dual_benchmark_gate.sh \
  --build-root generated/top-mainline/dual
```

Expected: both functional runs pass。IPC is recorded as a baseline and is not allowed to be omitted；a later performance campaign may raise a numerical IPC threshold without changing functional acceptance.

- [ ] **Step 7: Run recovery and long-latency stress**

Run: `bash tools/chisel/run_top_recovery_stress.sh --profile W4 --seed-count 100`

Expected: all seeds terminate, no duplicate commit, no stale writeback, no lost recovery ack, no committed wrong-path memory side effect.

- [ ] **Step 8: Check the unique owner graph, commit and push evidence**

Run: `python3 tools/chisel/check_production_owner_manifest.py`

Commit intent: `Prove the new core chain with natural workloads and exact commits`

### Task 20: Remove residual orphans and close the repository

**Files:**
- Delete: residual unreachable `Reduced*`、old top、compatibility adapter、probe
  and test files that earlier subsystem cutovers were unable to remove only
  because another not-yet-cut caller still existed.
- Delete: parameter/interface migration helpers with no canonical caller.
- Delete: `docs/chisel/linxcore-chisel-ifu-improvement-design.md`
- Delete: `docs/chisel/linxcore-chisel-ooo-improvement-design.md`
- Delete: `docs/chisel/linxcore-chisel-iex-improvement-design.md`
- Delete: `docs/chisel/linxcore-chisel-lsu-improvement-design.md`
- Delete: `docs/chisel/linxcore-chisel-microarchitecture-improvement-design.md`
- Delete: obsolete natural harness and runner files replaced by Task 18.
- Modify: `docs/chisel/README.md`
- Modify: `docs/chisel/module-index.md`
- Modify: `docs/architecture/linxcore_top_design.md`
- Modify: `docs/architecture/interfaces.md`
- Modify: `docs/chisel/production-owner-manifest.md`
- Modify: build/test file lists and CI entry points.

**Interfaces:**
- Consumes: passing Task 19 evidence.
- Produces: one live implementation chain, no compatibility owner and current documentation pointing only to TOP/IFU/CTU/OOO/IEX/LSU/DTU.

- [ ] **Step 1: Prove earlier cutovers already removed active legacy owners**

Run the owner manifest checker and add a repository gate that rejects live
old-chain identifiers:

```bash
python3 tools/chisel/check_production_owner_manifest.py
if rg -n '(class|object|new)[[:space:]]+(Reduced[A-Za-z0-9_]*|LinxCore[A-Za-z0-9_]*Top)' \
  chisel/src/main/scala chisel/src/test/scala; then
  echo "old Chisel chain remains" >&2
  exit 1
fi
```

The gate may allow historical commit text, but not live source、tests、emitters、
harnesses or current architecture pages. Any stateful legacy owner discovered
here is a failed earlier cutover: repair that subsystem and rerun its promotion
gate before continuing Task 20.

- [ ] **Step 2: Delete residual non-stateful orphans**

Verify Task-19 natural manifests and cross-check reports, then delete only
unreachable residual wrappers、aliases、fixtures、docs and tool entries. Task 20
must not be the first deletion point for an active state owner.

- [ ] **Step 3: Remove adapters and duplicate owners**

Delete parameter adapters from Task 2、old interface aliases from Task 3 and
any remaining temporary migration wrapper from Tasks 4–18。Do not keep dead
code under an archive source directory；Git history is the archive.

- [ ] **Step 4: Run the complete closure suite**

Run:

```bash
python3 tools/spec/check_ndf_profile.py docs/spec
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh
bash tools/chisel/run_chisel_verilator_lint.sh
bash tools/chisel/run_dual_benchmark_gate.sh --build-root generated/top-mainline/final
cmake -S . -B build
cmake --build build -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
bash tests/test_stage_connectivity.sh
bash tests/test_runner_protocol.sh
bash tests/test_cosim_smoke.sh
bash tests/test_opcode_parity.sh
bash tests/test_trace_schema_and_mem.sh
bash tests/test_rob_bookkeeping.sh
bash tests/test_block_struct_pyc_flow.sh
```

Expected: all commands pass；old-chain grep returns no matches；`git status --short` is empty after evidence cleanup and intended commits.

- [ ] **Step 5: Update current documents and remove superseded plans**

Current architecture pages must point to `docs/spec/` clauses and generated interface manifest。Historical rationale remains in Git；过期改进计划不保留为并列权威。

- [ ] **Step 6: Commit, push and update the superproject gitlink**

Commit intent: `Leave one maintained core after replacement evidence closes`

## 6. Review and Stop Rules

Every task ends at a reviewer gate。A task is not complete when only its unit test passes；it must also pass the listed adjacent interface and generated RTL gate。

Stop and repair the current task when any of the following occurs：

- a payload changes while stalled；
- a state mutation occurs without owner `fire`；
- a completion or recovery matches only a slot/low RID；
- one instruction partially mutates ROB/rename/IQ/LSU owners；
- TOP contains queue、age、rename、commit、cache 或 recovery policy；
- a reference mechanism introduces non-Linx architectural semantics；
- W2/W4/W6/W8 中任一 profile 无法 elaboration；
- natural benchmark uses expected rows、commit replay、instruction injection 或 QEMU oracle；
- a preparation task changes the public interface or creates a second state owner；
- a cutover commit retains a temporary adapter、old callable emitter or displaced
  state owner；
- a deletion removes the only passing implementation before its replacement has
  equivalent same-level evidence；
- a green loop ends with local commits not pushed to the configured upstream。

## 7. Completion Evidence Matrix

| Requirement | Required evidence |
|---|---|
| Major widths support 2/4/6/8 | `CoreConfigurationSpec`、`TOPWidthProfilesSpec` and emitted RTL for all four profiles |
| IFU fixed 64-bit output | IFU tests covering original 2/4/6/8-byte lengths and interface manifest showing 64-bit container |
| CTU template expansion | CTU UT for FENTRY/FEXIT/template blocks plus IFU→CTU→OOO IT |
| OOO D1-D3/S1 | decode、RENU、ROB/BROB、dispatch UT and `OOOIntegrationSpec` |
| P vs T/U rename separation | independent exhaustion、checkpoint、commit、recovery tests with unequal capacities |
| IEX requested topology | elaborated W4 manifest and simultaneous 2 ALU/1 BRU/2 AGU/2 STD tests |
| Separate system/multicycle and CMD queues | independent backpressure and completion tests |
| LSU 2 load/2 store | simultaneous-pipe UT、forward/replay/store visibility IT and manifest |
| Precise recovery | exact identity reuse、all-owner prepare/apply、trap/interrupt/debug tests |
| DTU scope | trace cannot block commit and DTU owns no ROB/recovery state |
| TOP routing only | structural connectivity test and source review gate |
| Production-owner reuse | checked owner manifest, active-emitter reachability, no duplicate state owner and no stateful adapter |
| Scalar linx-avs | natural finisher pass, nonzero box activation and bounded zero-mismatch report through emitted TOP |
| Dhrystone | natural finisher pass + 1000-commit zero-mismatch report |
| CoreMark | natural finisher pass + 1000-commit zero-mismatch report |
| Old chain removed | source/test/tool/doc grep gate and full closure suite |
| Repository clean | `git status --short` empty after reproducible generated intermediates are pruned |

## 8. Commit and Integration Discipline

每个 task 使用独立 Lore commit。提交正文至少记录实际影响本 task 的
`Constraint:`、已经比较并拒绝的方案及原因 `Rejected:`、实际
`Confidence:`、实际 `Scope-risk:`、后续修改必须保留的 owner/identity/
handshake 约束 `Directive:`、本次真正运行的命令 `Tested:`，以及明确由
后续 task 承接的门禁 `Not-tested:`。禁止照抄字段说明或填写虚构测试。

Task 10 冻结 production-owner manifest。Task 11、13、15、16、17、18 是
subsystem cutover task：同层级 replacement evidence 通过后，必须在本 task
删除已被替代的旧入口，不得将删除统一推迟到 Task 20。Task 12 和 Task 14
是 preparation task，只允许 behavior-preserving private refactor，不得改变
active public interface 或增加第二个 owner。Task 20 只删除 residual orphan。
最终合入 LinxCore 主分支后，更新 LinxISA superproject 的 `rtl/LinxCore`
gitlink，并在 superproject 侧完成独立提交和远端推送。

每个执行 loop 还必须：

1. 追加 `docs/chisel/mainline-loop-ledger.md`，记录本轮使用的技能、实际工作流、验证证据、发现的缺口和下一轮边界；
2. 明确记录 `skill-evolve: update` 或 `skill-evolve: no-update` 及理由；
3. 完成一个符合 Lore protocol 的 commit；
4. 将当前分支 push 到 upstream；台账记录 branch、enclosing commit intent
   和 push target，loop handoff 在 commit 已存在后记录 exact SHA 与远端相等性。

## 9. Final Definition of Done

只有同时满足以下条件，整个计划才完成：

1. `TOP` 是唯一活动 Chisel core 顶层。
2. `TOP` 只连接 IFU、CTU、OOO、IEX、LSU、DTU 和外部端口。
3. 参数、接口和 box IO 位于规定目录，生成 manifest 与 elaborated Bundle 一致。
4. W2/W4/W6/W8 全部通过 elaboration、接口和结构测试。
5. W4 默认 profile 具有 2 ALU、1 BRU、2 AGU、2 STD、独立 system/multicycle 和 CMD IQ、2 load pipe、2 store pipe。
6. OOO 是唯一 ROB、commit、精确 trap/interrupt 和全局 recovery plan owner。
7. production mechanisms 直接位于 canonical box owner graph 中；不存在
   committed compatibility adapter、重复状态 owner 或仅为匹配目标文件名而
   重写的平行机制。
8. scalar linx-avs、Dhrystone、CoreMark 都从真实 ELF 取指开始自然结束，
   并通过限定提交对比。
9. live source、tests、emitters、harnesses 和 current docs 中不存在旧顶层或 `Reduced*` 链。
10. `docs/spec/` 只使用 Linx 术语与项目内条款，不保留外部来源文件名或
   外部架构叙事。
11. 完整 LinxCore closure suite 通过。
12. LinxCore 工作树干净，提交遵循 Lore protocol；合入后 superproject gitlink 已更新。
