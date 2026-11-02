---
name: local-preview
description: 기능 구현·버그 수정을 마치면(테스트 통과 뒤, 커밋·배포 전) 사용자가 직접 화면에서 확인하도록 최신 코드를 로컬 DB 복사본으로 띄우고 해당 화면을 연다. 사용자가 "켜줘", "확인하게 띄워줘", "로컬에서 보여줘"라고 할 때도 쓴다.
---

# 로컬에서 켜서 확인하기

화면이 바뀌는 작업이 끝나면 마지막 단계로 이걸 돌려 사용자에게 바로 볼 수 있는 주소를 준다.
백엔드만 바뀐 작업도 사용자가 화면에서 결과를 확인할 수 있으면 돌린다.

## 띄우기

```bash
.claude/skills/local-preview/up.sh /review/54     # 열 경로. 없으면 첫 화면
.claude/skills/local-preview/up.sh /review/54 --keep-db   # DB 복사본을 다시 만들지 않음(빠름)
```

- 띄우는 것: DB 복사본 5433, 조사 사이드카 8009(지금 `josa/` 코드), 백엔드 8081(지금 작업 트리, Docker가 아니라 맥에서 `gradlew bootRun`), 프론트 5173(맥에서 vite — compose 프론트 컨테이너는 멈춰 둔다).
- 데이터는 로컬 compose postgres(5432)를 그대로 복사한 것 — 프로드가 아니고 마지막으로 `deploy/pull-prod-db.sh`를 돌린 시점의 데이터다.
- 새 마이그레이션은 복사본에만 적용된다. compose postgres·백엔드(5432·8080)는 건드리지 않고, 프론트 컨테이너만 멈췄다가 down.sh가 다시 켠다.
- OpenAI·DeepL 키는 로컬 compose 백엔드 컨테이너에서 가져온다. GPT 기능을 누르면 실제 비용이 든다.
- 처음엔 백엔드 빌드·기동에 1~2분 걸린다. 오래 걸리면 Bash의 `run_in_background`로 돌린다.

## 사용자에게 알릴 것

- 연 주소와 어디를 눌러 보면 되는지(바뀐 화면까지 가는 길).
- 데이터가 언제 것인지, GPT 버튼은 실제로 돈다는 것.
- 다 보면 끈다고 말해 달라고.

## 끄기

사용자가 다 봤다고 하면:

```bash
.claude/skills/local-preview/down.sh
```

## 막히면

- `백엔드가 뜨지 않았습니다` → 출력된 `backend.log` 끝부분을 보고 원인을 말한다(마이그레이션 오류가 흔하다).
- 리뷰 링크에 쓸 프로젝트 id: `docker exec gt-verify-pg psql -U postgres -d gametrans -At -c "select id, game_id, file_name from project order by id"`.
