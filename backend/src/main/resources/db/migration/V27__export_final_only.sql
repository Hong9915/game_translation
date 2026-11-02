-- 최종 수정분만 내보내기(2026-10-19): 최종 리뷰 시트에서 최종 수정 표시를 켠 행만 담은 수정본. 차수는 파일 전체 수정본과 따로 센다.
set lock_timeout = '5s';
alter table export_version add column final_only boolean not null default false;
drop index export_version_project_sheet_version_key;
create unique index export_version_project_sheet_version_key on export_version (project_id, coalesce(sheet_name, ''), final_only, version);
