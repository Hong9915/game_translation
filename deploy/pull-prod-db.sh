#!/bin/bash
# 운영 서버(Lightsail)의 DB와 올린 파일을 내 컴퓨터의 개발 환경(docker-compose, 5432/8080/5173)으로 가져온다.
# 개발 DB는 통째로 운영 내용으로 바뀐다. 운영 서버에는 읽기만 한다.
#
#   deploy/pull-prod-db.sh              운영에서 받아 개발 DB에 덮어쓰고, 개발 백엔드·프론트를 지금 코드로 다시 만든다
#   deploy/pull-prod-db.sh --dump-only  운영 DB를 backups/에 받기만 한다(백업용)
#   deploy/pull-prod-db.sh --from backups/prod-20260927-1500.dump
#                                       이미 받아 둔 파일로 개발 DB를 되돌린다(운영 접속 없음)
# 옵션: --no-files(올린 원본 파일은 안 받음) · --no-rebuild(개발 백엔드·프론트를 다시 만들지 않음) · -y(확인 안 물음)
set -euo pipefail
cd "$(dirname "$0")/.."
# 서버 주소는 git에 올리지 않는 deploy/.env.deploy에 둔다(deploy/.env.deploy.example 참고).
. deploy/.env.deploy
KEY=${KEY:-$HOME/.ssh/lightsail-gt.pem}
PG_CONTAINER=${PG_CONTAINER:-game_translation-postgres-1}
DB=gametrans

from="" dump_only=0 files=1 rebuild=1 yes=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) from="$2"; shift 2 ;;
    --dump-only) dump_only=1; shift ;;
    --no-files) files=0; shift ;;
    --no-rebuild) rebuild=0; shift ;;
    -y) yes=1; shift ;;
    *) echo "모르는 옵션: $1" >&2; exit 2 ;;
  esac
done

ssh_prod() { ssh -i "$KEY" -o ConnectTimeout=10 "$HOST" "$@"; }

# 1. 운영 DB 받기 (--from이면 건너뜀)
if [ -z "$from" ]; then
  mkdir -p backups
  from="backups/prod-$(date +%Y%m%d-%H%M).dump"
  echo "▶ 운영 DB를 받는 중 → $from"
  # -Fc: 압축된 사용자 지정 형식. 운영 DB는 잠그지 않고 그 순간의 상태를 뜬다.
  ssh_prod 'cd /opt/gt && sudo docker compose exec -T postgres pg_dump -U postgres -d gametrans -Fc' > "$from.part"
  mv "$from.part" "$from"
  echo "  받음: $(du -h "$from" | cut -f1)"
fi
[ -s "$from" ] || { echo "덤프 파일이 없거나 비어 있습니다: $from" >&2; exit 1; }
[ "$dump_only" = 1 ] && { echo "✔ 받기만 했습니다: $from"; exit 0; }

# 2. 확인
if [ "$yes" != 1 ]; then
  read -r -p "개발 DB($PG_CONTAINER)의 내용을 전부 지우고 $from 으로 바꿉니다. 계속할까요? [y/N] " ans
  [ "$ans" = y ] || [ "$ans" = Y ] || { echo "취소했습니다."; exit 1; }
fi

# 3. 개발 DB 바꾸기. 백엔드가 붙어 있으면 DB를 못 지우므로 먼저 멈춘다.
if [ "$PG_CONTAINER" = game_translation-postgres-1 ]; then docker compose stop backend >/dev/null 2>&1 || true; fi
echo "▶ 개발 DB를 바꾸는 중"
docker exec "$PG_CONTAINER" psql -U postgres -d postgres -qc "drop database if exists $DB with (force)" -c "create database $DB"
# --no-owner: 운영과 개발의 DB 사용자 이름이 달라도 들어가게 한다
docker exec -i "$PG_CONTAINER" pg_restore -U postgres -d "$DB" --no-owner < "$from"
ver=$(docker exec "$PG_CONTAINER" psql -U postgres -d "$DB" -Atc "select max(version::int) from flyway_schema_history where success")
rows=$(docker exec "$PG_CONTAINER" psql -U postgres -d "$DB" -Atc "select count(*) from segment")
echo "  DB 구조 V${ver} · 행 ${rows}개"

# 4. 올린 원본 파일(backend/data). 내보내기·다시 정규화에 쓰인다. 바뀐 파일만 받는다.
if [ "$files" = 1 ] && [ "$PG_CONTAINER" = game_translation-postgres-1 ]; then
  echo "▶ 올린 원본 파일을 맞추는 중 → backend/data/"
  mkdir -p backend/data
  rsync -az --delete --rsync-path='sudo rsync' -e "ssh -i $KEY" "$HOST:/opt/gt/data/" backend/data/
fi

# 5. 개발 백엔드·프론트를 지금 코드로 다시 만든다. 운영 DB 구조(V${ver})가 옛 백엔드보다 새것일 수 있다.
if [ "$PG_CONTAINER" = game_translation-postgres-1 ]; then
  if [ "$rebuild" = 1 ]; then
    echo "▶ 개발 백엔드·프론트를 다시 만드는 중"
    docker compose up -d --build backend frontend
  else
    docker compose start backend
  fi
fi
echo "✔ 끝. 개발 환경: http://localhost:5173 (받은 파일: $from)"
