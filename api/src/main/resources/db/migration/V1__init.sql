-- ============================================================================
-- V1 初始表结构
--
-- 设计要点：
-- 1. 画布的结构数据（代码块、坐标、视口）整体存 canvases.data jsonb；
--    问答拆成关系表，便于按画布查询、重答、审计。
-- 2. 主键统一 uuid + gen_random_uuid()（PostgreSQL 13+ 内置，无需扩展）。
-- 3. 时间统一 timestamptz，默认 now()。
-- 4. 邮箱不用 citext 扩展，改 text + lower(email) 唯一索引：
--    行为等价，且少一个扩展依赖（Neon 换库、将来换托管都不用管扩展权限）。
-- ============================================================================

-- 用户 -----------------------------------------------------------------------
create table users (
    id                uuid primary key default gen_random_uuid(),
    email             text        not null,
    password_hash     text        not null,
    display_name      text,
    status            text        not null default 'active',
    email_verified_at timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint users_status_chk check (status in ('active', 'disabled')),
    constraint users_email_len_chk check (char_length(email) between 3 and 254)
);

-- 同一邮箱大小写不同视为同一账号
create unique index users_email_lower_uidx on users (lower(email));

comment on table users is '账号；password 用 BCrypt，永不存明文';
comment on column users.email_verified_at is '邮箱验证通过时间，未验证为 null';

-- 邮箱验证码 / 图形码会话之外的 6 位数字码 --------------------------------
create table email_codes (
    id          uuid primary key default gen_random_uuid(),
    email       text        not null,
    code_hash   text        not null,
    purpose     text        not null,
    expires_at  timestamptz not null,
    consumed_at timestamptz,
    ip          text,
    attempts    integer     not null default 0,
    created_at  timestamptz not null default now(),
    constraint email_codes_purpose_chk check (purpose in ('register', 'reset_password')),
    constraint email_codes_attempts_chk check (attempts >= 0)
);

create index email_codes_email_idx on email_codes (lower(email), created_at desc);
-- 过期清理走「懒删除」，这个索引让扫描过期行不至于全表
create index email_codes_expiry_idx on email_codes (expires_at);

comment on table email_codes is '一次性验证码；只存哈希，明文仅出现在邮件里';

-- 画布 -----------------------------------------------------------------------
create table canvases (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    title      text        not null default '未命名画布',
    data       jsonb       not null default '{}'::jsonb,
    version    integer     not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint canvases_title_len_chk check (char_length(title) <= 120)
);

-- 侧栏列表：按最近更新倒序，软删除的排除在外
create index canvases_user_updated_idx
    on canvases (user_id, updated_at desc)
    where deleted_at is null;

comment on table canvases is '画布；data 存视口 + 代码块数组的完整快照，version 用于乐观锁';
comment on column canvases.data is '{"viewport":{...},"blocks":[...],"bg":{...}}';

-- 提问 -----------------------------------------------------------------------
create table questions (
    id         uuid primary key default gen_random_uuid(),
    canvas_id  uuid        not null references canvases (id) on delete cascade,
    block_id   text        not null,
    anchor     jsonb       not null,
    text       text        not null,
    side       text        not null default 'right',
    side_mode  text        not null default 'auto',
    box_x      double precision not null default 0,
    box_y      double precision not null default 0,
    pinned     boolean     not null default false,
    collapsed  boolean     not null default false,
    stale      boolean     not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint questions_side_chk check (side in ('left', 'right')),
    constraint questions_side_mode_chk check (side_mode in ('auto', 'manual')),
    constraint questions_text_len_chk check (char_length(text) between 1 and 2000)
);

create index questions_canvas_idx on questions (canvas_id, created_at);

comment on column questions.anchor is '像素无关锚点：blockId + startOffset/endOffset + startLine/startChar + quote';
comment on column questions.stale is '代码被编辑后原文片段找不到，锚点失效，仅保留问答';

-- 回答 -----------------------------------------------------------------------
create table answers (
    id                uuid primary key default gen_random_uuid(),
    question_id       uuid        not null references questions (id) on delete cascade,
    content           text        not null default '',
    status            text        not null default 'streaming',
    provider          text,
    model             text,
    prompt_tokens     integer,
    completion_tokens integer,
    error             text,
    created_at        timestamptz not null default now(),
    constraint answers_status_chk check (status in ('streaming', 'done', 'error'))
);

create index answers_question_idx on answers (question_id, created_at desc);

comment on table answers is '一次提问可能有多条回答（重答），按 created_at 取最新';

-- 用户自带 AI 配置 -----------------------------------------------------------
create table user_ai_settings (
    user_id        uuid primary key references users (id) on delete cascade,
    provider       text        not null,
    base_url       text        not null,
    model          text        not null,
    api_key_cipher bytea,
    api_key_mask   text,
    extra          jsonb       not null default '{}'::jsonb,
    updated_at     timestamptz not null default now(),
    constraint user_ai_settings_base_url_chk check (base_url like 'https://%')
);

comment on table user_ai_settings is '用户自填模型；api_key_cipher 为 AES-GCM 密文，掩码仅供回显';
