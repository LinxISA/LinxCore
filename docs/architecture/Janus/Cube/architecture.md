# CUBE 矩阵加速器架构（Janus 版本）

**版本**：Janus CUBE v2.0  
**状态**：设计中  
**最后更新**：2026-06-02

本文档定义 Janus 异构引擎中 CUBE 矩阵加速器的架构。

---

## 1. 概述

### 1.1 CUBE 的角色

CUBE 是 Janus 的矩阵乘法加速引擎，专为高吞吐量 GEMM（通用矩阵乘法）设计。

**设计目标**：
- 高效的矩阵乘法（A×B 和 A×B+C）
- 支持多种数据格式（FP16/BF16/FP8/FP4）
- Fractal 拆分和 uop 级乱序执行
- 支持多 ACC 链并发

### 1.2 关键特性

**计算能力**：
- 4096 个 MAC 单元（16×16 阵列，16×16 MAC per bank）
- 脉动阵列：~7 级流水线
- 每拍 4096 MAC（FP16）

**数据格式支持**：
- FP16：4096 MACs
- FP8：8192 MACs（2× 算力）
- FP4：32768 MACs（8× 算力）

**架构特性**：
- Fractal 拆分（固定 16×16 小分形）
- uop 级 ISQ 调度和乱序执行
- L0A/L0B Cache 支持数据复用
- ACC 硬件自动管理
- 支持 2-4 个并发 ACC 链

---

## 2. ISA 接口

### 2.1 tmatmul（矩阵乘法）

**指令格式**：
```
tmatmul zd, tA, tB, shape, acc_chain
```

**语义**：
```
zd = A × B
```

**操作数**：
- `zd`：目标累加器（架构累加器）
- `tA`：A 操作数的 TileReg 索引
- `tB`：B 操作数的 TileReg 索引
- `shape`：输出形状（例如 64×64）
- `acc_chain`：ACC 链标记（0-3，支持 2-4 个并发链）

**执行流程**：
1. FSM 拆分为多个 uop（16×16 小分形）
2. uop 进入 ISQ，等待 src ready
3. uop 乱序发射到 MAC 阵列
4. 结果写入 ACC（1 KB per uop，nz 格式）

### 2.2 tmatmul.acc（矩阵乘法累加）

**指令格式**：
```
tmatmul.acc zd, tA, tB, shape, acc_chain
```

**语义**：
```
zd = zd + A × B
```
其中 `zd` 的当前值**隐式**来自前一个 `tmatmul` 或 `tmatmul.acc`。

**C 的隐式传递**：
- C 不需要在指令中显式指定
- CUBE 通过 `acc_chain` 标记跟踪前一个操作的 ACC
- 硬件自动建立依赖关系

**依赖关系**：
- 必须紧跟在前一个 `tmatmul` 或 `tmatmul.acc` 后面（指令序）
- BIQ 保证发射顺序
- 通过 `acc_chain` 标记建立依赖

**ACC 链示例**：
```
tmatmul z0, tA0, tB0, 64×64, chain=0      // z0 = A0 × B0
tmatmul.acc z0, tA1, tB1, 64×64, chain=0  // z0 += A1 × B1
tmatmul.acc z0, tA2, tB2, 64×64, chain=0  // z0 += A2 × B2
acccvt z0, tC, mode=nz2nd, chain=0        // 搬运 z0 到 TileReg
```

**K 方向依赖**：
- 在 K 方向上，uop 有依赖：A₀₀×B₀₀=C₀₀ → A₀₁×B₁₀+C₀₀=C₀₀
- 同一个输出位置的 uop 必须按 K 顺序执行
- 不同输出位置的 uop 可以乱序

### 2.3 acccvt（累加器转换和搬运）

**指令格式**：
```
acccvt zd, tile_dst, mode, acc_chain
acccvt.rowmax zd, tile_dst, tile_rowmax, mode, acc_chain
```

**语义**：
```
tile_dst = convert(zd, mode)
```
对于 `rowmax` 模式：
```
tile_dst = convert(zd, mode)
tile_rowmax = rowmax(zd)
```

**模式（mode）**：
- `nz2nd`：nz 格式 → nd 格式（行主序）
- `quant_fp16`：FP32 → FP16 量化
- `quant_fp8`：FP32 → FP8 量化
- `quant_int8`：FP32 → INT8 量化
- `rowmax`：计算每行最大值
- 可组合多个模式

**执行流程**：
1. FSM 拆分为多个 acccvtuop（按 ACC slice 粒度）
2. 建立与前面 matmul/matmul.acc 的 uop 依赖
3. matmul uop 完成 → 对应 acccvtuop wakeup
4. acccvtuop 通过 FixPipe 执行格式转换
5. TileStore 请求写回 TileReg
6. 写回完成后释放对应 ACC slice

**一一对应关系**：
- 一个 ACC 只支持一个 acccvt
- 软件保证每个 tmatmul/tmatmul.acc 链以 acccvt 结束

---

## 3. 顶层架构

### 3.1 CUBE 在 Janus 中的位置

```
BCC → BIQ (Block Issue Queue)
       ↓ (保序发射 tileop)
     BCTRL
       ↓ (命令分发)
    ┌──────┐
    │ CUBE │
    └───┬──┘
        ↓ (TileStore 请求)
      TMU
        ↓
    TileReg
```

### 3.2 CUBE 内部架构

```
bctrl_cube_cmd_*  (命令接口)
    ↓
┌─────────────────────────────────────────────────────────────────┐
│                          CUBE                                    │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐    │
│  │            命令解码和 FSM (Fractal 拆分)               │    │
│  │  - 解析 tmatmul/tmatmul.acc/acccvt                     │    │
│  │  - 拆分为 uop (16×16 小分形)                           │    │
│  │  - 生成 tileload 预取请求                              │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │              ISQ (Issue Queue)                          │    │
│  │  - 容量：32 uop (可配置)                               │    │
│  │  - 等待 src ready (L0A/L0B cache hit)                  │    │
│  │  - 乱序发射 (K 方向保序)                               │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │        L0A/L0B Cache (输入数据缓存)                    │    │
│  │  - L0A: A 操作数缓存                                   │    │
│  │  - L0B: B 操作数缓存                                   │    │
│  │  - 地址匹配 + PLRU 替换                                │    │
│  │  - 优先级规则（块内数据保留）                          │    │
│  │  - 预取支持                                            │    │
│  │                                                         │    │
│  │  读带宽：                                               │    │
│  │    L0A: 512 B/cycle                                    │    │
│  │    L0B: 512 B/cycle (FP4: 1024 B/cycle)               │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │           MAC 阵列 (16×16 脉动阵列)                    │    │
│  │  - 4096 MACs (FP16)                                    │    │
│  │  - 8192 MACs (FP8, 2× 算力)                            │    │
│  │  - 32768 MACs (FP4, 8× 算力)                           │    │
│  │  - 脉动计算：~7 级流水线                               │    │
│  │  - 每拍输出：16×16×FP32 = 1 KB (nz 格式)              │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │              BufferC (KACC)                             │    │
│  │  - K 方向中间累加                                       │    │
│  │  - 减少 ACC RMW                                         │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │           物理累加器池 (ACC Pool)                       │    │
│  │  - 容量：128 KB                                         │    │
│  │  - Slice 大小：1 KB (16×16×FP32)                       │    │
│  │  - Slice 数量：128 个                                   │    │
│  │  - uop 粒度分配和释放                                   │    │
│  │  - 可以不连续分配                                       │    │
│  │                                                         │    │
│  │  写带宽：256 B/cycle (nz 格式)                         │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │               FixPipe (格式转换)                        │    │
│  │  - nz → nd 格式转换                                     │    │
│  │  - 量化 (FP32 → FP16/FP8/INT8)                          │    │
│  │  - rowmax 计算                                          │    │
│  │  - 输出带宽：256 B/cycle                                │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │              TileStore (写回请求)                       │    │
│  │  - 每拍最多 1 个请求                                    │    │
│  │  - 支持多个请求在途                                     │    │
│  │  - 通过 TMU 写 TileReg                                  │    │
│  └────────┬───────────────────────────────────────────────┘    │
│           ↓                                                      │
└───────────┼──────────────────────────────────────────────────────┘
            ↓
cube_tmu_wr_*  (TileStore 接口)
cube_brob_rsp_*  (响应接口)
```

---

## 4. Fractal 拆分和 uop 生成

### 4.1 Fractal 拆分规则

**固定拆分规则**：按 MK/KN 方向拆分

**输入固定大小**：
- **A 操作数**：16 行 × 32 B = 512 B
- **B 操作数**：32 B × 16 列 = 512 B
- **FP4 特殊情况**：B 为 32 B × 32 列 = 1024 B

**输出固定大小**：
- **C 输出**：16×16×FP32 = 1 KB (nz 格式)
- **ACC slice**：
  - 非 FP4：1 KB per uop（16×16×FP32）
  - FP4：2 KB per uop（16×32×FP32，双倍算力）

### 4.2 拆分示例

**示例 1：64×64 矩阵乘法（FP16）**

```
tmatmul z0, tA, tB, shape=64×64

拆分：
M=64, K=?, N=64
→ M 方向：64/16 = 4 个分形
→ N 方向：64/16 = 4 个分形
→ K 方向：K/32 个分形（取决于实际 K）

假设 K=128（FP16，每元素 2B）：
→ K 方向：128/(32B/2B) = 128/16 = 8 个分形

总 uop 数 = 4 × 4 × 8 = 128 uop
```

**uop 组织**：
```
对于输出 C[i,j]（i=0..3, j=0..3）：
  for k in 0..7:
    uop[i,j,k]: C[i,j] += A[i,k] × B[k,j]
    
依赖关系（K 方向）：
  uop[i,j,0] 完成 → uop[i,j,1] 可发射
  uop[i,j,1] 完成 → uop[i,j,2] 可发射
  ...
```

**乱序机会**：
- 不同 (i,j) 的 uop 可以乱序
- 例如：uop[0,0,0] 和 uop[1,1,0] 可以并发
- 但 uop[0,0,1] 必须等待 uop[0,0,0] 完成

### 4.3 FSM 拆分流程

```
1. 命令解码
   - 解析 shape (M, N)
   - 从 TileReg 元数据获取 K
   
2. 计算分形数量
   - M_tiles = M / 16
   - N_tiles = N / 16
   - K_tiles = K / (32B / element_size)
   
3. 生成 uop
   for i in 0..M_tiles-1:
     for j in 0..N_tiles-1:
       for k in 0..K_tiles-1:
         uop[i,j,k] = {
           A_tile: tA,
           A_offset: i * 16 * K + k * 16,
           B_tile: tB,
           B_offset: k * 16 * N + j * 16,
           C_slice: alloc_acc_slice(),
           K_idx: k,
           deps: (k > 0) ? uop[i,j,k-1] : none
         }
         
4. uop 入队 ISQ
   
5. 生成预取请求
   - 提前预测需要的 A/B tile 地址
   - 发送 tileload 请求到 L0A/L0B buffer
```

---

## 5. ISQ（Issue Queue）和 uop 调度

### 5.1 ISQ 组织

**容量**：32 uop（可配置参数）

**条目结构**：
```verilog
struct isq_entry_t {
    valid:       1 bit;      // 条目有效
    uop_id:      8 bits;     // uop 标识
    A_addr:      ? bits;     // A 数据地址
    B_addr:      ? bits;     // B 数据地址
    C_slice_id:  7 bits;     // ACC slice ID (0-127)
    K_idx:       8 bits;     // K 维度索引
    deps:        8 bits;     // 依赖的 uop_id
    src_ready:   2 bits;     // [1]=A ready, [0]=B ready
    acc_chain:   2 bits;     // ACC 链标记 (0-3)
};
```

### 5.2 src ready 条件

**src ready 定义**：
- `A_ready`：L0A cache 中有对应地址的数据
- `B_ready`：L0B cache 中有对应地址的数据
- `src_ready = A_ready && B_ready`

**Cache miss 处理**：
```
1. uop 入队 ISQ
2. 检查 L0A/L0B cache
   - Cache hit：设置 ready 标志
   - Cache miss：发送 tileload 请求
3. tileload 完成后更新 ready 标志
```

### 5.3 uop 发射规则

**发射条件**：
```
uop 可以发射 ⇔ 
  (src_ready == 2'b11) &&
  (K_idx == 0 || deps 已完成) &&
  MAC 阵列空闲
```

**K 方向依赖**：
- K_idx = 0：无依赖，src ready 即可发射
- K_idx > 0：必须等待 deps (前一个 K 的 uop) 完成

**乱序发射**：
- 不同 (M, N) 位置的 uop 可以乱序
- 相同 (M, N) 但不同 K 的 uop 必须顺序
- ISQ 扫描所有条目，选择满足条件的 uop

**发射示例**：
```
ISQ 中的 uop（64×64，K=8）：
uop[0,0,0]: src_ready=1, deps=none   → 可发射
uop[0,0,1]: src_ready=1, deps=uop[0,0,0] → 等待
uop[1,1,0]: src_ready=1, deps=none   → 可发射（并发）
uop[2,2,0]: src_ready=0, deps=none   → 等待 tileload
```

### 5.4 调度策略

**优先级**：
1. 优先发射 K_idx = 0 的 uop（新的输出位置）
2. 然后发射 K_idx > 0 的 uop（累加）
3. 同优先级按 uop_id 顺序

**并发度**：
- 理论上可以多个 uop 并发发射（如果 MAC 阵列支持）
- 实际上受限于 L0A/L0B 读带宽（每拍 512 B × 2 = 1024 B）
- 每拍最多 1 个 uop 发射（每个 uop 需要 512 B × 2）

---

## 6. L0A/L0B Cache

### 6.1 Cache 组织

**容量**：待定（基于设计参数）

**Entry 结构**：
```verilog
struct l0_cache_entry_t {
    valid:      1 bit;      // 条目有效
    tile_addr:  ? bits;     // TileReg 地址
    offset:     ? bits;     // Tile 内偏移
    data:       512 B;      // 缓存数据
    priority:   2 bits;     // 优先级（块内=3, 块外=0）
    lru:        ? bits;     // LRU 计数器
};
```

**组织方式**：
- L0A 和 L0B 独立
- 多路组相联（例如 4-way）
- PLRU 替换策略

### 6.2 地址匹配和查找

**查找流程**：
```
1. 接收 uop 的 A_addr/B_addr
2. 提取 tile_addr 和 offset
3. 在 L0A/L0B 中查找匹配的 entry
   - 匹配条件：valid && (tile_addr == req_addr) && (offset == req_offset)
4. Cache hit：
   - 读取 data
   - 更新 LRU
   - 设置 src_ready
5. Cache miss：
   - 分配新 entry
   - 发送 tileload 请求
   - 等待数据返回
```

### 6.3 PLRU 替换策略

**替换算法**：
```
1. 查找无效 entry（valid=0）
2. 如果都有效，查找优先级最低的 entry（priority=0）
3. 如果优先级相同，使用 PLRU 选择最近最少使用的
4. 替换选中的 entry
```

**优先级规则**：
- **块内数据**（priority=3）：当前正在计算的块需要的数据
- **块外数据**（priority=0）：其他块的数据
- FSM 拆分时标记每个 uop 的数据是否为块内

**示例**：
```
tmatmul 64×64，拆分为 4×4×8 = 128 uop

块内数据（正在计算）：
  uop[0,0,0..7] 使用 A[0], B[0]  → priority=3
  uop[0,1,0..7] 使用 A[0], B[1]  → priority=3
  ...

块外数据（未来需要）：
  其他块的 A/B 数据 → priority=0
```

### 6.4 预取机制

**预取流程**：
```
1. FSM 拆分时预测需要的 tile 地址
   - 根据 A/B tile 索引和 offset 计算地址
   - 生成 tileload 请求列表

2. tileload 请求入队 buffer
   - Buffer 容量：待定
   - 优先级：块内 > 块外

3. 等待读口空闲
   - TMU 读取 TileReg
   - 数据返回填充 L0A/L0B

4. 更新 ISQ 中 uop 的 src_ready 标志
```

**预取优势**：
- 减少 cache miss 延迟
- 提高 MAC 阵列利用率
- 数据预取和计算并行

### 6.5 读带宽和数据流

**每拍读取**：
- L0A → MAC 阵列：512 B/cycle
  - A: 16 行 × 32 B = 512 B
- L0B → MAC 阵列：512 B/cycle（FP16/BF16/FP8）
  - B: 32 B × 16 列 = 512 B
- L0B → MAC 阵列：1024 B/cycle（FP4）
  - B: 32 B × 32 列 = 1024 B

**发射条件**：
- 只有确认 cache hit 才发给 MAC 阵列
- src_ready 是发射的必要条件

---

## 7. MAC 阵列

### 7.1 阵列组织

**配置**：16×16 脉动阵列

**MAC 单元数量**：
- FP16/BF16：4096 MACs（16×16 × 16 MACs per PE）
- FP8：8192 MACs（2× 算力）
- FP4：32768 MACs（8× 算力）

**脉动阵列**：
- ~7 级流水线
- 数据流：A 从左到右，B 从上到下
- 部分和累加

### 7.2 数据流

**输入**（每拍）：
- A: 512 B（16 行 × 32 B）
- B: 512 B（32 B × 16 列）或 1024 B（FP4）

**输出**（每拍）：
- C: 16×16×FP32 = 1 KB（nz 格式）
- 写带宽：256 B/cycle 到 ACC

### 7.3 格式支持

| 格式 | MAC 数量 | 算力倍数 | 说明 |
|------|---------|---------|------|
| FP16 | 4096 | 1× | 基准 |
| BF16 | 4096 | 1× | Brain Float 16 |
| FP8  | 8192 | 2× | 双倍算力 |
| FP4  | 32768 | 8× | 8 倍算力 |

---

## 8. BufferC（K-Accumulator）

### 8.1 用途

**K 方向完整累加**：
- 避免频繁的 ACC RMW（读-改-写）操作
- 在 BufferC 中完成单个输出位置的所有 K 方向累加
- 累加完成后一次性写入 ACC

### 8.2 组织

**容量**：16×16×FP32 = 1 KB（匹配单个 fractal 的输出）

**操作**：
```
对于单个输出位置 C[mi,nj]（M方向tile mi，N方向tile nj）：

k=0:  BufferC = MAC_array_output       // 初始化
k=1:  BufferC += MAC_array_output      // FP32加法，1 cycle
k=2:  BufferC += MAC_array_output      // FP32加法，1 cycle
...
k=K_tiles-1: BufferC += MAC_array_output

完成后：
  tmatmul:     ACC[slice] = BufferC    // 写入（4 cycles，256 B/cy）
  tmatmul.acc: ACC[slice] += BufferC   // RMW（9 cycles）
```

**关键点**：
- BufferC 把整个 K 方向（所有 K_tiles）的累加都在片上完成
- 只在 K 方向全部完成后才访问 ACC
- 对于 tmatmul：仅需写入，不需要 RMW
- 对于 tmatmul.acc：需要 RMW（读取前值 + 累加 BufferC）

### 8.3 性能优势

**避免频繁 ACC 访问**：
```
无 BufferC 方案：
  k=0:  ACC[slice] = MAC_output                  // 写（4 cycles）
  k=1:  ACC[slice] = ACC[slice] + MAC_output     // RMW（9 cycles）
  k=2:  ACC[slice] = ACC[slice] + MAC_output     // RMW（9 cycles）
  ...
  k=15: ACC[slice] = ACC[slice] + MAC_output     // RMW（9 cycles）
  总计：4 + 15×9 = 139 cycles

有 BufferC 方案：
  k=0:  BufferC = MAC_output                     // 写（寄存器，<1 cycle）
  k=1:  BufferC += MAC_output                    // FP32加法（1 cycle）
  k=2:  BufferC += MAC_output                    // FP32加法（1 cycle）
  ...
  k=15: BufferC += MAC_output                    // FP32加法（1 cycle）
        ACC[slice] = BufferC                     // 写（4 cycles）
  总计：16 + 4 = 20 cycles

节省：119 cycles（85%）
```

---

## 9. 物理累加器池（ACC Pool）

### 9.1 组织

**容量**：128 KB（vs 之前 64 KB）

**Slice 大小**：
- 非 FP4：1 KB（16×16×FP32）
- FP4：2 KB（16×32×FP32，双倍算力）
- 一个 uop 对应一个 slice

**Slice 数量**：
- 非 FP4：128 KB / 1 KB = 128 slices
- FP4：128 KB / 2 KB = 64 slices

### 9.2 分配和释放

**分配**（uop 粒度）：
- 每个 uop 分配一个 ACC slice
- 可以不连续分配
- 硬件自动管理（无需软件干预）

**释放**（uop 粒度）：
- acccvtuop 完成后释放对应 slice
- 释放后 slice 返回空闲池

**分配算法**：
```
1. 查询空闲 slice 位图
2. 分配任意空闲 slice（可以不连续）
3. 更新位图
4. 记录 uop_id → slice_id 映射
```

**示例**：
```
tmatmul 64×64, K=8 → 128 uop

分配：
uop[0,0,0] → slice 0
uop[0,0,1] → slice 1
uop[0,1,0] → slice 5  (不连续)
uop[1,0,0] → slice 2
...

释放（acccvt 完成后）：
slice 0 released
slice 1 released
slice 5 released
...
```

### 9.3 写入和读取

**写入**（从 MAC 阵列）：
- 写带宽：256 B/cycle
- 格式：nz（Natural Z-curve）
- 每拍写 1/4 个 slice（256 B / 1 KB = 1/4）
- 4 拍完成一个 slice 写入

**读取**（到 FixPipe）：
- 读带宽：256 B/cycle
- acccvtuop 按需读取对应 slice
- 送入 FixPipe 进行格式转换

---

## 10. ACC 链和并发

### 10.1 ACC 链标记

**acc_chain 字段**：
- 2 bits（支持 2-4 个并发链）
- 编码在 tileop 中

**链的独立性**：
- 不同链完全独立
- 不同链可以并发执行
- 共享物理资源（ISQ, L0A/L0B, MAC, ACC Pool）

### 10.2 链的顺序保证

**BIQ 保序发射**：
- 同一个 acc_chain 的 tileop 保持顺序
- 不同 acc_chain 可以乱序

**CUBE 处理**：
```
链 0: tmatmul → tmatmul.acc → acccvt
链 1: tmatmul → tmatmul.acc → acccvt
链 2: ...
```
- CUBE 根据 acc_chain 标记路由
- 维护每条链的依赖关系

### 10.3 ACC 映射表和细粒度依赖

**细粒度依赖关系**：

CUBE 支持 **per-输出位置** 的细粒度依赖，不需要等待整个 tileop 完成：

```
tmatmul z0, tA0, tB0, 128×128, chain=0      // 生成 8×8×K_tiles 个uop
tmatmul.acc z0, tA1, tB1, 128×128, chain=0  // 生成 8×8×K_tiles 个uop

依赖关系（针对单个输出位置[mi,nj]）：
  第一个tmatmul的 uop[mi,nj,K_tiles-1] 完成
    ↓ 唤醒
  第二个tmatmul.acc的 uop[mi,nj,0] 可以开始

关键点：
- 依赖是 per-输出位置 的，不是 per-tileop
- 第二个tmatmul.acc可以部分开始（无需等第一个全部完成）
- 不同输出位置的uop可以流水线并行
```

**依赖建立示例**：
```
第一个 tmatmul：
  for mi in 0..M_tiles-1:
    for nj in 0..N_tiles-1:
      slice_id = alloc_acc_slice()
      slice_list[mi*N_tiles + nj] = slice_id
      for ki in 0..K_tiles-1:
        uop[mi,nj,ki].slice_id = slice_id
        if ki == 0:
          uop[mi,nj,ki].deps = none
        else:
          uop[mi,nj,ki].deps = uop[mi,nj,ki-1]
      last_uop_id[mi*N_tiles + nj] = uop[mi,nj,K_tiles-1].uop_id

第二个 tmatmul.acc：
  查询 ACC 映射表：slice_list = acc_mapping[chain][z0]
  
  for mi in 0..M_tiles-1:
    for nj in 0..N_tiles-1:
      slice_id = slice_list[mi*N_tiles + nj]  // 复用slice
      for ki in 0..K_tiles-1:
        uop[mi,nj,ki].slice_id = slice_id
        if ki == 0:
          uop[mi,nj,ki].deps = last_uop_id[mi*N_tiles + nj]  // 细粒度依赖
        else:
          uop[mi,nj,ki].deps = uop[mi,nj,ki-1]
```

**ACC 映射表结构**：
```verilog
struct acc_chain_table_t {
    valid:        1 bit;
    acc_chain:    2 bits;          // 链标记
    arch_acc:     2 bits;          // 架构累加器 (z0-z3)
    num_outputs:  12 bits;         // 输出位置数量 (M_tiles × N_tiles)
    slice_list:   [256][7:0];      // 每个输出位置的slice_id（最多256个）
    last_uop_id:  [256][7:0];      // 每个输出位置的最后一个uop_id
};
```

**Wakeup 机制**：
```
uop[mi,nj,K_tiles-1] 完成：
  1. 广播 uop_id
  2. ISQ 中查找所有 deps == uop_id 的 entry
  3. 对应的下一个 tileop 的 uop[mi,nj,0] 被 wakeup
  4. 如果 src_ready 也满足，可以发射

不同输出位置独立 wakeup，支持流水线并行
```

---

## 11. acccvt 和 FixPipe

### 11.1 acccvt 拆分

**拆分为 acccvtuop**：
- 按 ACC slice 粒度拆分
- 每个 acccvtuop 对应一个 matmul uop 的 ACC slice

**示例**：
```
tmatmul 64×64, K=8 → 128 uop → 128 ACC slices
acccvt z0, tC, mode=nz2nd
  → 拆分为 128 acccvtuop
  → acccvtuop[i] 对应 uop[i] 的 ACC slice
```

### 11.2 依赖和 wakeup

**依赖建立**：
```
acccvtuop[i].deps = matmul_uop[i]
```

**wakeup 机制**：
```
matmul uop[i] 完成（写入 ACC slice）
  → 设置 completed[i] = 1
  → wakeup acccvtuop[i]
  → acccvtuop[i] 可以开始搬运
```

**并发**：
- 多个 acccvtuop 可以并发执行
- 受限于 FixPipe 和 TileStore 带宽

### 11.3 FixPipe 流水线

**流水线阶段**（详细）：
```
Stage 0-3: ACC 读取（256 B/cy，4 拍读 1 KB）
Stage 4:   数据缓冲
Stage 5-6: nz → nd 格式转换（2 cycles）
Stage 7:   量化/格式转换（1 cycle）
Stage 8:   rowmax 计算（可选，1 cycle）
Stage 9:   输出缓冲
Stage 10:  TileStore 请求生成

总延迟：~11 cycles per slice
吞吐量：4 cycles per slice（流水线满载）
```

**格式转换（nz → nd）**：
- **nz（Natural Z-curve）**：CUBE MAC 阵列的自然输出格式
  - Z-curve 空间填充曲线布局
  - 优化 MAC 阵列的 bank-parallel 计算
  - 数据在 ACC 中以 nz 格式存储
- **nd（Natural Data）**：标准行主序格式
  - 软件期望的数据布局
  - 连续内存访问友好
  - TileReg 中以 nd 格式存储
- **转换目的**：从硬件优化布局到软件友好布局
- 重排序数据以匹配软件期望的布局

**相关格式说明**：
- **zn（Z-curve Natural）**：另一种 Z-curve 变体（未使用）
- **dn（Data Natural）**：列主序格式（未使用）
- Janus CUBE 主要使用 nz → nd 转换

**量化**：
- **FP32 → FP16**：标准 IEEE 754 舍入
  - 支持 round-to-nearest-even
  - 处理溢出和下溢
- **FP32 → FP8**：E4M3 或 E5M2 格式
  - E4M3：4-bit 指数，3-bit 尾数，适合激活值
  - E5M2：5-bit 指数，2-bit 尾数，适合权重
- **FP32 → INT8**：饱和量化
  - 量化范围：[-128, 127]
  - 饱和处理溢出值
  - 可选的 scale 因子

**rowmax**：
- **用途**：计算每行的最大值
- **应用场景**：
  - Softmax 前的 exp shift（避免数值溢出）
  - 归一化操作
  - 统计分析
- **输出格式**：
  - 主数据（tile_dst）：转换后的矩阵
  - rowmax 数据（tile_rowmax）：每行一个 FP32 值
  - 例如 16×16 矩阵 → 16 个 rowmax 值
- **额外存储**：rowmax 结果需要额外的 TileReg 地址

### 11.4 多次搬运

**TileReg 地址映射**：
```
acccvtuop[0] → TileReg[tC] offset 0
acccvtuop[1] → TileReg[tC] offset 1KB
acccvtuop[2] → TileReg[tC] offset 2KB
...
```

**搬运顺序**：
- 按 acccvtuop 顺序搬运
- 拼接到同一个 TileReg

---

## 12. TileStore 和写回

### 12.1 TileStore 请求

**接口信号（TMU Ring协议）**：
```verilog
// CUBE → TMU 写请求（通过 node3/pipe3）
output wire        cube_tmu_wr_req_valid
input  wire        tmu_cube_wr_req_ready
output wire [19:0] cube_tmu_wr_req_addr      // 20-bit 字节地址
output wire [7:0]  cube_tmu_wr_req_tag       // 请求tag（用于匹配响应）
output wire [255:0][7:0] cube_tmu_wr_req_data // 256 B（单个TMU flit）
```

**TMU Ring 接口说明**：
- CUBE 通过 **node3/pipe3** 写数据到 TileReg
- 每个请求是一个 **256B flit**
- 传输 1KB slice 需要 **4 个 flit**（4 × 256B）
- TMU Ring 延迟：4 + 2×H cycles（H = Ring跳数）

**TileStore 队列**：
- 深度：**8 entries**
- 每个 entry：一个 256B TMU 写请求
- 作用：
  - 缓冲 FixPipe 输出（256 B/cycle）
  - 添加 TMU Ring meta（addr, tag）
  - 处理 TMU 背压（Ring busy）

### 12.2 写回流程

```
1. acccvtuop 通过 FixPipe 完成格式转换
   - 输出：256 B/cycle（流水线）
   
2. 生成 TileStore 请求（256B粒度）
   - 计算 TMU 地址（20-bit）
   - 分配 tag（8-bit）
   - 携带 BID
   
3. TileStore 请求入队（8-deep queue）
   
4. 发送到 TMU node3（写通道）
   - 每个 slice（1KB）需要 4 个 flit
   - 按序发送
   
5. TMU Ring 传输和写入 TileReg
   
6. 写完成响应返回
   
7. 释放对应 ACC slice
```

**传输示例（1个slice = 1KB）**：
```
Cycle 0: FixPipe 输出 256B（第1/4）→ TileStore Queue
Cycle 1: 发送 flit #1 到 TMU（tag=0x10, offset=0）
Cycle 4: FixPipe 输出 256B（第2/4）→ TileStore Queue
Cycle 5: 发送 flit #2 到 TMU（tag=0x11, offset=256B）
Cycle 8: FixPipe 输出 256B（第3/4）→ TileStore Queue
Cycle 9: 发送 flit #3 到 TMU（tag=0x12, offset=512B）
Cycle 12: FixPipe 输出 256B（第4/4）→ TileStore Queue
Cycle 13: 发送 flit #4 到 TMU（tag=0x13, offset=768B）
Cycle 17-20: 4个响应陆续返回
Cycle 20: 释放 ACC slice
```

### 12.3 响应和释放

**写完成响应（TMU Ring协议）**：
```verilog
// TMU → CUBE 写完成（通过 node3）
input  wire        tmu_cube_wr_done_valid
input  wire [7:0]  tmu_cube_wr_done_tag      // 响应tag（匹配请求）
input  wire [STID_W-1:0] tmu_cube_wr_done_stid
input  wire [BID_W-1:0] tmu_cube_wr_done_bid
```

**ACC slice 释放**：
```
1. 接收写完成响应（通过tag匹配）
2. 确认该slice的所有4个flit都已完成
3. 释放对应 ACC slice
4. 更新空闲位图
5. 如果所有 acccvtuop 完成，向 BROB 报告完成
```
4. 如果所有 acccvtuop 完成，向 BROB 报告完成
```

---

## 13. 执行模型和时序

### 13.1 tmatmul 执行流程

```
Cycle 0-5:     命令解码和 FSM 拆分
Cycle 6-10:    uop 入队 ISQ
Cycle 11+:     uop 乱序发射和执行

每个 uop：
  Cycle 0-4:   等待 src ready (L0A/L0B)
  Cycle 5-11:  MAC 阵列计算 (~7 拍脉动)
  Cycle 12-13: BufferC 累加
  Cycle 14-15: 写入 ACC (256 B/cy, 4 拍完成 1KB)
```

**总延迟**（64×64, K=8）：
- FSM 拆分：~5 cycles
- 128 uop 执行：~20-30 cycles（乱序并发）
- 总计：~25-35 cycles

### 13.2 acccvt 执行流程

```
Cycle 0-5:     命令解码和拆分 acccvtuop
Cycle 6+:      acccvtuop 等待 matmul uop 完成

每个 acccvtuop：
  Cycle 0:     matmul uop 完成 → wakeup
  Cycle 1-4:   ACC 读取 (256 B/cy, 4 拍读 1KB)
  Cycle 5-8:   FixPipe 格式转换
  Cycle 9-10:  TileStore 请求
  Cycle 11-15: TMU 写入 TileReg
  Cycle 16:    写完成，释放 ACC slice
```

**总延迟**（128 acccvtuop）：
- 流水线并发执行
- 总计：~20-30 cycles（受 TileStore 带宽限制）

### 13.3 matmul/acccvt 并行

```
时间线：
Cycle 0-30:   tmatmul 执行
Cycle 10-40:  acccvt 执行（与 tmatmul 后半段并行）
```

**并行条件**：
- acccvtuop 只需要等待对应的 matmul uop 完成
- 不需要等待整个 tmatmul 完成
- 支持 matmul 和 acccvt 流水线并行

---

## 14. Block Fabric 接口

### 14.1 命令接口

**接口信号**（BCTRL → CUBE）：
```verilog
// 遵循命名规范：bctrl_cube_<signal>
input  wire        bctrl_cube_cmd_valid      // 命令有效
output wire        cube_bctrl_cmd_ready      // CUBE 就绪
input  wire [2:0]  bctrl_cube_cmd_kind       // 命令种类
input  wire [STID_W-1:0] bctrl_cube_cmd_stid
input  wire [BID_W-1:0] bctrl_cube_cmd_tag   // tag == BID
input  wire [63:0] bctrl_cube_cmd_payload    // 指令编码
input  wire [7:0]  bctrl_cube_cmd_epoch      // Epoch
```

**命令载荷编码**（64-bit）：
```
tmatmul/tmatmul.acc:
[63:60] - opcode (tmatmul=0x1, tmatmul.acc=0x2)
[59:56] - zd (架构累加器 z0-z3)
[55:48] - tA (A 操作数 TileReg 索引)
[47:40] - tB (B 操作数 TileReg 索引)
[39:32] - shape_m (M 维度)
[31:24] - shape_n (N 维度)
[23:22] - acc_chain (ACC 链标记, 0-3)
[21:16] - format (数据格式)
[15:0]  - 保留

acccvt:
[63:60] - opcode (acccvt=0x3)
[59:56] - zd (源累加器)
[55:48] - tile_dst (目标 TileReg)
[47:40] - tile_rowmax (rowmax 输出，可选)
[39:32] - mode (转换模式位图)
[31:22] - acc_chain
[21:0]  - 保留
```

### 14.2 响应接口

**接口信号**（CUBE → BROB）：
```verilog
// 遵循命名规范：cube_brob_<signal>
output wire        cube_brob_rsp_valid       // 响应有效
input  wire        brob_cube_rsp_ready
output wire [STID_W-1:0] cube_brob_rsp_stid
output wire [BID_W-1:0] cube_brob_rsp_tag    // tag == BID
output wire [7:0]  cube_brob_rsp_epoch
output wire [3:0]  cube_brob_rsp_status      // 状态码
output wire        cube_brob_rsp_trap_valid  // 异常有效
output wire [63:0] cube_brob_rsp_trapno
output wire [63:0] cube_brob_rsp_traparg0
output wire        cube_brob_rsp_trap_bi
```

**状态码**：
- `0x0`：成功
- `0x1`：无效操作数
- `0x2`：ACC 溢出
- `0x3`：TileReg 访问错误
- `0x4`：依赖未满足
- `0x5-0xF`：保留

### 14.3 冲刷接口

**接口信号**（BCC → CUBE）：
```verilog
// 遵循命名规范：bcc_cube_<signal>
input wire        bcc_cube_flush_fire        // 冲刷事件
input wire [STID_W-1:0] bcc_cube_flush_stid  // 目标 BROB ring
input wire [BROB_ENTRIES-1:0] bcc_cube_flush_kill_mask
```

**冲刷处理**：
1. CUBE 接收 `bcc_cube_flush_fire`、`flush_stid` 和 BROB 生成的 kill mask
2. 用操作携带的 `(STID,BID)` 查询对应 ring/mask；禁止比较 BID 数值大小
3. 取消 `op.stid == flush_stid && flush_kill_mask[op.bid]` 的操作：
   - 清空 ISQ 中年轻的 uop
   - 取消进行中的 MAC 计算
   - 释放被取消操作的 ACC slices
4. 继续 kill mask 未命中的操作
5. 清理 ACC 链状态

---

## 15. 错误和异常处理

### 15.1 matmul 执行错误

**错误类型**：
- **ACC 未释放**：前一个 ACC 还在搬运中
- **依赖未满足**：matmul.acc 的前一个操作未完成
- **资源耗尽**：ACC pool 满，无法分配

**处理流程**：
```
1. CUBE 检测错误
2. 设置 cube_brob_rsp_status = error_code
3. 设置 cube_brob_rsp_trap_valid = 1
4. 向 BROB 报告异常
5. BROB 等待 Block 成为最老
6. ROB 引发精确异常
7. 软件异常处理
```

### 15.2 acccvt 搬运错误

**错误类型**：
- **TileReg 访问错误**：TileStore 写入失败
- **格式转换错误**：无效的 mode 组合
- **超时**：TMU 长时间无响应

**恢复机制**：
```
1. CUBE 检测错误
2. 保留 ACC slice（不释放）
3. 向 BROB 报告异常
4. 软件可以选择：
   - 重试 acccvt
   - 终止操作
   - 切换到备用路径
```

### 15.3 精确异常

**精确异常模型**：
- CUBE 异常通过 BROB 报告
- BROB 等待 Block 成为最老（retired）
- 保证异常发生时，所有更老的指令已完成
- 所有更年轻的指令可以安全取消

**异常现场保存**：
- BID：标识异常的 Block
- ACC 状态：保留未完成的 ACC slices
- 依赖状态：保留 ACC 链依赖关系

---

## 17. 与其他模块的交互

### 17.1 与 TMU 的交互

**TMU Ring 接口**：
- CUBE 通过 TMU Ring 互联访问 TileReg
- **读通道**：node1/pipe1（128KB SRAM）
- **写通道**：node3/pipe3（128KB SRAM）
- **Flit大小**：256 B
- **峰值带宽**：256 B/cycle per channel

**TMU Ring 协议**：
- 8站点双向Ring拓扑
- 静态最短路径路由
- Token/bubble防死锁机制
- 请求/响应独立Ring通道

**访问延迟**：
- 本地访问（node访问自身pipe）：4 cycles
- 跨节点访问：4 + 2×H cycles（H = Ring跳数）
- CUBE node1→pipe1（H=0）：4 cycles
- CUBE node3→pipe3（H=0）：4 cycles

### 17.2 与 VEC/TMA/TAU 的关系

**资源共享**：
- **TRegFile**：所有引擎共享
- **TMU 带宽**：竞争资源
- **BIQ**：独立的发射队列

**独立性**：
- 每个引擎有独立的 ISQ/执行单元
- 不同引擎可以并发执行
- 通过 TMU 仲裁避免冲突

### 17.3 与 DavinciOO 的差异

**架构演进**：
- **DavinciOO outerCube**：超标量模型，指令级发射
- **Janus CUBE**：块结构模型，Fractal 拆分

**主要差异**：

| 特性 | DavinciOO outerCube | Janus CUBE |
|------|---------------------|------------|
| 控制模型 | 指令级发射 | Block-ordered + Fractal 拆分 |
| 拆分粒度 | 可变形状 | 固定 16×16 小分形 |
| 调度 | ROB + RS | ISQ + uop 乱序 |
| 指令 | CUBE.OPA + CUBE.DRAIN | tmatmul + tmatmul.acc + acccvt |
| ACC | 64 KB, 2 KB slice | 128 KB, 1 KB slice (FP4: 2 KB) |
| Cache | Burst Buffer + Staging | L0A/L0B Cache（真正的 cache） |
| 格式转换 | FixPipe (ZZ→ND) | FixPipe (nz→nd) |
| 并发 | 单链 | 2-4 个 ACC 链 |

**可复用的设计元素**：
- MAC 阵列组织思想
- 脉动阵列计算
- 累加器架构概念
- FixPipe 格式转换思想

---

## 18. 关键参数总结

| 参数 | 值 | 说明 |
|------|-----|------|
| **MAC 阵列** | | |
| MAC 单元（FP16） | 4096 | 16×16 阵列 |
| MAC 单元（FP8） | 8192 | 2× 算力 |
| MAC 单元（FP4） | 32768 | 8× 算力 |
| 脉动阵列延迟 | ~7 cycles | 流水线 |
| **ISQ** | | |
| ISQ 深度 | 32 uop | 待定组织方式 |
| **L0 Cache** | | |
| L0A 容量 | 64 KB | 4-way set-associative |
| L0B 容量 | 64 KB | 4-way set-associative |
| L0 Entry 大小 | 512 B | 需要2个TMU flit填充 |
| L0A 读带宽 | 512 B/cycle | |
| L0B 读带宽 | 512 B/cycle | FP4: 1024 B/cy |
| Prefetch Buffer 深度 | 32 entry | 每entry = 1个TMU读请求 |
| **BufferC** | | |
| BufferC 容量 | 1 KB | 16×16×FP32，K方向完整累加 |
| **ACC Pool** | | |
| ACC 容量 | 128 KB | 128 slices（非FP4） |
| ACC Slice 大小 | 1 KB | 16×16×FP32（非FP4） |
| ACC Slice 大小（FP4） | 2 KB | 16×32×FP32 |
| ACC 写带宽 | 256 B/cycle | nz 格式 |
| ACC 读带宽 | 256 B/cycle | 到FixPipe |
| **FixPipe** | | |
| FixPipe 延迟 | ~11 cycles | per slice |
| FixPipe 吞吐量 | 4 cycles | per slice（流水线） |
| FixPipe 输出带宽 | 256 B/cycle | |
| **TMU 接口** | | |
| TMU Flit 大小 | 256 B | 单个flit |
| TMU 读通道 | node1/pipe1 | CUBE读数据 |
| TMU 写通道 | node3/pipe3 | CUBE写数据 |
| TileStore Queue 深度 | 8 entry | 每entry = 1个TMU写请求 |
| TMU 访问延迟 | 4 cycles | 本地访问（H=0） |
| **并发** | | |
| 并发 ACC 链 | 2-4 | 可配置 |

---

**文档状态**：设计中 v2.0  
**最后更新**：2026-06-03
