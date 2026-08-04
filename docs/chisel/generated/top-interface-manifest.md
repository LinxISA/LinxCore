# Generated TOP interface manifest

This file is generated from the canonical Scala Bundle types. Do not edit it by hand.

| Profile | Endpoint | Contract | Producer | Consumer | Lanes | Payload | Leaf ports | Payload bits |
|---|---|---|---|---|---:|---|---:|---:|
| W2 | `top_io` | [[IFC-TOP-EXT-002]] | Platform | TOP | 1 | `TOPIO` | 532 | 12912 |
| W2 | `ifu_to_ctu` | [[IFC-IFU-CTU-001]] | IFU | CTU | 2 | `FetchedPacket` | 43 | 1214 |
| W2 | `ctu_to_ooo` | [[IFC-CTU-OOO-001]] | CTU | OOO | 2 | `D1Packet` | 53 | 1400 |
| W2 | `ooo_to_iex_alu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2358 |
| W2 | `ooo_to_iex_bru` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2358 |
| W2 | `ooo_to_iex_agu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2358 |
| W2 | `ooo_to_iex_std` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `StoreDispatchTxn` | 512 | 4718 |
| W2 | `ooo_to_iex_system` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2358 |
| W2 | `ooo_to_iex_cmd` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2358 |
| W2 | `ooo_to_iex_rob_noflush` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `RobNoflushTxn` | 13 | 226 |
| W2 | `iex_to_ooo_rob_noflush_ready` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `RobNoflushReadyTxn` | 13 | 226 |
| W2 | `iex_to_ooo_rob_resolve` | [[IFC-OOO-IEX-001]] | IEX | OOO | 2 | `RobResolveTxn` | 29 | 528 |
| W2 | `iex_to_ooo_system_issue` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `SystemIssueTxn` | 15 | 302 |
| W2 | `ooo_to_top_system_issue` | [[IFC-OOO-IEX-001]] | OOO | TOP | 1 | `SystemIssueTxn` | 15 | 302 |
| W2 | `iex_to_ooo_pc_buffer_read_address` | [[IFC-OOO-IEX-001]] | IEX | OOO | 6 | `PcBufferReadAddress` | 4 | 24 |
| W2 | `ooo_to_iex_pc_buffer_read_pc_base` | [[IFC-OOO-IEX-001]] | OOO | IEX | 6 | `UInt` | 1 | 64 |
| W2 | `iex_to_lsu_load_issue` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `LoadIssueTxn` | 44 | 562 |
| W2 | `lsu_to_iex_load_allocation_preview` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadAllocationPreview` | 15 | 282 |
| W2 | `lsu_to_iex_load_launch` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadLaunchTxn` | 15 | 282 |
| W2 | `iex_to_lsu_store_reservation` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreReservationTxn` | 24 | 374 |
| W2 | `iex_to_lsu_store_address` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreAddressTxn` | 27 | 501 |
| W2 | `iex_to_lsu_store_data` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreDataTxn` | 30 | 581 |
| W2 | `lsu_to_iex_load_result` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadResultTxn` | 57 | 833 |
| W2 | `lsu_to_iex_load_reissue` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadReissueTxn` | 29 | 548 |
| W2 | `lsu_to_iex_load_repick` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadRepickTxn` | 28 | 484 |
| W2 | `lsu_to_iex_load_cancel` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadCancelTxn` | 13 | 202 |
| W2 | `ooo_to_lsu_store_commit` | [[IFC-IEX-LSU-001]] | OOO | LSU | 1 | `StoreCommitAuthorizationTxn` | 14 | 220 |
| W2 | `translation_to_lsu_store_classify` | [[IFC-IEX-LSU-001]] | Translation | LSU | 1 | `StoreMemoryClassifyTxn` | 16 | 254 |
| W2 | `external_cmd_issue` | [[IFC-TOP-EXT-001]] | IEX | External CMD | 1 | `CmdIssueTxn` | 19 | 498 |
| W2 | `owner_to_ooo_recovery_event` | [[IFC-RECOVERY-001]] | IEX/LSU | OOO | 1 | `RecoveryEvent` | 32 | 618 |
| W2 | `ooo_recovery_prepare` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W2 | `target_to_ooo_recovery_prepared` | [[IFC-RECOVERY-001]] | IFU/CTU/IEX/LSU | OOO | 1 | `RecoveryPlan` | 41 | 459 |
| W2 | `ooo_recovery_apply` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W2 | `ooo_recovery_abort` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W2 | `ooo_commit` | [[IFC-COMMIT-001]] | OOO | TOP/DTU | 2 | `CommitTxn` | 159 | 2318 |
| W2 | `box_to_dtu_trace` | [[IFC-DTU-001]] | TOP/IFU/CTU/OOO/IEX/LSU | DTU | 2 | `TracePacket` | 41 | 750 |
| W2 | `external_to_dtu_debug_request` | [[IFC-DTU-001]] | External Debug | DTU | 1 | `DebugRequest` | 5 | 196 |
| W2 | `dtu_to_ooo_debug_request` | [[IFC-DTU-001]] | DTU | OOO | 1 | `DebugRequest` | 5 | 196 |
| W2 | `ooo_to_dtu_debug_response` | [[IFC-DTU-001]] | OOO | DTU | 1 | `DebugResponse` | 4 | 130 |
| W2 | `dtu_to_external_debug_response` | [[IFC-DTU-001]] | DTU | External Debug | 1 | `DebugResponse` | 4 | 130 |
| W2 | `dtu_trace_export` | [[IFC-DTU-001]] | DTU | External Trace | 2 | `TracePacket` | 41 | 750 |
| W2 | `dtu_performance_counter` | [[IFC-DTU-001]] | DTU | External Counter | 32 | `UInt` | 1 | 64 |
| W2 | `instruction_memory_request` | [[IFC-MEMORY-001]] | IFU | Memory | 1 | `MemoryRequestTxn` | 9 | 225 |
| W2 | `instruction_memory_response` | [[IFC-MEMORY-001]] | Memory | IFU | 1 | `MemoryResponseTxn` | 13 | 759 |
| W2 | `data_memory_request` | [[IFC-MEMORY-001]] | LSU | Memory | 4 | `MemoryRequestTxn` | 9 | 225 |
| W2 | `data_memory_response` | [[IFC-MEMORY-001]] | Memory | LSU | 4 | `MemoryResponseTxn` | 13 | 759 |
| W4 | `top_io` | [[IFC-TOP-EXT-002]] | Platform | TOP | 1 | `TOPIO` | 730 | 15978 |
| W4 | `ifu_to_ctu` | [[IFC-IFU-CTU-001]] | IFU | CTU | 4 | `FetchedPacket` | 85 | 2427 |
| W4 | `ctu_to_ooo` | [[IFC-CTU-OOO-001]] | CTU | OOO | 4 | `D1Packet` | 105 | 2799 |
| W4 | `ooo_to_iex_alu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2367 |
| W4 | `ooo_to_iex_bru` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2367 |
| W4 | `ooo_to_iex_agu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2367 |
| W4 | `ooo_to_iex_std` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `StoreDispatchTxn` | 512 | 4736 |
| W4 | `ooo_to_iex_system` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2367 |
| W4 | `ooo_to_iex_cmd` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2367 |
| W4 | `ooo_to_iex_rob_noflush` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `RobNoflushTxn` | 13 | 226 |
| W4 | `iex_to_ooo_rob_noflush_ready` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `RobNoflushReadyTxn` | 13 | 226 |
| W4 | `iex_to_ooo_rob_resolve` | [[IFC-OOO-IEX-001]] | IEX | OOO | 4 | `RobResolveTxn` | 29 | 528 |
| W4 | `iex_to_ooo_system_issue` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `SystemIssueTxn` | 15 | 302 |
| W4 | `ooo_to_top_system_issue` | [[IFC-OOO-IEX-001]] | OOO | TOP | 1 | `SystemIssueTxn` | 15 | 302 |
| W4 | `iex_to_ooo_pc_buffer_read_address` | [[IFC-OOO-IEX-001]] | IEX | OOO | 6 | `PcBufferReadAddress` | 4 | 24 |
| W4 | `ooo_to_iex_pc_buffer_read_pc_base` | [[IFC-OOO-IEX-001]] | OOO | IEX | 6 | `UInt` | 1 | 64 |
| W4 | `iex_to_lsu_load_issue` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `LoadIssueTxn` | 44 | 562 |
| W4 | `lsu_to_iex_load_allocation_preview` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadAllocationPreview` | 15 | 282 |
| W4 | `lsu_to_iex_load_launch` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadLaunchTxn` | 15 | 282 |
| W4 | `iex_to_lsu_store_reservation` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreReservationTxn` | 24 | 374 |
| W4 | `iex_to_lsu_store_address` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreAddressTxn` | 27 | 501 |
| W4 | `iex_to_lsu_store_data` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreDataTxn` | 30 | 581 |
| W4 | `lsu_to_iex_load_result` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadResultTxn` | 57 | 833 |
| W4 | `lsu_to_iex_load_reissue` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadReissueTxn` | 29 | 548 |
| W4 | `lsu_to_iex_load_repick` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadRepickTxn` | 28 | 484 |
| W4 | `lsu_to_iex_load_cancel` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadCancelTxn` | 13 | 202 |
| W4 | `ooo_to_lsu_store_commit` | [[IFC-IEX-LSU-001]] | OOO | LSU | 1 | `StoreCommitAuthorizationTxn` | 14 | 220 |
| W4 | `translation_to_lsu_store_classify` | [[IFC-IEX-LSU-001]] | Translation | LSU | 1 | `StoreMemoryClassifyTxn` | 16 | 254 |
| W4 | `external_cmd_issue` | [[IFC-TOP-EXT-001]] | IEX | External CMD | 1 | `CmdIssueTxn` | 19 | 498 |
| W4 | `owner_to_ooo_recovery_event` | [[IFC-RECOVERY-001]] | IEX/LSU | OOO | 1 | `RecoveryEvent` | 32 | 618 |
| W4 | `ooo_recovery_prepare` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W4 | `target_to_ooo_recovery_prepared` | [[IFC-RECOVERY-001]] | IFU/CTU/IEX/LSU | OOO | 1 | `RecoveryPlan` | 41 | 459 |
| W4 | `ooo_recovery_apply` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W4 | `ooo_recovery_abort` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W4 | `ooo_commit` | [[IFC-COMMIT-001]] | OOO | TOP/DTU | 4 | `CommitTxn` | 317 | 4635 |
| W4 | `box_to_dtu_trace` | [[IFC-DTU-001]] | TOP/IFU/CTU/OOO/IEX/LSU | DTU | 4 | `TracePacket` | 81 | 1499 |
| W4 | `external_to_dtu_debug_request` | [[IFC-DTU-001]] | External Debug | DTU | 1 | `DebugRequest` | 5 | 196 |
| W4 | `dtu_to_ooo_debug_request` | [[IFC-DTU-001]] | DTU | OOO | 1 | `DebugRequest` | 5 | 196 |
| W4 | `ooo_to_dtu_debug_response` | [[IFC-DTU-001]] | OOO | DTU | 1 | `DebugResponse` | 4 | 130 |
| W4 | `dtu_to_external_debug_response` | [[IFC-DTU-001]] | DTU | External Debug | 1 | `DebugResponse` | 4 | 130 |
| W4 | `dtu_trace_export` | [[IFC-DTU-001]] | DTU | External Trace | 4 | `TracePacket` | 81 | 1499 |
| W4 | `dtu_performance_counter` | [[IFC-DTU-001]] | DTU | External Counter | 32 | `UInt` | 1 | 64 |
| W4 | `instruction_memory_request` | [[IFC-MEMORY-001]] | IFU | Memory | 1 | `MemoryRequestTxn` | 9 | 225 |
| W4 | `instruction_memory_response` | [[IFC-MEMORY-001]] | Memory | IFU | 1 | `MemoryResponseTxn` | 13 | 759 |
| W4 | `data_memory_request` | [[IFC-MEMORY-001]] | LSU | Memory | 4 | `MemoryRequestTxn` | 9 | 225 |
| W4 | `data_memory_response` | [[IFC-MEMORY-001]] | Memory | LSU | 4 | `MemoryResponseTxn` | 13 | 759 |
| W6 | `top_io` | [[IFC-TOP-EXT-002]] | Platform | TOP | 1 | `TOPIO` | 928 | 19042 |
| W6 | `ifu_to_ctu` | [[IFC-IFU-CTU-001]] | IFU | CTU | 6 | `FetchedPacket` | 127 | 3639 |
| W6 | `ctu_to_ooo` | [[IFC-CTU-OOO-001]] | CTU | OOO | 6 | `D1Packet` | 157 | 4197 |
| W6 | `ooo_to_iex_alu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2367 |
| W6 | `ooo_to_iex_bru` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2367 |
| W6 | `ooo_to_iex_agu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2367 |
| W6 | `ooo_to_iex_std` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `StoreDispatchTxn` | 512 | 4736 |
| W6 | `ooo_to_iex_system` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2367 |
| W6 | `ooo_to_iex_cmd` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2367 |
| W6 | `ooo_to_iex_rob_noflush` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `RobNoflushTxn` | 13 | 226 |
| W6 | `iex_to_ooo_rob_noflush_ready` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `RobNoflushReadyTxn` | 13 | 226 |
| W6 | `iex_to_ooo_rob_resolve` | [[IFC-OOO-IEX-001]] | IEX | OOO | 6 | `RobResolveTxn` | 29 | 528 |
| W6 | `iex_to_ooo_system_issue` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `SystemIssueTxn` | 15 | 302 |
| W6 | `ooo_to_top_system_issue` | [[IFC-OOO-IEX-001]] | OOO | TOP | 1 | `SystemIssueTxn` | 15 | 302 |
| W6 | `iex_to_ooo_pc_buffer_read_address` | [[IFC-OOO-IEX-001]] | IEX | OOO | 6 | `PcBufferReadAddress` | 4 | 24 |
| W6 | `ooo_to_iex_pc_buffer_read_pc_base` | [[IFC-OOO-IEX-001]] | OOO | IEX | 6 | `UInt` | 1 | 64 |
| W6 | `iex_to_lsu_load_issue` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `LoadIssueTxn` | 44 | 562 |
| W6 | `lsu_to_iex_load_allocation_preview` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadAllocationPreview` | 15 | 282 |
| W6 | `lsu_to_iex_load_launch` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadLaunchTxn` | 15 | 282 |
| W6 | `iex_to_lsu_store_reservation` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreReservationTxn` | 24 | 374 |
| W6 | `iex_to_lsu_store_address` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreAddressTxn` | 27 | 501 |
| W6 | `iex_to_lsu_store_data` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreDataTxn` | 30 | 581 |
| W6 | `lsu_to_iex_load_result` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadResultTxn` | 57 | 833 |
| W6 | `lsu_to_iex_load_reissue` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadReissueTxn` | 29 | 548 |
| W6 | `lsu_to_iex_load_repick` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadRepickTxn` | 28 | 484 |
| W6 | `lsu_to_iex_load_cancel` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadCancelTxn` | 13 | 202 |
| W6 | `ooo_to_lsu_store_commit` | [[IFC-IEX-LSU-001]] | OOO | LSU | 1 | `StoreCommitAuthorizationTxn` | 14 | 220 |
| W6 | `translation_to_lsu_store_classify` | [[IFC-IEX-LSU-001]] | Translation | LSU | 1 | `StoreMemoryClassifyTxn` | 16 | 254 |
| W6 | `external_cmd_issue` | [[IFC-TOP-EXT-001]] | IEX | External CMD | 1 | `CmdIssueTxn` | 19 | 498 |
| W6 | `owner_to_ooo_recovery_event` | [[IFC-RECOVERY-001]] | IEX/LSU | OOO | 1 | `RecoveryEvent` | 32 | 618 |
| W6 | `ooo_recovery_prepare` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W6 | `target_to_ooo_recovery_prepared` | [[IFC-RECOVERY-001]] | IFU/CTU/IEX/LSU | OOO | 1 | `RecoveryPlan` | 41 | 459 |
| W6 | `ooo_recovery_apply` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W6 | `ooo_recovery_abort` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W6 | `ooo_commit` | [[IFC-COMMIT-001]] | OOO | TOP/DTU | 6 | `CommitTxn` | 475 | 6951 |
| W6 | `box_to_dtu_trace` | [[IFC-DTU-001]] | TOP/IFU/CTU/OOO/IEX/LSU | DTU | 6 | `TracePacket` | 121 | 2247 |
| W6 | `external_to_dtu_debug_request` | [[IFC-DTU-001]] | External Debug | DTU | 1 | `DebugRequest` | 5 | 196 |
| W6 | `dtu_to_ooo_debug_request` | [[IFC-DTU-001]] | DTU | OOO | 1 | `DebugRequest` | 5 | 196 |
| W6 | `ooo_to_dtu_debug_response` | [[IFC-DTU-001]] | OOO | DTU | 1 | `DebugResponse` | 4 | 130 |
| W6 | `dtu_to_external_debug_response` | [[IFC-DTU-001]] | DTU | External Debug | 1 | `DebugResponse` | 4 | 130 |
| W6 | `dtu_trace_export` | [[IFC-DTU-001]] | DTU | External Trace | 6 | `TracePacket` | 121 | 2247 |
| W6 | `dtu_performance_counter` | [[IFC-DTU-001]] | DTU | External Counter | 32 | `UInt` | 1 | 64 |
| W6 | `instruction_memory_request` | [[IFC-MEMORY-001]] | IFU | Memory | 1 | `MemoryRequestTxn` | 9 | 225 |
| W6 | `instruction_memory_response` | [[IFC-MEMORY-001]] | Memory | IFU | 1 | `MemoryResponseTxn` | 13 | 759 |
| W6 | `data_memory_request` | [[IFC-MEMORY-001]] | LSU | Memory | 4 | `MemoryRequestTxn` | 9 | 225 |
| W6 | `data_memory_response` | [[IFC-MEMORY-001]] | Memory | LSU | 4 | `MemoryResponseTxn` | 13 | 759 |
| W8 | `top_io` | [[IFC-TOP-EXT-002]] | Platform | TOP | 1 | `TOPIO` | 1126 | 22108 |
| W8 | `ifu_to_ctu` | [[IFC-IFU-CTU-001]] | IFU | CTU | 8 | `FetchedPacket` | 169 | 4852 |
| W8 | `ctu_to_ooo` | [[IFC-CTU-OOO-001]] | CTU | OOO | 8 | `D1Packet` | 209 | 5596 |
| W8 | `ooo_to_iex_alu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2376 |
| W8 | `ooo_to_iex_bru` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2376 |
| W8 | `ooo_to_iex_agu` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `DispatchTxn` | 255 | 2376 |
| W8 | `ooo_to_iex_std` | [[IFC-OOO-IEX-001]] | OOO | IEX | 2 | `StoreDispatchTxn` | 512 | 4754 |
| W8 | `ooo_to_iex_system` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2376 |
| W8 | `ooo_to_iex_cmd` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `DispatchTxn` | 255 | 2376 |
| W8 | `ooo_to_iex_rob_noflush` | [[IFC-OOO-IEX-001]] | OOO | IEX | 1 | `RobNoflushTxn` | 13 | 226 |
| W8 | `iex_to_ooo_rob_noflush_ready` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `RobNoflushReadyTxn` | 13 | 226 |
| W8 | `iex_to_ooo_rob_resolve` | [[IFC-OOO-IEX-001]] | IEX | OOO | 8 | `RobResolveTxn` | 29 | 528 |
| W8 | `iex_to_ooo_system_issue` | [[IFC-OOO-IEX-001]] | IEX | OOO | 1 | `SystemIssueTxn` | 15 | 302 |
| W8 | `ooo_to_top_system_issue` | [[IFC-OOO-IEX-001]] | OOO | TOP | 1 | `SystemIssueTxn` | 15 | 302 |
| W8 | `iex_to_ooo_pc_buffer_read_address` | [[IFC-OOO-IEX-001]] | IEX | OOO | 6 | `PcBufferReadAddress` | 4 | 24 |
| W8 | `ooo_to_iex_pc_buffer_read_pc_base` | [[IFC-OOO-IEX-001]] | OOO | IEX | 6 | `UInt` | 1 | 64 |
| W8 | `iex_to_lsu_load_issue` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `LoadIssueTxn` | 44 | 562 |
| W8 | `lsu_to_iex_load_allocation_preview` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadAllocationPreview` | 15 | 282 |
| W8 | `lsu_to_iex_load_launch` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadLaunchTxn` | 15 | 282 |
| W8 | `iex_to_lsu_store_reservation` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreReservationTxn` | 24 | 374 |
| W8 | `iex_to_lsu_store_address` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreAddressTxn` | 27 | 501 |
| W8 | `iex_to_lsu_store_data` | [[IFC-IEX-LSU-001]] | IEX | LSU | 2 | `StoreDataTxn` | 30 | 581 |
| W8 | `lsu_to_iex_load_result` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadResultTxn` | 57 | 833 |
| W8 | `lsu_to_iex_load_reissue` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadReissueTxn` | 29 | 548 |
| W8 | `lsu_to_iex_load_repick` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadRepickTxn` | 28 | 484 |
| W8 | `lsu_to_iex_load_cancel` | [[IFC-IEX-LSU-001]] | LSU | IEX | 2 | `LoadCancelTxn` | 13 | 202 |
| W8 | `ooo_to_lsu_store_commit` | [[IFC-IEX-LSU-001]] | OOO | LSU | 1 | `StoreCommitAuthorizationTxn` | 14 | 220 |
| W8 | `translation_to_lsu_store_classify` | [[IFC-IEX-LSU-001]] | Translation | LSU | 1 | `StoreMemoryClassifyTxn` | 16 | 254 |
| W8 | `external_cmd_issue` | [[IFC-TOP-EXT-001]] | IEX | External CMD | 1 | `CmdIssueTxn` | 19 | 498 |
| W8 | `owner_to_ooo_recovery_event` | [[IFC-RECOVERY-001]] | IEX/LSU | OOO | 1 | `RecoveryEvent` | 32 | 618 |
| W8 | `ooo_recovery_prepare` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W8 | `target_to_ooo_recovery_prepared` | [[IFC-RECOVERY-001]] | IFU/CTU/IEX/LSU | OOO | 1 | `RecoveryPlan` | 41 | 459 |
| W8 | `ooo_recovery_apply` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W8 | `ooo_recovery_abort` | [[IFC-RECOVERY-001]] | OOO | IFU/CTU/IEX/LSU | 1 | `RecoveryPlan` | 41 | 459 |
| W8 | `ooo_commit` | [[IFC-COMMIT-001]] | OOO | TOP/DTU | 8 | `CommitTxn` | 633 | 9268 |
| W8 | `box_to_dtu_trace` | [[IFC-DTU-001]] | TOP/IFU/CTU/OOO/IEX/LSU | DTU | 8 | `TracePacket` | 161 | 2996 |
| W8 | `external_to_dtu_debug_request` | [[IFC-DTU-001]] | External Debug | DTU | 1 | `DebugRequest` | 5 | 196 |
| W8 | `dtu_to_ooo_debug_request` | [[IFC-DTU-001]] | DTU | OOO | 1 | `DebugRequest` | 5 | 196 |
| W8 | `ooo_to_dtu_debug_response` | [[IFC-DTU-001]] | OOO | DTU | 1 | `DebugResponse` | 4 | 130 |
| W8 | `dtu_to_external_debug_response` | [[IFC-DTU-001]] | DTU | External Debug | 1 | `DebugResponse` | 4 | 130 |
| W8 | `dtu_trace_export` | [[IFC-DTU-001]] | DTU | External Trace | 8 | `TracePacket` | 161 | 2996 |
| W8 | `dtu_performance_counter` | [[IFC-DTU-001]] | DTU | External Counter | 32 | `UInt` | 1 | 64 |
| W8 | `instruction_memory_request` | [[IFC-MEMORY-001]] | IFU | Memory | 1 | `MemoryRequestTxn` | 9 | 225 |
| W8 | `instruction_memory_response` | [[IFC-MEMORY-001]] | Memory | IFU | 1 | `MemoryResponseTxn` | 13 | 759 |
| W8 | `data_memory_request` | [[IFC-MEMORY-001]] | LSU | Memory | 4 | `MemoryRequestTxn` | 9 | 225 |
| W8 | `data_memory_response` | [[IFC-MEMORY-001]] | Memory | LSU | 4 | `MemoryResponseTxn` | 13 | 759 |
