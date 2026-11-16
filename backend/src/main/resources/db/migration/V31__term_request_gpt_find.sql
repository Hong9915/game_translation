-- 단어장 요청 GPT 표기 확인(2026-11-02): 확인을 마친 항목 표시와 GPT가 남긴 까닭.
alter table glossary_request_item add column gpt_checked boolean not null default false,
                                  add column gpt_find_reason text;
