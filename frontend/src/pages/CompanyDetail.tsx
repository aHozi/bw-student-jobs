import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, Company, date, JobPosting } from '../api';
import { useUser } from '../App';
import { Chips, LanguageBadges, Section } from '../components/ui';

type RunLog = { id: number; adapter: string; startedAt: string; listed: number; accepted: number; newJobs: number; closedJobs: number; error?: string };
type Detail = { company: Company; openJobs: JobPosting[]; recentScans: RunLog[] };

export default function CompanyDetailPage() {
  const { id } = useParams();
  const { user } = useUser();
  const [d, setD] = useState<Detail>();
  const [msg, setMsg] = useState('');
  const load = () => api.get<Detail>(`/companies/${id}`).then(setD);
  useEffect(() => { load(); }, [id]);
  if (!d) return <div className="page muted">Loading…</div>;
  const c = d.company;
  const admin = async (action: 'scan' | 'discover') => {
    const r = await api.post<{ queued: boolean }>(`/admin/companies/${c.id}/${action}`);
    setMsg(r.queued ? `${action === 'scan' ? 'Scan' : 'Discovery'} queued.` : 'A task is already queued for this company.');
  };

  return (
    <div className="page grid">
      <Section title={c.name} actions={user?.role === 'ADMIN' && (
        <div className="row"><button onClick={() => admin('scan')}>Scan now</button><button onClick={() => admin('discover')}>Re-discover career page</button></div>)}>
        <div className="small stack">
          <div>{c.industry} · {c.hqCity} · status <b>{c.status}</b>{c.statusMessage && <span className="muted"> — {c.statusMessage}</span>}</div>
          {c.website && <div>Website: <a href={c.website} target="_blank" rel="noreferrer">{c.website}</a></div>}
          <div>Career page: {c.careerUrl ? <a href={c.careerUrl} target="_blank" rel="noreferrer">{c.careerUrl}</a> : <span className="muted">not discovered yet</span>} ({c.atsType})</div>
          <div className="muted">Last scan: {date(c.lastScannedAt) || 'never'} · scanned every {c.scanIntervalHours} h</div>
          {msg && <div className="notice">{msg}</div>}
        </div>
      </Section>
      <Section title={`Open student jobs (${d.openJobs.length})`}>
        {d.openJobs.map(j => (
          <div key={j.id}><Link to={`/jobs/${j.id}`}>{j.title}</Link> <span className="muted small">{j.locations.join('; ')}</span>
            <div className="row small"><Chips values={j.studentTypes} /><LanguageBadges german={j.germanLevel} english={j.englishLevel} /></div></div>
        ))}
        {!d.openJobs.length && <p className="muted">No open student IT jobs right now.</p>}
      </Section>
      {d.recentScans.length > 0 && (
        <Section title="Recent scans">
          <div className="table-wrap"><table>
            <thead><tr><th>Date</th><th>Method</th><th>Listed</th><th>Student IT</th><th>New</th><th>Closed</th><th>Error</th></tr></thead>
            <tbody>{d.recentScans.map(r => (
              <tr key={r.id}><td>{new Date(r.startedAt).toLocaleString('de-DE')}</td><td>{r.adapter}</td><td>{r.listed}</td>
                <td>{r.accepted}</td><td>{r.newJobs}</td><td>{r.closedJobs}</td><td className="bad small">{r.error}</td></tr>))}</tbody>
          </table></div>
        </Section>
      )}
    </div>
  );
}
