// Never raises done: TIME_LIMIT_EXCEEDED once the cycles the limit allows run out.
module solve (
    input  logic clk,
    input  logic start,
    output logic done,
    output logic [31:0] A_addr,
    input  logic [31:0] A_data,
    output logic [31:0] B_addr,
    input  logic [31:0] B_data,
    output logic [31:0] C_addr,
    output logic [31:0] C_data,
    output logic C_write,
    input  logic signed [31:0] N
);
    assign A_addr = 0;
    assign B_addr = 0;
    assign C_addr = 0;
    assign C_data = A_data + B_data;
    assign C_write = 1'b0;
    assign done = 1'b0;
endmodule
