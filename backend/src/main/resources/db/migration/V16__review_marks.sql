-- 검수 체크(1차·2차·확인 필요)와 비고(설계 2026-09-28). 완료(done)는 새 화면에서 쓰지 않는다 — 되돌릴 수 있게 칸은 남기고,
-- 이미 완료였던 행은 1차·2차를 본 것으로 옮긴다. default가 있는 add column은 PG 11+에서 테이블을 다시 쓰지 않는다.
set lock_timeout = '5s';

alter table segment add column review1 boolean not null default false;
alter table segment add column review2 boolean not null default false;
alter table segment add column needs_check boolean not null default false;
alter table segment add column note text;

update segment set review1 = true, review2 = true where done = true;
