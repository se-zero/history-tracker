# infra/terraform — whycode AWS 인프라

whycode를 AWS 서울 리전(`ap-northeast-2`)에 올리는 인프라 코드다. 무엇을 왜 만드는지, 순서와 완료 조건은
[docs/aws-migration-plan.md](../../docs/aws-migration-plan.md)가 정본이다. 이 문서는 **어떤 명령을 어떤 순서로 치는지**만 다룬다.

> **요금은 `terraform apply`로 자원이 생기는 순간부터 나간다.** 아래 "검사"는 가짜 AWS로만 돌아 요금이 없다.
> apply는 사람이 직접 한다. 에이전트는 검사까지만 돌린다.

용어:

- **상태 파일(state)**: Terraform이 "무엇을 만들었는지" 적어 두는 파일이다. 이 파일이 있어야 다음 apply 때 바뀐 것만 고치고,
  destroy 때 만든 것을 지운다. 자원 id와 비밀번호 같은 값이 평문으로 들어갈 수 있어 커밋하지 않는다.
- **OIDC provider**: GitHub Actions가 비밀 키를 저장소에 두지 않고, 실행할 때마다 GitHub이 발급한 토큰으로 AWS 역할을
  빌리게 하는 신뢰 설정이다. 어떤 저장소·환경이 어떤 역할을 빌릴지는 역할 쪽 조건(T1)에서 정한다.
- **가짜 provider(`mock_provider`)**: `terraform test`가 AWS를 부르지 않고 그럴듯한 값을 지어내게 하는 테스트용 provider다.

## 구성

| 경로 | 무엇 | 상태 파일 위치 |
|---|---|---|
| `bootstrap/` | 상태 버킷(S3)과 GitHub Actions용 OIDC provider. 계정당 한 번만 만든다 | 로컬 `bootstrap/terraform.tfstate`(커밋 안 함) |
| `envs/staging/`, `envs/prod/` | 환경 루트. network·platform 모듈을 연결하고(T1), 진입점·데이터·앱은 T2~T4에서 더한다 | 상태 버킷의 `staging/terraform.tfstate`, `prod/terraform.tfstate` |
| `modules/network/` | VPC·서브넷·NAT·라우팅·보안 그룹 7개와 서비스 간 허용 규칙 | — |
| `modules/platform/` | ECR 3개·로그 그룹·비밀 껍데기 6개·ECS 실행 역할(서비스별)·GitHub Actions CI 역할 2개·알림(SNS)·예산 | — |
| `check.sh` | 모든 루트·모듈의 형식·문법·테스트 검사 | — |

각 루트·모듈의 `tests/*.tftest.hcl`이 `terraform test`가 읽는 테스트다. `terraform test`는 실행한 디렉터리의 `tests/`만
읽어서 테스트를 모듈마다 둔다. `.terraform.lock.hcl`(provider 버전·해시 고정)은 커밋한다.

## 사전 준비

1. 설치. terraform은 2023년 라이선스 변경 뒤 Homebrew 기본 저장소에서 빠져서 HashiCorp 저장소를 추가해 받는다.
   `brew install`에 이름을 여러 개 주면 하나라도 못 찾을 때 전부 멈추니 한 줄씩 친다.

   ```bash
   brew tap hashicorp/tap
   brew install hashicorp/tap/terraform   # 1.11 이상(S3 상태 잠금이 정식이 된 버전)
   brew install awscli
   ```

2. AWS 로그인(apply 전까지만). 루트 계정에 MFA를 켜고, 루트 키 대신 관리자 권한이 있는 사용자로 로그인한다.
   IAM Identity Center(AWS의 통합 로그인 서비스)를 쓰면 `aws configure sso`로 프로필을 만든다.
   쓰지 않으면 `aws login`으로 콘솔 로그인(브라우저 인증)을 거쳐 CLI용 임시 자격 증명을 받는다.

   ```bash
   # Identity Center를 쓸 때
   aws configure sso              # 관리자 프로필 만들기
   export AWS_PROFILE=<프로필 이름>

   # Identity Center가 없을 때
   aws login                      # 콘솔 계정으로 임시 자격 증명 받기

   aws sts get-caller-identity    # 의도한 계정인지 확인
   ```

   `aws` 명령은 되는데 Terraform이 자격 증명을 못 찾으면, 임시 자격 증명을 환경변수로 넘긴다.

   ```bash
   eval "$(aws configure export-credentials --format env)"
   ```

## 검사 (요금 없음)

```bash
infra/terraform/check.sh              # 전부
infra/terraform/check.sh bootstrap    # 일부만(infra/terraform 기준 상대 경로)
```

순서대로 다음을 한다. 하나라도 실패하면 그 자리에서 멈춘다.

1. 모든 테스트 파일에 `mock_provider "aws"`가 있는지 본다. 이 줄이 빠진 테스트는 `terraform test`가 **실제 계정에 자원을 만들었다가 지운다.**
2. `terraform fmt -check -recursive`로 형식을 본다.
3. 디렉터리마다 `init -backend=false`, `validate`를 돌리고, 테스트가 있으면 `test`를 돌린다.

모든 terraform 명령은 가짜 자격 증명으로 돈다. 테스트에서 가짜 provider 선언이 빠져도 실제 계정에 닿지 못하고 자격 증명 오류로 끝난다.
AWS provider는 한 벌에 700MB대라 `~/.terraform.d/plugin-cache`에 한 벌만 두고 디렉터리마다 링크한다. 위치는 `TF_PLUGIN_CACHE_DIR`로 바꿀 수 있다.

## 처음 한 번: 부트스트랩

S3 버킷 하나와 OIDC provider 하나라 요금은 거의 없다(상태 파일 몇 KB의 저장 요금).

```bash
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap plan     # S3 버킷과 하위 설정 4개, OIDC provider 1개
terraform -chdir=infra/terraform/bootstrap apply
terraform -chdir=infra/terraform/bootstrap output -raw state_bucket_name
```

- 상태 버킷 이름은 `whycode-tfstate-<계정 번호>`다. S3 버킷 이름은 전 세계에서 겹치면 안 돼서 계정 번호를 붙인다.
  저장소가 공개라 이 이름은 어디에도 커밋하지 않는다.
- 버킷에는 버전 관리(덮어쓴 상태 파일을 이전 버전으로 되돌릴 수 있다), 기본 암호화(AES256), 공개 차단 네 항목,
  TLS가 아닌 요청을 거부하는 정책이 걸린다.
- bootstrap 자신의 상태는 로컬 파일이다. 상태 버킷이 생기기 전에는 원격에 둘 곳이 없기 때문이다. 이 파일을 잃어버리면
  자원을 `terraform import`로 다시 연결한다. 버킷과 하위 설정 4개는 버킷 이름으로, OIDC provider는 ARN으로 연결한다.
- OIDC provider는 계정에 같은 주소로 하나만 만들 수 있다. 이미 있어서 apply가 `EntityAlreadyExists`로 실패하면 가져온다.

  ```bash
  terraform -chdir=infra/terraform/bootstrap import aws_iam_openid_connect_provider.github \
    arn:aws:iam::<계정 번호>:oidc-provider/token.actions.githubusercontent.com
  ```

## 환경 초기화

```bash
BUCKET=$(terraform -chdir=infra/terraform/bootstrap output -raw state_bucket_name)
terraform -chdir=infra/terraform/envs/staging init -backend-config="bucket=$BUCKET"
```

- backend 블록 안에서는 변수를 못 쓴다. 그래서 버킷 이름은 블록에서 빼 두고 init 때 넘긴다(부분 설정). 나머지(경로·리전·암호화·잠금)는 블록에 있다.
- 상태 잠금은 S3 자체 잠금(`use_lockfile`)이라 DynamoDB 표가 필요 없다. 두 사람이 동시에 apply하면 나중 사람이 잠금 오류로 멈춘다.
- prod는 경로만 `envs/prod`로 바꿔 같은 명령을 친다.

## 환경 apply — T1(네트워크·플랫폼)

스테이징에서 먼저 한다. 이 묶음이 만드는 것은 74개다: 네트워크 47개(VPC·서브넷·NAT·라우팅·보안 그룹 7개·규칙 26개), 플랫폼 27개(ECR 3개와 수명 주기, 로그 그룹 3개, 비밀 껍데기 6개, 실행 역할 3개와 정책, CI 역할 2개와 푸시 정책, SNS 주제와 구독, 예산).

> **요금은 NAT 게이트웨이와 그 공인 IP부터 시간 단위로 붙는다.** 나머지(ECR·로그·비밀·SNS)는 저장하는 양만큼이라 거의 안 나온다. 확인을 마치면 바로 `destroy`한다.

1. 개인 값 파일을 만든다. 이메일과 월 예산이 들어가고, `*.auto.tfvars`는 `.gitignore` 대상이라 커밋되지 않는다.

   ```bash
   cp infra/terraform/envs/staging/terraform.auto.tfvars.example infra/terraform/envs/staging/terraform.auto.tfvars
   # alert_email, monthly_budget_usd를 채운다. 예산은 계정 전체 지출 기준이라 두 환경이 같이 떠 있으면 서로의 지출도 센다.
   ```

2. 위 "환경 초기화"의 `init`을 한 번 한다. bootstrap이 먼저 apply돼 있어야 한다(OIDC provider를 주소로 찾는다. 없으면 plan이 여기서 멈춘다).
3. plan을 파일로 저장하고 읽는다. 저장한 계획만 apply하면 읽은 것과 만든 것이 같다.

   ```bash
   terraform -chdir=infra/terraform/envs/staging plan -out=staging.tfplan
   ```

   읽을 것: 추가 74개·변경 0·삭제 0인지, 보안 그룹 규칙에 `0.0.0.0/0`이 인바운드로 없는지(아웃바운드는 `tcp 443` 네 개만).

4. apply한다: `terraform -chdir=infra/terraform/envs/staging apply staging.tfplan`
   - 예산(Budgets) 생성이 `AccessDenied`로 실패하면 로그인한 관리자에게 `budgets:*` 권한이 있는지 본다. 오래된 계정은 root가 "청구 정보에 대한 IAM 사용자 및 역할 액세스"를 켜야 할 수 있다(계정에 따라 달라 이 문서는 확인하지 못했다). 나머지 자원은 이미 만들어졌으니, 권한을 고친 뒤 plan을 다시 떠서 apply한다(상태가 바뀌어 저장한 계획은 더 쓸 수 없다).
5. apply 뒤 확인한다.
   - **알림 메일:** `alert_email`로 SNS 확인 메일이 온다. 링크를 눌러야 구독이 켜진다(예산 알림은 눌러도 안 눌러도 직접 온다).
   - **수렴:** 같은 입력으로 `plan`을 다시 쳐서 `No changes`인지 본다.
   - **인터넷에서 들어오는 길이 없는지:** 아래가 `[]`를 내야 한다. 가짜 provider로는 못 보는, 실제 계정의 상태다.

     ```bash
     SGS=$(aws ec2 describe-security-groups --filters 'Name=group-name,Values=whycode-staging-*' --query 'SecurityGroups[].GroupId' --output text | tr '\t' ',')
     aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$SGS" \
       --query 'SecurityGroupRules[?IsEgress==`false` && (CidrIpv4!=`null` || CidrIpv6!=`null` || PrefixListId!=`null`)]'
     ```

   - **바깥으로 나가는 길이 443뿐인지:** 아래는 `443`이 4개(backend·pipeline-worker·ai-engine·neo4j)여야 한다.

     ```bash
     aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$SGS" \
       --query 'SecurityGroupRules[?IsEgress==`true` && CidrIpv4==`"0.0.0.0/0"`].[GroupId,FromPort,ToPort]' --output text
     ```

   - **ECR:** `aws ecr describe-repositories --query 'repositories[].[repositoryName,imageTagMutability,imageScanningConfiguration.scanOnPush]' --output text`가 서비스 3개에 `IMMUTABLE true`를 낸다.
   - **테스트 태스크(계획서 AWS1 완료 조건):** ECR pull·바깥 호출·보안 그룹 차단을 태스크로 확인하는 일은 ECS 클러스터와 태스크 정의가 생기는 T4에서 한다. T1 apply만으로는 할 수 없다.
6. 비밀 껍데기에는 값이 없다. 값은 앱 묶음(T4)에서 키 목록과 함께 넣는다. 값이 없으면 태스크가 뜨지 않는다.
7. `terraform -chdir=infra/terraform/envs/staging output`의 `ci_push_role_arn`·`ci_deploy_role_arn`은 GitHub 환경(`staging`)에 등록할 값이다(T4).

환경 신뢰 조건: CI 역할은 `se-zero/history-tracker`의 GitHub 환경 `staging`(운영은 `production`)에서 도는 워크플로만 빌릴 수 있다. 환경 이름이 한 글자라도 다르면 아무 워크플로도 빌리지 못한다.

## 지우기

- 환경(`envs/*`)을 먼저 `terraform destroy`하고, bootstrap은 맨 마지막에 지운다. 상태 버킷이 환경들의 상태 파일을 담고 있다.
- 상태 버킷은 `force_destroy`가 꺼져 있다. 객체가 남아 있으면 이전 버전까지 포함해 삭제가 실패한다.
  콘솔에서 버킷을 "비우기"로 비운 뒤 `terraform -chdir=infra/terraform/bootstrap destroy`를 친다.

## 알려진 한계와 후속

코드 리뷰에서 나온 항목 중 지금 고치지 않기로 한 것이다. 각 항목에 언제 다시 볼지 적었다.

| 항목 | 내용 | 다시 볼 때 |
|---|---|---|
| 로그 보존 기간 검증 없음 | `log_retention_days`는 CloudWatch가 받는 값(1·3·5·7·14·30·60·90·…)만 쓸 수 있는데 모듈이 검사하지 않는다. 10 같은 값은 apply에서야 거부된다. 지금 호출부는 기본값 14만 쓴다. | 호출부가 이 값을 바꾸게 될 때 검증을 더한다 |
| 기본 보안 그룹이 남는다 | VPC 기본 보안 그룹은 자기 자신에게서만 받고 바깥으로는 전부 나간다. 규칙 없이 비우는 하드닝은 하지 않았다(네트워크 테스트의 자원 검사가 계약 밖 자원을 막는다). 모든 서비스·EC2에 보안 그룹을 명시하면 열리는 길은 없다. | T4에서 모든 ECS 서비스와 Neo4j EC2의 보안 그룹이 명시됐는지 본다 |
| 비밀 이름 `pipeline-worker`·`ai-engine` | 하이픈 뒤 6글자로 끝나는 이름이다. AWS는 이런 이름을 부분 ARN(끝의 임의 접미사를 뺀 ARN)으로 쓰면 접미사로 오해할 수 있다고 한다. 지금은 IAM 정책에 전체 ARN, CLI에 이름만 써서 문제없다. | T4 태스크 정의에서 ARN을 조립하지 않고 모듈 출력 `secret_arns`를 쓴다 |
| `app_secrets`를 바꿀 때 | 읽을 비밀이 하나도 없는 서비스가 생기면 그 실행 정책의 `Resource`가 빈 목록이라 IAM이 거부한다. 기본값에서는 모든 서비스가 3~4개씩 읽어 해당하지 않는다. | `app_secrets`를 바꾸는 호출이 생길 때 |
| CI 푸시 역할의 권한 | ECR 푸시 5개 동작만 있다. 워크플로가 이미지 존재 확인이나 빌드 캐시를 쓰면 `ecr:BatchGetImage` 등이 더 필요하다. | T4 배포 워크플로를 정할 때 |
| ECR이 환경별이다 | 스테이징을 지우면 이미지도 사라져, 운영에서 스테이징에서 시험한 것과 같은 다이제스트를 쓸 수 없다. | T4에서 같은 커밋으로 다시 빌드할지, ECR을 bootstrap으로 옮길지 정한다 |
| 예산이 계정 전체 기준 | 두 환경이 동시에 떠 있으면 예산이 서로의 지출도 센다. 비용 태그 필터는 청구 콘솔에서 태그를 켜지 않으면 조용히 0원으로 보여 쓰지 않았다. | 두 환경을 동시에 띄울 때 금액을 합산 기준으로 정한다 |
