import { ReactNode } from 'react';
import { label, platformName } from '../api';

export function Score({ value, ai }: { value: number; ai?: boolean }) {
  const cls = value >= 70 ? 'hi' : value >= 45 ? 'mid' : 'lo';
  return <div className={`score ${cls}`} title={ai ? 'AI match score' : 'Keyword match score'}>{value}{ai ? '★' : ''}</div>;
}

export function Chips({ values }: { values: string[] }) {
  return <>{values.map(v => <span key={v} className="chip">{label(v)}</span>)}</>;
}

export function LanguageBadges({ german, english }: { german: string; english: string }) {
  return (
    <span className="small">
      <span className="badge" title="German requirement">DE: {label(german)}</span>{' '}
      <span className="badge" title="English requirement">EN: {label(english)}</span>
    </span>
  );
}

export function MultiCheck({ options, value, onChange }: { options: string[]; value: string[]; onChange: (v: string[]) => void }) {
  return (
    <div>
      {options.map(o => (
        <label key={o} className="check small">
          <input type="checkbox" checked={value.includes(o)}
            onChange={e => onChange(e.target.checked ? [...value, o] : value.filter(x => x !== o))} />
          {label(o)}
        </label>
      ))}
    </div>
  );
}

export function ErrorBox({ error }: { error?: string }) {
  return error ? <div className="error">{error}</div> : null;
}

export function Section({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card stack">
      <div className="row between"><h2 style={{ margin: 0 }}>{title}</h2>{actions}</div>
      {children}
    </section>
  );
}

export function PlatformBadges({ platforms }: { platforms: string[] }) {
  return <>{platforms.filter(p => p !== 'COMPANY' || platforms.length === 1).map(p => (
    <span key={p} className={`badge platform-${p}`} title="Where this job was found">{platformName(p)}</span>
  ))}</>;
}
