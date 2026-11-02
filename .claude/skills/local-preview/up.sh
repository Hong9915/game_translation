#!/bin/bash
# 최신 코드를 로컬 DB 복사본으로 띄운다: postgres 복사본 5433 · 조사 사이드카 8009 · 백엔드 8081 · 프론트 5173.
# 5173을 쓰려고 compose 프론트 컨테이너만 멈춘다(down.sh가 다시 켠다). compose postgres·백엔드(5432·8080)는 건드리지 않는다. 쓰기: ./up.sh [열 경로, 예: /review/54] [--keep-db]
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
# 5173은 Docker가 frontend 컨테이너 포트로 쥐고 있을 수 있다 — com.docker.backend를 죽이면 Docker Desktop이 통째로 꺼지므로 건너뛴다.
for port in 8081 5173; do for pid in $(lsof -ti tcp:$port -sTCP:LISTEN); do ps -p "$pid" -o comm= | grep -q com.docker || kill "$pid" 2>/dev/null || true; done; done

# 맥에 직접 띄운 postgres가 localhost:5433을 쥐고 있으면 백엔드가 복사본 대신 그쪽에 붙는다(2026-10-19) — 멈추고 알린다.
for pid in $(lsof -ti tcp:5433 -sTCP:LISTEN); do
  ps -p "$pid" -o comm= | grep -q com.docker || { echo "5433을 Docker 밖 프로세스가 쓰고 있습니다: $(ps -p "$pid" -o command= | cut -c1-160)"; exit 1; }
done

# 올린 원본 파일(compose 백엔드의 backend/data)을 복사본 저장소로 — 없으면 파일 열기·내보내기가 NoSuchFile로 실패한다.
MAIN="$(cd "$(git -C "$ROOT" rev-parse --git-common-dir)/.." && pwd)"
rsync -a "$MAIN/backend/data/" "$STATE/storage/"

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
  SUPABASE_DB_URL=jdbc:postgresql://localhost:5433/gametrans CORS_ORIGINS=http://localhost:5173 \
  STORAGE_LOCAL_DIR="$STATE/storage" JOSA_URL=http://localhost:8009 \
  nohup ./gradlew bootRun --args='--server.port=8081' > "$STATE/backend.log" 2>&1 &)
for _ in $(seq 1 120); do curl -s -o /dev/null http://localhost:8081/api/games && break; sleep 2; done
curl -sf -o /dev/null http://localhost:8081/api/games || { echo "백엔드가 뜨지 않았습니다: $STATE/backend.log"; tail -30 "$STATE/backend.log"; exit 1; }

# 6. 프론트(5173) — compose 프론트 컨테이너(옛 이미지)를 멈추고 그 자리에 vite를 띄운다
docker stop game_translation-frontend-1 >/dev/null 2>&1 || true
(cd "$ROOT/frontend" && VITE_API_BASE_URL=/api nohup npx vite --config vite.local-preview.config.ts > "$STATE/frontend.log" 2>&1 &)
for _ in $(seq 1 30); do curl -s -o /dev/null http://localhost:5173/ && break; sleep 1; done

URL="http://localhost:5173${OPEN_PATH}"
open "$URL"
echo "열림: $URL"
echo "OpenAI 키: $([ -n "$OPENAI_API_KEY" ] && echo 있음 || echo 없음 — GPT 기능은 실패)"
echo "로그: $STATE/backend.log, $STATE/frontend.log"
