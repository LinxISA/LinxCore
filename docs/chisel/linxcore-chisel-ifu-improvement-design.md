# LinxCore Chisel IFU 微架构设计

- 状态：Architecture Baseline + Production Composition Baseline
- 日期：2026-07-26
- 适用范围：LinxCore Chisel production IFU
- 目标读者：架构、RTL、性能、验证、编译器和 LinxCoreModel 团队

## 架构决议

IFU 由两个彼此解耦的引擎组成：

1. **I-SIDE（Instruction Side）**：从 PC 发起取指，从 I-cache 取得一个
   cache line，完成指令边界预解码和定长化，将 instruction entry 写入
   Instruction Buffer。
2. **B-SIDE（Branch Side）**：维护跳转预测状态，独立接收预测请求并返回
   prediction，接收执行反馈完成训练。

两个引擎不共享流水寄存器，也不形成跨引擎组合 ready 环。它们通过带
`stid`、PC、fetch sequence 和 epoch 的 Decoupled 消息交换信息；合法
backpressure 由 request queue、boundary completion 和 prediction join
吸收。

I-SIDE 和 B-SIDE 各有五个真实 stage：

- I-SIDE：`I-F0`、`I-F1`、`I-F2`、`I-F3`、`I-F4`；
- B-SIDE：`B-F0`、`B-F1`、`B-F2`、`B-F3`、`B-F4`。

两个五级引擎以自己的 valid/ready、resident payload 和推进条件独立运行。
同编号不代表同周期，不要求 `I-Fn` 与 `B-Fn` 锁步，也不允许跨引擎共享
stage register。Model BFU F0–F4 的预测行为和时序关系映射到 B-SIDE
`B-F0..B-F4`；BHC、ITLB 和 L1I 始终归 I-SIDE。

**Instruction Buffer 位于 I-F4 之后、D1 之前。它不是 I-F4，也不与 I-F4
合并命名。**

D1 每周期从 Instruction Buffer 读取最多四条指令。每条 entry 的指令数据
已经扩展为 64 bit；从 D1 开始，流水中的 instruction representation 固定为
64 bit。D1 执行完整 opcode、operand、immediate 和 register-alias decode。

## 总体结构

```mermaid
flowchart LR
  subgraph BS["B-SIDE: decoupled branch engine"]
    BF0["B-F0: L0/Nano-BTB + checkpoint"]
    BF1["B-F1: uBTB + fast RAS / launch"]
    BF2["B-F2: PBTB/main BTB + BIM"]
    BF3["B-F3: short/medium TAGE + IBTB launch"]
    BF4["B-F4: static + long TAGE + final IBTB/loop/RAS"]
    TR["Training Queue"]
    BF0 --> BF1 --> BF2 --> BF3 --> BF4
    TR --> BF1
    TR --> BF2
    TR --> BF3
  end

  subgraph IS["I-SIDE: decoupled instruction engine"]
    IF0["I-F0: PC select / request allocate"]
    IF1["I-F1: ITLB + L1I parallel lookup"]
    IF2["I-F2: translation/cache resolve"]
    IF3["I-F3: line extraction / byte assembly"]
    IF4["I-F4: boundary predecode / 64-bit expansion"]
    PJ["Final prediction join"]
    IB["Instruction Buffer"]
    D1["D1: 4-wide full decode"]
    IF0 --> IF1 --> IF2 --> IF3 --> IF4 --> PJ --> IB --> D1
  end

  IF0 -- "prediction request" --> BF0
  BF0 -- "early prediction/correction" --> RA
  BF1 -- "later prediction/correction" --> RA
  BF4 -- "final prediction/correction" --> RA
  IF4 -- "identity-qualified boundary metadata" --> BF4
  BF4 -- "final prediction record" --> PJ
  RA["Canonical redirect / epoch arbiter"] --> IF0
  D1 -- "per-lane prediction record" --> DP
  D1 -- "per-lane prediction record" --> EX
  DP["Dispatch: direct/call validation"] -- "resolved metadata + training" --> TR
  IS -- "ITLB inner redirect" --> RA
  EX["BRU / recovery"] -- "resolved redirect + training" --> TR
  EX -- "restart" --> RA
```

## 术语和边界

| 术语 | 定义 |
|---|---|
| Fetch line | I-SIDE 以一个 PC 为起点请求的 I-cache cache line |
| Instruction entry | 一条已确定长度、已扩展为 64 bit 的指令及其 PC/身份/预解码信息 |
| Predecode | 只识别 `BSTART`/`BSTOP` block boundary；指令长度判定属于 I-F3 assembly |
| Full decode | opcode、operand、destination、immediate、alias、执行类别和资源提示的完整解码 |
| Inner flush | IFU 内部发现取指路径无效后，取消较年轻 IFU work 并回到正确 PC；不清理 OOO/LSU architectural state |
| Restart | 后端 recovery/exception/redirect 驱动的前端重新启动 |
| Prediction | B-SIDE 对给定 fetch PC/历史快照产生的方向、目标和 block 边界建议 |
| Training | 执行或解码反馈对 B-SIDE predictor state 的更新 |

I-SIDE 拥有：

- 每 STID 的 next PC、fetch sequence 和 fetch epoch；
- I-F0 到 I-F4 的所有取指流水状态；
- ITLB、L1I 请求和结果汇合；
- cache-line byte extraction、跨 line 指令拼接；
- 2/4/6/8-byte 指令到 64-bit 的零扩展；
- `BSTART`/`BSTOP` predecode；
- Instruction Buffer 写入。

B-SIDE 拥有：

- BTB family、方向预测、历史和 RAS；
- loop predictor/loop buffer；
- prediction request/response queue；
- speculative history checkpoint；
- prediction arbitration；
- resolved training 和预测器恢复。

Instruction Buffer 是 I-SIDE 与 D1 之间的独立 queue boundary。D1 属于
IFU decode 端，但不属于 I-SIDE 的 I-F0–I-F4 fetch pipeline。

## I-SIDE 流水

### I-F0：PC 选择和请求分配

I-F0 是唯一的 fetch-PC 消费和分配 owner。

职责：

- 从 runnable STID 中选择一个线程；
- 在 restart、inner redirect、B-SIDE prediction 和顺序 next PC 之间仲裁；
- 计算 cache-line-aligned request address 和 line 内 byte offset；
- 分配 `fetchSeq` 和当前 `fetchEpoch`；
- 同时向 I-F1 和 B-SIDE prediction request queue 发出请求；
- 在下游无容量时保持请求 payload 稳定。

PC 选择优先级：

1. accepted backend architectural restart；
2. accepted B-F1/B-F2/B-F3/B-F4 correction 或 typed I-SIDE inner redirect；
3. matching B-F3/B-F2/B-F1/B-F0 prediction，按 B-SIDE stage rank；
4. sequential next PC。

I-F0 不直接读取 predictor table，也不直接访问 ITLB/L1I。I-F0 只通过
Decoupled 接口向两个引擎投递带身份的请求。

建议请求类型：

```text
IF0FetchRequest {
  peId
  stid
  pc
  lineVa
  lineOffset
  fetchSeq
  fetchEpoch
  predictionTag
}
```

### I-F1：ITLB 和 L1I 并行访问

I-F1 必须在同一周期并行发起：

- ITLB lookup：以 `lineVa` 查询翻译和 execute permission；
- L1I lookup：以 virtual index 读取 tag/data candidate。

不得把 I-cache lookup 放在 ITLB hit 之后串行启动。I-F2 使用 ITLB 返回的
physical tag 对 I-F1 读出的 L1I candidate 做最终命中判断。

I-F1 驻留状态必须携带：

- I-F0 完整 request identity；
- ITLB request tag；
- L1I way/data candidate tag；
- B-SIDE prediction tag；
- line offset。

如果 I-F1 因下游阻塞不能前进，ITLB/L1I 请求和所有身份字段保持稳定，不得
重复分配 fetch sequence。

### I-F2：翻译与 cache 结果汇合

I-F2 是 ITLB 和 L1I 并行访问的汇合级。

正常 hit 条件：

```text
itlbHit
&& executePermitted
&& l1iTagMatch(translatedPhysicalTag)
&& request.fetchEpoch == currentEpoch(request.stid)
```

I-F2 分类：

- `Hit`：将完整 cache line 和 fetch context 送 I-F3；
- `ITLBMiss`：生成 I-SIDE inner flush，并启动 page-walk/miss handling；
- `AccessFault`：生成精确 instruction-fetch fault entry；
- `L1IMiss`：分配 I-cache miss transaction，等待 refill；
- `Stale`：epoch 不匹配，静默丢弃；
- `Replay`：端口冲突、结构冲突或 refill race 需要重新发起。

#### ITLB miss 的 inner flush

ITLB miss 必须产生 typed `InnerFlushRequest`。该请求：

- 仅作用于同一 `stid`；
- 使该请求之后的 I-SIDE I-F0–I-F4 work 失效；
- 递增或切换该 STID 的 fetch epoch；
- 清理尚未进入 Instruction Buffer 的较年轻取指结果；
- 不清理 OOO、ROB、rename、LSU 或其他 STID；
- 保留物理 ITLB/page-walk state，使 miss 可以继续完成；
- page-walk 完成后从原始 faulting PC 重新发起。

建议类型：

```text
InnerFlushRequest {
  stid
  cause       // ITlbMiss, FetchReplay, PredictionCorrection
  restartPc
  fetchSeq
  oldEpoch
}
```

所有异步 ITLB/I-cache response 必须携带 transaction identity。仅通过
`waitingResponse` 或无 ID response drain 不能作为 production 方案。

### I-F3：cache-line 提取和跨 line 拼接

I-F3 从 I-F2 的 line data 中提取从请求 PC 开始的字节流，并维护每 STID 的
instruction carry。

职责：

- 根据 line offset 提取有效 byte span；
- 识别当前 line 尾部是否包含不完整指令；
- 将上一 line carry 与当前 line 头部拼接；
- 产生若干完整的 2/4/6/8-byte instruction candidates；
- 保证每条 candidate 的 PC 连续且唯一；
- 将完整 instruction candidate 交给 I-F4。

指令长度规则：

| Header | 长度 |
|---|---:|
| bit 0 = 0 且 bits `[3:1] != 111` | 2 bytes |
| bit 0 = 0 且 bits `[3:1] == 111` | 6 bytes |
| bit 0 = 1 且 bits `[3:1] != 111` | 4 bytes |
| bit 0 = 1 且 bits `[3:1] == 111` | 8 bytes |

一个 cache line 可以产生多个连续的四宽 group。I-F3 必须保留当前 line 和
byte cursor，直到所有起始字节位于该 line 内的 instruction candidate 都已被
I-F4 接受；不得在第一个四宽 group 后直接释放 line。第二个 line 只用于补齐
跨 line 的最后一条指令，不能顺带产生起始于第二个 line 的 candidate，避免与
该 line 自己的 fetch transaction 重复。

I-F3 是跨 line instruction assembly 的唯一 owner。I-F4、Instruction Buffer 和
D1 都不得重新拼接 variable-length bytes。I-F3 不识别 block boundary；当 I-F4
接受的 group 含有关闭 resident `BSTART` context 的 `BSTOP` 时，通过
`acceptedStop` 反馈终止该 control stream 的后续 candidate。standalone
`BSTOP` 不产生此终止反馈。最后一个 group fire 的同周期，I-F3 必须允许
下一条 line consume-and-replace。

### I-F4：预解码和 64-bit 定长化

I-F4 是 I-SIDE 的第四个 stage。I-F4 不等于 Instruction Buffer。

I-F4 对 I-F3 已完成长度判定和跨 line 拼接的每条 instruction candidate 执行：

1. 接收并保留 I-F3 已确定的 `instLenBytes`；
2. 将指令 bit pattern 零扩展到 64 bit；
3. 只识别 `BSTART` 和 `BSTOP`；
4. 保留跨 cacheline 的 BSTART boundary context，并在 BSTOP 或 line
   terminal 生成 exact-identity Decoupled completion；
5. 形成 Instruction Buffer enqueue entry。

I-F4 predecode 明确不执行：

- 通用 opcode decode；
- 通用 operand 或 immediate decode（BSTART 自身编码的 boundary
  displacement 提取属于 boundary sideband，不形成 D1 immediate）；
- register alias 分类；
- load/store 分类；
- FU 或 issue-queue 分类；
- ROB/LSID/resource allocation；
- branch direction/target prediction。

`BSTOP` 必须区分两种语义。若已有 matching `BSTART` boundary context，
该 stop 关闭 control transaction，并终止其后 speculative entries；若没有
resident control context，它是 standalone execution-domain marker。后者仍写入
Instruction Buffer，但不得截断同一 cacheline 中已经形成的后续指令，也不得
单独宣告该 fetch transaction 完成。

建议 entry：

```text
InstBufferEntry {
  valid
  peId
  stid
  pc
  inst64
  instLenBytes   // 2, 4, 6, 8
  isBstart
  isBstop
  fetchSeq
  fetchEpoch
  predictionRecord {
    predictionTag
    branchPc
    taken
    target
    kind
    provider
    checkpointId
  }
  fetchFault
}
```

`inst64` 只承载原始 instruction bits 的零扩展值。预解码不得重编码 opcode，
不得把 2/4/6-byte 指令转换成另一套 ISA encoding。

## Instruction Buffer

Instruction Buffer 位于：

```text
I-SIDE I-F4 -> Instruction Buffer -> D1 decode
```

它是独立 queue，不属于 I-F4 stage，也不属于 D1 stage。

职责：

- 保存 I-F4 产生的定长 64-bit instruction entries；
- 按 STID 保持程序顺序；
- 为 D1 提供最多四条连续 entry 的 peek/pop 接口；
- 在 D1 阻塞时保持四条输出和 lane mask 稳定；
- 支持同周期多 entry enqueue 和最多四 entry dequeue；
- 对 typed restart/inner flush 只删除匹配 epoch/age 的 transient entries；
- 不修改 instruction bits 或重新执行 predecode。

推荐组织：

- per-STID FIFO bank；
- 全局 D1 STID arbiter；
- 至少四读端口或等价的四 entry head window；
- enqueue/dequeue credit 采用 pre-cycle resident occupancy，避免 ready loop；
- I-F4 enqueue group 只有在目标 bank 有完整容量时才原子接受。

Instruction Buffer entry 一旦写入，`pc`、`inst64`、`instLenBytes`、
boundary、epoch 和完整 effective `predictionRecord` 必须作为 row-owned
state 保存。不得依赖一个全局“当前预测”寄存器为后续 lane 补写预测。

## D1：四宽完整解码

D1 每周期从一个选定 STID 的 Instruction Buffer head 读取最多四条连续
instruction entries：

```text
lane0, lane1, lane2, lane3
```

每条 `inst64` 已经定长为 64 bit。`instLenBytes` 只用于 PC 连续性、trace、
exception 和 block 边界，不再决定下游 payload 宽度。

D1 对每 lane 执行完整解码：

- opcode mask/match；
- compact alias；
- source/destination operand extraction；
- immediate formation；
- GPR/T/U/SGPR/tile/vector alias classification；
- load/store、control-flow、system/service 分类；
- `BSTART`/`BSTOP` predecode 结果校验；
- illegal instruction detection；
- D2 resource-preview 所需的静态属性生成。

D1 输出为一个原子的四 lane group：

```text
D1DecodeGroup {
  stid
  laneValid[4]
  lane[4] {
    pc
    inst64
    instLenBytes
    opcode
    operands
    immediate
    boundary
    fetchSeq
    fetchEpoch
    predictionRecord {
      predictionTag
      branchPc
      taken
      target
      kind
      provider
      checkpointId
    }
    exception
  }
}
```

规则：

- lane 必须是同一 STID 的程序顺序连续前缀；
- 每个 valid lane 都必须携带完整 effective `predictionRecord`；多个 lane
  可以共享不可变 backing storage，但接口语义仍是 per-instruction metadata；
- 不允许跨空洞 compaction；
- group 未被 D2 接受前，全部 payload 保持稳定；
- dequeue 数量等于 accepted group 的有效 lane 数；
- D1 不分配 ROB、physical register、LSID、IQ 或 LSU row；
- group 尾部的 `ACRC` 可以读取 queue 中下一条 fixed-64-bit entry，以确认
  跨 group 相邻的 `BSTOP`；lookahead 未到达时保留 ACRC，禁止猜测 boundary
  或丢弃下一组 lane；
- D1 之后所有指令通路都使用 64-bit instruction representation。

## B-SIDE 架构

B-SIDE 是独立的五级 branch-prediction engine：`B-F0`、`B-F1`、`B-F2`、
`B-F3`、`B-F4`。其 predictor 布局、数据依赖和 correction 时序以
LinxCoreModel BFU F0–F4 为主要参考，但接口改为显式的 resident
ready/valid pipeline。B-SIDE 不访问 BHC、ITLB 或 L1I；Model BHC 及其
fetch-cache hit/miss/refill 行为只映射到 I-SIDE L1I。

### B-F0：L0/Nano-BTB、checkpoint 和最早预测

B-F0 从 Prediction Request Queue 接受 I-F0 请求。请求至少包含
`peId/stid/pc/fetchSeq/fetchEpoch/sequentialPc`。

B-F0：

1. 验证 request epoch；
2. 分配单调 `predictionTag`；
3. 冻结 per-STID GHR、path history 和完整 RAS image/pointer/count；
4. 原子分配 GHRQ/checkpoint row；
5. 查询 L0/Nano-BTB 或 NLP；
6. 若命中，发布最早的 identity-tagged prediction candidate；
7. 将 request、checkpoint 和 candidate 写入 B-F0/B-F1 resident register。

checkpoint 没有容量时 `B-F0.ready=0`，但不得组合拉低 I-SIDE I-F1/I-F2
的 ready。B-F0 prediction 被 I-F0 接受后，必须记录 accepted result，
供 B-F1–B-F4 比较和 correction。

### B-F1：uBTB、fast RAS 和大表启动

B-F1：

- 查询 uBTB；
- 对已知 return candidate 查询 fast RAS；
- 启动 PBTB/main BTB 和 BIM；
- 携带 frozen GHR 启动后续 TAGE；
- 携带 path/history 启动 tagged IBTB 的前置索引计算。

B-F1 可发布新的 identity-tagged prediction。若它与已被 I-F0 接受的
B-F0 prediction 不同，B-F1 发布 correction；若较早结果尚未被接受，
B-F1 直接以自己的更高 stage rank 覆盖 pending result。

### B-F2：PBTB/main BTB 和 BIM

B-F2 接收：

- PBTB/main BTB 的 direct control-flow type/target；
- BIM 基础方向；
- B-F1 发起的大表 request context。

B-F2 将 BIM direction 与 BTB direct target 合成，并可发布
identity-tagged prediction。B-F2 不能用 BTB target 冒充 indirect target
或 return target。它同时把 GHR、BTB kind、provider metadata 和
checkpoint 送入 B-F3。

### B-F3：short/medium TAGE 和 tagged IBTB 启动

B-F3：

- 读取并选择 short/medium-history TAGE provider/alternate；
- 以 PC、path history、GHR 和 control-flow kind 启动 tagged IBTB；
- 将 short/medium TAGE direction 与合法的 BTB direct target 合成；
- 保存 provider table/index/tag/counter/usefulness；
- 可发布 identity-tagged prediction，并纠正已接受的 B-F0/B-F1/B-F2
  结果。

B-F3 不执行最终 loop、long-history TAGE、IBTB 或 RAS 仲裁；这些属于
B-F4。

### B-F4：static predictor、long TAGE、final target 和统一仲裁

B-F4 同周期汇合：

- 基于 identity-matched I-F4 boundary metadata 的 static predictor；
- long-history TAGE provider/alternate；
- B-F3 short/medium TAGE 结果；
- final tagged IBTB target/confidence；
- Loop Predictor/Loop Buffer candidate；
- RAS final exact-kind/target check；
- PBTB/main BTB direct target；
- BIM fallback；
- 已接受的所有 earlier-stage prediction。

B-F4：

- 检查 request epoch/checkpoint；
- 执行统一 provider arbitration；
- 产生 final identity-tagged prediction；
- 与 I-F0 已接受的 B-F0/B-F1/B-F2/B-F3 结果按 exact
  `{taken, branchPc, target, kind}` tuple 比较；
- 必要时产生 `PredictionCorrection`；
- final tuple 相对 accepted result 发生变化时，随 correction proposal 携带
  exact predictionTag、GHR/RAS recovery action、conditional delta 和 typed
  Call/Return delta；
- correction proposal fire 时只设置 `historyRedirectPending`，不能更新 live
  GHR/RAS/loop；
- arbiter 返回 canonical prune 时从同一 B-F0 checkpoint rollback，再应用
  final conditional 或 Call/Return delta；若 final 与 earlier result 相同，
  不重复更新。

所有 B-F0–B-F4 stage 都允许发布 prediction。若顺序路径已经被 I-F0
采用，B-F0 与该路径不同也构成 correction；任何 later-stage prediction
只要 exact identity/epoch/checkpoint 有效，就可以纠正已经接受的 earlier
result。每一级 correction 都比较 exact
`{taken, branchPc, target, kind}` tuple。若被替代的结果已经驱动 I-F0，
B-F0/B-F1/B-F2/B-F3/B-F4 correction 都通过 typed inner flush 返回 I-F0；
B-F4 是最晚、最终的 correction 点。若该 stage 的结果在任何路径被采用前
到达，则它只是首次 steering selection，不需要 flush。

跨 stage prediction rank 固定为：

```text
B-F4 > B-F3 > B-F2 > B-F1 > B-F0 > sequential
```

更晚 stage 只有在 exact identity/epoch/checkpoint 有效时才能覆盖较早
结果。B-F4 同级 target 仲裁中，exact RAS return 与 high-confidence IBTB
indirect target 属于同一最高等级，control-flow kind 决定二者谁合法；
direct target 由 BTB 提供。B-F4 方向 override 保持
`loop > long-TAGE > short-TAGE > BIM > static`；这里的 `short-TAGE`
是 B-F3 已经在 short/medium tables 内选出的 provider class。static
predictor 是最终 fallback；它消费 I-F4 提供的 boundary metadata，但运行和
仲裁均归 B-F4，不得回迁到 I-F4。

backend restart 不属于任何 provider。它是 I-F0 控制仲裁的最高优先级，
高于全部 B-F0–B-F4 correction、全部 B-SIDE stage response 和
sequential PC。

需要 correction 的情况包括：

- taken 不同；
- branch PC 不同；
- target 不同；
- control-flow kind 不同；
- early miss 而 final provider hit；
- early provider 后来被 tag/epoch 检查判定无效。

任一 B-F1/B-F2/B-F3/B-F4 later correction 都产生 typed inner flush，
目标为 corrected target 或 sequential PC；**B-F4 correction 是最后一次
prediction-driven inner flush**。correction 被 arbiter 接受并返回 canonical
prune 后：

1. 切换对应 STID 的 I-SIDE fetch epoch；
2. 保留 correction producer，只清除其 younger I-F0–I-F4 transient work；
3. 保留 Instruction Buffer 中 correction producer 和 older rows，只压紧删除
   younger 错误路径 entries；
4. 从 corrected PC restart I-F0；
5. B-SIDE 按 exact predictionTag/checkpoint 恢复 speculative GHR 和完整 RAS
   snapshot，追加一次 corrected conditional direction 或应用一次 typed
   Call/Return push/pop；path/loop state 在后续实现中必须复用同一 canonical
   ordering。

B-F4 final response 被接受后，effective prediction 封存为不可变
`predictionRecord`，随 instruction bundle 写入 Instruction Buffer，并在 D1
附着到每个 valid lane。此后不再允许 predictor stage 产生 inner flush：

`IfuPredictionJoin` 在 I-F0 request fire 时按程序顺序分配 transaction row。
同一 cacheline 的多个 I-F4 四宽 group、B-SIDE early/final response 和
canonical redirect 可以任意顺序到达。只有当：

- I-F4 terminal group 已接受；
- B-F4 final response 已接受；
- 若存在 correction，其 canonical redirect 已广播；

三项同时成立时，join 才按原顺序逐 group 写 Instruction Buffer。写出时把
final `predictionRecord` 和 canonical epoch 覆盖到每个 valid lane；任何
younger-pruned row 不得复活。terminal I-F4 group 与 boundary completion
必须原子 fire，boundary table collision 必须 backpressure，禁止覆盖 resident
event。

`LinxCoreIfu` 是上述 owner 的 production composition。它原子接受 I-F0
lookup、join allocation 与 ordered line-context allocation，按
`miss replay > cross-line continuation > new F0 request` 仲裁唯一 I-F1
入口，并把 I-F2 结果明确分流到：

- hit：I-F3 resident line 或 matching cross-line response；
- ITLB miss：retained PTW-pending row 与 ITLB redirect proposal，refill 前
  禁止同一 unresolved transaction 重复发起 page walk；
- L1I miss：miss-table allocation 与 external line-read request，二者原子；
- access fault：retained fetch-fault output；
- stale：只消费并报告，不进入后续流水。

跨 line continuation 不旁路翻译或 cache；它重新走 I-F1 并行 ITLB/L1I、
I-F2 和同一 miss/refill owner。I-F0 可以分配多个 sequential cacheline；
`ISideLineContextQueue` 按 I-F0 顺序保留 context，允许 I-F2 exact hit 乱序
完成，但只把最老 completed context 交给 I-F3。跨线 instruction 被接受时，
I-F3 发布 exact successor prefix/carry；若 successor 已预取则原位更新其
semantic start PC，否则保留到该 context 分配。lookup request PC 保持不变，
因此 delayed I-F2 completion 仍能 exact match。non-correcting B-F4 final
response 不再改写 I-F0 speculative line frontier；只有 canonical correction
或 backend recovery 可以重定向 I-F0。由此跨线指令消费的第二行 prefix 不会
被再次解码，同时多个 cacheline 可以并发在途。

- direct branch/call 的无需运行时 operand 的 direction/target/kind 在
  Dispatch 校验；
- conditional `setc.*` 的实际 direction 和 exact-full-BID 静态 block target
  一致性在 BRU E1 校验；target 一致性用于拦截误接到其他 conditional block
  的 prediction record；
- indirect/return `setc.tgt` 的实际 target 在 BRU E1 校验。

prediction kind 同时限定唯一合法的 SETC validation owner：condition SETC
只消费 `Cond` record；`setc.tgt` 消费 `Ind/ICall/Ret` record，并把冷启动
`Fall` record 作为 `Ind` correction 校验，因为 `Fall` 只表示 B-SIDE 未识别
动态 boundary。与 `Direct/Call` record 同行携带的 `setc.tgt` 不得再次产生
BRU recovery；这些 target 已由 Dispatch 校验。

任一 post-B-F4 校验 mismatch 都产生 `BRU flush + recover`，恢复
predictor/rename/block checkpoint，并向 I-F0 发布 architectural restart；
不得重新分类为 inner flush。

SETC 不要求位于 block 的最后一条 scalar instruction。mismatch 被确认后，
backend 先冻结 commit 和 younger ingress，等待 exact full-BID 的全部 resident
ROB rows 完成，再以该 block 的 youngest RID 作为 non-inclusive recovery pivot。
rename cleanup 必须使用这个 rebased RID，不能继续携带 SETC 自身较早的 uop
order，否则会错误删除必须保留的 same-block tail GPR mappings。条件
`FRET.STK` 的 SETC condition 和显式 SETC target 都按 exact full-BID 保存和
消费；caller block 的 target 不得覆盖后续 callee-return fallback，其他 block
的 marker 也不得清除或复用 condition。

当前 `IfuBackendFeedbackBridge` 已实现这张类型表的 IFU feedback 边界：
合法 resolve 总是提交 actual tuple 训练；mismatch 的训练与 exact-keyed
backend restart 原子发布，任一 sink backpressure 时两者都保持。D1 sidecar
显式保留 transactionId、fetchPacketUid、fetchSeq 和 requestPc，不从 packet UID
猜测其他 request identity。Dispatch/BRU event producer 与 full-BID backend
cleanup 仍由 production composition 接线。

production IFU 只承认以下四个 wrapper 边界：

1. `LinxCoreIfu`：I-SIDE/B-SIDE、prediction join 与 canonical redirect；
2. `IfuLineMemoryBridge`：opaque-tagged 64-byte line request/response；
3. `D1InstructionDecodeStage`：四路 fixed-64-bit full decode；
4. `IfuBackendFeedbackBridge`：typed validation、training 与 keyed restart。

`LinxCoreComposition` 组合这四者。benchmark 使用的
`IfuWindowLineFillAdapter` 仅把 64-byte line 转成测试 memory window，不属于
production wrapper 集合。任何 composition 都不得恢复 packet-window decoder、
重新创建 `F4Slot`，或从 PC/地址猜测 transaction identity。

### B-SIDE ready/valid 和独立推进

每个 `B-F0..B-F4` 都是独立 resident stage：

```text
advance[n] = valid[n] && ready[n+1]
ready[n]   = !valid[n] || ready[n+1]
```

实现可用 skid buffer 打断长 ready 链。任何情况下：

- B-Fn stall 时完整 payload 稳定；
- I-Fn 和 B-Fn 不要求同周期推进；
- I-SIDE miss/refill stall 不冻结无关 B-SIDE STID；
- B-SIDE table conflict 不冻结已经进入 I-F1–I-F4 的取指；
- request/response queue 隔离两个 engine 的组合 ready；
- flush 按 `stid + epoch + predictionTag/checkpoint` 精确失效；
- physical predictor table learned state 不因普通 inner flush 清零。

### GHR、GHRQ、RAS 和 epoch

- GHR 是 per-STID speculative state；
- B-F0 原子分配 predictionTag/GHRQ row，保存完整 request identity 和不可变
  `ghrBefore`；B-F3/B-F4 以及 resolved TAGE training 只能使用该 snapshot，
  禁止重新采样 live GHR；
- correction response 与 redirect proposal 原子接受时只设置该 STID 的
  `historyRedirectPending`，不能提前修改 GHR；
- redirect arbiter 返回 canonical prune 后，B-SIDE 才从 exact matching row
  恢复 `ghrBefore`、对 conditional corrected direction 追加一次，并按 scope
  剪除年轻 GHRQ rows；同一 request 的再次 correction 仍从原 snapshot 重建，
  禁止重复追加；
- resolve 显式携带 `mispredict`；正确预测训练后释放 row，mispredict row 保留到
  backend BRU canonical recovery。该 recovery 携带 predictionTag 和 actual
  conditional delta，并 exact 命中仍 resident 的 row；
- ITLB miss 的 trigger 尚未到达 B-SIDE 时，从将被清除的最老 row 恢复；start
  使用显式 Reset action 清所选 STID 的 GHRQ/GHR；
- resolved training 使用 checkpoint 保存的 pre-branch history；
- stale identity/epoch response/training 丢弃并计数，不得修改 predictor table；
- epoch wrap 必须结合 outstanding tag/sequence，不能仅比较低位 epoch。

当前 Chisel 包已经实现上述 conditional GHR/GHRQ 和 RAS 合同。B-F0 row
保存完整 RAS image、pointer 和 count；B-F1 fast-RAS 与 B-F4 final-RAS 只读
该 request-owned top。correction proposal 不直接 push/pop，canonical prune
恢复 snapshot 后才应用 typed `None/Push/Pop` delta。resolve training 不再修改
speculative RAS。path history 和 loop speculative state 将在后续包复用同一
request-owned snapshot 与 canonical recovery 时序，不能另建第二套 flush
ordering。

### Training pipeline

Dispatch/BRU/recovery 通过 retained `PredTraining` queue 向 B-SIDE 提交
反馈。
Training entry 至少包含：

- exact `stid/requestPc/branchPc/fetchSeq/fetchEpoch/predictionTag`；
- resolved kind、taken、target 和 block boundary；
- checkpoint history/path/RAS snapshot；
- B-F3 provider、alternate、index/tag/counter/usefulness metadata；
- mispredict class 和 correction/recovery outcome。

训练顺序：

1. exact identity、epoch 和 checkpoint validation；
2. BTB/uBTB/PBTB/IBTB target/type 更新；
3. BIM/TAGE counter、provider、alternate、usefulness 更新；
4. loop trip-count/confidence 更新；
5. RAS classification 修正；
6. 释放 checkpoint/GHRQ row。

training queue backpressure 不能让 BRU feedback 丢失。相同
`predictionTag` 的重复 training 必须幂等丢弃或显式报错。训练写端口与
B-F1/B-F2 读端口冲突时，采用确定的 read-old/write-new 或 bypass 规则并
由 assertion 固定。

### Loop Buffer 边界

Loop Buffer 可缓存已确认的小循环 instruction-entry 序列，但它必须通过
I-SIDE 定义的独立 refill/replay 接口向 Instruction Buffer 提供同构 64-bit
entry。它不能直接绕过 D1，也不能访问 BHC/L1I physical state。

### superscalarNPU 对比

参考基线：`superscalarNPU origin/main@1fae7d0`。

可复用的设计原则：

- FTQ-style request/prediction 解耦、队列吸收速率差和显式 backpressure；
- per-thread GHR/RAS/predictor speculative state；
- BRQ、prediction checkpoint、resolved training 和 rollback 的基本原则。
- `origin/main@1fae7d0` 的 IFU→OOO 接口已经为 D1 slot 0..3 分别定义
  `predict_taken`/`predict_target`，证明 per-lane prediction carry 是明确的
  接口边界；Linx 在此基础上携带完整 `predictionRecord`，而不只携带
  taken/target。
- 该仓的 OOO→IEX BRU 接口继续携带 prediction metadata，IEX→IFU 在 E1
  发布 target/address mispredict。这支持把数据相关 direction/target 校验留在
  BRU E1；Linx 额外把 operand-independent direct/call 校验前移到 Dispatch。

不能直接移植的部分：

- 其 `B0–B4 + F1–F3` taxonomy 不替代 Linx 的
  `I-F0–I-F4 + B-F0–B-F4` 双流水；
- 无 TLB/PIPT 假设不适用于 Linx；Linx I-F1 必须并行访问 ITLB/L1I；
- 删除 UBTB 或 intra-frontend flush 的选择不适用于 Linx；
- B2/B3 predictor clumping 不替代 Linx 的逐级 resident、跨 stage rank
  和 B-F4 final correction；
- superscalarNPU 的 F3 static/context decode 与当前 LinxCoreModel Model F3
  SP 都不映射到 Linx I-F3；目标 Linx static predictor 明确运行在 B-F4，
  Linx I-F3 只负责 line extraction 和跨 line assembly；
- variable-width Instruction Buffer 不适用于 Linx。Linx I-F4 将
  2/4/6/8-byte 指令扩展为固定 64 bit 后才写 Instruction Buffer，D1 也只读
  64-bit entries。

因此 superscalarNPU 只提供 decoupling、per-thread predictor state 和
checkpoint/BRQ 方法参考。Linx 的冻结契约仍是双 I-F/B-F pipeline、并行
ITLB/L1I、B-F4 final prediction/最后一次 prediction-driven inner flush、
独立 fixed-64-bit Instruction Buffer、四宽 D1 per-lane prediction record，
以及 post-B-F4 的 Dispatch/BRU typed validation。

## I-SIDE 与 B-SIDE 的解耦接口

### Prediction request

```text
I-SIDE I-F0 -> B-SIDE B-F0
PredRequest {
  stid
  pc
  fetchSeq
  fetchEpoch
  sequentialPc
}
```

### Prediction response

```text
B-SIDE -> I-SIDE
PredResponse {
  stid
  requestPc
  branchPc
  fetchSeq
  fetchEpoch
  predictionTag
  checkpointId
  sourceStage       // B-F0..B-F4
  provider
  taken
  target
  kind
  confidence
  historyCheckpoint
}
```

I-SIDE 只接受 exact identity 匹配且 epoch 当前的 response。迟到 response
静默丢弃并计数。response 进入 I-F0 前必须经过 retained queue；下游
`ready=0` 时 payload 保持稳定。

### Training

```text
Dispatch / BRU / recovery -> B-SIDE
PredTraining {
  stid
  requestPc
  branchPc
  predictionTag
  fetchEpoch
  resolvedKind
  resolvedTaken
  resolvedTarget
  resolvedBoundary
  historySnapshot
}
```

训练入口必须有 retained queue。执行端不能因为 predictor table 端口忙而
丢失 resolved training。

### Redirect

B-SIDE 可以发布 prediction correction，但所有 PC 修改都由 I-SIDE I-F0
仲裁。B-SIDE 不直接写 I-SIDE pipeline register。

## Flush、epoch 和 stale response

IFU 定义两类控制：

I-F0 的控制优先级固定为：

```text
backend restart
> accepted B-F4/B-F3/B-F2/B-F1/B-F0 correction, by stage rank
> accepted earlier-stage B-SIDE prediction
> sequential PC
```

backend restart 不是 predictor provider，不参与 provider rank。

### Inner flush

来源包括：

- ITLB miss；
- fetch replay；
- I-SIDE 检测到 prediction/line-boundary 不一致；
- B-SIDE 较晚但仍可在 IFU 内纠正的 prediction。

作用域只覆盖匹配 STID 的 IFU transient state。B-F4 correction 是最后一个
prediction-driven inner flush；其 final response 被接受并封存
`predictionRecord` 后，任何 mismatch 都进入 OOO 可见的
`BRU flush + recover`。

所有 inner-flush proposal 进入唯一的 `IfuRedirectArbiter`，由它按
`backend > ITLB > prediction` 的优先级为每个 STID 分配 canonical
`newEpoch`。predictor 或 I-SIDE leaf 提供的局部 `epoch + 1` 不是最终 epoch
authority。即使较老但仍 live 的 transaction 较晚产生 correction，arbiter
也基于当前 canonical epoch 继续单调分配。

`IfuInnerFlush.scope` 明确定义裁剪范围：

| Scope | 语义 |
| --- | --- |
| `KillAllThreadState` | architectural restart，删除目标 STID 的全部 transient state |
| `KillTriggerAndYounger` | ITLB miss/replay，删除 trigger 及所有 younger transaction |
| `PreserveTriggerKillYounger` | prediction correction，保留 producer、删除所有 younger transaction |

younger 判定使用完整 fetch sequence 的有界模序关系，而不是仅比较低位 epoch。
因此一次 older live correction 能裁剪已处于更新 epoch 的 younger target-path
transaction。Instruction Buffer 必须压紧 surviving rows，并把 active epoch
切换到 canonical `newEpoch`；B-SIDE 只裁剪 pipeline、response 和 boundary
transient row，BTB/TAGE/GHR training table 等 learned state 不清零。

### Post-B-F4 按类型校验

`predictionRecord` 随每条 instruction 从 Instruction Buffer 进入 D1，并继续
传到校验 owner：

| 控制流类型 | 校验位置 | 校验内容 |
| --- | --- | --- |
| direct branch / call | Dispatch | `kind`、必然 taken 属性和静态 direct target |
| conditional `setc.*` | BRU E1 | 使用运行时 operand 计算的实际 direction，以及后端 exact-full-BID context 保存的静态 block target |
| indirect / return `setc.tgt` | BRU E1 | 使用运行时 operand/RAS 语义计算的实际 target |

校验比较有效记录中的 `{taken, branchPc, target, kind}` 适用字段。任一 mismatch
产生 `BRU flush + recover`，并携带 prediction/checkpoint identity 向 B-SIDE
训练、向 I-F0 发布 restart。SETC 只有在其类型与 prediction kind 的 validation
owner 匹配时才进入比较；cold `Fall + setc.tgt` 作为 `Ind` correction，而
`Direct/Call + setc.tgt` 不得重分类为 indirect。

当前 LinxCoreModel 将上述 `setc.*` direction/target 检查集中在 IEX/BRU E1，
并以当前 `BlockCommand` 提供 conditional target；Chisel 同时显式比较这个
exact-full-BID target，以验证 per-instruction prediction transport 没有跨 block
错配。Dispatch direct/call early validation 是目标 Chisel 设计的显式改进，
不是对当前 Model 实现位置的描述。

### Architectural restart

来自 BRU、exception、interrupt 或 central recovery。它：

- 为目标 STID 安装 restart PC；
- 切换 fetch epoch；
- 清理匹配的 I-F0–I-F4、B-F0–B-F4 和 Instruction Buffer transient rows；
- 通过 typed history action 通知 B-SIDE 从 mispredict-retained exact
  checkpoint 恢复 GHR/GHRQ/RAS，并追加 actual conditional delta 或应用 actual
  Call/Return delta；path state 必须复用同一 canonical recovery ordering；
- 保留 predictor learned state 和 cache/TLB physical state；
- 通过 age/epoch 丢弃迟到 response。

禁止用无 transaction ID 的“等待一次 response 再 discard”作为 production
stale-response 处理。

## 实施模块划分

### I-SIDE

- `LinxCoreIfu`
- `ISideF0PcSelect`
- `ISideF1Lookup`
- `ISideITLB`
- `ISideL1I`
- `ISideF2Resolve`
- `ISideFetchMissTable`
- `ISideF3LineAssembler`
- `ISideF4Predecode`
- `InstructionBuffer`
- `D1DecodeGroupGather`
- `D1DecodeStage`
- `IfuPredictionJoin`
- `IfuRedirectArbiter`

### B-SIDE

`BSidePredictionPipeline` 是当前 B-F0–B-F4、boundary table、response
retention 和 training 的唯一 composition owner。其内部物理单元是：

- NanoBTB、uBTB、PBTB 和 IBTB；
- BIM、short/long TAGE、loop predictor；
- per-STID GHR 和 guarded RAS；
- boundary table、training queue 和 prediction response queue。

未来可在不改变现有 Decoupled contract 的前提下拆分物理子模块，但不得
增加第二套 prediction/epoch owner。

### Shared protocol

- `FetchIdentity`
- `PredRequest`
- `PredResponse`
- `PredTraining`
- `InnerFlushRequest`
- `InstBufferEntry`
- `D1DecodeGroup`

### Production composition

`LinxCoreIfu` 只组合本章列出的 I-SIDE、B-SIDE、redirect/join、Instruction
Buffer 和 D1 owners。对外只暴露：

- start、backend redirect 和 resolved branch training；
- PTW request/refill；
- L1I line-read/refill；
- fetch fault；
- ITLB/L1I explicit invalidation；
- per-STID D1 dequeue selection 和四宽 D1 group。

start 为目标 STID 生成 `KillAllThreadState` 状态广播并 seed canonical
epoch；backend、ITLB 和 prediction proposal 统一由
`IfuRedirectArbiter` 按 `backend > ITLB > prediction` 选择。accepted
redirect 在一个广播周期内同时到达 F0、I-SIDE transient owners、B-SIDE、
prediction join、Instruction Buffer 和 D1；cache、TLB 与 learned
predictor tables 不因普通 redirect 清零。

## 分阶段实施

### IFU-0：冻结协议和命名

- 冻结 `I-F0..I-F4` 和 `B-F0..B-F4` 两套真实 stage 定义；
- 删除所有把 I-F4 与 Instruction Buffer 合并、或把 packet window 称为
  architectural I-F4 的规范性表述；
- 定义 fetch identity、epoch、inner flush 和 prediction 接口；
- 从 production graph 和规范 stage 命名中排除 test-only packet/window
  components。

### IFU-1：64-bit Instruction Buffer 和四宽 D1

- 将 I-F3/I-F4 输出转换为 `InstBufferEntry`；
- Instruction Buffer 支持 multi-enqueue/four-dequeue；
- D1 原子输出四 lane；
- 从 D1 起禁止 variable-width instruction payload。

### IFU-2：I-SIDE I-F0–I-F4

- 建立五个真实 resident stage；
- I-F1 并行启动 ITLB/L1I；
- I-F2 实现 hit/miss/fault/stale 分类；
- I-F3 实现跨 line carry；
- I-F4 只做 boundary predecode 和 64-bit expansion。

### IFU-3：inner flush 和 miss/refill

- ITLB miss 发出 typed inner flush；
- 建立 page-walk 和 L1I miss transaction；
- 用 exact transaction/epoch 丢弃 stale response；
- 覆盖 miss-under-miss 和不同 STID 并行。

### IFU-4：B-SIDE engine

- 建立 B-F0–B-F4 resident ready/valid pipeline；
- 接入 uBTB/PBTB/BTB、BIM、GHR/GHRQ 和 RAS；
- 接入 TAGE、IBTB、loop predictor/buffer；
- 实现跨 stage rank、B-F4 static/final arbitration、最后一次
  prediction-driven inner flush、per-instruction prediction record 和
  training。

### IFU-5：production promotion

- production top 只实例化 I-SIDE、B-SIDE、Instruction Buffer 和 D1；
- reduced BFU/body-cut 与 trace top 退出 production owner graph；
- generated RTL 证明双引擎异步运行；
- 与 LinxCoreModel 对比 prediction、block boundary 和 committed trace。

## 验证要求

### I-SIDE stage

- 每个 `I-F0..I-F4` 都有独立 valid/payload/ready residency assertion；
- stall 时 payload 稳定；
- flush 后旧 epoch 不得写 Instruction Buffer；
- PC、line offset、instruction PC 连续性可追踪。

### ITLB/L1I 并行

- 证明同一个 I-F1 request 同周期启动两个 lookup；
- ITLB hit + I-cache hit；
- ITLB hit + I-cache miss；
- ITLB miss + I-cache candidate hit；
- ITLB fault；
- refill 与 restart 同周期；
- stale translation/cache response。

### I-F3/I-F4 与 Instruction Buffer

- 2/4/6/8-byte 指令；
- cache-line 尾部的全部跨 line 组合；
- `BSTART`/`BSTOP` 唯一 predecode；
- 非 boundary opcode 不产生其他分类；
- 各长度都正确零扩展到 64 bit；
- 多 enqueue、四 dequeue、wrap、full 和 simultaneous enqueue/dequeue。

### D1

- 1/2/3/4 lane 连续前缀；
- stall payload stability；
- group 原子接受；
- full opcode/operand/immediate/alias parity；
- D1 之后所有 instruction field 均为 64 bit。

### B-SIDE

- B-F0–B-F4 各级 stall/replace/flush 和 payload stability；
- 每一级均可发布 identity-tagged prediction；
- B-F1/B-F2/B-F3/B-F4 分别纠正已接受 earlier-stage prediction；
- correction exact compare tuple `{taken, branchPc, target, kind}` 的每个
  单字段 mismatch；
- B-F0 L0/Nano-BTB、B-F1 uBTB/fast-RAS、B-F2 PBTB/BTB+BIM、
  B-F3 short/medium-TAGE+IBTB launch、B-F4
  static+long-TAGE+final-provider 归属；
- `B-F4 > B-F3 > B-F2 > B-F1 > B-F0 > sequential` stage rank；
- B-F4 同级 exact RAS/high-confidence IBTB target 选择；
- `loop > long-TAGE > short-TAGE > BIM > static` direction override；
- BTB direct target；
- 各 predictor table hit/miss；
- TAGE provider/alternate；
- BIM fallback；
- GHR speculative update 和 GHRQ recovery；
- RAS push/pop/underflow/overflow；
- indirect target；
- loop trip count；
- B-F1 early prediction 与 B-F4 final prediction 相同；
- B-F1/B-F4 taken、target、kind 不同触发 correction 和 inner flush；
- B-F4 correction 是最后一次 prediction-driven inner flush；
- B-F4 final `predictionRecord` 在 D1 每个 valid lane 上保持一致身份和内容；
- direct/call Dispatch 校验，以及 conditional direction 和 indirect/return
  target 的 BRU E1 校验；
- post-B-F4 mismatch 只产生 `BRU flush + recover`，不产生 inner flush；
- correction 到 I-F0 restart 的 exact PC/epoch/checkpoint；
- backend restart 覆盖全部 provider/correction；
- training queue backpressure；
- duplicate/stale training；
- prediction response 迟到和 epoch stale；
- I-SIDE/B-SIDE 独立 stall，不形成组合环。

## 架构验收清单

- [x] IFU 明确由 I-SIDE 和 B-SIDE 两个 decoupled engines 组成。
- [x] I-F0–I-F4 和 B-F0–B-F4 是两套真实且不锁步的五级 pipelines。
- [x] I-F4 不再与 Instruction Buffer 合并。
- [x] ITLB 与 L1I 在 I-F1 并行访问。
- [x] ITLB miss 产生 typed inner flush。
- [x] I-F3 唯一拥有跨 cache-line instruction assembly。
- [x] I-F3 完成长度判定与跨 line 拼接；I-F4 predecode 只识别
  `BSTART` 和 `BSTOP`。
- [x] 2/4/6/8-byte instruction 全部扩展为 64 bit 后写入 Instruction Buffer。
- [x] Instruction Buffer 是 I-F4 与 D1 之间的独立 queue。
- [x] D1 boundary 每周期读取最多四条 64-bit instruction，保持完整预测记录和
  ready/valid 稳定性。
- [x] 四条 D1 instruction 不经过 `F4Slot`，原子进入 production full decode，
  并逐 lane 保留 dynamic instruction UID 与完整 prediction sidecar。
- [ ] 四条 decoded instruction 原子进入 production rename 和 dispatch。
- [x] D1 之后不再传播 variable-width instruction representation。
- [x] I-SIDE 支持多 cacheline 在途、I-F2 乱序完成/I-F3 顺序消费，以及
  exact successor prefix/carry；hot-L1I composition 跨过 context 深度并连续
  20 周期输出四条。
- [x] `IfuLineMemoryBridge` 支持多 outstanding 64-byte tagged request、
  out-of-order tag+PA response、完整 IFU identity retention、same-PA alias、
  response backpressure 和 stale/duplicate drain；flush 周期不会丢失 refill。
- [ ] B-SIDE 完整实现 BTB family、speculative GHRQ、TAGE、BIM、RAS、IBTB
  和 loop units。
- [x] conditional GHR/GHRQ 已实现 request-owned B-F0 snapshot、B-F3/B-F4
  lookup/training history、canonical correction rollback、backend actual repair、
  ITLB fallback recovery 和 stale training rejection。
- [x] RAS 已实现 request-owned B-F0 full-image/pointer/count snapshot、B-F1
  fast target、B-F4 exact final target，以及 canonical Call/Return push/pop、
  late re-correction、ITLB fallback 和 start reset。
- [x] B-SIDE 实现 B-F0–B-F4 stage rank、B-F4 static/final correction 和
  I-F0 restart。
- [ ] B-F4 provider rank 与 direction override 有独立 assertion 和覆盖率。
- [x] B-F4 correction 是最后一次 prediction-driven inner flush。
- [x] B-F4 final `predictionRecord` 随 bundle 进入 IB，并附着到每个 D1
  valid lane。
- [x] `IfuBackendFeedbackBridge` 实现 Dispatch direct/call 与 BRU E1
  conditional/indirect/return 的分类型比较、actual-result training 和 exact
  predictor recovery transport。
- [x] `LinxCoreComposition` 已将 external Dispatch/BRU validation
  接到 actual-result training 和 canonical `LinxCoreIfu` BRU recovery；
  prediction-correction survivor 的 history key 随 canonical new epoch 重基准，
  exact mispredict training 在 matching prune 删除 checkpoint 前完成。
- [x] Dispatch/BRU event producer 和 full-BID backend cleanup 已接入 production
  backend；composition 仍保留明确的 `BackendBranchValidation` wrapper 边界。
- [x] `D1DecodeRenameROBIngress` 已将固定宽四 lane D1 group 原子写入
  `D1DecodedLaneQueue`，按程序序直接进入真实 rename/ROB；production elaboration
  禁用 packet/window decoder，prediction sidecar、precise prune 和 correction
  epoch rebase 均有 UT 与 generated-RTL gate。
- [ ] D2/D3 已从当前逐 lane admission 提升为四 row 原子资源预留与 dispatch。
- [x] `LinxCoreComposition` 已实例化 IFU line-memory bridge，且 bridge
  capacity 不小于 IFU miss-table capacity。
- [x] prediction、training、redirect 接口全部带 exact identity 和 epoch。
- [x] `LinxCoreIfu` composition 内只实例化本设计列出的 I-SIDE、B-SIDE、
  Instruction Buffer 和 D1 owners。
- [x] 迁移期 `ReducedBfu*` 文件、类型和 trace-top IO 已重命名为中性
  `Bfu*`；这些 compatibility helpers 仍明确排除在 production owner graph
  之外，production status 不由名称推断。
- [x] CoreMark/Dhrystone 使用的 production benchmark graph 已切换到
  `LinxCoreIfu`；fresh natural manifests 分别以 1426/1150 commits 到达
  `finisher=0x5555`。
- [x] generated RTL IFU probe 使用 64-byte cacheline，证明 eligible dense
  hot-cache window 连续 32 周期输出四宽 D1、每 lane 携带 B-F4 final record，
  并观测到 prediction join/line-context high-watermark 为 8/6。
- [x] production benchmark graph 覆盖真实混合长度指令、预测纠正、
  decode/dispatch backpressure 和 workload starvation 计数；standalone probe
  继续只作为四宽 mechanism gate。

## 不在本设计内

- D2/D3 resource reservation 和 rename；
- ROB/BROB allocation、commit 和 recovery arbitration；
- issue、execute、BRU resolution pipeline；
- LSU、DTLB、L1D 和 lower-memory；
- predictor table 的最终容量、组相联度和 replacement 参数。

这些内容分别由 OOO、IEX 和 LSU 设计定义；但它们不得改变本文件冻结的
IFU 边界、五级 I-SIDE、独立 Instruction Buffer、四宽 D1 或 decoupled
B-SIDE 契约。
