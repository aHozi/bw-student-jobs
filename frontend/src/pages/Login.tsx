import { FormEvent, useState } from 'react';
import { api, auth, User } from '../api';
import { useUser } from '../App';

export default function Login() {
  const { setUser } = useUser();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState('');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      const res = await api.post<{ token: string; user: User }>(`/auth/${mode}`,
        mode === 'login' ? { email, password } : { email, password, displayName });
      auth.set(res.token);
      setUser(res.user);
    } catch (err: any) {
      setError(err.message);
    }
  };

  return (
    <div className="auth card stack">
      <h1>BW Student Jobs</h1>
      <p className="muted">Werkstudent, internship and thesis jobs in IT in Baden-Württemberg — from your LinkedIn, StepStone and Indeed job alerts and the Bundesagentur für Arbeit, in one place.</p>
      <form className="stack" onSubmit={submit}>
        {mode === 'register' && (
          <label className="field"><span>Name</span><input value={displayName} onChange={e => setDisplayName(e.target.value)} /></label>
        )}
        <label className="field"><span>E-mail</span><input type="email" required value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label className="field"><span>Password {mode === 'register' && '(min. 8 characters)'}</span>
          <input type="password" required minLength={mode === 'register' ? 8 : 1} value={password} onChange={e => setPassword(e.target.value)} /></label>
        {error && <div className="error">{error}</div>}
        <button className="primary" type="submit">{mode === 'login' ? 'Log in' : 'Create account'}</button>
      </form>
      <button onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
        {mode === 'login' ? 'No account yet? Register' : 'Have an account? Log in'}
      </button>
    </div>
  );
}
