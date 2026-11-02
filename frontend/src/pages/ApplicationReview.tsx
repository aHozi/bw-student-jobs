import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, Application, applyPlatform, date, Doc, JobDetail, label } from '../api';
import { ErrorBox, Section } from '../components/ui';

export default function ApplicationReview() {
  const { id } = useParams();
  const [a, setA] = useState<Application>();
  const [job, setJob] = useState<JobDetail>();
  const [docs, setDocs] = useState<Doc[]>([]);
  const [shot, setShot] = useState<string>();
  const [confirmShot, setConfirmShot] = useState<string>();
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [consents, setConsents] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState('');
  const [notes, setNotes] = useState('');
  const [followUp, setFollowUp] = useState('');

  const load = async () => {
    const app = await api.get<Application>(`/applications/${id}`);
    setA(app);
    setNotes(n => n || app.notes || '');
    setFollowUp(f => f || app.followUpOn || '');
    if (!job || job.job.id !== app.jobId) setJob(await api.get<JobDetail>(`/jobs/${app.jobId}`));
    if (app.hasScreenshot) setShot(await api.blobUrl(`/applications/${id}/screenshot?which=review`));
    if (app.hasConfirmation) setConfirmShot(await api.blobUrl(`/applications/${id}/screenshot?which=confirmation`));
    return app;
  };

  useEffect(() => {
    api.get<Doc[]>('/documents').then(setDocs);
    load().catch(e => setError(e.message));
  }, [id]);

  // Poll while the browser is working in the background
  useEffect(() => {
    if (!a || !['PREPARING', 'APPROVED', 'SUBMITTING'].includes(a.status)) return;
    const t = setInterval(() => load().catch(() => {}), 3000);
    return () => clearInterval(t);
  }, [a?.status]);

  if (!a || !job) return <div className="page muted">{error || 'Loading…'}</div>;
  const r = a.fillReport;
  const board = applyPlatform(job.job);
  const docName = (docId?: number) => docs.find(d => d.id === docId)?.title ?? '—';
  const needs = r?.fields.filter(f => f.status === 'NEEDS_INPUT') ?? [];
  const consentFields = r?.fields.filter(f => f.status === 'CONSENT') ?? [];
  const missingRequired = needs.filter(f => f.required && !overrides[f.key]?.trim()).length
    + consentFields.filter(f => f.required && !consents.includes(f.key)).length;

  const act = async (fn: () => Promise<unknown>) => {
    setError('');
    try { await fn(); await load(); } catch (e: any) { setError(e.message); }
  };
  const approve = () => act(() => api.post(`/applications/${a.id}/approve`, { confirm: true, overrides, consents }));
  const track = (status?: string) => act(() => api.patch(`/applications/${a.id}`, { status, notes, followUpOn: followUp || null }));

  return (
    <div className="page grid">
      <div className="card stack">
        <div className="row between">
          <div>
            <h1 style={{ marginBottom: '.25rem' }}>{job.job.title}</h1>
            <div className="muted">{job.company.name} · <Link to={`/jobs/${job.job.id}`}>job details</Link> · <a href={job.job.applyUrl ?? job.job.url} target="_blank" rel="noreferrer">{board ? `posting on ${board} ↗` : 'original posting ↗'}</a></div>
          </div>
          <div className={`badge status-${a.status}`} style={{ fontSize: '1rem' }}>{label(a.status)}</div>
        </div>
        <div className="small">CV: <b>{docName(a.cvDocumentId)}</b> · Cover letter: <b>{a.coverLetterId ? docName(a.coverLetterId) : 'none'}</b>
          {a.extraDocumentIds.length > 0 && <> · Attachments: {a.extraDocumentIds.map(docName).join(', ')}</>} · {a.usedOriginalDocs ? 'original documents' : 'tailored documents'}</div>
        {a.submittedAt && <div className="ok">Submitted on {date(a.submittedAt)}</div>}
        {a.errorMessage && <div className={a.status === 'FAILED' ? 'error' : 'notice'}>{a.errorMessage}</div>}
      </div>

      {a.status === 'DRAFT' && (
        <Section title="Next step"><button className="primary" onClick={() => act(() => api.post(`/applications/${a.id}/prepare`))}>Fill the application form</button></Section>
      )}
      {['PREPARING', 'SUBMITTING', 'APPROVED'].includes(a.status) && (
        <div className="card notice">{a.status === 'PREPARING' ? 'Opening the company form and filling it in… (this takes up to a minute)' : 'Submitting your application…'}</div>
      )}

      {a.status === 'READY_FOR_REVIEW' && r && (
        <Section title="Review before sending">
          <p className="small">{r.message} Check every value below and the screenshot of the filled form. <b>Nothing has been sent yet.</b></p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Field</th><th>Value</th><th>Status</th></tr></thead>
              <tbody>
                {r.fields.map(f => (
                  <tr key={f.key}>
                    <td>{(f.label || f.key).replace(/\s*\*\s*$/, '')}{f.required && <span className="bad"> *</span>}<div className="muted small">{f.type}</div></td>
                    <td>
                      {f.status === 'NEEDS_INPUT' ? (
                        f.options && f.options.length ? (
                          <select value={overrides[f.key] ?? ''} onChange={e => setOverrides({ ...overrides, [f.key]: e.target.value })}>
                            <option value="">Choose…</option>{f.options.map(o => <option key={o} value={o}>{o}</option>)}
                          </select>
                        ) : <input value={overrides[f.key] ?? ''} onChange={e => setOverrides({ ...overrides, [f.key]: e.target.value })} />
                      ) : f.status === 'CONSENT' ? (
                        <label className="check small"><input type="checkbox" checked={consents.includes(f.key)}
                          onChange={e => setConsents(e.target.checked ? [...consents, f.key] : consents.filter(c => c !== f.key))} />
                          I have read and accept this</label>
                      ) : <span className="small">{f.value ?? '—'}</span>}
                    </td>
                    <td className="small">{f.status === 'FILLED' || f.status === 'FILE' ? <span className="ok">filled</span> : f.status === 'SKIPPED' ? <span className="muted">left empty</span> : <span className="warn">your input</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <label className="check"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />
            I checked the data and want to send this application to {job.company.name} now.</label>
          <ErrorBox error={error} />
          <div className="row">
            <button className="primary" disabled={!confirmed || missingRequired > 0} onClick={approve}>Approve and submit</button>
            <button onClick={() => act(() => api.post(`/applications/${a.id}/prepare`))}>Fill again</button>
            <a className="btn" href={r.url} target="_blank" rel="noreferrer">Open form myself ↗</a>
            {missingRequired > 0 && <span className="warn small">{missingRequired} required field(s) still need your input</span>}
          </div>
        </Section>
      )}

      {a.status === 'MANUAL_REQUIRED' && (
        <Section title={a.mode === 'EMAIL' ? 'Apply by e-mail' : 'Apply manually'}>
          <p>{r?.message ?? a.manualReason}</p>
          {r?.emailDraft ? (
            <div className="stack small">
              <div><b>To:</b> {r.emailDraft.to}</div>
              <div><b>Subject:</b> {r.emailDraft.subject}</div>
              <pre style={{ whiteSpace: 'pre-wrap' }}>{r.emailDraft.body}</pre>
              <div className="row">
                <a className="btn primary" href={`mailto:${r.emailDraft.to}?subject=${encodeURIComponent(r.emailDraft.subject)}&body=${encodeURIComponent(r.emailDraft.body)}`}>Open in my e-mail app</a>
                {r.emailDraft.attachmentDocumentIds.map(d => (
                  <button key={d} onClick={async () => window.open(await api.blobUrl(`/documents/${d}/download`), '_blank')}>Download {docName(d)}</button>
                ))}
              </div>
              <p className="muted">Attach the documents yourself — the platform never sends e-mails on your behalf.</p>
            </div>
          ) : (
            <div className="row">
              <a className="btn primary" href={r?.url ?? job.job.url} target="_blank" rel="noreferrer">{board ? `Apply on ${board} ↗` : 'Open application page ↗'}</a>
              {[a.cvDocumentId, a.coverLetterId, ...a.extraDocumentIds].filter(Boolean).map(d => (
                <button key={d} onClick={async () => window.open(await api.blobUrl(`/documents/${d}/download`), '_blank')}>Download {docName(d!)}</button>
              ))}
            </div>
          )}
          <div className="row"><button onClick={() => act(() => api.post(`/applications/${a.id}/mark-submitted`))}>I have applied — mark as submitted</button>
            {!board && <button onClick={() => act(() => api.post(`/applications/${a.id}/prepare`))}>Try automatic filling again</button>}</div>
          <ErrorBox error={error} />
        </Section>
      )}

      {a.status === 'FAILED' && (
        <Section title="Submission failed">
          <div className="row"><button onClick={() => act(() => api.post(`/applications/${a.id}/prepare`))}>Fill the form again</button>
            <a className="btn" href={job.job.applyUrl ?? job.job.url} target="_blank" rel="noreferrer">Apply on the original page ↗</a>
            <button onClick={() => act(() => api.post(`/applications/${a.id}/mark-submitted`))}>I applied myself — mark as submitted</button></div>
          <ErrorBox error={error} />
        </Section>
      )}

      {['SUBMITTED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN'].includes(a.status) && (
        <Section title="Tracking">
          <div className="two">
            <label className="field"><span>Notes</span><textarea value={notes} onChange={e => setNotes(e.target.value)} /></label>
            <label className="field"><span>Follow-up reminder</span><input type="date" value={followUp} onChange={e => setFollowUp(e.target.value)} /></label>
          </div>
          <div className="row">
            <button onClick={() => track()}>Save notes</button>
            {['SUBMITTED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN'].filter(s => s !== a.status).map(s => (
              <button key={s} onClick={() => track(s)}>Mark as {label(s)}</button>
            ))}
          </div>
          <ErrorBox error={error} />
        </Section>
      )}

      {(confirmShot || shot) && (
        <Section title={confirmShot ? 'Screenshot after submitting' : 'Screenshot of the filled form'}>
          <img className="screenshot" src={confirmShot ?? shot} alt="Application form screenshot" />
        </Section>
      )}
    </div>
  );
}
