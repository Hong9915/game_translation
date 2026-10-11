#!/bin/bash
# 처음 한 번: 운영 서버에 $DOMAIN HTTPS를 붙인다(Let's Encrypt). 다시 돌려도 된다.
# DNS A 레코드(DOMAIN → 서버 IP)가 먼저 있어야 한다. 이후 갱신은 compose의 certbot 서비스가 한다.
# 백엔드 CORS 허용 주소에 새 도메인을 더하느라 백엔드가 재시작되므로, GPT 작업이 돌고 있으면 멈춘다.
set -euo pipefail
cd "$(dirname "$0")/.."
# 서버 주소는 git에 올리지 않는 deploy/.env.deploy에 둔다(deploy/.env.deploy.example 참고).
. deploy/.env.deploy
KEY=${KEY:-$HOME/.ssh/lightsail-gt.pem}
INSTANCE=${INSTANCE:-game-translation-4g}
IP=${HOST#*@}

if [ "$(dig +short "$DOMAIN" @1.1.1.1 | tail -1)" != "$IP" ]; then
  echo "DNS가 아직 $DOMAIN → $IP 를 가리키지 않습니다. 몇 분 뒤 다시 실행하세요."; exit 1
fi

echo "== Lightsail 방화벽에 443 열기"
aws lightsail open-instance-public-ports --region ap-northeast-2 --instance-name "$INSTANCE" \
  --port-info fromPort=443,toPort=443,protocol=TCP >/dev/null

echo "== 실행 중인 GPT 작업 확인"
running=$(ssh -i "$KEY" "$HOST" "cd /opt/gt && sudo docker compose exec -T postgres psql -U postgres -d gametrans -tAc \"select count(*) from translation_job where status='RUNNING'\"")
if [ "$running" != "0" ]; then
  echo "GPT 작업 ${running}개가 실행 중입니다. 끝난 뒤 다시 실행하세요."; exit 1
fi

echo "== 설정 올리기"
scp -i "$KEY" deploy/docker-compose.prod.yml "$HOST":/opt/gt/docker-compose.yml
nginx_conf=$(mktemp); sed "s/__DOMAIN__/$DOMAIN/g" deploy/nginx.conf > "$nginx_conf"
scp -i "$KEY" "$nginx_conf" "$HOST":/opt/gt/nginx.conf; rm -f "$nginx_conf"

ssh -i "$KEY" "$HOST" "DOMAIN=$DOMAIN IP=$IP bash -s" <<'EOF'
set -euo pipefail
cd /opt/gt
sudo mkdir -p letsencrypt certbot-www

# 브라우저가 https://도메인 을 Origin으로 보내므로 백엔드 CORS 허용 주소에 더한다.
if ! sudo grep -q "https://$DOMAIN" .env 2>/dev/null; then
  if sudo grep -q '^CORS_ORIGINS=' .env 2>/dev/null; then
    sudo sed -i "s#^CORS_ORIGINS=.*#&,https://$DOMAIN#" .env
  else
    echo "CORS_ORIGINS=http://$IP,https://$DOMAIN" | sudo tee -a .env >/dev/null
  fi
fi
echo "CORS: $(sudo grep '^CORS_ORIGINS=' .env)"

dummy_cert() {
  sudo rm -rf "letsencrypt/live/$DOMAIN"
  sudo mkdir -p "letsencrypt/live/$DOMAIN"
  sudo openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj "/CN=$DOMAIN" \
    -keyout "letsencrypt/live/$DOMAIN/privkey.pem" -out "letsencrypt/live/$DOMAIN/fullchain.pem" 2>/dev/null
}
# up -d는 컨테이너만 띄우고 돌아온다. nginx가 임시 인증서를 실제로 읽고 80을 받기 시작할 때까지 기다린다.
wait_nginx() {
  for _ in $(seq 30); do
    if curl -s -o /dev/null --max-time 2 http://localhost/; then return 0; fi
    sleep 1
  done
  echo "nginx가 뜨지 않습니다:"; sudo docker compose logs --tail 20 frontend; exit 1
}

if [ ! -e "letsencrypt/renewal/$DOMAIN.conf" ]; then
  echo "== 첫 발급: nginx가 뜰 수 있게 임시 인증서를 두고 시작"
  dummy_cert
  sudo docker compose up -d
  sudo docker compose restart frontend
  wait_nginx
  sudo rm -rf "letsencrypt/live/$DOMAIN"
  echo "== Let's Encrypt 발급"
  if ! sudo docker compose run --rm --entrypoint certbot certbot certonly --webroot -w /var/www/certbot \
      -d "$DOMAIN" --register-unsafely-without-email --agree-tos --non-interactive; then
    # 실패해도 nginx가 다시 뜰 수 있게 임시 인증서를 되돌려 둔다(사이트는 브라우저 경고와 함께 열린다).
    dummy_cert
    echo "발급 실패. 임시 인증서로 두었습니다. 원인을 고친 뒤 다시 실행하세요."; exit 1
  fi
  sudo docker compose exec -T frontend nginx -s reload
else
  sudo docker compose up -d
fi
sudo docker image prune -f >/dev/null
sudo docker compose ps
EOF

echo "== 확인 (401이면 정상: 비밀번호 창)"
sleep 2
curl -sS -o /dev/null -w "https://$DOMAIN → %{http_code}\n" "https://$DOMAIN/"
curl -sS -o /dev/null -w "http://$IP → %{http_code} %{redirect_url}\n" "http://$IP/"
