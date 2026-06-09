# 累加器管理详细实现

本文档详细描述 CUBE 的累加器管理机制，包括物理累加器池、slice 分配和释放、ACC 链管理等。

---

## 1. 概述

CUBE 累加器系统负责管理 FP32 累加结果，支持：
- 架构累加器（z0-z3）到物理累加器的映射
- uop 粒度的 slice 分配和释放
- 多 ACC 链并发
- 高效的 RMW（读-改-写）操作

### 1.1 累加器层次

```
架构层（ISA）：
  z0, z1, z2, z3（4 个架构累加器）
  ↓
重命名层（硬件）：
  acc_chain + arch_acc → 物理 slice 列表
  ↓
物理层（SRAM）：
  128 KB 物理累加器池
  128 slices × 1 KB (非 FP4)
  64 slices × 2 KB (FP4)
```

---

## 2. 物理累加器池

### 2.1 存储组织

**容量**：128 KB

**Slice 组织**：
```
非 FP4：
  - 128 slices
  - 每个 slice：1 KB（16×16×FP32）
  - 总容量：128 KB

FP4：
  - 64 slices
  - 每个 slice：2 KB（16×32×FP32）
  - 总容量：128 KB
```

**物理实现**：
- SRAM 阵列
- 8 banks 组织（减少冲突）
- 每个 bank：16 KB

### 2.2 Bank 组织

**Bank 交错**：
```
Bank 0: slices 0, 8, 16, 24, 32, ..., 120
Bank 1: slices 1, 9, 17, 25, 33, ..., 121
Bank 2: slices 2, 10, 18, 26, 34, ..., 122
Bank 3: slices 3, 11, 19, 27, 35, ..., 123
Bank 4: slices 4, 12, 20, 28, 36, ..., 124
Bank 5: slices 5, 13, 21, 29, 37, ..., 125
Bank 6: slices 6, 14, 22, 30, 38, ..., 126
Bank 7: slices 7, 15, 23, 31, 39, ..., 127

交错模式：slice_id % 8 → bank_id
```

**Bank 冲突避免**：
```
同时访问的 slices 尽量分配到不同 bank
例如：uop[0,0,k] 和 uop[0,1,k] 分配到不同 bank
```

### 2.3 Slice 物理结构

**Slice 布局（1 KB，非 FP4）**：
```
16×16×FP32 = 1024 B
按 nz（Natural Z-curve）格式存储

地址映射：
slice_base + nz_offset(row, col)

nz_offset：Z-curve 空间填充曲线
  - 优化空间局部性
  - 减少 bank 冲突
```

**Slice 布局（2 KB，FP4）**：
```
16×32×FP32 = 2048 B
按 nz 格式存储
与非 FP4 类似，但列数加倍
```

---

## 3. 架构累加器和重命名

### 3.1 架构累加器

**ISA 定义**：
```
z0, z1, z2, z3（4 个架构累加器）

每个 tmatmul/tmatmul.acc 指定：
  - zd：目标累加器（z0-z3）
  - acc_chain：ACC 链标记（0-3）
```

### 3.2 累加器映射表（ACC Mapping Table）

**表结构**：
```verilog
struct acc_mapping_entry_t {
    valid:          1 bit;          // Entry 有效
    acc_chain:      2 bits;         // ACC 链标记
    arch_acc:       2 bits;         // 架构累加器（z0-z3）
    
    // 物理 slice 列表
    num_slices:     8 bits;         // 分配的 slice 数量
    slice_list:     128 bits;       // Slice 位图
    
    // 状态
    state:          2 bits;         // ALLOC/BUSY/DRAINING
    num_uops:       8 bits;         // 总 uop 数
    completed_uops: 8 bits;         // 已完成 uop 数
    
    // 依赖信息
    tileop_id:      8 bits;         // 所属 tileop
    
    // per-输出位置的 last_uop_id 记录（用于细粒度依赖）
    // 每个 slice 对应一个输出位置 (mi, nj)
    // 记录写入该 slice 的最后一个 uop_id
    last_uop_id:    8 bits[128];    // 每个 slice 一个 last_uop_id
                                    // 用于 tmatmul.acc 的细粒度依赖
};
```

**细粒度依赖管理**：
```
tmatmul.acc 链的依赖是 per-输出位置的：

第一个 tmatmul（FSM 拆分阶段）：
  FSM 生成 uop[i,j,k]，分配 ACC[slice_ij]
  FSM 立即更新：last_uop_id[slice_ij] = uop[i,j,K_tiles-1].uop_id
  // 在 Dispatch Time（译码拆分阶段）就确定并记录最后一个 uop

第二个 tmatmul.acc（FSM 拆分阶段）：
  FSM 查询 ACC 映射表，读取 last_uop_id[slice_ij]
  FSM 生成 uop'[i,j,0]，设置：
    uop'[i,j,0].deps_uop_id = last_uop_id[slice_ij]
  // 在拆分时就建立依赖关系，无需等待执行阶段

关键时序：
  Cycle 0:   第一个 tmatmul 开始拆分
  Cycle 1-5: FSM 生成 uop[i,j,0..K-1]，更新 last_uop_id[slice_ij]
  Cycle 6:   第二个 tmatmul.acc 开始拆分（无需等待第一个执行完成）
  Cycle 7:   FSM 查询 last_uop_id[slice_ij]，绑定依赖关系
  Cycle 8:   uop'[i,j,0] 入队 ISQ，携带 deps_uop_id
  ...
  Cycle 100: uop[i,j,K-1] 执行完成，broadcast uop_id
  Cycle 101: uop'[i,j,0].deps_ready = 1（Wakeup）

优势：
- last_uop_id 在 FSM 拆分时更新（Dispatch Time），无需等待执行
- 第二个 tileop 可以在第一个还在执行时就完成拆分并入队 ISQ
- 支持跨 tileop 的流水线并行（译码、发射、执行重叠）
- 依赖检查在执行时动态完成（Wakeup 机制）
```

**表容量**：16 个 entry（支持 4 个 ACC 链 × 4 个架构累加器）

### 3.3 映射查询

**查询接口**：
```
Input:  acc_chain, arch_acc
Output: slice_list, state

查询逻辑：
for each entry in acc_mapping_table:
    if (valid && acc_chain == req_chain && arch_acc == req_acc):
        return entry
return NOT_FOUND
```

**查询场景**：
```
1. tmatmul.acc：查询前一个 tmatmul 的 slice 列表
2. acccvt：查询需要搬运的 slice 列表
3. uop 发射：查询分配的 slice
```

---

## 4. Slice 分配

### 4.1 空闲 Slice 管理

**空闲位图**：
```verilog
reg [127:0] free_slice_bitmap;  // 1=空闲，0=使用中

初始状态：all 1's（全部空闲）

分配时：clear bit
释放时：set bit
```

**快速查找**：
```
使用前导零计数（CLZ）或 find-first-set（FFS）
硬件加速空闲 slice 查找

示例：
free_bitmap = 0x0000FFFF_FFFF0000
ffs(free_bitmap) = 16  // 第 16 个 bit 是第一个空闲
```

### 4.2 分配策略

**First-Fit（首次适配）**：
```
从低地址开始扫描 free_bitmap
找到第一个空闲 slice
优点：简单快速
缺点：可能导致碎片
```

**Bank-Aware 分配**：
```
优先分配到不同 bank
减少 bank 冲突

算法：
1. 计算需要的 slice 数量（例如 64 个，64×64）
2. 尝试均匀分配到 8 个 bank
3. 每个 bank 分配 ~8 个 slices
4. 使用轮询方式选择 bank

示例：
需要 16 个 slices
Bank 0: 2 slices
Bank 1: 2 slices
...
Bank 7: 2 slices
```

### 4.3 分配流程

**tmatmul 分配**：
```
1. FSM 拆分计算需要的 slice 数量
   num_slices = M_tiles × N_tiles
   
2. 检查空闲 slices
   if (count_free() < num_slices):
       stall  // 等待释放
   
3. 分配 slices
   slice_list = allocate_slices(num_slices)
   
4. 更新 ACC 映射表
   acc_mapping[acc_chain][arch_acc] = {
       valid: 1,
       slice_list: slice_list,
       num_slices: num_slices,
       state: ALLOC,
       ...
   }
   
5. 将 slice_list 分发给 uop
   for each uop[i, j, k]:
       uop.C_slice_id = slice_list[i * N_tiles + j]
```

**示例（64×64, K=4）**：
```
M_tiles = 4, N_tiles = 4
需要 4×4 = 16 个 slices

分配：
uop[0,0,*] → slice 0
uop[0,1,*] → slice 8  (不同 bank)
uop[0,2,*] → slice 16
uop[0,3,*] → slice 24
uop[1,0,*] → slice 1
...
```

---

## 5. Slice 访问（RMW）

### 5.1 读-改-写（RMW）操作

**RMW 需求**：累加操作需要先读后写
```
ACC[slice] = ACC[slice] + BufferC
```

**RMW 流水线**：
```
Stage 0-3: 读取 ACC slice（256 B/cy, 4 拍读 1 KB）
Stage 4:   FP32 加法（ACC + BufferC）
Stage 5-8: 写回 ACC slice（256 B/cy, 4 拍写 1 KB）

总延迟：9 cycles per slice
```

### 5.2 Bank 并行访问

**单 Bank 冲突**：
```
如果多个 uop 同时访问同一个 bank：
  - 串行化访问
  - 延迟增加
```

**Bank 交错优化**：
```
不同 uop 的 slices 分配到不同 bank
支持并行 RMW

示例：
uop[0,0,k] 访问 slice 0 (Bank 0)  ┐
uop[0,1,k] 访问 slice 8 (Bank 0)  ├─ 冲突！
                                   ┘

优化后：
uop[0,0,k] 访问 slice 0 (Bank 0)  ┐
uop[0,1,k] 访问 slice 1 (Bank 1)  ├─ 并行！
uop[0,2,k] 访问 slice 2 (Bank 2)  │
uop[0,3,k] 访问 slice 3 (Bank 3)  ┘
```

### 5.3 BufferC 减少 RMW

**问题**：每个 K 迭代都 RMW，开销大

**解决**：BufferC（K-Accumulator）
```
K_chunk = 4

K=0: BufferC = MAC_output
K=1: BufferC += MAC_output
K=2: BufferC += MAC_output
K=3: BufferC += MAC_output
     ACC += BufferC          ← 只有一次 RMW

RMW 次数：K_tiles / K_chunk
减少：K_chunk × 倍
```

---

## 6. Slice 释放

### 6.1 释放时机

**uop 粒度释放**：
```
每个 uop 对应一个 slice
当 acccvtuop 完成（TileStore 写回完成）时释放

不是整个 tmatmul 完成才释放
逐个 slice 释放，提高周转率
```

**释放流程**：
```
1. acccvtuop 通过 FixPipe 完成格式转换
2. TileStore 写回 TileReg
3. TMU 返回写完成响应
4. 释放对应 slice：
   free_bitmap[slice_id] = 1
5. 更新 ACC 映射表：
   completed_uops++
   if (completed_uops == num_uops):
       release_mapping_entry()
```

### 6.2 映射表释放

**释放条件**：
```
所有 slices 都已释放
（completed_uops == num_uops）

释放操作：
acc_mapping[entry].valid = 0
```

### 6.3 提前释放优化

**观察**：某些 slice 可以提前释放
```
对于 tmatmul（非 .acc）：
  - 每个 slice 独立
  - 完成搬运后立即释放

对于 tmatmul.acc：
  - 需要等待整个链完成
  - 不能提前释放（下一个 tmatmul.acc 需要）
```

---

## 7. ACC 链管理

### 7.1 ACC 链定义

**ACC 链**：连续的 tmatmul/tmatmul.acc 序列
```
链示例：
tmatmul z0, ..., chain=0       // 链头
tmatmul.acc z0, ..., chain=0   // 链中
tmatmul.acc z0, ..., chain=0   // 链中
acccvt z0, ..., chain=0        // 链尾
```

**链的独立性**：
- 不同链（chain=0, chain=1）完全独立
- 可以并发执行
- 共享物理 ACC 池

### 7.2 链间依赖

**无依赖**：
```
链 0 和链 1 之间无依赖
可以任意交错执行

示例：
Cycle 0: 链 0 的 tmatmul 开始
Cycle 5: 链 1 的 tmatmul 开始（并发）
Cycle 10: 链 0 的 tmatmul.acc 开始
Cycle 12: 链 1 的 tmatmul.acc 开始
```

**链内依赖**：
```
同一个链内有严格的顺序依赖
tmatmul.acc 必须等待前一个 tmatmul/tmatmul.acc 完成

依赖检测：
  通过 acc_mapping_table 查询
  检查前一个操作的 state 和 completed_uops
```

### 7.3 链状态跟踪

**状态定义**：
```
ALLOC:      已分配，未开始计算
BUSY:       正在计算
DRAINING:   正在搬运（acccvt）
COMPLETED:  已完成
```

**状态转换**：
```
tmatmul:
  ALLOC → BUSY（第一个 uop 发射）
  BUSY → BUSY（计算中）
  BUSY → DRAINING（acccvt 开始）

tmatmul.acc:
  等待前一个到达 BUSY → ALLOC
  ALLOC → BUSY（第一个 uop 发射）
  ...

acccvt:
  BUSY → DRAINING（开始搬运）
  DRAINING → COMPLETED（所有 slices 搬运完成）
```

---

## 8. 多链并发

### 8.1 物理资源共享

**共享资源**：
- 物理 ACC 池（128 KB）
- MAC 阵列
- L0A/L0B Cache
- ISQ

**独立资源**：
- ACC 映射表 entry（每个链独立）
- Slice 分配（从共享池中分配）

### 8.2 并发示例

**2 个链并发**：
```
链 0: tmatmul z0, 64×64 → 16 slices
链 1: tmatmul z1, 64×64 → 16 slices

总需求：32 slices（< 128 slices，OK）

时间线：
Cycle 0-20:  链 0 分配 slices 0-15，计算
Cycle 10-30: 链 1 分配 slices 16-31，计算（并发）
Cycle 20-40: 链 0 acccvt，释放 slices 0-15
Cycle 30-50: 链 1 acccvt，释放 slices 16-31
```

**4 个链并发**：
```
链 0: 16 slices
链 1: 16 slices
链 2: 16 slices
链 3: 16 slices

总需求：64 slices（< 128 slices，OK）
```

### 8.3 资源耗尽处理

**Slice 耗尽**：
```
if (free_slices < required_slices):
    stall new tmatmul
    wait for acccvt to release slices
```

**优先级策略**：
- 已开始的链优先完成
- 新链等待

---

## 9. 性能优化

### 9.1 Bank 冲突避免

**分配策略优化**：
```
轮询分配到 8 个 bank
减少同时访问同一个 bank 的概率

示例（16 个 slices）：
slice 0  → Bank 0
slice 1  → Bank 1
slice 2  → Bank 2
...
slice 7  → Bank 7
slice 8  → Bank 0  (下一轮)
...
```

### 9.2 预分配

**观察**：FSM 拆分时已知需要多少 slices

**优化**：提前分配所有 slices
```
FSM 拆分阶段：
  1. 计算 num_slices
  2. 一次性分配所有 slices
  3. 生成 uop 时直接分配 slice_id

好处：
  - 减少分配开销
  - 避免 uop 等待分配
```

### 9.3 Slice 回收优化

**快速回收**：
```
acccvtuop 完成 → 立即释放 slice
不等待整个 tmatmul 完成

好处：
  - 提高 slice 周转率
  - 减少资源耗尽概率
```

---

## 10. 调试和可观测性

### 10.1 性能计数器

```verilog
// Slice 分配
reg [31:0] slice_alloc_count;      // 分配次数
reg [31:0] slice_free_count;       // 释放次数
reg [31:0] slice_stall_cycles;     // 因 slice 不足 stall

// Bank 冲突
reg [31:0] bank_conflict_count[7:0];  // 每个 bank 的冲突次数

// ACC 利用率
reg [6:0]  acc_occupancy;          // 当前使用的 slices（0-128）
reg [6:0]  acc_peak_occupancy;     // 峰值使用

// 计算
acc_utilization = acc_occupancy / 128
avg_occupancy = ∑acc_occupancy / total_cycles
```

### 10.2 调试信号

```verilog
// Slice 分配状态
wire [127:0] slice_alloc_bitmap;   // 哪些 slice 已分配
wire [127:0] slice_free_bitmap;    // 哪些 slice 空闲

// ACC 映射表
wire [15:0]  acc_mapping_valid;    // 哪些映射 entry 有效
wire [7:0]   acc_mapping_state[15:0];  // 每个 entry 的状态

// 当前分配
wire [6:0]   current_alloc_slice;  // 正在分配的 slice
wire         alloc_valid;
```

### 10.3 错误检测

**检测项**：
```
1. Slice 泄漏：
   - 长时间未释放的 slice
   - 检测：access_count == 0 && alloc_time > threshold

2. Double free：
   - 释放已经空闲的 slice
   - 检测：free_bitmap[slice_id] == 1 时尝试释放

3. 映射表溢出：
   - 映射表 entry 耗尽
   - 检测：所有 entry valid == 1

4. Bank 冲突过高：
   - 某个 bank 冲突次数异常
   - 检测：bank_conflict[i] > threshold
```

---

## 11. 关键参数总结

| 参数 | 值 | 说明 |
|------|-----|------|
| ACC 总容量 | 128 KB | 物理 SRAM |
| Slice 大小 | 1 KB | 非 FP4 |
| Slice 大小 (FP4) | 2 KB | FP4 |
| Slice 数量 | 128 | 非 FP4 |
| Slice 数量 (FP4) | 64 | FP4 |
| Banks | 8 | 并行访问 |
| 架构累加器 | 4 | z0-z3 |
| ACC 链数 | 2-4 | 可配置 |
| 映射表容量 | 16 entry | ACC Mapping Table |
| RMW 延迟 | ~9 cycles | Per slice |
| 分配策略 | Bank-aware | 轮询 bank |
| 释放粒度 | uop 粒度 | 即时释放 |

---

**文档状态**：完成  
**最后更新**：2026-06-09

