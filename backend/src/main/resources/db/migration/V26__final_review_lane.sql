-- 최종 리뷰 레인(설계 2026-10-17): 시트 설정의 열 짝에 fixCol이 있으면 그 시트 행은 최종 리뷰 레인.
-- 같은 원문 묶음 기능(×N·함께 수정·불일치·이어받기)이 레인끼리만 돈다. final_mark는 사람이 켜는 "최종 수정" 표시.
alter table segment add column final_review boolean not null default false;
alter table segment add column final_mark boolean not null default false;
update segment s set final_review = true
  from normalize_config c
 where c.project_id = s.project_id and c.sheet_name = s.sheet_name
   and exists (select 1 from jsonb_array_elements(c.pairs) e where e->>'fixCol' is not null);
