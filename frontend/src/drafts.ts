type EditorIntent = { problemId: string; context: string; code: string; returnTo: string };
const memory = new Map<string, string>();
let pending: EditorIntent | null = null;
const key = (userId: string | null, problemId: string, context: string) => 'gpu-forces:draft:' + JSON.stringify([userId || 'guest', problemId, context]);

export function readDraft(userId: string | null, problemId: string, context: string, starterCode: string) {
  const id = key(userId, problemId, context);
  if (memory.has(id)) return memory.get(id)!;
  try { return localStorage.getItem(id) ?? starterCode; } catch { return starterCode; }
}

export function writeDraft(userId: string | null, problemId: string, context: string, code: string) {
  const id = key(userId, problemId, context);
  try {
    localStorage.setItem(id, code);
    memory.delete(id);
    return true;
  } catch {
    memory.set(id, code);
    return false;
  }
}

export function rememberEditorLogin(problemId: string, context: string, code: string, returnTo: string) {
  pending = { problemId, context, code, returnTo };
  try { sessionStorage.setItem('gpu-forces:editor-login', JSON.stringify(pending)); } catch { return; }
}

export function completeEditorLogin(userId: string, returnTo: string) {
  let intent = pending;
  try {
    const stored = sessionStorage.getItem('gpu-forces:editor-login');
    if (!intent && stored) intent = JSON.parse(stored) as EditorIntent;
    sessionStorage.removeItem('gpu-forces:editor-login');
  } catch { intent = pending; }
  pending = null;
  if (intent?.returnTo === returnTo && typeof intent.code === 'string') writeDraft(userId, intent.problemId, intent.context, intent.code);
}
