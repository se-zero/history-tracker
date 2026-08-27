import { Link } from "react-router-dom";

import {
  LEGAL_CONTACT_EMAIL,
  LEGAL_CONTACT_URL,
  LegalSection,
  LegalSourceBlock,
  LegalSourceRow,
} from "@/components/landing/LegalLayout";
import { PATHS } from "@/routes";

// 개인정보처리방침 본문(한국어) — 조항 11개(제2조 안에 LegalSourceBlock 9개). 이 파일의
// 조항을 고치면 PrivacyBodyEn.tsx의 같은 조항도 함께 고친다.
//
// 항목·보유기간·위탁처는 실제 구현에서 확인한 값이다(docs/DB.md, services/*/CLAUDE.md):
//   수집 대상  → pipeline-worker source/*의 정규화 코드 (CollectionProvider 등재 provider 전부)
//   자격증명   → provider별 저장 형태가 다르다(Slack·Discord 평문 토큰 / Jira·Google Chat JSON)
//   요청 권한  → backend application.yaml의 slack.user-scopes / atlassian.scopes /
//                discord.scopes·permissions / google-chat.scopes
//   위탁       → ai-engine의 OpenAI 임베딩·질의 모델
//   파기 기한  → backend user-lifecycle.purge.grace-period (P30D)
//   해제 시 삭제 → IntegrationService.disconnect + ai-engine delete_project_source_graph
// 이 중 하나라도 코드가 바뀌면 이 파일과 PrivacyBodyEn.tsx도 함께 고쳐야 한다.
export function PrivacyBodyKo() {
  return (
    <>
      <LegalSection index={1} heading="처리하는 개인정보 항목">
        <p>
          서비스는 크게 네 갈래의 정보를 처리합니다. 이 중 <strong>연동으로 수집되는 기록</strong>은
          이용자 본인 외 팀 구성원의 정보를 포함합니다(제7조 참고).
        </p>
        <div className="lp-legal-table-scroll">
          <table className="lp-legal-table">
            <thead>
              <tr>
                <th>구분</th>
                <th>항목</th>
                <th>수집 방법</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>계정</td>
                <td>GitHub 계정 식별자, 이메일, 표시 이름, 프로필 이미지 주소</td>
                <td>GitHub 로그인 시 자동 수집</td>
              </tr>
              <tr>
                <td>연동 자격증명</td>
                <td>
                  GitHub App 설치 토큰, Slack·ClickUp 액세스 토큰, Jira·Google Chat·Linear·Asana·Notion
                  액세스·리프레시 토큰, Discord 리프레시 토큰
                </td>
                <td>이용자가 각 서비스에서 연동에 동의할 때 발급</td>
              </tr>
              <tr>
                <td>연동으로 수집되는 기록</td>
                <td>
                  <ul>
                    <li>
                      GitHub — 커밋 메시지·작성자 이름·이메일·시각, 변경된 파일 경로와 변경
                      내용(diff), Pull Request와 이슈의 제목·본문·작성자
                    </li>
                    <li>Jira — 이슈의 제목·본문·상태·담당자·변경 시각</li>
                    <li>
                      Slack — 연동한 워크스페이스의 채널 목록, 채널 메시지와 스레드 답글,
                      멤버의 표시 이름·이메일
                    </li>
                    <li>
                      Discord — 연동한 서버의 채널·스레드 목록, 채널 메시지와 스레드 답글,
                      작성자의 표시 이름 (이메일은 수집하지 않습니다)
                    </li>
                    <li>
                      Google Chat — 연동한 스페이스의 메시지와 스레드 답글, 작성자의
                      표시 이름·이메일
                    </li>
                    <li>
                      Notion — 연동한 페이지(선택한 페이지의 하위 페이지 포함)의 제목·본문,
                      작성자·편집자의 표시 이름·이메일
                    </li>
                    <li>Linear — 연동한 팀의 이슈 제목·본문·상태·담당자·작성자</li>
                    <li>
                      Asana — 연동한 프로젝트의 태스크 제목·본문·완료 상태·담당자·작성자
                    </li>
                    <li>
                      ClickUp — 연동한 리스트의 태스크 제목·본문·상태·우선순위·담당자·작성자
                    </li>
                  </ul>
                </td>
                <td>연동 직후 최초 수집 및 이후 증분 수집</td>
              </tr>
              <tr>
                <td>서비스 이용 기록</td>
                <td>
                  프로젝트 이름·설정, 이용자가 입력한 질문과 서비스의 답변, 접속 및 오류 로그
                </td>
                <td>서비스 이용 과정에서 생성</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="lp-legal-note">
          서비스는 주민등록번호·결제 정보 등 민감정보나 고유식별정보를 수집하지 않습니다.
        </p>
      </LegalSection>

      <LegalSection id="sources" index={2} heading="연동하는 서비스별 안내">
        <p>
          연동 시 각 서비스에 아래 범위의 <strong>읽기 권한만</strong> 요청합니다. 서비스는
          외부 서비스에 데이터를 쓰거나 수정하지 않으며, 연동으로 얻은 데이터를{" "}
          <strong>판매하거나 광고에 이용하지 않습니다</strong>.
        </p>

        <LegalSourceBlock id="github" name="GitHub">
          <LegalSourceRow label="요청 권한">
            GitHub App 설치 시 <strong>이용자가 선택한 저장소</strong>에 대한 읽기 권한
            (콘텐츠·이슈·Pull Request·메타데이터). 설치 범위는 GitHub 설정에서 언제든 바꿀 수
            있습니다.
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            커밋(메시지, 작성자 이름·이메일, 시각, 변경된 파일 경로와 변경 내용, 증감 라인 수),
            Pull Request와 이슈(제목, 본문, 작성자, 상태, 시각)
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            코드 변경을 그래프의 중심축으로 삼아 이슈·대화와 연결하고, 변경의 배경을 답변의
            근거로 제시하기 위함입니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 저장된 자격증명과 해당 저장소에서 수집한 그래프 데이터를 삭제합니다.
            GitHub App 설치는 이용자의 계정에 속하고 다른 프로젝트에서도 쓰일 수 있어 그대로
            두므로, 앱 자체를 제거하려면 GitHub 설정에서 해주세요.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="slack" name="Slack">
          <LegalSourceRow label="요청 권한">
            <ul>
              <li>
                <code>channels:read</code> · <code>groups:read</code> — 연동한 워크스페이스의
                채널 목록을 확인해 수집 대상을 정하기 위함
              </li>
              <li>
                <code>channels:history</code> · <code>groups:history</code> — 채널의 메시지와
                스레드 답글을 읽어 의사결정 맥락을 추출하기 위함
              </li>
              <li>
                <code>users:read</code> · <code>users:read.email</code> — 메시지 작성자를
                사람 단위로 식별하고, 이메일로 GitHub 커밋 작성자와 동일인 여부를 판단하기 위함
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            채널 목록(이름·식별자), 채널 메시지와 스레드 답글(본문, 작성자, 시각), 워크스페이스
            멤버의 표시 이름과 이메일
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            대화에 남은 결정의 이유를 커밋·이슈와 연결하기 위함입니다. 이메일은 동일 인물 판단에만
            사용하며, 마케팅 발송이나 외부 제공에 쓰지 않습니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Slack에 액세스 토큰 폐기를 요청해 접근 권한을 끊고, 저장된 토큰과
            해당 워크스페이스에서 수집한 메시지·멤버 데이터를 삭제합니다.
          </LegalSourceRow>
          <LegalSourceRow label="쓰기 권한">
            요청하지 않습니다. 메시지 전송·수정·삭제, 채널 생성 등 워크스페이스를 변경하는
            어떤 동작도 하지 않습니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="jira" name="Jira (Atlassian)">
          <LegalSourceRow label="요청 권한">
            <ul>
              <li>
                <code>read:jira-work</code> — 선택한 프로젝트의 이슈를 읽기 위함
              </li>
              <li>
                <code>read:jira-user</code> — 이슈의 담당자·보고자를 사람 단위로 식별하기 위함
              </li>
              <li>
                <code>offline_access</code> — 이용자가 매번 다시 로그인하지 않아도 증분 수집이
                이어지도록 토큰을 갱신하기 위함
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            선택한 프로젝트의 이슈(제목, 본문, 상태, 담당자, 변경 시각)
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            티켓에 적힌 요구사항·결정을 코드 변경과 연결하기 위함입니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Atlassian에 토큰 폐기를 요청해 접근 권한을 끊고, 저장된 토큰과
            해당 프로젝트에서 수집한 이슈 데이터를 삭제합니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="discord" name="Discord">
          <LegalSourceRow label="요청 권한">
            <ul>
              <li>
                <code>bot</code> — 이용자가 선택한 서버에 봇을 추가하기 위함입니다. 봇에는{" "}
                <strong>채널 보기(View Channels)</strong>와{" "}
                <strong>메시지 기록 읽기(Read Message History)</strong> 두 가지 권한만
                요청합니다.
              </li>
              <li>
                <code>identify</code> — 연동한 이용자를 식별하기 위함이며, 연동을 해제할 때
                발급된 권한을 폐기하는 데에만 사용합니다.
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="수집 주체">
            메시지를 읽는 주체는 <strong>서버에 추가된 봇</strong>이며, 연동할 때 저장한
            이용자의 토큰은 수집에 사용하지 않습니다. 그 토큰은 연동을 해제할 때 발급된
            권한을 폐기하는 용도로만 보관합니다. 따라서 봇이 서버에 있는 동안에는 연동을
            설정한 이용자의 접속 여부와 관계없이 위 권한 범위의 채널이 수집됩니다.
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            연동한 서버의 텍스트·공지 채널 목록과 활성 스레드, 그 안의 메시지(본문, 작성자
            식별자와 표시 이름, 시각). 봇이 보낸 메시지와 입장 알림 같은 시스템 메시지는
            수집하지 않습니다. <strong>이메일은 수집하지 않습니다</strong> — Discord 봇은
            다른 구성원의 이메일에 접근할 수 없어, 동일 인물 판단은 표시 이름에만 의존합니다.
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            대화에 남은 결정의 이유를 커밋·이슈와 연결하기 위함입니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Discord에 발급된 권한의 폐기를 요청하고,{" "}
            <strong>봇이 해당 서버에서 나갑니다</strong>. 저장된 토큰과 그 서버에서 수집한
            메시지 데이터도 함께 삭제합니다.
          </LegalSourceRow>
          <LegalSourceRow label="쓰기 권한">
            요청하지 않습니다. 봇은 메시지를 보내거나 채널·서버 설정을 바꾸지 않으며,
            위의 읽기 권한 두 가지 외에는 아무 권한도 갖지 않습니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="google-chat" name="Google Chat">
          <LegalSourceRow label="요청 권한">
            <ul>
              <li>
                <code>chat.spaces.readonly</code> — 연동할 스페이스를 확인해 수집 대상을
                정하기 위함
              </li>
              <li>
                <code>chat.messages.readonly</code> — 선택한 스페이스의 메시지와 스레드
                답글을 읽어 의사결정 맥락을 추출하기 위함
              </li>
              <li>
                <code>directory.readonly</code> — 메시지 작성자를 사람 단위로 식별하기 위함.
                Google Chat API 응답에는 작성자의 식별자만 오고 이름이 포함되지 않아,{" "}
                <strong>Google People API</strong>로 표시 이름과 이메일을 따로 조회합니다.
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            선택한 스페이스의 메시지와 스레드 답글(본문, 작성자, 시각), 그리고 그 메시지
            작성자의 <strong>표시 이름과 이메일</strong>. 이름·이메일 조회는 수집한 메시지에
            실제로 등장한 사람만 대상으로 하며, 조직 구성원 전체를 내려받지 않습니다.
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            대화에 남은 결정의 이유를 커밋·이슈와 연결하기 위함입니다. 이메일은 동일 인물
            판단에만 사용하며, 마케팅 발송이나 외부 제공에 쓰지 않습니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Google에 토큰 폐기를 요청해 접근 권한을 끊고(리프레시 토큰을
            폐기하면 그로부터 발급된 액세스 토큰도 함께 무효화됩니다), 저장된 토큰과 해당
            스페이스에서 수집한 메시지·작성자 데이터를 삭제합니다.
          </LegalSourceRow>
          <LegalSourceRow label="쓰기 권한">
            요청하지 않습니다. 요청하는 세 가지 권한은 모두 읽기 전용(readonly)입니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="notion" name="Notion">
          <LegalSourceRow label="요청 권한">
            <ul>
              <li>
                <strong>콘텐츠 읽기(Read content)</strong> — 이용자가 연동 시 선택한 페이지와
                블록을 읽기 위함입니다. 페이지를 만들거나 수정하는 권한은 요청하지 않습니다.
              </li>
              <li>
                <strong>이메일을 포함한 사용자 정보 읽기</strong> — 페이지 작성자·편집자를
                사람 단위로 식별하고, 이메일로 GitHub 커밋 작성자와 동일인 여부를 판단하기
                위함입니다.
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="선택 범위">
            Notion 동의 화면에서 이용자가 공유할 페이지를 직접 선택합니다.{" "}
            <strong>선택한 페이지의 하위 페이지도 함께 공유되어 수집 대상에 포함</strong>됩니다
            — 상위 위키 페이지 하나만 선택해도 그 아래 페이지 전체가 수집될 수 있습니다.
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            선택한 페이지(하위 페이지 포함)의 제목과 본문. 페이지의 원본 작성자·편집자
            정보에는 식별자만 담겨 있어, <strong>Notion 사용자 목록 조회 API</strong>로 표시
            이름과 이메일을 별도로 조회합니다. 워크스페이스 전체 구성원이 아니라 조회 시점에
            연결된 워크스페이스의 사용자 목록을 대상으로 하며, 게스트 등 이메일이 없는
            계정은 이름만 저장될 수 있습니다.
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            문서에 남은 설계·결정 배경을 커밋·이슈와 연결하기 위함입니다. 이메일은 동일 인물
            판단에만 사용하며, 마케팅 발송이나 외부 제공에 쓰지 않습니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Notion에 토큰 폐기를 요청해 접근 권한을 끊고, 저장된 토큰과 수집한
            페이지·작성자 데이터를 삭제합니다.
          </LegalSourceRow>
          <LegalSourceRow label="쓰기 권한">
            요청하지 않습니다. 페이지를 만들거나 수정·삭제하는 어떤 동작도 하지 않습니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="linear" name="Linear">
          <LegalSourceRow label="요청 권한">
            <code>read</code> 권한 하나만 요청합니다 — 연동한 팀의 이슈를 읽기 위함이며, 이슈를
            생성·수정하는 쓰기 권한은 요청하지 않습니다.
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            연동한 팀의 이슈(제목, 본문, 상태), 작성자·담당자의 이름과 이메일. 연동 직후 한 차례
            전체 수집하고, 이후에는 GitHub Pull Request가 머지될 때 증분 수집합니다.
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            이슈에 적힌 요구사항·결정을 코드 변경과 연결하기 위함입니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Linear에 토큰 폐기를 요청해 접근 권한을 끊고, 저장된 토큰과 해당
            팀에서 수집한 이슈 데이터를 삭제합니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="asana" name="Asana">
          <LegalSourceRow label="요청 권한">
            <ul>
              <li>
                <code>workspaces:read</code> — 연동 가능한 워크스페이스 목록을 확인하기 위함
              </li>
              <li>
                <code>projects:read</code> — 워크스페이스의 프로젝트 목록을 확인해 수집 대상을
                정하기 위함
              </li>
              <li>
                <code>tasks:read</code> — 연동한 프로젝트의 태스크를 읽기 위함
              </li>
              <li>
                <code>users:read</code> — 태스크의 작성자·담당자를 사람 단위로 식별하기 위함
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            연동한 프로젝트의 태스크(제목, 본문, 완료 상태, 시각), 작성자·담당자의 이름과 이메일.
            연동 직후 한 차례 전체 수집하고, 이후에는 GitHub Pull Request가 머지될 때 증분 수집합니다.
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            태스크에 적힌 요구사항·결정을 코드 변경과 연결하기 위함입니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 Asana에 토큰 폐기를 요청해 접근 권한을 끊고, 저장된 토큰과 해당
            프로젝트에서 수집한 태스크 데이터를 삭제합니다.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="clickup" name="ClickUp">
          <LegalSourceRow label="요청 권한">
            ClickUp은 세분화된 권한 범위(scope) 개념을 제공하지 않습니다. 동의하면 승인한
            워크스페이스 전체에 대한 API 접근 권한이 앱에 부여되며, 서비스는 그중 이용자가
            선택한 리스트의 태스크만 실제로 수집합니다.
          </LegalSourceRow>
          <LegalSourceRow label="수집하는 정보">
            선택한 리스트의 태스크(제목, 본문, 상태, 우선순위, 생성·완료 시각, 부모 태스크
            관계), 작성자·담당자의 이름과 이메일
          </LegalSourceRow>
          <LegalSourceRow label="이용 목적">
            태스크에 적힌 요구사항·결정을 코드 변경과 연결하기 위함입니다.
          </LegalSourceRow>
          <LegalSourceRow label="삭제">
            연동을 해제하면 저장된 자격증명과 해당 리스트에서 수집한 그래프 데이터를
            삭제합니다. 다만 ClickUp은 원격으로 토큰을 폐기하는 API를 제공하지 않아, 앱 자체의
            접근 권한을 끊으려면 ClickUp 설정(Apps)에서 직접 연동을 해제해야 합니다.
          </LegalSourceRow>
        </LegalSourceBlock>
      </LegalSection>

      <LegalSection index={3} heading="처리 목적">
        <ul>
          <li>회원 식별과 로그인 유지</li>
          <li>연동한 데이터 소스에서 기록을 수집해 지식 그래프를 구축하고 갱신</li>
          <li>이용자의 자연어 질문에 근거를 포함한 답변 생성</li>
          <li>동일 인물 판단 등 그래프 품질 개선, 오류 분석과 서비스 안정성 확보</li>
        </ul>
      </LegalSection>

      <LegalSection index={4} heading="처리 위탁과 국외 이전">
        <p>
          서비스는 답변 생성과 의미 검색을 위해 아래 사업자에 처리를 위탁합니다. 이용자의
          질문과 그래프에 저장된 기록의 일부(제목·본문·요약 등)가 이 과정에서 전송됩니다.
        </p>
        <div className="lp-legal-table-scroll">
          <table className="lp-legal-table">
            <thead>
              <tr>
                <th>위탁받는 자</th>
                <th>위탁 업무</th>
                <th>이전 국가</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>OpenAI, L.L.C.</td>
                <td>텍스트 임베딩 생성, 자연어 질의응답 및 요약 처리</td>
                <td>미국</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          이 외에 이용자의 개인정보를 제3자에게 제공하지 않으며, 어떤 경우에도{" "}
          <strong>판매하거나 광고·마케팅 목적으로 이용하지 않습니다</strong>. 다만 법령에
          근거한 수사기관의 적법한 요청이 있는 경우는 예외로 합니다.
        </p>
      </LegalSection>

      <LegalSection index={5} heading="보유 기간과 파기">
        <ol>
          <li>
            <strong>연동 해제</strong> — 연동을 해제하면 해당 서비스에 접근 권한 폐기를 요청한
            뒤, 저장된 자격증명과 그 서비스에서 수집한 데이터(지식 그래프의 해당 노드 포함)를
            지체 없이 삭제합니다. 다른 서비스의 연동과 대화 기록은 유지됩니다.
          </li>
          <li>
            <strong>프로젝트 삭제</strong> — 프로젝트를 삭제하면 그 프로젝트의 연동 정보,
            대화 기록, 수집된 기록과 지식 그래프를 함께 삭제합니다.
          </li>
          <li>
            <strong>회원 탈퇴</strong> — 탈퇴 즉시 계정을 비활성화해 서비스 이용을 중단시키고,
            30일이 지나면 계정과 관련 데이터를 삭제합니다. 이 30일은 착오 탈퇴를
            되돌리기 위한 기간입니다. 이때 연동한 외부 서비스의 접근 권한도 함께 회수하고,
            수집된 기록과 지식 그래프를 삭제합니다.
            <br />
            다만 <strong>GitHub App 설치 정보는 남습니다</strong> — 설치는 개인이 아니라 계정
            단위이고, 같은 조직의 다른 이용자가 계속 쓰고 있을 수 있기 때문입니다. 이 기록에는
            GitHub 계정명과 암호화된 접근 토큰이 포함되며, 이용자와의 연결(누가 접근할 수 있는지)은
            탈퇴 시 함께 삭제됩니다. 이 기록의 삭제를 원하시면 아래 문의처로 요청해 주세요.
          </li>
          <li>
            법령이 보존을 요구하는 기록은 해당 법령이 정한 기간 동안 분리 보관한 뒤
            파기합니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={6} heading="안전성 확보 조치">
        <ul>
          <li>
            외부 서비스 자격증명은 평문으로 저장하지 않고 AES-256-GCM으로 암호화해
            보관합니다.
          </li>
          <li>로그인 갱신 토큰은 원본 대신 해시 값으로만 저장합니다.</li>
          <li>
            연동 시 필요한 최소한의 읽기 권한만 요청하며, 서비스 간 내부 호출은 별도의
            인증 토큰으로 제한합니다.
          </li>
          <li>개인정보 처리 시스템에 대한 접근 권한을 담당자에게 최소한으로 부여합니다.</li>
        </ul>
      </LegalSection>

      <LegalSection index={7} heading="이용자 외 구성원의 정보">
        <p>
          서비스가 수집하는 기록에는 커밋 작성자, 이슈 담당자, 대화 메시지 작성자 등 연동을
          설정한 이용자 본인이 아닌 사람의 이름·이메일·작성 내용이 포함됩니다. 이 정보는
          이용자가 소속 조직의 협업 도구를 연결함으로써 서비스에 반입되므로, 조직 내부의
          고지·동의 절차를 지킬 책임은 연동을 설정한 이용자와 그 조직에 있습니다(
          <Link to={PATHS.terms}>이용약관</Link> 제4조).
        </p>
        <p>
          기록에 정보가 포함된 구성원은 아래 문의처로 열람·삭제를 요청할 수 있으며, 해당
          프로젝트의 관리자를 통해 연동 해제나 프로젝트 삭제를 요청할 수도 있습니다.
        </p>
      </LegalSection>

      <LegalSection index={8} heading="이용자의 권리와 행사 방법">
        <p>
          이용자는 언제든지 자신의 개인정보에 대해 열람·정정·삭제·처리정지를 요구할 수
          있습니다. 서비스 화면에서 직접 할 수 있는 것은 다음과 같습니다.
        </p>
        <ul>
          <li>데이터 소스 화면 — 연동 연결·해제</li>
          <li>프로젝트 설정 — 프로젝트 및 그에 속한 모든 데이터 삭제</li>
          <li>계정 설정 — 회원 탈퇴</li>
        </ul>
        <p>그 밖의 요청은 제10조의 문의처로 접수해 주세요.</p>
      </LegalSection>

      <LegalSection index={9} heading="브라우저에 저장되는 정보">
        <p>
          서비스는 광고·분석 목적의 쿠키를 사용하지 않습니다. 로그인 상태를 유지하기 위해
          인증 토큰을 브라우저의 로컬 저장소에 보관하며, 로그아웃 시 삭제됩니다.
        </p>
        <p>
          이 외에 화면 표시 설정 — 앱 테마(<code>ht.theme</code>), 소개 페이지의 언어(
          <code>ht.lang</code>)와 테마(<code>ht.lp-theme</code>) — 를 이용자가 직접 선택한
          경우에만 로컬 저장소에 보관합니다. 이 값들은 개인정보가 아닌 기기별 편의 설정으로
          로그아웃과 무관하게 유지되며, 브라우저의 사이트 데이터 삭제로 언제든 지울 수
          있습니다.
        </p>
      </LegalSection>

      <LegalSection index={10} heading="문의처">
        <p>
          개인정보 처리에 관한 문의·불만·피해 구제는 아래로 접수해 주세요. 접수 후 지체 없이
          답변하겠습니다.
        </p>
        <ul>
          <li>
            이메일 —{" "}
            <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>
          </li>
          <li>
            이슈 트래커 —{" "}
            <a href={LEGAL_CONTACT_URL} target="_blank" rel="noreferrer">
              {LEGAL_CONTACT_URL}
            </a>
          </li>
        </ul>
        <p className="lp-legal-note">
          개인정보 침해에 대한 신고·상담은 개인정보침해신고센터(privacy.kisa.or.kr, 국번 없이
          118), 대검찰청 사이버수사과(spo.go.kr, 1301), 경찰청 사이버수사국(ecrm.police.go.kr,
          182)에도 하실 수 있습니다.
        </p>
      </LegalSection>

      <LegalSection index={11} heading="방침의 변경">
        <p>
          이 방침을 변경할 때는 변경 내용과 시행일을 시행 7일 전까지 서비스 내에 공지합니다.
          이용자의 권리에 중대한 영향을 주는 변경은 30일 전에 공지합니다.
        </p>
      </LegalSection>
    </>
  );
}
