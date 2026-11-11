// A syntax error: COMPILATION_ERROR with the message of Yosys.
module solve (
    input  logic clk,
    input  logic start,
    output logic done
    output logic [31:0] A_addr
);
    assign done = 1'b0
endmodule
