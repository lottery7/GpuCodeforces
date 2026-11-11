import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { ApiError } from '../api/client';
import { useSession } from '../auth';
import { completeEditorLogin } from '../drafts';
import { ErrorMessage, PageHeader, Tabs } from '../ui';
import { safeReturnTo } from '../utils';

export default function Auth({ mode }: { mode: 'login' | 'register' }) {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const session = useSession();
  const [identifier, setIdentifier] = useState('');
  const [handle, setHandle] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const returnTo = safeReturnTo(params.get('returnTo'));
  const suffix = `?returnTo=${encodeURIComponent(returnTo)}`;
  useEffect(() => { setPassword(''); setError(null); }, [mode]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setError(null);
    setBusy(true);
    try {
      const user = mode === 'login' ? await session.login({ identifier: identifier.trim(), password }) : await session.register({ handle: handle.trim(), email: email.trim(), password });
      setPassword('');
      completeEditorLogin(user.id, returnTo);
      navigate(returnTo, { replace: true });
    } catch (reason) {
      setError(mode === 'login' && reason instanceof ApiError && reason.status === 401 ? new Error('Неверный ник, почта или пароль.') : reason);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <PageHeader title="Аккаунт" />
    <div className="auth-form">
      <Tabs selected={mode} items={[{ id: 'login', label: 'Вход', to: `/login${suffix}` }, { id: 'register', label: 'Регистрация', to: `/register${suffix}` }]} />
      <form className="form" onSubmit={event => void submit(event)}>
        {mode === 'login' ? <label className="field"><span>Ник или почта</span><input name="identifier" required autoComplete="username" value={identifier} disabled={busy} onChange={event => setIdentifier(event.target.value)} /></label> : <>
          <label className="field"><span>Ник участника</span><input name="handle" required minLength={3} maxLength={24} pattern="[A-Za-z0-9_]{3,24}" autoComplete="username" aria-describedby="handle-hint" value={handle} disabled={busy} onChange={event => setHandle(event.target.value)} /><span id="handle-hint" className="small muted">От 3 до 24 символов: латинские буквы, цифры и _.</span></label>
          <label className="field"><span>Почта</span><input name="email" type="email" required autoComplete="email" value={email} disabled={busy} onChange={event => setEmail(event.target.value)} /></label>
        </>}
        <label className="field"><span>Пароль</span><input name="password" type="password" required minLength={mode === 'register' ? 8 : undefined} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} aria-describedby={mode === 'register' ? 'password-hint' : undefined} value={password} disabled={busy} onChange={event => setPassword(event.target.value)} />{mode === 'register' && <span id="password-hint" className="small muted">Не меньше 8 символов.</span>}</label>
        <ErrorMessage error={error} reveal />
        <button className="btn primary" type="submit" disabled={busy}>{busy ? mode === 'register' ? 'Регистрация...' : 'Вход...' : mode === 'register' ? 'Зарегистрироваться' : 'Войти'}</button>
      </form>
    </div>
  </>;
}
