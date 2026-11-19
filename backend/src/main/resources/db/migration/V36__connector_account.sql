-- 커넥터(Claude in Chrome MCP) 계정. 한 계정 = 기존 검수자 한 명. 화면에서 만들지 않고 SQL로 넣는다.
--
-- 계정 넣기:
--   insert into connector_account(login_id, password_hash, reviewer_id)
--   values ('아이디', crypt('비밀번호', gen_salt('bf')), (select id from reviewer where name = '검수자 이름'));
-- 계정 막기 / 풀기:
--   update connector_account set disabled = true  where login_id = '아이디';
--   update connector_account set disabled = false where login_id = '아이디';
-- 비밀번호 바꾸기:
--   update connector_account set password_hash = crypt('새 비밀번호', gen_salt('bf')) where login_id = '아이디';
create extension if not exists pgcrypto;

create table connector_account (
    id            bigserial primary key,
    login_id      text not null unique,
    password_hash text not null,
    reviewer_id   bigint references reviewer(id) on delete set null,
    disabled      boolean not null default false,
    created_at    timestamptz not null default now()
);
