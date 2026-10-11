#!/bin/bash
# 운영 서버(Lightsail)에 새 이미지를 올린다.
# GPT 작업이 도는 중에 실행하면 백엔드가 재시작되며 작업이 끊긴다. 작업 없을 때 실행할 것.
set -euo pipefail
cd "$(dirname "$0")/.."
# 서버 주소는 git에 올리지 않는 deploy/.env.deploy에 둔다(deploy/.env.deploy.example 참고).
. deploy/.env.deploy
KEY=${KEY:-$HOME/.ssh/lightsail-gt.pem}

docker buildx build --platform linux/amd64 -t gt-backend:prod --load backend
docker buildx build --platform linux/amd64 -t gt-frontend:prod --load frontend
docker buildx build --platform linux/amd64 -t gt-josa:prod --load josa
docker save gt-backend:prod gt-frontend:prod gt-josa:prod | gzip -1 | ssh -i "$KEY" "$HOST" 'gunzip | sudo docker load'
scp -i "$KEY" deploy/docker-compose.prod.yml "$HOST":/opt/gt/docker-compose.yml
nginx_conf=$(mktemp); sed "s/__DOMAIN__/$DOMAIN/g" deploy/nginx.conf > "$nginx_conf"
scp -i "$KEY" "$nginx_conf" "$HOST":/opt/gt/nginx.conf; rm -f "$nginx_conf"
ssh -i "$KEY" "$HOST" 'cd /opt/gt && sudo docker compose up -d && sudo docker image prune -f'
