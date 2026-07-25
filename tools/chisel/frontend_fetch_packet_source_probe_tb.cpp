#include <cstdlib>
#include <cstdint>
#include <iostream>

#include "VFrontendFetchPacketSourceProbe.h"
#include "verilated.h"

static void require_probe(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "frontend-fetch-packet-source-probe: " << message << '\n';
        std::exit(1);
    }
}

static void eval(VFrontendFetchPacketSourceProbe &dut) {
    dut.eval();
}

static void tick(VFrontendFetchPacketSourceProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

static void idle(VFrontendFetchPacketSourceProbe &dut) {
    dut.io_startValid = 0;
    dut.io_startPc = 0;
    dut.io_restartValid = 0;
    dut.io_restartPc = 0;
    dut.io_flushValid = 0;
    dut.io_reqReady = 0;
    dut.io_respValid = 0;
    dut.io_respWindow = 0;
    dut.io_outReady = 0;
    dut.io_advanceBytes = 8;
}

static void reset(VFrontendFetchPacketSourceProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    idle(dut);
}

static void start(VFrontendFetchPacketSourceProbe &dut, std::uint64_t pc) {
    idle(dut);
    dut.io_startValid = 1;
    dut.io_startPc = pc;
    tick(dut);
    idle(dut);
}

static void accept_request(VFrontendFetchPacketSourceProbe &dut, std::uint64_t pc) {
    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_probe(dut.io_reqValid, "expected request valid before accepting request");
    require_probe(dut.io_reqPc == pc, "request PC did not match expected PC");
    require_probe(dut.io_reqFire, "request did not fire with reqReady asserted");
    tick(dut);
    idle(dut);
}

static void require_no_stale_packet(VFrontendFetchPacketSourceProbe &dut, const char *context) {
    eval(dut);
    require_probe(!dut.io_out_valid, context);
    require_probe(!dut.io_packetValid, "discarded stale response became resident packet");
}

static void same_cycle_restart_response(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x1000);
    accept_request(dut, 0x1000);

    idle(dut);
    dut.io_restartValid = 1;
    dut.io_restartPc = 0x2000;
    dut.io_respValid = 1;
    dut.io_respWindow = 0x1111222233334444ULL;
    eval(dut);
    require_probe(dut.io_respReady,
                  "response-drain: same-cycle restart did not assert respReady");
    require_probe(dut.io_respFire,
                  "response-drain: same-cycle restart did not handshake stale response");
    require_probe(!dut.io_reqValid,
                  "response-drain: redirected request issued before stale response drained");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_no_stale_packet(dut, "response-drain: stale restart response packetized");
    require_probe(!dut.io_waitingResponse,
                  "response-drain: waiting state remained after same-cycle stale response");
    require_probe(dut.io_reqValid && dut.io_reqPc == 0x2000,
                  "response-drain: redirected request did not issue after same-cycle drain");
    require_probe(dut.io_nextPktUid == 1,
                  "response-drain: restart did not preserve UID progression");
    tick(dut);
}

static void delayed_restart_response(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x3000);
    accept_request(dut, 0x3000);

    idle(dut);
    dut.io_restartValid = 1;
    dut.io_restartPc = 0x4000;
    tick(dut);

    for (int i = 0; i < 3; ++i) {
        idle(dut);
        dut.io_reqReady = 1;
        eval(dut);
        require_probe(dut.io_respReady,
                      "delayed-drain: respReady dropped while stale response was outstanding");
        require_probe(!dut.io_reqValid,
                      "delayed-drain: redirected request issued before stale response");
        require_probe(dut.io_currentPc == 0x4000,
                      "delayed-drain: restart PC was not retained while draining stale response");
        tick(dut);
    }

    idle(dut);
    dut.io_respValid = 1;
    dut.io_respWindow = 0x5555666677778888ULL;
    eval(dut);
    require_probe(dut.io_respReady && dut.io_respFire,
                  "delayed-drain: stale response did not handshake after hold cycles");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_no_stale_packet(dut, "delayed-drain: stale response became output packet");
    require_probe(dut.io_reqValid && dut.io_reqPc == 0x4000,
                  "delayed-drain: redirected request did not issue after drain");
    tick(dut);
}

static void same_cycle_start_response(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x5000);
    accept_request(dut, 0x5000);

    idle(dut);
    dut.io_respValid = 1;
    dut.io_respWindow = 0xaaaa;
    eval(dut);
    require_probe(dut.io_respFire, "same-cycle-start-drain: first response did not fire");
    tick(dut);

    idle(dut);
    dut.io_outReady = 1;
    eval(dut);
    require_probe(dut.io_outFire, "same-cycle-start-drain: first packet did not drain");
    tick(dut);
    accept_request(dut, 0x5008);

    idle(dut);
    dut.io_startValid = 1;
    dut.io_startPc = 0x6000;
    dut.io_respValid = 1;
    dut.io_respWindow = 0xbbbb;
    eval(dut);
    require_probe(dut.io_respReady,
                  "same-cycle-start-drain: start did not assert respReady");
    require_probe(dut.io_respFire,
                  "same-cycle-start-drain: start did not handshake stale response");
    require_probe(!dut.io_reqValid,
                  "same-cycle-start-drain: request issued before stale response drained");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_no_stale_packet(dut, "same-cycle-start-drain: stale pre-start response packetized");
    require_probe(!dut.io_waitingResponse,
                  "same-cycle-start-drain: waiting state remained after same-cycle stale response");
    require_probe(dut.io_reqValid && dut.io_reqPc == 0x6000,
                  "same-cycle-start-drain: started request did not issue after drain");
    require_probe(dut.io_nextPktUid == 0,
                  "same-cycle-start-drain: start did not reset next request UID");
    tick(dut);
}

static void same_cycle_flush_response(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x7000);
    accept_request(dut, 0x7000);

    idle(dut);
    dut.io_flushValid = 1;
    dut.io_respValid = 1;
    dut.io_respWindow = 0xcccc;
    eval(dut);
    require_probe(dut.io_respReady,
                  "same-cycle-flush-drain: flush did not assert respReady");
    require_probe(dut.io_respFire,
                  "same-cycle-flush-drain: flush did not handshake stale response");
    require_probe(!dut.io_reqValid,
                  "same-cycle-flush-drain: request issued while flushed inactive");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_no_stale_packet(dut, "same-cycle-flush-drain: stale flushed response packetized");
    require_probe(!dut.io_waitingResponse,
                  "same-cycle-flush-drain: waiting state remained after same-cycle stale response");
    require_probe(!dut.io_active && !dut.io_reqValid,
                  "same-cycle-flush-drain: source did not remain inactive after stale drain");

    start(dut, 0x8000);
    accept_request(dut, 0x8000);
}

static void start_resets_uid_after_drain(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x5000);
    accept_request(dut, 0x5000);

    idle(dut);
    dut.io_respValid = 1;
    dut.io_respWindow = 0xaaaa;
    eval(dut);
    require_probe(dut.io_respFire, "start-drain: first response did not fire");
    tick(dut);

    idle(dut);
    dut.io_outReady = 1;
    eval(dut);
    require_probe(dut.io_outFire, "start-drain: first packet did not drain");
    tick(dut);
    accept_request(dut, 0x5008);

    idle(dut);
    dut.io_startValid = 1;
    dut.io_startPc = 0x6000;
    tick(dut);

    idle(dut);
    dut.io_respValid = 1;
    dut.io_respWindow = 0xbbbb;
    eval(dut);
    require_probe(dut.io_respReady && dut.io_respFire,
                  "start-drain: stale response after start did not handshake");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_no_stale_packet(dut, "start-drain: stale pre-start response packetized");
    require_probe(dut.io_reqValid && dut.io_reqPc == 0x6000,
                  "start-drain: started request did not issue after drain");
    require_probe(dut.io_nextPktUid == 0,
                  "start-drain: start did not reset next request UID");
    tick(dut);
}

static void flush_drains_and_stays_inactive(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x7000);
    accept_request(dut, 0x7000);

    idle(dut);
    dut.io_flushValid = 1;
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_probe(dut.io_respReady, "flush-drain: respReady dropped before stale response");
    require_probe(!dut.io_reqValid, "flush-drain: request issued while flushed inactive");

    dut.io_respValid = 1;
    dut.io_respWindow = 0xcccc;
    eval(dut);
    require_probe(dut.io_respFire, "flush-drain: stale response did not drain");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_no_stale_packet(dut, "flush-drain: stale flushed response packetized");
    require_probe(!dut.io_active && !dut.io_reqValid,
                  "flush-drain: source did not remain inactive after stale drain");

    start(dut, 0x8000);
    accept_request(dut, 0x8000);
}

static void simultaneous_start_restart_uses_restart_pc(VFrontendFetchPacketSourceProbe &dut) {
    reset(dut);
    start(dut, 0x9000);
    accept_request(dut, 0x9000);

    idle(dut);
    dut.io_startValid = 1;
    dut.io_startPc = 0xa000;
    dut.io_restartValid = 1;
    dut.io_restartPc = 0xb000;
    dut.io_respValid = 1;
    dut.io_respWindow = 0xdddd;
    eval(dut);
    require_probe(dut.io_respReady && dut.io_respFire,
                  "priority-drain: simultaneous start/restart response did not drain");
    tick(dut);

    idle(dut);
    dut.io_reqReady = 1;
    eval(dut);
    require_probe(dut.io_reqValid && dut.io_reqPc == 0xb000,
                  "priority-drain: restart PC did not win simultaneous start/restart");
    require_probe(dut.io_nextPktUid == 1,
                  "priority-drain: simultaneous restart incorrectly reset UID");
    tick(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VFrontendFetchPacketSourceProbe dut;

    same_cycle_restart_response(dut);
    same_cycle_start_response(dut);
    same_cycle_flush_response(dut);
    delayed_restart_response(dut);
    start_resets_uid_after_drain(dut);
    flush_drains_and_stays_inactive(dut);
    simultaneous_start_restart_uses_restart_pc(dut);

    std::cout << "frontend-fetch-packet-source-probe: PASS\n";
    return 0;
}
