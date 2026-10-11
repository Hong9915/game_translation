#!/bin/bash
# up.sh로 띄운 것을 모두 끈다. 로컬 compose 스택은 그대로 둔다.
for port in 8081 5178; do lsof -ti tcp:$port -sTCP:LISTEN | xargs kill 2>/dev/null || true; done
docker rm -f gt-verify-pg gt-verify-josa >/dev/null 2>&1 || true
echo "꺼짐: 백엔드 8081 · 프론트 5178 · DB 복사본 5433 · 조사 사이드카 8009"
