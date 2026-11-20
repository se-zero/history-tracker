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
| `envs/staging/`, `envs/prod/` | 환경 루트. 지금은 버전·backend·provider 설정만 있고, T1부터 모듈을 연결한다 | 상태 버킷의 `staging/terraform.tfstate`, `prod/terraform.tfstate` |
| `modules/` | T1부터 network·platform·edge·data·app 모듈이 들어온다 | — |
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
- 환경별 apply 순서와 비밀 값 넣는 명령은 T1~T4가 들어오면서 이 문서에 더한다.

## 지우기

- 환경(`envs/*`)을 먼저 `terraform destroy`하고, bootstrap은 맨 마지막에 지운다. 상태 버킷이 환경들의 상태 파일을 담고 있다.
- 상태 버킷은 `force_destroy`가 꺼져 있다. 객체가 남아 있으면 이전 버전까지 포함해 삭제가 실패한다.
  콘솔에서 버킷을 "비우기"로 비운 뒤 `terraform -chdir=infra/terraform/bootstrap destroy`를 친다.
