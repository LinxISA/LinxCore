# Janus Vector Core 微架构规格书

> 版本: 1.0
> 日期: 2026-02-11
> 状态: 初稿

---

## 1. 概述

### 1.1 Vector Core 在 Janus 中的定位

Vector Core 是 Janus AI 执行单元中的**向量执行引擎**，负责执行 SIMT 编程模型下的向量运算。它接收来自 BCC（Block Control Core）通过 BISQ 派发的向量块命令，从 TMU TileReg 中读取数据，执行向量计算，并将结果写回 TileReg。

```
BCC ──(块命令)──> Vector Core ──(load/store)──> TMU Ring ──> TileReg
```

| 特性 | 规格 |
|------|------|
| 编程模型 | SIMT（Single Instruction, Multiple Threads） |
| 微架构模型 | SIMD（Single Instruction, Multiple Data） |
| 计算宽度 | 2048-bit（64 lanes × 32-bit FP32） |
| 每 lane 数据宽度 | 32-bit |
| Lane 数量 | 64 |
| 线程模型 | SMT4 + OoO + SIMD |
| 数据格式 | FP32（FP64/FP16/FP8 后续扩展） |

### 1.2 设计目标

- 每周期执行一条 2048-bit SIMD 指令（64 个 FP32 lane 锁步执行）
- 通过 TMU Ring 的 3 个端口（2 load + 1 store）访问 TileReg，匹配 Ring 256B 带宽
- 支持多层循环展开（LB0/LB1/LB2），自动将 SIMT 线程拆分为 64-lane group
- 与 BCC 的块控制流程兼容（BISQ 派发、BROB 完成回报）

---

### 1.3 与 BCC / TMU AS 的最新对齐

- **Vector 接收的是 BCC TileRename 后的块 payload**：`B.IOT` 中的软件 Tile alias 和 output size 在 BCC 内完成重命名，Vector 侧的 `ti#N/to#N` 只作为块内 Tile 入口的本地 alias，实际访问使用 BISQ 随路携带的 Tile tag、base address、size 与 ready 信息。
- **块间 TileReg 依赖在 BISQ 处消解**：Vector 接收块时，可以认为输入 TileReg 的跨块生产者已经 resolve；Vector 内部仍需处理块内 load/store ordering、group ordering、TBuffer hit/miss/cancel 与可能的 data forwarding。
- **块完成回报必须晚于 TileReg 写回**：Vector 内部 TBuffer 的 Modified 数据需要先写回 TMU TileReg，再向 BCC/BROB 返回 block resolve。这样 BCC Ready Table 唤醒后，消费者读取到的一定是已经落入 TileReg 的数据。
- **GPR/Uniform 通路独立于 Tile ready**：`B.IOR` 传入的 GPR ptag 由 BCC GPR rename/ready 体系管理。Vector 的 get/Uniform 读数需要依据 GPRST 或 BCC ptag wakeup 解除依赖，不能仅凭 TileReg ready 推断 GPR ready。
- **TMA 是外部访存单元，Cube 为单计算单元**：Vector 的 `pe_sel` 只用于选择目标 PE。`pe_sel=0` 对应 TMA，`pe_sel=1` 对应唯一 Cube 计算单元；ACC chain 是 Cube/BCC payload 中的逻辑依赖链字段，不表示多个 Cube 实例。
- **Group Dispatch 需消费 block max element size hint**：Loop Ctrl 依据块内最大 element size 决定 group 拆分和 mask 生成；64-bit element 需要在 OOO/执行侧占用两份 pipe 或寄存器资源来覆盖完整 lane 语义。

---

## 2. 块命令接口

### 2.1 块头格式

Vector Core 接收来自 BCC BISQ 派发的向量块命令。块头的抽象形式为：

```
vpar srcA(TA, TileA), srcB(TB, TileB) -> dstA(TO, TileO)
```

具体块头编码示例：

```
Bstart par VCALL
B.IOT   TA, TB -> TO        // 输入输出 TileReg 绑定
B.IOR   GPR0, GPR1 -> GPR2  // 输入输出 GPR 绑定
B.DIM0  64                   // 维度 0
B.DIM1  1                    // 维度 1
B.DIM2  1                    // 维度 2
B.TEXT  (PC)                 // 向量块微指令起始地址 //块头编码请参照linxISA仓库的指令编码
```

### 2.2 块头字段说明

| 字段 | 含义 |
|------|------|
| `Bstart par VCALL` | 块起始标记，指示这是一个并行向量调用块 |
| `B.IOT` | I/O Tile 描述符。软件视图中指定输入 Tile alias（TA, TB）和输出 Tile alias（TO）；BCC TileRename 后，Vector Core 通过 BISQ payload 中的物理 base address 读写 TileReg |
| `B.IOR` | I/O Register 描述符。指定输入/输出 GPR 绑定 |
| `B.DIM0/1/2` | 三层循环维度，分别对应 LB0/LB1/LB2（Loop Bound） |
| `B.TEXT` | 向量块块体微指令流的起始 PC 地址 |

### 2.3 块命令接收接口

| 信号 | 位宽 | 方向 | 含义 |
|------|------|------|------|
| `vec_cmd_valid` | 1 | input | 块命令有效 |
| `vec_cmd_ready` | 1 | output | Vector Core 可接收命令 |
| `vec_cmd_iot` | packed | input | I/O Tile 描述。BISQ 内部组合并打包 BCC TileRename 后的 Tile tag、base address、size、src ready 与 dst 信息，Vector Core 接收的是解码后的块 payload 语义 |
| `vec_cmd_ior` | 18 | input | I/O Register 描述：`[17:12] srcA, [11:6] srcB, [5:0] dst` |   //这些信息都组合在了BISQ里，vector收到的是打包的信息
| `vec_cmd_dim0` | 16 | input | Loop Bound 0（LB0） |
| `vec_cmd_dim1` | 16 | input | Loop Bound 1（LB1） |
| `vec_cmd_dim2` | 16 | input | Loop Bound 2（LB2） |
| `vec_cmd_pc` | 32 | input | 块体微指令起始 PC |
| `vec_cmd_brob` | 8 | input | BROB index，用于完成回报 |

### 2.4 完成回报接口

| 信号 | 位宽 | 方向 | 含义 |
|------|------|------|------|
| `vec_done_valid` | 1 | output | 块执行完成 |
| `vec_done_brob` | 8 | output | 对应的 BROB index |

---

## 3. SIMT 到 SIMD 的映射

### 3.1 编程模型（SIMT）

程序员以**单线程**视角编写向量块的微指令流，每条指令描述一个 lane（32-bit）的数据操作。示例：

```asm
l.lw    [ta, lc0<<2]       -> vt    // 从 TA 加载 FP32
l.lw    [tb, lc0<<2]       -> vt    // 从 TB 加载 FP32
l.lw    [tc, lc0<<2]       -> vt    // 从 TC 加载 FP32
l.fmadd vt#1, vt#2, vt#3   -> vt    // FP32 乘加
l.lw    [td, lc0<<2]       -> vt    // 加载被除数
l.fdiv  vt#2, vt#1         -> vt    // FP32 除法
l.fcvtfp32fp16 vt#1        -> vt    // FP32 转 FP16
l.sh    vt#1, [to, lc0<<1]          // 存储 FP16 到 TO
```

其中：
- `vt` 为相对索引寄存器的目的写形式，`vt#N` 表示在 `vt` hand 中相对于当前 allocate pointer 回看 N 个映射表项后得到的源寄存器
- `lc0/lc1/lc2` 为 Loop Counter，对应三层循环的迭代变量
- `ti#N` / `to#N` 为 TileReg 相对 alias，由块头 `B.IOT` 指定；Vector 内部通过 BISQ payload 中的 TileReg base address 与 size 访问物理 TileReg

### 3.2 Loop Counter 语义

三层循环的展开等价于：

```python
for lc2 in range(LB2):
    for lc1 in range(LB1):
        for lc0 in range(LB0):
            # 执行块体微指令流
            # 此时 lc0, lc1, lc2 为当前迭代值
```

总线程数 = `LB0 × LB1 × LB2`。

### 3.3 Group 拆分（SIMT → SIMD）

微架构上，Vector Core 每次执行 64 个 lane 的操作。总线程数被拆分为多个 **Group**，每个 Group 包含 64 个连续线程：

```
Group 数量 = ceil(LB0 × LB1 × LB2 / 64)
```

以 `LB0=128, LB1=2, LB2=1` 为例：
- 总线程数 = 128 × 2 × 1 = 256
- Group 数量 = 256 / 64 = 4
- 块体微指令流被重复取指 4 次，每次执行 64 个 lane

每个 Group 内，64 个 lane 的 `lc0/lc1/lc2` 值由 Group ID 和 lane ID 共同决定：

```
global_thread_id = group_id × 64 + lane_id    (lane_id ∈ [0, 63])
lc0 = global_thread_id % LB0
lc1 = (global_thread_id / LB0) % LB1
lc2 = (global_thread_id / (LB0 × LB1)) % LB2
```

### 3.4 SIMD 执行语义

一条 SIMT 指令在微架构上被合成为一条 2048-bit SIMD 指令：

| SIMT 指令 | SIMD 等效操作 |
|-----------|--------------|
| `l.lw [ta, lc0<<2] -> vt` | 从 TileReg 加载 64 × 32-bit = 2048-bit 数据到向量寄存器 |
| `l.fmadd vt#1, vt#2, vt#3 -> vt` | 64 路并行 FP32 乘加 |
| `l.sh vt#1, [to, lc0<<1]` | 将 64 × 16-bit = 1024-bit 数据写回 TileReg |

---

## 4. 相对索引寄存器（Clockhand）

### 4.1 概念

Vector Core 使用**相对索引寄存器**表达块内寄存器访问，但它不是“无需重命名”的直接 PRF 指针模型。相对索引属于架构编码视图，Rename 阶段仍需要把它转换为物理 `ptag`，并通过 SCLK/SMAP、MAPQ、CCLK/CMAP 和 ptag freelist 管理乱序执行、提交与 flush 恢复。

Vector 中需要管理三类寄存器资源：

| 类型 | 架构视图 | 物理资源 | 原始规格口径 |
|------|----------|----------|--------------|
| VRF | `vt/vu/vm/vn` 四个 hand，均使用相对索引 | 2048-bit Vector PRF，以 `ptag` 编址 | 每个 hand 索引距离为 4；SMT4 下每个执行流维护一套相对索引映射 |
| Scalar RF | `t/u` 两个 hand，均使用相对索引 | 64-bit Scalar PRF，以 `ptag` 编址 | 每个 hand 索引距离为 4；每个执行流维护一套映射 |
| Uniform Register | `arg/RI/RO` 类块形参/返回寄存器 | 与 Scalar RF 共用物理寄存器 | 每个存活 Vector block 维护一套 Uniform 映射，典型为 16 个 Uniform |

以 VRF 为例，`vt` 表示对 `vt` hand 分配新的目的寄存器映射；`vt#1`、`vt#2`、`vt#3`、`vt#4` 分别表示从该 hand 当前 allocate pointer 回看 1 到 4 个映射表项。它们不是直接访问某个按线程环绕的 PRF 物理地址，而是先索引 SCLK/SMAP 表项，再得到真实 `ptag`。

### 4.2 SCLK / MAPQ / CCLK

| 结构 | 作用 | 关键字段 |
|------|------|----------|
| SCLK / SMAP | 记录当前投机态下，相对索引 hand 到最新 `ptag` 的映射 | `vld`、`ptag`、`pair` |
| MAPQ | 记录尚未提交的投机映射更新，用于 commit 搬移和 flush 恢复 | `tid`、`hand`、`alloc_ptr`、`new_ptag`、`old_ptag/pair` |
| CCLK / CMAP | 记录已提交的相对索引 hand 状态 | `vld`、`ptag`、`pair` |

VRF 的 SCLK/CCLK 按线程私有实现，表项组织为 `(vt/vu/vm/vn 四个 hand × 每 hand 4 entries) × SMT4 = 64 entries`。每个线程每个 hand 类似一个小 FIFO，维护 2-bit `allocate_ptr` 和 `commit_ptr`。`allocate_ptr - 1` 到 `allocate_ptr - 4` 的表项分别对应 `vt#1` 到 `vt#4` 这类相对索引源操作数。

### 4.3 Ptag 分配与 FP64 pair

目的寄存器写入时，Rename 从物理寄存器 freelist 中分配 `ptag`，并将新映射写入 SCLK，同时把映射更新记录进 MAPQ。FP64 指令需要连续两个 2048-bit 物理寄存器，因此 ptag manage 侧维护普通 `ptag_fifo` 与 `pair_ptag_fifo` 两类分配入口；SCLK/CCLK 表项中的 `pair` bit 标识该映射占用一对物理寄存器。

提交时，MAPQ 中对应映射搬移到 CCLK/CMAP；被覆盖的旧 `ptag` 才能回收到 freelist。Flush 时，硬件从 CCLK/CMAP 和未被刷掉的 MAPQ 恢复 SCLK/SMAP，并回收年轻投机映射占用的 `ptag`。

---

## 5. 顶层架构

### 5.1 系统框图

```
                    ┌──────────────────────────────────────────────────────┐
                    │                   Vector Core                        │
                    │                                                      │
  BCC BISQ ────────>│  ┌──────────┐   ┌──────────┐   ┌──────────────┐    │
  (块命令)          │  │ Cmd Queue │──>│ Group    │──>│ Frontend     │    │
                    │  │          │   │ Splitter │   │ (IFU+Decode) │    │
                    │  └──────────┘   └──────────┘   └──────┬───────┘    │
                    │                                        │            │
                    │                                 ┌──────v───────┐    │
                    │                                 │ OoO Engine   │    │
                    │                                 │ (Rename+IQ+  │    │
                    │                                 │  Scheduler)  │    │
                    │                                 └──────┬───────┘    │
                    │                                        │            │
                    │                                 ┌──────v───────┐    │
                    │                                 │ Execution    │    │
                    │                                 │ Backend      │    │
                    │                                 │ (2048b SIMD) │    │
                    │                                 └──────┬───────┘    │
                    │                                        │            │
  TMU node0 <───── │── Load Port 0  (256B/cycle) ───────────┤            │
  TMU node2 <───── │── Load Port 1  (256B/cycle) ───────────┤            │
  TMU node4 <───── │── Store Port 0 (256B/cycle) ───────────┘            │
                    │                                                      │
  BCC BROB <─────── │── Done 回报                                         │
                    └──────────────────────────────────────────────────────┘
```

### 5.2 TMU 端口映射

Vector Core 通过 TMU Ring 的 3 个节点访问 TileReg：

| Vector 端口 | TMU Node | Ring Pipe | 用途 |
|-------------|----------|-----------|------|
| Load Port 0 | node0 | pipe0 | 第一个 load 通道（对应 TA） |
| Load Port 1 | node2 | pipe2 | 第二个 load 通道（对应 TB） |
| Store Port 0 | node4 | pipe4 | store 通道（对应 TO） |

每个端口带宽 256B/cycle，与 Ring flit 大小一致，也与 Vector Core 的 2048-bit 计算宽度匹配。

### 5.3 TMU 端口接口信号

每个端口遵循 TMU 节点的 valid/ready 握手协议（参见 TMU_SPEC 第 6.1 节）：

**Load Port（×2）**：

| 信号 | 位宽 | 方向 | 含义 |
|------|------|------|------|
| `req_valid` | 1 | output | 读请求有效 |
| `req_write` | 1 | output | 固定为 0（读） |
| `req_addr` | 20 | output | TileReg 字节地址 |
| `req_tag` | 8 | output | 请求标签 |
| `req_ready` | 1 | input | TMU 就绪 |
| `resp_valid` | 1 | input | 响应有效 |
| `resp_tag` | 8 | input | 响应标签 |
| `resp_data_w{0..31}` | 64 each | input | 256B 读数据 |
| `resp_ready` | 1 | output | Vector Core 就绪 |

**Store Port（×1）**：

| 信号 | 位宽 | 方向 | 含义 |
|------|------|------|------|
| `req_valid` | 1 | output | 写请求有效 |
| `req_write` | 1 | output | 固定为 1（写） |
| `req_addr` | 20 | output | TileReg 字节地址 |
| `req_tag` | 8 | output | 请求标签 |
| `req_data_w{0..31}` | 64 each | output | 256B 写数据 |
| `req_ready` | 1 | input | TMU 就绪 |
| `resp_valid` | 1 | input | 写完成响应 |
| `resp_ready` | 1 | output | Vector Core 就绪 |

---

## 6. 微架构流水线

### 6.1 流水线总览

Vector Core 采用 SMT4 + OoO + SIMD 架构。前端取指/译码规格与 BCC 保持一致（4-wide），后端执行宽度为 2048-bit。

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Vector Core Pipeline                         │
│                                                                     │
│  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐                     │
│  │ GS  │─>│ F0  │─>│ F1  │─>│ F2  │─>│ F3  │  Frontend           │
│  │Group│  │Fetch│  │ICache│  │ICache│  │Inst │                     │
│  │Split│  │ PC  │  │ Tag │  │ Data │  │ Buf │                     │
│  └─────┘  └─────┘  └─────┘  └─────┘  └──┬──┘                     │
│                                           │                         │
│  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌──v──┐                     │
│  │ F4  │─>│ D1  │─>│ D2  │─>│ REN │─>│ S1  │  Decode + Rename    │
│  │Align│  │ Dec │  │uOp  │  │CkHd │  │Ready│                     │
│  └─────┘  └─────┘  └─────┘  └─────┘  └──┬──┘                     │
│                                           │                         │
│  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌──v──┐                     │
│  │ S2  │─>│ P1  │─>│ I1  │─>│ I2  │─>│ E1  │  Issue + Execute    │
│  │Issue│  │Pick │  │RdRF │  │Bypas│  │Exec │                     │
│  └─────┘  └─────┘  └─────┘  └─────┘  └──┬──┘                     │
│                                           │                         │
│                              ┌─────┐  ┌──v──┐                     │
│                              │ W2  │<─│ W1  │  Writeback           │
│                              │WrRF │  │Fwd  │                     │
│                              └─────┘  └─────┘                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 Group Splitter（GS 阶段）

Group Splitter 是 Vector Core 特有的前端模块，负责将块命令拆分为多个 Group。

**功能**：
1. 接收块命令，解析 `LB0/LB1/LB2`
2. 计算总线程数 `N = LB0 × LB1 × LB2`
3. 计算 Group 数量 `G = ceil(N / 64)`
4. 为每个 Group 生成取指请求，PC 指向 `B.TEXT` 指定的块体起始地址
5. 维护 Group 计数器，所有 Group 执行完毕后向 BCC 回报完成

**寄存器**：

| 寄存器 | 位宽 | 含义 |
|--------|------|------|
| `group_id` | 16 | 当前 Group ID（0 ~ G-1） |
| `group_total` | 16 | 总 Group 数量 |
| `base_thread_id` | 32 | 当前 Group 的起始线程 ID（= group_id × 64） |
| `cmd_pc` | 32 | 块体微指令起始 PC |
| `cmd_iot` | 18 | 当前块的 IOT 描述 |
| `cmd_brob` | 8 | 当前块的 BROB index |
| `lb0/lb1/lb2` | 16 each | 三层循环边界 |

**状态机**：

```
IDLE ──(cmd_valid)──> SPLITTING ──(group_id == group_total-1 && group_done)──> DONE ──> IDLE
                          │                                                      │
                          └──────(group_done, group_id++)─────────────────────────┘
```

### 6.3 SMT4 线程管理

Vector Core 支持 4 个硬件线程（SMT4），每个线程可以独立执行一个 Group 或来自不同块命令的 Group。

| 线程 | 用途 |
|------|------|
| Thread 0 | Group 执行槽 0 |
| Thread 1 | Group 执行槽 1 |
| Thread 2 | Group 执行槽 2 |
| Thread 3 | Group 执行槽 3 |

SMT4 的目的是**隐藏 TMU 访问延迟**。当一个线程因 load 等待 TMU 响应而阻塞时，其他线程可以继续执行，保持执行单元的利用率。

**线程调度策略**：Round-Robin 轮询，每周期从就绪线程中选择一个发射。

### 6.4 Frontend（取指 + 译码）

Frontend 规格与 BCC 保持一致：

| 参数 | 值 |
|------|-----|
| 取指宽度 | 4 条指令/cycle |
| 译码宽度 | 4 条指令/cycle |
| ICache | 与 BCC 共享或独立（可配置） |
| 指令缓冲 | 每线程独立的指令 buffer |

**关键差异**：
- 取指 PC 由 Group Splitter 提供，而非分支预测器
- 块体微指令流是线性的（无分支），不需要分支预测
- 每个 Group 从相同的 PC 开始取指，执行相同的指令序列
- 需要为每个 SMT 线程维护独立的 PC 和指令 buffer

### 6.5 Decode（D1/D2 阶段）

**D1 阶段**：将取指得到的 LinxISA 向量指令解码为内部 UOP 格式。

向量指令的 UOP 字段：

| 字段 | 位宽 | 含义 |
|------|------|------|
| `opcode` | 8 | 操作码（l.lw, l.fmadd, l.fdiv 等） |
| `src1_rel` | 4 | 源操作数 1 的相对索引（vt#N 中的 N） |
| `src2_rel` | 4 | 源操作数 2 的相对索引 |
| `src3_rel` | 4 | 源操作数 3 的相对索引（用于 fmadd） |
| `has_dst` | 1 | 是否有写目标 |
| `is_load` | 1 | 是否为 load 指令 |
| `is_store` | 1 | 是否为 store 指令 |
| `tile_sel` | 2 | TileReg 选择（TA=0, TB=1, TC=2, TO=3） |
| `addr_lc` | 2 | 地址计算使用的 loop counter（lc0/lc1/lc2） |
| `addr_shift` | 3 | 地址偏移的移位量 |
| `thread_id` | 2 | 所属 SMT 线程 |

**D2 阶段**：将复杂指令拆分为 2-input 1-output 的微操作（与 BCC 一致）。

### 6.6 Rename（REN 阶段）— 相对索引到 ptag 的重命名

Vector Core 的相对索引寄存器仍然需要 rename。REN 阶段把 `vt#N/vu#N/vm#N/vn#N`、`t#N/u#N` 这类相对索引转换为物理 `ptag`，同时为目的寄存器分配新的物理资源。

基本流程如下：

1. Decode 给出源/目的寄存器所属 hand、相对索引 offset、是否 FP64 pair 等信息。
2. 源操作数查询 SCLK/SMAP：`src_ptag = SMAP[tid, hand, allocate_ptr - offset]`。同拍多条指令之间需要处理 RAW bypass，例如当前拍较老指令写 `vt`、较年轻指令读 `vt#1` 时，年轻指令应看到同拍新分配的 `ptag`。
3. 目的操作数从 `ptag_fifo` 或 `pair_ptag_fifo` 分配物理寄存器。FP64 目的寄存器分配一对连续 ptag，并在映射表项中置 `pair=1`。
4. 新目的映射写入 SCLK/SMAP 的当前 `allocate_ptr` 表项，然后该 hand 的 `allocate_ptr` 前移。
5. 该映射更新写入 MAPQ，等待 ROB/group commit 后搬移到 CCLK/CMAP。
6. Commit 时，CCLK/CMAP 更新为新映射，被覆盖的旧 ptag 回收到 freelist；flush 时，SCLK/SMAP 根据 CCLK/CMAP 和保留下来的 MAPQ 恢复。

**VRF 相对索引状态**：

| 状态 | 组织 | 含义 |
|------|------|------|
| `allocate_ptr[tid][hand]` | `SMT4 × 4 hand × 2-bit` | 指向该线程该 hand 的下一次目的映射写入位置 |
| `commit_ptr[tid][hand]` | `SMT4 × 4 hand × 2-bit` | 指向已提交 Clock 状态 |
| `SCLK/SMAP entry` | 64 entries | 当前投机态映射，包含 `vld/ptag/pair` |
| `CCLK/CMAP entry` | 64 entries | 已提交映射，包含 `vld/ptag/pair` |
| `MAPQ entry` | per thread partition | 尚未提交的映射更新，用于 commit 与 flush 恢复 |

Scalar RF 的 `t/u` hand 是上述机制的简化版；Uniform Register 与 Scalar RF 共用物理资源，但映射表按存活 block 维持独立上下文。

### 6.7 Issue + Execute（S1/S2/P1/I1/I2/E1 阶段）

**Issue Queue**：

| 参数 | 值 |
|------|-----|
| VALU IQ | 1 个，深度 16 |
| VMEM IQ | 1 个，深度 16（load/store 共享） |
| VFPU IQ | 1 个，深度 8（多周期浮点） |

**执行管线**：

> 注：当前 bring-up 先按简化时延执行：除 div 外计算类统一 4 cycle，div 统一 6 cycle。后续与完整 pipeline/RTL 对齐时再校正此表。

| 管线 | 数量 | 延迟 | 操作宽度 | 功能 |
|------|------|------|----------|------|
| VALU | 1 | 1 cycle | 2048-bit | 向量整数/逻辑运算 |
| VFMA | 1 | 4 cycles | 2048-bit | FP32 乘加（64 路并行 FMA） |
| VFDIV | 1 | 12+ cycles | 2048-bit | FP32 除法（流水化，吞吐 1/4） |
| VCVT | 1 | 2 cycles | 2048-bit | 格式转换（FP32↔FP16 等） |
| VMEM | 1 | variable | 2048-bit | Load/Store 到 TMU |

**时延校正表（留空，后续填写）**：

| 管线 | 实际延迟（cycle） | 备注 |
|------|-------------------|------|
| VALU |                   |      |
| VFMA |                   |      |
| VFDIV |                  |      |
| VCVT |                   |      |
| VMEM |                   |      |

---

## 7. 向量寄存器文件（Vector PRF）

### 7.1 规格

| 参数 | 值 |
|------|-----|
| 寄存器宽度 | 2048-bit（64 × 32-bit lanes） |
| 物理寄存器数量 | 初始按 128 entries 描述，精确深度作为面积/性能参数 |
| ptag 管理 | freelist bitmap + `ptag_fifo`；FP64 额外使用 `pair_ptag_fifo` |
| 读端口 | 原始口径为 5 个读口，按执行 pipe 与 store/load 需求做端口共享；精确绑定待 RTL 决定 |
| 写端口 | 原始口径为 4 个写口，load writeback 与 compute writeback 之间需要仲裁 |
| Bank 组织 | 4 bank，使用 ptag 的高位/低位参与 bank 选择 |

### 7.2 物理布局

```
Vector PRF (example: 128 entries × 2048-bit)
├── bank0: selected by ptag bits
├── bank1: selected by ptag bits
├── bank2: selected by ptag bits
└── bank3: selected by ptag bits
```

Vector PRF 不是按线程固定切成 `16 entries/thread` 的环形窗口。SMT4 的线程私有状态在 SCLK/SMAP、MAPQ、CCLK/CMAP 中维护；物理 PRF 通过 ptag freelist 在线程和 hand 间共享。每个 entry 内部由 64 个 32-bit lane 组成：

```
Entry[i] = { lane[0], lane[1], ..., lane[63] }
           = { 32'b, 32'b, ..., 32'b }
           = 2048-bit total
```

### 7.3 Bypass 网络

与 BCC 类似，执行结果可以通过 bypass 网络前递到 I2 阶段，避免等待写回 PRF：

- VALU → I2：1 cycle bypass
- VFMA → I2：需要等待 4 cycle 延迟
- VMEM → I2：需要等待 TMU 响应延迟

---

## 8. 访存单元（VMEM）

### 8.1 地址生成

VMEM 单元负责将 SIMT 指令中的地址表达式转换为 TileReg 物理地址。

对于指令 `l.lw [ta, lc0<<2] -> vt`，地址生成过程：

```
对于 Group 内的 64 个 lane（lane_id = 0..63）:
    global_tid = base_thread_id + lane_id
    lc0 = global_tid % LB0
    lc1 = (global_tid / LB0) % LB1
    lc2 = (global_tid / (LB0 × LB1)) % LB2

    byte_addr = TileReg_base[ta] + lc0 << shift
```

由于 64 个 lane 的数据在 TileReg 中是**连续存储**的（每个 lane 占 4B，64 lanes = 256B = 1 个 Ring flit），地址生成可以简化为：

```
flit_addr = TileReg_base[tile_sel] + group_id × 256
```

这恰好对应 TMU Ring 的一次 256B flit 传输。

### 8.2 Load 流程

```
1. VMEM 计算 TileReg 地址
2. 通过 Load Port 0 或 Load Port 1 向 TMU 发送读请求
   - req_valid = 1
   - req_write = 0
   - req_addr = flit_addr
   - req_tag = load_tag（用于匹配响应）
3. 等待 TMU 响应（延迟 = 4 + 2×H cycles）
4. 收到 resp_valid，将 256B 数据写入 Vector PRF
   - 32 个 64-bit word 重组为 64 个 32-bit lane
```

**双 Load Port 调度**：
- 当指令流中有两条连续 load（如 `l.lw [ta, ...]` 和 `l.lw [tb, ...]`），可以同时使用 Load Port 0 和 Load Port 1 并行发射
- 调度器根据 tile_sel 分配端口：TA → Port 0，TB → Port 1

### 8.3 Store 流程

```
1. VMEM 计算 TileReg 地址
2. 从 Vector PRF 读出 2048-bit 数据
3. 将 64 个 32-bit lane 重组为 32 个 64-bit word
4. 通过 Store Port 0 向 TMU 发送写请求
   - req_valid = 1
   - req_write = 1
   - req_addr = flit_addr
   - req_data_w{0..31} = 重组后的数据
5. 等待写完成响应
```

### 8.4 数据重组

TMU Ring 传输的数据格式为 32 × 64-bit word，而 Vector Core 内部为 64 × 32-bit lane。需要进行格式转换：

**Load（TMU → Vector PRF）**：
```
lane[0]  = resp_data_w0[31:0]
lane[1]  = resp_data_w0[63:32]
lane[2]  = resp_data_w1[31:0]
lane[3]  = resp_data_w1[63:32]
...
lane[62] = resp_data_w31[31:0]
lane[63] = resp_data_w31[63:32]
```

**Store（Vector PRF → TMU）**：
```
req_data_w0  = { lane[1], lane[0] }
req_data_w1  = { lane[3], lane[2] }
...
req_data_w31 = { lane[63], lane[62] }
```

### 8.5 Outstanding Load 管理

VMEM 维护一个 **Load Inflight Queue (VLIQ)**，跟踪已发出但未返回的 load 请求：

| 参数 | 值 |
|------|-----|
| VLIQ 深度 | 8 entries per load port |
| Tag 位宽 | 8-bit（与 TMU tag 一致） |

每个 entry 记录：
- `tag`：请求标签
- `dst_preg`：目标 PRF entry
- `thread_id`：所属线程
- `valid`：有效位

---

## 9. 执行单元详细规格

### 9.1 VALU（向量 ALU）

| 参数 | 值 |
|------|-----|
| 操作宽度 | 64 × 32-bit = 2048-bit |
| 延迟 | 1 cycle |
| 支持操作 | vadd, vsub, vand, vor, vxor, vsll, vsrl, vsra, vmov |  //各个指令延迟不同，目前你都按照4cycle来，但需要能够pipeline起来，除法是6cycle

### 9.2 VFMA（向量浮点乘加）

| 参数 | 值 |
|------|-----|
| 操作宽度 | 64 × FP32 = 2048-bit |
| 延迟 | 4 cycles（流水化，吞吐 1/cycle） |
| 支持操作 | vfadd, vfsub, vfmul, vfmadd, vfmsub, vfnmadd, vfnmsub |

内部结构：64 个并行的 FP32 FMA 单元，每个单元包含：
- 指数对齐
- 尾数乘法（24×24）
- 尾数加法
- 规格化 + 舍入

### 9.3 VFDIV（向量浮点除法）

| 参数 | 值 |
|------|-----|
| 操作宽度 | 64 × FP32 = 2048-bit |
| 延迟 | 12 cycles（非流水化，吞吐 1/4 cycle） |
| 支持操作 | vfdiv, vfsqrt |

### 9.4 VCVT（向量格式转换）

| 参数 | 值 |
|------|-----|
| 操作宽度 | 2048-bit input → variable output |
| 延迟 | 2 cycles |
| 支持操作 | vfcvt.fp32.fp16, vfcvt.fp16.fp32（后续扩展 FP8） |

**FP32 → FP16 转换**：
- 输入：64 × FP32 = 2048-bit
- 输出：64 × FP16 = 1024-bit（store 时只写 128B，占半个 flit）

---

## 10. Loop Counter 硬件

### 10.1 LC 生成器

每个 SMT 线程维护一组 Loop Counter 生成器，根据 `base_thread_id` 和 `lane_id` 计算每个 lane 的 `lc0/lc1/lc2` 值。

```
对于 lane_id ∈ [0, 63]:
    tid = base_thread_id + lane_id
    lc0[lane_id] = tid % LB0
    lc1[lane_id] = (tid / LB0) % LB1
    lc2[lane_id] = (tid / (LB0 × LB1)) % LB2
```

### 10.2 硬件实现

LC 生成器在 Group Splitter 阶段预计算，结果存入每线程的 LC 寄存器组：

| 寄存器 | 位宽 | 数量 | 含义 |
|--------|------|------|------|
| `lc0_vec` | 64 × 16-bit | 每线程 1 组 | 64 个 lane 的 lc0 值 |
| `lc1_vec` | 64 × 16-bit | 每线程 1 组 | 64 个 lane 的 lc1 值 |
| `lc2_vec` | 64 × 16-bit | 每线程 1 组 | 64 个 lane 的 lc2 值 |

地址生成时，VMEM 单元使用 `lc_vec` 计算 64 个 lane 的地址偏移。

### 10.3 简化情况

当 `LB0 ≥ 64` 且 `LB0` 是 64 的整数倍时（常见情况），同一 Group 内 64 个 lane 的 `lc0` 值恰好是连续的 `[base, base+1, ..., base+63]`，此时地址计算退化为简单的基地址 + 连续偏移，可以直接生成一个 256B 对齐的 flit 地址。

---

## 11. 块执行流程（端到端）

### 11.1 完整执行时序

以 `LB0=128, LB1=1, LB2=1` 的向量块为例（总线程 128，2 个 Group）：

```
Cycle 0:    BCC BISQ 派发块命令到 Vector Core
Cycle 1:    Cmd Queue 接收，Group Splitter 计算 G=2
Cycle 2-N:  Group 0 执行
              - GS 设置 base_thread_id=0, 生成 lc0=[0..63]
              - Frontend 从 B.TEXT 地址取指
              - Decode → Rename(SMAP/ptag) → Issue → Execute
              - VMEM load 从 TMU 读取 TA[0..255], TB[0..255]
              - VFMA 执行 64 路 FP32 乘加
              - VMEM store 将结果写入 TO[0..255]
Cycle N+1:  Group 0 完成，Group 1 开始
              - GS 设置 base_thread_id=64, 生成 lc0=[64..127]
              - 重复取指、执行、访存流程
Cycle M:    Group 1 完成，所有 Group 执行完毕
Cycle M+1:  Vector Core 向 BCC 回报 vec_done_valid=1, vec_done_brob=cmd_brob
```

### 11.2 SMT4 流水线交织

当有多个块命令排队时，SMT4 允许多个 Group 交织执行：

```
Cycle 0: Thread0-Group0 取指 | Thread1-Group1 执行 | Thread2-Group2 访存 | Thread3-Group3 写回
Cycle 1: Thread0-Group0 译码 | Thread1-Group1 写回 | Thread2-Group2 取指 | Thread3-Group3 执行
...
```

---

## 12. 参数汇总

### 12.1 可配置参数

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `LANES` | 64 | 每 Group 的 lane 数量 |
| `LANE_WIDTH` | 32 | 每 lane 数据位宽（FP32） |
| `SIMD_WIDTH` | 2048 | SIMD 操作宽度（= LANES × LANE_WIDTH） |
| `SMT_THREADS` | 4 | SMT 线程数 |
| `VRF_PTAG_ENTRIES` | 128 | 向量物理寄存器/ptag 数量，初始规格口径，可参数化裁剪 |
| `VRF_HANDS` | 4 | `vt/vu/vm/vn` 四个相对索引 hand |
| `VRF_INDEX_DISTANCE` | 4 | 每个 VRF hand 可回看的相对索引距离 |
| `VRF_SMAP_ENTRIES` | 64 | `SMT4 × 4 hand × 4 entries` |
| `SCALAR_RF_HANDS` | 2 | `t/u` 两个 scalar 相对索引 hand |
| `UNIFORM_REGS_PER_BLOCK` | 16 | 每个存活 Vector block 的 Uniform 寄存器映射数量 |
| `FETCH_WIDTH` | 4 | 取指宽度（指令/cycle） |
| `DECODE_WIDTH` | 4 | 译码宽度（指令/cycle） |
| `VALU_IQ_DEPTH` | 16 | VALU Issue Queue 深度 |
| `VMEM_IQ_DEPTH` | 16 | VMEM Issue Queue 深度 |
| `VFPU_IQ_DEPTH` | 8 | VFPU Issue Queue 深度 |
| `VLIQ_DEPTH` | 8 | Load Inflight Queue 深度（per port） |
| `ROB_DEPTH` | 32 | Reorder Buffer 深度（per thread） |

### 12.2 派生参数

| 参数 | 计算公式 | 默认值 |
|------|----------|--------|
| `PREG_WIDTH` | log2(VRF_PTAG_ENTRIES) | 7 |
| `THREAD_WIDTH` | log2(SMT_THREADS) | 2 |
| `GROUP_LANES` | LANES | 64 |
| `FLIT_BYTES` | LANES × LANE_WIDTH / 8 | 256 |

---

## 13. 约束与限制

### 13.1 当前版本限制

1. **仅支持 FP32**：FP64/FP16/FP8 数据格式后续扩展
2. **无分支**：向量块块体微指令流为线性代码，不支持分支指令  //暂不支持
3. **无标量操作**：Vector Core 内部不执行标量运算，标量由 BCC 处理 //有标量操作，Vector Core中有两个标量pipe，其中有一套标量的寄存器，
4. **固定 64 lane**：不支持动态 lane 数量调整
5. **LB0 建议为 64 的整数倍**：非整数倍时最后一个 Group 需要 lane mask 处理

### 13.2 Lane Mask

当总线程数不是 64 的整数倍时，最后一个 Group 的部分 lane 无效。需要 lane mask 机制：

```
last_group_active_lanes = total_threads - (group_total - 1) × 64
lane_mask = (1 << last_group_active_lanes) - 1
```

被 mask 掉的 lane：
- Load 操作：正常读取但结果丢弃
- 计算操作：正常执行但结果不写回
- Store 操作：不发出写请求（需要部分写支持或 mask store）

---

## 14. 与 BCC 的交互协议

### 14.1 块命令派发

```
BCC BCtrl ──(cmd_valid/ready)──> Vector Core Cmd Queue
```

BCC 的 BISQ 通过 `pe_sel` 字段区分目标 PE：
- `pe_sel = 0`：TMA
- `pe_sel = 1`：Cube
- `pe_sel = 2`：TAU
- `pe_sel = 3`：Vector（新增）

这里的 `pe_sel=1` 选择的是唯一 Cube 计算单元。ACC chain 是 Cube 负载中的逻辑依赖链字段，用于区分累加链的数据相关性；它不映射为多个 Cube 物理实例。`pe_sel=0` 对应专用 TMA 访存单元，负责 TileReg 与 DDR 的搬运。

### 14.2 完成回报

```
Vector Core ──(done_valid, done_brob)──> BCC BROB
```

Vector Core 在所有 Group 执行完毕后，将 `brob_idx` 回报给 BCC BROB，BROB 标记该块命令为 done。

### 14.3 B.IOR 寄存器传递

块头中的 `B.IOR` 指定了 GPR 绑定。BCC 在派发块命令时，将对应 GPR 的值作为 payload 传递给 Vector Core。Vector Core 可以将这些标量值广播到所有 lane 使用。

---

## 15. 文件结构规划

```
janus/pyc/janus/vec/
├── __init__.py
├── vec_core.py          # Vector Core 顶层
├── group_splitter.py    # Group Splitter + LC 生成器
├── vec_frontend.py      # 取指 + 译码（复用 BCC IFU 框架）
├── vec_rename.py        # 相对索引 SMAP/ptag rename
├── vec_issue.py         # Issue Queue + Scheduler
├── vec_exec.py          # 执行单元（VALU, VFMA, VFDIV, VCVT）
├── vec_mem.py           # VMEM 单元（地址生成 + TMU 接口）
├── vec_regfile.py       # 2048-bit 向量 PRF
├── vec_rob.py           # Vector ROB
└── vec_params.py        # 参数定义
```

---

## 附录 A: 向量指令集摘要

| 指令 | 格式 | 含义 |
|------|------|------|
| `l.lw` | `l.lw [tile, lc<<shift] -> vt` | 从 TileReg 加载 32-bit |
| `l.lh` | `l.lh [tile, lc<<shift] -> vt` | 从 TileReg 加载 16-bit |
| `l.sw` | `l.sw vt#N, [tile, lc<<shift]` | 存储 32-bit 到 TileReg |
| `l.sh` | `l.sh vt#N, [tile, lc<<shift]` | 存储 16-bit 到 TileReg |
| `l.fadd` | `l.fadd vt#A, vt#B -> vt` | FP32 加法 |
| `l.fsub` | `l.fsub vt#A, vt#B -> vt` | FP32 减法 |
| `l.fmul` | `l.fmul vt#A, vt#B -> vt` | FP32 乘法 |
| `l.fmadd` | `l.fmadd vt#A, vt#B, vt#C -> vt` | FP32 乘加 (A×B+C) |
| `l.fdiv` | `l.fdiv vt#A, vt#B -> vt` | FP32 除法 |
| `l.fcvtfp32fp16` | `l.fcvtfp32fp16 vt#A -> vt` | FP32 转 FP16 |
| `l.fcvtfp16fp32` | `l.fcvtfp16fp32 vt#A -> vt` | FP16 转 FP32 |
| `l.vadd` | `l.vadd vt#A, vt#B -> vt` | 整数加法 |
| `l.vsub` | `l.vsub vt#A, vt#B -> vt` | 整数减法 |
| `l.vand` | `l.vand vt#A, vt#B -> vt` | 按位与 |
| `l.vor` | `l.vor vt#A, vt#B -> vt` | 按位或 |
| `l.vxor` | `l.vxor vt#A, vt#B -> vt` | 按位异或 |

## 附录 B: 术语表

| 术语 | 全称 | 含义 |
|------|------|------|
| Vector Core | 向量执行核 | Janus 中的向量计算引擎 |
| SIMT | Single Instruction, Multiple Threads | 编程模型：单指令多线程 |
| SIMD | Single Instruction, Multiple Data | 微架构模型：单指令多数据 |
| Lane | 执行通道 | SIMD 中的一个并行计算单元 |
| Group | 线程组 | 64 个连续线程的集合，类似 NVIDIA warp |
| Clockhand | 时钟指针 | 相对索引寄存器编码视图；硬件仍通过 SMAP/CMAP 和 ptag rename 管理投机状态 |
| PRF | Physical Register File | 物理寄存器文件 |
| SCLK/SMAP | Speculative Clock Map | 当前投机态相对索引到 ptag 的映射表 |
| CCLK/CMAP | Committed Clock Map | 已提交相对索引到 ptag 的映射表 |
| MAPQ | Map Queue | 保存未提交寄存器映射更新的队列 |
| SMT | Simultaneous Multi-Threading | 同时多线程 |
| OoO | Out-of-Order | 乱序执行 |
| LB | Loop Bound | 循环边界 |
| LC | Loop Counter | 循环计数器 |
| VALU | Vector ALU | 向量算术逻辑单元 |
| VFMA | Vector Fused Multiply-Add | 向量融合乘加单元 |
| VFDIV | Vector Floating-point Divider | 向量浮点除法单元 |
| VCVT | Vector Convert | 向量格式转换单元 |
| VMEM | Vector Memory Unit | 向量访存单元 |
| VLIQ | Vector Load Inflight Queue | 向量 load 在途队列 |
| TMU | Tile Management Unit | Tile 管理单元 |
| TileReg | Tile Register File | Tile 寄存器文件 |
| BCC | Block Control Core | 块控制核 |
| BISQ | Block Issue Queue | 块发射队列 |
| BROB | Block Reorder Buffer | 块重排序缓冲 |
| TMA | Tile Memory Access | TileReg 与 DDR 之间的数据搬运单元 |
| TileRename | Tile Register Rename | BCC 中负责 TileReg 相对 alias 到物理地址、tag、size 映射的单元 |
| TBuffer | Temporal Buffer | Vector Core 内部缓存 TileReg 读写数据并管理 writeback 的临时缓冲 |
| GPRST | Global Physical Register Status Table | Vector get/Uniform 通路用于跟踪 BCC GPR ptag ready 状态的表 |
| ACC chain | Accumulate dependency chain | Cube/BCC 负载中的累加链逻辑依赖标记，不表示多个 Cube 实例 |
| Flit | Flow control unit | Ring 上传输的最小数据单位（256B） |


//有两个计算pipe，一个vector store pipe， vector load是不需要度向量寄存器的。
每个pipe对应一个isq，这意味着，一拍可以同时挑出两条vector alu指令和一条vector store指令；
之前告诉了你，有两个标量pipe，一拍中也可以同时挑出两条标量指令，其中，lw [ta, lc<<2] -> t，由于lane间的地址是连续的，所以只用给lsu发一个256B数据的起始地址，这种load走的也是标量pipe。
