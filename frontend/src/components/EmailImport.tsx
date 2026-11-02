import { useEffect, useState } from 'react';
import { api, date } from '../api';
import { ErrorBox, Section } from './ui';

type View = { connected: boolean; host?: string; port?: number; username?: string; folder: string; enabled: boolean;
  lastSyncAt?: string; lastError?: string; featureEnabled: boolean };

const PRESETS: Record<string, { host: string; port: number }> = {
  gmail: { host: 'imap.gmail.com', port: 993 }, gmx: { host: 'imap.gmx.net', port: 993 },
  webde: { host: 'imap.web.de', port: 993 }, yahoo: { host: 'imap.mail.yahoo.com', port: 993 },
};

/** Connect the user's mailbox so their LinkedIn / StepStone / Indeed job-alert e-mails become jobs here. */
export default function EmailImport() {
  const [v, setV] = useState<View>();
  const [form, setForm] = useState({ host: 'imap.gmail.com', port: 993, username: '', password: '', folder: 'INBOX', enabled: true });
  const [msg, setMsg] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState('');

  const load = async () => {
    const view = await api.get<View>('/email-import');
    setV(view);
    if (view.connected) setForm(f => ({ ...f, host: view.host!, port: view.port!, username: view.username!, folder: view.folder, enabled: view.enabled, password: '' }));
  };
  useEffect(() => { load(); }, []);
  if (!v) return null;

  const run = async (name: string, fn: () => Promise<void>) => {
    setBusy(name); setError(''); setMsg('');
    try { await fn(); } catch (e: any) { setError(e.message); } finally { setBusy(''); }
  };
  const body = () => ({ ...form, password: form.password || null });
  const test = () => run('test', async () => {
    const r = await api.post<{ ok: boolean; message: string }>('/email-import/test', body());
    if (r.ok) setMsg(r.message); else setError(r.message);
  });
  const save = () => run('save', async () => { await api.put('/email-import', body()); await load(); setMsg('Saved ✓ — new alert e-mails are imported every 30 minutes.'); });
  const importNow = () => run('import', async () => {
    const r = await api.post<{ alertMessages: number; jobsFound: number; accepted: number; newJobs: number }>('/email-import/run');
    setMsg(`Read ${r.alertMessages} new alert e-mail(s), found ${r.jobsFound} job(s), ${r.accepted} student IT job(s), ${r.newJobs} new.`);
    await load();
  });
  const disconnect = () => run('del', async () => { await api.del('/email-import'); await load(); setMsg('Disconnected.'); });

  return (
    <Section title="Job-alert e-mails (LinkedIn, StepStone, Indeed)">
      {!v.featureEnabled && <div className="notice small">E-mail import is disabled on this server.</div>}
      <details>
        <summary className="small">How to set this up (5 minutes)</summary>
        <ol className="small">
          <li><b>LinkedIn</b>: search “Werkstudent Informatik” in Jobs, location “Baden-Württemberg”, then switch on <i>Job alert</i>.</li>
          <li><b>StepStone</b>: run the same search and click <i>Jobagent erstellen</i>.</li>
          <li><b>Indeed</b>: run the search and click <i>Jobalert erhalten / Get new jobs by e-mail</i>.</li>
          <li>Create more alerts for “Praktikum Informatik”, “Abschlussarbeit Informatik”, “Working Student Software” etc.</li>
          <li><b>Gmail</b>: turn on 2-step verification, then create an <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noreferrer">app password</a> and paste it below. GMX/web.de: enable IMAP in the mail settings. Outlook/Microsoft 365 no longer allow IMAP passwords — forward the alerts to a Gmail/GMX address instead.</li>
        </ol>
        <p className="muted small">The mailbox is opened read-only. Only e-mails from LinkedIn, StepStone and Indeed are read; nothing else is stored. Jobs from your alerts are only visible to you. The password is stored encrypted.</p>
      </details>
      <div className="row small">
        Provider:
        {Object.entries(PRESETS).map(([k, p]) => <button key={k} onClick={() => setForm({ ...form, ...p })}>{k}</button>)}
      </div>
      <div className="two">
        <label className="field"><span>IMAP server</span><input value={form.host} onChange={e => setForm({ ...form, host: e.target.value })} /></label>
        <label className="field"><span>Port</span><input type="number" value={form.port} onChange={e => setForm({ ...form, port: Number(e.target.value) })} /></label>
        <label className="field"><span>E-mail address / user name</span><input value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} /></label>
        <label className="field"><span>{v.connected ? 'App password (leave empty to keep)' : 'App password'}</span>
          <input type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} /></label>
        <label className="field"><span>Folder</span><input value={form.folder} onChange={e => setForm({ ...form, folder: e.target.value })} /></label>
        <label className="check" style={{ alignSelf: 'end' }}><input type="checkbox" checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} /> Import automatically</label>
      </div>
      {v.connected && <p className="muted small">Last import: {v.lastSyncAt ? new Date(v.lastSyncAt).toLocaleString('de-DE') : 'not yet'}{v.lastError && <span className="bad"> — {v.lastError}</span>}</p>}
      {msg && <div className="notice small">{msg}</div>}
      <ErrorBox error={error} />
      <div className="row">
        <button disabled={!!busy || !form.username} onClick={test}>{busy === 'test' ? 'Testing…' : 'Test connection'}</button>
        <button className="primary" disabled={!!busy || !form.username} onClick={save}>{busy === 'save' ? 'Saving…' : 'Save'}</button>
        {v.connected && <button disabled={!!busy} onClick={importNow}>{busy === 'import' ? 'Importing…' : 'Import now'}</button>}
        {v.connected && <button className="danger" disabled={!!busy} onClick={disconnect}>Disconnect</button>}
      </div>
      {v.lastSyncAt && <p className="muted small">Connected since {date(v.lastSyncAt)}.</p>}
    </Section>
  );
}
