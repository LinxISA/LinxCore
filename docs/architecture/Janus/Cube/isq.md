# ISQ（Issue Queue）详细设计

本文档详细描述 CUBE 的 ISQ（Issue Queue）设计和 uop 调度机制。

---

## 1. 概述

ISQ 是 CUBE 的 uop 调度核心，负责：
- 接收 FSM 拆分生成的 uop
- 跟踪 uop 的 src ready 状态
- 按依赖关系和优先级调度 uop
- 支持 K 方向保序、MN 方向乱序

### 1.1 ISQ 在 CUBE 中的位置

```
BCTRL → 命令解码 → FSM (Fractal 拆分)
                      ↓
                    ISQ (32 uop)
                      ↓ (src ready)
                 L0A/L0B Cache
                      ↓
                  MAC 阵列
```

---

## 2. ISQ 组织

### 2.1 容量和结构

**容量**：32 uop（可配置参数）

**设计权衡**：
- 容量过小（例如 8）：限制乱序并发度
- 容量过大（例如 64）：面积和功耗增加
- 推荐：32 uop，平衡性能和成本

**物理实现**：
- CAM（Content Addressable Memory）用于依赖匹配
- 或 SRAM + 扫描逻辑

### 2.2 ISQ Entry 结构

```verilog
struct isq_entry_t {
    // 基本信息
    valid:          1 bit;          // 条目有效
    uop_id:         8 bits;         // uop 唯一标识
    tileop_id:      8 bits;         // 所属 tileop
    
    // 操作数地址
    A_tile:         6 bits;         // A 操作数 tile 索引
    A_offset:       16 bits;        // A tile 内偏移
    B_tile:         6 bits;         // B 操作数 tile 索引
    B_offset:       16 bits;        // B tile 内偏移
    
    // 目标信息
    C_slice_id:     7 bits;         // ACC slice ID (0-127)
    acc_chain:      2 bits;         // ACC 链标记 (0-3)
    
    // 依赖信息
    K_idx:          8 bits;         // K 维度索引
    M_idx:          8 bits;         // M 维度索引
    N_idx:          8 bits;         // N 维度索引
    deps_valid:     1 bit;          // 是否有依赖
    deps_uop_id:    8 bits;         // 依赖的 uop_id (K 方向前一个)
    
    // Ready 状态
    src_ready:      2 bits;         // [1]=A ready, [0]=B ready
    deps_ready:     1 bit;          // 依赖满足
    issued:         1 bit;          // 已发射
    
    // 优先级和调度
    priority:       2 bits;         // 优先级（K_idx==0 为高）
    age:            6 bits;         // 年龄计数器（用于公平性）
};
```

**总大小**：约 96 bits per entry

---

## 3. uop 入队

### 3.1 FSM 拆分生成 uop

**拆分流程**：
```
1. 命令解码：提取 shape (M, N), tile (tA, tB), K
2. 计算分形数量：
   M_tiles = M / 16
   N_tiles = N / 16
   K_tiles = K / (32B / element_size)
3. 生成 uop：
   for i in 0..M_tiles-1:
     for j in 0..N_tiles-1:
       for k in 0..K_tiles-1:
         uop = create_uop(i, j, k)
         enqueue_isq(uop)
```

**示例**（64×64 × 64×64，FP16）：
```
M=64, N=64, K=64
M_tiles = 64/16 = 4
N_tiles = 64/16 = 4
K_tiles = 64/16 = 4

总 uop 数 = 4 × 4 × 4 = 64 uop
```

### 3.2 入队逻辑

**入队条件**：
```
if (isq_full) {
    stall FSM
} else {
    allocate free entry
    fill entry fields
    set valid = 1
}
```

**空闲 entry 分配**：
- 扫描 ISQ 找第一个 valid=0 的 entry
- 或使用空闲列表（free list）

**初始状态**：
```
uop 刚入队时：
- src_ready = 2'b00 (A 和 B 都未 ready)
- deps_ready = (K_idx == 0) ? 1 : 0
- issued = 0
- priority = (K_idx == 0) ? 3 : 1
- age = 0
```

---

## 4. 依赖管理

### 4.1 K 方向依赖

**依赖规则**：
```
对于 uop[i,j,k]：
  if (k > 0):
      deps_uop_id = uop[i,j,k-1].uop_id
      deps_valid = 1
  else:
      deps_valid = 0
```

**依赖满足**：
```
当 uop[i,j,k-1] 完成（写入 ACC）时：
  broadcast uop_id
  for each isq_entry:
      if (deps_valid && deps_uop_id == broadcast_uop_id):
          deps_ready = 1
```

**实现**：
- CAM 匹配 deps_uop_id
- 或扫描 ISQ，比较 deps_uop_id

### 4.2 matmul.acc 的 C 依赖

**tmatmul.acc 依赖**：
```
tmatmul.acc 的 uop 依赖前一个 tmatmul/tmatmul.acc 的相同位置 uop

例如：
  tmatmul z0, ... → 生成 uop[0,0,0], uop[0,0,1], ...
  tmatmul.acc z0, ... → 生成 uop'[0,0,0], uop'[0,0,1], ...
  
依赖：
  uop'[i,j,k] 依赖 uop[i,j,K_tiles-1]（最后一个 K）
```

**跨 tileop 依赖**：
- 使用 acc_chain 和 (M_idx, N_idx) 匹配
- 前一个 tileop 的最后一个 K 迭代完成
- 当前 tileop 的 uop 才能发射

---

## 5. src ready 检测

### 5.1 L0 Cache 查询

**查询触发**：
- uop 入队时查询一次
- L0 Cache 有新数据填充时查询

**查询逻辑**：
```
for each uop in ISQ:
    if (!src_ready[1]):  // A not ready
        if (L0A.lookup(A_tile, A_offset) == HIT):
            src_ready[1] = 1
    
    if (!src_ready[0]):  // B not ready
        if (L0B.lookup(B_tile, B_offset) == HIT):
            src_ready[0] = 1
```

**实现优化**：
- 只检查未 ready 的 uop
- 批量检查（例如每 cycle 检查 8 个）

### 5.2 预取响应

**预取完成时**：
```
TMU 返回数据 → 填充 L0A/L0B entry
  ↓
查询 ISQ 中等待该地址的 uop
  ↓
更新对应 uop 的 src_ready
```

**地址匹配**：
```
for each uop in ISQ:
    if (uop.A_tile == filled_tile && 
        uop.A_offset == filled_offset):
        uop.src_ready[1] = 1
    
    if (uop.B_tile == filled_tile && 
        uop.B_offset == filled_offset):
        uop.src_ready[0] = 1
```

---

## 6. uop 选择和发射

### 6.1 发射条件

**uop 可发射当且仅当**：
```
(src_ready == 2'b11) &&        // A 和 B 都 ready
(deps_ready == 1) &&           // K 方向依赖满足
(!issued) &&                   // 未发射
(MAC_array_available)          // MAC 阵列空闲
```

### 6.2 选择策略

**优先级**：
```
Priority 3 (最高): K_idx == 0（新的输出位置）
Priority 2:        K_idx > 0 && age > threshold
Priority 1:        K_idx > 0 && age <= threshold
Priority 0:        保留
```

**年龄机制**：
```
每个 cycle：
  for each uop in ISQ:
      if (!issued):
          age++
          
发射时：
  age = 0
```

**选择算法**：
```
1. 扫描 ISQ，找所有满足发射条件的 uop
2. 按优先级排序
3. 同优先级按 age 排序（年龄大的优先）
4. 选择第一个
5. 发射
```

### 6.3 发射操作

**发射流程**：
```
1. 从 L0A/L0B 读取数据
2. 发送到 MAC 阵列
3. 更新 ISQ entry：
   - issued = 1
4. uop 保留在 ISQ（用于依赖匹配）
```

**发射后状态**：
- uop 仍在 ISQ 中
- 用于依赖匹配和完成跟踪
- MAC 计算完成后才释放

---

## 7. uop 完成和释放

### 7.1 完成检测

**完成条件**：
- MAC 计算完成
- BufferC 累加完成
- ACC 写入完成（如果是 K-chunk 结束）

**完成信号**：
```verilog
// MAC 阵列 → ISQ
input wire        mac_done_valid
input wire [7:0]  mac_done_uop_id

// ACC 写入 → ISQ
input wire        acc_write_done_valid
input wire [7:0]  acc_write_done_uop_id
```

### 7.2 依赖唤醒（Wakeup）

**Wakeup 广播**：
```
当 uop 完成时：
  broadcast uop_id
  
for each isq_entry:
    if (deps_valid && deps_uop_id == broadcast_uop_id):
        deps_ready = 1  // 唤醒依赖的 uop
```

**CAM 实现**：
```
deps_uop_id[7:0] → CAM
broadcast_uop_id[7:0] → CAM 输入
match[31:0] → 匹配结果（32 个 entry）

for i in 0..31:
    if (match[i]):
        isq[i].deps_ready = 1
```

### 7.3 ISQ Entry 释放

**释放条件**：
- uop 已完成
- 没有其他 uop 依赖此 uop

**释放检查**：
```
for each completed uop:
    if (no_younger_deps(uop_id)):
        release(uop_id)
```

**no_younger_deps 检查**：
```
function bool no_younger_deps(uop_id):
    for each isq_entry:
        if (valid && deps_uop_id == uop_id):
            return false  // 有 uop 依赖此 uop
    return true
```

**释放操作**：
```
isq[entry].valid = 0
free_list.push(entry)
```

---

## 8. 乱序执行示例

### 8.1 单个 tmatmul（64×64, K=4）

**uop 生成**：
```
M_tiles=4, N_tiles=4, K_tiles=4
总 uop = 64 个

uop 标识：uop[i,j,k]，其中：
  i ∈ [0,3]  (M 维度)
  j ∈ [0,3]  (N 维度)
  k ∈ [0,3]  (K 维度)
```

**依赖关系**：
```
K 方向依赖（必须保序）：
  uop[0,0,0] → uop[0,0,1] → uop[0,0,2] → uop[0,0,3]
  uop[0,1,0] → uop[0,1,1] → uop[0,1,2] → uop[0,1,3]
  ...

MN 方向无依赖（可以乱序）：
  uop[0,0,k] 和 uop[1,1,k] 可以并发
  uop[0,0,k] 和 uop[0,1,k] 可以并发
```

**乱序执行示例**：
```
Cycle 0: uop[0,0,0] ready → 发射
Cycle 1: uop[1,1,0] ready → 发射（乱序，与 uop[0,0,0] 并发）
Cycle 2: uop[0,1,0] ready → 发射
Cycle 3: uop[2,2,0] ready → 发射
...
Cycle 10: uop[0,0,0] 完成 → wakeup uop[0,0,1]
Cycle 11: uop[0,0,1] ready → 发射
...
```

### 8.2 多 ACC 链并发

**场景**：2 个并发 ACC 链
```
链 0: tmatmul z0, tA0, tB0, 64×64
链 1: tmatmul z1, tA1, tB1, 64×64

ISQ 中同时有：
  - 链 0 的 64 个 uop
  - 链 1 的 64 个 uop
  - 总计 128 个 uop（超出 ISQ 容量 32）
```

**分批处理**：
```
批次 1: 链 0 的前 16 个 uop + 链 1 的前 16 个 uop
批次 2: 链 0 的后 48 个 uop + 链 1 的后 48 个 uop（等待批次 1）
```

**链间无依赖**：
- 链 0 和链 1 的 uop 可以完全并发
- ISQ 选择时不区分 acc_chain

---

## 9. 性能分析

### 9.1 乱序收益

**顺序执行（无 ISQ）**：
```
必须按 uop 顺序发射
L0 Cache miss 导致停顿
MAC 阵列利用率低

示例：
  uop[0,0,0] cache miss → stall
  uop[0,0,1] 无法发射（顺序）
  uop[1,1,0] 无法发射（顺序）
```

**乱序执行（有 ISQ）**：
```
跳过 cache miss 的 uop
发射其他 ready 的 uop
MAC 阵列利用率高

示例：
  uop[0,0,0] cache miss → skip
  uop[0,0,1] 无法发射（K 依赖）
  uop[1,1,0] ready → 发射（乱序）
```

**利用率提升**：
- 无 ISQ：~40-50%
- 有 ISQ（32 深度）：~70-85%
- **提升 1.5-2×**

### 9.2 ISQ 深度敏感性

**深度不足（例如 8）**：
```
- ISQ 经常满
- FSM 频繁 stall
- 乱序机会少
- 利用率：~60%
```

**深度充足（例如 32）**：
```
- ISQ 很少满
- FSM 很少 stall
- 充足的乱序机会
- 利用率：~80%
```

**深度过大（例如 64）**：
```
- 边际收益递减
- 面积和功耗增加
- 扫描延迟增加
- 利用率：~85%（仅提升 5%）
```

**推荐**：32 深度，性价比最优

### 9.3 关键路径

**ISQ 中的关键路径**：
```
1. 依赖匹配（Wakeup）：
   broadcast uop_id → CAM 匹配 → 更新 deps_ready
   延迟：~1-2 ns
   
2. uop 选择：
   扫描 ISQ → 优先级比较 → 选择最高优先级
   延迟：~2-3 ns
   
3. src ready 检查：
   查询 L0 Cache → 更新 src_ready
   延迟：~1 ns

总关键路径：~5 ns（1.5 GHz 需要分多级流水线）
```

---

## 10. 实现优化

### 10.1 分段扫描

**问题**：扫描 32 个 entry 延迟高

**解决**：每 cycle 只扫描部分 entry
```
Cycle 0: 扫描 entry 0-7
Cycle 1: 扫描 entry 8-15
Cycle 2: 扫描 entry 16-23
Cycle 3: 扫描 entry 24-31
Cycle 4: 重复

每 4 cycles 完成一次完整扫描
```

**权衡**：
- 降低延迟
- 增加发射间隔（但仍可接受）

### 10.2 快速路径（Fast Path）

**观察**：大多数 uop 在入队时就 ready

**优化**：
```
if (src_ready == 2'b11 && deps_ready == 1):
    fast_path_issue()  // 直接发射，跳过 ISQ
else:
    normal_path()      // 进入 ISQ
```

**收益**：
- ~50% uop 走快速路径
- 降低 ISQ 压力
- 提升平均延迟

### 10.3 依赖链压缩

**观察**：K 方向依赖链很长（例如 K=64）

**优化**：
```
不需要保留所有已完成的 uop
只保留链头（最新完成的）

释放策略：
  if (uop 完成 && deps_uop 也完成):
      release(deps_uop)  // 释放父 uop
```

**收益**：
- 减少 ISQ 占用
- 加快 entry 循环

---

## 11. 调试和可观测性

### 11.1 性能计数器

**ISQ 相关计数器**：
```verilog
reg [31:0] isq_stall_cycles;      // ISQ 满导致的 stall
reg [31:0] deps_stall_cycles;     // K 依赖导致的 stall
reg [31:0] src_stall_cycles;      // L0 miss 导致的 stall
reg [31:0] uop_issued_count;      // 发射的 uop 数
reg [31:0] uop_ooo_count;         // 乱序发射的 uop 数
reg [5:0]  isq_occupancy;         // ISQ 当前占用（0-32）
reg [5:0]  isq_peak_occupancy;    // ISQ 峰值占用
```

**利用率计算**：
```
MAC 利用率 = uop_issued_count / total_cycles
乱序比例 = uop_ooo_count / uop_issued_count
ISQ 平均占用 = ∑isq_occupancy / total_cycles
```

### 11.2 调试信号

**关键调试信号**：
```verilog
// ISQ 状态
wire [31:0] isq_valid_bitmap;     // 哪些 entry 有效
wire [31:0] isq_ready_bitmap;     // 哪些 uop ready
wire [31:0] isq_issued_bitmap;    // 哪些 uop 已发射

// 当前选中的 uop
wire [4:0]  selected_entry;
wire [7:0]  selected_uop_id;
wire        selected_valid;
```

---

## 12. 关键参数总结

| 参数 | 值 | 说明 |
|------|-----|------|
| ISQ 深度 | 32 uop | 可配置 |
| Entry 大小 | ~96 bits | 包含所有字段 |
| 总容量 | 384 B | 32 × 96 bits |
| 选择延迟 | ~2-3 ns | 扫描和比较 |
| Wakeup 延迟 | ~1-2 ns | CAM 匹配 |
| 发射吞吐量 | 1 uop/cycle | 理想情况 |
| 乱序窗口 | 32 uop | ISQ 深度 |
| MAC 利用率 | ~70-85% | 有 ISQ |

---

**文档状态**：完成  
**最后更新**：2026-06-02

