#!/bin/bash
# up.sh로 띄운 것을 모두 끄고, 멈춰 둔 compose 프론트 컨테이너를 다시 켠다.
# 5173은 Docker가 frontend 컨테이너 포트로 쥐고 있을 수 있다 — com.docker.backend를 죽이면 Docker Desktop이 통째로 꺼지므로 건너뛴다.
for port in 8081 5173; do for pid in $(lsof -ti tcp:$port -sTCP:LISTEN); do ps -p "$pid" -o comm= | grep -q com.docker || kill "$pid" 2>/dev/null || true; done; done
docker rm -f gt-verify-pg gt-verify-josa >/dev/null 2>&1 || true
docker start game_translation-frontend-1 >/dev/null 2>&1 || true
echo "꺼짐: 백엔드 8081 · 프론트 5173(vite) · DB 복사본 5433 · 조사 사이드카 8009 — compose 프론트 5173 다시 켬"
