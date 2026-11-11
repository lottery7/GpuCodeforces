import { describe, expect, it } from 'vitest';
import { formatLimit } from '../src/processors';

describe('Лимит задачи', () => {
  it.each([
    [3_500_000_000, '3,5·10⁹'],
    [20_000_000_000, '2·10¹⁰'],
    [10_000_000_000_000, '10¹³'],
    [1_250_000, '1,25·10⁶'],
    [10_000_000, '10⁷'],
  ])('круглый лимит от миллиона — степенью десяти: %d', (value, text) => {
    expect(formatLimit({ value, type: 'CYCLES_X_DEPTH_X_TRANSISTORS' })).toBe(text);
  });

  it('меньший или некруглый лимит — всеми цифрами', () => {
    expect(formatLimit({ value: 50_000, type: 'CYCLES' })).toMatch(/^50\s000$/);
    expect(formatLimit({ value: 1_234_567, type: 'WEIGHTED_INSTRUCTIONS' })).toMatch(/^1\s234\s567$/);
    expect(formatLimit({ value: 1000, type: 'TIME_MS' })).toBe('1000');
    expect(formatLimit({ value: 2.5, type: 'TIME_MS' })).toBe('2.5');
  });
});
