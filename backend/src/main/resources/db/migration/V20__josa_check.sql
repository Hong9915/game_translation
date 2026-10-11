-- 조사 검사(설계 2026-10-07 §1). josa_checked: null = 검사 대상 아님(아무도 안 열었고 번역도 안 바뀜),
-- false = 검사 대기, true = 검사함. 누군가 파일을 열면(워크스페이스 요약) 그 파일의 null 행이 false가 된다.
-- latest_ko가 바뀌면 검사 대기로 돌리고 무시도 푼다 — 저장 경로(직접 수정·일괄·단어장·되돌리기·재번역)를
-- 하나도 고치지 않고 모든 변경을 잡는다.
set lock_timeout = '5s';

alter table segment
  add column josa_checked boolean,
  add column josa_hint    jsonb,
  add column josa_ignored boolean not null default false;

create function segment_josa_reset() returns trigger language plpgsql as $$
begin
  if new.latest_ko is distinct from old.latest_ko then
    -- 처음 번역이 들어오는 것(파일 정규화·첫 GPT 번역)은 수정이 아니다 — 아무도 안 연 파일이면 대상 아님 그대로
    if not (old.latest_ko is null and old.josa_checked is null) then
      new.josa_checked := false;
    end if;
    new.josa_hint := null;
    new.josa_ignored := false;
  end if;
  return new;
end $$;

create trigger segment_josa_reset before update of latest_ko on segment
  for each row execute function segment_josa_reset();

create index segment_josa_pending_idx on segment (id) where josa_checked = false and skipped = false;
create index segment_josa_hint_idx on segment (project_id, sheet_name) where josa_hint is not null and josa_ignored = false;
