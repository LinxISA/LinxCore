# acccvt 和 FixPipe 详细设计

本文档详细描述 acccvt 指令的实现，包括 acccvtuop 拆分、FixPipe 流水线、格式转换、量化和 rowmax 等。

---

## 1. 概述

acccvt 指令负责将 CUBE 累加器中的结果搬运到 TileReg，并在搬运过程中执行格式转换、量化等操作。

### 1.1 acccvt 职责

**主要功能**：
- 读取物理累加器（ACC）
- 格式转换（nz → nd）
- 量化（FP32 → FP16/FP8/INT8）
- rowmax 计算（可选）
- 通过 TileStore 写回 TileReg

**与 matmul 的关系**：
- matmul 生产数据（写 ACC）
- acccvt 消费数据（读 ACC）
- 通过依赖机制同步

---

## 2. acccvt 指令

### 2.1 指令格式

**基本格式**：
```
acccvt zd, tile_dst, mode, acc_chain
```

**rowmax 扩展**：
```
acccvt.rowmax zd, tile_dst, tile_rowmax, mode, acc_chain
```

**操作数**：
- `zd`：源累加器（z0-z3）
- `tile_dst`：目标 TileReg 索引（主数据）
- `tile_rowmax`：rowmax 输出 TileReg 索引（可选）
- `mode`：转换模式位图
- `acc_chain`：ACC 链标记

### 2.2 Mode 位图

**Mode 编码**（8 bits）：
```
[7:6] - 格式转换
  00: nz2nd（仅格式转换）
  01: 保留
  10: 保留
  11: 保留

[5:4] - 量化模式
  00: 无量化（FP32）
  01: FP16
  10: FP8 (E4M3)
  11: INT8

[3:2] - rowmax
  00: 无 rowmax
  01: rowmax（计算每行最大值）
  10: 保留
  11: 保留

[1:0] - 保留
```

**常用组合**：
```
mode=0x00: nz2nd + FP32（无量化）
mode=0x10: nz2nd + FP16
mode=0x20: nz2nd + FP8
mode=0x30: nz2nd + INT8
mode=0x04: nz2nd + FP32 + rowmax
mode=0x14: nz2nd + FP16 + rowmax
```

---

## 3. acccvt 拆分

### 3.1 拆分为 acccvtuop

**拆分规则**：按 ACC slice 粒度拆分
```
1. 查询 ACC 映射表
   acc_mapping[acc_chain][zd] → slice_list
   
2. 生成 acccvtuop
   for each slice in slice_list:
       acccvtuop = {
           slice_id: slice,
           mode: mode,
           tile_dst: tile_dst,
           offset: calculate_offset(slice),
           deps: matmul_uop[slice],
       }
       
3. acccvtuop 入队（独立队列或共享 ISQ）
```

**示例（64×64，16 个 slices）**：
```
tmatmul z0, ... → 16 个 slices (0-15)
acccvt z0, tC, mode=0x10

生成 16 个 acccvtuop：
acccvtuop[0]: slice 0 → tC offset 0
acccvtuop[1]: slice 1 → tC offset 1KB
acccvtuop[2]: slice 2 → tC offset 2KB
...
acccvtuop[15]: slice 15 → tC offset 15KB
```

### 3.2 依赖建立

**依赖规则**：
```
acccvtuop[i] 依赖 matmul_uop[i] 完成

对于 tmatmul.acc：
  acccvtuop[i] 依赖所有 K 迭代完成
  deps = matmul_uop[i, j, K_tiles-1]
```

**依赖跟踪**：
```verilog
struct acccvtuop_t {
    valid:          1 bit;
    slice_id:       7 bits;         // ACC slice ID
    mode:           8 bits;         // 转换模式
    tile_dst:       6 bits;         // 目标 tile
    offset:         16 bits;        // Tile 内偏移
    deps_uop_id:    8 bits;         // 依赖的 matmul uop
    deps_ready:     1 bit;          // 依赖满足
    issued:         1 bit;          // 已发射
};
```

### 3.3 Wakeup 机制

**Wakeup 触发**：
```
当 matmul uop 完成（ACC 写入完成）时：
  broadcast uop_id
  
for each acccvtuop:
    if (deps_uop_id == broadcast_uop_id):
        deps_ready = 1  // Wakeup
```

**提前 Wakeup 优化**：
```
对于 K_tiles > 1 的情况：
  acccvtuop 可以在 K_chunk 完成后开始
  不需要等待所有 K 完成

例如（K=16, K_chunk=4）：
  K=0-3 完成 → ACC 部分写入
  acccvtuop 可以开始读取（乐观）
  同时 K=4-7 继续计算
```

---

## 4. FixPipe 流水线

### 4.1 流水线架构

**FixPipe 阶段**：
```
Stage 0-3: ACC 读取（256 B/cy，4 拍读 1 KB）
Stage 4:   数据缓冲
Stage 5-6: nz → nd 格式转换
Stage 7:   量化/格式转换
Stage 8:   rowmax 计算（可选）
Stage 9:   输出缓冲
Stage 10:  TileStore 请求生成

总延迟：~11 cycles per slice
```

**流水线并发**：
- 多个 acccvtuop 可以并发
- 受限于 FixPipe 吞吐量（256 B/cy）

### 4.2 Stage 0-3：ACC 读取

**读取接口**：
```verilog
// acccvtuop → ACC Pool
output wire        acccvt_acc_rd_req_valid
input  wire        acc_acccvt_rd_req_ready
output wire [6:0]  acccvt_acc_rd_req_slice   // Slice ID
output wire [STID_W-1:0] acccvt_acc_rd_req_stid
output wire [BID_W-1:0] acccvt_acc_rd_req_bid

// ACC Pool → FixPipe
input  wire        acc_acccvt_rd_rsp_valid
input  wire [STID_W-1:0] acc_acccvt_rd_rsp_stid
input  wire [BID_W-1:0] acc_acccvt_rd_rsp_bid
input  wire [6:0]  acc_acccvt_rd_rsp_slice
input  wire [255:0][7:0] acc_acccvt_rd_rsp_data  // 256 B
```

Concurrent reads are associated by `(STID,BID,slice_id)`; an implementation
that omits echoed fields must guarantee and document strictly in-order
responses on an STID-dedicated lane.

**读取流程**：
```
Cycle 0: 请求读取 slice，读取 0-255 B
Cycle 1: 读取 256-511 B
Cycle 2: 读取 512-767 B
Cycle 3: 读取 768-1023 B
Cycle 4: 数据准备好（1 KB，nz 格式）
```

### 4.3 Stage 4：数据缓冲

**缓冲作用**：
- 解耦 ACC 读取和格式转换
- 支持流水线并发
- 容量：2-4 个 slice（2-4 KB）

### 4.4 Stage 5-6：nz → nd 格式转换

**nz 格式（输入）**：
```
Natural Z-curve 布局
16×16 矩阵按 Z-curve 顺序存储

优点：
  - MAC 阵列自然输出
  - 空间局部性好

缺点：
  - 软件不友好
  - 需要转换
```

**nd 格式（输出）**：
```
Natural Data（行主序）
16×16 矩阵按行主序存储

Row 0: [0][1][2]...[15]
Row 1: [16][17][18]...[31]
...
Row 15: [240][241]...[255]

优点：
  - 软件友好
  - 内存访问连续
```

**转换实现**：
```
使用重排序网络（Permutation Network）：
  - Benes 网络 或 Butterfly 网络
  - 可配置的交叉开关
  - 延迟：1-2 cycles

电路示例（简化的 4×4）：
  输入（nz）: [0,1,4,5, 2,3,6,7, 8,9,12,13, 10,11,14,15]
  输出（nd）: [0,1,2,3, 4,5,6,7, 8,9,10,11, 12,13,14,15]
  
实际 16×16：
  - 256 个元素
  - 需要多级交叉开关
  - 延迟 2 cycles
```

### 4.5 Stage 7：量化和格式转换

**FP32 → FP16**：
```
IEEE 754 FP32:
  [31]    sign
  [30:23] exponent (8 bits, bias=127)
  [22:0]  mantissa (23 bits)

IEEE 754 FP16:
  [15]    sign
  [14:10] exponent (5 bits, bias=15)
  [9:0]   mantissa (10 bits)

转换算法：
1. 提取 FP32 字段
2. 调整指数：exp16 = exp32 - 127 + 15
3. 舍入尾数：round(mantissa32[22:13])
4. 处理特殊情况：
   - Overflow → Infinity
   - Underflow → 0 或 denormal
   - NaN → NaN
5. 组装 FP16
```

**FP32 → FP8 (E4M3)**：
```
E4M3 格式：
  [7]     sign
  [6:3]   exponent (4 bits, bias=7)
  [2:0]   mantissa (3 bits)

范围：[-448, 448]
精度：相对误差 ~6%

转换：
1. 提取 FP32 字段
2. 调整指数：exp8 = exp32 - 127 + 7
3. 舍入尾数：round(mantissa32[22:20])
4. 饱和处理：
   - value > 448 → 448
   - value < -448 → -448
5. 组装 FP8
```

**FP32 → INT8**：
```
转换算法：
1. 应用 scale：scaled = fp32_value * scale
2. 舍入：int_value = round(scaled)
3. 饱和：
   - int_value > 127 → 127
   - int_value < -128 → -128
4. 输出 INT8

Scale 因子：
  - 可配置（例如 scale=128/max_value）
  - 或固定（例如 scale=1.0）
```

**硬件实现**：
```
并行转换单元：
  - 16 个并行转换器（处理 16 个 FP32）
  - 每个转换器：1 cycle
  - 吞吐量：16 elements/cycle

流水线：
  Cycle 0: 输入 16 个 FP32
  Cycle 1: 输出 16 个 FP16/FP8/INT8
```

### 4.6 Stage 8：rowmax 计算

**rowmax 定义**：计算每行的最大值
```
对于 16×16 矩阵：
  for row in 0..15:
      max_val = -Infinity
      for col in 0..15:
          if (matrix[row][col] > max_val):
              max_val = matrix[row][col]
      rowmax[row] = max_val

输出：16 个 FP32 值
```

**硬件实现（树形归约）**：
```
16 个元素 → 8 个 max → 4 个 max → 2 个 max → 1 个 max

Level 0: 16 元素
Level 1: 8 个比较器 → 8 个 max
Level 2: 4 个比较器 → 4 个 max
Level 3: 2 个比较器 → 2 个 max
Level 4: 1 个比较器 → 1 个 max

总延迟：4 级 = 1 cycle（流水线）

并行处理 16 行：
  - 16 个树形归约单元
  - 1 cycle 产生 16 个 rowmax 值
```

**输出组织**：
```
主数据：tile_dst
  - 转换后的矩阵（16×16×FP16/FP8/INT8）
  
rowmax 数据：tile_rowmax
  - 16 个 FP32 值（每行一个）
  - 占用 64 B
  - 写到单独的 TileReg
```

### 4.7 Stage 9-10：输出和 TileStore

**输出缓冲**：
- 容量：2-4 个 slice
- Ping-pong buffer
- 解耦 FixPipe 和 TileStore

**TileStore 请求生成**：
```
1. FixPipe 完成一个 slice 的处理
2. 生成 TileStore 请求：
   - tile_dst：目标 TileReg
   - offset：slice 在 tile 内的偏移
   - size：写入大小
   - data：转换后的数据
3. 请求入队 TileStore 队列
4. 等待 TileStore 完成
```

---

## 5. TileStore 接口

### 5.1 TileStore 请求队列

**队列结构**：
```verilog
struct tilestore_req_t {
    valid:          1 bit;
    tile_dst:       6 bits;         // 目标 TileReg
    offset:         16 bits;        // Tile 内偏移
    size:           16 bits;        // 写入大小
    data:           2048 B;         // 数据（burst）
    stid:           STID_W bits;    // independent BROB ring / thread context
    bid:            BID_W bits;     // complete BROB slot identity
    slice_id:       7 bits;         // 对应的 ACC slice
};
```

**队列容量**：8-16 个 entry

### 5.2 TileStore 发射

**发射条件**：
```
1. 队列有未发射的请求
2. TMU 写口空闲
3. 带宽允许（每拍最多 1 个请求）
```

**发射流程**：
```
1. 从队列选择请求（FIFO）
2. 发送到 TMU：
   cube_tmu_wr_req_valid = 1
   cube_tmu_wr_req_tile = tile_dst
   cube_tmu_wr_req_offset = offset
   cube_tmu_wr_req_data = data
   cube_tmu_wr_req_stid = stid
   cube_tmu_wr_req_bid = bid
3. TMU 仲裁和写入 TileReg
4. 等待写完成响应
```

### 5.3 写完成处理

**完成响应**：
```verilog
// TMU → CUBE
input wire        tmu_cube_wr_done_valid
input wire [5:0]  tmu_cube_wr_done_tile
input wire [STID_W-1:0] tmu_cube_wr_done_stid
input wire [BID_W-1:0] tmu_cube_wr_done_bid
input wire [6:0]  tmu_cube_wr_done_slice_id
```

Completion matches `(STID,BID,slice_id)` (or a stronger echoed transaction
identity) before freeing the ACC slice.

**完成操作**：
```
1. 接收写完成响应
2. 释放 ACC slice：
   free_bitmap[slice_id] = 1
3. 更新 acccvt 进度：
   completed_slices++
4. 如果所有 slices 完成：
   向 BROB 报告 acccvt 完成
```

---

## 6. 多 acccvtuop 并发

### 6.1 FixPipe 并发

**并发限制**：
```
FixPipe 吞吐量：256 B/cycle
单个 slice：1 KB
处理时间：4 cycles

理论并发度：
  如果流水线满载，可以有 4 个 acccvtuop 在不同阶段
```

**实际并发**：
```
Stage 0-3: acccvtuop[0] 读 ACC
Stage 4-6: acccvtuop[1] nz→nd 转换
Stage 7-8: acccvtuop[2] 量化 + rowmax
Stage 9-10: acccvtuop[3] TileStore

4 个 acccvtuop 流水线并发
```

### 6.2 TileStore 带宽

**带宽限制**：
```
TMU 写带宽：2048 B/cycle（burst）
TileStore 请求：每拍最多 1 个
单个 slice：1 KB（量化后可能更小）

例如（FP16）：
  1 KB slice → 512 B（FP32→FP16）
  可以在 1 拍内完成（< 2048 B）
```

### 6.3 rowmax 特殊处理

**双写问题**：
```
acccvt.rowmax 需要写两个 TileReg：
  1. tile_dst：主数据（16×16×FP16）
  2. tile_rowmax：rowmax 数据（16×FP32）

解决方案 1：串行写
  - 先写 tile_dst
  - 再写 tile_rowmax
  - 总计 2 个 TileStore 请求

解决方案 2：合并写（如果连续）
  - 如果 tile_dst 和 tile_rowmax 连续
  - 合并为一个 burst
  - 总计 1 个 TileStore 请求
```

---

## 7. 性能分析

### 7.1 延迟分析

**单个 acccvtuop 延迟**：
```
ACC 读取:     4 cycles
nz→nd 转换:   2 cycles
量化:         1 cycle
rowmax:       1 cycle (可选)
输出缓冲:     1 cycle
TileStore:    3 cycles（请求 + TMU + TileReg）

总计：~12 cycles（无 rowmax）
     ~13 cycles（有 rowmax）
```

**整个 acccvt 延迟（64×64，16 slices）**：
```
流水线并发执行：
  前 4 个 slices：填充流水线，~16 cycles
  中间 slices：流水线满载，4 cycles/slice
  最后 4 个 slices：排空流水线，~16 cycles

总延迟：16 + (16-8)×4 + 16 = 64 cycles

平均：64/16 = 4 cycles/slice
```

### 7.2 吞吐量分析

**FixPipe 吞吐量**：
```
256 B/cycle（读/写）
= 64 FP32/cycle
= 16 FP32/cycle per stage（4 级流水线）

对于 16×16 矩阵（256 FP32）：
  处理时间：256 / 64 = 4 cycles
```

**TileStore 吞吐量**：
```
2048 B/cycle（burst）
单个 slice（FP16）：512 B
可以每拍完成 1 个 slice

瓶颈：FixPipe（4 cycles/slice）
```

### 7.3 与 matmul 并行

**并行机会**：
```
matmul 计算和 acccvt 可以并行：
  - matmul uop[i] 完成 → acccvtuop[i] 开始
  - 不需要等待所有 matmul uop 完成
  - 提高整体吞吐量

示例（16 slices）：
Cycle 0-20:   matmul uop[0-3] 计算
Cycle 20:     acccvtuop[0] 开始
Cycle 20-40:  matmul uop[4-7] 计算（并行）
Cycle 24:     acccvtuop[1] 开始
...
```

---

## 8. 调试和可观测性

### 8.1 性能计数器

```verilog
// acccvt 统计
reg [31:0] acccvt_count;          // acccvt 指令数
reg [31:0] acccvtuop_count;       // acccvtuop 总数
reg [31:0] acccvtuop_cycles;      // acccvtuop 总周期

// FixPipe 利用率
reg [31:0] fixpipe_busy_cycles;   // FixPipe 忙碌周期
reg [31:0] fixpipe_idle_cycles;   // FixPipe 空闲周期

// TileStore 统计
reg [31:0] tilestore_req_count;   // TileStore 请求数
reg [31:0] tilestore_stall_cycles; // TileStore 停顿周期

// 计算
avg_latency = acccvtuop_cycles / acccvtuop_count
fixpipe_util = fixpipe_busy_cycles / (fixpipe_busy_cycles + fixpipe_idle_cycles)
```

### 8.2 调试信号

```verilog
// acccvtuop 状态
wire [15:0] acccvtuop_valid;      // 哪些 acccvtuop 有效
wire [15:0] acccvtuop_ready;      // 哪些 acccvtuop ready

// FixPipe 状态
wire [3:0]  fixpipe_stage_valid;  // 各阶段是否有效
wire [6:0]  fixpipe_current_slice; // 当前处理的 slice

// TileStore 状态
wire [7:0]  tilestore_queue_valid; // 队列哪些 entry 有效
wire [2:0]  tilestore_queue_occupancy; // 队列占用
```

---

## 9. 关键参数总结

| 参数 | 值 | 说明 |
|------|-----|------|
| FixPipe 吞吐量 | 256 B/cycle | 读/写 |
| ACC 读带宽 | 256 B/cycle | 4 拍读 1 KB |
| nz→nd 延迟 | 2 cycles | 重排序网络 |
| 量化延迟 | 1 cycle | FP32→FP16/FP8/INT8 |
| rowmax 延迟 | 1 cycle | 树形归约 |
| 单 slice 延迟 | ~12 cycles | 无 rowmax |
| 流水线并发 | 4 slices | 理论 |
| TileStore 带宽 | 2048 B/cycle | Burst |
| TileStore 队列 | 8-16 entry | 请求队列 |
| acccvtuop 粒度 | 1 slice | 1 KB (FP4: 2 KB) |

---

**文档状态**：完成  
**最后更新**：2026-06-02
