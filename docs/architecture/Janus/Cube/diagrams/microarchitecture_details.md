# CUBE 微架构详细图表

**版本**：Janus CUBE v2.0  
**日期**：2026-06-10

本文档补充 CUBE 各关键模块的详细微架构图。

---

## 1. ISQ (Issue Queue) 微架构

### 1.1 ISQ 整体结构

```
┌────────────────────────────────────────────────────────────────────┐
│                     ISQ (32 entries)                               │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  Entry Array (32 × 96 bits)                                  │ │
│  │                                                               │ │
│  │  Entry[0]:  [valid|uop_id|A_addr|B_addr|C_slice|deps|ready] │ │
│  │  Entry[1]:  [valid|uop_id|A_addr|B_addr|C_slice|deps|ready] │ │
│  │  ...                                                          │ │
│  │  Entry[31]: [valid|uop_id|A_addr|B_addr|C_slice|deps|ready] │ │
│  └────────────┬─────────────────────────────────────────────────┘ │
│               ↓                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  Ready Logic (并行检查所有 entries)                          │ │
│  │                                                               │ │
│  │  for each entry i:                                           │ │
│  │    src_ready[i] = L0A_hit(A_addr[i]) && L0B_hit(B_addr[i])  │ │
│  │    deps_ready[i] = !deps_valid[i] || completed(deps_uop_id) │ │
│  │    can_issue[i] = valid[i] && src_ready[i] && deps_ready[i] │ │
│  └────────────┬─────────────────────────────────────────────────┘ │
│               ↓                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  Selection Logic (优先级仲裁)                                │ │
│  │                                                               │ │
│  │  优先级：                                                     │ │
│  │    1. K_idx == 0 (新输出位置，priority=3)                    │ │
│  │    2. K_idx > 0  (累加，priority=2)                          │ │
│  │    3. 同优先级按 age (年龄) 选择                             │ │
│  │                                                               │ │
│  │  输出：selected_uop (每拍最多 1 个)                          │ │
│  └────────────┬─────────────────────────────────────────────────┘ │
│               ↓                                                    │
│           发射到 MAC 阵列                                          │
└────────────────────────────────────────────────────────────────────┘
```

### 1.2 ISQ Entry 详细结构

```
ISQ Entry (96 bits):

┌─────────┬──────────┬──────────┬──────────┬───────────┬──────────┐
│ valid   │ uop_id   │ tileop_id│ acc_chain│ K/M/N_idx │ priority │
│ 1 bit   │ 8 bits   │ 8 bits   │ 2 bits   │ 24 bits   │ 2 bits   │
└─────────┴──────────┴──────────┴──────────┴───────────┴──────────┘

┌──────────┬──────────┬───────────┬──────────┬───────────┬─────────┐
│ A_tile   │ A_offset │ B_tile    │ B_offset │ C_slice_id│ age     │
│ 6 bits   │ 16 bits  │ 6 bits    │ 16 bits  │ 7 bits    │ 6 bits  │
└──────────┴──────────┴───────────┴──────────┴───────────┴─────────┘

┌───────────┬─────────────┬────────────┬──────────┐
│ deps_valid│ deps_uop_id │ src_ready  │ issued   │
│ 1 bit     │ 8 bits      │ 2 bits     │ 1 bit    │
└───────────┴─────────────┴────────────┴──────────┘
```

---

## 2. L0 Cache 微架构

### 2.1 L0A/L0B Cache 组织（4-way 组相联）

```
┌────────────────────────────────────────────────────────────────────┐
│                   L0A Cache (64 KB, 4-way)                         │
│                                                                    │
│  Cache 组织：128 sets × 4 ways × 128 B/line = 64 KB              │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  地址映射（uop 的 A_addr）                                    │ │
│  │                                                               │ │
│  │  A_addr = {tile_id[6], offset[16]}                           │ │
│  │           ↓                                                   │ │
│  │  {tag[15], set_idx[7]}                                       │ │
│  │    ↑         ↑                                                │ │
│  │    │         └─ 索引 128 个 set                              │ │
│  │    └─────────── 标签匹配                                     │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  Set 结构（每个 set 有 4 个 way）                            │ │
│  │                                                               │ │
│  │  Set[i]:                                                      │ │
│  │    Way 0: [V|tag|data(512B)|priority|LRU]                   │ │
│  │    Way 1: [V|tag|data(512B)|priority|LRU]                   │ │
│  │    Way 2: [V|tag|data(512B)|priority|LRU]                   │ │
│  │    Way 3: [V|tag|data(512B)|priority|LRU]                   │ │
│  │                                                               │ │
│  │  V:        valid bit (1 bit)                                 │ │
│  │  tag:      地址标签 (15 bits)                                │ │
│  │  data:     缓存数据 (512 B)                                  │ │
│  │  priority: 优先级 (2 bits: 3=块内, 0=块外)                  │ │
│  │  LRU:      PLRU 位 (2 bits)                                  │ │
│  └────────────┬─────────────────────────────────────────────────┘ │
│               ↓                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  查找逻辑（并行查找 4 个 way）                               │ │
│  │                                                               │ │
│  │  for way in 0..3:                                            │ │
│  │    hit[way] = valid[way] && (tag[way] == req_tag)           │ │
│  │                                                               │ │
│  │  if any(hit):                                                 │ │
│  │    data_out = data[hit_way]                                  │ │
│  │    update_LRU(hit_way)                                       │ │
│  │  else:                                                        │ │
│  │    victim = select_victim(priority, LRU)                     │ │
│  │    allocate(victim_way, req_addr)                            │ │
│  └──────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘

L0B Cache 结构相同，独立管理
```

### 2.2 PLRU 替换策略

```
替换算法（优先级优先 + PLRU）：

Step 1: 查找无效 entry
  if exists way with valid=0:
    return way
    
Step 2: 查找低优先级 entry  
  if exists way with priority=0 (块外数据):
    return LRU(ways with priority=0)
    
Step 3: 所有都是块内数据
  return LRU(all ways)

PLRU 更新（伪 LRU，2 bits per way）：
  on hit(way_i):
    LRU[way_i] = 0           // 最近使用
    LRU[other_ways] += 1     // 其他 way 老化
```

---

## 3. ACC Pool 微架构

### 3.1 ACC Pool 组织（128 KB，8 banks）

```
┌────────────────────────────────────────────────────────────────────┐
│                   ACC Pool (128 KB)                                │
│                                                                    │
│  组织：8 banks × 16 KB/bank                                        │
│  Slice：128 slices × 1 KB (非 FP4)                                │
│         64 slices × 2 KB (FP4)                                     │
│                                                                    │
│  ┌──────────┬──────────┬──────────┬──────────┬──────────┐        │
│  │ Bank 0   │ Bank 1   │ Bank 2   │ Bank 3   │ Bank 4   │  ...   │
│  │ 16 KB    │ 16 KB    │ 16 KB    │ 16 KB    │ 16 KB    │        │
│  │          │          │          │          │          │        │
│  │ Slice 0  │ Slice 1  │ Slice 2  │ Slice 3  │ Slice 4  │        │
│  │ Slice 8  │ Slice 9  │ Slice 10 │ Slice 11 │ Slice 12 │        │
│  │ Slice 16 │ Slice 17 │ ...      │ ...      │ ...      │        │
│  └──────────┴──────────┴──────────┴──────────┴──────────┘        │
│                                                                    │
│  Slice 映射到 Bank：slice_id % 8 → bank_id                        │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 Slice 分配和释放

```
┌────────────────────────────────────────────────────────────────────┐
│                   Slice 分配器                                     │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  Free Bitmap (128 bits，每 bit 代表一个 slice)               │ │
│  │                                                               │ │
│  │  [127][126]...[2][1][0]                                      │ │
│  │    0    0      1  1  0     (1=空闲, 0=占用)                 │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  分配算法（uop 粒度）                                         │ │
│  │                                                               │ │
│  │  on uop_alloc:                                               │ │
│  │    slice_id = find_first_set(free_bitmap)  // 优先级编码器  │ │
│  │    free_bitmap[slice_id] = 0                // 标记占用      │ │
│  │    uop_to_slice[uop_id] = slice_id          // 记录映射      │ │
│  │    return slice_id                                           │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  释放算法（acccvtuop 完成后）                                │ │
│  │                                                               │ │
│  │  on acccvt_done(uop_id):                                     │ │
│  │    slice_id = uop_to_slice[uop_id]                          │ │
│  │    free_bitmap[slice_id] = 1                // 标记空闲      │ │
│  │    clear uop_to_slice[uop_id]                               │ │
│  └──────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

---

## 4. FixPipe 流水线微架构

### 4.1 FixPipe 11 级流水线详细结构

```
┌────────────────────────────────────────────────────────────────────┐
│                    FixPipe (11 stages)                             │
│                                                                    │
│  Stage 0: ACC 读请求生成                                           │
│    ├─ 计算 ACC slice 地址                                          │
│    └─ 生成 256B 读请求 (1/4 slice)                                │
│           ↓                                                        │
│  Stage 1-3: ACC 读取 (3 cycles, 256B/cy)                          │
│    ├─ Cycle 1: 读 256B (offset 0)                                 │
│    ├─ Cycle 2: 读 256B (offset 256)                               │
│    ├─ Cycle 3: 读 256B (offset 512)                               │
│    └─ Cycle 4: 读 256B (offset 768)  → 完整 1KB slice            │
│           ↓                                                        │
│  Stage 4: 数据缓冲和对齐                                           │
│    └─ 1KB buffer (16×16×FP32, nz格式)                            │
│           ↓                                                        │
│  Stage 5-6: nz → nd 格式转换 (2 cycles)                           │
│    ├─ nz (Natural Z-curve): MAC 阵列自然输出                      │
│    │    数据排列：按 Z-curve 空间填充曲线                         │
│    │    [0,0][0,1][1,0][1,1][0,2][0,3]...                        │
│    │                                                               │
│    └─ nd (Natural Data): 行主序                                   │
│         数据排列：标准二维数组                                    │
│         [0,0][0,1][0,2]...[0,15][1,0][1,1]...                    │
│           ↓                                                        │
│  Stage 7: 量化 (1 cycle)                                          │
│    ├─ FP32 → FP16: IEEE 754 round-to-nearest                     │
│    ├─ FP32 → FP8:  E4M3/E5M2 格式                                │
│    └─ FP32 → INT8: 饱和量化 [-128, 127]                          │
│           ↓                                                        │
│  Stage 8: rowmax 计算 (可选, 1 cycle)                            │
│    └─ 每行找最大值：16 个 FP32 值 (16 行)                         │
│           ↓                                                        │
│  Stage 9: 输出缓冲                                                 │
│    └─ 256B buffer (写回粒度)                                      │
│           ↓                                                        │
│  Stage 10: TileStore 请求生成                                     │
│    ├─ 计算 TileReg 地址                                           │
│    ├─ 生成 TMU 写请求 (256B flit)                                │
│    └─ 分配 tag 用于响应匹配                                       │
│           ↓                                                        │
│        发送到 TMU node3                                            │
└────────────────────────────────────────────────────────────────────┘

总延迟：~11 cycles per slice
吞吐量：4 cycles per slice (流水线满载，256B/cy)
```

---

## 5. MAC 阵列微架构

### 5.1 16×16 脉动阵列结构

```
┌────────────────────────────────────────────────────────────────────┐
│                 16×16 Systolic Array                               │
│                                                                    │
│        A 输入 (从左到右) →                                         │
│                                                                    │
│      PE[0,0]  →  PE[0,1]  →  ...  →  PE[0,15]                    │
│        ↓          ↓                    ↓                           │
│      PE[1,0]  →  PE[1,1]  →  ...  →  PE[1,15]                    │
│        ↓          ↓                    ↓                           │
│        ...        ...                  ...                         │
│        ↓          ↓                    ↓                           │
│      PE[15,0] →  PE[15,1] →  ...  →  PE[15,15]                   │
│                                                                    │
│        ↓ B 输入 (从上到下)                                         │
│                                                                    │
│  每个 PE (Processing Element):                                     │
│    ┌──────────────────────────────────────┐                       │
│    │  PE[i,j]                             │                       │
│    │                                       │                       │
│    │  输入：A_in (16 elements)            │                       │
│    │        B_in (16 elements)            │                       │
│    │        C_in (partial sum, FP32)      │                       │
│    │                                       │                       │
│    │  计算：                               │                       │
│    │    for k in 0..15:                   │                       │
│    │      C_out += A_in[k] × B_in[k]     │                       │
│    │                                       │                       │
│    │  延迟：1 cycle (流水线 MAC)          │                       │
│    │  算力：16 MACs (FP16)                │                       │
│    │        32 MACs (FP8)                 │                       │
│    │        128 MACs (FP4)                │                       │
│    └──────────────────────────────────────┘                       │
│                                                                    │
│  脉动数据流：                                                      │
│    - A 数据从左向右传播 (每拍移动一列)                            │
│    - B 数据从上向下传播 (每拍移动一行)                            │
│    - 部分和沿对角线累加                                            │
│    - 约 7 拍延迟从输入到输出稳定                                  │
│                                                                    │
│  总算力：256 PEs × 16 MACs/PE = 4096 MACs (FP16)                 │
└────────────────────────────────────────────────────────────────────┘
```

### 5.2 数据输入格式

```
A 矩阵输入 (512 B/cycle):
  16 行 × 32 B/行 = 512 B
  每行：16 个 FP16 元素 (或 32 个 FP8，64 个 FP4)

B 矩阵输入 (512 B/cycle，FP4: 1024 B/cycle):
  32 B × 16 列 = 512 B (FP16/FP8)
  32 B × 32 列 = 1024 B (FP4)
  每列：16 个 FP16 元素

C 矩阵输出 (1 KB/cycle):
  16×16 个 FP32 元素 = 1 KB (nz 格式)
```

---

## 6. FSM 和 Fractal 拆分

### 6.1 FSM 状态机

```
┌────────────────────────────────────────────────────────────────────┐
│                     FSM 状态机                                     │
│                                                                    │
│         IDLE                                                       │
│           ↓ (收到 tile 命令)                                       │
│         DECODE                                                     │
│           ↓                                                        │
│       CALC_TILES  (计算 M_tiles, N_tiles, K_tiles)                │
│           ↓                                                        │
│       GEN_UOPS   (循环生成 uop)                                    │
│           │                                                        │
│           ├─→ 分配 ACC slice                                       │
│           ├─→ 计算 A/B 地址                                        │
│           ├─→ 建立 K 方向依赖                                      │
│           └─→ uop 入队 ISQ                                         │
│           ↓                                                        │
│       PREFETCH  (生成预取请求)                                     │
│           ↓                                                        │
│         WAIT  (等待 uop 完成)                                      │
│           ↓                                                        │
│         DONE  (向 BROB 报告完成)                                   │
│           ↓                                                        │
│         IDLE                                                       │
└────────────────────────────────────────────────────────────────────┘
```

### 6.2 Fractal 拆分示例（64×64 矩阵，K=128，FP16）

```
输入：tmatmul z0, tA, tB, shape=64×64

Step 1: 解析参数
  M = 64, N = 64
  从 TileReg 元数据读取 K = 128
  元素大小 = 2B (FP16)

Step 2: 计算分形数量
  M_tiles = 64 / 16 = 4
  N_tiles = 64 / 16 = 4
  K_tiles = 128 / (32B / 2B) = 128 / 16 = 8

Step 3: 生成 uop (4×4×8 = 128 个)
  
  uop 分布：
    M=0 N=0: [K0, K1, K2, K3, K4, K5, K6, K7]  (8 uops)
    M=0 N=1: [K0, K1, K2, K3, K4, K5, K6, K7]  (8 uops)
    ...
    M=3 N=3: [K0, K1, K2, K3, K4, K5, K6, K7]  (8 uops)

  依赖关系（每个输出位置）：
    uop[M,N,0]: 无依赖
    uop[M,N,1]: deps = uop[M,N,0]
    uop[M,N,2]: deps = uop[M,N,1]
    ...
    uop[M,N,7]: deps = uop[M,N,6]

  ACC 分配：
    每个输出位置 (M,N) 分配一个 slice
    slice_list[M*4 + N] = alloc_slice()
    所有 K 方向 uop 共享同一个 slice
```

---

## 7. ACC 链和依赖管理

### 7.1 ACC 映射表结构

```
┌────────────────────────────────────────────────────────────────────┐
│                   ACC 映射表 (16 entries)                          │
│                                                                    │
│  Entry 组织：4 chains × 4 arch_acc = 16 entries                   │
│                                                                    │
│  Entry[(chain, arch_acc)]:                                         │
│    ┌──────────────────────────────────────────────────────────┐   │
│    │  valid:        1 bit                                     │   │
│    │  acc_chain:    2 bits  (0-3)                            │   │
│    │  arch_acc:     2 bits  (z0-z3)                          │   │
│    │  num_outputs:  12 bits (M_tiles × N_tiles)             │   │
│    │  slice_list:   [256][7:0]  (每个输出位置的 slice_id)   │   │
│    │  last_uop_id:  [256][7:0]  (每个输出位置的最后 uop)    │   │
│    └──────────────────────────────────────────────────────────┘   │
│                                                                    │
│  示例：chain=0, arch_acc=z0, 64×64 矩阵                            │
│    num_outputs = 4×4 = 16                                         │
│    slice_list[0..15] = [slice_id for each M,N]                   │
│    last_uop_id[0..15] = [uop_id of K=7 for each M,N]             │
└────────────────────────────────────────────────────────────────────┘
```

### 7.2 细粒度依赖示例

```
tmatmul z0, tA0, tB0, 64×64, chain=0      // 第一个 tmatmul
  → 生成 128 uop (4×4×8)
  → 分配 16 个 ACC slices (每个 M,N 一个)
  → 记录到 ACC 映射表：
      mapping[chain=0][z0].slice_list = [s0, s1, ..., s15]
      mapping[chain=0][z0].last_uop_id = [uop7, uop15, ..., uop127]

tmatmul.acc z0, tA1, tB1, 64×64, chain=0  // 第二个 tmatmul.acc
  → 查询 ACC 映射表：
      slice_list = mapping[chain=0][z0].slice_list
      last_uop_list = mapping[chain=0][z0].last_uop_id
  → 生成 128 uop，复用相同的 slices：
      for M,N in 0..3, 0..3:
        slice_id = slice_list[M*4+N]
        for K in 0..7:
          uop = create_uop(M, N, K)
          uop.slice_id = slice_id
          if K == 0:
            uop.deps = last_uop_list[M*4+N]  // 细粒度依赖
          else:
            uop.deps = prev_K_uop

细粒度唤醒：
  当 uop[M=0,N=0,K=7] (第一个 tmatmul) 完成
    → 唤醒 uop[M=0,N=0,K=0] (第二个 tmatmul.acc)
  
  不同输出位置独立并行：
    uop[M=1,N=1,K=7] 完成 → 唤醒 uop[M=1,N=1,K=0]
```

---

## 8. 时序图

### 8.1 单个 uop 执行时序

```
Cycle:  0    5    10   15   20   25   30
        |----|----|----|----|----|----|
        
L0 Miss |████| TileLoad (5 cy)
        
L0 Hit       |█| Lookup (1 cy)
        
MAC               |███████| Compute (7 cy)
        
BufferC                   |█| Accum (1 cy)
        
ACC RMW                    |█████████| RMW (9 cy)
(chunk end)
        
Total:  约 15 cy (L0 hit, no RMW)
        约 24 cy (L0 hit, with RMW)
```

### 8.2 流水线并行示例（4 个 uop）

```
Cycle:  0    5    10   15   20   25   30   35
        |----|----|----|----|----|----|----|
        
uop0    |L0  |MAC      |Buf|ACC          |
        
uop1         |L0  |MAC      |Buf|ACC          |
        
uop2              |L0  |MAC      |Buf|ACC          |
        
uop3                   |L0  |MAC      |Buf|ACC          |

并发度：MAC 阵列每拍处理 1 个 uop
吞吐量：约 15-20 cycles for 4 uops (流水线并行)
```

---

**文档状态**：完成  
**最后更新**：2026-06-10
