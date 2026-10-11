-- 단어장 추천 창 표기 갈림의 구절 분류(2026-10-07 사용자 결정: 캐릭터·아이템·스킬·장소·능력치·일반명사·동사·색깔 등으로 골라 보기).
-- GPT가 한 번 매긴 분류를 게임별로 둔다 — 같은 구절을 다시 묻지 않는다. 보기용이라 틀리면 지워서 다시 매기면 된다.
set lock_timeout = '5s';

create table glossary_category (
  game_id    bigint not null references game(id) on delete cascade,
  src        text not null,
  category   text not null,
  created_at timestamptz not null default now(),
  primary key (game_id, src)
);
