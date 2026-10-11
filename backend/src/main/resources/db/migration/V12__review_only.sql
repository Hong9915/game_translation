-- 검수 전용 파일: 한국어 열이 이미 있어 정규화 → 검수 → 내보내기만 거친다 (용어·번역 단계 없음).
alter table project add column review_only boolean not null default false;
