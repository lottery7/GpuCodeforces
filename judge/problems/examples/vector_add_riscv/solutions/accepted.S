# solve(A = a0, B = a1, C = a2, N = a3) with the vector extension.
    .text
    .globl solve
solve:
    blez a3, 2f
1:
    vsetvli t0, a3, e32, m8, ta, ma
    vle32.v v0, (a0)
    vle32.v v8, (a1)
    vadd.vv v16, v0, v8
    vse32.v v16, (a2)
    slli t1, t0, 2
    add a0, a0, t1
    add a1, a1, t1
    add a2, a2, t1
    sub a3, a3, t0
    bnez a3, 1b
2:
    ret
