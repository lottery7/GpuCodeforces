import { createContext, useCallback, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { ApiError } from './api/client';
import { useSession } from './auth';
import { completeEditorLogin } from './drafts';
import { ErrorMessage } from './ui';
import { safeReturnTo } from './utils';

export type AuthMode = 'login' | 'register';

type OpenAuth = (mode?: AuthMode, returnTo?: string) => void;
export const AuthDialogContext = createContext<OpenAuth>(() => {});
export const useAuthDialog = () => useContext(AuthDialogContext);

export function AuthForm({ mode, returnTo, onClose }: { mode: AuthMode; returnTo: string; onClose: () => void }) {
  const session = useSession();
  const navigate = useNavigate();
  const [current, setCurrent] = useState<AuthMode>(mode);
  const [identifier, setIdentifier] = useState('');
  const [handle, setHandle] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => { setPassword(''); setError(null); }, [current]);

  function switchMode(next: AuthMode) {
    if (next === current || busy) return;
    setCurrent(next);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setError(null);
    setBusy(true);
    try {
      const user = current === 'login' ? await session.login({ identifier: identifier.trim(), password }) : await session.register({ handle: handle.trim(), email: email.trim(), password });
      setPassword('');
      completeEditorLogin(user.id, returnTo);
      onClose();
      navigate(returnTo, { replace: true });
    } catch (reason) {
      setError(current === 'login' && reason instanceof ApiError && reason.status === 401 ? new Error('Неверный ник, почта или пароль.') : reason);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="auth-dialog-head"><h2>Аккаунт</h2><button type="button" className="auth-close" aria-label="Закрыть" onClick={onClose}>×</button></div>
    <nav className="tabs auth-tabs" aria-label="Доступ к аккаунту">{(['login', 'register'] as const).map(id => <button key={id} type="button" className={`tab${current === id ? ' active' : ''}`} aria-current={current === id ? 'true' : undefined} onClick={() => switchMode(id)}>{id === 'login' ? 'Вход' : 'Регистрация'}</button>)}</nav>
    <form className="form" onSubmit={event => void submit(event)}>
      {current === 'login' ? <label className="field"><span>Ник или почта</span><input name="identifier" required autoComplete="username" value={identifier} disabled={busy} autoFocus onChange={event => setIdentifier(event.target.value)} /></label> : <>
        <label className="field"><span>Ник участника</span><input name="handle" required minLength={3} maxLength={24} pattern="[A-Za-z0-9_]{3,24}" autoComplete="username" aria-describedby="handle-hint" value={handle} disabled={busy} onChange={event => setHandle(event.target.value)} /><span id="handle-hint" className="small muted">От 3 до 24 символов: латинские буквы, цифры и _.</span></label>
        <label className="field"><span>Почта</span><input name="email" type="email" required autoComplete="email" value={email} disabled={busy} onChange={event => setEmail(event.target.value)} /></label>
      </>}
      <label className="field"><span>Пароль</span><input name="password" type="password" required minLength={current === 'register' ? 8 : undefined} autoComplete={current === 'register' ? 'new-password' : 'current-password'} aria-describedby={current === 'register' ? 'password-hint' : undefined} value={password} disabled={busy} onChange={event => setPassword(event.target.value)} />{current === 'register' && <span id="password-hint" className="small muted">Не меньше 8 символов.</span>}</label>
      <ErrorMessage error={error} reveal />
      <button className="btn primary" type="submit" disabled={busy}>{busy ? current === 'login' ? 'Вход...' : 'Регистрация...' : current === 'login' ? 'Войти' : 'Зарегистрироваться'}</button>
    </form>
  </>;
}

export function AuthDialogProvider({ children }: { children: ReactNode }) {
  const session = useSession();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [state, setState] = useState<{ mode: AuthMode; returnTo: string; n: number }>({ mode: 'login', returnTo: '/profile', n: 0 });

  const close = useCallback(() => {
    dialogRef.current?.close();
  }, []);

  const openAuth = useCallback((mode: AuthMode = 'login', returnTo?: string) => {
    setState(prev => ({ mode, returnTo: safeReturnTo(returnTo ?? '/profile'), n: prev.n + 1 }));
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (state.n > 0 && !dialog.open) dialog.showModal();
  }, [state.n]);

  useEffect(() => {
    if (session.user) close();
  }, [session.user, close]);

  return <AuthDialogContext.Provider value={openAuth}>
    {children}
    <dialog key={state.n} ref={dialogRef} className="auth-dialog">
      {session.isLoading ? <div className="auth-dialog-head"><h2>Аккаунт</h2><button type="button" className="auth-close" aria-label="Закрыть" onClick={close}>×</button></div>
        : <AuthForm mode={state.mode} returnTo={state.returnTo} onClose={close} />}
    </dialog>
  </AuthDialogContext.Provider>;
}

export function AuthLanding({ mode }: { mode: AuthMode }) {
  const openAuth = useAuthDialog();
  const session = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const returnTo = params.get('returnTo');
  const signedIn = Boolean(session.user);
  useEffect(() => {
    if (session.isLoading) return;
    // nothing to sign in to: the link is followed to where it leads
    if (signedIn) navigate(returnTo ? safeReturnTo(returnTo) : '/', { replace: true });
    else openAuth(mode, returnTo || undefined);
  }, [openAuth, navigate, mode, returnTo, signedIn, session.isLoading]);
  return null;
}