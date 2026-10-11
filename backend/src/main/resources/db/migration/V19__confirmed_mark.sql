-- 확정 체크(2026-10-06 사용자 결정). 같은 원문 전파·단어장 일괄 적용·되돌리기가 건너뛰는 행을 2차 체크에서 확정으로 옮긴다.
-- 2차 체크는 이제 표시일 뿐이다. 기존 2차 행은 확정으로 옮기지 않는다(사용자 결정) — 모든 행이 확정 없이 시작한다.
set lock_timeout = '5s';

alter table segment add column confirmed boolean not null default false;
