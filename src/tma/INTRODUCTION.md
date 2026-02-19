# TMA/TAU INTRODUCTION（TLOAD/TSTORE/TCVT/TCOPY）

本文总结两个层面的现状：

1. `linx-isa` 中 tile memory 指令（重点 `TLOAD`/`TSTORE`）的指令级定义状态。
2. `pyCircuit/designs/linxcore/src` 中 TMA/TAU 对应实现状态。

结论基于当前工作区源码（检查时间：2026-02-17）。

## 1. ISA 定义状态（Linx v0.3 strict）

### 1.1 指令形态与编码

- TMA 入口是 `BSTART.TMA TileOp, DataType`，其中 `TileOp`/`Function` 字段选择具体 tile memory 操作，`DataType` 指定元素类型。
  - 见：`linx-isa/isa/v0.3/opcodes/lx_32.opc:80`
  - 见：`linx-isa/isa/v0.3/linxisa-v0.3.json:8941`
- 手册给出的 canonical 形式中，tile memory block 明确区分：
  - `BSTART.TMA TLOAD, <DataType>`
  - `BSTART.TMA TSTORE, <DataType>`
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:120`

### 1.2 TLOAD/TSTORE 行为定义（描述符驱动）

- `TLOAD`/`TSTORE` 是 template-style tile memory 操作，语义由 block header 描述符联合决定：
  - `LB0/LB1/LB2`（来自 `B.DIM`）
  - 布局与 pad（来自 `B.ARG`）
  - GPR 绑定（来自 `B.IOR`）
  - tile IO 与传输大小（来自 `B.IOT/B.IOTI`）
  - 元素类型（来自 `BSTART.TMA`）
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:45`
- 关键布局与维度约束：
  - 线性化形式：`ND/DN/NZ/ZN`
  - `NZ/ZN` 在 strict profile 中为 TR 侧布局；GM 侧仅允许 `ND/DN`
  - `LB0/LB1/LB2` 分别对应 GM-inner / GM-outer / TR-inner
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:56`
- Padding：
  - `TLOAD` 支持 `Null/Zero/Max/Min`
  - `TSTORE` 不生成 padding，仅按布局把 TR 写回 GM
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:85`
- 顺序与原子性：
  - 单条 `TLOAD/TSTORE` 可拆成多个 internal beats
  - 对外观察上非原子（non-atomic）
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:105`

### 1.3 Memory model 与 ordering

- v0.3 strict 的架构内存模型是 TSO。
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:10`
  - 见：`linx-isa/isa/v0.3/state/memory_model.json:3`
- BCC（scalar）与 MTC（tile memory: `TLOAD/TSTORE/TPREFETCH`）在单 LXCPU 上共享同一 TSO ordering domain。
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:32`
  - 见：`linx-isa/isa/v0.3/state/memory_model.json:17`
- `TSTORE` beats 也受 store->store 保序约束；`aq/rl/fence` 可进一步加强排序。
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/08_memory_operations.adoc:20`
  - 见：`linx-isa/isa/v0.3/state/memory_model.json:40`

### 1.4 异常、重启与地址翻译

- `TLOAD/TSTORE` 页面故障要求 precise 且可由软件重启（restartable）。
  - 见：`linx-isa/isa/v0.3/state/memory_model.json:43`
- `BSTATE.TMA` 要包含可重启所需状态（例如 `FaultRestartInfo`）。
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/09_system_and_privilege.adoc:297`
- IOMMU 使能时，DMA-style 引擎（含 TMA）必须走 IOMMU 翻译。
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/09_system_and_privilege.adoc:443`

### 1.5 相关架构上下文（tile 寄存器）

- tile 寄存器为 `4 hands x 8 depth = 32`，单 tile 架构尺寸 `512B..4KB`，总架构容量 `128KB`。
  - 见：`linx-isa/docs/architecture/isa-manual/src/chapters/02_programming_model.adoc:153`
  - 见：`linx-isa/isa/v0.3/registers/tile_reg.json:3`

## 2. pyCircuit 当前实现状态（`designs/linxcore/src`）

### 2.1 指令级支持状态

- 当前 `pyCircuit` 内部 op 枚举与解码中，没有 `TLOAD`/`TSTORE`/`TPREFETCH` 或 `BSTART.TMA` 专用内部 op。
  - `isa.py` 主要是 `BSTART.STD` 系列与标量 load/store：
    - 见：`pyCircuit/designs/linxcore/src/common/isa.py:10`
    - 见：`pyCircuit/designs/linxcore/src/common/isa.py:95`
  - `decode.py` 可见 `BSTART.STD` 解码路径，但无 `BSTART.TMA`、`B.ARG/B.IOR/B.IOT/B.DIM` 解码消费路径：
    - 见：`pyCircuit/designs/linxcore/src/common/decode.py:757`

### 2.2 Block command 到 TMA/TAU 的接线状态

- Backend 只在特定 block start 内部 op retire 时导出 block command（当前条件是 `OP_C_BSTART_STD / OP_C_BSTART_COND / OP_BSTART_STD_CALL`）。
  - 见：`pyCircuit/designs/linxcore/src/bcc/backend/backend.py:2216`
- `block_cmd_kind` 路由策略：
  - `kind==0 -> TMA`
  - `kind==1 -> CUBE`
  - `kind==2 -> VEC`
  - 其余 -> TAU
  - 见：`pyCircuit/designs/linxcore/src/bcc/bctrl/bctrl.py:46`
- 顶层存在“BISQ -> TMU NoC -> TileReg -> BCtrl -> TMA/TAU”的通道，但当前仍是简化/占位通路，不是 ISA 描述符语义执行管线。
  - 见：`pyCircuit/designs/linxcore/src/top/top.py:428`
  - 见：`pyCircuit/designs/linxcore/src/top/top.py:444`
  - 见：`pyCircuit/designs/linxcore/src/top/top.py:500`

### 2.3 TMA 模块本体行为（当前）

- `tma/tma.py` 是功能占位实现，行为仅为：
  - `rsp_valid = cmd_valid`
  - `rsp_tag = cmd_tag`
  - `rsp_status = 0`
  - `rsp_data0 = cmd_payload + 1`
  - `rsp_data1 = cmd_payload`
  - 见：`pyCircuit/designs/linxcore/src/tma/tma.py:7`
- 现阶段未实现：
  - `TLOAD/TSTORE` 的真实 memory transaction
  - `B.ARG/B.IOR/B.IOT/B.DIM` 描述符语义消费
  - 布局转换、padding、多 beat 传输
  - MMU/IOMMU 访问路径与可重启 fault 状态机
  - 与 TSO/fence/aq/rl 相关的 TMA 专用排序逻辑

### 2.4 TAU 模块本体行为（当前）

- `tau/tau.py` 也是功能占位实现，行为仅为：
  - `rsp_valid = cmd_valid`
  - `rsp_tag = cmd_tag`
  - `rsp_status = 0`
  - `rsp_data0 = cmd_payload + cmd_payload`
  - `rsp_data1 = cmd_payload`
  - 见：`pyCircuit/designs/linxcore/src/tau/tau.py:7`
- TAU 当前尚未实现：
  - `TCVT` 的布局变换执行
  - `TCOPY` 的真实 TR 侧搬运通路
  - 与 TMA 对齐的事务上下文管理与错误处理

## 3. 对齐结论（ISA vs pyCircuit）

- **ISA 侧**：`TLOAD/TSTORE` 语义、memory model、fault/restart、IOMMU 责任边界在 v0.3 strict 中已给出可执行约束。
- **pyCircuit 侧**：目前 TMA 与 TAU 都处于 bring-up stub 状态，接口已接线但语义尚未落地。
- **直接含义**：当前路径主要用于 block-command 接口联调，不具备 `TLOAD/TSTORE/TCVT/TCOPY` 的行为级验证能力。

## 4. 当前实施范围更新

- 实施计划范围覆盖 TMA 与 TAU 两个单元。
- TMA 本期目标：支持 `TLOAD/TSTORE`，完成 GM<->TR 数据搬运闭环。
- TAU 本期目标：支持 `TCVT/TCOPY`，且 `TCVT` 支持以下五种规格：
  - `NORM`
  - `ND2NZ`
  - `ND2ZN`
  - `DN2NZ`
  - `DN2ZN`
