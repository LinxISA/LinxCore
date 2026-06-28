# Chisel Issues

## CHISEL-ISSUE-001: Local JVM/SBT Toolchain Missing

Status: closed

Impact:

- The initial Chisel compile, Scala test, and Verilog emit were blocked until a
  local JDK and `sbt` were installed.

Evidence:

```bash
bash tools/chisel/build_chisel.sh                 # pass
bash tools/chisel/run_chisel_tests.sh --only ROBID # pass
bash tools/chisel/emit_verilog.sh                 # pass
```

Resolution:

- Installed Homebrew `openjdk@17` and `sbt`.
- Added `tools/chisel/chisel_env.sh` so wrappers set `JAVA_HOME` to Homebrew
  `openjdk@17` when `JAVA_HOME` is unset.
- Updated the Chisel project to Scala `2.13.17` to match the resolved
  dependency graph.

## CHISEL-ISSUE-002: SBT Server Socket Race Under Parallel Invocations

Status: open

Impact:

- Running two SBT-backed wrappers at the same time can race the SBT 2 server
  socket and produce `Connection refused`.

Evidence:

- A parallel invocation of `run_chisel_tests.sh --only ROBID` and
  `run_chisel_rob_bookkeeping.sh --robid-only` produced an SBT client
  `Connection refused` error, while the same ROBID gate passed when rerun
  sequentially.

Current mitigation:

- Run SBT-backed Chisel gates sequentially.
- Wrappers use `--batch --no-colors` for CI-like output, but this does not
  prove parallel server use is safe.
