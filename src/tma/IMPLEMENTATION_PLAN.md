# TMA/TAU 实施计划（TLOAD/TSTORE/TCVT/TCOPY）

## 1. 目标与边界

### 1.1 本期目标

- 将 `pyCircuit/designs/linxcore/src/tma/tma.py` 与 `pyCircuit/designs/linxcore/src/tau/tau.py` 从占位实现推进为可执行单元。
- TMA 覆盖 `TLOAD` 与 `TSTORE`，完成 GM 与 TR 间数据搬运。
- TAU 覆盖 `TCVT` 与 `TCOPY`，通过 TR 侧接口完成 tile 内/间数据处理。
- 指令完成后输出完成响应，驱动 BCC 路径上的提交推进（`non-flush ptr` / `ROB oldest ptr` 前移）。

### 1.2 本期明确不包含

- 不包含“块指令解码到 TMA/TAU”的实现（默认 `cmd_payload_*` 已携带执行参数）。
- 不包含 LSU 中 memory model 相关地址重叠检查。
- 不包含预测性 `TLOAD` 被 flush 时的取消/回滚流程。

## 2. BCC 接口与完成语义

### 2.1 命令入口（BCC -> PE）

- TMA 命令入口保持：
  - `cmd_valid_tma`
  - `cmd_tag_tma`
  - `cmd_payload_tma`
- TAU 命令入口保持：
  - `cmd_valid_tau`
  - `cmd_tag_tau`
  - `cmd_payload_tau`
- 两者均复用 BCtrl 的共享命令总线语义（同一 `tag/payload` 编码体系，按 `kind` 路由到不同 PE）。

### 2.2 完成出口（PE -> BCC）与状态码

现有代码路径（`src/bcc/bctrl/bctrl.py`、`src/bcc/bctrl/brob.py`、`src/top/top.py`）下，TMA 与 TAU 完成口行为一致：

- `rsp_valid_*`：完成有效。
- `rsp_tag_*`：完成标签，由 BCtrl 聚合后驱动 BROB 清理 pending。
- `rsp_status_*`：状态码，已在 BCtrl 聚合，供后续 BROB/软件侧扩展消费。
- `rsp_data0_*` / `rsp_data1_*`：完成附加信息，当前不影响提交推进主路径。

统一状态码（TMA/TAU 共用）：

- `4'h0` `OK`
- `4'h1` `DECODE_ERR`
- `4'h2` `PROTOCOL_ERR`
- `4'h3` `ACCESS_ERR`
- `4'h4` `TIMEOUT`
- `4'h5` `UNSUPPORTED`
- `4'hF` `INTERNAL_ERR`

### 2.3 接口行为约束

- 每条已发射命令仅允许上报一次完成。
- `rsp_valid_*` 拉高周期内，`rsp_tag_* / rsp_status_* / rsp_data*` 必须稳定可采样。
- `rsp_tag_*` 必须与 issue 阶段的 `issue_tag_brob` 一一对应，避免错误清理 pending。

## 3. 功能范围定义

### 3.1 TMA（TLOAD/TSTORE）

- 支持操作：`TLOAD`、`TSTORE`。
- 访问范围：GM + TR。
- GM 侧事务风格：ARM CHI non-coherent。
  - 读：`ReadOnce`
  - 写：`WriteUnique`，并遵循 `WriteReq -> CompDBIDResp -> WriteData -> Comp`
- TR 侧事务风格：CHI over Ring/TMU。
  - 读：`ReadNoSnp`
  - 写：`WriteNoSnp`
- 事务 `Size` 由 TMA 内部 uop 拆分策略决定（不由 BCC 显式传入）。

### 3.2 TAU（TCVT/TCOPY）

- 支持操作：`TCVT`、`TCOPY`。
- TAU 无 GM 侧接口，仅实现 TR 侧接口。
- TAU 的 TR 侧通道行为、握手和完成语义与 TMA TR 侧保持一致。
- `TCVT` 在本期支持以下五种规格：
  - `NORM`
  - `ND2NZ`
  - `ND2ZN`
  - `DN2NZ`
  - `DN2ZN`

## 4. 微架构实施要点

### 4.1 TMA 侧

- `cmd_frontend_tma`：命令接收、解包、合法性检查。
- `tma_desc_engine`：维度/步长遍历，生成分片访问序列。
- `gm_chi_master`：GM 侧 Req/Rsp/Dat 三通道主机。
- `tr_chi_master`：TR 侧 Req/Rsp/Dat 三通道主机。
- `tma_completion_unit`：命令级完成收敛与状态上报。

### 4.2 TAU 侧

- `cmd_frontend_tau`：命令接收与参数检查。
- `tcvt_engine`：布局变换执行（覆盖五种 layout 规格）。
- `tcopy_engine`：TR 内搬运直通路径。
- `tr_chi_master_tau`：TR 侧 Req/Rsp/Dat 三通道主机。
- `tau_completion_unit`：完成收敛与状态上报。

## 5. 分阶段实施

### Phase 0：规格冻结

- 冻结命令 payload 字段和版本。
- 冻结 CHI 接口信号与时序约束。
- 冻结统一状态码及完成语义。

交付物：

- `tma/PAYLOAD_SPEC.md`
- `tma/CHI_IF_SPEC.md`

### Phase 1：TMA/TAU 骨架替换

- 将 `tma.py`、`tau.py` 从“输入即返回”的 stub 改为结构化状态机骨架。
- 打通命令队列、事务上下文、完成回传三条基本路径。

验收：

- 可稳定接收命令并按 `tag` 返回完成。
- 非法参数可返回 `DECODE_ERR`/`UNSUPPORTED`。

### Phase 2：TMA 数据搬运闭环

- 落地 GM 侧 `ReadOnce/WriteUnique`。
- 落地 TR 侧 `ReadNoSnp/WriteNoSnp`。
- 建立 `TLOAD/TSTORE` 端到端搬运闭环。

验收：

- 读写握手顺序正确。
- 分片搬运与完成统计一致。

### Phase 3：TAU 功能闭环

- 落地 `TCOPY` TR->TR 数据搬运。
- 落地 `TCVT` 五种 layout 转换（`NORM/ND2NZ/ND2ZN/DN2NZ/DN2ZN`）。

验收：

- `TCOPY` 结果与参考模型一致。
- `TCVT` 五种规格在 directed case 下逐项通过。

### Phase 4：错误处理与可观测性

- 增加超时、协议违例、访问异常处理路径。
- 增加事务计数、错误计数、周期统计等调试计数器。

验收：

- 异常路径可收敛，不出现挂死或重复完成。

### Phase 5：集成回归

- 在 `top` 路径替换 TMA/TAU stub 行为。
- 建立 TMA+TAU 联合回归：基本流、背压、错误注入、并发 tag。

验收：

- 无 deadlock。
- 无 tag 错配。
- 无完成丢失。

## 6. 验证策略

### 6.1 参考模型

- 构建软件参考模型，覆盖：
  - TMA 的地址遍历与搬运
  - TAU 的 `TCOPY`
  - TAU `TCVT` 五种布局变换

### 6.2 协议断言

- Req/Rsp/Dat 配对完整性。
- 写事务顺序约束（尤其 `WriteUnique` + `DBID` 路径）。
- `TxnID` 生命周期唯一性与回收正确性。

### 6.3 提交推进一致性

- 校验 `rsp_tag_*` 与 BROB pending 位清理的一致性。
- 校验背压条件下无丢包、无重复完成。

## 7. 下一步计划（本期后）

- 块指令完整解码链路落地（`BSTART/B.ARG/B.DIM/B.IOR/B.IOT` -> `cmd_payload_*`）。
- LSU memory model 地址重叠检查落地。
- 预测执行 flush 下的事务取消/回滚。
- TMA/TAU 跨单元协同优化（队列共享、统一调度、限流策略）。
