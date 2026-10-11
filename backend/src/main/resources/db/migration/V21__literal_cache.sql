-- 표기 갈림 카드의 DeepL 직역(중→한) 저장(2026-10-07 사용자 결정). 같은 중국어면 게임과 상관없이 같은 직역을 쓴다.
-- 참고용이라 만료 없이 둔다 — 같은 구절을 DeepL에 다시 사지 않고, 재시작·배포 뒤에도 바로 보이게 한다.
set lock_timeout = '5s';

create table literal_cache (
  src        text primary key,
  ko         text not null,
  created_at timestamptz not null default now()
);
