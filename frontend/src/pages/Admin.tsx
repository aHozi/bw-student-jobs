import { FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, Company } from '../api';
import { ErrorBox, Section } from '../components/ui';

type RunLog = { id: number; companyId: number; companyName: string; adapter: string; startedAt: string; listed: number; accepted: number; newJobs: number; closedJobs: number; error?: string };
type SourceRun = { id: number; source: string; userId?: number; startedAt: string; fetched: number; accepted: number; newJobs: number; error?: string };
type Sources = { sources: { name: string; label: string; enabled: boolean; hint?: string }[]; openJobs: Record<string, number>; recentRuns: SourceRun[] };
type Health = { queue: Record<string, number>; companies: number; openJobs: number; broken: Company[]; needsReview: Company[]; recentRuns: RunLog[] };

export default function Admin() {
  const [h, setH] = useState<Health>();
  const [src, setSrc] = useState<Sources>();
  const [form, setForm] = useState({ name: '', website: '', careerUrl: '', city: '', industry: '' });
  const [error, setError] = useState('');
  const [msg, setMsg] = useState('');
  const load = () => {
    api.get<Sources>('/admin/sources').then(setSrc).catch(() => {});
    return api.get<Health>('/admin/scan-health').then(setH).catch(e => setError(e.message));
  };
  const runSource = async (name: string) => { await api.post(`/admin/sources/${name}/run`); setMsg(`${name} started — results appear below in a few minutes.`); };
  useEffect(() => { load(); const t = setInterval(load, 15000); return () => clearInterval(t); }, []);

  const add = async (e: FormEvent) => {
    e.preventDefault(); setError(''); setMsg('');
    try {
      const c = await api.post<Company>('/admin/companies', form);
      setMsg(`Added ${c.name}. ${c.atsType === 'UNKNOWN' ? 'Career page discovery is queued.' : 'First scan is queued.'}`);
      setForm({ name: '', website: '', careerUrl: '', city: '', industry: '' }); load();
    } catch (err: any) { setError(err.message); }
  };

  if (!h) return <div className="page muted">{error || 'Loading…'}</div>;
  const list = (cs: Company[]) => cs.map(c => <div key={c.id} className="small"><Link to={`/companies/${c.id}`}>{c.name}</Link> <span className="muted">— {c.statusMessage}</span></div>);

  return (
    <div className="page grid">
      <h1>Admin</h1>
      <div className="row">
        <div className="card"><b>{h.companies}</b> companies</div>
        <div className="card"><b>{h.openJobs}</b> open student jobs</div>
        {Object.entries(h.queue).map(([k, v]) => <div key={k} className="card"><b>{v}</b> tasks {k.toLowerCase()}</div>)}
      </div>
      {src && (
        <Section title="Job sources">
          <div className="table-wrap"><table>
            <thead><tr><th>Source</th><th>Status</th><th>Open jobs</th><th /></tr></thead>
            <tbody>{src.sources.map(s => (
              <tr key={s.name}><td>{s.label}</td><td className={s.enabled ? 'ok' : 'muted'}>{s.enabled ? 'on' : 'off'}{s.hint && <div className="muted small">{s.hint}</div>}</td>
                <td>{src.openJobs[s.name] ?? 0}</td>
                <td>{s.enabled && <button onClick={() => runSource(s.name)}>Run now</button>}</td></tr>))}</tbody>
          </table></div>
          {msg && <div className="notice">{msg}</div>}
          <h3>Recent runs</h3>
          <div className="table-wrap"><table>
            <thead><tr><th>Time</th><th>Source</th><th>Fetched</th><th>Student IT</th><th>New</th><th>Error</th></tr></thead>
            <tbody>{src.recentRuns.map(r => (
              <tr key={r.id}><td className="small">{new Date(r.startedAt).toLocaleString('de-DE')}</td><td className="small">{r.source}{r.userId ? ` (user ${r.userId})` : ''}</td>
                <td>{r.fetched}</td><td>{r.accepted}</td><td>{r.newJobs}</td><td className="bad small">{r.error}</td></tr>))}</tbody>
          </table></div>
        </Section>
      )}
      <Section title="Add a company">
        <form className="stack" onSubmit={add}>
          <div className="two">
            <label className="field"><span>Name *</span><input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
            <label className="field"><span>Website</span><input value={form.website} onChange={e => setForm({ ...form, website: e.target.value })} placeholder="https://www.example.de" /></label>
            <label className="field"><span>Career page (optional)</span><input value={form.careerUrl} onChange={e => setForm({ ...form, careerUrl: e.target.value })} placeholder="Official career page or ATS link" /></label>
            <label className="field"><span>City in BW</span><input value={form.city} onChange={e => setForm({ ...form, city: e.target.value })} /></label>
            <label className="field"><span>Industry</span><input value={form.industry} onChange={e => setForm({ ...form, industry: e.target.value })} /></label>
          </div>
          <p className="muted small">Career-page scanning is off by default (APP_SCAN_ENABLED=false). Companies added here are scanned only when it is turned on.</p>
          <ErrorBox error={error} />
          <div><button className="primary">Add company</button></div>
        </form>
      </Section>
      {h.broken.length > 0 && <Section title={`Broken (${h.broken.length})`}>{list(h.broken)}</Section>}
      {h.needsReview.length > 0 && <Section title={`Needs review (${h.needsReview.length})`}>{list(h.needsReview)}</Section>}
      <Section title="Recent career-page scans">
        <div className="table-wrap"><table>
          <thead><tr><th>Time</th><th>Company</th><th>Method</th><th>Listed</th><th>Student IT</th><th>New</th><th>Closed</th><th>Error</th></tr></thead>
          <tbody>{h.recentRuns.map(r => (
            <tr key={r.id}><td className="small">{new Date(r.startedAt).toLocaleString('de-DE')}</td><td><Link to={`/companies/${r.companyId}`}>{r.companyName}</Link></td>
              <td className="small">{r.adapter}</td><td>{r.listed}</td><td>{r.accepted}</td><td>{r.newJobs}</td><td>{r.closedJobs}</td><td className="bad small">{r.error}</td></tr>))}</tbody>
        </table></div>
      </Section>
    </div>
  );
}
