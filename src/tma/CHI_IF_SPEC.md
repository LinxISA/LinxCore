# TMA CHI 接口规范（GM/TR）

## 1. 范围

本规范定义 TMA 对外 CHI 三通道接口（Req/Rsp/Dat）以及 `TLOAD/TSTORE` 的事务映射：

- GM 侧：ARM CHI Non-coherent 映射（`ReadOnce` / `WriteUnique`）。
- TR 侧：ARM CHI 风格映射（`ReadNoSnp` / `WriteNoSnp`，通过 Ring 访问 TMU）。

## 2. 参数化

- `ADDR_W`：地址位宽（默认 64）
- `DATA_W`：数据位宽（默认 256）
- `TXNID_W`：事务 ID 位宽（默认 8）
- `DBID_W`：DBID 位宽（默认 8）
- `BE_W`：字节使能位宽（`DATA_W/8`）

## 3. 通道定义

### 3.1 Req 通道（请求）

- `*_req_valid`
- `*_req_ready`
- `*_req_opcode[6:0]`
- `*_req_txnid[TXNID_W-1:0]`
- `*_req_addr[ADDR_W-1:0]`
- `*_req_size[2:0]`
- `*_req_len[7:0]`

### 3.2 Rsp 通道（响应）

- `*_rsp_valid`
- `*_rsp_ready`
- `*_rsp_opcode[5:0]`（`Comp` / `CompDBIDResp` 等）
- `*_rsp_txnid[TXNID_W-1:0]`
- `*_rsp_dbid[DBID_W-1:0]`
- `*_rsp_resp[1:0]`（`OKAY/EXOKAY/SLVERR/DECERR`）

### 3.3 Dat 通道（数据）

- `*_dat_valid`
- `*_dat_ready`
- `*_dat_opcode[5:0]`（`CompData` / `NonCopyBackWrData`）
- `*_dat_txnid[TXNID_W-1:0]`
- `*_dat_dbid[DBID_W-1:0]`
- `*_dat_data[DATA_W-1:0]`
- `*_dat_be[BE_W-1:0]`
- `*_dat_resp[1:0]`（读返回数据时有效）

约定：

- `gm_*` 前缀用于 GM 侧接口。
- `tr_*` 前缀用于 TR 侧接口。

## 4. 事务映射

### 4.1 GM 侧

`TLOAD`（GM -> TR）:

1. Req 发送 `ReadOnce`。
2. Dat 接收 `CompData`（按 `TxnID` 聚合；结束边界由事务上下文推导）。

`TSTORE`（TR -> GM）:

1. Req 发送 `WriteUnique`。
2. Rsp 接收 `CompDBIDResp`，锁存 `DBID`。
3. Dat 发送 `NonCopyBackWrData`（带 `DBID`；发送拍数由事务上下文决定）。
4. Rsp 接收 `Comp` 作为写完成。

### 4.2 TR 侧（over Ring）

`TLOAD`（写入 TR）:

1. Req 发送 `WriteNoSnp`。
2. Dat 发送 `NonCopyBackWrData`。
3. Rsp 接收 `Comp`。

`TSTORE`（读取 TR）:

1. Req 发送 `ReadNoSnp`。
2. Dat 接收 `CompData`。

说明：

- 若 TMU/Ring 子系统要求写前 `DBID`，则遵循 `CompDBIDResp` 后发数；否则该步骤可省略。

## 5. 顺序与一致性约束（模块内）

- 每个 `TxnID` 在生命周期内唯一，直至完成回收。
- `WriteUnique` 严格顺序：`Req -> CompDBIDResp -> Dat -> Comp`。
- 同一 TMA 命令的完成条件：
  - 所有 GM/TR 分片事务终态完成；
  - 无未决 `TxnID`；
  - 无协议/访问错误。

## 5.1 关于 Dat 通道是否有 `last`

- ARM CHI 的 Dat 通道协议语义中**没有独立的 `last` 信号字段**。
- 读/写数据传输的结束边界由请求侧事务信息（`opcode/size/len/txn context`）和响应配对关系确定。
- 如果实现内部需要 `last_local` 辅助位，可在模块内部/adapter 内生成，但不作为 CHI 对外信号定义。

## 6. 错误映射

- CHI `resp` 非成功（`SLVERR/DECERR`）映射 `ACCESS_ERR`。
- 通道顺序违规、非法 `DBID/TxnID`、重复完成映射 `PROTOCOL_ERR`。
- 超时窗口溢出映射 `TIMEOUT`。

## 7. 实现建议（与现有代码对齐）

- 在 `tma.py` 中维护：
  - `txn_alloc`（`TxnID` 分配）
  - `wr_dbid_table`（`TxnID -> DBID`）
  - `rd_reorder_buffer`（读数据重组）
  - `cmd_completion_table`（命令级分片计数）
- 命令完成后通过：
  - `rsp_valid_tma=1`
  - `rsp_tag_tma=cmd_tag_tma`
  - `rsp_status_tma=<status>`
  上报到 BCtrl/BROB 路径。
