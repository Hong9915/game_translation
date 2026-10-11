-- 기존 한국어 열이 있는 교정용 파일 지원: 셀의 원래 값과 건너뛴 이유를 남긴다.
alter table segment add column existing_ko_text text;
alter table segment add column skip_reason text;
alter table normalize_config add column retranslate_existing boolean not null default false;
