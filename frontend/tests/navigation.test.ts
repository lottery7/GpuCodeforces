import { describe, expect, it } from 'vitest';
import { problemPath, safeReturnTo } from '../src/utils';

describe('Возврат после входа', () => {
  it.each([null, '', 'https://example.com', '//example.com', '/\\example.com', '/\n/example.com', '/\t/example.com'])('отклоняет внешний или некорректный путь %s', path => {
    expect(safeReturnTo(path)).toBe('/profile');
  });
  it('сохраняет задачу, контекст и вкладку', () => {
    const path = problemPath('vector-addition', 'weekly-12', 'submissions');
    expect(safeReturnTo(path)).toBe('/problem/vector-addition?contest=weekly-12&tab=submissions');
  });
});
