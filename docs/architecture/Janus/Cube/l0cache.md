# L0A/L0B Cache 详细设计

本文档详细描述 CUBE 的 L0A/L0B Cache 设计，包括缓存组织、地址映射、替换策略和预取机制。

---

## 1. 概述

L0A/L0B Cache 是 CUBE 输入路径的关键组件，负责缓存 A 和 B 操作数，支持跨 tileop 的数据复用。

### 1.1 设计目标

**主要目标**：
- 减少 TileReg 访问次数
- 提高数据复用率
- 隐藏 TMU 访问延迟
- 支持预取

**关键特性**：
- 真正的 cache（地址匹配 + PLRU 替换）
- 跨 tileop 复用
- 优先级策略（块内 vs 块外）
- 预取支持

---

## 2. Cache 组织

### 2.1 基本配置

**L0A Cache（A 操作数）**：
```
容量：8 KB（推荐配置）
组织：4-way 组相联
Sets：16
Entry 大小：512 B
总 entry 数：16（4-way × 4 sets）
```

**L0B Cache（B 操作数）**：
```
容量：8 KB（推荐配置，非 FP4）
       16 KB（FP4，双倍容量）
组织：4-way 组相联
Sets：16
Entry 大小：512 B（非 FP4）
          1024 B（FP4）
总 entry 数：16（非 FP4）
           16（FP4，但每个 entry 更大）
```

**设计权衡**：
```
容量更小（例如 4 KB）：
  - 命中率下降
  - 更多 TMU 访问
  - 性能下降

容量更大（例如 16 KB）：
  - 边际收益递减
  - 面积和功耗增加
  - 访问延迟增加

推荐：8 KB，平衡命中率和成本
```

### 2.2 Entry 结构

```verilog
struct l0_cache_entry_t {
    // 标签和有效位
    valid:          1 bit;          // Entry 有效
    tag:            16 bits;        // Tag（tile + offset 高位）
    
    // 数据
    data:           512 B;          // 缓存数据（FP4 的 B: 1024 B）
    
    // 替换策略
    lru:            2 bits;         // PLRU 位（4-way）
    priority:       2 bits;         // 优先级
                                    //   3 = 块内（高优先级）
                                    //   2 = 预取（中优先级）
                                    //   1 = 块外（低优先级）
                                    //   0 = 无效
    
    // 引用计数
    ref_count:      4 bits;         // 引用此 entry 的 uop 数量
    
    // 调试信息
    last_access:    16 bits;        // 最后访问时间戳
    access_count:   16 bits;        // 访问次数
};
```

**总大小**：
- 非 FP4：512 B (data) + ~8 B (metadata) ≈ 520 B
- FP4（B）：1024 B (data) + ~8 B (metadata) ≈ 1032 B

---

## 3. 地址映射

### 3.1 地址结构

**TileReg 地址**：
```
[63:52] - 保留（12 bits）
[51:46] - tile_addr（6 bits，64 个 tiles）
[45:0]  - offset（tile 内偏移）
```

**Cache 地址映射**：
```
对于 4-way 16-set cache：

[45:9]  - tag（37 bits）
[8:5]   - set index（4 bits，16 sets）
[4:0]   - block offset（5 bits，32 B 对齐）

注：512 B entry = 16 个 32 B blocks
```

### 3.2 Set 索引计算

**索引函数**：
```
set_index = (tile_addr[1:0] ^ offset[8:5]) & 0xF

使用 XOR 混合 tile_addr 和 offset
减少冲突
```

**示例**：
```
Tile 0, offset 0x000: set = (0x0 ^ 0x00) & 0xF = 0
Tile 0, offset 0x200: set = (0x0 ^ 0x10) & 0xF = 0 (冲突)
Tile 1, offset 0x000: set = (0x1 ^ 0x00) & 0xF = 1 (无冲突)
```

### 3.3 Tag 比较

**Tag 生成**：
```
tag = {tile_addr[5:0], offset[45:9]}

16 bits tag（压缩）：
  - 取 tile_addr 低 6 bits
  - 取 offset 高位
```

**Tag 匹配**：
```
for each way in set:
    if (entry[way].valid && entry[way].tag == req_tag):
        return HIT, way
return MISS
```

---

## 4. Cache 查找

### 4.1 查找流程

```
1. 接收请求（uop 的 A_addr 或 B_addr）

2. 计算 set index 和 tag
   set = (tile[1:0] ^ offset[8:5]) & 0xF
   tag = {tile[5:0], offset[45:9]}

3. 查询 cache set
   for way in 0..3:
       if (entry[way].valid && entry[way].tag == tag):
           return HIT, way
   
4. 命中（Hit）：
   - 读取 entry[way].data
   - 更新 LRU
   - 增加 ref_count
   - 更新 last_access
   - 返回数据
   
5. 未命中（Miss）：
   - 选择 victim（PLRU + 优先级）
   - 发起 tileload 请求
   - 分配 MSHR（Miss Status Holding Register）
   - 等待数据返回
```

### 4.2 MSHR（Miss Status Holding Register）

**用途**：跟踪进行中的 miss 请求

**MSHR Entry**：
```verilog
struct mshr_entry_t {
    valid:          1 bit;
    tile_addr:      6 bits;
    offset:         16 bits;
    way:            2 bits;         // 分配的 way
    set:            4 bits;         // 目标 set
    pending_uops:   32 bits;        // 等待此数据的 uop 位图
};
```

**MSHR 数量**：8-16 个（可配置）

**MSHR 合并**：
```
if (新 miss 请求的地址与现有 MSHR 匹配):
    合并到现有 MSHR
    添加到 pending_uops 位图
else:
    分配新 MSHR
```

---

## 5. 替换策略

### 5.1 PLRU（Pseudo-LRU）

**PLRU 树（4-way）**：
```
         [0]
        /   \
      [1]   [2]
      / \   / \
    W0 W1 W2 W3

3 个 LRU 位：
  bit[0]: 0=左子树最近，1=右子树最近
  bit[1]: 0=W0最近，1=W1最近（左子树内）
  bit[2]: 0=W2最近，1=W3最近（右子树内）
```

**更新 PLRU**：
```
访问 W0: lru = 3'b110  (左子树，W0)
访问 W1: lru = 3'b100  (左子树，W1)
访问 W2: lru = 3'b011  (右子树，W2)
访问 W3: lru = 3'b001  (右子树，W3)
```

**选择 victim**：
```
根据 lru 位选择最久未使用的 way：
  lru[0]==0 → 选择右子树（W2 或 W3）
    lru[2]==0 → 选择 W3
    lru[2]==1 → 选择 W2
  lru[0]==1 → 选择左子树（W0 或 W1）
    lru[1]==0 → 选择 W1
    lru[1]==1 → 选择 W0
```

### 5.2 优先级策略

**优先级定义**：
```
Priority 3 (最高): 块内数据
  - 当前正在计算的块需要的数据
  - 尽量保留

Priority 2 (中): 预取数据
  - 预取但还未使用的数据
  - 可以替换

Priority 1 (低): 块外数据
  - 其他块的数据
  - 优先替换

Priority 0: 无效（不应出现）
```

**优先级设置**：
```
FSM 拆分时标记：
  if (uop 属于当前块):
      priority = 3  // 块内
  else:
      priority = 1  // 块外

预取时：
      priority = 2  // 预取
      
首次使用后（块内）：
      priority = 3  // 提升为块内
```

### 5.3 替换算法

**完整替换算法**：
```
1. 检查无效 entry
   for way in 0..3:
       if (!entry[way].valid):
           return way  // 选择无效 entry
   
2. 检查 ref_count
   for way in 0..3:
       if (entry[way].ref_count > 0):
           exclude[way] = true  // 正在使用，不能替换
   
3. 按优先级选择
   min_priority = 3
   for way in 0..3:
       if (!exclude[way] && entry[way].priority < min_priority):
           min_priority = entry[way].priority
   
   candidates = ways with priority == min_priority
   
4. 在候选中使用 PLRU
   victim = PLRU_select(candidates)
   
5. 返回 victim way
```

**块内判断（FSM 拆分时）**：
```
当前块：正在计算的 (M_block, N_block)

对于 uop[i, j, k]：
  M_block = i / block_size_M
  N_block = j / block_size_N
  
  if (所有 K 迭代都在同一个 (M_block, N_block)):
      priority = 3  // 块内
  else:
      priority = 1  // 块外
```

---

## 6. 预取机制

### 6.1 预取触发

**触发时机**：FSM 在 Fractal 拆分时

**预取策略**：
```
1. FSM 拆分生成 uop
2. 对于每个 uop，计算 A 和 B 地址
3. 检查 L0 Cache
   - 如果已在 cache，skip
   - 如果不在，生成 prefetch 请求
4. Prefetch 请求入队 Prefetch Buffer
5. 按优先级发送到 TMU
```

### 6.2 Prefetch Buffer

**Buffer 结构**：
```verilog
struct prefetch_buffer_entry_t {
    valid:          1 bit;
    tile_addr:      6 bits;
    offset:         16 bits;
    priority:       2 bits;         // 块内=3, 块外=1
    issued:         1 bit;          // 已发送到 TMU
    target_cache:   1 bit;          // 0=L0A, 1=L0B
    way:            2 bits;         // 预分配的 way
    set:            4 bits;         // 目标 set
};
```

**Buffer 容量**：8-16 个 entry

**预取请求优先级**：
```
Priority 3: 块内数据预取
Priority 1: 块外数据预取
```

### 6.3 预取发射

**发射条件**：
```
1. Prefetch buffer 有未发射的请求
2. TMU 读口空闲
3. MSHR 有空闲 entry
```

**发射策略**：
```
1. 按优先级排序 prefetch buffer
2. 选择最高优先级的请求
3. 发送到 TMU
4. 分配 MSHR 跟踪
5. 标记 issued = 1
```

### 6.4 预取数据填充

**填充流程**：
```
1. TMU 返回数据
2. 查找对应 MSHR
3. 写入 L0 Cache（预分配的 way）
4. 设置 priority = 2（预取）
5. 释放 MSHR
6. Wakeup 等待此数据的 uop（更新 src_ready）
```

---

## 7. 引用计数

### 7.1 用途

**问题**：正在使用的数据被替换

**解决**：引用计数机制
```
- 每个 cache entry 有 ref_count
- uop 使用数据时 ref_count++
- uop 完成后 ref_count--
- ref_count > 0 的 entry 不能被替换
```

### 7.2 引用计数管理

**增加引用**：
```
当 uop 从 ISQ 发射时：
  L0A[way].ref_count++
  L0B[way].ref_count++
```

**减少引用**：
```
当 uop 完成（MAC 计算 + ACC 写入）时：
  L0A[way].ref_count--
  L0B[way].ref_count--
```

**溢出保护**：
```
if (ref_count == 15):  // 4-bit 最大值
    saturate  // 不再增加
```

### 7.3 替换保护

**替换检查**：
```
for each way in candidates:
    if (entry[way].ref_count > 0):
        skip  // 跳过正在使用的 entry
```

**死锁避免**：
```
如果所有 entry 都 ref_count > 0：
  - 应该不会发生（ISQ 深度 32 < cache ways 64）
  - 如果发生，选择 ref_count 最小的
```

---

## 8. Cache 一致性

### 8.1 一致性问题

**问题场景**：
```
1. CUBE 从 TileReg[5] 读取数据到 L0A
2. VEC 修改 TileReg[5]
3. CUBE 再次从 L0A 读取（脏数据）
```

**Janus 保证**：
- Block-ordered 执行
- 同一个 tile 的读写在不同 block
- Block 顺序保证一致性

### 8.2 Flush 处理

**Flush 触发**：
```
BCC 发出 flush 信号
  ↓
CUBE 接收 flush
  ↓
清空 L0A/L0B Cache（可选）
```

**Flush 策略**：
```
保守策略：清空整个 cache
  - 简单
  - 但下一个 block 需要重新加载

优化策略：按 STID-qualified BROB kill context 清空
  - 只清空 `entry.stid == flush_stid && kill_mask[entry.bid]` 的数据
  - 保留其他 block 的数据
  - 需要跟踪每个 entry 的 `(STID,BID)`；禁止用 BID 数值比较年龄
```

---

## 9. 性能分析

### 9.1 命中率

**理论分析**：
```
工作集大小（64×64 矩阵，FP16）：
  A: 64 × 64 × 2B = 8 KB
  B: 64 × 64 × 2B = 8 KB
  总计: 16 KB

L0A + L0B 容量: 8 KB + 8 KB = 16 KB

理论命中率:
  - 完美 cache：100%
  - 实际（考虑冲突）：~85-95%
```

**实测命中率**（估算）：
```
工作负载          L0A 命中率    L0B 命中率
==============================================
小矩阵（16×16）    ~98%          ~98%
中矩阵（64×64）    ~90%          ~90%
大矩阵（128×128）  ~75%          ~75%
极大（256×256）    ~60%          ~60%
```

### 9.2 带宽节省

**无 Cache（直接访问 TileReg）**：
```
每个 uop 需要：
  - A: 512 B
  - B: 512 B
  - 总计: 1024 B

64 个 uop（64×64, K=4）:
  总带宽: 64 × 1024 B = 64 KB
```

**有 Cache（90% 命中率）**：
```
Miss 请求: 64 × 10% × 1024 B = 6.4 KB
节省: 64 KB - 6.4 KB = 57.6 KB
带宽减少: 90%
```

### 9.3 延迟隐藏

**Cache Hit 延迟**：
```
L0 Cache 查询: 1 cycle
L0 Cache 读取: 1 cycle
总计: 2 cycles
```

**Cache Miss 延迟**：
```
L0 Cache 查询: 1 cycle（发现 miss）
TMU 请求: 1 cycle
TMU 仲裁: 1-5 cycles
TRegFile 读取: 2 cycles
Burst 传输: 2 cycles
填充 L0: 1 cycle
总计: 8-12 cycles
```

**预取隐藏**：
```
预取成功: miss 延迟 → hit 延迟
隐藏: 8-12 cycles → 2 cycles
节省: 6-10 cycles per uop
```

---

## 10. 实现优化

### 10.1 Bank 化

**问题**：单 port cache 访问冲突

**解决**：多 bank 组织
```
L0A Cache: 4 banks
  - Bank 0: set 0, 4, 8, 12
  - Bank 1: set 1, 5, 9, 13
  - Bank 2: set 2, 6, 10, 14
  - Bank 3: set 3, 7, 11, 15

支持并行访问不同 bank
```

### 10.2 流水线化

**查找流水线**：
```
Stage 0: 地址计算（set index, tag）
Stage 1: Tag 比较
Stage 2: Data 读取
Stage 3: MUX 选择（4-way）

总延迟: 4 cycles（流水线）
吞吐量: 1 查询/cycle
```

### 10.3 Critical Word First

**问题**：512 B entry 传输需要多个 cycle

**优化**：
```
TileReg 返回数据时：
  1. 先传输 critical word（uop 需要的部分）
  2. uop 可以提前开始计算
  3. 同时继续传输剩余数据
```

---

## 11. 调试和可观测性

### 11.1 性能计数器

```verilog
// L0A 计数器
reg [31:0] l0a_access_count;      // 总访问次数
reg [31:0] l0a_hit_count;         // 命中次数
reg [31:0] l0a_miss_count;        // Miss 次数
reg [31:0] l0a_eviction_count;    // 替换次数
reg [31:0] l0a_prefetch_count;    // 预取次数
reg [31:0] l0a_prefetch_hit;      // 预取命中

// L0B 计数器（同上）

// 计算
hit_rate = l0a_hit_count / l0a_access_count
prefetch_effectiveness = l0a_prefetch_hit / l0a_prefetch_count
```

### 11.2 调试信号

```verilog
// Cache 状态
wire [15:0] l0a_valid_bitmap;     // 16 个 entry 的 valid 位
wire [15:0] l0b_valid_bitmap;

// 当前访问
wire [3:0]  l0a_access_set;
wire        l0a_access_hit;
wire [1:0]  l0a_access_way;

// MSHR 状态
wire [7:0]  mshr_valid_bitmap;    // 8 个 MSHR 的 valid 位
wire [2:0]  mshr_occupancy;       // 当前使用的 MSHR 数
```

---

## 12. 关键参数总结

| 参数 | 值 | 说明 |
|------|-----|------|
| L0A 容量 | 8 KB | 推荐 |
| L0B 容量 | 8 KB | 非 FP4 |
| L0B 容量 (FP4) | 16 KB | FP4 |
| 组相联 | 4-way | PLRU |
| Sets | 16 | 每个 cache |
| Entry 大小 | 512 B | 非 FP4 |
| Entry 大小 (FP4 B) | 1024 B | FP4 |
| MSHR 数量 | 8-16 | 跟踪 miss |
| Prefetch Buffer | 8-16 | 预取请求队列 |
| 查找延迟 | 1-2 cycles | Hit |
| Miss 延迟 | 8-12 cycles | 无预取 |
| 预期命中率 | 85-95% | 中等工作负载 |
| 带宽节省 | ~90% | vs 无 cache |

---

**文档状态**：完成  
**最后更新**：2026-06-02
