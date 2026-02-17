# TMA 实施计划（TLOAD/TSTORE 真功能化）

## 1. 目标与边界

### 1.1 本期目标

- 将 `pyCircuit/designs/linxcore/src/tma/tma.py` 从占位模块改造成可执行的 TMA 单元。
- 支持 `TLOAD` 与 `TSTORE` 在 GM（Global Memory）与 TR（Tile Register/TMU 侧）之间搬移数据。
- 支持按指令参数执行数据排布转换（至少覆盖 `ND/DN/NZ/ZN` 相关路径与 `B.ARG` 选择）。
- 指令完成后产生完成响应，推动 BCC 侧提交推进（`non-flush ptr` / `ROB oldest ptr` 前移）。

### 1.2 本期明确不包含

- 不包含“块指令解码到 TMA”的实现（默认 `cmd_payload_tma` 已给出 TMA 需要的关键参数）。
- 不包含 LSU 中 memory model 要求的地址重叠范围检查实现。
- 不包含预测性 `TLOAD` 被 flush 时的回滚/取消处理流程。

## 2. 外部接口与协议约束

### 2.1 BCC -> TMA 命令接口

- 继续沿用现有入口：
  - `cmd_valid_tma`
  - `cmd_tag_tma`
  - `cmd_payload_tma`
- 在 `tma` 目录新增“payload 解包规范”，定义字段含义与版本：
  - 操作类型：`TLOAD`/`TSTORE`
  - 数据类型与元素字节数
  - 维度参数（等价 `LB0/LB1/LB2` 语义）
  - GM/TR 基地址、步长、tile 尺寸
  - layout/pad 配置（等价 `B.ARG`）
  - 注：`W5/W7`（超时、拆分上限、软件 cookie）延后到下一步计划
  - 注：CHI 事务 `Size` 由 TMA 内部 uop 拆分策略生成，不由 BCC 显式传入

### 2.2 TMA -> BCC 完成接口

- 现有代码中的端口作用（`src/bcc/bctrl/bctrl.py` + `src/bcc/bctrl/brob.py` + `src/top/top.py`）：
  - `rsp_valid_tma`：TMA 响应有效位；被 `BCtrl` 作为 PE 响应仲裁输入，并参与 `rsp_valid_brob` 聚合。
  - `rsp_tag_tma`：事务标签；`BCtrl` 将其转发为 `rsp_tag_brob`，`BROB` 用标签低 4 位清除 pending bit。
  - `rsp_status_tma`：状态码；当前在 `BCtrl` 内已聚合到 `pe_to_brob_stage_rsp_status_bctrl`，但 `BROB` 模块尚未消费该字段。
  - `rsp_data0_tma` / `rsp_data1_tma`：返回数据；当前在 `BCtrl` 内已聚合到 `pe_to_brob_stage_rsp_data0/1_bctrl`，尚未被 `BROB` 使用。
- 现有提交推进机制：
  - issue 侧：`issue_fire_brob + issue_tag_brob` 置位 pending。
  - complete 侧：`rsp_valid_brob + rsp_tag_brob` 清位 pending。
  - 因此 TMA 必须保证 `rsp_tag_tma` 与已发射命令一一对应，且完成只上报一次。
- 状态码规范（`rsp_status_tma[3:0]`）：
  - `4'h0` `OK`：命令完成，数据与副作用均生效。
  - `4'h1` `DECODE_ERR`：payload/descriptor 非法（字段越界、保留值、不支持组合）。
  - `4'h2` `PROTOCOL_ERR`：CHI 握手或顺序违规（例如 `WriteData` 先于有效 `DBID`）。
  - `4'h3` `ACCESS_ERR`：地址访问失败（译码/权限/目标返回 SLVERR 等）。
  - `4'h4` `TIMEOUT`：事务在超时窗口内未完成。
  - `4'h5` `UNSUPPORTED`：操作类型或参数在当前实现中未支持。
  - `4'hF` `INTERNAL_ERR`：内部一致性错误（状态机非法迁移、计数器失配等）。
- 集成要求：
  - 当前版本至少保证 `rsp_valid_tma/rsp_tag_tma/rsp_status_tma` 在完成周期稳定采样。
  - 为后续 `BROB` 扩展，`rsp_data0/1_tma` 保持语义化编码（见 `PAYLOAD_SPEC.md` 的 completion payload）。

### 2.3 GM 侧事务映射（ARM CHI, Non-coherent）

- 协议角色：TMA 作为 Requesting Node（RN），GM 侧内存系统作为 Home/Slave 侧。
- 读事务：
  - Req 通道发送 `ReadOnce`。
  - Dat 通道接收 `CompData`（可多拍，按 `TxnID` 归并）。
- 写事务：
  - Req 通道发送 `WriteUnique`。
  - Rsp 通道接收 `CompDBIDResp`（获取 `DBID`/写入信用）。
  - Dat 通道发送 `NonCopyBackWrData`（绑定 `TxnID` 与 `DBID`）。
  - Rsp 通道接收 `Comp` 作为写完成确认。
- 事务大小：
  - `Size` 字段参数化配置。
  - 首批支持 `128B` 与 `256B`（由 TMA 内部拆分策略决定）。
- 顺序约束：
  - 同一写事务内必须满足 `WriteUnique(Req) -> CompDBIDResp(Rsp) -> WriteData(Dat) -> Comp(Rsp)`。
  - 同一命令分片内的完成判定以全部分片收到终态响应为准。

### 2.4 TR/TMU 侧事务映射（ARM CHI 风格 over Ring）

- 协议角色：TMA 作为 Ring master，经 TMU 接口访问 TR 空间。
- 读事务：
  - Req 通道发送 `ReadNoSnp`。
  - Dat 通道接收 `CompData`。
- 写事务：
  - Req 通道发送 `WriteNoSnp`。
  - Dat 通道发送 `NonCopyBackWrData`（若实现要求 DBID，则遵循 `CompDBIDResp` 后发数）。
  - Rsp 通道接收 `Comp` 作为写完成确认。
- 地址空间：
  - TR 访问地址解释由 TMU/Ring 侧定义（tile index + tile offset 的线性化规则见 `PAYLOAD_SPEC.md`）。
- 接口实现要求：
  - 通道保持 CHI 三通道分离（Req/Rsp/Dat）。
  - `TxnID` 在 TMA 内部唯一，支持并发分片归并。
  - 错误响应统一上送 `rsp_status_tma`。

## 3. 目标微架构

### 3.1 模块拆分

- `cmd_frontend`：命令接收、解包、参数合法性检查、排队。
- `desc_engine`：维度/步长/布局遍历器，生成逻辑 tile 访问序列。
- `layout_engine`：执行 GM<->TR 数据映射与可选 pad。
- `gm_chi_master`：GM 侧 CHI 读写主机（ReadOnce/WriteUnique）。
- `tr_chi_master`：TR 侧 CHI 读写主机（ReadNoSnp/WriteNoSnp）。
- `xfer_scheduler`：按资源可用性和依赖关系调度事务。
- `completion_unit`：收敛完成条件并回传响应。

### 3.2 TLOAD/TSTORE 主流程

- `TLOAD`：
  1. 发起 GM `ReadOnce`（按 `Size=128B/256B` 分片）
  2. 数据进入 `layout_engine`，执行 GM->TR 排布转换与 pad
  3. 发起 TR `WriteNoSnp`
  4. 全部分片完成后返回 `rsp_status=OK`
- `TSTORE`：
  1. 发起 TR `ReadNoSnp`
  2. 数据进入 `layout_engine`，执行 TR->GM 排布转换
  3. 发起 GM `WriteUnique`（Req->DBIDResp->Data）
  4. 全部分片完成后返回 `rsp_status=OK`

### 3.3 状态机建议

- 指令级状态：
  - `IDLE`
  - `DECODE`
  - `ISSUE_RD`
  - `WAIT_RD`
  - `TRANSFORM`
  - `ISSUE_WR`
  - `WAIT_WR`
  - `COMPLETE`
  - `ERROR`
- 分片级状态独立管理（支持未来扩展到多 in-flight）。

## 4. 分阶段实施

### Phase 0：规格冻结与接口建模

- 输出 `payload` 字段定义文档（位域、单位、约束、默认值）。
- 输出 GM/TR 两侧 CHI-like 端口定义（信号列表与时序规则）。
- 输出错误码、完成语义、时序约束文档。

交付物：

- `tma/PAYLOAD_SPEC.md`
- `tma/CHI_IF_SPEC.md`

### Phase 1：替换占位符为可控骨架

- 将 `tma.py` 改造成结构化模块：
  - 命令队列
  - 状态机
  - 错误与完成通路
- 暂用“空搬运路径”连通流程（不做真实数据变换），确保命令生命周期正确。

验收：

- 可以正确接收命令并返回 `tag/status`。
- `rsp_valid_tma` 只在完成时拉高。

### Phase 2：CHI 主机通路打通（不含 layout）

- 实现 GM 侧：
  - `ReadOnce`
  - `WriteUnique` 三阶段握手
- 实现 TR 侧：
  - `ReadNoSnp`
  - `WriteNoSnp`
- 先用线性地址映射（无 layout 转换）验证双向读写链路。

验收：

- 协议握手序列正确。
- 分片搬运（128B/256B）可运行。

### Phase 3：layout/pad 引擎接入

- 接入 `ND/DN/NZ/ZN` 映射逻辑。
- 接入 pad 策略（至少 `Null/Zero/Max/Min` 对应路径）。
- 完成 `TLOAD` 与 `TSTORE` 的数据排布闭环。

验收：

- 指定案例下 TR 数据与软件参考模型一致。
- `TSTORE` 不生成 pad，仅按目标 footprint 写回。

### Phase 4：错误处理与可观察性

- 增加协议超时/非法参数/访问异常处理。
- 增加调试与统计计数器：
  - 请求数
  - 分片数
  - 错误数
  - 周期占用
- 统一映射到 `rsp_status_tma`。

验收：

- 异常路径可稳定结束且不挂死。
- 统计信息可被测试读取。

### Phase 5：集成与回归

- 在 `top` 路径上接入 TMA 新模块并替换 stub 行为。
- 增加 directed tests：
  - `TLOAD` 基本流
  - `TSTORE` 基本流
  - `128B/256B` size 切换
  - layout 转换覆盖
  - 错误注入
- 增加随机化回归（参数随机 + 事务背压）。

验收：

- 回归通过率达到约定阈值（建议 > 95%）。
- 无死锁、无 tag 错配、无完成丢失。

## 5. 验证策略

### 5.1 参考模型

- 在测试侧实现软件 reference（Python）：
  - 地址遍历
  - layout 映射
  - pad 行为
- 硬件输出与 reference 按 tile 比对。

### 5.2 协议检查

- 对 CHI-like 接口加断言：
  - 请求-响应配对
  - `WriteUnique` 顺序约束（Req 在前，Data 在 DBID 后）
  - 分片边界与 size 合法性

### 5.3 集成检查

- 验证完成响应与 BCC tag 匹配，确认可驱动提交推进。
- 验证背压场景下不丢包、不重放、不错序。

## 6. 风险与缓解

- 风险：`cmd_payload_tma` 位宽/字段不足。
  - 缓解：Phase 0 先冻结位域；必要时引入扩展 payload 或旁带配置。
- 风险：Ring/TMU 真实接口晚于 TMA 开发。
  - 缓解：先做 CHI-like 本地接口 + adapter 抽象层。
- 风险：layout 逻辑复杂导致时序压力。
  - 缓解：分级流水（地址生成/搬运/重排拆级），优先功能正确再优化频率。

## 7. 下一步计划（本期后）

- 块指令解码到 TMA（把 `BSTART.TMA/B.ARG/B.DIM/B.IOR/B.IOT` 端到端落地到 `cmd_payload_tma`）。
- LSU 中 memory model 相关地址重叠范围检查。
- 预测执行下 `TLOAD` 被 flush 的取消、回滚与资源回收机制。
