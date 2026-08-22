# 배포 가이드 (실사용 · 단일 호스트)

단일 서버 한 대에 전체 스택(컨테이너 7개)을 compose로 올리는 절차를 적는다.
로컬 개발 기동은 루트 `CLAUDE.md`를 본다 — 이 문서는 **실제 사용자가 붙는 배포**만 다룬다.

로컬과 배포의 차이는 compose 오버라이드 파일 하나뿐이다.

| | 로컬 | 배포 |
|---|---|---|
| 실행 | `./dev.sh` | `./prod.sh` |
| 오버라이드 | `docker-compose.dev.yml` | `docker-compose.prod.yml` |
| 열리는 포트 | 인프라·앱 전부(9개) | **웹(80) 하나** |
| 자원 상한 | 없음 | 컨테이너별 메모리·JVM/Neo4j 힙 |
| 재시작·로그 | 없음 | `unless-stopped` · 로테이션 |

---

## 1. 호스트 준비

기준 구성은 **Proxmox VM 1대**다(홈서버 상정).

| 항목 | 값 | 이유 |
|---|---|---|
| 가상화 | **VM** (LXC 아님) | LXC에 Docker를 넣으면 nesting·keyctl·apparmor 설정이 계속 붙는다 |
| CPU | **소켓 1 × 코어 6** | 소켓을 2로 나누면 게스트에 가짜 NUMA 토폴로지가 생겨 스케줄링만 나빠진다. 호스트가 4코어/8스레드급이면 4로 줄인다 |
| CPU Type | **`host`** | 기본값은 최신 명령어 세트를 게스트에서 숨긴다. 단일 노드에 라이브 마이그레이션 계획이 없으면 `host`가 맞다 |
| RAM | **16GB, 벌루닝 비활성**(min = max) | **벌루닝이 가장 위험하다** — JVM은 한 번 잡은 힙을 반납하지 않아, 호스트가 회수하려 들면 스왑으로 끌려가 응답이 통째로 멎는다 |
| 디스크 | **NVMe/SSD 64GB** | Neo4j pagecache 미스와 Postgres가 전부 디스크로 간다. HDD면 질의 지연이 바로 체감된다 |
| 스왑 | 2GB, `vm.swappiness=10` | 컨테이너는 스왑을 못 쓰게 막아 뒀다(아래). 호스트 자체의 여유용 |

**호스트가 ZFS면 `zfs_arc_max`를 고정한다.** 기본값이 호스트 RAM의 상당 부분을 먹어서, 32GB
호스트에 16GB VM을 주면 ARC와 겹쳐 압박이 온다. 8GB 정도로 상한을 두면 예측 가능해진다.

### 자원 배분

`docker-compose.prod.yml`이 컨테이너별 상한을 박는다. 합 약 10.4GB로, 16GB에서 5.5GB가 남는다 —
OS와 **이미지 빌드 피크**(Gradle 빌드 2개 + `npm ci`가 동시에 도는 순간)를 위한 여유다.

| 컨테이너 | 상한 | 내부 힙 |
|---|---|---|
| neo4j | 4g | heap 2G / pagecache 1G |
| pipeline-worker | 2g | `MaxRAMPercentage=70` |
| backend | 1.5g | `MaxRAMPercentage=70` |
| ai-engine | 1g | — |
| postgres | 1g | `shared_buffers=256MB` |
| rabbitmq | 768m | — |
| web-dashboard | 128m | — |

**왜 상한을 박아야 하나** — JVM은 컨테이너에 메모리 제한이 없으면 *호스트* RAM의 25%를 힙 상한으로
잡고, Neo4j도 자기가 본 호스트 RAM 기준으로 heap·pagecache를 자동 산정한다. 상한이 없으면 합이
물리 메모리를 넘고, 넘은 것을 아무도 모르다가 OOM killer가 정리한다. **VM을 키워도 각자 더 잡아서
해결되지 않는다.**

`memswap_limit`을 `mem_limit`과 같게 두어 **컨테이너의 스왑을 막았다.** JVM이 스왑되면 GC가
디스크를 훑어 죽지도 살지도 않는 상태가 된다 — 즉시 OOM kill 후 재시작이 낫다.

CPU 상한은 걸지 않는다. 수집이 버스트성이라 커널 스케줄러의 공평 분배로 충분하고, 상한을 걸면
버스트 수집이 느려지기만 한다.

---

## 2. 배포 절차

### 2-1. 코드와 `.env` 준비

```bash
git clone <repo> && cd history-tracker/infra/docker
cp .env.example .env
```

`.env`를 채운다. **`.env.example`의 맨 아래 "배포 시 반드시 직접 채운다" 블록이 핵심이다** —
이 값들은 비워 두어도 코드가 막지 않고 **개발 기본값으로 조용히 뜬다.**

| 키 | 생성 | 비고 |
|---|---|---|
| `JWT_SECRET` | `openssl rand -hex 32` | 개발 기본값이 저장소에 공개돼 있다. 그대로 뜨면 **누구나 토큰을 위조**할 수 있다 |
| `POSTGRES_PASSWORD` · `NEO4J_PASSWORD` | `openssl rand -hex 32` | 비우면 `history_tracker` / `password1234` |
| `INTERNAL_SERVICE_TOKEN` | `openssl rand -hex 32` | backend·pipeline-worker가 같은 값 |
| `BACKEND_CREDENTIAL_KEY` | `openssl rand -base64 32` | **형식 고정**(32-byte Base64) — hex를 쓰면 안 된다 |
| `RABBITMQ_USER` · `RABBITMQ_PASSWORD` | `openssl rand -hex 32` | **URL-safe 값만** — 아래 경고 참고 |

> ⚠️ **RabbitMQ 비밀번호에 `/`·`@`·`#`·`?`를 쓰지 않는다.** ai-engine이 이 값을 AMQP URL
> (`amqp://user:password@rabbitmq:5672/`) 안에 끼워 넣기 때문에, 특수문자가 있으면 파서가 vhost나
> host로 오인해 연결이 조용히 깨진다. `openssl rand -base64`는 `+`·`/`를 만들므로 **쓰지 않는다.**

> 🔑 **`BACKEND_CREDENTIAL_KEY`는 백업과 다른 곳에 보관한다.** 저장된 OAuth 자격증명이 이 키로
> 암호화돼 있어, 키를 잃으면 DB 백업을 살려도 **복호화할 수 없다.** 반대로 백업과 키를 같은 곳에
> 두면 백업 한 번 새는 순간 전체 자격증명이 털린다.

### 2-2. 기동

```bash
./prod.sh up -d --build
./prod.sh ps          # 전부 healthy 인지 확인
./prod.sh logs -f backend
```

`GITHUB_REDIRECT_URI`가 비어 있으면 **여기서 명시적으로 실패한다**(의도된 동작).

### 2-3. 확인

- 브라우저로 `https://<도메인>` → 로그인 화면
- GitHub 로그인 → 프로젝트 생성 → 소스 연동 → 수집 시작
- 수집·그래프 상태 점검은 `.claude/skills/pipeline-inspect`의 쿼리를 쓰되, 명령의 `./dev.sh`를
  `./prod.sh`로 바꿔 읽는다

---

## 3. 외부 콘솔 등록 체크리스트

**로컬 값이 남아 있으면 그 provider의 연동만 조용히 깨진다.** 쓰는 provider만 하면 된다.

### 3-1. OAuth redirect URI (9종)

`<도메인>`은 배포 도메인이다. 각 provider 콘솔에 등록한 값과 `.env`의 값이 **정확히 일치**해야 한다.

| Provider | `.env` 키 | 등록할 URL |
|---|---|---|
| **GitHub** | `GITHUB_REDIRECT_URI` | `https://<도메인>/auth/callback` ← **경로 규칙이 다르다** |
| Slack | `SLACK_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/slack/callback` |
| Jira | `ATLASSIAN_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/jira/callback` |
| Discord | `DISCORD_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/discord/callback` |
| Google Chat | `GOOGLE_CHAT_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/google-chat/callback` |
| Linear | `LINEAR_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/linear/callback` |
| Asana | `ASANA_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/asana/callback` |
| ClickUp | `CLICKUP_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/clickup/callback` |
| Notion | `NOTION_REDIRECT_URI` | `https://<도메인>/api/v1/integrations/notion/callback` |

**GitHub만 프론트 라우트(`/auth/callback`)에 착지한다.** 다른 provider는 backend 콜백이 처리 후
프론트로 302를 보내지만, GitHub은 React 페이지가 먼저 받아 backend에 code를 넘긴다.
나머지 8종도 backend가 아니라 **프론트 오리진**을 써야 한다 — 콜백의 302가 상대 경로라
backend(:8080)를 직접 가리키면 연동은 성공해도 마지막 리다이렉트가 401로 끝난다.

### 3-2. GitHub App은 등록할 곳이 두 개다

| GitHub App 설정 | 값 | 방향 |
|---|---|---|
| Callback URL | `https://<도메인>/auth/callback` | 사용자 브라우저 → 프론트 |
| **Webhook URL** | `https://<도메인>/api/v1/webhook/github` | **GitHub 서버 → 우리 서버** |
| Webhook secret | `.env`의 `GITHUB_WEBHOOK_SECRET`과 동일 | 직접 정하는 값(`openssl rand -hex 32`) |

Webhook은 바깥에서 들어오는 방향이라 **공개 접근 가능한 실제 도메인**이어야 한다. 로컬 터널
주소를 남겨 두면 배포 후 증분 수집이 멈춘다. `GITHUB_WEBHOOK_SECRET`이 비면 모든 webhook이
거부된다(fail-closed) — 초기 수집은 정상이라 조용히 지나가기 쉽다.

GitHub App은 Callback URL을 여러 개 등록할 수 있어 로컬과 배포를 함께 둘 수 있다.
`.env`의 `GITHUB_REDIRECT_URI`만 환경별로 하나 고른다.

### 3-3. Jira 개인정보 보고 봇 계정 (Jira를 쓰는 경우 최초 1회)

Atlassian은 개인정보 보고 의무가 앱 전체에 걸리므로, 특정 사용자 토큰이 아니라 **봇 계정의 앱
수준 토큰**으로 보고한다. 절차는 `docs/jira-personal-data-policy.md`의 「배포 절차 — 봇 계정 등록」을
그대로 따르고, 끝나면 `.env`에 `ATLASSIAN_PDR_ENABLED=true`를 켠다.

---

## 4. 운영

### 4-1. 재시작·로그

전 컨테이너가 `restart: unless-stopped`라 정전·재부팅 후 자동으로 올라온다.
로그는 컨테이너당 30MB(10MB × 3)로 로테이션된다 — 기본 json-file 드라이버는 상한이 없어
홈서버에서 몇 달이면 디스크를 채운다.

### 4-2. 인프라 접근 (포트가 안 열려 있다)

배포에서는 Postgres·Neo4j·RabbitMQ 포트가 호스트에 없다. 조회는 컨테이너 안에서 한다.

```bash
docker exec -it history-graph-neo4j cypher-shell -u neo4j -p "$NEO4J_PASSWORD"
docker exec -it history-tracker-postgres psql -U history_tracker -d history_tracker
```

Neo4j Browser처럼 **웹 UI가 꼭 필요하면** 포트를 인터넷에 여는 대신 루프백에만 묶고 SSH 터널로
접근한 뒤 되돌린다.

```bash
# 서버: 임시 오버라이드로 127.0.0.1 에만 노출
#   ports: ["127.0.0.1:7474:7474", "127.0.0.1:7687:7687"]
# 로컬:
ssh -L 7474:127.0.0.1:7474 -L 7687:127.0.0.1:7687 <user>@<서버>
```

### 4-3. ⚠️ 이 배포가 의존하는 방어

pipeline-worker의 `POST /api/v1/collect/{provider}`와 `POST /api/v1/raw/*`에는 **인증이 없다.**
nginx가 `/api/v1/webhook/`만 pipeline-worker로 좁게 프록시하고, **prod 오버라이드가 8081을 호스트에
열지 않기 때문에** 바깥에서 닿지 않는다.

즉 이 방어는 **포트 폐쇄 하나에 걸려 있다.** 다음을 하면 그대로 노출된다.

- `./prod.sh` 대신 `./dev.sh`로 배포 서버를 띄우는 것
- prod 오버라이드에 pipeline-worker 포트를 추가하는 것
- 호스트 방화벽 앞단에서 8081을 포워딩하는 것

노출되면 누구나 임의 프로젝트의 수집을 트리거해 OpenAI·외부 API 쿼터를 태우고 원본 데이터를
당길 수 있다.

### 4-4. 백업

**아직 없다.** 백업·복구 스크립트는 별도 PR에서 추가한다 — 이 문서의 이 절을 그때 채운다.
그전까지 `postgres_data`·`neo4j_data` 볼륨이 유실되면 복구 수단이 없다는 뜻이다
(Postgres는 재생성 불가, Neo4j는 재수집·재구축에 OpenAI 비용이 든다).

---

## 5. 아직 이 문서가 다루지 않는 것

| 항목 | 상태 |
|---|---|
| TLS·도메인·인증서 | 별도 PR. **3-2의 Webhook URL이 여기서 완성된다** |
| DB 백업·복구 | 별도 PR (4-4) |
| CI 테스트 워크플로 | 별도 PR |
| 모니터링·알림 | 별도 PR |
