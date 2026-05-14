# NPU-GPU Fusion Architecture Appendix

> **Document ID**: JCORE-BCC-AS-APP-FUSION
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft
> **Parent**: [JCore_BCC_AS.md](JCore_BCC_AS.md)
> **Topic**: JCore NPU/GPU 融合架构、层次化指令、内存层级、Master-Slave 接口

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Captured NPU-GPU fusion architecture notes and Master-Slave interface material |

---

## 1. Introduction

Linx JCore 是一款融合架构设计，目标是在单一硬件平台上同时支持 NPU 与 GFU 业务需求。该架构基于 LinxISA 指令集构建，采用灵犀指令集的层次化设计范式，将 NPU/GPU 任务的分发过程与执行过程解耦，从而简化系统架构并提升执行效率。

阅读本文档前，默认读者已阅读 LinxISA 指令集，并理解 LinxISA 层次化指令集中的块头、块体、TileOp、Group、MicroOp 等概念。

### 1.1 Layered Execution Architecture

JCore 采用“主核 + 专用核”的分层执行模式:

- 主核统一调度多个专用核协同工作。
- 主核执行第一层指令集，即“块头”，负责基础控制流运算、指令依赖解析与任务调度。
- 主核也具备执行第二层指令集，即“块体”的 scalar 运算能力。
- 该主核称为 **Block Control Core (BCC)**。

### 1.2 Dedicated Core Partitioning

专用核负责执行第二层指令集，即“块体”。按任务类型划分为:

| Dedicated Core | Responsibility |
|----------------|----------------|
| Cube Core | 矩阵运算 |
| TMA | 专用访存单元，负责数据搬运以及与外部内存交互 |
| Vector Core | 向量运算 |

### 1.3 Tile Register Centered CPU-Like Architecture

JCore 以 Tile Register 为数据交互中心。层次化指令集使计算任务可以被高效分解并并行处理:

- BCC 负责块头解析、资源重命名、依赖解除和 block dispatch。
- 专用核负责块体或 TileOp 的具体执行。
- Tile Register 承担跨 core 的数据交换。
- Local Buffer / register 承担块内微指令间的数据传递。

---

## 2. Architecture Overview

DOT source: [diagrams/npu_gpu_fusion_top.dot](diagrams/npu_gpu_fusion_top.dot)

```text
            +----------------+
            |      BCC       |
            | block header   |
            | scalar compute |
            +--+------+---+--+
               |      |   |
       dispatch|      |   |dispatch
               v      v   v
        +------+  +---+---+  +----------------+
        | Cube |  |Vector |  |      TMA       |
        | Core |  | Core  |  | Tile Memory    |
        |      |  |       |  | Access Unit    |
        +---+--+  +---+---+  +--------+-------+
            |         |               |
            +---------+---------------+
                      |
               +------+------+
               | TileRegister|
               | / Unified   |
               | Buffer      |
               +------+------+
                      |
               Global Memory
```

---

## 3. Memory Hierarchy

从程序易用性看，内存层级越少越好。JCore 中数据存储按三个层级组织:

| Level | Name | Function | Hardware Mapping |
|-------|------|----------|------------------|
| L1 | Global Memory | JCore 下游全局存储，包括 BCC L2/LLC 等 | L2/LLC/DDR 等 |
| L2 | Tile Register | 块间传递数据 | Cube、TMA、Vector 三个执行单元间的数据交互 |
| L3 | Local Buffer / Register | 块内微指令间传递数据 | Vector/BCC 内部寄存器，Cube L0A/L0B/L0C Buffer 等 |

注: 原始材料中第三项也写为“第二级”，本整理稿按层级语义标为 L3。

DOT source: [diagrams/jcore_memory_hierarchy.dot](diagrams/jcore_memory_hierarchy.dot)

---

## 4. Instruction Hierarchy

JCore 指令被划分成三个层级:

| Level | Name | Description |
|-------|------|-------------|
| L1 | TileOp / 块 | 宏指令，表达一系列操作。块的具体操作在块内定义；TileOp 是已经约定好的一段指令，由硬件自行产生。块头可定义循环次数，一次或多次执行。 |
| L2 | Group | TileOp/块由一个或多个 Group 组成。一个 Group 相当于 TileOp/块循环定义中的 VecLen 次执行，执行次数由硬件 Vector Length 定义。 |
| L3 | MicroOp | 每个 Group 由一条或多条 MicroOp 组成。每条 MicroOp 是 LinxISA 定义的块内微指令。 |

DOT source: [diagrams/jcore_instruction_hierarchy.dot](diagrams/jcore_instruction_hierarchy.dot)

---

## 5. Thread Model

JCore 整体为单线程模型:

- BCC，包括 Scalar Core，为单线程。
- Vector PE 和 TMA 访存单元支持多线程执行。
- Cube 较特殊，由于内部数据流较流畅，当前暂不考虑多线程 Cube。

当 SIMT block 或 TileOp 下发到各 PE 时，每个 PE 前的 Block Dispatch 模块根据迭代次数，将任务拆解为多个 Group，并均分到多个线程上执行，以提高并行度。Vector 侧 Group 分解和调度见 [07_Vector_Core_Architecture.md](07_Vector_Core_Architecture.md)。

---

## 6. Instruction Interface

JCore 中各 PE 的指令全部来自 BCC，一般以两种形式出现:

| Form | Description |
|------|-------------|
| TileOp | 模板块。块头提供操作类型、源操作数、目的操作数、操作数格式、迭代次数等信息。该指令在解除依赖后发往对应 PE，由 PE 内部硬件生成具体指令序列。 |
| 通用块头 | 非模板块。块头提供块体输入输出寄存器、块体指令 PC、操作数格式、迭代次数等信息。该块头在解除依赖后下发给 PE，PE 基于块体 PC 获取微指令。 |

Master Scalar Core 对其他 PE 的 instruction interface 需要携带:

| Field | Description |
|-------|-------------|
| TileOp ID / BID / RID | TileOp 或 block 的标识，用于 resolve/flush/commit |
| type | Vector/Cube/TileTransfer/MCALL 等类型 |
| Element Type | 元素位宽编码 |
| Src/Dest | Tile Register 源/目的 idx，即 buffer ID |
| size / LB0/LB1/LB2 | TileOp size 或循环维度 |
| block body PC | 通用块头的块体取指地址 |
| reg src/dst ptag | B.IOR 描述的 Global/GPR 参数 |

Element Type:

| Encoding | Meaning |
|----------|---------|
| 0 | 8-bit element |
| 1 | 16-bit element |
| 2 | 32-bit element |

Src/Dest:

- 表示源 Tile Register idx 与目的 Tile Register idx。
- 位宽为 12 bit。
- 对齐粒度为最小元素在 Vector Lane 长度上的位宽，即 `8-bit x 64-lane = 512-bit`。
- 若 Tile Register 容量为 256KB，则 `256KB / 512-bit = 4096`，需要 12-bit ID。

注意: BCC TileRename AS 中也存在 1MB TileReg 总空间描述，二者需要在后续资源规格中统一，见 [99_Open_Issues.md](99_Open_Issues.md)。

---

## 7. Data Interfaces

JCore 中数据通路主要有两套。

### 7.1 DDR <-> Tile Register

该通路主要通过 TMA 访存单元中的 LSU 模块交互。TMA 根据指令指示，对外部内存进行读写。

接口当前为 TBD，后续可能根据 LINX 核代码调整。

### 7.2 Cube / Vector / TileTransfer <-> Tile Register

该通路负责块间传递数据:

- 各 PE 读取 Tile Register 获取操作数。
- 各 PE 执行结果最终写入 Tile Register。
- PE 通常作为 Master 向 Slave，即 Tile Register，发起读写操作。
- Tile Register 读写口资源有限，各 PE 在出口处通常维护读写请求 buffer。
- Tile Register 由 SRAM 搭建，多请求同时访问可能产生 bank conflict。
- PE 与 Tile Register 之间存在 retry/cancel 通道，用于通知 PE 重新发送发生 bank conflict 的请求。

Tile Register 具体结构见 [08_Tile_Register_Unified_Buffer.md](08_Tile_Register_Unified_Buffer.md)。

---

## 8. Control Interfaces

### 8.1 Resolve Channel

PE 通过 Resolve Channel 返回 resolve 信息。当 block/TileOp 在 PE 完成时，PE 告知 BCC:

- 该指令已完成。
- 相关数据已写回 Tile Register。
- 可以 wakeup 依赖该 TileOp / block 的后续操作。

### 8.2 Flush Channel

若 BCC 前端出现跳转预测失败或其他 flush 条件，BCC 将发出 Flush 信号，用于取消投机发射的指令。该信号通过 Flush Channel 传递给各 PE。

当前 Master-Slave Flush 接口暂时保留为从 Master 发至 Slave 的单通道 Flush。

### 8.3 Global Register Path

Global 寄存器通路仍为 TODO。Vector Get 指令读取 Global Register 的细化流程见 [07_Vector_Core_Architecture.md](07_Vector_Core_Architecture.md)。

---

## 9. Master Scalar Core Scope

Master Scalar Core 整体保持与 GFU Scalar 一致。本 AS 对 Master Scalar Core 的关注重点:

- Buffer / Tile Register Rename。
- 基于 TileOp 的 ISQ / BIQ。
- TileOp 和 block 级依赖解除方式。
- Master-Slave Core Interface。

---

## 10. TileOp Issue Queue and Slave Core Interface

Block Issue Queue 模块存储 Buffer Rename 完成的 TileOp，并将 TileOp 发送至对应 core 执行。上游经过 buffer rename 的 TileOp 顺序下发至本单元，而从 TileOp Issue Queue 发射到下游执行核的 TileOp 可乱序执行。

### 10.1 TIQ Topology

TIQ 内包括三种 Issue Queue:

| Queue | Downstream |
|-------|------------|
| Vector ISQ | Vector Core |
| Cube ISQ | Cube Core |
| TMA ISQ | TMA |

### 10.2 TIQ Issue Conditions

发射条件:

- UB buffer ready。
- 非线程 barrier 状态。
- UB buffer 信息由 Buffer Rename 配置。
- UB ready 可由 TileOp UB resolve wakeup。

发射方式:

- Ready 前提下按 AgeMatrix FIFO 规则发射。
- 当下游 Core 可接收请求时，每拍最多发射:
  - 1 个 Vector TileOp。
  - 1 个 TMA TileOp。
  - 1 个 Cube TileOp。

### 10.3 TileOp Payload

TIQ 传递给下游的 TileOp 信息包括:

| Field | Description |
|-------|-------------|
| rid | TileOp / block 在上游的顺序标识 |
| tid | 线程标识 |
| type | TileOp 类型 |
| UB src0/1/2 id | 源 Tile/UB id |
| UB src0/1/2 addr | 源 Tile/UB 地址 |
| src ready | 源是否 ready |
| UB dst addr | 目的 Tile/UB 地址 |
| size | 操作规模或空间大小 |

TileOp 执行完毕后，下游执行单元返回带 rid 相关的信息。

### 10.4 Flush / Replay

TIQ 支持 flush:

- 接收 ROB/BROB 的 flush 请求。
- 清除无效投机 TileOp。
- 下游 Core 向 ROB/BROB 返回 TileOp resolve 标记时，随路返回 UB id 和 UB 地址。
- TIQ 内部 entry 对该 id 和地址执行 wakeup。

### 10.5 Master-Slave IQ Interface

Master to Slave:

- TileOp ID。
- type。
- TileReg src id/addr。
- TileReg dst id/addr。
- size。

Slave to Master:

- backpressure。
- resolve。
- TileOp 随路信息。

当 slave core 无法接收新请求时，反压 TIQ，master 停止向该 slave core 发送请求。

TIQ 上游入口:

- buffer rename 输出的 rid/tid/type/size/UB src/UB dst。
- UB map ready 信息。

TIQ 下游出口:

- TileOp payload。
- dispatch valid/ready。
