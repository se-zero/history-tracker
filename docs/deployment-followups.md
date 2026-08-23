# 배포 경로 후속 작업 TODO

> 배포 준비(PR #109)에서 의도적으로 범위 밖에 둔 것과, 도메인 확보 후에야 확인 가능한 것을 모은다.
> 배포 절차 자체는 `docs/deployment.md`를 본다.

---

## 1. RabbitMQ 자격증명을 URL에서 떼어낸다

### 배경

지금 ai-engine은 브로커 접속 정보를 **URL 한 덩어리**로 받는다.

```yaml
RABBITMQ_URL: amqp://${RABBITMQ_USER:-guest}:${RABBITMQ_PASSWORD:-guest}@rabbitmq:5672/
```

`graph/consumer.py:14`가 이 값을 그대로 읽어 `aio_pika.connect_robust()`에 넘긴다.
비밀번호가 `guest` 고정이던 시절에는 문제가 아니었지만, **배포를 위해 이 값을 설정 가능하게
만들면서 시크릿이 URL 안에 들어앉았다.** 그 결과 두 가지 제약이 생겼다.

**(a) 비밀번호가 로그로 샜다** — `consumer.py`가 기동 때마다 URL 전체를 INFO로 찍고 있었다.
PR #109 봇 리뷰에서 지적돼 `mask_amqp_url()`로 가렸지만, **이건 새는 지점을 막은 것이지
비밀번호가 URL에 있다는 사실 자체를 없앤 것이 아니다.** 다른 곳에서 URL을 찍으면 다시 샌다
(예외 메시지에 URL이 실리는 라이브러리 경로도 남아 있다).

**(b) 비밀번호에 쓸 수 있는 문자가 제한된다** — `/`·`@`·`#`·`?`가 들어가면 URL 파서가 vhost나
host로 오인해 연결이 조용히 깨진다. 그래서 `docs/deployment.md`와 `.env.example`이
"`openssl rand -base64`를 쓰지 말고 `-hex`를 써라"라는 **사람이 지켜야 하는 규칙**을 안내하고 있다.
지키지 않으면 기동은 되는데 수집만 멈춘다.

### 할 일

- [ ] compose에서 `RABBITMQ_URL` 대신 `RABBITMQ_HOST`·`RABBITMQ_USER`·`RABBITMQ_PASSWORD`를 넘긴다
- [ ] `graph/consumer.py`가 세 값을 받아 `urllib.parse.quote()`로 인코딩해 URL을 조립한다.
      `RABBITMQ_URL`이 설정돼 있으면 그대로 쓰는 하위 호환 경로를 한동안 남길지 결정한다
- [ ] 특수문자 비밀번호로 실제 연결이 되는지 확인한다(현재는 깨지는 것이 정상 동작이다)
- [ ] `.env.example`·`docs/deployment.md`에서 **URL-safe 경고를 제거**한다 — 이 작업의 실질 성과다
- [ ] `mask_amqp_url()`과 그 테스트는 유지한다. URL을 조립하는 이상 로그 마스킹은 계속 필요하다

**우선순위: 중간.** 지금도 안전하게 운영할 수 있지만(마스킹 + 문서 경고), 두 방어 모두
**사람이 규칙을 지켜야 성립한다.** 계약을 바꾸면 둘 다 구조적으로 사라진다.

---

## 2. 터널 실기동 검증 (도메인 확보 후)

PR #109는 코드가 완성됐지만 도메인·토큰이 없어 터널을 실제로 띄우지 못했다.
포트 폐쇄·자원 상한·백업/복구는 실기동으로 확인됐고, 아래만 남았다.

- [ ] `./prod.sh logs cloudflared`의 **실제 연결 성공 로그 문구 확인** —
      `docs/deployment.md` 2-3에 `Registered tunnel connection`이라고 적어 뒀으나 **추측이다.**
      다르면 문서를 고친다
- [ ] `https://<도메인>` 접속 → GitHub 로그인 (콜백 URL이 실제로 맞는지)
- [ ] GitHub webhook 수신 — PR 머지 후 증분 수집이 도는지
- [ ] provider 9종 중 로컬·배포 콜백을 **동시에 등록할 수 있는 곳이 어디인지** 확인해
      `docs/deployment.md` 3-1의 경고를 확정으로 바꾼다(현재 GitHub·Notion만 확인됨)

---

## 3. `upload-artifact` 권한 실측

`.github/workflows/test.yml`의 아티팩트 업로드는 테스트가 **실패할 때만** 돈다.
처음에는 `actions: write`를 부여했다가, PR #109 리뷰에서 근거가 불확실하다는 지적을 받고
제거했다 — v4는 `GITHUB_TOKEN`이 아니라 러너가 주입하는 `ACTIONS_RUNTIME_TOKEN`으로
업로드하는 것으로 보이나, 공식 README에 권한 언급이 없어 **어느 쪽도 문서로 확인되지 않는다.**

- [ ] 실제로 테스트가 실패하는 첫 CI 실행에서 리포트 업로드가 되는지 확인한다.
      안 되면 `actions: write`를 되살리고 그때 근거를 주석에 남긴다

**우선순위: 낮음.** 최소 권한 쪽으로 틀어 둔 상태라, 틀렸더라도 손실은 "실패한 런의 리포트를
못 받는다"이고 즉시 드러난다.

---

## 4. pipeline-worker 인바운드 인증 (범위 밖으로 둔 것)

`POST /api/v1/collect/{provider}`와 `POST /api/v1/raw/*`에는 인증이 없다.
현재는 **네트워크 경계 세 겹**으로만 막는다 — 포트 폐쇄 · 터널 ingress에 미등록 · nginx의 좁은
webhook prefix(`docs/deployment.md` 4-4).

설정 세 곳 중 하나만 어긋나면 열리는 구조라, 언젠가는 애플리케이션 레벨 인증이 맞다.
다만 컨트롤러·테스트에 걸치는 별개 변경이라 배포 준비와 분리했다.

- [ ] `INTERNAL_SERVICE_TOKEN` 헤더 검증을 pipeline-worker 인바운드에도 적용할지 검토
      (backend에는 이미 `InternalServiceAuthenticationFilter`가 있다 — 같은 패턴을 옮길 수 있는지)
- [ ] webhook 경로는 GitHub 서명 검증이 이미 있으므로 예외로 둘지 결정

**우선순위: 중간.** 지금 노출돼 있지는 않지만, 방어가 설정에 걸려 있고 그 설정이 늘고 있다
(터널 도입으로 대시보드라는 새 경로가 하나 더 생겼다).

---

## 5. 제거된 GitHub 설치의 영구 실패가 502로 보고된다

사용자가 GitHub 설정에서 앱을 직접 제거하면 `github_installations` 행이 남는다(우리 UI의 연동
해제는 설치를 **의도적으로** 남긴다 — 계정 단위라 다른 프로젝트가 재사용한다). 이때 레포 목록
조회가 실패하는데, `GitHubAppClient.gitHubApiException`이 GitHub 응답 오류를 상태 코드 구분 없이
전부 502로 바꾼다. **영구 실패(404 = 설치 없음)가 일시 장애로 보여** 사용자가 재시도를 반복하게 된다.

- [ ] 404를 502와 분리해 "이 GitHub 설치는 더 이상 유효하지 않다(재설치 필요)"로 답한다
- [ ] (선택) 로그인 동기화에서 GitHub이 돌려주지 않은 내 설치는 목록 표시에서 거른다

**행을 삭제하는 경로는 만들지 않는다** — `integrations.installation_id` FK가 `ON DELETE CASCADE`라
프로젝트 연동까지 함께 지워질 위험만 있고, 얻는 것은 위 두 항목으로 이미 해결된다.

**우선순위: 낮음.** 데이터 손실이 없다 — 앱이 제거되면 GitHub이 webhook을 보내지 않아 수집은
자연히 멈추고, 쌓인 그래프는 그대로다. 드러나는 것은 소스 화면의 오류 표시뿐이다.
