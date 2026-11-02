-- 시트 하나만 내보내기(설계 2026-10-12 §4). null = 파일 전체 수정본(기존 행 모두). 차수는 (프로젝트, sheet_name)마다 따로 센다.
set lock_timeout = '5s';
alter table export_version add column sheet_name text;
-- 차수가 (프로젝트, 시트)마다 따로라 (프로젝트, 차수)만으로는 겹친다. 파일 전체(null)는 ''로 묶어 같은 차수가 두 번 생기지 않게 한다.
alter table export_version drop constraint export_version_project_id_version_key;
create unique index export_version_project_sheet_version_key on export_version (project_id, coalesce(sheet_name, ''), version);
