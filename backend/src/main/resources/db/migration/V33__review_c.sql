-- 검수 체크 C(2026-11-09). A·B와 같은 표시용 체크로, 행을 잠그지 않는다. default가 있는 add column은 테이블을 다시 쓰지 않는다.
set lock_timeout = '5s';

alter table segment add column review3 boolean not null default false;
