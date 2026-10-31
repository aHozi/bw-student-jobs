import { FormEvent, useEffect, useState } from 'react';
import { api, date, Doc, label } from '../api';
import { ErrorBox, Section } from '../components/ui';
import DocEditor from '../components/DocEditor';

const KINDS = ['CV', 'COVER_LETTER', 'TRANSCRIPT', 'CERTIFICATE', 'OTHER'];

export default function Documents() {
  const [docs, setDocs] = useState<Doc[]>([]);
  const [kind, setKind] = useState('CV');
  const [title, setTitle] = useState('');
  const [file, setFile] = useState<File>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<Doc>();

  const load = () => api.get<Doc[]>('/documents').then(setDocs);
  useEffect(() => { load(); }, []);

  const upload = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setBusy(true); setError('');
    const form = new FormData();
    form.append('file', file); form.append('kind', kind); if (title) form.append('title', title);
    try {
      await api.upload('/documents', form);
      setFile(undefined); setTitle(''); (e.target as HTMLFormElement).reset();
      await load();
    } catch (err: any) { setError(err.message); } finally { setBusy(false); }
  };

  const open = async (d: Doc) => window.open(await api.blobUrl(`/documents/${d.id}/download?inline=true`), '_blank');
  const remove = async (d: Doc) => { if (confirm(`Delete "${d.title}"?`)) { await api.del(`/documents/${d.id}`); load(); } };

  return (
    <div className="page grid">
      <h1>Documents</h1>
      <Section title="Upload">
        <form className="stack" onSubmit={upload}>
          <div className="two">
            <label className="field"><span>Type</span>
              <select value={kind} onChange={e => setKind(e.target.value)}>{KINDS.map(k => <option key={k} value={k}>{label(k)}</option>)}</select></label>
            <label className="field"><span>Title (optional)</span><input value={title} onChange={e => setTitle(e.target.value)} placeholder="e.g. CV German 2026" /></label>
          </div>
          <input type="file" accept=".pdf,.docx,.doc,.txt,.png,.jpg,.jpeg" onChange={e => setFile(e.target.files?.[0])} />
          <p className="muted small">PDF, DOCX, DOC, TXT or images, max 10 MB. Files are stored encrypted. CVs must contain selectable text (not a scan).</p>
          <ErrorBox error={error} />
          <div><button className="primary" disabled={!file || busy}>{busy ? 'Uploading…' : 'Upload'}</button></div>
        </form>
      </Section>

      <Section title="My documents">
        <div className="table-wrap">
          <table>
            <thead><tr><th>Title</th><th>Type</th><th>Source</th><th>Date</th><th /></tr></thead>
            <tbody>
              {docs.map(d => (
                <tr key={d.id}>
                  <td>{d.title}<div className="muted small">{d.filename} · {(d.sizeBytes / 1024).toFixed(0)} KB</div></td>
                  <td>{label(d.kind)}</td>
                  <td>{d.original ? 'Uploaded' : <span>Generated{d.jobId && <> for <a href={`/jobs/${d.jobId}`}>job</a></>}</span>}</td>
                  <td>{date(d.createdAt)}</td>
                  <td className="row">
                    <button onClick={() => open(d)}>View</button>
                    {!d.original && d.contentJson && <button onClick={() => setEditing(d)}>Edit</button>}
                    <button className="danger" onClick={() => remove(d)}>Delete</button>
                  </td>
                </tr>
              ))}
              {!docs.length && <tr><td colSpan={5} className="muted">No documents yet. Start by uploading your CV.</td></tr>}
            </tbody>
          </table>
        </div>
      </Section>
      {editing && <DocEditor doc={editing} onClose={() => setEditing(undefined)} onSaved={() => { setEditing(undefined); load(); }} />}
    </div>
  );
}
