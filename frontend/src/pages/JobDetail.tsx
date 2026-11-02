import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AiMatch, api, Application, ApiError, applyPlatform, date, Doc, JobDetail, label, Profile } from '../api';
import { Chips, ErrorBox, LanguageBadges, PlatformBadges, Score, Section } from '../components/ui';
import DocEditor from '../components/DocEditor';

export default function JobDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const [d, setD] = useState<JobDetail>();
  const [docs, setDocs] = useState<Doc[]>([]);
  const [profile, setProfile] = useState<Profile>();
  const [ai, setAi] = useState<AiMatch>();
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [cvId, setCvId] = useState<number>();
  const [clId, setClId] = useState<number | ''>('');
  const [extras, setExtras] = useState<number[]>([]);
  const [lang, setLang] = useState('AUTO');
  const [notes, setNotes] = useState('');
  const [editing, setEditing] = useState<Doc>();
  const [similar, setSimilar] = useState<{ id: number; title: string; status: string }[]>();

  const load = async () => {
    const [detail, list, p] = await Promise.all([
      api.get<JobDetail>(`/jobs/${id}`), api.get<Doc[]>('/documents'), api.get<Profile>('/profile')]);
    setD(detail); setDocs(list); setProfile(p);
    if (detail.aiScore?.details) setAi(detail.aiScore.details);
    const originalCv = list.find(x => x.kind === 'CV' && x.original);
    setCvId(c => c ?? originalCv?.id);
  };
  useEffect(() => { load().catch(e => setError(e.message)); }, [id]);

  if (!d || !profile) return <div className="page muted">{error || 'Loading…'}</div>;
  const { job, company } = d;
  const cvs = docs.filter(x => x.kind === 'CV' && (x.original || x.jobId === job.id));
  const letters = docs.filter(x => x.kind === 'COVER_LETTER' && (x.original || x.jobId === job.id));
  const others = docs.filter(x => !['CV', 'COVER_LETTER'].includes(x.kind));
  const noConsent = !profile.aiConsent;
  const board = applyPlatform(job);

  const run = async (name: string, fn: () => Promise<void>) => {
    setBusy(name); setError('');
    try { await fn(); } catch (e: any) { setError(e.message); } finally { setBusy(''); }
  };

  const getAiScore = () => run('score', async () => setAi(await api.post<AiMatch>(`/jobs/${job.id}/ai-score`)));
  const tailor = () => run('tailor', async () => {
    const doc = await api.post<Doc>(`/jobs/${job.id}/tailor-cv`, { cvDocumentId: cvOriginal(), language: lang === 'AUTO' ? null : lang });
    setDocs(await api.get<Doc[]>('/documents')); setCvId(doc.id); setEditing(doc);
  });
  const letter = () => run('letter', async () => {
    const doc = await api.post<Doc>(`/jobs/${job.id}/cover-letter`, { cvDocumentId: cvOriginal(), language: lang === 'AUTO' ? null : lang, notes });
    setDocs(await api.get<Doc[]>('/documents')); setClId(doc.id); setEditing(doc);
  });
  const cvOriginal = () => {
    const selected = docs.find(x => x.id === cvId);
    return selected?.original ? selected.id : (selected?.derivedFromId ?? docs.find(x => x.kind === 'CV' && x.original)?.id);
  };

  const apply = (confirmSimilar = false) => run('apply', async () => {
    try {
      const app = await api.post<Application>('/applications', {
        jobId: job.id, cvDocumentId: cvId, coverLetterId: clId || null, extraDocumentIds: extras, confirmSimilar });
      await api.post(`/applications/${app.id}/prepare`);
      nav(`/applications/${app.id}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.details?.type === 'SIMILAR_AT_COMPANY') { setSimilar(e.details.similar); return; }
      if (e instanceof ApiError && e.status === 409 && e.details?.applicationId) { nav(`/applications/${e.details.applicationId}`); return; }
      throw e;
    }
  });

  const open = async (doc: Doc) => window.open(await api.blobUrl(`/documents/${doc.id}/download?inline=true`), '_blank');

  return (
    <div className="page grid">
      <div className="card stack">
        <div className="row between">
          <div>
            <h1 style={{ marginBottom: '.25rem' }}>{job.title}</h1>
            <div className="muted"><Link to={`/companies/${company.id}`}>{company.name}</Link> · {job.locations.join('; ') || company.hqCity}{job.remote && ' · Remote possible'}</div>
          </div>
          <a className="btn" href={job.applyUrl ?? job.url} target="_blank" rel="noreferrer">{board ? `Open on ${board} ↗` : 'Open original posting ↗'}</a>
        </div>
        <div className="row"><PlatformBadges platforms={job.platforms} /><Chips values={job.studentTypes} /><Chips values={job.itFields} /></div>
        {job.snippetOnly && <div className="notice small">Only a short summary of this job is known (from {job.sources.includes('EMAIL_ALERT') ? 'your job-alert e-mail' : 'the job search API'}). Open the original posting for the full description and language requirements.</div>}
        <div className="row"><LanguageBadges german={job.germanLevel} english={job.englishLevel} />
          <span className="muted small">Posting in {job.postingLanguage === 'EN' ? 'English' : 'German'} · first seen {date(job.firstSeenAt)}{job.languageSource === 'AI' && ' · language checked by AI'}</span></div>
        {d.application && <div className="notice">You already have an application for this job: <Link to={`/applications/${d.application.id}`}>{label(d.application.status)}</Link></div>}
        {d.otherApplicationsAtCompany.length > 0 && <div className="notice small">You have {d.otherApplicationsAtCompany.length} other application(s) at {company.name}.</div>}
      </div>

      <div className="layout" style={{ gridTemplateColumns: '1fr 380px' }}>
        <div className="grid">
          <Section title="Job description">
            {job.descriptionHtml
              ? <div className="description" dangerouslySetInnerHTML={{ __html: job.descriptionHtml }} />
              : <p className="muted">No description available. Open the original posting for details.</p>}
          </Section>
          <Section title="Company">
            <div className="small">
              <div><b>{company.name}</b> · {company.industry} · {company.hqCity}</div>
              {company.website && <div><a href={company.website} target="_blank" rel="noreferrer">{company.website}</a></div>}
              {company.careerUrl && <div>Career page: <a href={company.careerUrl} target="_blank" rel="noreferrer">{company.careerUrl}</a></div>}
              {company.atsType !== 'UNKNOWN' && <div className="muted">Career system: {company.atsType}</div>}
            </div>
          </Section>
        </div>

        <div className="grid">
          <Section title="Match">
            <div className="row"><Score value={d.keywordScore.total} /><span className="small">Keyword score · language fit: {d.keywordScore.languageFit.toLowerCase()}</span></div>
            {d.keywordScore.matchedSkills.length > 0 && <div className="small"><span className="ok">You have:</span> {d.keywordScore.matchedSkills.join(', ')}</div>}
            {d.keywordScore.missingSkills.length > 0 && <div className="small"><span className="warn">Not in your profile:</span> {d.keywordScore.missingSkills.join(', ')}</div>}
            {ai ? (
              <div className="stack small">
                <div className="row"><Score value={ai.score} ai /><span>{ai.summary}</span></div>
                {ai.strengths.length > 0 && <div><b>Strengths:</b><ul>{ai.strengths.map((s, i) => <li key={i}>{s}</li>)}</ul></div>}
                {ai.gaps.length > 0 && <div><b>Gaps:</b><ul>{ai.gaps.map((s, i) => <li key={i}>{s}</li>)}</ul></div>}
                <div><b>Language:</b> {ai.languageAssessment}</div>
                <div><b>Advice:</b> {ai.recommendation}</div>
              </div>
            ) : (
              <button disabled={!!busy || noConsent} onClick={getAiScore}>{busy === 'score' ? 'Analysing…' : 'Get AI assessment'}</button>
            )}
            {noConsent && <p className="muted small">AI features are off. Enable them in <Link to="/settings">Settings</Link>.</p>}
          </Section>

          <Section title="Apply">
            {!docs.some(x => x.kind === 'CV') ? (
              <p>Upload your CV in <Link to="/documents">Documents</Link> first.</p>
            ) : (
              <div className="stack">
                <label className="field"><span>CV</span>
                  <select value={cvId} onChange={e => setCvId(Number(e.target.value))}>
                    {cvs.map(c => <option key={c.id} value={c.id}>{c.original ? 'Original: ' : 'Tailored: '}{c.title}</option>)}
                  </select></label>
                <label className="field"><span>Cover letter</span>
                  <select value={clId} onChange={e => setClId(e.target.value ? Number(e.target.value) : '')}>
                    <option value="">No cover letter</option>
                    {letters.map(c => <option key={c.id} value={c.id}>{c.original ? 'Original: ' : 'Generated: '}{c.title}</option>)}
                  </select></label>
                {others.length > 0 && (
                  <div className="small"><span className="muted">Also attach:</span>
                    {others.map(o => (
                      <label key={o.id} className="check"><input type="checkbox" checked={extras.includes(o.id)}
                        onChange={e => setExtras(e.target.checked ? [...extras, o.id] : extras.filter(x => x !== o.id))} />{o.title}</label>
                    ))}
                  </div>
                )}
                <div className="row small">
                  {[...cvs, ...letters].filter(x => x.id === cvId || x.id === clId).map(x => (
                    <span key={x.id}><button onClick={() => open(x)}>View {label(x.kind)}</button>{!x.original && <button onClick={() => setEditing(x)}>Edit</button>}</span>
                  ))}
                </div>

                <details>
                  <summary>Personalise with AI (optional)</summary>
                  <div className="stack" style={{ marginTop: '.5rem' }}>
                    <label className="field"><span>Language</span>
                      <select value={lang} onChange={e => setLang(e.target.value)}>
                        <option value="AUTO">Same as posting</option><option value="DE">German</option><option value="EN">English</option>
                      </select></label>
                    <label className="field"><span>Notes for the cover letter (optional)</span>
                      <textarea placeholder="e.g. why this company, availability, a project you want to mention" value={notes} onChange={e => setNotes(e.target.value)} /></label>
                    <div className="row">
                      <button disabled={!!busy || noConsent} onClick={tailor}>{busy === 'tailor' ? 'Tailoring…' : 'Tailor my CV'}</button>
                      <button disabled={!!busy || noConsent} onClick={letter}>{busy === 'letter' ? 'Writing…' : 'Write cover letter'}</button>
                    </div>
                    <p className="muted small">The AI only uses facts from your CV and profile. Your original files are never changed. Review every generated document before applying.</p>
                  </div>
                </details>

                {similar && (
                  <div className="notice small">
                    You recently applied for a similar position at {company.name}: {similar.map(s => `${s.title} (${label(s.status)})`).join(', ')}.
                    <div className="row" style={{ marginTop: '.4rem' }}><button onClick={() => apply(true)}>Apply anyway</button><button onClick={() => setSimilar(undefined)}>Cancel</button></div>
                  </div>
                )}
                <button className="primary" disabled={!!busy || !!d.application || !cvId} onClick={() => apply()}>
                  {busy === 'apply' ? 'Starting…' : board ? 'Prepare & track application' : 'Prepare application'}
                </button>
                <p className="muted small">{board
                  ? `You apply on ${board} yourself (automating job boards is against their terms). The app keeps your documents ready and tracks the application.`
                  : "We fill the company's form for you and show you everything first. Nothing is sent until you approve it."}</p>
              </div>
            )}
            <ErrorBox error={error} />
          </Section>
        </div>
      </div>

      {editing && (
        <DocEditor doc={editing} onClose={() => setEditing(undefined)}
          onSaved={async () => { setDocs(await api.get<Doc[]>('/documents')); setEditing(undefined); }} />
      )}
    </div>
  );
}
