# BW Student Jobs

Find **student IT jobs** (Werkstudent, Praktikum/Internship, Abschlussarbeit/Thesis) in Baden-Württemberg from **LinkedIn, StepStone and Indeed** (through your own job-alert e-mails), the **Bundesagentur für Arbeit** and **Adzuna** — in one list, with match scores and flexible language filters.
Upload your CV, optionally let AI tailor it and write a cover letter, and track every application. Company application forms can be filled for you; **nothing is sent until you approve it.**

Multi-user: every user has their own documents, profile, filters, job alerts and applications.

---

## Where the jobs come from

| Source | How | What you get |
|---|---|---|
| **LinkedIn, StepStone, Indeed** | You create job alerts there. The app reads those alert e-mails from your mailbox (IMAP, read-only) every 30 min. | Title, company, location, link. Private to you. |
| **Bundesagentur für Arbeit** | Official public Jobsuche API, every 6 h, student IT searches around BW cities. | Full description → language levels, match scores. |
| **Adzuna** | Official API (free key), every 6 h. Adzuna aggregates many German job boards. | Title, company, location, short description. |
| Company career pages (off by default) | The original career-page scanner (`APP_SCAN_ENABLED=true`). | Full descriptions, auto-fill of company forms. |

**Why not scrape LinkedIn, StepStone and Indeed directly?** None of them offers a public job-search API, their terms forbid scraping, and their `robots.txt` blocks it. Job-alert e-mails are the legitimate way to get their jobs automatically. For the same reason the app **never automates applications on these job boards**: you get a direct link, your prepared documents and tracking. Company forms behind a Bundesagentur link are auto-filled as before.

The same job seen in several sources (e.g. LinkedIn alert + Bundesagentur) is merged into one entry, keeping the longest description and preferring a company apply link over a job-board link.

---

## Features

- **Student + IT only**: titles classified as Werkstudent / Praktikum / Abschlussarbeit (Duales Studium as optional filter); fields like Backend, Java, AI/ML, Data Science, QA/Testing, DevOps, Embedded, Security, SAP.
- **Flexible language filters**: each posting gets a German level (Not required, Nice to have, B1, B2, Fluent, Native, Not stated) and an English level. **Jobs are never hidden by language** unless the user picks accepted levels. Alert e-mails carry no description, so their level is usually "Not stated".
- **Filters**: source, job type, field, languages, location (BW only / BW + remote / all), city, date, minimum score.
- **Match scores**: instant keyword score for every job + optional AI assessment (Claude).
- **Documents**: upload CV, cover letters, transcripts, certificates (encrypted at rest). Per job: use originals, or generate a tailored CV / cover letter (DE or EN), edit it, and re-render the PDF. Originals are never changed.
- **Applying**: job-board jobs → open the posting, apply there, mark as submitted. Company forms → headless Chromium fills the form, shows every value + a screenshot, and submits **only after your explicit approval**.
- **Tracking + duplicate protection**: one application per job (DB-enforced), warning for near-identical reposts at the same company, statuses from Draft to Offer, notes and follow-up dates.
- **Privacy**: AI only after opt-in, mailbox opened read-only with an encrypted app password, GDPR export and account deletion.

---

## Architecture

```
React (Vite)  ──/api──►  Spring Boot (Java 21)  ──►  PostgreSQL
                              │
   ┌──────────────────┬───────┴─────────┬──────────────────┬──────────────────┐
   ↓                  ↓                 ↓                  ↓                  ↓
 source             job               document + ai     application         scan (optional)
 IMAP alerts,       classify,         encrypted files,  manual / Playwright  company career
 Bundesagentur,     search, score     Claude, PDF       fill → approve       pages
 Adzuna
```

Import flow:

```
alert e-mail / API result
      ↓  employer name → company (auto-created, "Robert Bosch GmbH" = "Robert Bosch")
      ↓  student title? ── no ──► dropped
      ↓  IT field? ── no ──► dropped
      ↓  language + BW location tagging
      ↓  upsert by fingerprint (company + title + city) → merges duplicates across sources
open jobs   ── API jobs not seen for 7 days / alert jobs older than 30 days ──► CLOSED
```

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

3. Optional: open `.env` and fill in `ANTHROPIC_API_KEY=` (AI features) and `ADZUNA_APP_ID` / `ADZUNA_APP_KEY` (free at https://developer.adzuna.com).
4. Start everything (the first build takes about 5–10 minutes):

```bash
docker compose up -d --build
```

5. Open http://localhost:3000 and register. **The first account becomes admin.**
6. **Settings → Job-alert e-mails**: follow the steps there (create alerts on LinkedIn/StepStone/Indeed, Gmail app password), click **Test connection**, **Save**, **Import now**.
7. Bundesagentur jobs are imported about 1 minute after startup and then every 6 hours; see **Admin → Job sources**.

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
| `APP_SOURCE_EMAIL_ENABLED` | `true` | Job-alert e-mail import (LinkedIn, StepStone, Indeed) |
| `APP_SOURCE_BA_ENABLED` | `true` | Bundesagentur für Arbeit Jobsuche API (search terms and cities: `app.sources.arbeitsagentur` in `application.yml`) |
| `ADZUNA_APP_ID`, `ADZUNA_APP_KEY` | – | Enables the Adzuna source (free key at developer.adzuna.com) |
| `APP_SCAN_ENABLED` | `false` | Company career-page scanning (the original source) |
| `APP_SEED_ENABLED` | `false` | Load the 300+ curated BW companies for career-page scanning |
| `APP_SCAN_WORKERS` | `4` | Worker threads per instance |
| `APP_SCAN_USER_AGENT` | bot name | **Use an honest name with a contact URL/e-mail** |
| `APP_SCAN_BROWSER` | `true` | Headless-browser fallback for JavaScript career pages |
| `ANTHROPIC_API_KEY` | – | Enables AI features |
| `CLAUDE_MODEL` | `claude-opus-5-5` | Model for all AI calls |
| `APP_AI_DAILY_LIMIT` | `60` | AI calls per user per day |
| `APP_AI_LANGUAGE_FALLBACK` | `false` | Let Claude classify language requirements the rules could not read |

---

## Company career pages (optional)

Companies are created automatically from imported jobs. To additionally scan official career pages, set `APP_SCAN_ENABLED=true` and `APP_SEED_ENABLED=true`.

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

## Applying: what is automated and what isn't

- **Job-board jobs** (LinkedIn, StepStone, Indeed, Adzuna, Arbeitsagentur pages): "Prepare & track application" shows the posting link and your chosen documents; you apply on the job board, then click "mark as submitted". The browser never opens these sites (their terms forbid automation).
- **Company application forms** (e.g. the employer link of a Bundesagentur job, or career pages):

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

What is covered: alert e-mail parsing (LinkedIn, StepStone, Indeed — synthetic sample e-mails), real IMAP import against a GreenMail test server (read-only, once per message, private to the user), Bundesagentur + Adzuna imports against mock APIs, merging the same job across sources, source filter and visibility, manual mode for job-board links, classifiers (student type, IT field, language levels, BW location), aggregator blocklist, ATS detection, every adapter against mock API responses, robots.txt, full scan → search → close pipeline, application flow with a real headless browser (no submit before approval, single submit after approval, duplicates), AI endpoints against a mock Claude API (PDF output, consent, quota).

---

## Responsible use

- LinkedIn, StepStone and Indeed are never scraped or automated; only the alert e-mails they send you are read.
- Career-page scanning (if enabled): only public job pages are read. `robots.txt` is respected, each host gets at most one request every 2 s, with retries and backoff on 429/5xx.
- Some companies' terms of use restrict automated access. Check them before enabling scanning in production, and remove companies that do not allow it.
- Documents contain personal data: run behind HTTPS, set `APP_STORAGE_KEY`, and keep backups of the key.

## Known limitations

- Alert e-mail layouts change. The parser recognises job links by URL pattern and was tested with synthetic sample e-mails, not real ones. If an alert yields no jobs, enable AI processing (fallback extraction) and please add a real e-mail as a test fixture (`backend/src/test/resources/fixtures/alerts/`).
- Alert jobs have no full description (the job pages are not fetched), so language levels and skill matching are weaker for them; open the posting for details.
- Microsoft 365 / Outlook.com mailboxes no longer accept IMAP passwords. Forward the alerts to a Gmail/GMX/web.de address.
- The Bundesagentur client follows the published OpenAPI spec (bundesAPI/jobsuche-api); the Adzuna client follows Adzuna's documented response format but could not be checked against the live API from the build environment.
- Seed career URLs are hints and were not verified against the live sites; discovery fills in the real career page on the first scan, and failures show up in the Admin page.
- ATS adapters were tested against mock responses that follow the public API formats, not against every live tenant. Monitor the Admin page after the first scans.
- The jar is large (~340 MB) because Playwright bundles its driver for all platforms.
