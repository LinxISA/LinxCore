# CUBE 矩阵加速器文档（Janus 版本）

本目录包含 CUBE（矩阵乘法加速器）的详细实现文档。

## 概述

CUBE 是 Janus 的矩阵乘法加速引擎，专为高吞吐量 GEMM 和卷积操作设计。

**关键特性**：
- 4096 个 MAC 单元（16×16 脉动阵列）
- Fractal 拆分（固定 16×16 小分形）
- uop 级 ISQ 调度和乱序执行
- L0A/L0B Cache 支持数据复用
- 支持 2-4 个并发 ACC 链
- 多格式支持（FP16/BF16/FP8/FP4）

## 文档结构

### 规格书总览
- **[CUBE_SPEC.md](CUBE_SPEC.md)** - CUBE 微架构规格书（推荐入口）⭐
  - 单文件完整规格，类似 TMU/VEC spec 风格
  - 在 Janus 中的定位和系统对齐
  - ISA 接口定义和协议说明
  - 顶层架构、数据流、访问时序
  - 性能模型和配置参数
  - 适合快速了解 CUBE 全貌

### 架构合约
- **[architecture.md](architecture.md)** - CUBE 架构合约（主文档）
  - ISA 接口（tmatmul, tmatmul.acc, acccvt）
  - 顶层架构和内部模块
  - Fractal 拆分和 uop 生成
  - ISQ 调度和 L0A/L0B Cache
  - ACC 管理和 FixPipe
  - Block Fabric 接口
  - 错误处理和与其他模块交互

### 架构图
- **[diagrams/cube_architecture.md](diagrams/cube_architecture.md)** - CUBE 架构图（Janus v2.0）✅
  - CUBE 顶层模块框图
  - 数据流图（tmatmul/acccvt）
  - 带宽和延迟总结
  - 性能特征
- `diagrams/cube_pipeline_v1_archived.md` - 旧版归档（v1 superscalar）

### 实现细节
以下文档基于新的 Janus 架构：
- **[datapath.md](datapath.md)** - 数据通路详细设计 ✅
  - 输入路径（TReg Burst, L0A/L0B Cache）
  - MAC 阵列（16×16 脉动阵列）
  - BufferC（K-Accumulator）
  - 物理累加器池
  - 输出路径（FixPipe）
  - 数据通路时序和优化

- **[isq.md](isq.md)** - ISQ 详细设计 ✅
  - ISQ 组织和 Entry 结构
  - uop 入队和依赖管理
  - src ready 检测
  - uop 选择和发射
  - 乱序执行示例
  - 性能分析和实现优化

- **[l0cache.md](l0cache.md)** - L0A/L0B Cache 详细设计 ✅
  - Cache 组织（4-way 组相联）
  - 地址映射和查找
  - PLRU 替换策略
  - 优先级策略（块内 vs 块外）
  - 预取机制
  - 引用计数和一致性
  - 性能分析

- **[accumulator.md](accumulator.md)** - 累加器管理详细实现 ✅
  - 128 KB ACC Pool 组织
  - Slice 分配和释放算法
  - Bank 组织和冲突避免
  - ACC 链管理
  - RMW 优化
  - 性能分析

- **[acccvt.md](acccvt.md)** - acccvt 和 FixPipe 详细设计 ✅
  - acccvtuop 拆分和调度
  - FixPipe 流水线（11 级）
  - 格式转换（nz→nd）
  - 量化（FP32→FP16/FP8/INT8）
  - rowmax 实现
  - TileStore 接口
  - 性能分析

- `memory_interface.md` - 内存接口详细实现 ⏳

### 归档（旧版本）
- **[diagrams/cube_pipeline_v1_archived.md](diagrams/cube_pipeline_v1_archived.md)** - 旧版 CUBE 文档归档（superscalar 风格）
  - 使用 CUBE.OPA + CUBE.DRAIN 指令模型
  - 64 KB ACC, 2 KB slice
  - 保留作为参考

---

## 阅读指南

### 架构师
1. 从 [architecture.md](architecture.md) 开始 - 完整架构
2. 阅读 [diagrams/cube_architecture.md](diagrams/cube_architecture.md) - 可视化架构

### RTL 工程师
1. 阅读 [architecture.md](architecture.md) - 架构合约
2. 阅读 [diagrams/cube_architecture.md](diagrams/cube_architecture.md) - 模块和流水线
3. 等待实现细节文档完成

### 验证工程师
1. 阅读 [architecture.md](architecture.md) §2 - ISA 接口
2. 阅读 [architecture.md](architecture.md) §14-§15 - 接口和错误处理

### 编译器开发者
1. 阅读 [architecture.md](architecture.md) §2 - ISA 接口
2. 阅读 [architecture.md](architecture.md) §4 - Fractal 拆分规则
3. 阅读 [architecture.md](architecture.md) §13 - 执行模型和延迟

---

## CUBE 指令

### tmatmul（矩阵乘法）
```
tmatmul zd, tA, tB, shape, acc_chain
```
- 执行矩阵乘法：`zd = A × B`
- Fractal 拆分为多个 16×16 uop
- 支持 FP16/BF16/FP8/FP4

### tmatmul.acc（矩阵乘法累加）
```
tmatmul.acc zd, tA, tB, shape, acc_chain
```
- 执行累加矩阵乘法：`zd = zd + A × B`
- C（zd 当前值）隐式来自前一个 tmatmul/tmatmul.acc
- 支持 ACC 链

### acccvt（累加器转换和搬运）
```
acccvt zd, tile_dst, mode, acc_chain
acccvt.rowmax zd, tile_dst, tile_rowmax, mode, acc_chain
```
- 搬运累加器到 TileReg：`tile_dst = convert(zd, mode)`
- 支持格式转换（nz→nd）、量化、rowmax
- 通过 FixPipe 和 TileStore 完成

---

## 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| MAC 单元（FP16） | 4096 | 16×16 脉动阵列 |
| MAC 单元（FP8） | 8192 | 2× 算力 |
| MAC 单元（FP4） | 32768 | 8× 算力 |
| 脉动阵列延迟 | ~7 cycles | 流水线 |
| Fractal 大小 | 16×16 | 固定小分形 |
| ISQ 深度 | 32 uop | 可配置 |
| L0A 读带宽 | 512 B/cycle | |
| L0B 读带宽 | 512 B/cycle | FP4: 1024 B/cy |
| ACC 容量 | 128 KB | 128 slices (FP4: 64 slices) |
| ACC Slice | 1 KB | FP4: 2 KB |
| 并发 ACC 链 | 2-4 | 可配置 |

---

## 性能

### 峰值吞吐量（@ 1.5 GHz）
- **FP16**: 12.3 TFLOPS
- **FP8**: 24.6 TOPS
- **FP4**: 98.3 TOPS

### 典型工作负载
- GEMM（通用矩阵乘法）
- 卷积（通过 Fractal 拆分）
- 注意力机制（Q×K^T, scores×V）
- Transformer 层

---

## 文档状态

| 文档 | 状态 | 行数 | 最后更新 |
|------|------|------|---------|
| architecture.md | ✅ 完成 | 1152 | 2026-06-02 |
| diagrams/cube_architecture.md | ✅ 完成 | 265 | 2026-06-02 |
| datapath.md | ✅ 完成 | 769 | 2026-06-02 |
| isq.md | ✅ 完成 | 633 | 2026-06-02 |
| l0cache.md | ✅ 完成 | 685 | 2026-06-02 |
| accumulator.md | ✅ 完成 | 633 | 2026-06-02 |
| acccvt.md | ✅ 完成 | 658 | 2026-06-02 |
| memory_interface.md | ⏳ 待完成 | - | - |

---

## 参考资料

### LinxCore 相关文档
- `../../microarchitecture.md` - LinxCore 微架构
- `../architecture.md` - Janus 整体架构
- `../../block_fabric_contract.md` - Block Fabric 协议
- `../../stages/BROB.md` - BROB 阶段规范

### DavinciOO 参考（归档）
- `diagrams/cube_pipeline_v1_archived.md` - 旧版 CUBE 实现参考

---

**文档版本**：Janus CUBE v2.0  
**最后更新**：2026-06-02
