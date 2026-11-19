create table connector_proposal (
  id bigserial primary key,
  reviewer_id bigint not null references reviewer(id) on delete cascade,
  game_id bigint not null references game(id) on delete cascade,
  title text,
  items jsonb not null,
  status text not null default 'PENDING' check (status in ('PENDING', 'APPLIED', 'DISMISSED')),
  result jsonb,
  created_at timestamptz not null default now(),
  decided_at timestamptz
);
create index connector_proposal_pending on connector_proposal (reviewer_id, game_id, created_at desc) where status = 'PENDING';
