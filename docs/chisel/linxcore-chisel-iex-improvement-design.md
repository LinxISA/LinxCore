# LinxCore Chisel IEX 微架构改进设计

| 项目 | 内容 |
|---|---|
| 状态 | Draft，供架构评审 |
| 日期 | 2026-07-25 |
| 范围 | OOO 到 IEX 边界、issue、RF、执行单元、system/service、SP、template child、写回与完成 |
| 上位约束 | `linxcore-chisel-microarchitecture-improvement-design.md` |
| 主要实现证据 | `chisel/src/main/scala/linxcore/{execute,backend,system,top}` |

## 1. 目的与结论

本文先描述当前 Chisel IEX 实现，再给出可以分阶段落地的 production
改进方案。它不把 benchmark wrapper 的可运行性等同于完整微架构。

当前实现已经验证了若干关键机制：物理 GPR ready/data 的单一 owner、
resident IQ、P1/I1/I2、长延迟 divider 的 valid/ready、SP 顺序队列、
service 请求保留，以及两槽 execute completion retention。但这些机制
仍散落在 reduced top 和大量 helper 中，主要缺口是：

1. OOO 到 IEX 没有统一、可扩展的双 dispatch 边界。
2. IQ row 的释放点与 W2 完成、副作用混在一起，限制吞吐，也使 recovery
   ownership 难以证明。
3. ALU、BRU、AGU、system、service 和 macro 仍集中或旁路于
   `ReducedScalarAluExecute`。
4. RF writeback、ROB completion、replay、service 和 template 使用多个
   fixed-priority 组合 arbiter，低优先级请求通常没有统一保留语义。
5. system/CSR/trap/interrupt 没有 production owner；service 仍是
   benchmark proxy。
6. completion 的一些接口只携带 `robValue`，不足以抵抗 slot reuse。

目标实现采用如下 owner 图：

```text
OOO D3
  -> IssueDispatchBoundary(S1/S2)
  -> ScalarIssueQueueBank(S3 resident)
  -> ScalarIssueSelect(P1/I1/I2)
  -> ExecuteDispatchRouter
       -> ALU / BRU / AGU
       -> Divider / FPU
       -> ScalarSpOrderOwner
       -> SystemControlUnit
       -> ServiceRequestUnit
       -> Template child normal execution
  -> WritebackNetwork + CompletionNetwork
  -> ROB / RecoveryFabric / physical RF owners
```

核心设计结论是：

- IQ row 可以早于 W2 释放，但只能在一个可恢复、带完整 identity 的下游
  retained slot 已经接受该 row 后释放。
- IQ row release 只表示“issue storage ownership 已转移”，绝不表示指令
  已完成、ROB 可提交、RF 已写或外部副作用已发生。
- W2 是结果和 completion transaction 的终端确认点。W2 的 ROB
  completion、RF write 与对应 wakeup 必须由一次原子 accept 驱动。
- system、service、SC、MMIO 等不可逆副作用必须在 precise permit 后
  才能发出；检测可以更早，架构状态改变不能更早。
- 所有 completion、kill、response 和 release 都使用 exact identity，
  禁止只按 `robValue`、RID value 或 trace ID 匹配。

## 2. 范围、非目标与基线

### 2.1 本文范围

本文覆盖：

- OOO D3 到 IEX issue dispatch；
- S1 capture、S2 route/allocation、S3 IQ residency；
- P1 pick、I1 read、I2 confirm；
- scalar physical GPR；
- ALU、BRU、AGU dispatch envelope；
- divider、FPU；
- scalar SP；
- CSR、system、trap、interrupt；
- ISA service；
- RF writeback 与 ROB completion；
- template child 的 IEX 执行；
- 与 LSU、ROB、RecoveryFabric 的接口边界。

LSU 内部 LIQ/STQ/SCB、cache 和 memory ordering 由 LSU 设计文档定义。
本文只定义 AGU/SC/service 与这些 owner 的 handoff。

### 2.2 Production 参数基线

第一版以如下配置作为容量和端口验收点，但参数必须独立可变：

| 项目 | 基线 |
|---|---:|
| OOO 到 IEX dispatch | 2 rows/cycle |
| scalar issue | 2 rows/cycle |
| IQ | 4 banks，合计 32 entries |
| 每物理 IQ enqueue | 2 ports |
| architectural scalar GPR | 24 |
| physical scalar GPR | 128 |
| GPR read/write ports | 6R/4W |
| ROB/commit | 独立参数，commit 4 |
| PE/STID | 独立参数，不允许常量化为 1 |

### 2.3 Exact identity

本文用 `ExactCompletionKey` 表示：

```text
PE
+ STID
+ TID
+ native BID(valid, value[BID_W-1:0])
+ native GID(valid, wrap, value)
+ native RID(valid, wrap, value)
+ independent BROB pointer/generation
+ ROB resident generation（若不由 native RID 完整表达）
```

必要时另加 owner-local generation，例如 divider epoch、service request
epoch、template parent generation。owner-local generation 用来拒绝 stale
response，不能替代原生 BID/GID/RID、BROB pointer 或 ROB resident
generation。

`pc` 可作为诊断字段，但不是 completion identity 的组成部分。trace 中的
截断 ID 也不能参与精确匹配。

### 2.4 Recovery 输入

IEX 只消费一次解析后的 `RecoveryResolvedTxn`：

- `pivotKey`；
- `killedMask` / `retainedMask` 或等价的精确集合；
- restart reason；
- owner-specific cancel token；
- recovery generation。

每个 owner 对同一个 recovery transaction 只 mutation 一次。零匹配或
多匹配均 fail closed 并报告 protocol error；普通 speculative recovery
不得使用无条件 `nuke` 模拟。

## 3. 统一事务与生命周期

### 3.1 建议的公共 bundle

| Bundle | 必须字段 |
|---|---|
| `IexDispatchRow` | full renamed uop、`ExactCompletionKey`、source/destination class、ordering class、FU mask、recovery checkpoint、template child metadata |
| `IssueRowId` | IQ bank、slot、slot generation |
| `ExecuteTxn` | `ExactCompletionKey`、`IssueRowId`、FU class、operand provenance、destination ownership、recovery generation |
| `IssueRelease` | `IssueRowId`、execute owner ID、execute slot、execute slot generation、`ExactCompletionKey` |
| `WritebackTxn` | exact key、destination class/tag/data、producer ID、exception metadata |
| `CompletionTxn` | exact key、supported/trap/result metadata、required-side-effect mask |
| `FuKill` | exact key或 resolved killed bit、owner slot generation |

所有跨 owner 请求采用 `Decoupled` 或等价 retained protocol。任何 producer
在 `valid && !ready` 时必须保持 payload 不变。

### 3.2 Issue row release 与 W2 副作用

这是本设计最重要的区分。

| 事件 | 含义 | 可以发生的最早点 | 不代表 |
|---|---|---|---|
| `pick` | row 被 P1 暂时锁定 | P1 | row 已离开 IQ |
| `readConfirm` | operands 已在 I2 原子确认 | I2 | FU 已拥有恢复责任 |
| `executeAccept` | retained execute slot 接受 full transaction | I2->E | 指令完成 |
| `issueRowRelease` | IQ residency ownership 转移给 execute owner | 与 `executeAccept` 同周期或之后 | ROB complete、RF write、wakeup、trap、外部副作用 |
| `resultReady` | FU 结果已计算并被 terminal record 保留 | EX/W1/W2 | 所有 sink 已接受 |
| `w2Fire` | 所有本 row 必需的 W2 sink 同时接受 | W2 | commit 已发生 |
| `robComplete` | ROB exact row 标记完成 | `w2Fire` | 架构 retirement |

允许 early release 的必要条件全部满足：

1. 下游 slot 已经 `fire`，不是只有 `ready` 或 preview；
2. slot 保存完整 `ExecuteTxn` 和 owner-local generation；
3. slot 能接收 `RecoveryResolvedTxn`，能区分 retained/killed；
4. slot 对结果和 completion 有 retained 输出；
5. slot capacity 已计入 issue admission credit；
6. 同一 `IssueRowId` 只允许一次 release。

不满足这些条件的临时 FU 继续在 W2 或其 terminal record 被接受时释放
IQ row。普通 load 在 LIQ 精确接受 allocation 后可以释放；LIQ 从该时刻
成为 completion/replay owner。SC 和 service 请求不能因为“请求已展示”
而释放，必须由对应 retained owner 接受。

### 3.3 W2 原子规则

每个 terminal record 计算 `requiredMask`，例如：

```text
ALU GPR result = ROB_COMPLETE | GPR_WRITE_WAKEUP
BRU correction = ROB_COMPLETE | BRANCH_RESOLVE
load return = ROB_COMPLETE | GPR_WRITE_WAKEUP | LSU_RETURN_ACK
store address = LSU_ACCEPT（ROB completion 点由 LSU contract 决定）
trap = ROB_COMPLETE | PRECISE_TRAP_RECORD
```

`w2Fire = allRequiredReady && terminalValid && !killed`。在 `w2Fire` 之前，
所有 sink 只能看到 request，不能 mutation；在 `w2Fire` 的时钟边沿，
required side effects 由同一个 transaction accept 脉冲驱动。GPR data
write、non-spec ready 和 P wakeup 是同一个 `GprWriteFire`。

如果实现为了布线而把 W2 拆成多个物理周期，必须先由一个
`TerminalCommitRecord` 原子预约全部 sink，再按内部 ack 完成；ROB
complete 必须是最后的对外可见 mutation，且中间状态不能被当成另一条
指令的可用结果。第一版优先采用 simultaneous-ready 原子 fire。

## 4. OOO 到 IEX：`IssueDispatchBoundary`

### 4.1 当前实现

当前 `ScalarIssueFabric` 接收单个 `RenamedUop`，按 bank occupancy 做
least-occupied 分配，并实例化多个 `ReducedScalarIssueQueue`。共享 I1
RF read arbiter 和 I2 issue arbiter各只有一路输出。

`ScalarIssueIngressSkid2` 是两 entry、单输入、两 bank 的弹性 skid：

- 可以在两个 row 都属于 `ScalarPipeSafety.fixedScalarAlu` 且目标 bank
  不同时双 drain；
- `drainPresent/Preview` 只是展示；
- 只有 `drainFire` 可以改变下游；
- 只有两 bank、1-bit bank ID；
- flush 无条件清空两个 entry。

`ScalarPipeSafety` 用 opcode 白名单和若干 sideband 排除条件判断“固定
scalar ALU 安全”。这是为 reduced 双 drain 提供的局部证明，不是完整
dispatch 分类器。

### 4.2 问题

- 单输入 fabric 和两 entry skid 不能证明 2-wide D3 的任意 row 组合。
- bank route 与 opcode 白名单耦合，新增 FU 容易漏改。
- ingress 没有 reservation token、exact key 和 recovery generation。
- preview 与 accept 虽已区分，但只对两 bank/固定 ALU 特例成立。
- 当前 flush 是 blanket clear，不能保留 recovery pivot 之前的 row。

### 4.3 目标 owner 与接口/状态

新增唯一 owner `IssueDispatchBoundary`，内部为：

- 每 dispatch lane 一个 S1 elastic slot；
- S2 route/allocation matrix；
- 每个 IQ bank 两个 enqueue candidate；
- D3 reservation credit ledger；
- exact recovery filter；
- per-STID ordering admission checker。

输入为 `Vec(2, Decoupled[IexDispatchRow])`；输出为每 bank
`Vec(2, Decoupled[IssueEnqueue])`。S2 只有在 row 所需 IQ、SP token、
system/service slot、template child lease 等 mandatory credit 都可用时
才 fire。

route 来自 decode/rename 产生的 typed `FuClass` 与 `OrderingClass`，
`ScalarPipeSafety` 不再重新解码 opcode。非法组合进入 precise
unsupported trap row，不静默选择 ALU。

### 4.4 时序、背压与恢复

- S1 只 capture，不分配 IQ。
- S2 同周期完成 bank 选择和双 enqueue 原子授权。
- 同一 dispatch group 若有必须保持顺序的两 row，不允许 younger
  越过 older。
- 两个独立 row 可同周期进入同一物理 IQ 的两个 enqueue port。
- recovery 逐 entry 应用 retained/killed；S1/S2 中尚未 fire 的 killed
  row 丢弃，已 fire 的 row 交给 S3 owner 处理。

### 4.5 迁移

1. 给现有 ingress 增加 `IexDispatchRow` adapter 和 exact recovery 测试。
2. 用 typed route 取代 `ScalarPipeSafety` 白名单。
3. 引入 2-lane S1/S2，先接现有两 bank queue。
4. 扩到 4 bank/2 enqueue，删除 production 中的
   `ScalarIssueIngressSkid2`；保留为 width=1/2 协议单测 fixture。

### 4.6 验收

- 2-wide 同 bank、异 bank、互相有序的所有组合；
- 一个 lane 背压时另一个 lane 的合法独立前进；
- preview 不 mutation，只有 fire 分配；
- recovery 与 enqueue 同周期；
- PE/STID/ROB/IQ 参数变化；
- 未知 FU/ordering class 精确 trap。

## 5. S3 residency：`ScalarIssueQueueBank`

### 5.1 当前实现

`ReducedScalarIssueQueue` 是 compact FIFO。每 entry 保存 uop、valid、
issued 和三路 registered source-ready。P source 使用
`ScalarGPRFile.readyMask` 与 committed wakeup；T/U 使用 reduced local
ready mask。P1 设置 issued，I1 denial 清 issued，row 在 release 时按
`(bid,rid,stid)` 删除并压紧。

当前可从两个 release lane 删除不同 issued row：常规 W2 release 和
load E1 被 LIQ 接受后的 release。young ready row 可以绕过 older
inflight row。`ScalarIssueFabric` 已提供 resident control/store frontier
的局部检查。

### 5.2 问题

- 默认/常用实例仍是 reduced depth、单 enqueue、两 bank。
- row release 缺少 `IssueRowId` generation，匹配不含 GID、ROB 独立代。
- compact FIFO 会使 slot index 移动，不适合把 `(bank,index)` 暴露给
  retained execute slot。
- P/T/U ready 信息和 top-level local overlay 混合。
- control/store frontier 的逻辑分散在 parent，不是 typed ordering
  admission。
- blanket flush 不能表达 retained subset。

### 5.3 目标 owner 与接口/状态

`ScalarIssueQueueBank` 是 S3 resident owner。4 bank 合计 32 entries；
每 bank 参数化双 enqueue、至少双 release。entry 保存：

- `valid`、slot generation、full `IexDispatchRow`；
- per-source `readyNonSpec`、`readySpec`、producer qtag；
- `picked/inflight` 与 owner slot token；
- ordering class、SP/system/service/template lease；
- recovery generation；
- age metadata。

采用固定 slot free-list 或 generation-qualified slot，禁止 compact 后
旧 `IssueRowId` 指向另一条 row。same-STID oldest 比较使用 wrap-qualified
ROB age；不同 STID 用 round-robin，禁止跨 STID 比 RID。

### 5.4 时序、背压与恢复

- S2 enqueue 在边沿进入 S3，N 周期 wakeup 最早影响 N+1 P1。
- enqueue 可以读取 RF ready snapshot，但同周期 committed wakeup 不得
  组合直通 pick。
- release 与 enqueue 可同周期；free credit 必须基于确定的 release fire，
  不能基于 release preview。
- fixed-latency FU 在 retained execute accept 后 early release。
- load 在 LIQ allocation accept 后 release；LIQ rejection 时 row 保持
  inflight，不重复 pick。
- recovery 精确清 killed entry；retained entry 的 source state和
  inflight owner token保持一致。

### 5.4.1 I0.7a 已实现：speculative-ready owner

`OooIexIssue` 已将 source readiness 拆成两类：

- `ready` 是 RF/global scoreboard 可继承的 non-spec readiness；
- `specReady` 是 physical IQ row 私有、可撤销的 load-dependent readiness；
- speculative wakeup 必须带完整 producer `RobMemberKey` 和独立
  `loadGeneration`，数字 generation 不能脱离 producer identity 单独授权；
- speculative wakeup 不写 P/T/U ready scoreboard，后绑定 consumer 不会
  继承该脉冲；committed wakeup 才提升 global ready，并清掉 row 中旧的
  speculative provenance；
- picker、query 和 P1 只从注册状态判断 `ready || specReady`，仍保持
  wakeup N、pick N+1。

本包只关闭 ready ownership。I0.7b 负责 bypass data/provenance 选择，
I0.7c 负责 load miss cancel、lane poison、清 `specReady` 和 exact repick。

### 5.5 迁移

1. 先把 release identity 扩成 exact key。
2. 引入 slot generation，停止用 compact index 作为长期身份。
3. 将 local T/U overlay 移到独立 operand owner/qtag path。
4. 扩成 4-bank 32-entry、双 enqueue/release。
5. `ReducedScalarIssueQueue` 降为 width=1 adapter。

### 5.6 验收

- 32-entry 压力、满队列 release+enqueue；
- 同周期双 enqueue、双 release；
- same-STID oldest 与 cross-STID fairness；
- wakeup N、pick N+1；
- slot reuse stale release；
- partial recovery 保留 older inflight；
- speculative wakeup 不污染 non-spec ready。

## 6. P1/I1/I2：`ScalarIssueSelect`

### 6.1 当前实现

`ReducedScalarIssuePick` 每 bank 保存：

- `i1Valid/i1Index/i1Uop`；
- `i2Valid/i2Uop/i2SrcData`；
- bank-local `rrBase`。

P1 从每 STID 的 oldest selectable row 中轮询。I1 产生全部 source read，
parent 原子 grant 后进入 I2；denial 产生 cancel，row 留在 IQ。I2 使用
valid/ready 等待 execute。execute accept 目前不删除 IQ，仍等 W2 或 LIQ
release。

正式 OOO 路径已经增加 `OooIexAtomicReadArbiter`：它接收参数化 issue
domain 的完整 I1 read group，用同 STID age、跨 STID round-robin 选择可行
子集，并独立映射 P/T/U/PC 端口。整组 grant/deny 和 readyless partial
response 到精确 repick 已实现。`OooIexOperandFiles` 现在把真实P/T/U数据
owner接在这些端口后面，`OooIexIssueReadFabric`直接驱动每条lane的
decision/data；PC端口保留为连接`OooPcBuffer`的显式边界。

### 6.2 问题

- parent 只有一组共享 read grant 和一条 I2 issue，未达到 6R/2 issue。
- 没有正式 bypass select、spec wakeup validation 和 replay poison。
- I2 accept 与 issue release 没有统一 owner-transfer token。
- flush 同时清 I1/I2，未区分已经由 FU accept 的 row。

### 6.3 目标 owner 与接口/状态

`ScalarIssueSelect` 每 bank保留 P1/I1/I2：

- P1：candidate age/fairness 选择并锁 `IssueRowId`；
- I1：产生一个原子的 `OperandReadGroup`，请求 P RF、T/U local bank、
  SP snapshot 或其他 typed source；
- I2：捕获 read/bypass 结果，验证 source version 和 recovery generation，
  选择一个 `ExecuteAccept`。

I1 arbiter按同 STID age、跨 STID RR 分配 6 个 GPR read ports。一个 uop
所有必需 read port 必须全得或全不得。I2 输出两 lane；每个 lane 可选择
一个有 credit 的 FU。

### 6.4 时序、背压与恢复

- P1 lock 不释放 residency。
- I1 denial 只 cancel P1 lock，不清 source ready。
- I2 下游背压时保持 uop、data、bypass provenance。
- I2 `fire` 后，由目标 retained slot 同周期返回 owner-transfer token；
  只有 token fire 才 early release。
- recovery 杀掉 P1/I1/I2 中 matching row；已 transfer 的 row 由 FU
  owner kill，issue select 不再次释放。

### 6.4.1 I0.7b 已实现：exact bypass select

每条 `OooIexP1I2Lane` 现在直接观察参数化 bypass candidates：

- P 用 `{STID,epoch,PTag,ptagGeneration}` 匹配；T/U 用
  `{STID,epoch,localTag,localSequence}` 匹配；
- speculative source 还必须匹配 I0.7a 保存的完整
  `{producer RobMemberKey,loadGeneration}`；
- 同一 source 按 W1、W2、W3 选择最新合法值，同 age duplicate candidate
  fail closed；
- bypass 命中的 source 不占 RF port，`OooIexI1ReadAttempt.sourceMask`
  明确定义为 logical source 的 RF-needed subset；
- 没有精确 bypass 的 speculative source 停在 I1，不允许回退到 RF；
- I2 保留 logical source mask、合并数据、bypass mask和完整 provenance，
  下游背压不能改写它们。

I0.7c 仍需把 load miss/cancel token 接入 IQ 与 lane，清 matching
`specReady`、poison matching I1/I2 copy并完成 exact repick。

### 6.5 迁移与验收

read group、exact token、多 domain 原子端口仲裁和P/T/U真实data owner组合
已经完成；IQ-local speculative-ready与精确load generation也已完成。
下一步连接正式PC owner，再扩bypass/replay validation和I2-to-E owner
transfer。验收必须覆盖：

- 两 bank simultaneous pick；
- 6R contention、全授予/全拒绝；
- I1 cancel/retry；
- I2 两个 resident row 与独立 backpressure；
- redirect younger 不影响 older accepted E/W；
- stale bypass/version 被拒绝并重试。

## 7. `ScalarGPRFile`

### 7.1 当前实现

当前模块是 scalar physical GPR data 和 non-spec ready 的 canonical
owner。容量、读口、写口独立参数化。24 个 identity tag reset ready。
组合读返回 data/ready；rename `clear` 把目标设为 not-ready。

写端口分为 `requestValid` 和 `commit`；只有
`requestValid && ready && commit` 的 `fire` 改 data/ready。不同 tag 可
并行，同 tag 按低端口优先，但 duplicate committed write 和 clear/write
collision 报错。accepted write 同时驱动 committed P wakeup。

正式 OOO/IEX 路径通过 `OooIexOperandFiles` 保持该模块为唯一data/ready
owner，并增加每PTag一个精确owner sidecar：
`{STID,epoch,PTag,generation}`。allocation clear安装新owner并清ready；
read/write必须匹配完整owner。T/U使用独立的STID-local sequence-qualified
数据文件，不复用P文件规则。

### 7.2 问题

- canonical top尚未把rename clear和W1 terminal write接到新接口。
- arbiter 在 RF 外部是 reduced fixed-priority 组合逻辑。
- readyMask 全量扇出会成为 128-entry/多 bank 的时序热点。
- recovery/free-list 对 killed destination 的 ready 清理没有统一 packet。
- 正式路径还没有bypass provenance和speculative-ready owner。

### 7.3 目标 owner 与接口/状态

保留 `ScalarGPRFile` 名称和唯一 owner 地位，配置为 24/128、6R/4W。
接口分为：

- `ReadGroupReq/Resp`：I1 原子读组；
- `AllocClear`：rename allocation exact phys generation；
- `GprWriteTxn`：exact producer、tag、data；
- `GprWriteCommit`：W2 原子 accept；
- banked/replicated ready read，而不是全局组合 mask 长线；
- diagnostics：duplicate producer、clear/write collision、stale generation。

物理 tag 的 allocation generation 由 rename/free-list owner提供并随
write txn 携带。RF 只接受当前 generation。

### 7.4 时序、背压与恢复

- I1 可用组合读或一拍读，但 production 选择必须固定并写入 timing
  contract；本文基线为 I1 request、I2 capture。
- write fire 同一边沿更新 data、non-spec ready、P wakeup。
- request hold 不 mutation；W2 未满足 ROB completion ready 时不能先写。
- recovery 不回滚物理 data；rename/free-list 使 killed generation
  不再可见，并对重新分配 tag 发 clear。
- speculative load wakeup进入 IQ `readySpec`，不写 non-spec RF ready。

### 7.5 迁移与验收

6R/4W参数、read-group arbiter、phys generation sidecar和真实P/T/U组合
已经实现；下一步把接口接入canonical top并分bank/复制ready read。验收：

- 1/2/4 write port 和不同 read port参数；
- 4 个不同 tag 同周期 write；
- same-tag、stale generation、clear/write collision；
- request grant 但 W2 未 fire 时 RF 不变化；
- write N 只让 dependent row 在 N+1 pick。

## 8. Execute dispatch envelope

### 8.1 当前实现

`ReducedScalarAluExecute` 是单 issue、E/W1/W2 resident pipe。一个大型
opcode function 同时处理整数、branch、load/store sideband、macro、
FP subset 和 divider。普通 load可在 E1 被 LIQ 接受后转移完成 ownership；
否则 W2 发 completion、RF writeback、branch/redirect、SP terminal 和
IQ release。unsupported opcode 也到 W2 才报告。

`ScalarPipeSafety.fixedScalarAlu` 通过白名单识别可双 drain 的安全子集。
它本质是 top-level optimization guard，不是执行 owner。

### 8.2 目标 `ExecuteDispatchRouter`

新增无执行状态的 typed router。输入是两个 `ExecuteTxn`，输出到：

- `ScalarAluPipe`；
- `ScalarBranchUnit`；
- `ScalarAguPipe`；
- `ScalarDividerUnit`；
- `ScalarFpuUnit`；
- `SystemControlUnit`；
- `ServiceRequestUnit`；
- template FINAL/control unit。

每个 FU 输出 credit 和 `ExecuteSlotToken`。router 必须对一个 txn
one-hot select；零选或多选均形成 precise unsupported/internal trap，
不得回退到 ALU。

### 8.3 公共 envelope 状态与时序

每个接受 row 的 FU slot至少保存：

- exact key 与 issue row token；
- opcode/FU subop；
- operands 与 provenance；
- destination ownership；
- recovery generation；
- SP/system/service/template metadata；
- terminal required-side-effect mask。

固定 ALU/BRU/AGU 可以在 accept 时 early release IQ；divider/FPU 等只在
其 request slot 真正接受后 release。router 自身不保留 request时，必须
把 backpressure直接返回 I2。

### 8.4 `ScalarPipeSafety` 处置

第一阶段保留其测试作为“typed fixed ALU route 与旧白名单等价”的对照；
production route 上线后删除该对象。所有 safety 条件转化为 decode
产生并由 D3 检查的 typed facts，避免 top 重复 opcode 语义。

## 9. `ScalarAluPipe`

### 9.1 当前实现与问题

当前 ALU 正确覆盖不断扩展的 reduced opcode 集，但与 memory、macro、
FP、branch 和 system sideband 共用一个 E/W1/W2 pipe。新增 opcode 容易
改变 unrelated hold 条件，且单 pipe 吞吐只有一路。

### 9.2 目标 owner

`ScalarAluPipe` 只拥有固定延迟整数 arithmetic/logical/shift/move/PC
relative 计算。production 基线两条输入 lane，可采用两套 E1/W1/W2 或
共享两 lane datapath。所有 opcode 语义来自唯一 decode table。

输出 `TerminalCommitRecord`，不直接写 RF、ROB 或 IQ。word operation
的截断/符号扩展在 ALU 内完成；unsupported subop 形成 precise trap
record。

### 9.3 背压、恢复、迁移与验收

- W2 terminal 满会逐级 backpressure，payload保持。
- killed E/W row 不发布 terminal；older retained row继续。
- 首先从 `ReducedScalarAluExecute` 抽取纯函数和固定 ALU op；
- 对旧/new ALU 做逐 opcode differential；
- 验收双 issue、连续 W2 backpressure、word 边界、modifier/shift、
  recovery 每一级和 exact completion。

## 10. `ScalarBranchUnit`

### 10.1 当前实现与问题

branch condition、SETC、FRET target 和 redirect 目前由
`ReducedScalarAluExecute` 及 top-level marker owner共同处理。直接
`redirectValid` 与 boundary recovery职责容易重叠。

### 10.2 目标 owner 与接口/状态

`ScalarBranchUnit` 只计算：

- condition/result；
- predicted 与 actual target；
- correction metadata；
- branch checkpoint reference；
- full exact key。

BRU 不直接改 architectural PC，不 blanket flush。它输出 retained
`BranchResolveTxn` 给 RecoveryFabric。RecoveryFabric 在 R0 捕获 resolve，
R1 做 precise decision，R2 发布 commit/flush 决策，R3 注册 recovery，
R4 向 I-F0 发布 restart，并触发 B-F0–B-F4 speculative checkpoint 恢复。

每 STID 可配置至少两个 resolve slots；同 STID correction 按 age，
不同 STID 独立。resolve output backpressure时 slot保持。

### 10.3 恢复、迁移与验收

将 reduced compare/target计算先迁入 BRU，top redirect改接
RecoveryFabric adapter，再删除 execute direct redirect。验收：

- correct prediction不产生 restart；
- mispredict exact pivot；
- 两个并发 STID resolve；
- younger redirect不杀 older E/W；
- target/condition owner唯一；
- R0-R4 每阶段 backpressure 与 stale checkpoint。

## 11. `ScalarAguPipe` 与 SC handoff

### 11.1 当前实现

普通 load/store address、resident-store lookup、LIQ admission、FRET.STK
synthetic load、SC 边界等散布在 `ReducedScalarAluExecute` 和 top。

`ScalarScTopHandshake` 另有一个 SC active slot 和一个 terminal pending
slot，保存 uop、address/data、BID/GID/RID/LSID。它等待 SC owner、
STQ insert/miss-discard，再生成 completion/writeback/release。其 completion
仍以 `terminalRid.value` 送 ROB，并以 `serviceCompleteValid` 回避同周期
冲突。

### 11.2 问题

- AGU 计算和 LSU ownership transfer未统一。
- SC 的 terminal arbitration依赖 service valid，说明 completion 网络
  不是 retained/fair。
- SC issue/release/completion仍使用 reduced sideband，缺少 ROB 独立代。
- synthetic FRET.STK load绕过正常 template/LSU child 路径。

### 11.3 目标 owner 与接口/状态

`ScalarAguPipe` 只计算 effective address、size、alignment、STA/STD
split metadata 和 memory ordering identity。输出：

- `LoadAllocTxn` 到 LIQ；
- `StoreAddrTxn` / `StoreDataTxn` 到 STQ；
- `ScTxn` 到 canonical LSU SC owner；
- alignment/access-fault candidate 到 completion terminal。

只有 LSU 对应 queue/owner `fire` 后 ownership 才转移，AGU 才可发
`IssueRelease`。AGU 不读写 memory，不直接 completion ordinary load，
不拥有 SC reservation monitor。

`ScalarScTopHandshake` 的 active/terminal retention语义并入 LSU 的
`ScExecutionSequencer`。SC request exact key、LSID 和 request epoch；
SC 成功的 STQ insertion与 status writeback是一个 terminal transaction。

### 11.4 时序、恢复、迁移与验收

- AGU 固定 E1 address stage；LSU admission backpressure保持 slot。
- alignment fault不进入 LIQ/STQ，而进入 precise trap terminal。
- 已被 LSU accept 的 load/store由 LSU recovery packet处理。
- SC 外部/缓存副作用只能在 precise/ordered permit 后一次发生。
- 迁移顺序：ordinary load -> store STA/STD -> SC -> template memory child。
- 验收：LIQ/STQ full、split store、alignment trap、SC success/fail、
  recovery between request/response、stale LSID/epoch、同周期 service
  completion不再影响 SC。

## 12. `ScalarDividerUnit`

### 12.1 当前实现

`ReducedScalarDivider` 是单 outstanding radix-2 divider，word 32 cycle、
full 64 cycle。request只携带 operands 和 signed/word/remainder；response
valid会保持到 ready。flush无条件取消。除零时 quotient返回 0、
remainder返回 dividend；该行为必须由 Linx ISA 契约确认，不能仅因当前
代码存在而视为规范。

### 12.2 问题

- request/response不携带 exact key或 epoch。
- 只有单 slot，flush无法区分 killed 与 retained。
- overflow、divide-by-zero等 ISA corner contract未在接口显式表示。
- 直接嵌在 reduced ALU hold path。

### 12.3 目标 owner 与接口/状态

`ScalarDividerUnit` 接收 `Decoupled[DividerReq]`：

- exact key、slot generation；
- lhs/rhs、signed、word、quotient/remainder；
- destination ownership；
- recovery generation。

内部 request slot、iteration state、response terminal slot均 retained。
response携带原 request key和 exception/status。radix/latency参数化，但
对外语义不随实现改变。

### 12.4 时序、背压、恢复、迁移与验收

- req fire 后可 early release IQ。
- response slot满时完成结果保持，不能接受新 request覆盖。
- killed busy request停止迭代或标记 drop；slot reuse response由
  generation拒绝。
- 先包一层 exact envelope，不改 radix-2 datapath；再增加并发/高 radix。
- 验收 signed/unsigned、32/64、min/-1、zero、remainder符号、
  req/resp backpressure、kill 每一 iteration、stale response。

## 13. `ScalarFpuUnit`

### 13.1 当前实现

`ReducedScalarFpExecute` 是组合 valid/ready 子集，仅覆盖 FEQ FS/FD、
FCVT FS->FD 和 UCVTF U64->FS。它没有完整 rounding mode、exception
flags、NaN/denormal contract，也没有独立 pipeline 或 exact identity。

### 13.2 目标 owner 与接口/状态

`ScalarFpuUnit` 至少拆分：

- add/mul/FMA pipeline；
- compare/convert pipeline；
- divide/sqrt long-latency unit；
- rounding/exception pack；
- retained terminal queue。

`FpuReq` 携带 exact key、format、rounding mode、operand class、destination
class、CSR rounding snapshot。`FpuResp` 携带 data、IEEE/Linx exception
flags、precise trap candidate 和 destination。

FPR 是独立物理文件还是与 scalar GPR 共用端口，必须由 ISA/register
architecture评审明确。接口先保留 `RegisterClass`，禁止把当前 64-bit
结果默认写入 GPR作为隐含架构决定。若 flags 映射到 CSR，FPU只产生
pending flags，`SystemControlUnit` 在 commit precise point合并。

### 13.3 时序、背压、恢复、迁移与验收

- 各 pipe accept 后可 early release IQ。
- output terminal 满会 backpressure对应 pipe，不丢低优先级 response。
- killed pipeline entry 不发布；长延迟 response用 generation拒绝 stale。
- 当前 subset 保留为 reference oracle，先接 envelope和 exact completion，
  再替换成完整 arithmetic。
- 验收所有支持格式/opcode、五类 exception、四舍五入模式、NaN/
  subnormal/signed zero、pipeline saturation、kill、slot reuse。

## 14. `ScalarSpOrderOwner`

### 14.1 当前实现

当前 owner 为每 STID维护：

- committed SP；
- reservation FIFO；
- head/tail/count；
- 一个 `completedValid/completedData`。

只有每 STID head可 issue。read-only terminal可直接 pop；writer terminal
先保存完成值，匹配 commit row时发布 committed SP并 pop。flush清全部
reservation；`recoveryRestoreValid` 有 FRET.STK 特例，可在 flush 时保存
返回路径产生的 SP。

### 14.2 问题

- transaction identity只有 STID、epoch、BID、RID，不含 GID和独立 ROB
  generation。
- 每 STID只有一个 completed writer slot。
- commit match混用 trace row value，边界不够严格。
- FRET.STK restore特例把 template/redirect语义嵌入通用 SP owner。
- blanket flush无法保留 recovery 前的 reservation。

### 14.3 目标 owner 与接口/状态

保留 `ScalarSpOrderOwner`，将 reservation改成 exact transaction：

- exact key、SP epoch、access read/write；
- D3 reservation token；
- source SP snapshot；
- produced value terminal；
- committed/pending状态。

每 STID FIFO depth参数化，可有多个 completed writer，但仍只按 head
commit发布。I1 通过 typed SP read group取 snapshot，不能把 SP当普通
top shadow旁路。

### 14.4 时序、背压、恢复、迁移与验收

- D3 reserve是 mandatory credit；失败则整个 row/group不 dispatch。
- issue必须匹配每 STID head exact key。
- writer execute只写 pending value；commit exact fire才改 committed SP。
- recovery删除 killed reservations并保持 retained顺序；恢复值来自
  canonical checkpoint，不再用 FRET 特例。
- 先扩 identity，再接 RecoveryFabric，最后删除 top `scalarSpValue`。
- 验收多 STID、多个 completed writer、reserve/commit同周期、partial
  recovery、template SP child、stale epoch、commit-before-complete error。

## 15. `SystemControlUnit`

### 15.1 当前实现

production `SystemControlUnit` 尚不存在。CSR、privilege、同步 trap、
interrupt和 system instruction的语义，当前由 decode/trace、reduced
service trap、top control fence等局部路径代替。这些路径能做 bring-up，
不能证明 precise architecture。

### 15.2 唯一 owner 与状态

新增 `SystemControlUnit`，唯一拥有：

- CSR file及实现/只读/权限属性；
- privilege mode；
- trap vector、cause、epc、status stack；
- pending interrupt retention、mask与priority；
- system instruction serialize状态；
- precise trap/return transaction；
- CSR pending write ledger。

每 STID 至少有独立 architectural system state和一个 system execution
slot。CSR uop携带 exact key、CSR address、operation、source和 privilege
snapshot。execute阶段只计算 old value、new value和异常候选，不直接改
CSR。

### 15.3 精确语义

同步异常：

1. FU/decoder产生 `TrapCandidate`；
2. ROB保存 exact trap metadata；
3. 只有该 row成为 precise commit boundary 时，ROB发 `PreciseTrapPermit`；
4. SystemControlUnit原子更新 CSR/privilege并产生 typed recovery；
5. RecoveryFabric在 R4 restart。

interrupt：

1. 外部 interrupt先在 owner中 retained；
2. owner结合 mask、priority和当前 privilege选择候选；
3. 只在 ROB给出合法 instruction boundary permit时接受；
4. 接受后形成带 boundary key 的 trap transaction；
5. interrupt在被接受前不能异步改 PC、清 IQ或覆盖另一个 pending cause。

CSR read/modify/write：

- 第一版采用每 STID system serialize：older system row未 commit前，
  younger system row不 issue；
- execute返回 old CSR value作为普通 destination；
- new CSR value只在该 row commit precise fire时写入；
- illegal address/privilege产生同步 precise trap；
- trap与 CSR write同 row冲突时trap优先，CSR不 mutation。

### 15.4 接口、背压与恢复

主要接口：

- `SystemExecuteReq/Resp`；
- `TrapCandidate`；
- `PreciseTrapPermit`；
- `InterruptInput` 与 retained pending状态；
- `SystemCommitTxn`；
- `RecoveryTxn`；
- CSR debug/trace read-only port。

system slot、trap terminal或 RecoveryFabric背压时请求保持。speculative
recovery可取消未获得 precise permit 的 row；已经获得 permit 的
architectural trap transaction属于 recovery pivot，不能当 younger
speculation取消。

### 15.5 迁移与验收

1. 建 CSR descriptor table与非法访问测试。
2. 接 CSR read/write但保持 system serialize。
3. 接同步 trap exact completion和 ROB permit。
4. 接 retained interrupt与 R0-R4 recovery。
5. 删除 reduced top control fence和 direct trap/PC side effects。

验收覆盖 CSR RMW/readonly/权限、exception priority、interrupt mask/
priority、backpressure、trap与commit同周期、interrupt arrival during
recovery、多 STID隔离、unsupported system opcode fail closed。

## 16. `ServiceRequestUnit`

### 16.1 当前实现

`ReducedServiceRenameSnapshot` 只有一个 slot，按 STID+BID/GID/RID保存
7 个 phys tag。`ReducedServiceRequestPath` 在 issue at commit head 后
串行读取 a0-a5/a7并提交请求。`ReducedServiceRequestOwner` 只有一个
pending request，检查 reduced ACRC type和 adjacent compressed BSTOP，
flush后进入 cancel-drain，匹配 response后同时要求 complete/release/
writeback ready。

这是 benchmark semihost/service proxy。它不拥有完整 system/trap语义，
identity没有 PE/TID/独立 ROB代/request epoch，且单 snapshot复制了
rename数据。

### 16.2 目标 owner 与范围

只有 Linx ISA明确规定的 service进入 `ServiceRequestUnit`。UART、
finisher、host semihost和普通 MMIO不属于该 owner。

owner状态包括：

- parameterized request slots；
- exact key与 independent request epoch；
- captured source values及 phys generation；
- request sent/response pending/cancel-drain；
- response terminal record；
- precise side-effect permit。

source tag随正常 `IexDispatchRow` 保存，I1读取 source value；不再建立
第二份 rename snapshot。

### 16.3 精确 request/response 语义

第一版采用保守策略：

- service row必须是 ROB head并获得 `PreciseServicePermit` 后才能向外部
  发出不可撤销请求；
- request `fire` 后可释放 IQ，因为 ServiceRequestUnit已 retained完整
  transaction；
- response必须匹配 exact key、request type和 request epoch；
- mismatch/stale response只被 drain并报告，不能 completion；
- response结果进入统一 W2，ROB completion、GPR write/wakeup原子接受；
- unsupported/illegal sequence生成同步 precise trap，不伪造正常完成。

未来若 ISA定义某类 service可取消或幂等，可以按 service descriptor
放宽到 speculative send；默认不推断。

### 16.4 背压、恢复、迁移与验收

- request和response均 valid/ready retained。
- precise permit前外部 `serviceRequest.valid`必须为0。
- recovery取消未发送请求；已发送不可撤销请求不应成为 speculative
  recovery对象，因为其发送前已在 precise boundary。
- 将 `ReducedServiceRequestOwner` 的 hold/mismatch测试迁入新 owner；
  删除 `ReducedServiceRenameSnapshot`；
  `ReducedServiceRequestPath` 保留为 test adapter后从 production移除。
- 验收 request/response backpressure、slot reuse、stale epoch、flush、
  unknown response、precise trap、重复 response、多个 STID公平性。

## 17. 写回与完成网络

### 17.1 当前实现

`ReducedScalarWritebackArbiter` 是组合固定优先级 execute > replay，
无内部 retention。选中结果直接驱动 `ScalarGPRFile.write(0)`。

`ReducedRobCompletionArbiter` 同样是组合固定优先级 execute > replay，
top后续还叠加 service/template等选择；输出核心接口是
`completeRobValue`。blocked producer是否保持主要依赖外层 helper。

`ExecuteCompletionRetainer` 提供更强的局部原型：

- 两个 ingress lane、两个 resident slot；
- key含 PE/STID/TID/PC、native BID/GID/RID和独立 ROB ID；
- 能检测 invalid/duplicate identity；
- output backpressure时保留；
- exact clear或 `nuke`。

但它固定2槽，仍向下游输出 `completeRobValue`，clear只有单 key，
`pc`被纳入匹配，且 `nuke`不能表达普通 partial recovery。

### 17.2 目标 owner

拆成两个相关但独立的网络：

1. `ScalarWritebackNetwork`
   - 4 个 GPR write lanes；
   - 生产者 retained ingress queue；
   - same-tag ownership检查；
   - same-STID age-aware、cross-STID fair仲裁；
   - accepted write同时驱动 RF data/ready/wakeup。
2. `RobCompletionNetwork`
   - 参数化 completion lanes；
   - full `CompletionTxn` 送 ROB；
   - exact residency lookup；
   - duplicate full identity检测；
   - fair arbitration与 producer credit。

两者由每 producer 的 `TerminalCommitRecord` 和 `W2AtomicCoordinator`
联结。一个需要 RF+ROB 的 row 只有在两边同时 reservation ready时
`w2Fire`。

`ExecuteCompletionRetainer` 演进为通用
`CompletionIngressQueue`/`TerminalRecordQueue`。它位于 IEX 与 OOO ROB
之间：IEX在 ingress accept后可释放自己的 terminal slot；OOO只有在
exact key lookup唯一命中 resident ROB row后才接受 completion。

### 17.3 接口与状态

每个 producer端口暴露：

- `terminal.valid/ready/bits`；
- `wbReservationReady`；
- `completionReservationReady`；
- `terminalFire`；
- `kill`；
- `blockedReason`。

网络状态包括 ingress slots、producer RR pointer、per-lane grant、
duplicate scoreboard和 recovery generation。下游接口不再以
`completeRobValue` 为权威，只传 full key；rob value仅可做数组索引，
比较仍需 valid/wrap/generation和其余 identity。

### 17.4 背压、恢复和公平性

- 任一 required sink不ready，producer terminal payload保持。
- 低优先级 replay/service/template不得靠脉冲请求；必须 retained。
- same full key两个 producer同时出现是 ownership error，不仲裁吞掉。
- 不同 key争用使用 RR/age policy，不能永久 execute-first。
- recovery在 ingress、grant和ROB mutation前均按 exact killed set过滤。
- 普通 recovery删除 killed slot，保留 retained slot；只有 fatal
  quiesce完成后才允许全局 nuke。

### 17.5 迁移与验收

1. 所有 producer前接 retained adapter。
2. completion接口扩为 exact key，保留 robValue只作兼容诊断。
3. 用 2-lane `ExecuteCompletionRetainer` 替换 fixed-priority pulse丢失点。
4. 扩成参数化 ingress和4W/multi-complete lanes。
5. 删除 reduced arbiters和 top-level service-vs-SC互斥布线。

验收：

- 每个 producer持续 backpressure；
- execute/replay/service/template长期竞争无饥饿；
- same-row duplicate、slot reuse、stale generation；
- 4个不同 GPR tag同周期写；
- RF+ROB原子 fire；
- recovery与grant/accept同周期；
- exact lookup零命中/多命中均无 ROB mutation。

## 18. Template child execution

### 18.1 当前实现

`ReducedTemplateContextStack` 串行 capture/restore r2-r23，使用单端口并
在操作期间阻塞 issue。部分 top/template helper还能直接产生 RF、
load/store、completion等 side effect。独立 `TemplateD3*` 原型已经
表达 reservation/fill，但尚未把所有 child作为普通生产 row接入
IEX/LSU。

### 18.2 目标 owner 与 row 规则

Template D3 预约并 fill真实 ROB/IQ/LIQ/STQ资源。每个 child都是普通
`IexDispatchRow`，携带：

- parent exact key；
- template generation；
- child ordinal与row kind；
- child自己的 exact BID/GID/RID/ROB generation；
- reservation lease；
- SP/context dependency token。

child分别进入 ALU、AGU、BRU或 system owner，不存在 private template
execute pipe。parent FINAL row由 `TemplateFinalUnit` 等待：

- 所有 mandatory child filled；
- 所有 child completion或规定的 ownership handoff；
- authoritative store count发布；
- final lease可释放。

### 18.3 Context 与恢复

正式 `TemplateContextOwner` 保存 descriptor/checkpoint引用和 lease，
不保存第二份 speculative rename map，不直接写 RF。保存/恢复寄存器是
正常 load/store或move child；SP change走 `ScalarSpOrderOwner`。

partial fill recovery：

- 未 fill token由 template lease ledger取消；
- 已 fill child由 ROB/IQ/LSU各自按 normal killed set恢复；
- CTU不得 blanket cancel已由正常 owner接受的 side effect；
- stale fill/ack用 template generation拒绝。

### 18.4 时序、背压、迁移与验收

- reservation credit不足时整个 group不 mutation。
- row按 descriptor order fill；S2/IQ backpressure可暂停 fill。
- child issue和普通 scalar row公平竞争。
- 首先让简单 ALU/SP child走正常 IEX，再迁 memory child和 FINAL；
  最后删除 `ReducedTemplateContextStack` 直接 RF语义和 private completion。
- 验收 FENTRY/FEXIT/FRET_RA/FRET_STK完整 row plan、多 row原子 reserve、
  partial fill recovery、stale token、child backpressure、store count、
  template/scalar共同 issue以及 FINAL不早完。

## 19. Reduced helper 处置表

| 当前模块 | Production 处置 | 目标归属 |
|---|---|---|
| `ScalarIssueIngressSkid2` | 合并后删除；保留协议 fixture | `IssueDispatchBoundary.S1` |
| `ScalarPipeSafety` | typed route上线后删除 | decode/D3 route contract |
| `ScalarIssueFabric` | 重构 | dispatch + bank orchestration |
| `ScalarIssueCandidateArbiter` | 保留/扩展 | same-STID oldest、cross-STID RR、多 issue lane |
| `ScalarIssueExternalControlFence` | 合并 | issue admission frontier，不作为第二 recovery owner |
| `ReducedScalarIssueQueue` | 降为 width=1 adapter | `ScalarIssueQueueBank` |
| `ReducedScalarIssuePick` | 替换 | `ScalarIssueSelect` |
| `ReducedScalarAluExecute` | 拆分后删除 | ALU/BRU/AGU/FU envelope |
| `ReducedScalarDivider` | 保留 datapath、重做 envelope | `ScalarDividerUnit` |
| `ReducedScalarFpExecute` | 仅作 reference | `ScalarFpuUnit` |
| `ScalarGPRFile` | 保留并扩展 | scalar GPR canonical owner |
| `ScalarSpOrderOwner` | 保留并重做 exact recovery | SP canonical owner |
| `ScalarSpAccess` | 保留 typed bundle | SP request/response envelope，不拥有状态 |
| `ScalarScTopHandshake` | 合并后删除 | LSU `ScExecutionSequencer` |
| `ReducedScalarWritebackArbiter` | 替换 | `ScalarWritebackNetwork` |
| `ReducedRobCompletionArbiter` | 替换 | `RobCompletionNetwork` |
| `ExecuteCompletionRetainer` | 泛化 | `TerminalRecordQueue` |
| `ScalarLoadCompletionROBBridge` | 删除/替换 | load completion 进入统一 exact completion network |
| `ScalarLoadGPRCompletionSink` | 删除/替换 | load W2 使用统一 RF writeback/completion transaction |
| `ReducedServiceRenameSnapshot` | 删除 | normal dispatch/source ownership |
| `ReducedServiceRequestOwner` | 替换 | `ServiceRequestUnit` |
| `ReducedServiceRequestPath` | test adapter后移出production | service integration fixture |
| `ReducedTemplateContextStack` | 替换/删除 | template lease + normal child |
| `ReducedTemplateSnapshotTable` | 合并/删除 | Template sidecar/checkpoint lease，不复制 snapshot owner |

## 20. 分阶段实施计划

### Phase I0：锁定现状

- 为当前 issue release、W2、service、SC、SP、divider和completion retainer
  补 exact lifecycle回归。
- 记录当前 opcode differential结果。
- 增加所有 pulse producer在 backpressure下的失败测试，作为新网络验收
  基线。

退出条件：现有行为可重复，所有 known reduced waiver有显式清单。

### Phase I1：统一 identity 与 retained protocol

- 引入 `ExactCompletionKey`、`IssueRowId`、`ExecuteTxn`。
- 所有 completion/release/response扩 full identity。
- producer前加 retained terminal adapter。

退出条件：ROB completion无 slot-only权威路径，stale generation测试通过。

### Phase I2：S1-S3 与 P1-I2 production issue

- 上线 2-wide `IssueDispatchBoundary`；
- 4-bank/32-entry固定 slot IQ；
- 6R read group和2-wide I2；
- exact early-release token。

退出条件：2 dispatch/2 issue压力测试、fairness和partial recovery通过。

### Phase I3：拆分执行单元

- 先 ALU，再 BRU/RecoveryFabric，再 AGU/LSU；
- divider加 exact envelope；
- SC迁入 LSU；
- 删除 `ReducedScalarAluExecute` memory/redirect旁路。

退出条件：每 FU owner唯一，fixed ALU双 issue，load/store/branch不经
reduced direct side effect。

### Phase I4：W2 网络

- 4W writeback、multi-lane completion；
- retained/fair arbitration；
- W2 required-mask原子 fire；
- 泛化 `ExecuteCompletionRetainer`。

退出条件：所有 producer可无限 backpressure无丢失，duplicate exact key
fail closed，无固定 execute-first饥饿。

### Phase I5：System、service 与 FPU

- `SystemControlUnit` + CSR + precise trap/interrupt；
- `ServiceRequestUnit` + precise permit；
- 完整 FPU和register-class决策。

退出条件：system/service/FPU ISA测试和 precise recovery矩阵通过，
production top不实例化 `ReducedService*`。

### Phase I6：Template child统一

- D3真实 reservation/fill；
- 所有 child走普通 IQ/FU/LSU；
- 删除 context direct side effect和 private completion。

退出条件：四种 template row plan、partial fill recovery和 FINAL closure
通过。

## 21. 验证矩阵与完成定义

| 维度 | 必须证明 |
|---|---|
| Identity | full key唯一命中；slot reuse/stale response拒绝 |
| Dispatch | 2-wide所有route/bank组合；原子 reservation |
| IQ | 4-bank/32-entry；双 enqueue/release；age/fairness |
| Pick/read | P1/I1/I2 occupancy、原子6R grant、cancel/retry |
| Early release | 下游未accept绝不release；release不触发W2副作用 |
| W2 | RF write+wakeup+ROB complete required-mask原子fire |
| Recovery | 每一级kill；older retained；zero/multi-match无mutation |
| ALU/BRU/AGU | FU one-hot owner；branch R0-R4；LSU exact handoff |
| Long latency | divider/FPU req/resp backpressure、kill、epoch |
| SP | reserve/terminal/commit分离；多STID；partial recovery |
| System | CSR RMW、权限、同步trap、interrupt precise boundary |
| Service | precise request、response epoch、duplicate/stale、unknown fail closed |
| SC | request/insert/result原子关系；与其他completion独立 |
| Template | normal child execution；partial fill；FINAL不早完 |
| 参数化 | PE/STID、ROB、IQ、RF端口独立变化并通过 elaboration/test |

完成定义：

1. production top 的 D3->IQ->IEX->W2 是唯一 scalar execution graph。
2. `ReducedScalarAluExecute`、`ReducedService*`、reduced fixed-priority
   completion/writeback不在 production elaboration中。
3. 没有 completion、kill、service response只按 `robValue` 匹配。
4. issue release与 W2 side effects在接口、计数器、assertion中明确分离。
5. system/service/SC/MMIO没有 speculative irreversible side effect。
6. 单元、集成、随机 backpressure、recovery、差分 ISA和 benchmark
   cross-check全部通过。

## 22. 评审时必须关闭的架构决策

以下项目不能由实现代码默认决定：

1. Linx divider除零、signed overflow的精确结果/异常语义。
2. scalar FP的 architectural register class、物理文件和 writeback端口
   组织。
3. ROB completion lanes与 RF 4W 的目标频率/面积折中。
4. 哪些 service是 ISA architectural service，哪些只是 platform adapter。
5. CSR/interrupt的完整集合、priority和多 STID隔离方式。
6. ordinary load/speculative wakeup的 validation/replay owner接口。
7. template FINAL的精确完成条件与 parent/child exception传播规则。

这些决策关闭前可以实现接口和 fail-closed behavior，但不得把 reduced
行为提升为 production ISA contract。
