// C[i] = A[i] + B[i]: the address of element i goes out on one cycle, its data comes back on the next one and is
// written to C then. One element per cycle, done two cycles after the last address.
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
    logic [31:0] i, pending_index;
    logic running, pending, started;

    assign A_addr = i;
    assign B_addr = i;
    assign C_addr = pending_index;
    assign C_data = A_data + B_data;
    assign C_write = pending;

    always_ff @(posedge clk) begin
        if (start) begin
            i <= 0;
            running <= 1;
            started <= 1;
            pending <= 0;
            done <= 0;
        end else begin
            pending <= running && $signed(i) < N;
            pending_index <= i;
            if (running) begin
                if ($signed(i) < N) i <= i + 1;
                else running <= 0;
            end
            done <= started && !running && !pending;
        end
    end
endmodule
