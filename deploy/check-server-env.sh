#!/bin/bash
# deploy.sh·deploy-mcp.sh가 source 해서 쓴다. KEY·HOST가 정해져 있어야 한다.
# 서버 /opt/gt/.env에 mcp가 꼭 읽어야 하는 값(MCP_TOKEN_SECRET 32자 이상, PUBLIC_URL)이 있는지 먼저 본다.
# 값이 빠진 채 올리면 mcp가 뜨자마자 죽기를 되풀이한다. 그래서 빠졌으면 아무것도 올리거나 다시 띄우지 않고 멈춘다.
# 값 자체는 화면에 내보내지 않는다(길이·있음만 본다).

check_server_env() {
  local rc=0
  ssh -i "$KEY" "$HOST" 'sudo bash -s' <<'REMOTE' || rc=$?
f=/opt/gt/.env
# KEY=값 마지막 줄의 값. 앞뒤 공백·CR·감싼 따옴표는 뗀다.
get() {
  [ -f "$f" ] || return 0
  grep -E "^[[:space:]]*$1[[:space:]]*=" "$f" | tail -n 1 | cut -d= -f2- | tr -d '\r' \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}
bad=0
[ -f "$f" ] || { echo "  - /opt/gt/.env 파일이 없습니다." >&2; bad=1; }
secret=$(get MCP_TOKEN_SECRET)
url=$(get PUBLIC_URL)
if [ "${#secret}" -lt 32 ]; then echo "  - MCP_TOKEN_SECRET 이 없거나 32자보다 짧습니다." >&2; bad=1; fi
if [ -z "$url" ]; then echo "  - PUBLIC_URL 이 없습니다." >&2; bad=1; fi
exit $bad
REMOTE
  if [ "$rc" = 255 ]; then
    echo "서버에 접속하지 못해 서버 .env를 확인하지 못했습니다. 배포를 멈춥니다(아무것도 다시 띄우지 않았습니다)." >&2
    exit 1
  fi
  if [ "$rc" != 0 ]; then
    echo "서버 /opt/gt/.env 에 mcp 설정이 빠져 배포를 멈춥니다(아무것도 다시 띄우지 않았습니다)." >&2
    echo "MCP_TOKEN_SECRET(openssl rand -hex 32로 만든 32자 이상)과 PUBLIC_URL(공개 https 주소)을 넣은 뒤 다시 실행하세요." >&2
    exit 1
  fi
}
