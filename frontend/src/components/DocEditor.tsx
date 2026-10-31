import { useState } from 'react';
import { api, Doc } from '../api';
import { ErrorBox } from './ui';

type CvEntry = { heading: string; subheading: string; period: string; bullets: string[] };
type Cv = { language: string; headline: string; summary: string; skills: string[]; sections: { title: string; entries: CvEntry[] }[]; changes: string[] };
type Letter = { language: string; subject: string; salutation: string; paragraphs: string[]; closing: string };

/** Lets the user review and edit AI-generated documents before using them. */
export default function DocEditor({ doc, onSaved, onClose }: { doc: Doc; onSaved: (d: Doc) => void; onClose: () => void }) {
  const initial = doc.contentJson ? JSON.parse(doc.contentJson) : null;
  const [content, setContent] = useState<any>(initial);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  if (!content) return null;

  const save = async () => {
    setSaving(true);
    try {
      onSaved(await api.put<Doc>(`/documents/${doc.id}/content`, content));
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="card stack">
      <div className="row between"><h2 style={{ margin: 0 }}>Edit: {doc.title}</h2><button onClick={onClose}>Close</button></div>
      {doc.kind === 'CV' ? <CvForm cv={content as Cv} onChange={setContent} /> : <LetterForm l={content as Letter} onChange={setContent} />}
      <ErrorBox error={error} />
      <div className="row"><button className="primary" disabled={saving} onClick={save}>{saving ? 'Saving…' : 'Save and update PDF'}</button></div>
    </div>
  );
}

function CvForm({ cv, onChange }: { cv: Cv; onChange: (c: Cv) => void }) {
  const set = (p: Partial<Cv>) => onChange({ ...cv, ...p });
  const setEntry = (si: number, ei: number, p: Partial<CvEntry>) => {
    const sections = cv.sections.map((s, i) => i !== si ? s : { ...s, entries: s.entries.map((e, j) => j !== ei ? e : { ...e, ...p }) });
    set({ sections });
  };
  return (
    <div className="stack">
      {cv.changes?.length > 0 && (
        <div className="notice small"><b>What the AI changed — please check:</b><ul>{cv.changes.map((c, i) => <li key={i}>{c}</li>)}</ul></div>
      )}
      <label className="field"><span>Headline</span><input value={cv.headline} onChange={e => set({ headline: e.target.value })} /></label>
      <label className="field"><span>Summary</span><textarea value={cv.summary} onChange={e => set({ summary: e.target.value })} /></label>
      <label className="field"><span>Skills (comma separated)</span>
        <input value={cv.skills.join(', ')} onChange={e => set({ skills: e.target.value.split(',').map(s => s.trim()).filter(Boolean) })} /></label>
      {cv.sections.map((s, si) => (
        <div key={si} className="stack">
          <h3>{s.title}</h3>
          {s.entries.map((e, ei) => (
            <div key={ei} className="card stack">
              <div className="two">
                <label className="field"><span>Heading</span><input value={e.heading} onChange={x => setEntry(si, ei, { heading: x.target.value })} /></label>
                <label className="field"><span>Period</span><input value={e.period} onChange={x => setEntry(si, ei, { period: x.target.value })} /></label>
              </div>
              <label className="field"><span>Organisation</span><input value={e.subheading} onChange={x => setEntry(si, ei, { subheading: x.target.value })} /></label>
              <label className="field"><span>Bullets (one per line)</span>
                <textarea value={e.bullets.join('\n')} onChange={x => setEntry(si, ei, { bullets: x.target.value.split('\n').filter(b => b.trim()) })} /></label>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

function LetterForm({ l, onChange }: { l: Letter; onChange: (l: Letter) => void }) {
  const set = (p: Partial<Letter>) => onChange({ ...l, ...p });
  return (
    <div className="stack">
      <label className="field"><span>Subject</span><input value={l.subject} onChange={e => set({ subject: e.target.value })} /></label>
      <label className="field"><span>Salutation</span><input value={l.salutation} onChange={e => set({ salutation: e.target.value })} /></label>
      <label className="field"><span>Text (blank line between paragraphs)</span>
        <textarea style={{ minHeight: '18rem' }} value={l.paragraphs.join('\n\n')}
          onChange={e => set({ paragraphs: e.target.value.split(/\n\s*\n/).map(p => p.trim()).filter(Boolean) })} /></label>
      <label className="field"><span>Closing</span><input value={l.closing} onChange={e => set({ closing: e.target.value })} /></label>
    </div>
  );
}
