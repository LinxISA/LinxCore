# Janus CUBE (矩阵加速器) 微架构规格书

> 版本: 2.0
> 日期: 2026-06-05
> 状态: 设计中
> 实现代码: `LinxCore/srcs/cube/` (待实现)

---

## 1. 概述

### 1.1 CUBE 在 Janus 中的定位

Janus 是一个 AI 执行单元，由以下五个核心模块组成：

| 模块 | 全称 | 功能 |
|------|------|------|
| **BCC** | Block Control Core | 标量控制核，负责指令调度与流程控制 |
| **TMU** | Tile Management Unit | Tile 寄存器文件管理单元，通过 Ring 互联提供高带宽数据访问 |
| **VectorCore** | 向量执行核 | 执行向量运算（load/store 通过 TMU 访问 TileReg） |
| **Cube** | 矩阵乘计算单元 | 基于 Systolic Array 的矩阵乘法引擎 |
| **TMA** | Tile Memory Access | 负责 TileReg 与外部 DDR 之间的数据搬运 |

CUBE 是 Janus 的**矩阵乘法加速引擎**，专为高吞吐量 GEMM（General Matrix Multiplication）和卷积操作设计。采用 16×16 脉动阵列架构，支持 Fractal 拆分和 uop 级乱序执行。

### 1.2 设计目标

- **峰值算力**: 
  - FP16: 4,096 MACs × 2 ops × 1.5 GHz = 12.3 TFLOPS
  - FP8: 8,192 MACs × 2 ops × 1.5 GHz = 24.6 TOPS
  - FP4: 32,768 MACs × 2 ops × 1.5 GHz = 98.3 TOPS
- **L0 Cache**: L0A 64 KB + L0B 64 KB，4-way 组相联
- **ACC Pool**: 128 KB，支持 2-4 条并发 ACC 链
- **ISQ 深度**: 32 uop，乱序调度
- **数据格式**: FP16/BF16/FP8/FP4 多精度支持

### 1.3 与 BCC / TMU / Vector AS 的最新对齐

- **CUBE 是单一计算单元**：TMU 的 node1/node3 分别是 CUBE 的读/写端口，不代表多个物理 CUBE 实例。ACC chain (0-3) 是逻辑累加链标记，用于依赖管理，不表示多个物理 CUBE。
- **指令由 BCC 派发**：CUBE 接收 BCC BIQ (Block Issue Queue) 派发的 tile 级指令（tmatmul/tmatmul.acc/acccvt）。BCC 保证块间依赖已消解，CUBE 内部处理块内 uop 级依赖。
- **TileReg 地址由 BCC TileRename 分配**：CUBE 消费的 tA/tB/tC 是 BCC TileRename 后的物理 Tile tag、base address、size。CUBE 不负责 Tile 级 rename。
- **数据访问通过 TMU Ring**：CUBE 通过 TMU node1 (读) 和 node3 (写) 访问 TileReg。每个请求/响应为 256 B flit，遵循 TMU Ring 协议。
- **块完成回报给 BCC**：CUBE 执行完一个 tile 指令后，向 BCC BROB (Block Reorder Buffer) 回报完成，BCC 负责块级依赖唤醒和提交。
- **与 Vector 无直接交互**：CUBE 和 Vector 都是 compute PE，通过 TileReg 间接交换数据，不存在直接的 CUBE → Vector 数据通路。

---

## 2. 顶层架构

### 2.1 系统框图

```
                    ┌─────────────────────────────────────────────┐
                    │                   CUBE                      │
                    │                                             │
  BCC ──(tile cmd)──┤ Tile Cmd Buffer (depth=4)                  │
                    │   ↓                                         │
                    │ FSM & Fractal Split                        │
                    │   ↓                                         │
                    │ ISQ (32 uop, out-of-order)                 │
                    │   ↓                                         │
                    │ ┌─────────┐  ┌─────────┐                   │
                    │ │ L0A     │  │ L0B     │                   │
   TMU node1 ◄──────┤ │ 64 KB   │  │ 64 KB   │                   │
   (read)           │ │ 4-way   │  │ 4-way   │                   │
                    │ └────┬────┘  └────┬────┘                   │
                    │      ↓            ↓                         │
                    │ ┌─────────────────────────┐                │
                    │ │  16×16 Systolic Array   │                │
                    │ │  4096 MACs (FP16)       │                │
                    │ └──────────┬──────────────┘                │
                    │            ↓                                │
                    │ ┌─────────────────────────┐                │
                    │ │  BufferC (K-Accum)      │                │
                    │ │  1 KB                   │                │
                    │ └──────────┬──────────────┘                │
                    │            ↓                                │
                    │ ┌─────────────────────────┐                │
                    │ │  ACC Pool (128 KB)      │                │
                    │ │  128 slices × 1 KB      │                │
                    │ └──────────┬──────────────┘                │
                    │            ↓                                │
                    │ ┌─────────────────────────┐                │
   TMU node3 ◄──────┤ │  FixPipe (11 stages)    │                │
   (write)          │ │  nz→nd, quant, rowmax   │                │
                    │ └─────────────────────────┘                │
                    └─────────────────────────────────────────────┘
```

### 2.2 关键模块说明

| 模块 | 功能 | 容量/规格 |
|------|------|----------|
| **Tile Cmd Buffer** | 接收 BCC 派发的 tile 指令 | 4 entries |
| **FSM** | 从 Cmd Buffer 取指令，拆分为 16×16 uop | - |
| **ISQ** | uop 调度队列，乱序执行 | 32 entries |
| **L0A Cache** | A 矩阵数据缓存 | 64 KB, 4-way, 512B/entry |
| **L0B Cache** | B 矩阵数据缓存 | 64 KB, 4-way, 512B/entry |
| **MAC Array** | 16×16 脉动阵列 | 4096 MACs (FP16) |
| **BufferC** | K 方向中间累加器 | 1 KB (16×16×FP32) |
| **ACC Pool** | 物理累加器池 | 128 KB, 128 slices |
| **FixPipe** | 格式转换流水线 | 11 stages |

### 2.3 数据流概述

1. **指令接收**: BCC 派发 tmatmul/tmatmul.acc/acccvt 指令
2. **Fractal 拆分**: FSM 将 M×N×K tile 拆分为多个 16×16 uop
3. **uop 入队**: uop 进入 ISQ，等待 src ready (L0 Cache hit)
4. **数据预取**: L0 Cache 通过 TMU node1 预取 A/B 数据
5. **乱序发射**: ISQ 选择 ready uop 发射到 MAC 阵列
6. **计算**: MAC 阵列执行 16×16 矩阵乘法
7. **中间累加**: BufferC 累加 K 方向的部分结果
8. **写 ACC**: K 累加完成后写入 ACC Pool (RMW, 9 cycles)
9. **格式转换**: acccvt 通过 FixPipe 转换格式并写回 TileReg

---

## 3. ISA 接口

### 3.1 tmatmul（矩阵乘法）

**指令格式**：
```
tmatmul zd, tA, tB, shape, acc_chain
```

**语义**：
```
zd = A × B
```

**操作数**：
- `zd`: 目标累加器（架构累加器，逻辑标识）
- `tA`: A 操作数的 TileReg 索引（BCC TileRename 后的物理地址）
- `tB`: B 操作数的 TileReg 索引
- `shape`: 输出形状（如 64×64，128×128）
- `acc_chain`: ACC 链标记（0-3）

**硬件行为**：
1. FSM 拆分为 (M/16) × (N/16) × (K/16) 个 uop
2. 为 zd 分配物理 ACC slices
3. uop 进入 ISQ 等待调度
4. 结果存储为 nz (Natural Z-curve) 格式

### 3.2 tmatmul.acc（矩阵乘法累加）

**指令格式**：
```
tmatmul.acc zd, tA, tB, shape, acc_chain
```

**语义**：
```
zd = zd + A × B
```

**C 的隐式传递**：
- `zd` 的当前值来自前一个 `tmatmul` 或 `tmatmul.acc`
- 硬件通过 `acc_chain` 标记跟踪 ACC 依赖
- BCC BIQ 保证同一 chain 的指令顺序派发

**依赖管理**：
- **粗粒度依赖**（指令级）：BCC 保证前一个 tmatmul 的块完成后才派发 tmatmul.acc
- **细粒度依赖**（uop级）：CUBE 内部通过 ACC 映射表记录每个输出位置的 last_uop_id，支持流水线并行

**示例**：
```
tmatmul z0, tA0, tB0, 64×64, chain=0      // z0 = A0 × B0
tmatmul.acc z0, tA1, tB1, 64×64, chain=0  // z0 += A1 × B1
tmatmul.acc z0, tA2, tB2, 64×64, chain=0  // z0 += A2 × B2
acccvt z0, tC, mode=nz2nd, chain=0        // 转换并写回
```

### 3.3 acccvt（累加器转换和搬运）

**指令格式**：
```
acccvt zd, tC, mode, acc_chain
acccvt.rowmax zd, tC, tRowmax, mode, acc_chain
```

**语义**：
```
tC = convert(zd, mode)           // 基本转换
tRowmax, tC = rowmax(zd, mode)   // 带 rowmax
```

**mode 参数**：
- `nz2nd`: nz 格式转 nd (Natural Data) 格式
- `quant_fp16/fp8/int8`: 量化到指定精度

**硬件行为**：
1. 拆分为多个 acccvtuop（每个 uop 处理 1 个 ACC slice）
2. 通过 FixPipe (11 stages) 执行格式转换
3. 通过 TMU node3 写回 TileReg

---

## 4. Fractal 拆分与 uop 生成

### 4.1 Fractal 拆分规则

CUBE 使用固定的 **16×16 小分形（small fractal）**：

**拆分公式**：
```
M_tiles = M / 16
N_tiles = N / 16
K_tiles = K / 16

uop_count = M_tiles × N_tiles × K_tiles
```

### 4.1 Fractal 拆分规则

CUBE 使用固定的 **16×16 小分形（small fractal）**：

**拆分公式**：
```
M_tiles = M / 16
N_tiles = N / 16
K_tiles = K / 16

uop_count = M_tiles × N_tiles × K_tiles
```

**uop 结构**：
```
uop = {
    m_idx: [0..M_tiles-1],
    n_idx: [0..N_tiles-1],
    k_idx: [0..K_tiles-1],
    src_A: tA + (m_idx × 16 + k_idx × 16×M),
    src_B: tB + (k_idx × 16×N + n_idx × 16),
    dst_slice: slice_id,
    is_first_k: (k_idx == 0),
    is_last_k: (k_idx == K_tiles-1)
}
```

### 4.2 拆分示例

**64×64×128 矩阵乘法**：
```
M=64, N=64, K=128
M_tiles = 4, N_tiles = 4, K_tiles = 8
uop_count = 4 × 4 × 8 = 128 uop
```

**输出位置 [0,0] 的 K 累加链**：
```
uop[0,0,0]: A[0,0] × B[0,0] → BufferC = result
uop[0,0,1]: A[0,1] × B[1,0] → BufferC += result
...
uop[0,0,7]: A[0,7] × B[7,0] → BufferC += result
                            → ACC[slice_0_0] = BufferC (RMW)
```

---

## 5. ISQ 与乱序调度

### 5.1 ISQ 组织

**容量**: 32 entries
**支持**: 2-4 条 ACC chain 并发

**Entry 结构**：
```
{
    valid: 1 bit,
    uop_id: 8 bits,
    m_idx: 4 bits,
    n_idx: 4 bits,
    k_idx: 4 bits,
    src_A_addr: 32 bits,
    src_B_addr: 32 bits,
    src_A_ready: 1 bit,
    src_B_ready: 1 bit,
    dst_slice: 7 bits,
    acc_chain: 2 bits,
    deps_valid: 1 bit,
    deps_uop_id: 8 bits
}
```

### 5.2 uop 调度策略

**发射条件**：
```
can_issue = valid && src_A_ready && src_B_ready && 
            (!deps_valid || deps_ready)
```

**调度顺序**：
1. **K-ordered**（同一输出位置）：K 方向的 uop 必须按顺序执行
2. **MN-unordered**（不同输出位置）：不同 (m,n) 的 uop 可以乱序执行

**优先级策略**：
- 块内数据（正在计算的块）：priority = 3
- 预取数据（prefetch）：priority = 2
- 块外数据（其他块）：priority = 1

### 5.3 乱序执行示例

**64×64 矩阵，K=32**：
```
Cycle 10: uop[0,0,0] 发射 (计算 A[0,0]×B[0,0])
Cycle 11: uop[1,1,0] 发射 (不同输出位置，可以乱序)
Cycle 12: uop[0,0,1] 发射 (依赖 uop[0,0,0]，K-ordered)
Cycle 13: uop[2,2,0] 发射 (继续乱序)
```

---

## 6. L0 Cache

### 6.1 组织结构

| 参数 | L0A (A 矩阵) | L0B (B 矩阵) |
|------|-------------|-------------|
| 容量 | 64 KB | 64 KB |
| 组织 | 4-way 组相联 | 4-way 组相联 |
| Entry 大小 | 512 B | 512 B |
| 总 Entry 数 | 128 | 128 |
| 替换策略 | PLRU | PLRU |

### 6.2 地址映射

**地址结构**：
```
[31:9]    [8:0]
  Tag     Index (512B offset)

Set Index = Index[8:2]  (7 bits, 128 sets)
Way = 4-way associative
```

### 6.3 预取机制

**触发时机**：FSM 拆分 uop 时生成预取请求

**预取 Buffer**：32 entries，每个 entry 对应一个 TMU 读请求 (256B)

**优先级策略**：
- 块内数据（priority=3）：当前正在计算的块的数据
- 预取数据（priority=2）：prefetch 请求
- 块外数据（priority=1）：其他块的数据

**Cache miss 处理**：
- uop 遇到 miss 时，维持 src_ready=0
- 预取请求发送到 TMU node1
- TMU 返回数据后填充 L0 Cache
- 更新 ISQ 中 uop 的 src_ready 位

---

## 7. MAC 阵列与计算

### 7.1 脉动阵列结构

**配置**：16×16 阵列

**计算能力**：
- FP16: 4096 MACs/cycle
- FP8: 8192 MACs/cycle (2× subword packing)
- FP4: 32768 MACs/cycle (8× subword packing)

**流水线深度**：约 7 stages

### 7.2 BufferC (K-Accumulator)

**作用**：减少 ACC RMW 操作

**容量**：
- 非 FP4: 1 KB (16×16×FP32)
- FP4: 2 KB (16×32×FP32)

**工作流程**：
```
for k in [0..K_tiles-1]:
    MAC_result = MAC_array(A[m,k], B[k,n])
    
    if (k == 0):
        BufferC = MAC_result        // 初始化
    else:
        BufferC += MAC_result        // 累加
    
    if (k == K_tiles-1):
        ACC[slice] = BufferC         // 写入物理 ACC (RMW, 9 cycles)
```

---

## 8. ACC Pool 管理

### 8.1 物理 ACC 组织

**容量**：128 KB
**组织**：128 slices × 1 KB (FP4: 64 slices × 2 KB)
**Bank 数**：8 banks (bank-interleaved)

### 8.2 ACC 映射表

**用途**：记录架构累加器 (zd) 到物理 slices 的映射

**结构**：
```
acc_mapping[acc_chain][zd] = {
    base_slice: 7 bits,
    slice_count: 7 bits,
    allocated: 1 bit,
    last_uop_id[m][n]: 8 bits  // 细粒度依赖跟踪
}
```

**容量管理**：
- **所有 ACC chain 共享 128 KB 总容量**，无固定分区
- 单条 ACC chain 最多可使用全部 128 KB（此时只能执行该链）
- 2-4 条 ACC chain 并发时，动态分配容量
- BCC 侧维护每个 chain 的使用量计数器，防止溢出

**分配策略**：
- Bank-aware allocation: 避免同一 bank 的连续分配
- 2-4 条 ACC chain 并发管理

### 8.3 ACC RMW 流水线

**延迟**：9 cycles
- 读取 1 KB：4 cycles (256 B/cycle)
- FP32 加法：1 cycle
- 写回 1 KB：4 cycles (256 B/cycle)

**带宽**：256 B/cycle

---

## 9. FixPipe 与格式转换

### 9.1 FixPipe 流水线

**深度**：11 stages

**功能**：
1. **nz → nd 格式转换**：Z-curve 自然顺序转为行主序
2. **量化**：FP32 → FP16/FP8/INT8
3. **rowmax**：计算每行最大值

### 9.2 acccvtuop 拆分

**拆分规则**：按 ACC slice 粒度拆分

**示例（64×64，16 个 slices）**：
```
tmatmul z0, ... → 16 个 slices (0-15)
acccvt z0, tC, mode=nz2nd, chain=0

拆分为：
acccvtuop[0]: slice 0 → tC offset 0
acccvtuop[1]: slice 1 → tC offset 1KB
...
acccvtuop[15]: slice 15 → tC offset 15KB
```

### 9.3 TileStore 接口

**通道**：TMU node3 (write)
**带宽**：256 B/cycle
**Queue 深度**：8 entries

---

## 10. BCC 接口

### 10.1 块命令接收接口

CUBE 接收来自 BCC BISQ 派发的块命令（tile 级指令）。

**Tile Command Buffer**：
- **深度**：4 entries
- **功能**：缓冲来自 BCC 的块命令，避免 BCC 与 CUBE FSM 之间的时序耦合
- **Entry 内容**：完整的 tile 命令（包括下述所有 `cube_cmd_*` 信号）
- **反压机制**：Buffer 满时，`cube_cmd_ready` 拉低，阻止 BCC 继续派发
- **FSM 取指**：FSM 从 Buffer 头部取出命令进行 Fractal 拆分

**接口信号**：

| 信号 | 位宽 | 方向 | 说明 |
|------|------|------|------|
| `cube_cmd_valid` | 1 | input | 块命令有效 |
| `cube_cmd_ready` | 1 | output | CUBE 可接收命令 |
| `cube_cmd_type` | 3 | input | 指令类型（tmatmul=0, tmatmul.acc=1, acccvt=2） |
| `cube_cmd_iot` | packed | input | I/O Tile 描述（BCC TileRename 后） |
| `cube_cmd_shape` | 16 | input | 输出矩阵形状（如 64×64） |
| `cube_cmd_acc_chain` | 2 | input | ACC 链标记（0-3） |
| `cube_cmd_mode` | 4 | input | acccvt 模式（nz2nd, quant等） |
| `cube_cmd_brob` | 8 | input | BROB index，用于完成回报 |

**IOT 字段说明**（packed）：
```
cube_cmd_iot = {
    src_A_valid:  1 bit,
    src_A_tag:    8 bits,     // TileRename tag
    src_A_addr:   32 bits,    // TileReg 物理地址
    src_A_ready:  1 bit,      // src ready (应该在 BISQ 已消解)

    src_B_valid:  1 bit,
    src_B_tag:    8 bits,
    src_B_addr:   32 bits,
    src_B_ready:  1 bit,

    dst_valid:    1 bit,
    dst_tag:      8 bits,
    dst_addr:     32 bits,
    dst_size:     16 bits     // 输出大小（KB）
}
```

### 10.2 块完成回报接口

CUBE 执行完一个 tile 指令后，向 BCC BROB 回报完成。

**接口信号**：

| 信号 | 位宽 | 方向 | 说明 |
|------|------|------|------|
| `cube_done_valid` | 1 | output | 块执行完成 |
| `cube_done_brob` | 8 | output | 对应的 BROB index |
| `cube_done_status` | 4 | output | 完成状态（0=成功） |
| `cube_done_error` | 1 | output | 错误标志 |
| `cube_done_error_code` | 8 | output | 错误码（ACC溢出、TMU超时等） |

**Status 编码**：
```
0x0: 成功完成
0x1: ACC 分配失败
0x2: L0 Cache thrashing
0x3: TMU 超时
0x4-0xF: 保留
```

### 10.2.1 ACC 释放信号

**用途**：acccvt 执行过程中，每完成一个 acccvtuop 的写回，CUBE 向 BCC 发送 ACC 释放信号。

**接口信号**：

| 信号 | 位宽 | 方向 | 说明 |
|------|------|------|------|
| `cube_acc_release_valid` | 1 | output | ACC 释放信号有效 |
| `cube_acc_release_chain` | 2 | output | 对应的 ACC chain (0-3) |
| `cube_acc_release_size` | 16 | output | 释放的 ACC 大小（单位：KB 或 Bytes，待定） |

**工作流程**：
1. acccvt 指令拆分为多个 acccvtuop（每个 uop 处理 1 个 ACC slice）
2. 每个 acccvtuop 通过 FixPipe 完成格式转换并写回 TileReg
3. 每完成一个 acccvtuop 写回，CUBE 发送一次 `cube_acc_release_valid`
4. BCC 侧维护的 `acc_usage[chain]` 计数器递减对应大小
5. 所有 acccvtuop 完成后，发送 `cube_done_valid` 表示整个块完成

**时序示例（64×64 矩阵，16 个 slices）**：
```
Cycle N:   acccvtuop[0] 完成 → cube_acc_release_valid=1, size=1KB
Cycle N+K: acccvtuop[1] 完成 → cube_acc_release_valid=1, size=1KB
...
Cycle M:   所有 acccvtuop 完成 → cube_done_valid=1
```

### 10.3 Flush 接口

**接口信号**：

| 信号 | 位宽 | 方向 | 说明 |
|------|------|------|------|
| `cube_flush_valid` | 1 | input | Flush 请求有效 |
| `cube_flush_brob` | 8 | input | Flush 的 BROB 边界 |
| `cube_flush_older` | 1 | input | 1=flush older, 0=flush younger |

**Flush 行为**：
1. 清空 ISQ 中 younger/older uop
2. 保留符合条件的 uop 继续执行
3. 释放被 flush 的 ACC slices
4. 终止 L0 预取请求

---

## 11. TMU Ring 接口

### 11.1 与 TMU 的接口协议

CUBE 通过 TMU Ring 访问 TileReg：

| 端口 | TMU Node | 方向 | 用途 |
|------|----------|------|------|
| CUBE port0 | node1 | Read | L0A/L0B 数据加载 |
| CUBE port1 | node3 | Write | FixPipe 结果写回 |

### 11.2 Flit 格式

**数据粒度**：256 B per flit

**请求 Flit Meta**：
```
{
    valid: 1 bit,
    addr: 32 bits,       // TileReg 物理地址
    size: 8 bits,        // 256B 单位
    tag: 8 bits,         // 用于响应匹配
    priority: 2 bits     // 0-3
}
```

**响应 Flit Meta**：
```
{
    valid: 1 bit,
    tag: 8 bits,         // 匹配请求 tag
    data: 256 B
}
```

### 10.3 握手协议

**读请求**：
```
CUBE → TMU:
    cube_rd_req_valid
    cube_rd_req_addr
    cube_rd_req_tag

TMU → CUBE:
    cube_rd_req_ready
    
TMU → CUBE (响应):
    tmu_rd_rsp_valid
    tmu_rd_rsp_tag
    tmu_rd_rsp_data[255:0]
```

**写请求**：
```
CUBE → TMU:
    cube_wr_req_valid
    cube_wr_req_addr
    cube_wr_req_data[255:0]

TMU → CUBE:
    cube_wr_req_ready
```

---

## 12. 访问时序

### 12.1 单个 uop 延迟

**无 RMW 情况（K 中间步）**：
```
Cycle 0:  uop 发射，读取 L0A/L0B
Cycle 7:  MAC 阵列完成 (7-stage pipeline)
Cycle 8:  BufferC 累加完成
Total: 8 cycles
```

**有 RMW 情况（K 最后一步）**：
```
Cycle 0-7:  MAC 计算
Cycle 8:    BufferC 累加完成
Cycle 9-12: ACC RMW 读取 (4 cycles)
Cycle 13:   ACC 加法
Cycle 14-17: ACC RMW 写回 (4 cycles)
Total: 18 cycles (含 9-cycle RMW)
```

### 12.2 tmatmul 完整流程时序

**64×64×128 矩阵 (4×4×8 uop)**：

```
Cycle 0:     FSM 开始拆分
Cycle 1-10:  生成 128 uop，进入 ISQ
Cycle 10:    预取开始
Cycle 20:    首批 uop 的 L0 数据 ready
Cycle 20:    uop[0,0,0] 发射
Cycle 21:    uop[0,0,1] 发射 (K 方向顺序)
Cycle 28:    uop[0,0,0] 完成
Cycle 38:    uop[0,0,7] 完成，触发 ACC RMW
Cycle 47:    ACC RMW 完成，输出位置 [0,0] 完成
...
Cycle 900:   所有 uop 完成
Cycle 900:   向 BCC BROB 回报完成
```

### 12.3 L0 Cache 访问延迟

**Cache hit**：1 cycle
**Cache miss**：
- 发送 TMU 请求：1 cycle
- TMU Ring 延迟：2-8 cycles (取决于跳数)
- TMU SRAM 读取：4 cycles
- 返回延迟：2-8 cycles
- 总计：约 10-20 cycles

---

## 12. 性能模型

### 12.1 峰值性能

**@ 1.5 GHz**：

| 精度 | MACs/cycle | FLOPS/TOPS |
|------|-----------|-----------|
| FP16 | 4,096 | 12.3 TFLOPS |
| FP8 | 8,192 | 24.6 TOPS |
| FP4 | 32,768 | 98.3 TOPS |

### 12.2 典型工作负载

**GEMM 效率**：
- L0 Cache hit rate > 90%：接近峰值性能
- L0 Cache hit rate < 50%：性能下降 30-50%

**卷积**：
- 通过 Fractal 拆分映射为 GEMM
- Im2col 开销由软件/编译器处理

**Transformer 注意力**：
- Q×K^T：64×64 @ 1.5GHz，约 20 μs
- Scores×V：64×64 @ 1.5GHz，约 20 μs

### 12.3 带宽需求

**输入带宽（L0 → MAC）**：
- A: 512 B/cycle (预取)
- B: 512 B/cycle (FP4: 1024 B/cycle)
- 总计：1024 B/cycle (FP4: 1536 B/cycle)

**输出带宽（ACC → TileReg）**：
- FixPipe: 256 B/cycle
- TileStore: 256 B/cycle

---

## 13. 错误处理

### 13.1 异常类型

| 异常 | 触发条件 | 处理方式 |
|------|---------|----------|
| **ACC 溢出** | 分配失败 | 阻塞 FSM，回压 BCC |
| **ISQ 满** | 32 entries 用尽 | 阻塞 FSM |
| **L0 Cache thrashing** | 频繁替换 | 性能下降，但正确性保证 |
| **TMU timeout** | 请求超时 | 错误信号上报 BCC |

### 13.2 Flush 处理

**Flush 来源**：BCC 分支预测错误或异常

**Flush 行为**：
1. 清空 ISQ 中 younger uop
2. 保留 older uop 继续执行
3. 释放 younger 的 ACC slices
4. 终止 L0 预取请求

---

## 14. 配置参数

### 14.1 可配置参数列表

| 参数 | 默认值 | 可选范围 |
|------|--------|---------|
| ISQ 深度 | 32 | 16-64 |
| L0A 容量 | 64 KB | 32-128 KB |
| L0B 容量 | 64 KB | 32-128 KB |
| ACC Pool 容量 | 128 KB | 64-256 KB |
| 并发 ACC Chain | 4 | 2-4 |
| Prefetch Buffer | 32 | 16-64 |
| TileStore Queue | 8 | 4-16 |

---

## 15. 实现注意事项

### 15.1 关键路径

1. **ISQ 调度路径**：src_ready 检测 → uop 选择 → 发射
2. **ACC RMW 路径**：读 SRAM → 加法 → 写 SRAM
3. **L0 Cache 查找**：tag compare → way select → data output

### 15.2 面积与功耗估算（5nm）

| 模块 | 面积估算 | 功耗估算 |
|------|---------|---------|
| MAC Array | ~2.5 mm² | ~15 W |
| L0 Cache (128 KB) | ~1.2 mm² | ~3 W |
| ACC Pool (128 KB) | ~1.2 mm² | ~2 W |
| ISQ + Control | ~0.5 mm² | ~1 W |
| FixPipe | ~0.3 mm² | ~1 W |
| **总计** | **~5.7 mm²** | **~22 W** |

### 15.3 验证要点

1. **功能验证**：
   - Fractal 拆分正确性
   - K-ordered MN-unordered 调度
   - ACC 依赖链正确性
   - nz↔nd 格式转换

2. **性能验证**：
   - L0 Cache hit rate
   - ISQ 利用率
   - 带宽饱和度

3. **边界条件**：
   - ISQ 满时背压
   - ACC 池耗尽
   - TMU 超时

---

## 16. 相关文档

### 16.1 Janus 系统文档

- `../TMU/TMU_SPEC_EN.md` - TMU Ring 协议和 TileReg 管理
- `../Vector/VECTOR_CORE_SPEC_EN.md` - Vector Core 架构
- `../BCC/00_BCC_Architecture.md` - BCC 块控制流

### 16.2 CUBE 详细设计文档

- `architecture.md` - CUBE 架构总览（主文档）
- `datapath.md` - 数据通路详细设计
- `isq.md` - ISQ 调度详细设计
- `l0cache.md` - L0 Cache 详细设计
- `accumulator.md` - ACC Pool 管理详细设计
- `acccvt.md` - FixPipe 和格式转换详细设计
- `diagrams/cube_architecture.md` - 架构图
- `WORK_IN_PROGRESS.md` - 工作进度追踪

### 16.3 ISA 文档

- LinxISA 仓库：`instructions/tmatmul.md`
- LinxISA 仓库：`instructions/acccvt.md`

---

## 附录 A：术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| **Fractal** | Fractal | 分形拆分，将大矩阵拆为固定大小的小块 |
| **uop** | Micro-operation | 微操作，16×16 矩阵乘法的最小执行单元 |
| **ISQ** | Issue Queue | 发射队列，uop 调度结构 |
| **ACC** | Accumulator | 累加器，存储中间计算结果 |
| **nz** | Natural Z-curve | Z-curve 自然顺序存储格式 |
| **nd** | Natural Data | 行主序（row-major）存储格式 |
| **RMW** | Read-Modify-Write | 读-修改-写操作 |
| **PLRU** | Pseudo-LRU | 伪最近最少使用替换算法 |
| **Flit** | Flow control unit | 流控单元，TMU Ring 的数据传输粒度 |

---

**文档版本**：Janus CUBE v2.0  
**最后更新**：2026-06-05  
**维护者**：Janus 架构团队
