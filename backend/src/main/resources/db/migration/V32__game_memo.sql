-- 게임 메모(2026-11-09): 게임마다 한 장, 팀이 같이 본다. content는 Tiptap 문서 JSON(서버는 내용을 해석하지 않는다).
-- version은 저장할 때마다 1씩 오른다 — 같이 고칠 때 남의 저장을 덮어쓰지 않게 하는 검사에 쓴다.
create table game_memo (
  game_id bigint primary key references game(id) on delete cascade,
  content text not null,
  version bigint not null,
  updated_by bigint,
  updated_at timestamptz not null default now());
