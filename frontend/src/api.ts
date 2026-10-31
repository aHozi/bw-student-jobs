// Small typed client for the backend REST API.

export type User = { id: number; email: string; displayName?: string; role: 'USER' | 'ADMIN' };

export type LanguageSkill = { language: string; level: string };

export type Preferences = {
  studentTypes: string[];
  itFields: string[];
  acceptedGermanLevels: string[];
  acceptedEnglishLevels: string[];
  includeUnknownLanguage: boolean;
  locationMode: string;
  cities: string[];
  defaultDocumentMode: string;
  coverLetterLanguage: string;
  minScore: number;
};

export type Profile = {
  userId: number;
  fullName?: string; email?: string; phone?: string; street?: string; postalCode?: string; city?: string;
  linkedinUrl?: string; githubUrl?: string; websiteUrl?: string; university?: string; studyProgram?: string;
  degree?: string; semester?: number; availableFrom?: string; hoursPerWeek?: number;
  skills: string[]; languages: LanguageSkill[]; summary?: string; preferences: Preferences; aiConsent: boolean;
};

export type JobSummary = {
  id: number; title: string; companyId: number; companyName: string; companyCity?: string; locations: string[];
  inBw?: boolean; remote: boolean; studentTypes: string[]; itFields: string[]; germanLevel: string; englishLevel: string;
  postingLanguage?: string; postedAt?: string; firstSeenAt: string; url: string; keywordScore: number; aiScore?: number;
  score: number; languageFit: string; applicationStatus?: string; applicationId?: number;
};

export type Company = {
  id: number; name: string; website?: string; careerUrl?: string; hqCity?: string; industry?: string; size?: string;
  atsType: string; atsTenant?: string; status: string; statusMessage?: string; source: string; scanIntervalHours: number;
  consecutiveFailures: number; lastScannedAt?: string; nextScanAt?: string;
};

export type JobPosting = {
  id: number; companyId: number; url: string; applyUrl?: string; title: string; descriptionHtml?: string;
  descriptionText?: string; locations: string[]; remote: boolean; inBw?: boolean; studentTypes: string[];
  itFields: string[]; techTerms: string[]; germanLevel: string; englishLevel: string; postingLanguage?: string;
  languageSource: string; contactEmail?: string; postedAt?: string; firstSeenAt: string; lastSeenAt: string; status: string;
};

export type KeywordScore = { total: number; matchedSkills: string[]; missingSkills: string[]; languageFit: string };

export type JobDetail = {
  job: JobPosting; company: Company; keywordScore: KeywordScore;
  aiScore?: { score?: number; summary?: string; details?: AiMatch };
  application?: { id: number; status: string };
  otherApplicationsAtCompany: { id: number; status: string }[];
};

export type AiMatch = { score: number; summary: string; strengths: string[]; gaps: string[]; languageAssessment: string; recommendation: string };

export type Doc = {
  id: number; userId: number; kind: string; title: string; filename: string; contentType: string; sizeBytes: number;
  original: boolean; derivedFromId?: number; jobId?: number; contentJson?: string; createdAt: string; updatedAt: string;
};

export type FillField = { key: string; label: string; type: string; required: boolean; status: string; value?: string; options?: string[] };

export type FillReport = {
  url: string; fields: FillField[]; captcha: boolean; loginRequired: boolean; submitButtonFound: boolean;
  unresolvedRequired: number; message: string;
  emailDraft?: { to: string; subject: string; body: string; attachmentDocumentIds: number[] };
};

export type Application = {
  id: number; userId: number; jobId: number; companyId: number; status: string; mode: string; cvDocumentId?: number;
  coverLetterId?: number; extraDocumentIds: number[]; usedOriginalDocs: boolean; fillReport?: FillReport;
  overrides?: Record<string, string>; consents?: string[]; manualReason?: string; errorMessage?: string; notes?: string;
  followUpOn?: string; approvedAt?: string; submittedAt?: string; createdAt: string; updatedAt: string;
  hasScreenshot: boolean; hasConfirmation: boolean;
};

export type ApplicationRow = { application: Application; jobTitle: string; jobUrl: string; companyName: string; jobStatus: string };

export class ApiError extends Error {
  constructor(public status: number, message: string, public details?: any) {
    super(message);
  }
}

const TOKEN_KEY = 'bwjobs.token';

export const auth = {
  get token() {
    try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
  },
  set(token: string | null) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token); else localStorage.removeItem(TOKEN_KEY);
    } catch { /* storage unavailable */ }
  },
};

async function request<T>(method: string, path: string, body?: unknown, isForm = false): Promise<T> {
  const headers: Record<string, string> = {};
  if (auth.token) headers.Authorization = `Bearer ${auth.token}`;
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  const res = await fetch(`/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
  });
  if (res.status === 401 && !path.startsWith('/auth')) {
    auth.set(null);
    window.location.href = '/login';
  }
  const text = await res.text();
  const data = text ? (() => { try { return JSON.parse(text); } catch { return text; } })() : undefined;
  if (!res.ok) throw new ApiError(res.status, data?.error ?? res.statusText, data?.details);
  return data as T;
}

export const api = {
  get: <T>(p: string) => request<T>('GET', p),
  post: <T>(p: string, b?: unknown) => request<T>('POST', p, b ?? {}),
  put: <T>(p: string, b: unknown) => request<T>('PUT', p, b),
  patch: <T>(p: string, b: unknown) => request<T>('PATCH', p, b),
  del: <T>(p: string) => request<T>('DELETE', p),
  upload: <T>(p: string, form: FormData) => request<T>('POST', p, form, true),
  /** Fetches a binary file with auth and returns an object URL. */
  async blobUrl(p: string): Promise<string> {
    const res = await fetch(`/api${p}`, { headers: auth.token ? { Authorization: `Bearer ${auth.token}` } : {} });
    if (!res.ok) throw new ApiError(res.status, 'Download failed');
    return URL.createObjectURL(await res.blob());
  },
};

export function qs(params: Record<string, unknown>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) { if (v.length) u.set(k, v.join(',')); } else u.set(k, String(v));
  }
  const s = u.toString();
  return s ? `?${s}` : '';
}

export const labels: Record<string, string> = {
  WERKSTUDENT: 'Werkstudent', PRAKTIKUM: 'Praktikum / Internship', ABSCHLUSSARBEIT: 'Abschlussarbeit / Thesis',
  DUALES_STUDIUM: 'Duales Studium',
  NONE: 'Not required', NICE_TO_HAVE: 'Nice to have', B1: 'B1', B2: 'B2', C1_FLUENT: 'Fluent (C1+)', NATIVE: 'Native',
  GOOD: 'Good', FLUENT: 'Fluent', UNKNOWN: 'Not stated',
  BW_ONLY: 'Only Baden-Württemberg', BW_OR_REMOTE: 'BW + remote', ALL: 'All locations',
  SOFTWARE_ENGINEERING: 'Software engineering', BACKEND: 'Backend', FRONTEND: 'Frontend', FULLSTACK: 'Full stack',
  JAVA: 'Java', PYTHON: 'Python', AI_ML: 'AI / ML', DATA_SCIENCE: 'Data science', DATA_ENGINEERING: 'Data engineering',
  QA_TESTING: 'QA / Testing', DEVOPS_CLOUD: 'DevOps / Cloud', EMBEDDED: 'Embedded', IT_SECURITY: 'IT security',
  SAP_DEV: 'SAP development', MOBILE: 'Mobile', IT_GENERAL: 'IT (general)',
  DRAFT: 'Draft', PREPARING: 'Filling form…', READY_FOR_REVIEW: 'Ready for your review', MANUAL_REQUIRED: 'Apply manually',
  APPROVED: 'Approved', SUBMITTING: 'Submitting…', SUBMITTED: 'Submitted', FAILED: 'Failed', INTERVIEW: 'Interview',
  OFFER: 'Offer', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn',
  CV: 'CV', COVER_LETTER: 'Cover letter', TRANSCRIPT: 'Transcript', CERTIFICATE: 'Certificate', OTHER: 'Other',
};

export const label = (k?: string) => (k ? labels[k] ?? k : '');

export const date = (s?: string) => (s ? new Date(s).toLocaleDateString('de-DE') : '');
