# 게임 번역 검수 커넥터 (MCP 서버)

Claude(Chrome 옆 패널 Cowork 등)가 검수 화면의 행을 찾고·고치고·메모하고·되돌릴 수 있게 하는 MCP 서버입니다. 백엔드 API를 부르는 얇은 층이고, 자체 OAuth 로그인(아이디·비밀번호)을 가집니다.

부르는 백엔드 API:
- 커넥터 전용 `/api/connector/*`: `login`, `accounts/{id}`(계정 확인), `games/{id}/sheets`, `games/{id}/files`, `projects/{id}/inspect`, `projects/{id}/normalize`, `notes`, `undo`, `proposals`
- 화면과 같이 쓰는 API: `GET /api/games`, `GET /api/projects/{id}`, `GET /api/games/{id}/review/rows`, `POST /api/segments/save-batch`(`source: "CONNECTOR"`)

도구 열세 개: `list_games`, `find_rows`, `save_rows`, `set_note`, `undo`, `propose_rows`, `propose_replace`, `proposal_status`, `read_game_memo`, `get_glossary`, `list_files`, `inspect_file`, `normalize_sheets`.
- 확정된 행은 `save_rows`가 건너뛰고(`skipped`, 이유 "확정") 글을 바꾸지 않습니다. 이미 같은 글인 행은 `unchanged`로 셉니다.
- 최종 리뷰 레인 행을 고치면 최종 수정 표시가 켜집니다.
- `save_rows`의 저장은 변경 내역에 `CONNECTOR`로 남고, `undo`에 그 id를 주면 되돌립니다(되돌린 결과의 id로 다시 `undo`하면 다시 실행). `undo`는 그 계정이 커넥터로 고친 것만, 한 번에 50개까지 받습니다.
- `propose_rows`는 고칠 안을 사용자의 웹 검수 화면 확인 창으로 보냅니다. 사용자가 창에서 행을 체크해 저장하며, 저장은 `save_rows`와 같은 규칙으로 변경 내역에 `CONNECTOR`로 남아 `undo`로 되돌릴 수 있습니다. 제안은 24시간 뒤 만료됩니다.
- `propose_replace`는 행마다 문장을 쓰지 않고 "지금 한국어의 A를 B로" 한 번에 제안합니다. 서버가 fix에 A가 든 행을 찾아 뒤 조사까지 맞춰 고치고, 잠긴 행은 빼며, 직접 저장하지 않고 항상 웹 검수 화면에 확인 창(PENDING 제안)으로 띄웁니다. 용어를 바꾸는 거면 `src`(원문 용어)를 넣어 원문 검사를 하고, 저장되면 단어장 후보가 됩니다. 돌려주는 값은 `matched`·`proposed`·`locked`·`srcMissing`·`samples`(앞 3행)·`termCandidate`이고 결과는 `proposal_status`로 봅니다.
- `find_rows`는 src·existingKo·note가 300자를 넘으면 앞 300자 + "…"로 자르고 그 행에 `truncated: true`를 붙입니다. `fix`는 자르지 않습니다. `propose_rows`에서 `base`를 생략하면 서버가 지금 fix로 채웁니다.
- `proposal_status`는 `propose_rows`·`propose_replace`로 보낸 제안의 결과(대기·저장함·넣지 않음·만료, 저장·건너뜀·체크 뺀 행, `activityIds`)를 돌려줍니다. 자기 계정의 제안만 봅니다.
- `set_note`(비고·확인 필요)는 변경 내역에 남지 않고 `undo`로 되돌릴 수 없습니다. 비고를 되돌리려면 돌려받은 `before` 값으로 `mode=replace`를 씁니다.
- `list_files`는 게임의 파일 목록과 파일마다 정규화한 시트 수를 돌려줍니다. 파일 올리기는 웹 게임 화면에서 합니다.
- `inspect_file`은 시트마다 맨 위 6행과 열별 글자 비율(han·hangul·latin·traditional)을 돌려줍니다(시트 많으면 `offset`·`limit`, 한 번에 50개까지). `canReplace`가 null이 아니면 이미 정규화한 시트입니다.
- `normalize_sheets`는 시트별 계획(헤더 행·열 짝·간체 변환)으로 한 번에 40개 시트까지 정규화합니다. 결과 `status`는 OK·REFUSED·FAILED·NOT_RUN(30초 안에 못 해서 시작 안 함)이고 `warnings`를 그대로 돌려줍니다. `replace: true`는 번역·검수가 없는 시트만 다시 만듭니다.

## 환경 변수

| 이름 | 뜻 | 기본값 |
|---|---|---|
| `PUBLIC_URL` | 이 서버의 바깥 주소(OAuth 발급자·콜백 기준). 필수 | 없음 |
| `MCP_TOKEN_SECRET` | 출입증 서명 비밀(32자 이상, `openssl rand -hex 32`). 필수. 바꾸면 모든 로그인이 풀린다 | 없음 |
| `BACKEND_URL` | 백엔드 주소 | `http://backend:8080` |
| `PORT` | 듣는 포트 | `3000` |

## 로컬 실행

백엔드가 먼저 떠 있어야 합니다(DB 복사본에 붙인 백엔드를 권장 — 시험 편집이 실제 DB에 남지 않게).

```bash
cd mcp
npm install
PUBLIC_URL=http://localhost:3100 BACKEND_URL=http://localhost:<백엔드 포트> \
  MCP_TOKEN_SECRET=$(openssl rand -hex 32) PORT=3100 npm run dev
```

시험: `npm test`. 빌드 실행은 `npm run build` 뒤 `node dist/index.js`.
같은 `MCP_TOKEN_SECRET`으로 다시 켜면 이미 받은 출입증·갱신 토큰이 그대로 통합니다(서버에 저장하는 상태가 없음).

## Claude Code로 로컬 연결

```bash
claude mcp add --transport http gt-local http://localhost:3100/mcp
```

Claude Code 안에서 `/mcp` → `gt-local` → 로그인. 브라우저에 뜨는 로그인 화면에 아래 SQL로 만든 아이디·비밀번호를 넣습니다.

## 계정 SQL

화면에서 만들지 않고 DB에서 직접 넣습니다. 한 계정 = 기존 검수자 한 명(변경 내역에 그 검수자 이름으로 남음). `pgcrypto`는 V36 마이그레이션이 켭니다. V36 파일 머리 주석의 예시 대신 아래 SQL을 쓰세요.

넣기는 검수자 이름이 틀리면 아무 행도 넣지 않습니다. 결과가 `INSERT 0 1`이 아니라 `INSERT 0 0`이면 이름이 틀린 것입니다(`select id, name from reviewer;`로 확인).

```sql
-- 넣기
insert into connector_account (login_id, password_hash, reviewer_id)
select '아이디', crypt('비밀번호', gen_salt('bf', 10)), id from reviewer where name = '검수자 이름';

-- 막기 / 풀기
update connector_account set disabled = true  where login_id = '아이디';
update connector_account set disabled = false where login_id = '아이디';

-- 비밀번호 바꾸기
update connector_account set password_hash = crypt('새 비밀번호', gen_salt('bf', 10)) where login_id = '아이디';
```

## 배포

- 서버 `.env`에 `MCP_TOKEN_SECRET`(`openssl rand -hex 32`)과 `PUBLIC_URL`(공개 https 주소)을 넣어 둡니다. 없거나 비밀이 32자보다 짧으면 두 배포 스크립트가 빌드 전에 멈춥니다(아무것도 다시 띄우지 않음).
- 첫 배포는 `deploy/deploy.sh`(백엔드 V36 마이그레이션·mcp 컨테이너·nginx 경로 포함). 돌고 있는 번역·GPT 작업이 없을 때 하세요.
- 그 뒤 mcp만 고칠 때는 `deploy/deploy-mcp.sh` — 백엔드는 다시 뜨지 않아 작업이 끊기지 않습니다.
- 서버 주소는 git에 없는 `deploy/.env.deploy`에 둡니다.
