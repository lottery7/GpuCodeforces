---
title: Vector Addition

tags:
  - EASY
  - vector
  - arithmetic

author: platform

interface:
  function_name: solve
  parameters:
    - name: A
      type: float*
      direction: input
    - name: B
      type: float*
      direction: input
    - name: C
      type: float*
      direction: output
    - name: N
      type: int
      direction: input

constraints:
  N:
    min: 1
    max: 10000000

execution:
  gpu:
    min_compute_capability: "7.0"
    min_memory_mb: 2048
  limits:
    gpu_time_ms: 1000
    host_memory_mb: 4096
    device_memory_mb: 2048
---

# Vector Addition

Implement vector addition on the GPU.

Given two input vectors A and B, calculate:

C[i] = A[i] + B[i]

Your implementation must support all valid values of N.