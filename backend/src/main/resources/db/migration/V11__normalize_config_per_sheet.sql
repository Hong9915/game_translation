-- 다중 시트: 정규화 설정을 (프로젝트, 시트)당 한 행으로 바꾼다. 기존 행은 옮기지 않고 그대로
-- 그 프로젝트의 첫 시트 설정이 된다. normalize_config는 작은 표지만 관례대로 빨리 실패하게 둔다.
set lock_timeout = '5s';

-- project_id는 더 이상 기본 키가 아니다. project(id)로 가는 외래 키(on delete cascade)는 따로라 그대로 남는다.
alter table normalize_config drop constraint normalize_config_pkey;
alter table normalize_config alter column project_id set not null;
alter table normalize_config add column id bigserial primary key;
-- project_id로만 찾는 조회는 이 unique 인덱스의 선두 열로 해결된다(따로 인덱스를 더 두지 않는다).
alter table normalize_config add constraint normalize_config_project_sheet_key unique (project_id, sheet_name);
