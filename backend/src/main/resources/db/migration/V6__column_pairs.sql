-- 프로젝트 하나에 원문→한국어 열 쌍을 여러 개 둔다. 기존 설정은 쌍 하나짜리로 옮긴다.
alter table normalize_config add column pairs jsonb not null default '[]'::jsonb;
update normalize_config set pairs = jsonb_build_array(jsonb_strip_nulls(jsonb_build_object(
  'srcCol', src_col, 'enCol', en_col, 'koColMode', ko_col_mode, 'koCol', ko_col, 'koHeader', ko_header)));
alter table normalize_config
  drop column src_col, drop column en_col, drop column ko_col_mode, drop column ko_col, drop column ko_header;

alter table segment add column pair_index int not null default 0;
alter table segment drop constraint segment_project_id_sheet_name_row_no_key;
alter table segment add constraint segment_project_sheet_row_pair_key unique (project_id, sheet_name, row_no, pair_index);
