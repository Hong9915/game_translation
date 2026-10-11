-- 검수 워크스페이스(설계: docs/superpowers/specs/2026-09-23-review-workspace-design.md).
-- 1) 목록을 DB에서 페이징하려고 최신 한국어(Fix)를 segment에 캐시한다. 리비전을 넣는 코드 경로가 여럿이라
--    Java가 아니라 트리거가 유지한다 — 어느 경로로 넣어도 어긋나지 않는다.
-- 2) 게임별 새 용어집(Phase 2가 쓴다). 옛 game_term과 별개.
set lock_timeout = '5s';

alter table segment add column latest_ko text;

create or replace function segment_sync_latest_ko() returns trigger language plpgsql as $$
declare sid bigint;
begin
  sid := coalesce(new.segment_id, old.segment_id);
  -- 세그먼트가 cascade로 지워지는 중이면 0행 갱신으로 끝난다(에러 없음)
  update segment s
     set latest_ko = (select r.text from translation_revision r where r.segment_id = sid order by r.version desc limit 1)
   where s.id = sid;
  return null;
end $$;

-- update of text, version에만 반응한다: TranslationRevisionRepository.moveReviewer는 검토자 삭제 시
-- reviewer_id만 벌크로 바꾸는데, 예전엔 그것도 리비전 건수만큼 트리거를 태워 매번 segment 1행을
-- 다시 잠그고 값이 바뀌지도 않는 재계산을 했다(검토자 삭제가 리비전 많은 프로젝트에서 느려짐).
create trigger translation_revision_latest_ko
  after insert or delete or update of text, version on translation_revision
  for each row execute function segment_sync_latest_ko();

-- 기존 데이터 백필. 세그먼트마다 상관 서브쿼리(인덱스 탐색)를 한 번씩 돌리는 대신, 리비전이 있는
-- segment_id만 한 번의 distinct on으로 골라 그 값으로만 갱신한다 — Flyway 트랜잭션이 백엔드 기동을
-- 막는 시간과, 4GB 프로덕션 박스에서 세그먼트 전체 행에 거는 잠금 수를 줄인다.
update segment s set latest_ko = r.text
  from (select distinct on (segment_id) segment_id, text
          from translation_revision order by segment_id, version desc) r
 where r.segment_id = s.id;

create table glossary (
  id bigserial primary key,
  game_id bigint not null references game(id) on delete cascade,
  src text not null,
  ko text,
  prev_ko text,
  note text,
  created_by bigint references reviewer(id),
  updated_at timestamptz not null default now(),
  unique (game_id, src)
);
