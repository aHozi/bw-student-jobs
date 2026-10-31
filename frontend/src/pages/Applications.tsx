import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApplicationRow, date, label } from '../api';

const GROUPS: { title: string; statuses: string[] }[] = [
  { title: 'Needs your action', statuses: ['DRAFT', 'READY_FOR_REVIEW', 'MANUAL_REQUIRED', 'FAILED'] },
  { title: 'In progress', statuses: ['PREPARING', 'APPROVED', 'SUBMITTING'] },
  { title: 'Applied', statuses: ['SUBMITTED', 'INTERVIEW', 'OFFER'] },
  { title: 'Closed', statuses: ['REJECTED', 'WITHDRAWN'] },
];

export default function Applications() {
  const [rows, setRows] = useState<ApplicationRow[]>([]);
  useEffect(() => {
    const load = () => api.get<ApplicationRow[]>('/applications').then(setRows);
    load();
    const t = setInterval(load, 8000);
    return () => clearInterval(t);
  }, []);

  return (
    <div className="page grid">
      <h1>Applications</h1>
      {GROUPS.map(g => {
        const list = rows.filter(r => g.statuses.includes(r.application.status));
        if (!list.length) return null;
        return (
          <section key={g.title} className="card">
            <h2 style={{ marginTop: 0 }}>{g.title} ({list.length})</h2>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Job</th><th>Company</th><th>Status</th><th>Documents</th><th>Updated</th><th>Follow-up</th></tr></thead>
                <tbody>
                  {list.map(({ application: a, jobTitle, companyName, jobStatus }) => (
                    <tr key={a.id}>
                      <td><Link to={`/applications/${a.id}`}>{jobTitle}</Link>{jobStatus === 'CLOSED' && <div className="muted small">posting closed</div>}</td>
                      <td>{companyName}</td>
                      <td className={`status-${a.status}`}>{label(a.status)}</td>
                      <td className="small">{a.usedOriginalDocs ? 'Original' : 'Tailored'}</td>
                      <td className="small">{date(a.updatedAt)}</td>
                      <td className="small">{date(a.followUpOn)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}
      {!rows.length && <div className="card muted">No applications yet. Open a job and click “Prepare application”.</div>}
    </div>
  );
}
