// The index of C is held by a latch, not a flip-flop: Yosys has no transistor count for a latch, so the judge
// rejects the circuit (COMPILATION_ERROR) and asks for a synchronous one.
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
    logic [31:0] held_index;
    always_latch if (pending) held_index = pending_index;
    assign C_addr = held_index;
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
