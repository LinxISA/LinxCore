# CUBE 数据通路设计（Janus v2.0）

本文档详细描述 Janus CUBE 的数据通路设计。

---

## 1. 概述

CUBE 数据通路负责将输入数据（A 和 B 操作数）从 TileReg 送入 MAC 阵列，执行矩阵乘法，并将结果累加到物理累加器，最后通过 FixPipe 和 TileStore 写回 TileReg。

### 1.1 数据通路组件

```
TileReg (TMU)
    ↓
┌─────────────────────────────────────────────────┐
│              输入路径                            │
│                                                 │
│  TReg Burst → L0A/L0B Cache → MAC 阵列         │
└─────────────┬───────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────┐
│         MAC 阵列（16×16 脉动阵列）               │
│                                                 │
│  4096 MACs (FP16)                              │
│  8192 MACs (FP8, 2× 算力)                      │
│  32768 MACs (FP4, 8× 算力)                     │
└─────────────┬───────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────┐
│              BufferC (KACC)                     │
│  K 方向中间累加，减少 ACC RMW                   │
└─────────────┬───────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────┐
│      物理累加器池 (ACC Pool)                     │
│  128 KB, 128 slices × 1 KB                     │
└─────────────┬───────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────┐
│         输出路径 (FixPipe)                       │
│                                                 │
│  ACC → nz→nd 转换 → 量化 → TileStore           │
└─────────────────────────────────────────────────┘
    ↓
TileReg (TMU)
```

---

## 2. 输入路径

### 2.1 TMU Ring 接口

**TMU Ring 协议**：
- CUBE 通过 **node1/pipe1** 读取 TileReg
- 每个请求是一个 **256B flit**
- 读取 512B L0 entry 需要 **2 个 flit**

**Tile 读取时序**：
```
Cycle 0: 预取请求（FSM 拆分时生成）
Cycle 1: 发送到 TMU Ring（flit #1，256B）
Cycle 5: TMU 返回数据（flit #1，延迟4 cycles）
Cycle 2: 发送到 TMU Ring（flit #2，256B）
Cycle 6: TMU 返回数据（flit #2，延迟4 cycles）
Cycle 6: L0 Cache entry 填充完成（512B = 2×256B）
```

**接口信号（TMU Ring协议）**：
```verilog
// CUBE → TMU 读请求（通过 node1/pipe1）
output wire        cube_tmu_rd_req_valid
input  wire        tmu_cube_rd_req_ready
output wire [19:0] cube_tmu_rd_req_addr      // 20-bit 字节地址
output wire [7:0]  cube_tmu_rd_req_tag       // 请求tag（用于匹配响应）

// TMU → CUBE 读响应
input  wire        tmu_cube_rd_rsp_valid
output wire        cube_tmu_rd_rsp_ready
input  wire [7:0]  tmu_cube_rd_rsp_tag       // 响应tag
input  wire [255:0][7:0] tmu_cube_rd_rsp_data  // 256 B（单个flit）
```

**Prefetch Buffer**：
- 深度：**32 entries**
- 每个 entry：一个 TMU 读请求（256B flit + meta）
- 作用：
  - 缓冲 FSM 生成的预取请求
  - 处理 TMU Ring 背压
  - 支持优先级排序（块内 > 块外）

### 2.2 L0A/L0B Cache（输入数据缓存）

**用途**：缓存 A 和 B 操作数，支持跨 tileop 的数据复用

**L0A Cache（A 操作数）**：
- 容量：**64 KB**
- 组织：4-way set-associative
- 每个 entry：512 B（16 行 × 32 B）
- 总 entries：128（64KB / 512B）
- Set 数：32（128 / 4-way）

**L0B Cache（B 操作数）**：
- 容量：**64 KB**
- 组织：4-way set-associative
- 每个 entry：512 B（32 B × 16 列）
- FP4 特殊：1024 B（32 B × 32 列）
- 总 entries：128（64KB / 512B）
- Set 数：32（128 / 4-way）

**Cache Entry 结构**：
```verilog
struct l0_cache_entry_t {
    valid:      1 bit;        // 条目有效
    tag:        ? bits;       // Tag（地址高位）
    data:       512 B;        // 缓存数据（FP4 的 B: 1024 B）
    priority:   2 bits;       // 优先级（3=块内, 2=预取, 1=块外）
    lru:        2 bits;       // PLRU 位（4-way）
    pending:    1 bit;        // 填充中（等待TMU响应）
    flit_mask:  2 bits;       // 标记哪些flit已到达（512B需要2个flit）
};
```

### 2.3 Cache 查找和匹配

**查找流程**：
```
1. uop 从 ISQ 发射
2. 提取 A_addr 和 B_addr（tile + offset）
3. 在 L0A 和 L0B 中并行查找
   - 索引：使用 addr 的低位
   - Tag：使用 tile_addr + offset 高位
4. 命中（Hit）：
   - 读取 data
   - 更新 LRU
   - 设置 src_ready
   - 发送到 MAC 阵列
5. 未命中（Miss）：
   - 选择 victim（PLRU + 优先级）
   - 发送 tileload 请求到 TMU
   - 等待数据返回
   - 填充 cache entry
   - 重试 uop
```

**地址映射**：
```
TileReg 地址：
[63:58] - 保留
[57:52] - tile_addr (6 bits, 0-63)
[51:0]  - offset (tile 内偏移)

对于 Fractal uop：
A_addr = tA_tile + (uop_i * 16 * K + uop_k * 16) * element_size
B_addr = tB_tile + (uop_k * 16 * N + uop_j * 16) * element_size
```

### 2.4 PLRU 替换策略

**替换算法**（4-way 组相联）：
```
1. 检查 valid 位
   - 如果有无效 entry，选择第一个无效 entry
   
2. 检查 priority
   - priority=3（块内数据）：尽量保留
   - priority=0（块外数据）：优先替换
   
3. 检查 uop_ref 计数
   - uop_ref > 0：有 uop 正在使用，不能替换
   - uop_ref = 0：可以替换
   
4. PLRU 选择
   - 在可替换的 entry 中，选择 LRU 最小的
   - 伪 LRU：2-bit 近似
```

**优先级设置**：
```
FSM 拆分时标记：
- 当前块的 uop：priority = 3（块内）
- 未来块的 uop：priority = 0（块外）

块内判断：
  if (uop 的所有 K 迭代都在同一个块) {
      priority = 3;
  } else {
      priority = 0;
  }
```

### 2.5 预取机制

**预取触发**：
- FSM 在 Fractal 拆分时预测需要的 tile 地址
- 提前生成 TMU 读请求（256B粒度）
- 请求入队 Prefetch Buffer

**Prefetch Buffer**：
```verilog
struct prefetch_buffer_entry_t {
    valid:      1 bit;
    addr:       20 bits;      // TMU地址（20-bit字节地址）
    tag:        8 bits;       // TMU请求tag
    priority:   2 bits;       // 3=块内, 2=预取, 1=块外
    target:     1 bit;        // 0=L0A, 1=L0B
    cache_idx:  ? bits;       // 目标L0 Cache entry索引
    flit_seq:   1 bit;        // 0=第1个flit, 1=第2个flit（512B需要2个）
};
```

**预取队列深度**：**32 entries**

**预取策略**：
```
1. FSM 拆分生成 uop
2. 对于每个 uop，计算需要的 A/B 地址
3. 检查 L0A/L0B Cache
   - 如果已在 cache 或 pending，跳过
   - 如果不在，生成 2 个 prefetch 请求（512B = 2×256B）
4. Prefetch 请求入队（按优先级）
   - 块内数据：priority = 3
   - 预取数据：priority = 2
   - 块外数据：priority = 1
5. 按优先级发送到 TMU node1（读通道）
6. TMU 返回数据后填充 L0 Cache
   - 第1个flit到达：填充entry的前256B
   - 第2个flit到达：填充entry的后256B，entry完整可用
```

**L0 Cache填充示例**：
```
填充一个512B entry（需要2个TMU flit）：

Cycle 0: FSM发现L0 miss，分配entry_id=5
Cycle 1: 生成2个预取请求
  Req1: addr=0x1000, tag=0x10, flit_seq=0, cache_idx=5
  Req2: addr=0x1100, tag=0x11, flit_seq=1, cache_idx=5
Cycle 2: 发送Req1到TMU
Cycle 3: 发送Req2到TMU
Cycle 6: TMU返回flit1（tag=0x10）
         → L0[5].data[0:255] = flit1.data
         → L0[5].flit_mask[0] = 1
Cycle 7: TMU返回flit2（tag=0x11）
         → L0[5].data[256:511] = flit2.data
         → L0[5].flit_mask[1] = 1
         → L0[5].pending = 0（entry完整，可用）
Cycle 8: 更新ISQ中依赖此entry的uop.src_ready
```

### 2.6 数据流（L0 Cache → MAC）

**每拍读取**：
```
L0A Cache → MAC 阵列：512 B/cycle
  - A: 16 行 × 32 B = 512 B
  
L0B Cache → MAC 阵列：512 B/cycle (非 FP4)
  - B: 32 B × 16 列 = 512 B
  
L0B Cache → MAC 阵列：1024 B/cycle (FP4)
  - B: 32 B × 32 列 = 1024 B
```

**数据分发**：
```
每个 cycle：
- 从 L0A 读取 512 B（16 行，每行 32 B）
- 从 L0B 读取 512 B（16 列，每列 32 B）或 1024 B (FP4)
- 分发到脉动阵列：
  - A 数据从左边进入，逐列传播
  - B 数据从上边进入，逐行传播
  - 每个 MAC 单元接收对应的 A 和 B 元素
```

---

## 3. MAC 阵列

### 3.1 脉动阵列组织

**配置**：16×16 脉动阵列

**物理组织**：
```
        Col 0   Col 1   Col 2   ...   Col 15
Row 0   [MAC]   [MAC]   [MAC]   ...   [MAC]
Row 1   [MAC]   [MAC]   [MAC]   ...   [MAC]
Row 2   [MAC]   [MAC]   [MAC]   ...   [MAC]
...     ...     ...     ...     ...   ...
Row 15  [MAC]   [MAC]   [MAC]   ...   [MAC]

总计：16 × 16 = 256 个 PE (Processing Element)
每个 PE：16 个 MAC 单元（FP16）
总 MACs：256 × 16 = 4096
```

**PE（Processing Element）结构**：
```
每个 PE 包含：
- 16 个 MAC 单元（FP16）
- 局部寄存器（A, B, 部分和）
- 数据传播逻辑
```

### 3.2 脉动计算

**数据流动**：
```
A 数据流动方向：从左到右（→）
B 数据流动方向：从上到下（↓）
部分和流动方向：对角线累加

Cycle 0:
  PE[0,0] 接收 A[0], B[0]
  
Cycle 1:
  PE[0,0] 计算 A[0] × B[0]，传递 A[0] → PE[0,1]，传递 B[0] ↓ PE[1,0]
  PE[0,1] 接收 A[0], B[1]
  PE[1,0] 接收 A[1], B[0]
  
Cycle 2:
  PE[0,0] 累加新的 A[0]' × B[0]'
  PE[0,1] 计算 A[0] × B[1]
  PE[1,0] 计算 A[1] × B[0]
  PE[1,1] 接收 A[1], B[1]
  ...
```

**流水线深度**：
```
Stage 0: 输入寄存（A, B）
Stage 1: 乘法器（A × B）
Stage 2: 加法器（部分和累加）
Stage 3: 部分和寄存
Stage 4: 数据传播寄存
Stage 5: 输出寄存
Stage 6: 归约准备

总计：~7 级流水线
```

### 3.3 格式支持

**FP16（基准）**：
- 每个 PE：16 个 FP16 MAC
- 总算力：4096 MACs
- 吞吐量：4096 ops/cycle

**FP8（2× 算力）**：
- 每个 PE：32 个 FP8 MAC
- 总算力：8192 MACs
- 吞吐量：8192 ops/cycle

**FP4（8× 算力）**：
- 每个 PE：128 个 FP4 MAC
- 总算力：32768 MACs
- 吞吐量：32768 ops/cycle
- 特殊：B 操作数 32 列（vs 16 列）

**实现**：
- 使用可重配置的 MAC 单元
- 根据格式动态调整并行度
- 共享乘法器和加法器资源

### 3.4 MAC 输出

**输出格式**：nz（Natural Z-curve）

**输出大小**：
- 非 FP4：16×16×FP32 = 1 KB per cycle
- FP4：16×32×FP32 = 2 KB per cycle

**输出组织**（nz 格式）**：
```
16×16 输出矩阵（FP32）：
按 Z-curve 顺序排列，优化空间局部性

例如（简化的 4×4）：
  0  1  4  5
  2  3  6  7
  8  9  12 13
  10 11 14 15

实际 16×16 使用递归 Z-curve
```

---

## 4. BufferC（K-Accumulator）

### 4.1 用途和组织

**问题**：如果每个 K 迭代都访问 ACC，会导致频繁的 RMW 操作

**解决**：BufferC 作为 K 方向的完整累加器

**容量**：
- 非 FP4：1 KB（16×16×FP32）
- FP4：2 KB（16×32×FP32）

**组织**：单个缓冲区，匹配 MAC 阵列输出

### 4.2 累加策略

**K 方向完整累加**：
```
对于单个输出位置 C[mi,nj]（所有 K 方向累加）：

k=0:  BufferC = MAC_array_output           // 初始化
k=1:  BufferC += MAC_array_output          // FP32加法，1 cycle
k=2:  BufferC += MAC_array_output          // FP32加法，1 cycle
...
k=K_tiles-1: BufferC += MAC_array_output   // FP32加法，1 cycle

完成后：
  tmatmul:     ACC[slice] = BufferC        // 写入（4 cycles，256 B/cy）
  tmatmul.acc: ACC[slice] += BufferC       // RMW（9 cycles）
```

**关键点**：
- BufferC 完成**整个 K 方向**的累加（所有 K_tiles）
- 只在 K 方向全部完成后才访问 ACC
- 对于 tmatmul：仅需写入，无 RMW
- 对于 tmatmul.acc：需要 RMW（读取前值 + 累加）

### 4.3 性能优势

**避免频繁 ACC 访问**：
```
无 BufferC 方案（K=16）：
  k=0:  ACC[slice] = MAC_output              // 写（4 cycles）
  k=1:  ACC[slice] += MAC_output             // RMW（9 cycles）
  k=2:  ACC[slice] += MAC_output             // RMW（9 cycles）
  ...
  k=15: ACC[slice] += MAC_output             // RMW（9 cycles）
  总计：4 + 15×9 = 139 cycles

有 BufferC 方案（K=16）：
  k=0:  BufferC = MAC_output                 // 初始化（寄存器）
  k=1:  BufferC += MAC_output                // FP32加法（1 cycle）
  k=2:  BufferC += MAC_output                // FP32加法（1 cycle）
  ...
  k=15: BufferC += MAC_output                // FP32加法（1 cycle）
        ACC[slice] = BufferC                 // 写（4 cycles）
  总计：16 + 4 = 20 cycles

节省：119 cycles（85%）
```

**tmatmul.acc 链的优势**：
```
tmatmul:     BufferC完成K累加 → ACC写入（4 cycles）
tmatmul.acc: BufferC完成K累加 → ACC RMW（9 cycles）

vs 无BufferC：每个K都需要RMW ACC
```

---

## 5. 物理累加器池（ACC Pool）

### 5.1 存储组织

**容量**：128 KB

**Slice 组织**：
- 非 FP4：128 slices × 1 KB
- FP4：64 slices × 2 KB

**存储实现**：
- SRAM 阵列
- 多 bank 组织（例如 8 banks）
- 支持并行访问

**Bank 组织**：
```
Bank 0: slices 0, 8, 16, ...
Bank 1: slices 1, 9, 17, ...
Bank 2: slices 2, 10, 18, ...
...
Bank 7: slices 7, 15, 23, ...

交错布局，减少 bank 冲突
```

### 5.2 写入数据通路

**写入源**：BufferC

**写带宽**：256 B/cycle

**写入流程**：
```
1. BufferC 完成 K-chunk 累加
2. 读取目标 ACC slice（RMW 读）
3. FP32 加法：ACC_slice += BufferC
4. 写回 ACC slice（RMW 写）

延迟：
  Cycle 0: 读 ACC slice（256 B/cy，需 4 拍读 1KB）
  Cycle 4: 加法
  Cycle 5: 写 ACC slice（256 B/cy，需 4 拍写 1KB）
  Cycle 9: 完成
```

**写入格式**：nz（与 MAC 输出一致）

### 5.3 读取数据通路

**读取目的**：FixPipe（用于 acccvt）

**读带宽**：256 B/cycle

**读取流程**：
```
1. acccvtuop wakeup
2. 读取对应 ACC slice
   - 256 B/cycle，需 4 拍读 1 KB
3. 送入 FixPipe 进行格式转换
```

---

## 6. 输出路径（FixPipe）

### 6.1 FixPipe 组织

**用途**：格式转换和数据重排

**流水线阶段**（详细）：
```
Stage 0-3: ACC 读取（256 B/cy，4 拍读 1 KB）
Stage 4:   数据缓冲
Stage 5-6: nz → nd 格式转换（2 cycles）
Stage 7:   量化/格式转换（1 cycle）
Stage 8:   rowmax 计算（可选，1 cycle）
Stage 9:   输出缓冲（1 cycle）
Stage 10:  TileStore 请求生成

总延迟：~11 cycles per slice
吞吐量：4 cycles per slice（流水线满载）
```

### 6.2 格式转换（nz → nd）

**nz 格式**：
- Natural Z-curve
- MAC 阵列的自然输出
- 空间局部性好

**nd 格式**：
- Natural Data（行主序）
- 软件期望的标准格式
- 内存访问友好

**转换逻辑**：
```
nz 布局（16×16，简化的 Z-curve）：
[0][1][4][5] [2][3][6][7] ...

nd 布局（行主序）：
Row 0: [0][1][2][3][4][5]...[15]
Row 1: [16][17][18]...
...

转换：重排序网络
- 使用 Benes 网络或 Butterfly 网络
- 延迟：1-2 cycles
```

### 6.3 量化

**支持的量化模式**：

**FP32 → FP16**：
```
1. 检查范围
2. 舍入到最近偶数（round-to-nearest-even）
3. 处理溢出：
   - 超出范围 → ±Infinity
   - 非规格化数 → 0 或最小非规格化数
```

**FP32 → FP8**：
```
E4M3 格式（适合激活值）：
- 1-bit 符号
- 4-bit 指数（bias=7）
- 3-bit 尾数
- 范围：[-448, 448]

E5M2 格式（适合权重）：
- 1-bit 符号
- 5-bit 指数（bias=15）
- 2-bit 尾数
- 范围更大，精度更低
```

**FP32 → INT8**：
```
1. 应用 scale 因子：scaled = value × scale
2. 舍入：rounded = round(scaled)
3. 饱和：
   - rounded > 127 → 127
   - rounded < -128 → -128
4. 输出 INT8
```

### 6.4 rowmax

**用途**：计算每行的最大值（用于 softmax 等）

**实现**：
```
对于 16×16 矩阵（或 16×32，FP4）：
  for each row (0..15):
      max_val = -Infinity
      for each col (0..15 或 0..31):
          if (matrix[row][col] > max_val):
              max_val = matrix[row][col]
      rowmax[row] = max_val

输出：
- 主数据：转换后的矩阵（写到 tile_dst）
- rowmax 数据：16 个 FP32 值（写到 tile_rowmax）
```

**流水线**：
```
Stage 0: 读取行数据
Stage 1: 并行比较（树形归约）
Stage 2: 输出 max 值
```

### 6.5 TileStore 请求

**请求生成**：
```
1. FixPipe 完成格式转换
2. 数据准备好（256 B）
3. 生成 TileStore 请求：
   - tile_dst：目标 TileReg 索引
   - offset：Tile 内偏移
   - size：写入大小
   - data：转换后的数据
   - bid：BID
```

**接口信号**：
```verilog
// FixPipe → TileStore
output wire        fixpipe_tstore_req_valid
input  wire        tstore_fixpipe_req_ready
output wire [5:0]  fixpipe_tstore_req_tile
output wire [15:0] fixpipe_tstore_req_offset
output wire [15:0] fixpipe_tstore_req_size
output wire [2047:0][7:0] fixpipe_tstore_req_data
output wire [63:0] fixpipe_tstore_req_bid
```

**输出缓冲**：
- 小型 FIFO（例如 4-8 个 entry）
- 解耦 FixPipe 和 TileStore
- 支持流水线并行

---

## 7. 数据通路时序

### 7.1 完整路径延迟（单个 uop）

```
Stage         操作                         延迟 (cycles)
================================================================
输入路径：
  0-4         TReg 读取（预取）            5
  5           L0 Cache 查找                1
  6           L0 Cache 读取                1

MAC 计算：
  7-13        脉动阵列计算                 7

BufferC：
  14          BufferC 累加                 1

ACC 写入（K 全部完成后）：
  15-23       ACC RMW（读 + 加 + 写）      9
              - 读取 1 KB：4 cycles (256 B/cy)
              - FP32 加法：1 cycle
              - 写回 1 KB：4 cycles (256 B/cy)

输出路径（acccvt）：
  0-3         ACC 读取                     4
  4-10        FixPipe（nz→nd + 量化）      7
  11-13       TileStore 请求               3

================================================================
总延迟：
  - 单个 uop（无 ACC RMW）：~15 cycles
  - 单个 uop（有 ACC RMW）：~24 cycles
  - acccvt（per slice）：~11 cycles
```

### 7.2 流水线并发

**K 循环流水线**（无停顿）：
```
K=0: [TReg][L0][MAC][BufC][ACC]
K=1:       [TReg][L0][MAC][BufC][ACC]
K=2:              [TReg][L0][MAC][BufC][ACC]
K=3:                     [TReg][L0][MAC][BufC][ACC]

理想启动间隔：~5 cycles（受 TReg 读取限制）
```

**实际情况**（考虑 TMU 仲裁）：
```
启动间隔：~8-10 cycles
限制因素：
  - TMU 仲裁延迟
  - L0 Cache miss
  - ACC bank 冲突
```

### 7.3 吞吐量分析

**MAC 阵列利用率**：
```
工作负载          MAC 利用率    瓶颈
================================================
K=1               ~40%          TReg 读取延迟
K=4               ~70%          TReg 读取延迟
K=16              ~90%          接近峰值
K=64              ~95%          接近峰值

大输出形状        ~85%          数据复用
(64×64, 128×128)
```

**带宽利用率**：
```
输入带宽：
  - TReg burst：2048 B/cy（峰值）
  - L0 → MAC：512 B/cy × 2 = 1024 B/cy
  - 利用率：1024 / 2048 = 50%（考虑 A+B）

输出带宽：
  - MAC → ACC：256 B/cy（写）
  - ACC → FixPipe：256 B/cy（读）
  - TileStore：2048 B/cy（burst）
```

---

## 8. 数据通路优化

### 8.1 L0 Cache 优化

**命中率优化**：
- 预取机制：提前加载数据
- 优先级策略：保留块内数据
- 足够的容量：减少冲突

**目标命中率**：
- 块内数据：>95%
- 块外数据：>70%

### 8.2 BufferC 优化

**设计优势**：
- K 方向完整累加在片上完成
- 避免频繁的 ACC RMW 操作
- 对于 tmatmul：仅需写入（4 cycles）
- 对于 tmatmul.acc：需要 RMW（9 cycles）

**精度考虑**：
- FP32 累加精度足够（IEEE 754）
- K 方向累加次数通常 ≤ 64
- 累加误差在可接受范围内

### 8.3 FixPipe 优化

**并行度**：
- nz→nd 转换：全流水线
- 量化：并行处理多个元素
- rowmax：树形归约

**延迟隐藏**：
- FixPipe 与 MAC 计算并行
- acccvtuop 可以在 matmul uop 完成前启动（如果不需要该 slice）

---

## 9. 关键参数总结

| 参数 | 值 | 说明 |
|------|-----|------|
| **TMU接口** | | |
| TMU Flit 大小 | 256 B | 单个flit |
| TMU 读通道 | node1/pipe1 | CUBE读数据 |
| TMU 写通道 | node3/pipe3 | CUBE写数据 |
| TMU 访问延迟 | 4 cycles | 本地访问（H=0） |
| Prefetch Buffer 深度 | 32 entry | 每entry = 1个TMU读请求 |
| **L0 Cache** | | |
| L0A 容量 | 64 KB | 4-way set-associative |
| L0B 容量 | 64 KB | 4-way set-associative |
| L0 Entry 大小 | 512 B | 需要2个TMU flit填充 |
| L0A 读带宽 | 512 B/cycle | A 操作数 |
| L0B 读带宽 | 512 B/cycle | B 操作数（FP4: 1024 B） |
| **MAC 阵列** | | |
| MAC 阵列 | 16×16 脉动阵列 | 4096 MACs (FP16) |
| 脉动延迟 | ~7 cycles | 流水线 |
| **BufferC** | | |
| BufferC 容量 | 1 KB | 非 FP4 |
| **ACC Pool** | | |
| ACC 写带宽 | 256 B/cycle | nz 格式 |
| ACC 读带宽 | 256 B/cycle | 到 FixPipe |
| **FixPipe** | | |
| FixPipe 延迟 | ~11 cycles | per slice |
| FixPipe 吞吐量 | 4 cycles | per slice（流水线） |
| FixPipe 输出带宽 | 256 B/cycle | |
| TileStore Queue 深度 | 8 entry | 每entry = 1个TMU写请求 |

---

**文档状态**：完成  
**最后更新**：2026-06-09

