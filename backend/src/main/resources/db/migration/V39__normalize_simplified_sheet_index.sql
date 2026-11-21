-- 정규화 설정에 두 칸을 더한다: 원문을 간체로 바꿔 저장할지, 워크북 안 시트 자리(0부터).
-- 옛 행은 false·null로 남는다(null은 리뷰 표에서 순번 있는 시트 뒤, id 순). 작은 표지만 관례대로 빨리 실패하게 둔다.
set lock_timeout = '5s';

alter table normalize_config
    add column src_to_simplified boolean not null default false,
    add column sheet_index integer;
