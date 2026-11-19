#!/bin/bash
# mcp 컨테이너만 운영 서버에 올린다. 백엔드는 다시 뜨지 않는다(돌고 있는 번역·GPT 작업이 끊기지 않는다).
# 이미지는 파일로 저장해 rsync --partial로 보낸다. 핫스팟처럼 불안정한 연결에서 긴 ssh 스트림이 끊기는 걸 피하려는 것.
set -euo pipefail
cd "$(dirname "$0")/.."
# 서버 주소는 git에 올리지 않는 deploy/.env.deploy에 둔다(deploy/.env.deploy.example 참고).
. deploy/.env.deploy
KEY=${KEY:-$HOME/.ssh/lightsail-gt.pem}
SSH_OPTS="ssh -i $KEY"
# 서버 .env에 mcp 설정(MCP_TOKEN_SECRET·PUBLIC_URL)이 없으면 빌드·전송·재시작 전에 멈춘다.
. deploy/check-server-env.sh
check_server_env

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

docker buildx build --platform linux/amd64 -t gt-mcp:prod --load mcp
docker save gt-mcp:prod | gzip -1 > "$tmp/gt-mcp.tar.gz"

ok=0
for i in 1 2 3; do
  if rsync --partial --progress -e "$SSH_OPTS" "$tmp/gt-mcp.tar.gz" "$HOST":/tmp/gt-mcp.tar.gz; then
    ok=1; break
  fi
  echo "전송 실패($i/3), 다시 시도합니다" >&2
  sleep 3
done
[ "$ok" = 1 ] || { echo "이미지 전송 실패" >&2; exit 1; }

ssh -i "$KEY" "$HOST" 'gunzip -c /tmp/gt-mcp.tar.gz | sudo docker load && rm -f /tmp/gt-mcp.tar.gz'
scp -i "$KEY" deploy/docker-compose.prod.yml "$HOST":/opt/gt/docker-compose.yml
nginx_conf=$(mktemp); sed "s/__DOMAIN__/$DOMAIN/g" deploy/nginx.conf > "$nginx_conf"
scp -i "$KEY" "$nginx_conf" "$HOST":/opt/gt/nginx.conf; rm -f "$nginx_conf"
ssh -i "$KEY" "$HOST" 'cd /opt/gt && sudo docker compose up -d --no-deps mcp && sudo docker compose exec -T frontend nginx -s reload && sudo docker image prune -f'
