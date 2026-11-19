#!/bin/bash
# 운영 서버(Lightsail)에 새 이미지를 올린다.
# GPT 작업이 도는 중에 실행하면 백엔드가 재시작되며 작업이 끊긴다. 작업 없을 때 실행할 것.
set -euo pipefail
cd "$(dirname "$0")/.."
# 서버 주소는 git에 올리지 않는 deploy/.env.deploy에 둔다(deploy/.env.deploy.example 참고).
. deploy/.env.deploy
KEY=${KEY:-$HOME/.ssh/lightsail-gt.pem}
# 서버 .env에 mcp 설정(MCP_TOKEN_SECRET·PUBLIC_URL)이 없으면 빌드·전송·재시작 전에 멈춘다.
. deploy/check-server-env.sh
check_server_env

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

docker buildx build --platform linux/amd64 -t gt-backend:prod --load backend
docker buildx build --platform linux/amd64 -t gt-frontend:prod --load frontend
docker buildx build --platform linux/amd64 -t gt-josa:prod --load josa
docker buildx build --platform linux/amd64 -t gt-mcp:prod --load mcp
# 이미지는 파일로 저장해 rsync --partial로 보낸다. 한 줄 ssh 스트림은 핫스팟처럼 불안정한 연결에서 끊기면 처음부터 다시 보내야 한다.
docker save gt-backend:prod gt-frontend:prod gt-josa:prod gt-mcp:prod | gzip -1 > "$tmp/gt-images.tar.gz"
ok=0
for i in 1 2 3 4 5; do
  if rsync --partial --progress -e "ssh -i $KEY" "$tmp/gt-images.tar.gz" "$HOST":/tmp/gt-images.tar.gz; then
    ok=1; break
  fi
  echo "전송 실패($i/5), 다시 시도합니다" >&2
  sleep 3
done
[ "$ok" = 1 ] || { echo "이미지 전송 실패(서버는 그대로입니다)" >&2; exit 1; }
ssh -i "$KEY" "$HOST" 'gunzip -c /tmp/gt-images.tar.gz | sudo docker load && rm -f /tmp/gt-images.tar.gz'
scp -i "$KEY" deploy/docker-compose.prod.yml "$HOST":/opt/gt/docker-compose.yml
nginx_conf=$(mktemp); sed "s/__DOMAIN__/$DOMAIN/g" deploy/nginx.conf > "$nginx_conf"
scp -i "$KEY" "$nginx_conf" "$HOST":/opt/gt/nginx.conf; rm -f "$nginx_conf"
ssh -i "$KEY" "$HOST" 'cd /opt/gt && sudo docker compose up -d && sudo docker image prune -f'
