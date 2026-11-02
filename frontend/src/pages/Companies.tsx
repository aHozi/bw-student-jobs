import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, Company, qs } from '../api';

type Row = { company: Company; openJobs: number };

export default function Companies() {
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);

  useEffect(() => {
    const t = setTimeout(() => api.get<{ items: Row[]; total: number }>('/companies' + qs({ q, page, size: 50 }))
      .then(r => { setRows(r.items); setTotal(r.total); }), 250);
    return () => clearTimeout(t);
  }, [q, page]);

  return (
    <div className="page grid">
      <div className="row between"><h1 style={{ margin: 0 }}>{total} companies</h1>
        <input style={{ maxWidth: 320 }} placeholder="Search name, city, industry" value={q} onChange={e => { setPage(0); setQ(e.target.value); }} /></div>
      <p className="muted small">Employers are added automatically from the imported jobs.</p>
      <div className="card table-wrap">
        <table>
          <thead><tr><th>Company</th><th>City</th><th>Industry</th><th>Open student jobs</th></tr></thead>
          <tbody>
            {rows.map(({ company: c, openJobs }) => (
              <tr key={c.id}>
                <td><Link to={`/companies/${c.id}`}>{c.name}</Link>{c.status !== 'ACTIVE' && <div className="warn small">{c.status}</div>}</td>
                <td>{c.hqCity}</td><td className="small">{c.industry}</td><td>{openJobs}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {total > 50 && (
        <div className="row"><button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <button disabled={(page + 1) * 50 >= total} onClick={() => setPage(page + 1)}>Next</button></div>
      )}
    </div>
  );
}
