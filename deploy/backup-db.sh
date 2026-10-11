#!/bin/bash
# 운영 서버에서 매일 밤 cron(root)으로 돈다. DB와 올린 파일(data/)을 /opt/gt/backups/ 에 하루 하나씩 남기고
# 7일이 지난 것은 지운다. 설치는 내 컴퓨터에서 deploy/install-backup.sh.
#
# 되돌리기(서버에서, 백엔드를 멈추고):
#   cd /opt/gt && sudo docker compose stop backend
#   sudo docker compose exec -T postgres psql -U postgres -d postgres -c "drop database gametrans with (force)" -c "create database gametrans"
#   sudo docker compose exec -T postgres pg_restore -U postgres -d gametrans --no-owner < backups/db-YYYYmmdd-HHMM.dump
#   sudo docker compose start backend
# 내 컴퓨터로 가져와 보기만 하려면: scp 로 받은 뒤 deploy/pull-prod-db.sh --from <파일>
set -euo pipefail
cd /opt/gt
KEEP_DAYS=${KEEP_DAYS:-7}
mkdir -p backups
stamp=$(TZ=Asia/Seoul date +%Y%m%d-%H%M)

# -Fc: 압축된 사용자 지정 형식. 운영 DB를 잠그지 않고 그 순간의 상태를 뜬다.
docker compose exec -T postgres pg_dump -U postgres -d gametrans -Fc > "backups/db-$stamp.dump.part"
[ -s "backups/db-$stamp.dump.part" ] || { echo "$stamp 덤프가 비어 있음" >&2; rm -f "backups/db-$stamp.dump.part"; exit 1; }
mv "backups/db-$stamp.dump.part" "backups/db-$stamp.dump"

# 올린 원본 파일. 내보내기·다시 정규화에 쓰인다.
tar -czf "backups/files-$stamp.tar.gz.part" data
mv "backups/files-$stamp.tar.gz.part" "backups/files-$stamp.tar.gz"

# 오늘 것을 포함해 KEEP_DAYS개만 남긴다
find backups -maxdepth 1 \( -name 'db-*.dump' -o -name 'files-*.tar.gz' \) -mtime +$((KEEP_DAYS - 1)) -delete
echo "$stamp 백업 끝: db $(du -h "backups/db-$stamp.dump" | cut -f1), files $(du -h "backups/files-$stamp.tar.gz" | cut -f1)"
