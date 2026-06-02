# Tile Register / Unified Buffer Appendix

> **Document ID**: JCORE-BCC-AS-APP-UB
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft
> **Parent**: [JCore_BCC_AS.md](JCore_BCC_AS.md)
> **Topic**: Tile Register, Unified Buffer, SRAM bank organization, PE access, rename implications

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Captured Tile Register / Unified Buffer notes, port table, bank conflict and open discussion items |

---

## 1. Unified Buffer Overview

Unified Buffer 是 AI Core 中连接各处理单元的大型缓存结构，可被 TMA、Vector Core、Cube Core 访问。它在 JCore 软件/架构视角中表现为 Tile Register。

原始材料中存在两个容量口径:

- Tile Register 总大小 1MB，最多容纳 8192 个 1024-bit 大小的 TileRegister。
- Unified Buffer 容量 256KB，由 Tile Reg 地址索引。

这两个口径需要在后续资源规格中统一。当前文档保留两者:

- BCC TileRename 章节以 1MB 总空间、T/U/M/N = 512KB/256KB/128KB/128KB 作为讨论基线。
- Unified Buffer 章节记录 256KB SRAM bank 组织和 PE 访问端口。

---

## 2. Why TileReg Needs Rename

若 TileReg 总大小为 1MB，则最多可容纳:

```text
1MB / 1024bit = 8192 TileRegister
```

硬件难以跟踪 8192 个 TileReg 的分配状态和 ready 状态。但程序执行时不可能同时使用 8192 个寄存器。实际最大使用寄存器数量与最大投机深度有关，并与 ROB/BROB 深度相关。

因此:

- ROB/BROB 投机深度限制了 TileReg 最大在飞使用量。
- TileRegister 映射关系表项数量只需与 ROB/BROB 深度维持一定比例。
- BCC TileRename 使用映射表 entry index 作为 Tile tag。
- ReadyTable 与映射表一一对应。

---

## 3. Address and Bank Mapping

原始示例:

- 地址 `[3:0]` 指示 SRAM bank。
- 地址 `[11:4]` 指示 bank 内行号。

BCC TileRename 地址格式:

```text
addr[20:19] = Reg Type
  T: 00
  U: 01
  M: 10
  N: 11

addr[18:0] = allocate pointer / offset
```

Unified Buffer bank 组织:

- Tile Reg 内部由 16 个 `256 x 512` 单口 SRAM 组成。
- 地址 `[6:5]` 交织 bank 结构。
- 多端口同时访问可能触发 bank conflict。
- Tile Reg 给各访问源提供 cancel/retry 信号。

DOT source: [diagrams/tile_register_unified_buffer.dot](diagrams/tile_register_unified_buffer.dot)

---

## 4. PE Access Ports

| Source | Read Ports | Read Width | Write Ports | Write Width | Direction |
|--------|------------|------------|-------------|-------------|-----------|
| Vector Tile Access | 1 | 2048-bit | 1 | 2048-bit | Tile Reg <-> Vector register |
| Cube Tile Access | 1 | 2048-bit | 1 | 2048-bit | Tile Reg <-> Cube |
| TMA Tile Access | 1 | 2048-bit | 1 | 2048-bit | Tile Reg <-> TMA |

由于 Tile Reg 承载大量读写口，硬件需要在端口层面复用。

Bank conflict:

- 多端口同时访问概率性触发 bank conflict。
- Tile Reg 向访问源提供 cancel/retry 机制。
- PE 侧需要在请求 buffer / LDQ / STQ / TBuffer 中回退并重新发送。

---

## 5. Rename / Memory Semantics

BCC 对 Tile 做 rename:

- 根据 dst size 连续分配 TileReg 空间。
- 映射表记录 offset 和 size。
- src Tile rename 时从映射表获取起始地址。
- 分配/释放时维护 TileReg size credit。
- 所需 size 大于剩余 size 时，Tile rename stall。

软件仍需要内存语义:

- 寄存器不足时做 spill。
- 需要额外 TileReg 空间时显式指示。
- TMUL 示例: 需要 7KB TileRegister，其中 4KB 可能用于最终输出，3KB 可能用于 TileOP 执行时 spill 和读回。

块内限制:

- 因为 TileReg rename 在 BCC 完成，块内不能再对新的 TileReg 做 BCC 级分配。
- 块内指令只能访问块头中已分配/传入的 TileReg 空间，作为 scratchpad 使用。

---

## 6. Conflict Avoidance and Open Design Notes

原始材料保留若干讨论点:

1. 需要设计 UB rename 避免 conflict 的机制。
2. Cube 需要详细讨论。
3. 当前 block 方案中，block 结束后内部缓存消失；但矩阵乘法存在大量数据复用。
4. 大矩阵通常拆成多个小矩阵，若 buffer 信息只在 block 内体现，小矩阵 TileOp 结束后 buffer 消失，之后复用需重新搬运。
5. 可讨论是否将 L0A/L0B/L0C 做成 cache，对软件不可见。
6. 该方案可能冲击 Cube 流水，需要继续评估。
7. 是否需要 Scalar 访问 Tile Reg: 当前暂时不用。
8. TileReg / TBuffer 可考虑 2-way。
9. 识别是否为该 block 第一次写需要额外硬件代价。
10. 预取量如何设置仍 TBD。

---

## 7. Parallel vs Vector Block Write Ordering

对于 TileReg 写:

| Block Mode | Ordering Rule |
|------------|---------------|
| Parallel | 不同 group 间可以乱序写。Parallel 确保 group 间无重叠，每个 group 写不同地址。 |
| Vector | 不同 group 间要保序写，写出的数据必须正确。 |

这与 [07_Vector_Core_Architecture.md](07_Vector_Core_Architecture.md) 中 Vector Tile Access ordering 章节一致。

---

## 8. TBD / Modeling Items

| ID | Item |
|----|------|
| UB-1 | 统一 1MB TileReg 与 256KB Unified Buffer 两套容量描述。 |
| UB-2 | 明确 bank index、row index 与 TileRename address format 的映射关系。 |
| UB-3 | 端口复用仲裁策略需补图和时序。 |
| UB-4 | bank conflict cancel/retry 协议需定义周期。 |
| UB-5 | Cube L0A/L0B/L0C cache 化可行性需模型评估。 |
| UB-6 | BCC 触发 Vector TBuffer prefetch 的预取量和优先级待定。 |
