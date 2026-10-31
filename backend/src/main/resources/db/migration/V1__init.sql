-- ============================================================
-- Companies and their official career pages
-- ============================================================
create table company (
    id                    bigserial primary key,
    name                  text not null unique,
    website               text,
    career_url            text,
    hq_city               text,
    industry              text,
    size                  text,
    ats_type              text not null default 'UNKNOWN',
    ats_tenant            text,
    status                text not null default 'ACTIVE',   -- ACTIVE / NEEDS_REVIEW / BROKEN / DISABLED
    status_message        text,
    source                text not null default 'SEED',     -- SEED / ADMIN / DISCOVERY
    scan_interval_hours   int  not null default 12,
    consecutive_failures  int  not null default 0,
    last_scanned_at       timestamptz,
    next_scan_at          timestamptz not null default now(),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);
create index company_next_scan_idx on company (next_scan_at) where status in ('ACTIVE', 'NEEDS_REVIEW');

-- ============================================================
-- Job postings (student IT roles only)
-- ============================================================
create table job_posting (
    id                bigserial primary key,
    company_id        bigint not null references company(id) on delete cascade,
    external_id       text,
    url               text not null,
    apply_url         text,
    title             text not null,
    description_html  text,
    description_text  text,
    locations         text[] not null default '{}',
    remote            boolean not null default false,
    in_bw             boolean,
    student_types     text[] not null default '{}',
    it_fields         text[] not null default '{}',
    tech_terms        text[] not null default '{}',
    german_level      text not null default 'UNKNOWN',
    english_level     text not null default 'UNKNOWN',
    posting_language  text,
    language_source   text not null default 'RULES',
    contact_email     text,
    posted_at         timestamptz,
    first_seen_at     timestamptz not null default now(),
    last_seen_at      timestamptz not null default now(),
    missed_scans      int not null default 0,
    status            text not null default 'OPEN',      -- OPEN / CLOSED
    fingerprint       text not null unique
);
create index job_status_idx on job_posting (status, first_seen_at desc);
create index job_company_idx on job_posting (company_id);
create index job_student_types_idx on job_posting using gin (student_types);
create index job_it_fields_idx on job_posting using gin (it_fields);

-- ============================================================
-- Users and profiles
-- ============================================================
create table app_user (
    id             bigserial primary key,
    email          text not null unique,
    password_hash  text not null,
    display_name   text,
    role           text not null default 'USER',
    created_at     timestamptz not null default now()
);

create table user_profile (
    user_id             bigint primary key references app_user(id) on delete cascade,
    full_name           text,
    email               text,
    phone               text,
    street              text,
    postal_code         text,
    city                text,
    linkedin_url        text,
    github_url          text,
    website_url         text,
    university          text,
    study_program       text,
    degree              text,
    semester            int,
    available_from      text,
    hours_per_week      int,
    skills              text[] not null default '{}',
    languages           jsonb not null default '[]',   -- [{"language":"German","level":"B2"}]
    summary             text,
    preferences         jsonb not null default '{}',   -- filters + defaults, see UserPreferences
    ai_consent          boolean not null default false,
    updated_at          timestamptz not null default now()
);

-- ============================================================
-- Documents (CVs, cover letters, certificates ...)
-- ============================================================
create table document (
    id               bigserial primary key,
    user_id          bigint not null references app_user(id) on delete cascade,
    kind             text not null,                    -- CV / COVER_LETTER / TRANSCRIPT / CERTIFICATE / OTHER
    title            text not null,
    filename         text not null,
    content_type     text not null,
    storage_key      text not null,
    size_bytes       bigint not null,
    original         boolean not null default true,    -- false = generated / tailored
    derived_from_id  bigint references document(id) on delete set null,
    job_id           bigint references job_posting(id) on delete set null,
    extracted_text   text,
    content_json     jsonb,                            -- editable content of generated documents
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);
create index document_user_idx on document (user_id);

-- ============================================================
-- Match scores (AI part; keyword score is computed live)
-- ============================================================
create table match_score (
    user_id      bigint not null references app_user(id) on delete cascade,
    job_id       bigint not null references job_posting(id) on delete cascade,
    ai_score     int,
    ai_summary   text,
    ai_details   jsonb,
    computed_at  timestamptz not null default now(),
    primary key (user_id, job_id)
);

-- ============================================================
-- Applications
-- ============================================================
create table application (
    id                        bigserial primary key,
    user_id                   bigint not null references app_user(id) on delete cascade,
    job_id                    bigint not null references job_posting(id) on delete cascade,
    company_id                bigint not null references company(id) on delete cascade,
    status                    text not null default 'DRAFT',
    mode                      text not null default 'AUTO_FORM',   -- AUTO_FORM / MANUAL / EMAIL
    cv_document_id            bigint references document(id) on delete set null,
    cover_letter_id           bigint references document(id) on delete set null,
    extra_document_ids        bigint[] not null default '{}',
    used_original_docs        boolean not null default true,
    fill_report               jsonb,
    overrides                 jsonb,
    consents                  jsonb,
    screenshot_key            text,
    confirmation_key          text,
    manual_reason             text,
    error_message             text,
    notes                     text,
    follow_up_on              date,
    approved_at               timestamptz,
    submitted_at              timestamptz,
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now(),
    unique (user_id, job_id)
);
create index application_user_idx on application (user_id, status);

-- ============================================================
-- Scan queue and logs
-- ============================================================
create table scan_task (
    id           bigserial primary key,
    company_id   bigint not null references company(id) on delete cascade,
    kind         text not null default 'SCAN',      -- SCAN / DISCOVER
    status       text not null default 'PENDING',   -- PENDING / RUNNING / DONE / FAILED
    attempts     int not null default 0,
    run_after    timestamptz not null default now(),
    locked_by    text,
    locked_at    timestamptz,
    error        text,
    created_at   timestamptz not null default now(),
    finished_at  timestamptz
);
create index scan_task_pending_idx on scan_task (run_after) where status = 'PENDING';
create unique index scan_task_one_open_per_company on scan_task (company_id, kind) where status in ('PENDING', 'RUNNING');

create table scan_run_log (
    id           bigserial primary key,
    company_id   bigint not null references company(id) on delete cascade,
    adapter      text,
    started_at   timestamptz not null,
    finished_at  timestamptz not null,
    listed       int not null default 0,
    accepted     int not null default 0,
    new_jobs     int not null default 0,
    closed_jobs  int not null default 0,
    error        text
);
create index scan_run_log_company_idx on scan_run_log (company_id, started_at desc);

-- ============================================================
-- AI usage quota
-- ============================================================
create table ai_usage (
    user_id  bigint not null references app_user(id) on delete cascade,
    day      date not null,
    calls    int not null default 0,
    primary key (user_id, day)
);
