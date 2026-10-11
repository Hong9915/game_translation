#!/bin/bash
# 운영 서버에 매일 한국 시간 00:00 백업(deploy/backup-db.sh)을 설치하고, 한 번 돌려서 되는지 본다.
# 다시 실행해도 된다(스크립트와 cron 줄을 덮어쓴다).
set -euo pipefail
cd "$(dirname "$0")/.."
# 서버 주소는 git에 올리지 않는 deploy/.env.deploy에 둔다(deploy/.env.deploy.example 참고).
. deploy/.env.deploy
KEY=${KEY:-$HOME/.ssh/lightsail-gt.pem}

scp -i "$KEY" deploy/backup-db.sh "$HOST":/tmp/backup-db.sh
ssh -i "$KEY" "$HOST" 'set -e
  sudo install -m 755 /tmp/backup-db.sh /opt/gt/backup-db.sh
  # cron은 서버 시간대로 돈다. 서버가 UTC면 15:00이 한국 00:00.
  if [ "$(date +%z)" = "+0900" ]; then hour=0; else hour=15; fi
  echo "0 $hour * * * root /opt/gt/backup-db.sh >> /opt/gt/backups/backup.log 2>&1" | sudo tee /etc/cron.d/gt-backup >/dev/null
  sudo chmod 644 /etc/cron.d/gt-backup
  echo "서버 시간대: $(date +%Z) → cron: $(cat /etc/cron.d/gt-backup)"
  sudo mkdir -p /opt/gt/backups
  sudo /opt/gt/backup-db.sh
  ls -lh /opt/gt/backups'
