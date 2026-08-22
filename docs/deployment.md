# 배포 가이드 (실사용 · 단일 호스트)

단일 서버 한 대에 전체 스택을 compose로 올리는 절차를 적는다.
로컬 개발 기동은 루트 `CLAUDE.md`를 본다 — 이 문서는 **실제 사용자가 붙는 배포**만 다룬다.

로컬과 배포의 차이는 compose 오버라이드 파일 하나뿐이다.

| | 로컬 | 배포 |
|---|---|---|
| 실행 | `./dev.sh` | `./prod.sh` |
| 오버라이드 | `docker-compose.dev.yml` | `docker-compose.prod.yml` |
| **외부에 열리는 포트** | 인프라·앱 전부(9개) | **0개** — 웹은 `127.0.0.1`에만 |
| 공개 경로 | 없음 | **Cloudflare Tunnel** (cloudflared 컨테이너) |
| 자원 상한 | 없음 | 컨테이너별 메모리·JVM/Neo4j 힙 |
| 재시작·로그 | 없음 | `unless-stopped` · 로테이션 |

**공개는 터널이 맡는다.** `cloudflared`가 바깥으로 아웃바운드 연결만 만들어 Cloudflare 엣지에
붙고, 들어오는 요청을 도커 네트워크의 `web-dashboard:80`으로 넘긴다. 그래서

- **공유기에 포트를 하나도 열지 않는다.** CGNAT 회선에서도 동작한다.
- 집 IP가 드러나지 않는다.
- TLS가 엣지에서 끝나므로 **인증서 발급·갱신이 우리 몫이 아니다.**
- GitHub webhook(바깥 → 우리 서버)도 이 경로로 들어온다.

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

### 소프트웨어 준비

게스트 OS는 **Ubuntu Server LTS**(또는 Debian)를 상정한다. 필요한 것은 셋뿐이다.

| | 왜 |
|---|---|
| **Docker Engine + Compose plugin** | 스택 전체가 compose다 |
| **git** | 레포를 받아 온다 |
| **`docker` 그룹 멤버십** | `prod.sh`·`backup.sh`가 docker를 직접 부른다. sudo가 필요하면 cron 백업이 조용히 실패한다 |

설치는 [Docker 공식 apt 저장소 절차](https://docs.docker.com/engine/install/ubuntu/)를 따른다.
배포판의 `docker.io` 패키지는 Compose plugin이 빠져 있거나 오래된 경우가 있어 권장하지 않는다.

```bash
sudo usermod -aG docker "$USER"   # 적용하려면 로그아웃 후 재접속
docker compose version            # v2가 나와야 한다 (docker-compose가 아니라 docker compose)
```

**Compose는 v2면 충분하다.** 특정 최신 버전을 요구하지 않는다 — 포트를 오버라이드로 덮는 대신
base/dev/prod 3분할 구조를 쓴 이유 중 하나가 `!override` 태그(2.24+)에 의존하지 않기 위해서다.

네트워크는 **아웃바운드만 열려 있으면 된다.** 인바운드는 Cloudflare Tunnel이 만들므로 공유기에
포트포워딩을 설정하지 않는다. 아웃바운드는 터널·OpenAI·각 provider API에 쓰인다.

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
| `TUNNEL_TOKEN` | Cloudflare 대시보드 | 2-2에서 발급받는다. 이것이 있어야 바깥에서 접근할 수 있다 |

> ⚠️ **RabbitMQ 비밀번호에 `/`·`@`·`#`·`?`를 쓰지 않는다.** ai-engine이 이 값을 AMQP URL
> (`amqp://user:password@rabbitmq:5672/`) 안에 끼워 넣기 때문에, 특수문자가 있으면 파서가 vhost나
> host로 오인해 연결이 조용히 깨진다. `openssl rand -base64`는 `+`·`/`를 만들므로 **쓰지 않는다.**

> 🔑 **`BACKEND_CREDENTIAL_KEY`는 백업과 다른 곳에 보관한다.** 저장된 OAuth 자격증명이 이 키로
> 암호화돼 있어, 키를 잃으면 DB 백업을 살려도 **복호화할 수 없다.** 반대로 백업과 키를 같은 곳에
> 두면 백업 한 번 새는 순간 전체 자격증명이 털린다.

### 2-2. Cloudflare 터널 준비

도메인과 터널 토큰이 있어야 공개할 수 있다. **기동 전에 끝내 둔다** — 토큰이 `.env`에 있어야
`./prod.sh`가 터널까지 함께 띄운다.

1. **도메인 확보** — Cloudflare에서 구입하거나, 다른 곳에서 산 도메인의 네임서버를
   Cloudflare로 옮긴다.
2. **터널 생성** — Zero Trust → Networks → Tunnels → Create a tunnel → **Cloudflared** 선택.
   생성 후 화면에 나오는 설치 명령 안의 `eyJ...` 문자열이 토큰이다.
   `.env`의 `TUNNEL_TOKEN`에 넣는다.
3. **Public hostname 등록** — 같은 터널 설정에서

   | 항목 | 값 |
   |---|---|
   | Subdomain / Domain | 서비스를 띄울 호스트명 |
   | Service Type | `HTTP` |
   | URL | `web-dashboard:80` |

   `web-dashboard`는 compose 서비스 이름이다. cloudflared가 같은 도커 네트워크에 있어
   이 이름으로 해석된다(호스트 포트를 거치지 않는다).

> 터널 토큰은 **시크릿이다.** 이 값 하나로 누구나 같은 터널의 커넥터를 띄울 수 있다.
> compose는 커맨드라인 대신 환경변수로 넘긴다 — `--token <값>`으로 주면 `docker ps`·`ps aux`에
> 그대로 보인다.

**도메인을 아직 확보하지 못했다면** 터널 없이 기동해 자원 상한·포트 폐쇄 같은 것만 먼저
점검할 수 있다. 이 상태에서는 서버 자신만 `http://localhost`로 접근할 수 있다.

```bash
./prod.sh --no-tunnel up -d
```

### 2-3. 기동

```bash
./prod.sh up -d --build
./prod.sh ps          # 전부 healthy 인지 확인
./prod.sh logs -f cloudflared   # "Registered tunnel connection" 이 보이면 붙은 것
./prod.sh logs -f backend
```

`GITHUB_REDIRECT_URI`가 비어 있으면 **여기서 명시적으로 실패한다**(의도된 동작).
`TUNNEL_TOKEN`이 비어 있으면 cloudflared만 기동 실패를 반복한다 — 나머지 스택은 뜨지만
바깥에서 닿을 수 없다.

### 2-4. 확인

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

> ⚠️ **배포 URL로 바꾸면 로컬 개발이 깨질 수 있다.** provider마다 redirect URI를 여러 개
> 등록할 수 있는지가 다르다. GitHub App과 Notion은 로컬·배포를 함께 둘 수 있다(각각 공식
> 지원). 나머지 7종은 **콘솔에서 직접 확인해야 한다** — 하나만 허용하는 provider가 있으면
> 로컬과 배포 중 하나를 골라야 한다.
>
> `.env`의 `*_REDIRECT_URI`는 어차피 환경마다 하나만 고르는 값이므로, **콘솔에 둘 다 등록해 두고
> `.env`만 바꿔 쓰는 것**이 가장 마찰이 적다.

### 3-2. GitHub App은 등록할 곳이 두 개다

| GitHub App 설정 | 값 | 방향 |
|---|---|---|
| Callback URL | `https://<도메인>/auth/callback` | 사용자 브라우저 → 프론트 |
| **Webhook URL** | `https://<도메인>/api/v1/webhook/github` | **GitHub 서버 → 우리 서버** |
| Webhook secret | `.env`의 `GITHUB_WEBHOOK_SECRET`과 동일 | 직접 정하는 값(`openssl rand -hex 32`) |

Webhook은 바깥에서 들어오는 방향이라 **공개 접근 가능한 실제 도메인**이어야 한다. 개발용 터널
주소를 남겨 두면 배포 후 증분 수집이 멈춘다. `GITHUB_WEBHOOK_SECRET`이 비면 모든 webhook이
거부된다(fail-closed) — 초기 수집은 정상이라 조용히 지나가기 쉽다.

**공유기에 포트를 열 필요는 없다.** Cloudflare Tunnel이 GitHub의 요청을 받아 도커 네트워크
안으로 넘긴다(2-2). nginx가 `/api/v1/webhook/`만 pipeline-worker로 프록시하므로 이 경로만
바깥에 존재한다.

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

### 4-2. 인프라 접근 (외부에 열린 포트가 없다)

배포에서는 Postgres·Neo4j·RabbitMQ 포트가 호스트에 없고, 웹조차 `127.0.0.1`에만 묶여 있다.
서버 자신에서는 앱을 확인할 수 있다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost      # 웹 (루프백)
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

### 4-3. Cloudflare 엣지의 제약

| 항목 | 값 | 우리에게 의미 |
|---|---|---|
| Proxy Read Timeout | **125초** 초과 시 524 | 질의(`/query`)가 평균 12초대이고, backend가 ai-engine 호출에 60초 read timeout을 걸어 앱이 먼저 끊는다. 지금은 2배 이상 여유가 있다 |
| 요청 본문 | 무료 플랜 100MB | webhook·API 페이로드가 근처에도 가지 않는다 |

**125초는 올릴 수 없다 — Enterprise 플랜 전용이다**(최대 6000초). 그리고 **스트리밍도 예외가
아니다**: Cloudflare 문서는 "요청이 125초를 넘으면(예: 스트리밍) Proxy Read Timeout을 올리라"고
안내하며, 그 설정 자체가 Enterprise 전용이다. 응답을 조금씩 흘려보낸다고 타이머가 리셋되지 않는다.

그래서 **125초를 넘길 일이 생기면 인프라가 아니라 애플리케이션에서 푼다.** 이 저장소에는 이미
같은 패턴이 있다 — 그래프 재구축은 `POST .../graph/build`가 즉시 202를 반환하고
`GET .../graph/build/status`로 폴링한다. 긴 질의도 같은 모양으로 바꾸면 한도와 무관해진다.
(Cloudflare가 안내하는 다른 방법인 "DNS-only 서브도메인으로 우회"는 프록시를 벗어나는 것이라
터널의 이점—포트 미개방·IP 비노출—을 통째로 잃는다.)

터널이 끊기면 앱은 살아 있는데 바깥에서만 안 보인다. `./prod.sh logs cloudflared`로 커넥션
상태를 먼저 본다.

### 4-4. ⚠️ 이 배포가 의존하는 방어

pipeline-worker의 `POST /api/v1/collect/{provider}`와 `POST /api/v1/raw/*`에는 **인증이 없다.**
바깥에서 닿지 않는 이유는 세 겹이다.

1. prod 오버라이드가 **8081을 호스트에 열지 않는다**
2. 터널 ingress에 **`web-dashboard:80` 하나만 등록**돼 있다 — pipeline-worker로 가는 공개 경로가 없다
3. 그 웹의 nginx가 `/api/v1/webhook/`만 pipeline-worker로 좁게 프록시한다

즉 이 방어는 **설정 세 곳에 걸려 있고, 어느 하나만 어긋나도 열린다.** 다음을 하면 노출된다.

- `./prod.sh` 대신 `./dev.sh`로 배포 서버를 띄우는 것
- prod 오버라이드에 pipeline-worker 포트를 추가하는 것
- Cloudflare 대시보드에서 **`pipeline-worker:8081`을 가리키는 public hostname을 추가**하는 것
  (호스트 포트를 안 열어도 터널이 바로 뚫어 준다 — 가장 놓치기 쉬운 경로다)
- 호스트 방화벽 앞단에서 8081을 포워딩하는 것

노출되면 누구나 임의 프로젝트의 수집을 트리거해 OpenAI·외부 API 쿼터를 태우고 원본 데이터를
당길 수 있다.

### 4-5. 백업

`infra/scripts/backup.sh`가 PostgreSQL과 Neo4j를 **호스트 로컬**에 덤프한다.
오프사이트 복사는 하지 않는다 — 홈서버가 임시 구성이라는 전제다. 바꿔 말하면
**호스트가 통째로 죽으면 백업도 함께 사라진다.** 오래 운영할 구성이면 이 전제를 다시 본다.

| 볼륨 | 백업 | 유실되면 |
|---|---|---|
| `postgres_data` | ✅ `pg_dump -Fc` (무중단) | **재생성 불가** — 전 사용자 재연동 |
| `neo4j_data` | ✅ `neo4j-admin database dump` (**중단 필요**) | 재수집·재구축 가능하지만 OpenAI 비용 |
| `rabbitmq_data` | ❌ | 재수집으로 복구되는 일시 상태 |

**Neo4j만 중단이 필요한 이유** — 온라인 백업은 Enterprise 전용이고, Community의
`neo4j-admin database dump`는 실행 중인 서버에 마운트된 DB를 덤프하지 못한다. 그래서
stop → dump → start 순서다. 실측 중단 시간은 **671MiB 기준 6초**. 스크립트가 `trap`으로
어떤 실패·중단 경로에서도 Neo4j를 다시 올린다.

```bash
./infra/scripts/backup.sh                    # 기본 $HOME/history-tracker-backups
BACKUP_DIR=/mnt/backup ./infra/scripts/backup.sh
```

설정은 **환경변수로 준다**(`infra/docker/.env`가 아니다 — 그 파일은 docker compose 전용이고
이 스크립트는 읽지 않는다).

| 변수 | 기본 | 설명 |
|---|---|---|
| `BACKUP_DIR` | `$HOME/history-tracker-backups` | 레포 밖에 두어 `git clean`에 쓸려가지 않게 한다 |
| `BACKUP_RETENTION_DAYS` | `14` | 초과분 삭제. **이번 백업이 성공한 뒤에만** 지운다 |

cron 등록 (매일 04:00). 실패를 알 수 있어야 하므로 출력을 로그로 남긴다:

```cron
0 4 * * * BACKUP_DIR=/mnt/backup /path/to/infra/scripts/backup.sh >> /var/log/history-backup.log 2>&1
```

#### 복구

```bash
./infra/scripts/restore.sh --pg <파일> --neo4j <파일>
```

인자 없이 실행하면 아무것도 하지 않고 사용법만 출력한다 — 기본 동작이 파괴적이면 안 되기
때문이다. 실행하면 무엇을 덮어쓰는지 보여주고 `yes` 입력을 요구한다(`--yes`로 생략 가능).
복구 중에는 backend·pipeline-worker·ai-engine을 내렸다가 다시 올린다(커넥션이 살아 있으면
`pg_restore --clean`이 객체를 지우지 못한다).

> ⚠️ **`BACKEND_CREDENTIAL_KEY`가 없으면 이 백업은 절반만 복구된다.** 저장된 OAuth 자격증명이
> 그 키로 암호화돼 있어, DB를 되살려도 키가 없으면 복호화할 수 없어 전 사용자가 재연동해야 한다.
> 그렇다고 키를 백업 옆에 두면 백업 한 번 새는 순간 전체 자격증명이 털린다.
> **백업과 다른 곳에 보관한다** (2-1 참고).

#### 복원해 본 적 없는 백업은 백업이 아니다

배포 후 한 번은 실제로 되돌려 본다. 덤프 파일이 유효한지만 확인하려면 라이브 데이터를
건드리지 않고도 할 수 있다.

```bash
# Postgres — 임시 DB에 복원해 보고 지운다
docker exec history-tracker-postgres createdb -U history_tracker restore_probe
docker exec -i history-tracker-postgres pg_restore --no-owner -U history_tracker \
  -d restore_probe < pg-<타임스탬프>.dump
docker exec history-tracker-postgres psql -U history_tracker -d restore_probe \
  -tAc "SELECT count(*) FROM users"        # 원본과 대조
docker exec history-tracker-postgres dropdb -U history_tracker restore_probe

# Neo4j — 아카이브 메타데이터만 읽는다(로드하지 않음)
docker run --rm -i neo4j:5.26-community \
  neo4j-admin database load neo4j --from-stdin --info < neo4j-<타임스탬프>.dump
```

**전체 복구 리허설은 격리된 스택에서 할 수 없다.** compose가 `container_name`을 고정하고
있어 같은 호스트에 두 번째 스택을 띄우면 이름이 충돌한다. 리허설을 하려면 운영 스택을
내리고 백업에서 되돌린 뒤 로그인·그래프 조회까지 확인하는 방식이 된다.

---

## 5. 아직 이 문서가 다루지 않는 것

| 항목 | 상태 |
|---|---|
| 오프사이트 백업 | 하지 않는다 — 4-5의 전제 참고 |
| 모니터링·알림 | 아직 없음 |
| Cloudflare Access(접근 제한) | 아직 없음. 지금은 도메인을 아는 누구나 로그인 화면까지 닿는다 |
