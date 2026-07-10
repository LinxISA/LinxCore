# Vector Core Architecture Appendix

> **Document ID**: JCORE-BCC-AS-APP-VEC
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft
> **Parent**: [JCore_BCC_AS.md](JCore_BCC_AS.md)
> **Topic**: Vector Core, Block Dispatch, Loop Ctrl, VIFU, ISQ, Vector Tile Access, TBuffer

> **Stage-name scope:** VIFU labels in this appendix are local Vector-core
> implementation labels, not the canonical LinxCore BCC pipeline. Where the
> appendix discusses the BCC/core frontend, use `pipeline-stage-catalog.md`:
> F0 is frontend control, F1-F4 are the four fetch stages, and F4 aliases IB.
> A lane count never defines F4.

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Captured Vector Core architecture, dispatch, VIFU, ISQ, Tile Access and TBuffer details |

---

## 1. Vector Core Introduction

Vector Core 是面向 AI 向量计算的高效处理单元，采用 group + SIMD 执行模式，同时具备标量运算与向量计算能力。Vector Core 作为乱序处理器，通过简洁的 block instruction interface 与 BCC 及上下游模块协同，为 AI 负载提供高性能并行计算。

### 1.1 Feature Summary

| Feature | Description |
|---------|-------------|
| SMT-4 | Vector 支持四线程模式，线程之间相互独立 |
| GROUP-4 | Vector 支持四 Group 模式，group 间独立，共享算子 |
| SIMT | Vector 支持 SIMT 执行模式，处理位宽为 256B，硬件自动处理线程分支差异 |
| Scalar Interface | Master Scalar 通过 Block Instruction Buffer 传输任务给 Vector |
| Tile Reg Interface | Vector 核内所有 data 数据通过 Unified Buffer 与外部 L2 交互 |
| TileOp | Vector 核内置 TileOp 解析单元，自行拆解 TileOp 为 Vector 可执行指令 |
| Vec ICache | Vector 核包含 IFU，通过上游下发的指令 PC 自行获取指令 |
| OoO Execution | Vector/Scalar 指令在发射条件满足时具备乱序执行能力 |
| TUMN Reg Rename | 相对索引寄存器 rename，其中 U/M/N 或 M/N 可放入 SRAM |
| LTPR | 针对 T 寄存器做备份与提前释放，减少物理寄存器数量 |
| Scalar LSU | 针对 Tile Reg 进行标量读写访问 |
| Vector Tile Access | 针对 Tile Reg 进行向量读写访问 |
| Vector Execution | 支持总位宽 2048bit 的多元素向量运算，同时支持分支 mask |
| SFU | 支持 Davinci 包含的所有 Special Function Unit |
| Block Resolve Channel | 指令块完成后，将 resolve 信息同步给上游 Master Scalar |
| Unified Buffer | 384KB SRAM? 支持 3 读 2 写，规格待定 |

---

## 2. Vector Core Constraints

硬件对 Vector 的执行设置以下约束，以保证设计简单高效:

1. 所有 Tile Register 依赖都已在 BISQ 处消解。Vector 从 Tile Register 读取输入 Tile 时，可以认为数据已经准备完成。
2. 所有 Tile Register 依赖都已在 BISQ 处消解。Vector 不需考虑 block 与 block 之间的 LD-ST violation。
3. 块内 LD-ST violation 仍然可能存在:
   - Parallel 模式下，Group 间不存在 LD-ST violation，但 Group 内可能存在。
   - Vector 模式下，Group 内外都可能存在 LD-ST violation，但块间不存在。
   - Group 内 LD-ST 需要保序执行。
4. TileOp 内部存在跳转，例如 loop，但不存在分支分歧，例如 if-else。Lane 间分歧由软件通过 predication 解决。

---

## 3. Vector Core Microarchitecture Overview

DOT source: [diagrams/vector_core_top.dot](diagrams/vector_core_top.dot)

```text
BCC BlockISQ
    |
    v
Block Buffer -> Loop Ctrl -> Group Buffer -> VIFU / TileOp Generator
                                      |              |
                                      v              v
                                  Vector OOO Decode/Rename/Dispatch
                                      |
             +------------------------+-----------------------+
             |                        |                       |
        VALU ISQ                 VSTD ISQ              Scalar/AGU ISQ
             |                        |                       |
             v                        v                       v
        Vector Pipe              Store/Data              Tile LSU / VAB
             \____________________    ________________________/
                                  \  /
                                   RF / Bypass / TBuffer
                                      |
                                Tile Register
```

---

## 4. Block Dispatch

### 4.1 Block Buffer

Block Buffer 接收来自 Ring / Block Issue Queue 的请求，根据 Group Buffer 状态，顺序取出不同 block，发送给 Loop Ctrl。

Loop Ctrl 根据 block size，即 LB0/LB1/LB2，完成 Group 分解。

容量设计:

- 根据 Ring 结构的缓存设计，保证最小功能需求。
- 具体 depth 待模型确定。

### 4.2 Loop Ctrl

Vector block 指令通过三个专用字段编码三层嵌套循环信息:

| Field | Meaning |
|-------|---------|
| LB0 | 第一层循环迭代次数 |
| LB1 | 第二层循环迭代次数 |
| LB2 | 第三层循环迭代次数 |

硬件掩码规则:

- 硬件 mask 只在最后一个 group 生效。
- 中间所有 group 的硬件 mask 均按全有效计算。
- Group count 计算时，为最后一个 group 增加一个 flag bit，指示该 group 是否是最后一个 group。
- 最后一个 group 的指令携带该 flag，用于 I2 stage 生成硬件 mask。

Mask 生成:

| Condition | Mask |
|-----------|------|
| 非最后一个 Group | 全使能 mask，例如 `32'hFFFF_FFFF` |
| 最后一个 Group | 根据 LB0/LB1/LB2 和 Group Size 动态生成有效位宽 mask |

线程拆分:

- Vector 最多支持 4 线程。
- 硬件可动态将 Group 循环拆分为 4 个并行线程或 2 个并行线程。
- 四个线程轮询取指。
- 对长度为 100 的一维 Vector，Loop Ctrl 接收 `Tadd t#1 t#2 -> t<100,int8>` 后拆成多次 group 执行。
- 在 Vector 执行单元中，每条 lane 看到的 LC0 值实际为 `LC0 * 32 + Lane_idx`。

三层循环示例:

- 当 `LB0=5, LB1=4, LB2=3`，Loop Ctrl 将 TileOp 拆成若干 group 迭代。
- 原始材料中图示为两次迭代，后续需补正式图。

### 4.3 Precision-Aware Group Allocation

设计目标:

- 提高 Vector Regfile 利用率。
- 针对 8bit/4bit 小 size 指令，尽量压缩 group 间指令。
- 将 SIMT 线程分解模式吸收为 SIMD 方式，以获得最大 RF 利用率。

架构诉求:

- Block 信息中增加 hint，告知 Loop Ctrl 整个 block 内指令的最大 element size。
- Group 拆分只关注 block 最大 element size。
- gather/scatter 指令均按 32bit 计算。
- 当前支持最大 size 为 64。
- size64 需要在 OOO 处单独处理，通过占用 2 个 pipe 资源支持同一 group 的 64 lane。

待定规则:

- 32bit、16bit 的 group 分配规则待定。
- 8bit/4bit 可选择与 16bit 相同，或采用 predicate-as-counter 编码。
- 非连续 predicate group 若按 16bit 规则，资源占用需评估。
- PRF 按 255bit 位宽设置。
- PRF 值更新为 4 个 scalar，具体编码待定。

### 4.4 Group Buffer

Group Buffer 存储 Loop Ctrl 分解后的 group 信息。

写入模式:

| Mode | Description |
|------|-------------|
| 并行写入 | 同一个 block 根据 size 最多占用 4 个 bank，每个 bank alloc 一个 entry |
| 串行写入 | 同一个 block 只能占用某一个 bank 的一个 entry |

当前规划:

- Block Buffer 根据最大并行 group 数设计。
- 目前规划 4 个 bank。

Entry allocation:

- entry 被 alloc 时写入 `group_issue_count`，表示该 group 需要被 issue 多少次。
- 每次该 entry 被 pick 后，count 递减。
- count 减到 0 时，该 group 可释放。

Pick modes:

| Mode | Rule |
|------|------|
| vector mode | FIFO；一个 group 直到 count 清零才轮转到下一个 bank 的最老 group |
| RR mode | 每 cycle 在 4 个 bank 内随机/轮询 pick |

Last pick marker:

- 当某 group count 清零的那次 pick，会给该 group 打标记。
- 下游据此知道该 group 内指令需要去 BMF(Block Mask File) 读取 LB 寄存器值，用于生成边界 mask。

De-allocate:

- group count 清零后，entry valid 拉低。
- 下一拍释放 entry。
- 每个 bank 维护 free num。
- 根据所有 bank free num 生成 block 信号，反压前端 BCC。

Flush 回退机制:

- 原始材料标为 TBD。
- 需要与 BCC flush、Vector ROB group、predicate BID/grp_id 恢复一致。

### 4.5 Group ROB

Group ROB 记录 block 执行状态，并向 BCC 反馈 block resolve / commit 状态。

Alloc:

- block 进入 Loop Ctrl 计算 group 拆解时，写 Group Buffer 的同时 alloc Group ROB entry。
- Group entry 并行写入 block 拆分的 lane 数，即所有 group count 之和。
- 写入 Block ID。
- Group ROB 本身不涉及反压，反压通过 Group Buffer size 限制。

Entry:

- 期望只存储 Block ID 和 Block count。
- 接收下游 OOO commit。
- 根据 BID 索引对应 entry。
- 一个 group commit 后，相应 entry 的 block count 减 1。
- 每拍最多接收 2 个 OOO commit 请求。

Dealloc:

- entry 内 block count 清零后，生成 block commit 请求。
- entry valid 拉低，下一拍释放。
- 每拍最多向上游 BCC 发送 2 个 block commit 请求。

---

## 5. Vector Instruction Fetch Unit

### 5.1 Overview

Vector Instruction Fetch Unit (VIFU) 负责为 Vector Core 获取 TileOp 或 block 的微指令。入口为 N-entry Origin Buffer，用于接收 Block Dispatch 请求。

VIFU 从上游 Block Dispatch 获取两类指令:

| Request | Flow |
|---------|------|
| TileOp | 送入 TileOp Generator，根据指令类型拆解成若干 Vector 指令，再进入 TileOp Inst Buffer |
| 指令块 PC | 访问 Vector L1 ICache 取指；miss 进入 Miss Buffer 并向下游发送 fill 请求；hit 后读 Data Array，完成预解码与对齐，进入 Inst Buffer |

VIFU 支持四线程 SMT RR 调度取指:

- 线程 PC 在 ICache miss 时不参与仲裁。
- 线程流水阻塞时，例如 Inst Buffer 反压，不参与仲裁。
- 四线程模式可一定程度掩盖跳转时延。
- VIFU 当前不实现跳转预测，只采用默认顺延的固定预测策略。

分支约束:

- 软件层面不支持 SIMT 下的分支分叉执行，即不同 lane 执行不同分支路径。
- 此类场景由软件 Stack 替代实现分支逻辑。
- 当所有 lane 跳转方向一致时，例如 loop 或 direct jump，硬件支持。
- 跳转目标 PC 在 F4 完成解析，并通过内部 flush 清除 F0-F3 中该线程错误 PC 指令。

### 5.2 VIFU Blocks

| Block | Description |
|-------|-------------|
| ITLB | fully-associative cache，将 VA 转换为 PA |
| Tag Array | 存储 ICache 四个 way 的 physical tag |
| Data Array | 存储 ICache instruction data，四个 way |
| Miss Buffer | 存储 ICache miss 请求，顺序向 L2 发送 |
| Alignment & Predecode | 指令拼接对齐、变长指令对齐、跳转识别 |
| SP Predictor | 识别 direct branch，若非顺延则产生 intra flush |
| TileOp Generator | 接收 TileOp block 指令并实时生成微指令 |
| Inst Buffer | 存储 ICache 或 TileOp Generator 产生的指令，四个独立 buffer 对应四线程 |

### 5.3 Pipeline Flow

WaveDrom source: [diagrams/vector_ifu_pipeline.wavedrom.json](diagrams/vector_ifu_pipeline.wavedrom.json)

1. 仲裁器按优先级选择 PC 写入 Origin Buffer:
   - BRU misprediction flush，根据计算内容更新 PC。
   - F4 识别 Indirect Branch，产生 intra flush 清空 F0-F4 该线程全部取指，Thread_en 拉低，直到 BRU 算出正确跳转地址，并将该地址写入 Origin Buffer。
   - F4 识别 Direct Branch，产生 intra flush 清空 F0-F4 该线程全部取指，并根据 Static Predictor 计算 PC 更新 Origin Buffer。
   - Predecode 识别 BSTOP，在 F4 产生 intra flush 清空 F0-F4 该线程取指，并更新 Origin Buffer 为 GROB 下一个 entry 起始 PC。
   - 若指令为 TileOp，更新 Origin Buffer 为 GROB 下一个 entry 起始 PC。
   - 若以上都未发生，Origin Buffer + 32，顺延获取下一个 Fetch Bundle。
2. 后级 RR 调度器选取有效线程进入 IFU F1 stage。
3. PC 同时进入:
   - TLB 做 VA -> PA 翻译。
   - Tag Array 查询 cacheline tag。
   - Data Array 根据 PC index 读数据。
   - 若为 TileOp，则直接送入 TileOp_Gen 触发状态机生成指令。
4. PA tag 与 Tag Array 四个 way 比较:
   - hit: 只使能命中 way 的 Data Array。
   - miss: 将 PA 送入 Miss Buffer，并发 cacheline 读请求到 L2，同时拉低该线程 Thread_en，直到 cacheline fill。
5. Data Array 读出 256-bit Fetch Bundle，送入 Alignment Buffer 并预解码。
   - Vector block 指令为 32-bit scalar 与 64-bit SIMT 指令。
   - Alignment Buffer 按 32-bit 分界顺序识别，抽出四条指令传给下游。
   - Alignment Buffer 满时反压前端对应线程。
   - Predecode 识别跳转信息。
6. F4 stage 根据 BID 选取进入 Instruction Buffer 的源头，最多写入 4 条指令。
7. F4 stage Static Predictor 根据预解码信息提前计算跳转 PC，并对 indirect/direct branch 产生 intra flush。
8. D0 stage，四线程通过 RR 调度器选择一个有效线程进入 OOO。

---

## 6. Vector ISQ / Execution

### 6.1 ISQ Topology

Vector 中有四个 ISQ:

| ISQ | Function |
|-----|----------|
| Vector ALU ISSUEQ | vector 计算类 uop |
| Vector STD ISSUEQ | vector STD data 传递 |
| Scalar ALU ISSUEQ | scalar uop 计算，以及 global register 到 Vector register 的 move |
| AGU ISSUEQ | load/store 地址计算 uop |

### 6.2 Vector ALU Issue Queue

规格:

- 4 个写口。
- 2 个 picker。
- 乱序 issue。
- 根据 age matrix 选择 ready 中最老的 uop。
- 部分 uop 只能被某个 picker pick。
- VALU 每个 uop 有 3 个计算 src: src1/src2/src3，以及一个 predicate pg src。

Alloc:

- OOO 根据 Vector ALU free num 决定每拍写入 uop 个数。
- free num 为 0 时，OOO 反压 dispatch。
- 四个写口默认写口 0 最老，写口 4 最年轻。
- S1 当拍 OOO 发送 uop 给 Vector ALU。
- 相应 ptag clear 在上一拍完成，避免错误 wakeup。
- S1 当拍完成 ready table 查询，并接收 on-fly wakeup。
- 并行完成 uop 执行信息解码，包括 latency/EID/PID 等。

Entry:

- S1 下一拍写入 entry。
- entry 持续接收 Vector、Scalar、AGU wakeup。

DPD:

| DPD | Producer |
|-----|----------|
| `3'b001` | Vector |
| `3'b010` | Scalar |
| `3'b100` | LD0 |
| `3'b101` | LD1 |
| `3'b111` | Gather |

64bit block:

- 64bit 精度指令会 reissue 两次。
- pick 的第二拍会 block entry 内所有 uop，确保无资源冲突。

Repick:

- uop 上 pipe 后遇到 load cancel 或资源冲突，最快下下拍重新上 pipe。

Deallocate:

- uop pick 后，确认 non-spec 后 entry deallocate ready。
- 对不依赖投机 producer 的 uop，I1 stage deallocate ready。
- 对依赖投机 producer 的 uop，在确认 producer 不会被 cancel 当拍 deallocate ready，下一拍 entry 释放。
- 遇到 flush 的下一拍 entry 释放。

### 6.3 VSTD Issue Queue

规格:

- 4 个写口。
- 1 个 picker。
- 乱序 issue。
- 根据 age matrix 选择 ready 中最老 uop。
- VSTD 每个 uop 有 2 个计算 src，无 predicate 寄存器。
- 整体 DPD / bypass / deallocate 规则与 VALU 一致。

### 6.4 Vector Register File

Vector RF:

- 物理上分为 4 个 bank。
- 通过 ptag 的最高 bit 和最低 bit 索引 bank。
- 规格: 128 entry，每 entry 2K bits。
- 共有 4 个写口、5 个读口。

写口规则:

- I2F 和 LD0 share 一个写口。
- Load 会 block 同拍写的 I2F 指令。

I2F/I2P pipe:

- I2F/I2P share pipeline。
- 接口信号为 I1 stage。
- 增加 type bit 区分 I2F 和 I2P。
- 为简化 SIMT scalar 依赖关系，I2F pipe 携带 element size。
- Vector 通路根据 element size 对非 reduce 类型指令完成 data 复制到所有 element。
- 简化 read regfile 后 mask 行为。

F2I pipeline:

- 整体复用 VALU pipeline。
- 根据 OOO dst type 区分。
- W0 stage 给 scalar 发送 wakeup info。
- W2 stage 将数据送给 scalar，并携带 size 信息。

### 6.5 Vector Bypass Network

Vector bypass network 完成 vector uop bypass 选择。

- I1 stage 根据 BID、Size 和 lastgroup 标记读取 Mask File。
- I2 stage 完成对应 element 的 mask 计算。

### 6.6 Heterogeneous Pipeline

出于面效考虑，不希望所有 pipe 全量布满所有算力。Vector 执行 pipe 有 2 条宽度为 2K 的 pipeline。

| Pipe | Operators |
|------|-----------|
| PIP0 | PERM, FMLA H/S, CVT, IMAC BH, IALU, DIV |
| PIP1 | PERM, FMLA H/S/D, CVT, IMAC SD, IALU, SEC |

算子分布应根据业务作为可配置项设计。不作为性能瓶颈的算子，按最小 pipe 配置原则。

Instruction latency:

| Instruction | Latency |
|-------------|---------|
| FADD | 2 |
| FMUL | 3 |
| FMLA | 4 |
| PERM | 2 |
| IALU | 2 |
| IMAC | 3 |
| CVT | 2 |
| SEC | 2/4 |
| DIV | 5/7/9 |

Resolve:

- VALU 在确认 non-spec 执行的下一拍，即 W0，完成 resolve。
- 同拍并行 set ready table。
- 同拍最多 2 个 resolve。
- VSTD 在 E1 stage 给 OOO 发送 resolve，一拍 1 个 resolve。

Reduce:

- 同一个 group 内的 reduce 指令，最终 dst 写入 Scalar RF。
- 除第一个 group 外，剩余 group 携带前一个 group 的 ptag 作为 src。

Predicate / pg:

- 小 size group 融合后的 pg 以 predicate-as-counter 编码方式存储。
- 若不满足该需求，则会占用 2 或 4 个 ptag 存放。
- 8bit/4bit 保持与 16bit 相同 lane 数，即 128 lane。

---

## 7. Global Register Move / Get

Vector block 支持 Get 指令，将 Global Register 的值存入 Vector Scalar 寄存器。

示例:

```text
Get a1 -> t
```

BISQ 只保证 Tile Register 依赖解除，并未保证 Global Register 依赖解除。因此 Vector block 执行时，Get 指令的操作数可能尚未 ready。

硬件维护 Global Physical Register Status Table (GPRST):

- 记录 Global register 的状态。
- Get 指令进入 GPR ISQ 前查询 GPRST，获取 Global register 最新状态。
- GPRST 和 Global GPR ISQ 接收来自 BCC 的 wakeup 信号。

当 Get 操作数 ready:

- picker 选取 Get 指令发射。
- 指令携带 ptag 去 BCC 索引 Global register。
- 读回值存入 Vector Scalar register。

---

## 8. Vector Tile Access Unit

Vector Tile Access 用于对 Tile Reg 进行访存，由 Load Queue 和 Store Queue 组成。

基础规格:

- Vector 支持一读一写。
- 读写位宽均为 256B。
- Tile Reg 空间由 BCC 通过 TileReg rename 分配。
- 从分配开始到 TileOp 结果写入，这块空间不会被其他 block 读写。
- 在此期间，块内微指令可将该空间视作可自由操作的 scratchpad memory。

### 8.1 Ordering

Tile Reg 被赋予寄存器概念，但 OoO 乱序写寄存器属性仅在 block/TileOp 层面成立。原因:

- BCC 对 Tile Reg 做重命名，或相对索引本质上是一种重命名。
- 每个 TileOp 写 Tile Reg 时，实际写不同地址。
- flush 时乱序写出的寄存器可以回退。

块内写 TileReg:

- Group 内层面不具备乱序写特性。
- 因为 Group 内对 TileReg 写不做 BCC 式地址重命名。
- 一旦写出后发生 flush，对应 TileReg 在块内已改变，无法回退。

Group 间写 TileReg:

| Block Attribute | Rule |
|-----------------|------|
| Vector | 不同 group 可能反复读写同一 TileReg，写 TileReg 需保序。写请求需等待自己成为最老 Group 的最老指令，即 `nxt_cmmt_gid && nxt_cmmt_rid` |
| Parallel | 不同 group 不写同一 TileReg，写 TileReg 无需等自己成为最老 Group，只需该指令为该 Group 最老指令，即 `nxt_cmmt_rid` |

读请求:

- 硬件可投机访问 Tile Reg。
- 需要检查块内潜在 LD-ST violation。

---

## 9. Vector Tile Temporal Buffer (TBuffer)

TBuffer 是 Tile Access Unit 内置的 Temporal Buffer。

容量:

```text
2-way x 32-entry x 2048-bit = 16KB
```

用途:

1. 利用输入 temporal locality，缓存输入，减少重复读 Tile Register 请求。
2. 利用输出 temporal locality 和 spatial locality，减少重复写 Tile Register 请求。
3. 将对 Tile Register 的读写带宽限制在 1024/2048-bit，使 Tile Reg 可使用大宽度 SRAM bank。
4. 对软件不可见，由硬件管理。
5. 读写 buffer 融合，减少面积。

### 9.1 TBuffer Entry

| Field | Width | Description |
|-------|-------|-------------|
| Tag | 12-bit | 请求地址比较，判断 hit/miss；hit 需判断 `(STID,BID)` 相同，否则直接 miss |
| Data | 2048-bit | 缓存数据 |
| State | 2-bit | 2: Modified, 1: Shared, 0: Invalid |
| WMask | 2-bit? | 1024-bit data write mask；128 lane 写最小带宽为 `128*8=1024-bit` |
| STID | `STID_W` | 独立 BROB ring / thread context |
| BID | `BID_W` (default 8) | 每 STID 256-entry BROB slot，用于 flush 和 block 完成时 writeback |
| GID | 5-bit | 用于 flush 和 block 完成时 writeback |

TBuffer 可接受请求:

1. PE read。
2. PE write。
3. PE 发起的 block end writeback。
4. 来自 BCC 的 prefetch 请求，TBD。

### 9.2 Read Request

流程:

1. TBuffer 接收 PE Access Unit read request。
2. CAM TBuffer 判断所需数据是否已存在。
3. Miss:
   - 请求发向 Tile Register。
   - 返回数据传回 LDQ 并写回 Vector Register。
   - 同时根据地址 idx 写入 TBuffer，记录 Tag。
4. Hit:
   - 不访问 Tile Register。
   - 直接从 TBuffer 读出数据，写回 Vector Register。

### 9.3 Write Request

流程:

1. TBuffer 接收 write request。
2. CAM TBuffer 判断是否 hit。
3. Hit:
   - 将 data merge 进入 TBuffer。
   - State 修改为 Modified。
4. Miss:
   - 判定是否为该 block 的第一次写入。
   - 若第一次写入，或写入带宽为 1024/2048-bit，则直接修改对应地址，必要时 evict。
   - 若非第一次写入，则需等待 cacheline fill 再写入。

写入前 entry 状态:

| Previous State | Action |
|----------------|--------|
| Invalid / Shared | 直接写入，State=Modified |
| Modified | 先按 WMask 将 entry 数据 writeback 到 Tile Register，再将新写请求直接写入该 entry，无需从 Tile Register fill 回 TBuffer |

### 9.4 WriteBack Request

当 PE block 整体 resolve 时，GROB 通知 TBuffer 将内部所有属于该 block 的 Modified 数据刷出到 Tile Register。

流程:

1. GROB 发起 block resolve/writeback。
2. TBuffer 查找对应 BID/GID 的 Modified entry。
3. 将数据写回 Tile Register。
4. entry state 标记 Invalid。
5. 所有数据刷出后，发出 Block Resolved 信号通知 BCC。
6. BCC 可将依赖该 block 的操作数设为 ready。

### 9.5 Prefetch Request

当 BCC 发送 block 请求给 PE 时，可触发 prefetch:

- 将块头中的 src0/src1 从 Tile Register 读入 TBuffer。
- 该请求以最低优先级与 Access Unit 发出的读请求共同仲裁。
- 预取量如何设置仍为 TBD。

### 9.6 TBuffer Flow Diagram

DOT source: [diagrams/vector_tbuffer_flow.dot](diagrams/vector_tbuffer_flow.dot)

---

## 10. Vector Load Pipe

Vector Load Pipe 每拍可发送两条读请求到 TBuffer。

来源:

- Vector PE LSU ISQ 选出的两条请求。
- LDQ 中等待 repick 的两条请求。

E1 仲裁后，选出两个请求访问 TBuffer tag/data。

规则:

- 读请求数据位宽与指令数据类型单位对齐，例如 int8 为 1024-bit，FP16 为 2048-bit。
- Load 指令可投机访问 Tile Reg。
- Store 指令来时需检查 LD-ST violation，若存在则向 ROB 发送 Nuke Flush。
- 支持 Load-Store Data Forwarding。
- Forward 除地址相同外，还需要 BID 相同。外部保证 block 间数据依赖已解除；若相同地址 BID 不同，代表程序有问题。

Miss:

- TBuffer 访问 Tile Reg，读回 cacheline。
- 数据写回 Vector Register。
- 如果发生 bank conflict，Load Queue 在请求发出 1 拍后收到 cancel。
- Load Queue 根据 cancel 回退状态机并重新发射。
- 第二次发射可直接访问 Tile Register，无需再次查询 tag。

Hit:

- 直接读回所需数据并写回 Vector Register。

Dequeue:

- Load 指令为所在块最老指令时，即 `nxt_cmmt_rid` 相等，才可从 Load Queue dequeue。

---

## 11. Vector Store Pipe

Vector Store Queue 每拍可发送一条写请求到 TBuffer。

发射条件:

- Store 属于当前 Vector 中最老指令。
- 不用等 BID 最老，前提是不存在 block 间 memory 依赖。
- 若存在 block 间 memory 地址依赖，且 LD 指令可提前从 Load Queue dequeue，则无法识别 LD-ST violation。
- Store 需属于当前 block 中最老指令。

功能:

- 支持 Load 指令 CAM Store Queue 并 data-forward。
- Store 指令确认写入 TBuffer 即可 resolve。
- Hit: 在 TBuffer 中直接 merge。
- Miss: 先 evict 对应 cacheline，再直接写入 TBuffer，无需等待 cacheline fill。

---

## 12. Vector WriteBack Control

TBuffer state 处维护 WriteBack Control 逻辑。该单元接收 Group ROB 的 Block Resolve 信号，将对应 block 的所有 Modified 数据刷出到 Tile Register。

WriteBack Control 发出的读请求参与 Load Pipe 仲裁。

仲裁优先级:

```text
Evict > Read Fill > LDQ Repick > ISQ Load > STQ Store
```

---

## 13. Notes for BCC Integration

- BCC BISQ 已解除 Tile Register block 间依赖，因此 Vector 可认为输入 Tile ready。
- BCC TileRename 分配的 TileReg 空间在 block 生命周期内作为 scratchpad。
- Vector block resolve 需要等 TBuffer Modified 数据全部 writeback 到 Tile Register。
- Vector Group ROB 的 block commit/resolve 请求需要映射回 BCC BID。
- Vector Get Global Register 依赖 BCC GPR wakeup，不能只依赖 BISQ Tile ready。
- Vector TBuffer prefetch 可由 BCC block dispatch 触发，但预取量和优先级需建模。
