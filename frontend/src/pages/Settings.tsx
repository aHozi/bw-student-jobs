import { useEffect, useState } from 'react';
import { api, auth, Doc, LanguageSkill, Profile } from '../api';
import { ErrorBox, Section } from '../components/ui';
import EmailImport from '../components/EmailImport';

const LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'NATIVE'];

export default function Settings() {
  const [p, setP] = useState<Profile>();
  const [docs, setDocs] = useState<Doc[]>([]);
  const [ai, setAi] = useState<{ enabled: boolean; usedToday: number; dailyLimit: number }>();
  const [msg, setMsg] = useState('');
  const [error, setError] = useState('');
  const [suggestion, setSuggestion] = useState<any>();
  const [skillsText, setSkillsText] = useState('');

  useEffect(() => {
    api.get<Profile>('/profile').then(x => { setP(x); setSkillsText(x.skills.join(', ')); });
    api.get<Doc[]>('/documents').then(setDocs);
    api.get<any>('/ai/status').then(setAi);
  }, []);
  if (!p) return <div className="page muted">Loading…</div>;

  const set = (patch: Partial<Profile>) => setP({ ...p, ...patch });
  const save = async (next = p) => {
    setError(''); setMsg('');
    try {
      const saved = await api.put<Profile>('/profile', { ...next, skills: skillsText.split(',').map(s => s.trim()).filter(Boolean) });
      setP(saved); setSkillsText(saved.skills.join(', ')); setMsg('Saved ✓');
    } catch (e: any) { setError(e.message); }
  };
  const setLang = (i: number, patch: Partial<LanguageSkill>) => set({ languages: p.languages.map((l, j) => j === i ? { ...l, ...patch } : l) });
  const cv = docs.find(d => d.kind === 'CV' && d.original);

  const extract = async () => {
    setError('');
    try { setSuggestion(await api.post<any>(`/profile/extract/${cv!.id}`)); } catch (e: any) { setError(e.message); }
  };
  const applySuggestion = async () => {
    const saved = await api.post<Profile>('/profile/apply-extraction', suggestion.profile);
    setP(saved); setSkillsText(saved.skills.join(', ')); setSuggestion(undefined); setMsg('Profile updated from CV ✓');
  };
  const exportData = async () => window.open(await api.blobUrl('/account/export'), '_blank');
  const deleteAccount = async () => {
    if (!confirm('Delete your account, all documents and applications permanently?')) return;
    await api.del('/account'); auth.set(null); window.location.href = '/login';
  };

  const text = (k: keyof Profile, title: string, type = 'text') => (
    <label className="field"><span>{title}</span>
      <input type={type} value={(p[k] as any) ?? ''} onChange={e => set({ [k]: type === 'number' ? (e.target.value ? Number(e.target.value) : undefined) : e.target.value } as any)} /></label>
  );

  return (
    <div className="page grid">
      <h1>Settings</h1>
      <EmailImport />
      <Section title="AI features">
        <label className="check"><input type="checkbox" checked={p.aiConsent} onChange={e => save({ ...p, aiConsent: e.target.checked })} />
          Allow AI processing: my CV and profile are sent to the Claude API (Anthropic) to compute AI match scores, tailor my CV and write cover letters.</label>
        {ai && <p className="muted small">{ai.enabled ? `AI is available on this server · used today: ${ai.usedToday}/${ai.dailyLimit}` : 'AI is not configured on this server.'}</p>}
      </Section>

      <Section title="Fill profile from my CV">
        {cv ? <div className="row"><button onClick={extract}>Read profile from “{cv.title}”</button>
          <span className="muted small">{p.aiConsent && ai?.enabled ? 'Uses AI' : 'Uses simple rules (enable AI for better results)'}</span></div>
          : <p className="muted">Upload a CV in Documents first.</p>}
        {suggestion && (
          <div className="notice small stack">
            <div><b>Found ({suggestion.source}):</b> {[suggestion.profile.fullName, suggestion.profile.email, suggestion.profile.phone, suggestion.profile.university, suggestion.profile.studyProgram].filter(Boolean).join(' · ')}</div>
            <div><b>Skills:</b> {suggestion.profile.skills.join(', ')}</div>
            <div><b>Languages:</b> {suggestion.profile.languages.map((l: any) => `${l.language} ${l.level}`).join(', ')}</div>
            <div className="row"><button className="primary" onClick={applySuggestion}>Add to my profile</button><button onClick={() => setSuggestion(undefined)}>Dismiss</button></div>
          </div>
        )}
      </Section>

      <Section title="Profile" actions={<button className="primary" onClick={() => save()}>Save</button>}>
        <p className="muted small">Used to fill application forms and to compute match scores.</p>
        <div className="two">
          {text('fullName', 'Full name')}{text('email', 'E-mail for applications', 'email')}
          {text('phone', 'Phone')}{text('street', 'Street and number')}
          {text('postalCode', 'Postal code')}{text('city', 'City')}
          {text('university', 'University')}{text('studyProgram', 'Study program')}
          {text('degree', 'Degree (e.g. Bachelor)')}{text('semester', 'Semester', 'number')}
          {text('availableFrom', 'Available from')}{text('hoursPerWeek', 'Hours per week', 'number')}
          {text('linkedinUrl', 'LinkedIn URL')}{text('githubUrl', 'GitHub URL')}
          {text('websiteUrl', 'Website / portfolio')}
        </div>
        <label className="field"><span>Skills (comma separated)</span><textarea value={skillsText} onChange={e => setSkillsText(e.target.value)} /></label>
        <div>
          <h3>Languages</h3>
          {p.languages.map((l, i) => (
            <div key={i} className="row" style={{ marginBottom: '.4rem' }}>
              <input style={{ maxWidth: 200 }} value={l.language} onChange={e => setLang(i, { language: e.target.value })} placeholder="German" />
              <select style={{ maxWidth: 140 }} value={l.level} onChange={e => setLang(i, { level: e.target.value })}>{LEVELS.map(x => <option key={x}>{x}</option>)}</select>
              <button onClick={() => set({ languages: p.languages.filter((_, j) => j !== i) })}>Remove</button>
            </div>
          ))}
          <button onClick={() => set({ languages: [...p.languages, { language: '', level: 'B2' }] })}>Add language</button>
        </div>
        <label className="field"><span>Short summary</span><textarea value={p.summary ?? ''} onChange={e => set({ summary: e.target.value })} /></label>
      </Section>

      <Section title="Defaults">
        <div className="two">
          <label className="field"><span>Cover letter language</span>
            <select value={p.preferences.coverLetterLanguage} onChange={e => set({ preferences: { ...p.preferences, coverLetterLanguage: e.target.value } })}>
              <option value="AUTO">Same as job posting</option><option value="DE">German</option><option value="EN">English</option></select></label>
          <label className="field"><span>Documents</span>
            <select value={p.preferences.defaultDocumentMode} onChange={e => set({ preferences: { ...p.preferences, defaultDocumentMode: e.target.value } })}>
              <option value="ORIGINAL">Use my original documents</option><option value="TAILORED">Prefer tailored documents</option></select></label>
        </div>
        <p className="muted small">Job filters (job types, accepted language levels, location) are saved from the Jobs page.</p>
        <div><button onClick={() => save()}>Save</button></div>
      </Section>

      {msg && <div className="notice">{msg}</div>}
      <ErrorBox error={error} />

      <Section title="Privacy">
        <div className="row"><button onClick={exportData}>Export my data (JSON)</button><button className="danger" onClick={deleteAccount}>Delete my account</button></div>
      </Section>
    </div>
  );
}
