import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, date, JobSummary, label, Preferences, Profile, qs } from '../api';
import { Chips, ErrorBox, LanguageBadges, MultiCheck, PlatformBadges, Score } from '../components/ui';

type Meta = { sources: string[]; studentTypes: string[]; itFields: string[]; germanLevels: string[]; englishLevels: string[]; locationModes: string[] };

type Filters = {
  q: string; sources: string[]; studentTypes: string[]; itFields: string[]; germanLevels: string[]; englishLevels: string[];
  includeUnknownLanguage: boolean; locationMode: string; city: string; postedWithinDays: string; minScore: number;
  hideApplied: boolean; sort: string;
};

const fromPrefs = (p: Preferences): Filters => ({
  q: '', sources: [], studentTypes: p.studentTypes, itFields: p.itFields, germanLevels: p.acceptedGermanLevels,
  englishLevels: p.acceptedEnglishLevels, includeUnknownLanguage: p.includeUnknownLanguage, locationMode: p.locationMode,
  city: '', postedWithinDays: '', minScore: p.minScore, hideApplied: false, sort: 'match',
});

export default function Jobs() {
  const [meta, setMeta] = useState<Meta>();
  const [profile, setProfile] = useState<Profile>();
  const [f, setF] = useState<Filters>();
  const [items, setItems] = useState<JobSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api.get<Meta>('/meta/filters').then(setMeta);
    api.get<Profile>('/profile').then(p => { setProfile(p); setF(fromPrefs(p.preferences)); });
  }, []);

  useEffect(() => {
    if (!f) return;
    const t = setTimeout(() => {
      api.get<{ items: JobSummary[]; total: number }>('/jobs' + qs({ ...f, page, size: 25 }))
        .then(r => { setItems(r.items); setTotal(r.total); setError(''); })
        .catch(e => setError(e.message));
    }, 250);
    return () => clearTimeout(t);
  }, [f, page]);

  if (!meta || !f || !profile) return <div className="page muted">Loading…</div>;
  const set = (patch: Partial<Filters>) => { setPage(0); setSaved(false); setF({ ...f, ...patch }); };

  const savePrefs = async () => {
    const preferences: Preferences = {
      ...profile.preferences, studentTypes: f.studentTypes, itFields: f.itFields, acceptedGermanLevels: f.germanLevels,
      acceptedEnglishLevels: f.englishLevels, includeUnknownLanguage: f.includeUnknownLanguage, locationMode: f.locationMode,
      minScore: f.minScore,
    };
    setProfile(await api.put<Profile>('/profile', { ...profile, preferences }));
    setSaved(true);
  };

  return (
    <div className="page">
      <div className="layout">
        <aside className="card stack">
          <label className="field"><span>Search</span>
            <input placeholder="Title, company, keyword" value={f.q} onChange={e => set({ q: e.target.value })} /></label>
          <div><h3>Source</h3><p className="muted small">None selected = all sources</p>
            <MultiCheck options={meta.sources} value={f.sources} onChange={v => set({ sources: v })} /></div>
          <div><h3>Job type</h3><MultiCheck options={meta.studentTypes} value={f.studentTypes} onChange={v => set({ studentTypes: v })} /></div>
          <div><h3>Field</h3><p className="muted small">None selected = all IT fields</p>
            <MultiCheck options={meta.itFields} value={f.itFields} onChange={v => set({ itFields: v })} /></div>
          <div>
            <h3>German I can work in</h3>
            <p className="muted small">None selected = show every job. Jobs are never hidden unless you choose levels here.</p>
            <MultiCheck options={meta.germanLevels.filter(l => l !== 'UNKNOWN')} value={f.germanLevels} onChange={v => set({ germanLevels: v })} />
          </div>
          <div>
            <h3>English requirement</h3>
            <MultiCheck options={meta.englishLevels.filter(l => l !== 'UNKNOWN')} value={f.englishLevels} onChange={v => set({ englishLevels: v })} />
          </div>
          <label className="check small"><input type="checkbox" checked={f.includeUnknownLanguage}
            onChange={e => set({ includeUnknownLanguage: e.target.checked })} /> Include jobs where the language is not stated</label>
          <label className="field"><span>Location</span>
            <select value={f.locationMode} onChange={e => set({ locationMode: e.target.value })}>
              {meta.locationModes.map(m => <option key={m} value={m}>{label(m)}</option>)}
            </select></label>
          <label className="field"><span>City</span><input placeholder="e.g. Stuttgart" value={f.city} onChange={e => set({ city: e.target.value })} /></label>
          <label className="field"><span>Posted within</span>
            <select value={f.postedWithinDays} onChange={e => set({ postedWithinDays: e.target.value })}>
              <option value="">Any time</option><option value="3">3 days</option><option value="7">7 days</option>
              <option value="14">14 days</option><option value="30">30 days</option>
            </select></label>
          <label className="field"><span>Minimum match score: {f.minScore}</span>
            <input type="range" min={0} max={90} step={5} value={f.minScore} onChange={e => set({ minScore: Number(e.target.value) })} /></label>
          <label className="check small"><input type="checkbox" checked={f.hideApplied} onChange={e => set({ hideApplied: e.target.checked })} /> Hide jobs I applied to</label>
          <button onClick={savePrefs}>{saved ? 'Saved ✓' : 'Save as my default filters'}</button>
        </aside>

        <main className="stack">
          <div className="row between">
            <h1 style={{ margin: 0 }}>{total} student jobs</h1>
            <label className="row small">Sort
              <select style={{ width: 'auto' }} value={f.sort} onChange={e => set({ sort: e.target.value })}>
                <option value="match">Best match</option><option value="date">Newest</option>
              </select></label>
          </div>
          {!profile.skills.length && (
            <div className="notice small">Tip: upload your CV in <Link to="/documents">Documents</Link> and add skills in <Link to="/settings">Settings</Link> to get meaningful match scores.</div>
          )}
          <ErrorBox error={error} />
          {items.map(j => (
            <div key={j.id} className="card job">
              <Score value={j.score} ai={j.aiScore != null} />
              <div>
                <Link to={`/jobs/${j.id}`}><h3>{j.title}</h3></Link>
                <div className="muted small">
                  <Link to={`/companies/${j.companyId}`}>{j.companyName}</Link> · {j.locations.join('; ') || j.companyCity}
                  {j.remote && ' · Remote possible'} · seen {date(j.postedAt ?? j.firstSeenAt)}
                </div>
                <div className="row small" style={{ marginTop: '.35rem' }}>
                  <PlatformBadges platforms={j.platforms} />
                  <Chips values={j.studentTypes} /><Chips values={j.itFields.slice(0, 3)} />
                  <LanguageBadges german={j.germanLevel} english={j.englishLevel} />
                  {j.applicationStatus && <span className={`badge status-${j.applicationStatus}`}>{label(j.applicationStatus)}</span>}
                </div>
              </div>
            </div>
          ))}
          {!items.length && !error && (
            <div className="card muted">No jobs match these filters yet. Connect your LinkedIn/StepStone/Indeed job alerts in <Link to="/settings">Settings</Link>; jobs from the Bundesagentur für Arbeit are imported automatically every few hours.</div>
          )}
          {total > 25 && (
            <div className="row">
              <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span className="muted small">Page {page + 1} of {Math.ceil(total / 25)}</span>
              <button disabled={(page + 1) * 25 >= total} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
