import { beforeEach, describe, expect, it, vi } from 'vitest';

let drafts: typeof import('../src/drafts');

/** A CUDA draft for the real GPU, the only kind there was before processors. */
const at = (problemId: string, context: string, processor = 'gpu-cuda', language = 'cuda') => ({ problemId, context, processor, language });

beforeEach(async () => {
  vi.resetModules();
  drafts = await import('../src/drafts');
});

describe('Editor drafts', () => {
  it('isolates accounts, guests, problems and contest or practice drafts', () => {
    drafts.writeDraft(null, at('vector-addition', 'practice'), 'guest code');
    drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), 'alex practice');
    drafts.writeDraft('new_cuda', at('vector-addition', 'practice'), 'new practice');
    drafts.writeDraft('alex_cuda', at('reduction', 'practice'), 'alex reduction');
    drafts.writeDraft('alex_cuda', at('vector-addition', 'weekly-12'), 'alex contest');
    expect(drafts.readDraft(null, at('vector-addition', 'practice'), 'starter')).toBe('guest code');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('alex practice');
    expect(drafts.readDraft('new_cuda', at('vector-addition', 'practice'), 'starter')).toBe('new practice');
    expect(drafts.readDraft('alex_cuda', at('reduction', 'practice'), 'starter')).toBe('alex reduction');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'weekly-12'), 'starter')).toBe('alex contest');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'weekly-13'), 'starter')).toBe('starter');
  });

  it('preserves an intentionally empty editor after saving or reloading the module', async () => {
    expect(drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), '')).toBe(true);
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('');
    vi.resetModules();
    const reloaded = await import('../src/drafts');
    expect(reloaded.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('');
  });

  it('transfers only the editor that initiated login and only to its matching return URL', () => {
    const target = '/problems/vector-addition?contest=weekly-12';
    drafts.writeDraft(null, at('vector-addition', 'weekly-12'), 'guest version');
    drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), 'practice version');
    drafts.writeDraft('alex_cuda', at('reduction', 'weekly-12'), 'other problem');
    drafts.rememberEditorLogin(at('vector-addition', 'weekly-12'), '', target);
    drafts.completeEditorLogin('alex_cuda', target);
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'weekly-12'), 'starter')).toBe('');
    expect(drafts.readDraft(null, at('vector-addition', 'weekly-12'), 'starter')).toBe('guest version');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('practice version');
    expect(drafts.readDraft('alex_cuda', at('reduction', 'weekly-12'), 'starter')).toBe('other problem');
    expect(drafts.readDraft('new_cuda', at('vector-addition', 'weekly-12'), 'starter')).toBe('starter');

    drafts.rememberEditorLogin(at('reduction', 'practice'), 'do not transfer', '/problems/reduction');
    drafts.completeEditorLogin('alex_cuda', '/rating');
    drafts.completeEditorLogin('alex_cuda', '/problems/reduction');
    expect(drafts.readDraft('alex_cuda', at('reduction', 'practice'), 'starter')).toBe('starter');
    expect(sessionStorage.getItem('gpu-forces:editor-login')).toBeNull();
  });

  it('restores a matching login intent across an authentication page reload', async () => {
    const target = '/problems/reduction';
    drafts.rememberEditorLogin(at('reduction', 'practice'), 'unsaved guest edit', target);
    vi.resetModules();
    const reloaded = await import('../src/drafts');
    reloaded.completeEditorLogin('new_cuda', target);
    expect(reloaded.readDraft('new_cuda', at('reduction', 'practice'), 'starter')).toBe('unsaved guest edit');
    expect(sessionStorage.getItem('gpu-forces:editor-login')).toBeNull();
  });

  it('keeps the latest code in memory when storage fails and recovers after a successful save', async () => {
    drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), 'stale saved code');
    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage quota exceeded', 'QuotaExceededError');
    });
    expect(drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), 'latest code')).toBe(false);
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('latest code');
    expect(drafts.readDraft('new_cuda', at('vector-addition', 'practice'), 'starter')).toBe('starter');
    expect(drafts.writeDraft('alex_cuda', at('reduction', 'practice'), '')).toBe(false);
    expect(drafts.readDraft('alex_cuda', at('reduction', 'practice'), 'starter')).toBe('');
    blocked.mockRestore();
    expect(drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), 'persisted recovery')).toBe(true);
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('persisted recovery');
    vi.resetModules();
    const reloaded = await import('../src/drafts');
    expect(reloaded.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('persisted recovery');
  });

  it('uses starter code when reads fail and keeps login transfer available without session storage', () => {
    const originalGet = Storage.prototype.getItem;
    const originalSet = Storage.prototype.setItem;
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (this: Storage, key: string) {
      if (this === sessionStorage || key.includes('missing-problem')) throw new DOMException('Storage blocked', 'SecurityError');
      return originalGet.call(this, key);
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      if (this === sessionStorage) throw new DOMException('Storage blocked', 'SecurityError');
      originalSet.call(this, key, value);
    });
    expect(drafts.readDraft('alex_cuda', at('missing-problem', 'practice'), 'starter')).toBe('starter');
    drafts.rememberEditorLogin(at('vector-addition', 'practice'), 'in-memory intent', '/problems/vector-addition');
    drafts.completeEditorLogin('alex_cuda', '/problems/vector-addition');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('in-memory intent');
  });

  it('keeps a draft per processor and language, and reads drafts saved before processors as CUDA ones', () => {
    drafts.writeDraft('alex_cuda', at('vector-addition', 'practice'), 'cuda code');
    drafts.writeDraft('alex_cuda', at('vector-addition', 'practice', 'rtl', 'systemverilog'), 'module solve');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice', 'rtl', 'systemverilog'), 'starter')).toBe('module solve');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice', 'riscv-rvv', 'c'), 'starter')).toBe('starter');
    expect(drafts.readDraft('alex_cuda', at('vector-addition', 'practice'), 'starter')).toBe('cuda code');

    localStorage.setItem('gpu-forces:draft:' + JSON.stringify(['alex_cuda', 'reduction', 'practice']), 'old cuda draft');
    expect(drafts.readDraft('alex_cuda', at('reduction', 'practice'), 'starter')).toBe('old cuda draft');
    expect(drafts.readDraft('alex_cuda', at('reduction', 'practice', 'cuda-sim', 'cuda'), 'starter')).toBe('starter');
  });
});
