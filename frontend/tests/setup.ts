import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Processor } from '../src/api/types';

/** The catalog the pages load once; a test that needs another one mocks listProcessors itself. */
export const testProcessors: Processor[] = [
  { id: 'gpu-cuda', title: 'NVIDIA GPU', model: 'NVIDIA A100', languages: [{ id: 'cuda', title: 'CUDA C++', extension: '.cu' }], scoreType: 'TIME_MS', emulated: false },
  { id: 'rtl', title: 'RTL (Verilator + Yosys)', model: 'Verilator + Yosys', languages: [{ id: 'systemverilog', title: 'SystemVerilog', extension: '.sv' }], scoreType: 'CYCLES_X_DEPTH_X_TRANSISTORS', emulated: true },
];

beforeEach(() => {
  vi.spyOn(api, 'listProcessors').mockResolvedValue(testProcessors);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
});
