# BW Student Jobs

Find **student IT jobs** (Werkstudent, Praktikum/Internship, Abschlussarbeit/Thesis) **directly on the official career pages** of companies in Baden-Württemberg — never on job boards like LinkedIn, StepStone, Indeed or XING.
Upload your CV, optionally let AI tailor it and write a cover letter, and let the platform fill the company's application form. **Nothing is sent until you approve it.**

Multi-user: every user has their own documents, profile, filters and applications.

---

## Features

- **Company database**: 300+ curated BW companies (`backend/src/main/resources/seed/companies-bw.yaml`), plus admins can add more. Career pages and the ATS behind them are discovered automatically from the company website.
- **Direct scanning only**: ATS adapters for Workday, SAP SuccessFactors, SmartRecruiters, Greenhouse, Lever, Personio, Recruitee, plus a generic scanner (schema.org JSON-LD, job links, headless browser fallback). A blocklist (`taxonomy/aggregator-domains.txt`) rejects aggregator URLs everywhere.
- **Student + IT only**: titles classified as Werkstudent / Praktikum / Abschlussarbeit (Duales Studium as optional filter); fields like Backend, Java, AI/ML, Data Science, QA/Testing, DevOps, Embedded, Security, SAP.
- **Flexible language filters**: each posting gets a German level (Not required, Nice to have, B1, B2, Fluent, Native, Not stated) and an English level. **Jobs are never hidden by language** unless the user picks accepted levels.
- **Match scores**: instant keyword score for every job (skills, field, type, language fit) + optional AI assessment (Claude).
- **Documents**: upload CV, cover letters, transcripts, certificates (encrypted at rest). Per job: use originals, or generate a tailored CV / cover letter (DE or EN), edit it, and re-render the PDF. Originals are never changed.
- **Application automation**: headless Chromium fills the form, shows you every value + a screenshot, asks you for missing answers and consents, and submits **only after your explicit approval**. CAPTCHA, login walls and e-mail applications switch to a guided manual mode.
- **Tracking + duplicate protection**: one application per job (DB-enforced), warning for near-identical reposts at the same company, statuses from Draft to Offer, notes and follow-up dates.
- **Privacy**: AI only after opt-in, GDPR export and account deletion.

---

## Architecture

```
React (Vite)  ──/api──►  Spring Boot (Java 21)  ──►  PostgreSQL
                              │
       ┌──────────────┬───────┴──────┬────────────────┬─────────────────┐
       ↓              ↓              ↓                ↓                 ↓
   company         scan            job            document + ai     application
   seed, ATS     queue, workers,  classify,       encrypted files,   Playwright form
   discovery     adapters         search, score   Claude, PDF        fill → approve → submit
```

Scanning flow for one company:

```
company website
      ↓  CareerPageDiscovery (company domain + known ATS only, never aggregators)
career page + ATS type
      ↓  adapter.list()          (API / HTML / JSON-LD / headless browser)
raw jobs
      ↓  student title? ── no ──► dropped
      ↓  adapter.details()       (only for student-looking titles)
      ↓  IT field? ── no ──► dropped
      ↓  language + BW location tagging
      ↓  upsert by fingerprint   (company + title + location)
open jobs     ── not seen in 2 scans ──► CLOSED
```

Scaling: scan tasks live in a Postgres queue (`FOR UPDATE SKIP LOCKED`), so you can run several backend instances; one advisory lock keeps the scheduler single. Scan intervals adapt per company (6–48 h).

---

## Open in Cursor and run (Docker)

You need [Docker Desktop](https://www.docker.com/products/docker-desktop/) running.

1. In Cursor: **File → Clone Repository…**, paste `https://github.com/aHozi/bw-student-jobs`, choose a folder, then **Open**.
2. Open Cursor's terminal (**Terminal → New Terminal**) and create `.env` with two random secrets.

macOS / Linux:

```bash
cp .env.example .env
```

```bash
echo "APP_STORAGE_KEY=$(openssl rand -base64 32)" >> .env
```

```bash
echo "APP_JWT_SECRET=$(openssl rand -base64 48)" >> .env
```

Windows (PowerShell):

```powershell
Copy-Item .env.example .env
```

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); Add-Content .env "APP_STORAGE_KEY=$([Convert]::ToBase64String($b))"
```

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); Add-Content .env "APP_JWT_SECRET=$([Convert]::ToBase64String($b))"
```

3. Optional: open `.env` and fill in `ANTHROPIC_API_KEY=` to enable the AI features.
4. Start everything (the first build takes about 5–10 minutes):

```bash
docker compose up -d --build
```

5. Open http://localhost:3000 and register. **The first account becomes admin.** Scanning starts about 20 seconds after startup; watch it on the **Admin** page.

Useful commands:

```bash
docker compose logs -f backend
```

```bash
docker compose down
```

---

## Local development

Requirements: Java 21, Maven, Node 22, PostgreSQL 16 (or `docker compose up -d db`).

Backend (http://localhost:8080):

```bash
cd backend && mvn spring-boot:run
```

Frontend (http://localhost:5173, proxies `/api` to the backend):

```bash
cd frontend && npm install && npm run dev
```

The headless browser uses Playwright's Chromium. If it is not installed yet:

```bash
cd backend && mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"
```

---

## Configuration

All settings are environment variables (see `backend/src/main/resources/application.yml`).

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local `bwjobs` | PostgreSQL connection |
| `APP_JWT_SECRET` | dev value | Login token signing key (≥ 32 chars). **Set in production.** |
| `APP_STORAGE_KEY` | generated dev key | AES-256 key (base64, 32 bytes) for document encryption. **Set in production; losing it makes files unreadable.** |
| `APP_STORAGE_DIR` | `./data/files` | Where encrypted files are stored |
| `APP_ADMIN_EMAILS` | – | Comma-separated e-mails that become admins on registration |
| `APP_CORS_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | Allowed browser origins (patterns). Add your domain, e.g. `https://jobs.example.org` |
| `APP_SEED_ENABLED` | `true` | Load new companies from the seed YAML on startup |
| `APP_SCAN_ENABLED` | `true` | Run scheduler + scan workers |
| `APP_SCAN_WORKERS` | `4` | Worker threads per instance |
| `APP_SCAN_USER_AGENT` | bot name | **Use an honest name with a contact URL/e-mail** |
| `APP_SCAN_BROWSER` | `true` | Headless-browser fallback for JavaScript career pages |
| `ANTHROPIC_API_KEY` | – | Enables AI features |
| `CLAUDE_MODEL` | `claude-opus-5-5` | Model for all AI calls |
| `APP_AI_DAILY_LIMIT` | `60` | AI calls per user per day |
| `APP_AI_LANGUAGE_FALLBACK` | `false` | Let Claude classify language requirements the rules could not read |

---

## Managing companies

- **Seed file**: add entries to `companies-bw.yaml` (`name`, `website`, optional `careerUrl`, optional `ats: "TYPE:tenant"`, `city`, `industry`, `size`). New names are inserted on the next start; existing ones are not overwritten.
- **Admin UI** (`Admin` page): add a company by name + website. Discovery finds the career page and ATS, then the first scan runs.
- **Health**: the Admin page lists BROKEN companies (3 failed scans) and NEEDS_REVIEW companies (no career page found), plus recent scan runs.

ATS tenant formats (set automatically by discovery):

| ATS | Tenant |
|---|---|
| Workday | `host\|tenant\|site`, e.g. `acme.wd3.myworkdayjobs.com\|acme\|Acme_Careers` |
| SmartRecruiters | company identifier, e.g. `BoschGroup` |
| Greenhouse | board token |
| Lever | company slug, `eu/slug` for the EU instance |
| Personio | host, e.g. `acme.jobs.personio.de` |
| Recruitee | subdomain |
| SuccessFactors | career site base URL, e.g. `https://jobs.sap.com` |

---

## AI features (optional)

Uses the official Anthropic Java SDK with structured outputs. Server-side refusal fallback is enabled.

- **AI match score**: fit 0–100, strengths, gaps, language assessment, advice.
- **Tailored CV**: reorders and rephrases only facts from your CV, lists every change for review, rendered as PDF.
- **Cover letter**: German or English, no clichés, editable, rendered as PDF with your address block.
- **Profile from CV**: fills skills, languages, study program (rule-based fallback without AI).

Job postings and CVs are passed to the model inside tagged data blocks with instructions to ignore any instructions inside them (prompt-injection protection). AI features require the user's explicit opt-in in Settings.

---

## Application automation: what it does and doesn't do

```
Prepare application
      ↓  duplicate check
      ↓  headless browser opens the apply page, fills fields, uploads chosen files
      ↓  STOPS before submit → field table + screenshot
You review, answer missing questions, tick consents, confirm
      ↓  Approve and submit
fresh browser session fills again and clicks submit → confirmation screenshot
```

- Privacy/consent checkboxes are **never** ticked without your explicit click. Talent-pool / newsletter boxes stay unticked.
- **Manual mode** (direct link, document downloads, "mark as submitted") when the site has a CAPTCHA, needs an account (typical for Workday and SuccessFactors), has no form, or wants applications by e-mail (a draft e-mail is prepared; the platform never sends e-mail for you).
- Every career site is different. The filler uses generic label matching (German and English), so unusual forms may need "Fill again" or manual mode.

---

## Tests

Unit tests run anywhere. Integration tests need PostgreSQL with a `bwjobs_test` database (created automatically by `docker compose up -d db`) and Chromium for the form-filling tests. They are skipped if the database is not reachable.

```bash
cd backend && mvn test
```

What is covered: classifiers (student type, IT field, language levels, BW location), aggregator blocklist, ATS detection, every adapter against mock API responses, robots.txt, full scan → search → close pipeline, application flow with a real headless browser (no submit before approval, single submit after approval, duplicates), AI endpoints against a mock Claude API (PDF output, consent, quota).

---

## Responsible use

- Only public job pages are read. `robots.txt` is respected, each host gets at most one request every 2 s, with retries and backoff on 429/5xx.
- Some companies' terms of use restrict automated access. Check them before enabling scanning in production, and remove companies that do not allow it.
- Documents contain personal data: run behind HTTPS, set `APP_STORAGE_KEY`, and keep backups of the key.

## Known limitations

- Seed career URLs are hints and were not verified against the live sites; discovery fills in the real career page on the first scan, and failures show up in the Admin page.
- ATS adapters were tested against mock responses that follow the public API formats, not against every live tenant. Monitor the Admin page after the first scans.
- The jar is large (~340 MB) because Playwright bundles its driver for all platforms.
