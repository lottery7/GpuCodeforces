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
  limits:
    gpu_time_ms: 1000
    host_memory_mb: 4096
    device_memory_mb: 2048
---

# Vector Addition

Given two input vectors A and B, calculate:

C[i] = A[i] + B[i]