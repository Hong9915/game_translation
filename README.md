# 게임 번역 검수 도구

중국어(간체) 게임 텍스트를 한국어로 옮긴 엑셀 파일을 **팀이 함께 검수하고 고치는 웹 도구**.
파일을 올리면 엑셀처럼 생긴 화면에서 원문·기존 번역·고친 번역을 나란히 보고, 용어 통일·조사·띄어쓰기·문체 같은
반복 수정은 도구와 GPT에 맡긴 뒤, 원본 형식 그대로 한국어 열이 채워진 xlsx를 내려받는다.
각자의 Claude(claude.ai)를 **MCP 커넥터**로 붙여 "던전을 지하 감옥으로 바꿔줘" 같은 부탁을 맡길 수도 있다 — Claude는 제안만 하고, 사람이 검수 화면의 체크 창에서 골라 저장한다.

```mermaid
flowchart LR
  A[게임 고르기] --> B["파일 올리기<br/>xlsx·csv, 폴더째"] --> C["열 정하기<br/>원문·한국어 열"] --> D[엑셀식 검수] --> E["내보내기<br/>xlsx·zip"]
  D -.- T["단어장 · 표기 갈림 · 조사 검사<br/>명령으로 고치기 · 교정 규칙 · 변경 내역"]
  CL["Claude<br/>MCP 커넥터"] -.->|제안 → 체크 창| D
  CL -.->|시트 들여다보고 열 정하기| C
```

## 무엇을 할 수 있나

**엑셀식 검수 화면** (`/review/:id`, 게임 전체는 `/games/:gameId/review`)
- 시트·페이지 단위 그리드. 칸을 고쳐 바로 저장하고, 같은 파일을 연 사람끼리 실시간으로 보인다(SSE).
- 엑셀처럼 범위를 드래그로 골라 복사·붙여넣기하고, **Ctrl+Z / Ctrl+Y**로 되돌리고 다시 한다.
  붙여넣기·일괄 수정은 한 단위로 되돌린다. 표 위 입력줄은 높이를 끌어 바꾼다.
- 범위 우클릭: 원문 범위를 **DeepL·GPT로 다시 번역**하거나, 고친 번역 범위를 **GPT로 다듬어** 전·후를 보고 고른 행만 넣는다.
  게임마다 GPT 번역·다듬기에 덧붙일 지시문을 둔다.
- 검수 표시: 체크 A·B·C · 확인 필요 · 비고 · **확정**. 확정한 행은 일괄 수정 도구가 건드리지 않는다.
- 최종 리뷰 레인: 최종 리뷰에서 고친 칸을 따로 표시하고, 최종 수정분만 골라 내보낼 수 있다.
- 필터: 수정됨 · 확정 안 함 · 확인 필요 · 내 담당 · 용어 불일치 · 조사 확인 · 동일 원문 불일치 · Fix 비어 있음 등.
- 행마다 고친 사람·이력, 같은 원문 모아 보기(×N), 길이 초과 줄이기(GPT 후보), 셀 우클릭 번역.
- **변경 내역** 패널: 누가 무엇을 고쳤는지 시간순으로 보고, 일괄 수정은 한 번에 되돌린다.
- 게임 전체 검수: 게임의 모든 파일을 한 화면에서 보고, 전체 파일을 zip으로 내보낸다.
- **게임 메모**: 검수 화면 메모 버튼으로 새 창을 열어 팀이 함께 쓰는 게임별 메모(서식 있는 편집기, 자동 저장).

**용어 통일**
- 게임마다 **단어장**(확정 용어)을 두고, 검수 화면 옆 패널에서 보고 고치고 일괄 적용한다. 단어장은 DeepL 번역에도 용어집으로 들어간다.
- **단어장 요청양식**: 요청 파일을 올리면 기존 표기와 대조하고 GPT 초안을 붙여, 한 표에서 승인한다.
  GPT가 실제 행을 열어 보고 지금 쓰인 표기를 골라 주는 "표기 확인"도 있다.
- 같은 원문이 여러 행에 있으면 한 곳을 고칠 때 나머지도 맞춘다(동일 원문 동기화).
- **표기 갈림** 화면: 같은 중국어 구절이 여러 한국어로 옮겨진 곳을 찾아 고유명사·수치·문법 조각 등으로 나눠 보여 준다.
- 예전 표기가 남아 있는 행을 찾아 새 확정 용어로 바꿀 것을 제안한다.

**자동 검사·일괄 수정**
- **조사 검사**: 용어를 바꾼 뒤 어긋난 조사(을/를, 이/가, 으로/로…)를 한국어 형태소 분석기(Kiwi)로 찾아 고칠 것을 제안한다.
- **명령으로 고치기**: "구미호를 여우로 바꾸고 조사도 맞춰줘"처럼 적으면 GPT가 도구(찾아 바꾸기·용어 바꾸기·조사 맞추기·태그 띄어쓰기·
  같은 원문 통일·길이 줄이기)로 시트에 시험해 본 뒤 변경 전·후를 보여 주고, 고른 행만 고친다. 태그·자리표시자 안쪽은 바꾸지 않는다.

**교정 규칙** (`/games/:gameId/rules`, 검수 화면 위 막대의 버튼) — 게임 전체에서 기계적으로 고칠 곳을 찾아 미리 보기로 고른 행만 고친다.
- **문체: 서술식 → 개조식**. "~습니다·~하세요·~한다"가 남은 행을 글 종류(효과·아이템·퀘스트·시스템 알림·도움말·이야기·UI…)별로 찾아
  "~함·~됨·명사"로 바꾼다. 대사·공지·메일은 바꾸지 않는다. 글 종류는 GPT가 행마다 한 번 판단해 저장한다(그 버튼만 비용이 든다).
  사람이 손으로 고친 기록이 있으면 그 종류에서 바꾼 비율을 참고로 보여 준다.
- **줄 끝 마침표 빼기**: 줄바꿈(`<br>`·줄바꿈)이나 글 끝 바로 앞의 "." 하나만 지운다. 말줄임표·소수점은 그대로.
- **태그 띄어쓰기**: `<color>` 같은 태그 앞뒤에 붙은 낱말을 Kiwi 띄어쓰기로 보고 띄워야 할 경계에만 공백을 넣는다.
- 미리 보기는 같은 글을 ×N으로 묶고, 전체 선택·파일 거르기·줄마다 직접 고치기가 된다. 적용은 변경 내역에 남아 되돌릴 수 있다.
  기계 변환이 안 된 문체 행만 "GPT로 고치기"로 다시 쓸 수 있다.

**Claude 커넥터 (MCP)** — 팀원마다 자기 Claude 플랜으로 쓴다(회사 API 비용 없음).
- claude.ai 설정 → 커넥터에 `https://<도메인>/mcp`를 추가하고, 커넥터 계정(아이디·비밀번호)으로 로그인한다. 계정 하나 = 검수자 한 명.
- Claude가 고친 안은 **그 검수자의 웹 검수 화면에 체크 창**으로 뜬다. 사람이 행을 골라 저장해야 들어가고, 저장은 일반 저장과 같은 규칙(확정 행 건너뜀·같은 원문 동기화·변경 내역)을 따른다.
- 용어를 바꾼 제안이 저장되면 **단어장 승인 대기**에 후보가 쌓이고, 아무 검수자나 승인하면 단어장에 들어간다. 다음 사람의 Claude는 그 단어장을 읽고 따른다.
- 같은 50행 다듬기 기준 체크 창까지 약 41초 — 커넥터 없이 Chrome 패널이 화면을 조작해 저장하면 약 2분 37초(2026-11-19 운영 측정).

| 도구 | 하는 일 |
|---|---|
| `list_games` | 게임 목록. 검수 화면 주소의 id가 보이면 부르지 않는다 |
| `list_files` | 게임의 파일 목록(올린 파일, 정규화한 시트 수) |
| `inspect_file` | 파일의 시트마다 맨 위 행·열별 한자/한글/번체 비율을 보여 열 역할을 내용으로 고르게 한다(쪽으로 나눠 읽음) |
| `normalize_sheets` | 시트 여러 개(한 번에 최대 40)를 열 지정대로 정규화. 번체 원문은 간체로 바꾸고, 이미 검수한 시트는 거절한다 |
| `find_rows` | 파일·게임에서 행 찾기(원문·기존·Fix·비고, 레인별). 긴 원문·비고는 300자로 자르고 Fix는 자르지 않는다 |
| `read_game_memo` | 게임 메모(고객 요청·문체 규칙)를 글로 읽기 |
| `get_glossary` | 단어장 확정 표기 + 승인 대기 후보 |
| `propose_rows` | 행마다 고친 안을 체크 창으로 보내기 |
| `propose_replace` | "A를 B로" 찾아 바꾸기를 서버가 한 번에(조사 맞춤, 원문 용어 검사) 체크 창으로 보내기 |
| `proposal_status` | 제안 결과(저장·넣지 않음·만료, 뺀 행, 되돌리기 id) |
| `save_rows` | 사용자 허락을 받고 체크 창 없이 바로 저장 |
| `set_note` | 비고·확인 필요 표시 |
| `undo` | 커넥터로 저장한 것 되돌리기 |

**시트가 많은 파일 정규화** — 올리기는 게임 화면에서 한다. 그다음 Claude에게 "이 게임 정규화해 줘"라고 하면 `list_files` → `inspect_file`로 시트를 훑고, 열 역할을 시트마다 묶은 표로 보여 주어 확인받는다.
확인하면 `normalize_sheets`가 정규화하고(호출당 시간 제한이 있어 못 한 시트는 이어서 부른다), 끝나면 게임 전체 리뷰에서 시트 전부를 한 표로 검수한다. 시트 순서는 원본 워크북 순서 그대로다.

```mermaid
sequenceDiagram
  actor R as 검수자
  participant C as Claude
  participant M as MCP 서버
  participant B as 백엔드
  R->>B: 웹에서 파일·폴더 올리기
  R->>C: "이 게임 정규화해 줘"
  C->>M: list_files · inspect_file
  M->>B: /api/connector/…/files · inspect
  B-->>C: 시트마다 맨 위 행 · 열별 한자/한글/번체 비율
  C->>R: 시트별 헤더 행 · 원문/한국어 열 표
  R->>C: 확인
  loop 못 한 시트(NOT_RUN)가 남는 동안
    C->>M: normalize_sheets (최대 40시트)
    M->>B: /api/connector/…/normalize
    B-->>C: 시트별 OK · REFUSED · FAILED · NOT_RUN
  end
  R->>B: 게임 전체 리뷰에서 한 표로 검수 → 원본 형태로 내보내기
```

왜 만들었고 무엇을 지키나:

| 문제 | 해결 | 결과 |
|---|---|---|
| 시트가 104개이고 헤더 위치·열 배치가 시트마다 달라, 손으로 하나씩 열을 고르거나 한 시트로 합쳤다 되돌리는 스크립트를 돌려야 했다 | Claude가 시트별 통계를 보고 헤더 행과 원문·한국어 열을 정해 여러 시트를 한 번에 정규화한다 | 96개 시트를 호출 5번, 약 61초. 합치기·되돌리기 없이 게임 전체를 한 표로 검수하고 원본 워크북에 그대로 써서 내보낸다 |
| 시트마다 워크북을 다시 열어 101개 시트에 160초, 큰 시트 하나에 37초가 걸렸다 | 호출당 워크북을 한 번만 연다. 호출 하나는 40시트·30초까지이고, 넘친 시트는 `NOT_RUN`으로 돌려보내 이어서 부르게 한다. 워크북은 한 번에 하나만 연다 | 가장 긴 호출 38초, 힙 최고 955MB(상한 2GB) |
| 정규화는 시트의 행을 지우고 다시 만든다. AI가 잘못 부르면 검수한 번역이 사라진다 | 번역·검수 흔적(고친 번역·체크·비고·담당)이 있는 시트는 커넥터가 거절한다. 다시 정규화(`replace: true`)는 흔적이 없을 때만 되고, 지우기 직전 같은 트랜잭션에서 행을 잠근 뒤 다시 확인한다. 시트마다 따로 커밋한다 | 실패한 시트는 이전 행이 그대로 남는다. 웹 정규화 화면의 동작은 바뀌지 않았다 |
| 번체 원문 시트가 섞여 있다 | OpenCC `tw2s`를 Java로 옮긴 변환기로 원문을 간체로 바꿔 넣는다. 번체 글자가 없는 칸은 손대지 않는다 | 실제 OpenCC와 번체가 든 30,369칸 중 2칸만 다르다(사전 판 차이). 내보내기는 원문 칸을 쓰지 않아 원본 워크북에는 번체가 그대로 남는다 |

시트 104개·약 3.5만 행 파일 기준(104개 중 96개를 정규화한 수치 — 나머지 8개는 원문 열이 없는 작은 시트라 보내지 않았다. 2026-11-21, 프로드와 같은 힙 2GB): 정규화 약 61초(호출 5번, 가장 긴 호출 38초), 리뷰 첫 쪽 0.07초, 내보내기 3.8초. 검수 전 내보내기는 원본과 142,099칸이 모두 같다.

**번역**
- 기본 번역 엔진은 **DeepL**이다. 게임 단어장을 DeepL 용어집으로, 게임별 지시문을 함께 보낸다.
- 파일 없이 문장 하나를 그 게임의 용어에 맞춰 번역하는 빠른 번역.
- 번역 메모리: 사람이 고친 문장과 내보낸 최종 문장이 쌓여 다음 번역의 참고가 된다.
- GPT 호출마다 토큰 사용량과 비용 추정치를 남긴다.

## 구성

```mermaid
flowchart LR
  U[브라우저<br/>React 19] -->|요청| N["nginx<br/>HTTPS · 팀 비밀번호"]
  CL["Claude<br/>claude.ai · Chrome 패널"] -->|"/mcp · OAuth 로그인<br/>(팀 비밀번호 없음)"| N
  N -->|/api| B["Spring Boot 백엔드<br/>검수 API · 작업 실행기 · xlsx"]
  N -->|/mcp · /authorize · /token| M["MCP 서버<br/>TypeScript · 도구 13개<br/>상태 없는 OAuth(JWT)"]
  M -->|/api/connector · 내부망| B
  B -->|모든 데이터| P[("PostgreSQL 16<br/>Flyway V1–V39")]
  B ==>|기본 번역| D["DeepL Pro<br/>단어장 용어집 · 게임별 지시문"]
  B -->|명령으로 고치기 · 다듬기 · 글 종류 판단 · 단어장 초안| O[OpenAI<br/>gpt-5 · gpt-5-mini]
  B -->|조사 검사 · 태그 띄어쓰기 · 내부망| J["josa 사이드카<br/>Python · Kiwi"]
  B -->|원본 보관| S[("파일 저장소<br/>STORAGE_TYPE=local")]
  B -.->|SSE: 다른 사람이 고친 칸 · 작업 진행률<br/>Claude 제안 · 단어장 후보| U
```

운영에서는 nginx만 밖으로 열린다. 백엔드·DB 포트는 열지 않고, josa는 백엔드만 컨테이너 네트워크 안에서 부른다.
MCP 경로(`/mcp`, OAuth)만 팀 비밀번호 없이 열리고 커넥터 계정 로그인으로 막는다(로그인은 분당 10회 제한). MCP 서버는 DB에 직접 붙지 않고 백엔드 API만 부른다.

| 폴더 | 내용 |
|---|---|
| `backend/` | Spring Boot 3.3 · Java 17 · Apache POI · Flyway. xlsx 읽기·쓰기, 검수 API, GPT tool calling, 작업 실행기, 실시간 알림(SSE) |
| `frontend/` | React 19 · Vite · TypeScript · TanStack Query · Tailwind |
| `josa/` | 조사 검사 보조 서버(Python, Kiwi). 백엔드만 컨테이너 네트워크로 부른다 |
| `mcp/` | Claude 커넥터 MCP 서버(TypeScript · MCP SDK · express · jose · zod). 도구와 OAuth 로그인, 계정 SQL은 [`mcp/README.md`](mcp/README.md) |
| `deploy/` | 운영 배포·HTTPS·백업 스크립트, 운영용 compose와 nginx 설정 |

### 파일 한 개의 흐름

```mermaid
flowchart TD
  U["업로드 → 원본 파일<br/><code>project · storage</code>"] --> N["열 정하기 → segments 행<br/><code>normalize · xlsx</code>"]
  N --> T["번역 → 빈 한국어 채움<br/><code>job · translate · deepl</code>"]
  T --> R["검수 → 고친 한국어 · 표시<br/><code>workspace · review · command · josa · activity</code>"]
  C["교정 규칙 → 문체 · 마침표 · 태그 띄어쓰기<br/><code>rules · josa</code>"] -->|미리 보기 → 적용| R
  CL["Claude 제안 → 체크 창에서 저장<br/><code>connector</code>"] -->|저장| R
  CL -->|시트 들여다보기 → 여러 시트 정규화| N
  R --> E["내보내기 → .xlsx · .zip<br/><code>export · xlsx</code>"]
  G[("단어장<br/><code>glossary</code>")] -->|DeepL 용어집| T
  G -->|일괄 적용 · 불일치 필터| R
  R -->|용어 바꾼 저장 → 승인 대기| G
  R -->|변경 내역 · 수정 이력| H[("기록")]
  E -->|최종 문장| M[("번역 메모리<br/><code>memory</code>")]
```

모든 단계가 같은 `segments` 행을 읽고 쓴다. 검수 단계의 일괄 수정(명령으로 고치기·조사 맞추기·단어장 적용·교정 규칙)은 확정된 행을 건너뛰고,
변경 내역에 묶음으로 남아 한 번에 되돌릴 수 있다.

### 백엔드 패키지 (`backend/src/main/java/com/gametrans`)

| 단계·역할 | 패키지 |
|---|---|
| 게임·파일 | `game`(게임, DeepL·GPT 지시문, 게임 메모) · `project`(올린 파일) · `storage`(원본 저장: local/Supabase) |
| 열 정하기 | `normalize`(열 쌍 → `segments` 행) · `xlsx`(읽기·쓰기·열 추측·CSV) |
| 번역 | `job`(백그라운드 작업·진행 SSE) · `translate`(파일·행·빠른 번역, 여러 행 다시 번역·GPT 다듬기, 길이 줄이기, 수정 이력) · `deepl` · `llm`(OpenAI·tool loop·사용량) · `memory`(번역 메모리) |
| 검수 | `workspace`(그리드 조회, 게임 전체 검수) · `review`(표시·담당·동일 원문 동기화·실시간·접속자) · `reviewer` · `command`(명령으로 고치기·전체 길이 줄이기) · `josa`(조사 검사·태그 띄어쓰기) · `rules`(교정 규칙: 글 종류 판단·문체 변환·마침표·미리 보기·적용) · `activity`(변경 내역·되돌리기·Ctrl+Z) |
| Claude 커넥터 | `connector`(커넥터 계정·로그인, 파일 목록·시트 들여다보기·여러 시트 정규화, 제안 만들기·체크 창 저장·넣지 않음, 찾아 바꾸기 제안, 커넥터 되돌리기) |
| 용어 | `glossary`(단어장·분류·표기 갈림·추천·요청양식·승인 대기 후보) · `text`(용어·조사·길이·자리표시자 검사, 번체→간체 변환) |
| 내보내기 | `export`(xlsx·zip, 최종 수정분만, 내보낸 버전) |
| 공통 | `config` · `common` · `health` |

### 화면 → API → 패키지

| 화면 (프론트 경로) | 주로 부르는 API | 백엔드 패키지 |
|---|---|---|
| 게임 목록 `/` | `/api/games` | game |
| 게임 화면 `/games/:gameId` | `/api/games`, `/api/projects`, `/api/games/{id}/jobs`, `/api/games/{id}/quick-translate` | game · project · job · translate · glossary |
| 열 정하기 `/normalize/:id` | `/api/projects/{id}` | normalize · xlsx |
| 검수 그리드 `/review/:id` | `/api/review/{projectId}/…` (rows · command-fix · josa · activity), `/api/segments/…` (translate-batch · polish · save-batch), `/api/projects/{id}/presence` | workspace · review · command · josa · translate · activity · glossary |
| 게임 전체 검수 `/games/:gameId/review` | `/api/games/{gameId}/review/…` | workspace |
| 교정 규칙 `/games/:gameId/rules` | `/api/games/{gameId}/rules`(relearn · judge-kinds), `/api/rules/{id}/…`(preview · apply · rewrite), `/api/games/{gameId}/review/tag-spacing` | rules · josa |
| 게임 메모 `/games/:gameId/memo` (새 창) | `/api/games/{id}/memo` | game |
| 단어장 추천 `/games/:gameId/glossary/recommend` | `/api/games/{gameId}/glossary/recommend` | glossary |
| 단어장 요청 `/games/:gameId/glossary/requests/:requestId` | `/api/games/{gameId}/glossary/requests`, `/api/glossary/requests/{id}` | glossary |
| Claude 커넥터 (claude.ai → `/mcp`) | MCP 서버 → `/api/connector/…`(login · games/{id}/files · projects/{id}/inspect · projects/{id}/normalize · proposals · proposals/replace · notes · undo), 화면과 같은 `rows`·`save-batch` | connector · normalize · xlsx · workspace · translate · activity |
| 체크 창 · 단어장 승인 대기 (검수 화면) | `/api/connector/proposals/{id}`(apply · dismiss), `/api/games/{gameId}/glossary/candidates`, `/api/glossary/candidates/{id}`(approve · dismiss) | connector · glossary |
| 내보내기 (게임·검수 화면 버튼) | `/api/projects/{id}/export` | export · xlsx |

## 실행

**한 번에 띄우기 (Docker)**
```bash
cp .env.example .env          # OPENAI_API_KEY, DEEPL_AUTH_KEY 채우기
docker compose up -d --build  # postgres · backend · josa · frontend
```
- 화면: http://localhost:5173 (nginx가 `/api`를 백엔드로 넘긴다)
- 백엔드: http://localhost:8080 · DB: localhost:5432
- `.env.local`이 있으면 그 값이 `.env`를 덮어쓴다.

**개발 (핫리로드)**
```bash
docker compose up -d postgres josa
cd backend && set -a && source ../.env && set +a && ./gradlew bootRun   # :8080
cd frontend && npm install && npm run dev                               # :5173
```

**테스트**
```bash
cd backend && ./gradlew test     # Docker 필요 — Testcontainers Postgres 16
cd frontend && npm test
cd josa && python -m unittest   # kiwipiepy 설치 필요
cd mcp && npm install && npm test
```

> `docker compose up -d --build`는 진행 중인 GPT 작업을 끊는다. 작업이 없을 때 다시 빌드한다.
> 프론트만 고쳤으면 `docker compose up -d --build --no-deps frontend`.

## 배포

운영은 리눅스 서버 한 대에서 `deploy/docker-compose.prod.yml`로 돈다. nginx가 HTTPS(Let's Encrypt)와
팀 공용 비밀번호(Basic Auth)를 맡고, 백엔드·DB 포트는 밖으로 열지 않는다.

| 스크립트 | 하는 일 |
|---|---|
| `deploy/deploy.sh` | 이미지 4개(backend·frontend·josa·mcp)를 빌드해 파일로 저장하고 rsync(끊기면 이어서, 5회 재시도)로 보낸 뒤 다시 띄운다. **작업 트리를 그대로 빌드**하므로 깨끗한 상태에서 돌린다 |
| `deploy/deploy-mcp.sh` | MCP 컨테이너만 다시 올린다(백엔드는 그대로라 돌던 작업이 안 끊긴다) |
| `deploy/setup-https.sh` | 처음 한 번 HTTPS 인증서를 받는다 |
| `deploy/install-backup.sh` | 서버에 매일 밤 DB·파일 백업(7일 보관) cron을 설치한다 |
| `deploy/pull-prod-db.sh` | 운영 DB와 파일을 로컬 개발 환경으로 복사한다 |

서버 주소는 `deploy/.env.deploy`에 둔다(`deploy/.env.deploy.example`을 복사, git에 올리지 않음). SSH 키 경로는 `KEY`로 바꿀 수 있다.
서버 `.env`에 `MCP_TOKEN_SECRET`(32자 이상)·`PUBLIC_URL`이 없으면 두 배포 스크립트 모두 아무것도 건드리지 않고 멈춘다. 커넥터 계정은 화면이 아니라 SQL로 만든다([`mcp/README.md`](mcp/README.md)).

## 파일 처리 세부

- **열 쌍**: 시트마다 원문 열 → 한국어 열(선택으로 영어 열) 목록을 정한다. 헤더 접미사(`_CN/_EN/_KR`, `中文/英文/韩语`)로 자동 추측한다.
  쌍이 여러 개면(이름·설명 등) 한 번에 처리해 파일 하나로 받는다.
- **헤더 행**: 시트마다 헤더가 몇째 행인지 정한다. 헤더가 없는 시트는 "없음"으로 두면 첫 행부터 데이터로 읽는다.
- **번체 원문**: "원문을 간체로 바꿔 넣기"를 켜면 번체 원문을 간체로 바꿔 정규화한다. 내보낼 때 원문 칸은 원본 그대로 둔다.
- **이미 한국어가 있는 파일**: 기존 한국어는 "기존 번역"으로 들어와 그대로 검수한다. 한국어·영어 칸이 `N/A`·`-` 같은 표시여도
  행을 건너뛰지 않는다(2026-10-29부터).
- **CSV·TSV**: 내부에서 시트 하나짜리 xlsx로 처리하고 다시 CSV(UTF-8 BOM)로 내보낸다. 인코딩은 UTF-8 → MS949 → GB18030 순으로 판별한다.

## 환경변수

`.env.example` 참고. 주요 값:

| 변수 | 기본 | 설명 |
|---|---|---|
| `OPENAI_API_KEY` | — | 필수 |
| `OPENAI_MODEL` / `OPENAI_MODEL_LIGHT` | gpt-5 / gpt-5-mini | 번역·명령 / 추출·후보 추천 |
| `OPENAI_REASONING_EFFORT` | low | gpt-5 계열 추론 강도 (비추론 모델은 빈 값) |
| `LLM_CONCURRENCY` | 8 | GPT 동시 호출 수. 429가 잦으면 줄인다 |
| `DEEPL_AUTH_KEY` | — | 기본 번역 엔진. 없으면 파일 번역이 시작되지 않는다 |
| `STORAGE_TYPE` | local | 원본 파일 저장 위치 (`local` 또는 `supabase`) |
| `OPENAI_PRICES` | — | 비용 추정용 백만 토큰당 USD (`모델=입력/출력`) |
| `UPLOAD_MAX_MB` | 50 | 업로드 크기 제한 |
| `MCP_TOKEN_SECRET` | — | MCP 커넥터 출입증 서명 비밀(32자 이상). 바꾸면 모든 커넥터 로그인이 풀린다 |
| `PUBLIC_URL` | — | MCP 서버 바깥 주소(OAuth 발급자). 예: `https://<도메인>` |
