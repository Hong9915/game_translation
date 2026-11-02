-- DeepL 번역 엔진(설계 2026-10-12). 같은 원문이면 게임과 상관없이 같은 번역을 다시 쓴다 — 단어장과 어긋나면 그 문장만 다시 산다.
set lock_timeout = '5s';

-- 키는 원문의 md5 — 원문 그대로를 색인하면 긴 문장(수천 자)이 색인 한도(약 8KB)를 넘어 저장이 실패한다.
create table deepl_cache (
  src_hash    text not null,
  source_lang text not null,
  src         text not null,
  ko          text not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  primary key (src_hash, source_lang)
);

-- 게임마다 DeepL에 만들어 둔 단어장. 내용 해시가 달라지면 새로 만든다. 바로 앞 단어장(previous_glossary_id)은 그걸로 번역 중인
-- 작업이 있을 수 있어 한 번 더 바뀔 때 지운다.
create table deepl_glossary (
  game_id      bigint not null references game(id) on delete cascade,
  source_lang  text not null,
  glossary_id  text not null,
  previous_glossary_id text,
  content_hash text not null,
  created_at   timestamptz not null default now(),
  primary key (game_id, source_lang)
);

-- 파일마다 마지막으로 고른 번역 원문 언어(ZH | EN). 행 번역이 이걸 따른다.
alter table project add column translate_source_lang text not null default 'ZH';
