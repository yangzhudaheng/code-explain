-- ============================================================================
-- V2：图形码答案落库 + 问答与前端 id 对齐
--
-- 两件事都是 M4（接 AI）的前置条件，不能等到出问题再补：
--
-- 1. 图形码答案原来存在 JVM 的 Caffeine 里。Vercel 容器「闲置 5 分钟缩容、按需冷启动」，
--    多实例之间也不共享内存，于是用户点开登录页拿到图、慢慢输完再提交时，
--    请求可能落到另一个实例上——答案是 null，用户看到「验证码已过期」，
--    而他手里那张图刚生成不到一分钟。这类 bug 在本地永远复现不了，只能靠落库解决。
--
-- 2. 画布快照里的问答 id 是前端生成的 `q_lx8f2a`，而 questions.id 是 uuid。
--    接 AI 时要「前端的那条问句」对应「库里的调用记录」，必须有共同标识。
--    加 client_id 而不是把主键改成 text：uuid 主键有它的价值（不可枚举、索引小），
--    换掉会让所有外键跟着改，风险远大于收益。
-- ============================================================================

-- 图形码会话 ----------------------------------------------------------------
create table captcha_sessions (
    id          text        primary key,
    answer_hash text        not null,
    ip          text,
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now()
);

-- 懒删除（每次签发顺手清一批）和过期判断都按这个索引扫，避免全表
create index captcha_sessions_expiry_idx on captcha_sessions (expires_at);

comment on table captcha_sessions is '图形码一次性会话；只存答案的 SHA-256，5 分钟有效，校验即删';
comment on column captcha_sessions.answer_hash is '大写归一后取 SHA-256；4 位字符空间太小，用 BCrypt 每次校验 60ms 不值得，靠短 TTL + 一次性消费兜底';
comment on column captcha_sessions.ip is '签发时的客户端 IP，仅用于排查刷码，不参与鉴权';

-- 问答与前端 id 的对应关系 ---------------------------------------------------
alter table questions add column client_id text;

comment on column questions.client_id is '前端生成的 q_xxx；画布快照里的问答靠它回指这条记录，不参与渲染';

-- 同一画布内前端 id 唯一；client_id 允许为空（兼容没有前端 id 的历史数据/内部写入）
create unique index questions_client_uidx
    on questions (canvas_id, client_id)
    where client_id is not null;
