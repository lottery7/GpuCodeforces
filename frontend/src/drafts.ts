/** Where a draft belongs: the user's code of one problem, in one context, for one processor and language. */
export type DraftTarget = { problemId: string; context: string; processor: string; language: string };
type EditorIntent = DraftTarget & { code: string; returnTo: string };
/** An intent stored before processors existed carries no processor and language. */
type StoredIntent = Omit<EditorIntent, 'processor' | 'language'> & Partial<Pick<EditorIntent, 'processor' | 'language'>>;
const memory = new Map<string, string>();
let pending: EditorIntent | null = null;

const key = (userId: string | null, target: DraftTarget) =>
  'gpu-forces:draft:' + JSON.stringify([userId || 'guest', target.problemId, target.context, target.processor, target.language]);

/** Drafts saved before processors existed were CUDA code for the real GPU. */
const legacyKey = (userId: string | null, target: DraftTarget) =>
  target.processor === 'gpu-cuda' && target.language === 'cuda'
    ? 'gpu-forces:draft:' + JSON.stringify([userId || 'guest', target.problemId, target.context])
    : null;

export function readDraft(userId: string | null, target: DraftTarget, starterCode: string) {
  const id = key(userId, target);
  if (memory.has(id)) return memory.get(id)!;
  try {
    const legacy = legacyKey(userId, target);
    return localStorage.getItem(id) ?? (legacy ? localStorage.getItem(legacy) : null) ?? starterCode;
  } catch { return starterCode; }
}

export function writeDraft(userId: string | null, target: DraftTarget, code: string) {
  const id = key(userId, target);
  try {
    localStorage.setItem(id, code);
    memory.delete(id);
    return true;
  } catch {
    memory.set(id, code);
    return false;
  }
}

export function rememberEditorLogin(target: DraftTarget, code: string, returnTo: string) {
  pending = { ...target, code, returnTo };
  try { sessionStorage.setItem('gpu-forces:editor-login', JSON.stringify(pending)); } catch { return; }
}

export function completeEditorLogin(userId: string, returnTo: string) {
  let intent: StoredIntent | null = pending;
  try {
    const stored = sessionStorage.getItem('gpu-forces:editor-login');
    if (!intent && stored) intent = JSON.parse(stored) as StoredIntent;
    sessionStorage.removeItem('gpu-forces:editor-login');
  } catch { intent = pending; }
  pending = null;
  if (intent?.returnTo === returnTo && typeof intent.code === 'string') {
    const target = { problemId: intent.problemId, context: intent.context, processor: intent.processor ?? 'gpu-cuda', language: intent.language ?? 'cuda' };
    writeDraft(userId, target, intent.code);
  }
}
