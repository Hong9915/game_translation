#!/bin/bash
# 최신 코드를 로컬 DB 복사본으로 띄운다: postgres 복사본 5433 · 조사 사이드카 8009 · 백엔드 8081 · 프론트 5178.
# 로컬 compose 스택(5432·8080·5173)은 건드리지 않는다. 쓰기: ./up.sh [열 경로, 예: /review/54] [--keep-db]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
STATE="${TMPDIR:-/tmp}/gt-local-preview"
OPEN_PATH="/"
KEEP_DB=0
for a in "$@"; do
  case "$a" in
    --keep-db) KEEP_DB=1 ;;
    *) OPEN_PATH="$a" ;;
  esac
done
mkdir -p "$STATE/storage"

# 1. Docker
if ! docker info >/dev/null 2>&1; then
  open -a Docker
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
fi
# 로컬 postgres는 재시작 정책이 없어 Docker를 다시 켜면 꺼져 있다 — 복사 원본이라 켠다
docker start game_translation-postgres-1 >/dev/null

# 2. 앞서 띄운 백엔드·프론트를 끈다
for port in 8081 5178; do lsof -ti tcp:$port -sTCP:LISTEN | xargs kill 2>/dev/null || true; done

# 3. DB 복사본(5433)
if [ "$KEEP_DB" = 0 ] || ! docker ps --format '{{.Names}}' | grep -qx gt-verify-pg; then
  docker rm -f gt-verify-pg >/dev/null 2>&1 || true
  docker run -d --name gt-verify-pg -p 5433:5432 -e POSTGRES_DB=gametrans -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres:16-alpine >/dev/null
  docker exec game_translation-postgres-1 pg_dump -U postgres -d gametrans -Fc > "$STATE/live.dump"
  for _ in $(seq 1 30); do docker exec gt-verify-pg pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
  docker cp "$STATE/live.dump" gt-verify-pg:/tmp/live.dump
  docker exec gt-verify-pg pg_restore -U postgres -d gametrans --no-owner /tmp/live.dump >/dev/null 2>&1 || true
fi

# 4. 조사 사이드카(8009) — 지금 josa/ 코드로 만든 이미지
if docker ps -a --format '{{.Names}}' | grep -qx gt-verify-josa; then docker rm -f gt-verify-josa >/dev/null; fi
docker build -q -t gt-verify-josa-img "$ROOT/josa" >/dev/null
docker run -d --name gt-verify-josa -p 8009:8000 gt-verify-josa-img >/dev/null

# 5. 백엔드(8081). 키는 로컬 compose 백엔드 설정에서 가져온다(화면에 찍지 않는다)
OPENAI_API_KEY="$(docker exec game_translation-backend-1 printenv OPENAI_API_KEY 2>/dev/null || true)"
DEEPL_AUTH_KEY="$(docker exec game_translation-backend-1 printenv DEEPL_AUTH_KEY 2>/dev/null || true)"
(cd "$ROOT/backend" && OPENAI_API_KEY="$OPENAI_API_KEY" DEEPL_AUTH_KEY="$DEEPL_AUTH_KEY" \
  SUPABASE_DB_URL=jdbc:postgresql://localhost:5433/gametrans CORS_ORIGINS=http://localhost:5178 \
  STORAGE_LOCAL_DIR="$STATE/storage" JOSA_URL=http://localhost:8009 \
  nohup ./gradlew bootRun --args='--server.port=8081' > "$STATE/backend.log" 2>&1 &)
for _ in $(seq 1 120); do curl -s -o /dev/null http://localhost:8081/api/games && break; sleep 2; done
curl -sf -o /dev/null http://localhost:8081/api/games || { echo "백엔드가 뜨지 않았습니다: $STATE/backend.log"; tail -30 "$STATE/backend.log"; exit 1; }

# 6. 프론트(5178)
(cd "$ROOT/frontend" && nohup npx vite --config vite.local-preview.config.ts > "$STATE/frontend.log" 2>&1 &)
for _ in $(seq 1 30); do curl -s -o /dev/null http://localhost:5178/ && break; sleep 1; done

URL="http://localhost:5178${OPEN_PATH}"
open "$URL"
echo "열림: $URL"
echo "OpenAI 키: $([ -n "$OPENAI_API_KEY" ] && echo 있음 || echo 없음 — GPT 기능은 실패)"
echo "로그: $STATE/backend.log, $STATE/frontend.log"
