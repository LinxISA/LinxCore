# CUBE Tile 尺寸选择指南

**版本**：Janus CUBE v2.0  
**日期**：2026-06-03

本文档聚焦 **M×N×K 与 TileReg 容量/带宽** 的关系，指导编译器选择最优 tile 尺寸。

---

## 核心约束

### 1. TileReg 容量约束

**数据占用**（单个 tmatmul）：
```
A: M × K × elem_size_in   (输入精度：FP4/FP8/FP16)
B: K × N × elem_size_in
C: M × N × elem_size_out  (输出精度：FP32)

总占用 = (M×K + K×N) × elem_size_in + M×N × elem_size_out
```

**容量限制**（TileReg = 1 MB）：
```
(M×K + K×N) × elem_size_in + M×N × elem_size_out ≤ 1 MB
```

**K 上限公式**：
```
K_max = [1 MB - M×N×elem_size_out] / [(M+N)×elem_size_in]
```

**示例**：

| 输入精度 | M | N | K_max | 说明 |
|---------|---|---|-------|------|
| FP16 (2B) | 128 | 128 | 1920 | C 占 64 KB |
| FP8 (1B) | 128 | 128 | 3904 | C 占 64 KB，K 翻倍 |
| FP4 (0.5B) | 128 | 128 | 7872 | C 占 64 KB，K 再翻倍 |
| FP16 (2B) | 256 | 256 | 832 | C 占 256 KB |
| FP8 (1B) | 256 | 256 | 1728 | C 占 256 KB |

**关键观察**：
- 输出 C 始终是 FP32（4B），占用 = M×N×4
- 输入精度降低可以显著增大 K_max
- FP4 的 K_max ≈ FP16 的 4 倍

**超限惩罚**：

当 `K > K_max` 时，需要 TMA 分块搬运：

```
K_chunks = ceil(K / K_max)

每次分块的 TMA 搬运周期数（TMA_BW = α × 256 B/cycle）：
  Data_per_chunk = (M+N)×K_max×elem_size_in
  T_TMA_per_chunk = Data_per_chunk / (α × 256)

总额外周期：
  Extra_cycles = (K_chunks - 1) × T_TMA_per_chunk

对比计算周期（8192 ops/cycle 峰值）：
  T_compute = (M×N×K×2) / 8192
```

**示例（M=128, N=128, K=4096, FP16 输入, α=0.3）**：
```
K_max = 1920  (FP16 输入，FP32 输出)
K_chunks = ceil(4096/1920) = 3

单次 TMA 周期：
  Data = 256×1920×2B = 983 KB  (只搬运输入 A,B)
  T_TMA = 983 KB / (0.3 × 256 B/cycle) = 13,107 cycles
  
  换算时间（1.5 GHz）：
  T_TMA = 13,107 / 1.5e9 ≈ 8.7 μs

额外延迟：
  2 × 13,107 cycles = 26,214 cycles ≈ 17.5 μs

对比计算周期：
  T_compute = (128×128×4096×2) / 8192 = 16,384 cycles ≈ 10.9 μs

结论：TMA 延迟 (17.5 μs) > 计算时间 (10.9 μs)
      效率降至 10.9/(10.9+17.5) = 38%
      
避免 K 超限是性能优化的关键！
```

---

## 2. 访存带宽与计算平衡

### 硬件峰值参数

**MAC 阵列**（来自 architecture.md）：
```
FP16 配置：
  MAC 单元数 = 4096 (16×16 脉动阵列 × 16 banks)
  频率 = 1.5 GHz（假设）
  每 MAC 每周期 = 2 FLOPs（1个乘法 + 1个加法）
  
峰值算力 = 4096 MACs × 2 ops/cycle
         = 8192 ops/cycle
         = 8192 × 1.5 GHz = 12.288 TFLOPS

FP8 配置（双倍算力）：
  峰值算力 = 16384 ops/cycle = 24.576 TFLOPS
  
FP4 配置（8倍算力）：
  峰值算力 = 65536 ops/cycle = 98.304 TFLOPS
```

**TMA 带宽**（从系统架构推导）：
```
TMU Ring flit = 256 B
理论峰值 = 256 B/cycle（每 cycle 1 flit）

实际可用带宽取决于：
  - 总线竞争（多个 CUBE、DMA、其他单元共享）
  - 仲裁延迟
  - DDR 带宽限制
  
设实际可用 = α × 256 B/cycle（α < 1，待实测确定）
```

### Roofline 临界点

> **Roofline Model**：性能分析模型，判断瓶颈是计算还是访存。临界点 = 计算峰值 / 访存带宽，AI 低于临界点时访存受限，高于临界点时计算受限。

**临界 AI 计算**：
```
AI_balance = Peak_Compute / TMA_BW
           = 8192 ops/cycle / (α × 256 B/cycle)
           = 32 / α  ops/byte (FP16)

示例：
  α = 1.0（理论峰值）: AI_balance = 32 ops/byte
  α = 0.5（50% 利用）: AI_balance = 64 ops/byte
  α = 0.3（30% 利用）: AI_balance = 107 ops/byte
```

**计算访存比（Arithmetic Intensity, AI）**：
```
AI = 计算量 / 访存量
   = (2×M×N×K) / [(M×K + K×N) × elem_size_in + M×N × elem_size_out]

简化（K 较大，忽略 M×N 输出项）：
AI ≈ (2×M×N×K) / [(M+N)×K × elem_size_in]
   = (2×M×N) / [(M+N) × elem_size_in]

含义：每字节数据能执行多少次运算

示例（FP16 输入）：
  M=128, N=128: AI ≈ (2×128×128) / (256×2) = 64 ops/byte
  M=256, N=256: AI ≈ (2×256×256) / (512×2) = 128 ops/byte
```

**性能瓶颈判断**：
```
AI < 32/α: 访存受限（Memory Bound，带宽不足）
  → 增大 M, N 提高 AI
  → 或优化 L0 Cache 减少 TMA 访问

AI > 32/α: 计算受限（Compute Bound，MAC 算力不足）
  → 充分利用 MAC 阵列
```

### 无气泡（Bubble-Free）所需带宽推导

> **气泡（Bubble）**：MAC 阵列因数据未就绪而空闲的周期，导致性能损失。无气泡 = MAC 持续满载运行。

**目标**：MAC 阵列满载运行，无空闲周期

对于矩阵乘法 `C[M×N] = A[M×K] × B[K×N]`：

**每个 MAC 操作的数据需求**（FP16）：
```
1 次 MAC = 1 个 A 元素 + 1 个 B 元素
         = 2 B + 2 B = 4 B（输入）

4096 MACs/cycle 理论需求：
  = 4096 × 4 B = 16384 B/cycle
```

**考虑数据复用**（Systolic Array 特性）：
```
16×16 脉动阵列中：
  - A 的每行被复用 16 次（N 方向广播）
  - B 的每列被复用 16 次（M 方向广播）

实际带宽需求 = 16384 / 16 = 1024 B/cycle

分解：
  A 侧：512 B/cycle
  B 侧：512 B/cycle
```

**加上 C 的读写**（ACC RMW）：
```
C 的带宽需求 ≈ 256 B/cycle（写回和累加）

总内部带宽需求 = 512 (A) + 512 (B) + 256 (C)
               = 1280 B/cycle
```

**对比实际硬件**（从 datapath.md）：
```
L0A → MAC：512 B/cycle ✓（满足 A 需求）
L0B → MAC：512 B/cycle ✓（满足 B 需求）
ACC RMW：  256 B/cycle ✓（满足 C 需求）

结论：内部数据通路可以支撑 MAC 满载
```

**TMA 加载瓶颈分析**：
```
TMA 带宽：α × 256 B/cycle（α 为实际利用率）

对于 M×N×K 矩阵乘法：
  总输入数据 = (M+N)×K×elem_size_in
  总输出数据 = M×N×elem_size_out
  总数据量 = (M+N)×K×elem_size_in + M×N×elem_size_out
  
计算周期数（峰值）：
  T_compute = (M×N×K) / 4096

TMA 加载周期数：
  T_TMA = [(M+N)×K×elem_size_in + M×N×elem_size_out] / (α × 256)
  
无气泡条件：T_TMA ≤ T_compute
  
  [(M+N)×K×elem_size_in + M×N×elem_size_out] / (α×256) ≤ (M×N×K) / 4096
  
  简化（忽略 M×N 输出项，假设 K >> M, N）：
  (M+N)×K×elem_size_in / (α×256) ≤ (M×N×K) / 4096
  
  (M+N) / (M×N) ≤ (α × 256) / (elem_size_in × 4096)
  
  1/M + 1/N ≤ α / (16 × elem_size_in)
  
  当 M=N 时：
  2/M ≤ α / (16 × elem_size_in)
  M ≥ 32 × elem_size_in / α
  
结论（参数化）：
  FP16 (2B):
    α = 1.0: M ≥ 64（理论峰值，不现实）
    α = 0.5: M ≥ 128（50% TMA 利用率）
    α = 0.3: M ≥ 213（30% TMA 利用率）
  
  FP8 (1B):
    α = 0.5: M ≥ 64
    α = 0.3: M ≥ 107
    
  FP4 (0.5B):
    α = 0.5: M ≥ 32
    α = 0.3: M ≥ 53
  
实际场景分析（FP16）：
  M=N=128, α=0.5: 刚好满足无气泡条件
  M=N=128, α=0.3: MAC 利用率约 60%
  M=N=256, α=0.3: MAC 利用率约 95%
```

### 典型配置分析

**计算 vs 搬运周期数对比**

| 输入 | M | N | K | 计算周期 | 搬运周期(α=0.3) | 搬运周期(α=0.5) | 瓶颈(α=0.3) | 瓶颈(α=0.5) |
|------|---|---|---|---------|----------------|----------------|-----------|-----------|
| FP16 | 64 | 64 | 256 | 256 | 2,219 | 1,331 | 访存受限 | 访存受限 |
| FP16 | 128 | 128 | 256 | 1,024 | 4,437 | 2,662 | 访存受限 | 访存受限 |
| FP16 | 128 | 128 | 1024 | 4,096 | 17,749 | 10,650 | 访存受限 | 访存受限 |
| FP16 | 256 | 256 | 256 | 4,096 | 8,875 | 5,325 | 访存受限 | 访存受限 |
| FP16 | 512 | 512 | 256 | 16,384 | 17,749 | 10,650 | 计算受限 | 计算受限 |
| FP8 | 128 | 128 | 256 | 1,024 | 2,219 | 1,331 | 访存受限 | 访存受限 |
| FP8 | 256 | 256 | 256 | 4,096 | 4,437 | 2,662 | 访存受限 | 访存受限 |
| FP4 | 128 | 128 | 256 | 1,024 | 1,109 | 666 | 访存受限 | 计算受限 |

**周期数计算公式**：
```
计算周期 = (M × N × K) / 4096

搬运周期 = [(M+N)×K×elem_size_in + M×N×elem_size_out] / (α × 256)

示例（M=128, N=128, K=256, FP16 输入, FP32 输出）：
  计算周期 = (128×128×256) / 4096 = 1,024 cycles
  
  搬运周期(α=0.3) = [(256×256×2) + (128×128×4)] / (0.3×256)
                   = [131,072 + 65,536] / 76.8
                   = 196,608 / 76.8
                   = 2,560 cycles（近似）
                   
  比值 = 2,560 / 1,024 = 2.5×（访存是瓶颈）
```

**关键观察**：
- **FP16 输入，M=N=128, K=256**：搬运周期是计算周期的 **2.6-4.3 倍**（取决于 α）
- **FP16 输入，M=N=256, K=256**：搬运周期是计算周期的 **1.3-2.2 倍**
- **FP8 输入**：搬运减半，更容易达到计算受限
- **FP4 输入**：搬运再减半，小矩阵也能计算受限
- 增大 M,N 比增大 K 更能改善计算/搬运比

---

## 3. 优化策略

### 策略 1：平衡 M, N, K

**目标**：最大化 `AI × min(K, K_max)`

```python
# 伪代码
def optimal_tile(TileReg_MB=1, elem_size=2, TMA_BW_GB=50):
    best_score = 0
    best_config = None
    
    for M in [64, 128, 256, 512]:
        for N in [64, 128, 256, 512]:
            # 计算 K 上限
            K_max = (TileReg_MB*1024*1024 - M*N*elem_size) / ((M+N)*elem_size)
            
            # 计算 AI
            AI = (2*M*N) / (elem_size * (M+N))
            
            # 评分：AI × 有效K（不超过上限）
            score = AI * K_max
            
            if score > best_score:
                best_score = score
                best_config = (M, N, int(K_max))
    
    return best_config

# 结果（FP16, 1MB TileReg）：
# M=128, N=128, K_max=1984
# AI=64, score=126976
```

### 策略 2：分阶段优化

**阶段 1：选择 M, N（最大化 AI）**
```
目标：AI 尽量接近 246（临界点）

推荐：M ≈ N ≈ 128-256
  - M=N=128 → AI=64
  - M=N=256 → AI=128
  - M=N=512 → AI=256（超过临界点，但 K_max 太小）
```

**阶段 2：选择 K（填满 TileReg）**
```
K = min(K_request, K_max)

其中 K_request 是用户/模型要求的 K 维度
     K_max 由 M, N 决定
     
如果 K_request > K_max：
  需要编译器分块或告警
```

### 策略 3：特殊场景适配

| 场景 | M | N | K | 理由 |
|------|---|---|---|------|
| **Transformer Q×K^T** | 128 | 512 | 128 | N 大（序列长），K 小（head_dim） |
| **Transformer QK×V** | 128 | 128 | 512 | K 大（序列长） |
| **CNN GEMM** | 256 | 256 | 64 | 输出特征图大，K 小（kernel） |
| **全连接层** | 128 | 128 | 1024 | 平衡配置 |

---

## 4. 推荐配置

### 通用推荐（FP16, 1MB TileReg）

| 配置 | M | N | K | AI | K_max | 适用场景 |
|------|---|---|---|----|-------|---------|
| **平衡** | 128 | 128 | 256-1024 | 64 | 1984 | 大多数情况 |
| **高 AI** | 256 | 256 | 256-896 | 128 | 896 | 计算密集型 |
| **大 K** | 128 | 128 | 1024-1984 | 64 | 1984 | K 维度大的模型 |
| **非对称** | 256 | 128 | 512-1109 | 85 | 1109 | 特殊形状 |

### 避免的配置

| 配置 | M | N | K | 问题 |
|------|---|---|---|------|
| 小矩阵 | 64 | 64 | 128 | AI=32 太低，访存严重瓶颈 |
| K 超限 | 128 | 128 | 4096 | K > K_max 3倍，TMA 分块开销大 |
| 极大 MN | 512 | 512 | 256 | K_max=384 太小，灵活性差 |

---

## 5. 编译器实现建议

### 静态分析

```python
def choose_tile_size(total_M, total_N, total_K):
    """
    根据原始矩阵形状选择 tile 尺寸
    """
    # 候选配置
    candidates = [
        (128, 128, 1024),  # 平衡
        (256, 128, 512),   # 非对称
        (256, 256, 256),   # 高 AI
    ]
    
    best = None
    best_score = 0
    
    for (M, N, K) in candidates:
        if M > total_M or N > total_N or K > total_K:
            continue
        
        # 检查 K 是否超限
        K_max = (1024*1024 - M*N*2) / ((M+N)*2)
        if K > K_max:
            K = int(K_max)
        
        # 计算 AI
        AI = (2*M*N) / (2*(M+N))
        
        # 评分：AI × K（偏好高 AI 和大 K）
        score = AI * K
        
        if score > best_score:
            best_score = score
            best = (M, N, K)
    
    return best
```

### 运行时调优

利用性能计数器（PMU）收集：
- TMA 带宽利用率
- MAC 阵列利用率
- L0 Cache 命中率

根据瓶颈调整 tile 尺寸。

---

## 6. 总结

### 核心公式

**K 上限**：
```
K_max = [1 MB - M×N×elem_size_out] / [(M+N)×elem_size_in]

elem_size_in: FP4=0.5B, FP8=1B, FP16=2B
elem_size_out: FP32=4B
```

**计算访存比**：
```
AI ≈ (2×M×N) / [(M+N) × elem_size_in]

输入精度越低，AI 越高
```

**Roofline 临界点**：
```
AI_balance = 8192 ops/cycle / (α × 256 B/cycle) 
           = 32 / (α × elem_size_in)  ops/byte

FP16: AI_balance = 16/α
FP8:  AI_balance = 32/α
FP4:  AI_balance = 64/α
```

**无气泡带宽条件**：
```
1/M + 1/N ≤ α / (16 × elem_size_in)

当 M=N 时：M ≥ 32 × elem_size_in / α

FP16, α=0.5: M ≥ 128
FP8,  α=0.5: M ≥ 64
FP4,  α=0.5: M ≥ 32
```

### 关键洞察

**1. TMA 带宽决定性能边界**
```
α（TMA 实际利用率）决定：
  - 临界 AI = 32/(α × elem_size_in)
  - 无气泡最小尺寸 M ≥ 32×elem_size_in/α
  
实测确定 α 后可精确预测性能
```

**2. 输入精度的影响**
```
FP4 vs FP16:
  - K_max 增大 4 倍
  - 搬运周期减少 4 倍
  - 临界 AI 增大 4 倍
  - 更容易达到计算受限
```

**3. 权衡三角**

```
    大 M,N（高 AI，接近满载）
       /    \
      /      \
     /        \
大 K（避免分块） ─ TileReg 容量（1 MB）

无法同时最大化三者，需要权衡：
- 优先保证 K ≤ K_max（避免 TMA 分块惩罚）
- 推荐 M,N ≥ 128（FP16）或 ≥ 64（FP8）
- 低精度输入可以用更大的 K 或更小的 M,N
```

### 推荐起点

**FP16 输入**：
```
M = N = 128
K = min(K_request, 1920)
AI = 64 ops/byte

在 α=0.5 时刚好平衡
在 α=0.3 时略微访存受限（~60% 利用率）
```

**FP8 输入**：
```
M = N = 128
K = min(K_request, 3904)
AI = 128 ops/byte

大多数 α 都能达到计算受限
```

---

**参考资料**：
- `architecture.md`：CUBE 架构定义
- `TMU_SPEC_EN.md`：TMU Ring 协议和带宽
- Roofline Model: Williams et al., "Roofline: An Insightful Visual Performance Model", 2009
