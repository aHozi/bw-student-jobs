-- ============================================================
-- Jobs from external sources: job-alert e-mails (LinkedIn, StepStone, Indeed),
-- Bundesagentur fuer Arbeit API and Adzuna API.
-- ============================================================
alter table job_posting
    add column sources        text[]  not null default '{CAREER_PAGE}',   -- CAREER_PAGE / EMAIL_ALERT / ARBEITSAGENTUR / ADZUNA
    add column platforms      text[]  not null default '{COMPANY}',       -- COMPANY / LINKEDIN / STEPSTONE / INDEED / ARBEITSAGENTUR / ADZUNA
    add column public_visible boolean not null default true,              -- false = only users in job_visibility can see it
    add column apply_is_board boolean not null default false,             -- apply link points to a job board (manual apply)
    add column snippet_only   boolean not null default false;             -- only a short description is known
create index job_sources_idx on job_posting using gin (sources);

-- E-mail alert jobs are private to the users whose inbox they came from.
create table job_visibility (
    user_id  bigint not null references app_user(id) on delete cascade,
    job_id   bigint not null references job_posting(id) on delete cascade,
    added_at timestamptz not null default now(),
    primary key (user_id, job_id)
);

alter table company
    add column name_key     text,
    add column scan_enabled boolean not null default true;
create unique index company_name_key_idx on company (name_key);

create table source_run_log (
    id           bigserial primary key,
    source       text not null,
    user_id      bigint references app_user(id) on delete cascade,
    started_at   timestamptz not null,
    finished_at  timestamptz not null default now(),
    fetched      int not null default 0,
    accepted     int not null default 0,
    new_jobs     int not null default 0,
    error        text
);
create index source_run_log_idx on source_run_log (source, started_at desc);

create table email_import_settings (
    user_id       bigint primary key references app_user(id) on delete cascade,
    host          text not null,
    port          int  not null default 993,
    username      text not null,
    password_enc  text not null,
    folder        text not null default 'INBOX',
    enabled       boolean not null default true,
    uid_validity  bigint,
    last_uid      bigint,
    last_sync_at  timestamptz,
    last_error    text,
    updated_at    timestamptz not null default now()
);

create table email_import_seen (
    user_id     bigint not null references app_user(id) on delete cascade,
    message_id  text not null,
    seen_at     timestamptz not null default now(),
    primary key (user_id, message_id)
);
