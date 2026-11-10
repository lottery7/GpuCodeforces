---
title: Matrix Multiplication

tags:
  - MEDIUM
  - matrix
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
    - name: M
      type: int
      direction: input
    - name: K
      type: int
      direction: input
    - name: N
      type: int
      direction: input

constraints:
  M:
    min: 1
    max: 1000
  K:
    min: 1
    max: 1000
  N:
    min: 1
    max: 1000

execution:
  gpu:
    min_compute_capability: "7.0"
  limits:
    gpu_time_ms: 2000
    host_memory_mb: 4096
    device_memory_mb: 4096
---

# Matrix Multiplication

Implement matrix multiplication on the GPU.

Given two dense matrices A (M×K) and B (K×N), compute the product C = A × B.

Your implementation must handle the tiling and memory access patterns
efficiently.