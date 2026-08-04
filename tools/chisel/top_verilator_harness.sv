module CoreTOPHarness(
  input logic clock,
  input logic reset
);
  (* keep_hierarchy = "yes" *) CoreTOP dut(
    .clock(clock),
    .reset(reset)
  );
endmodule
