import { Link } from "react-router-dom";

import {
  LEGAL_CONTACT_EMAIL,
  LEGAL_CONTACT_URL,
  LegalNotice,
  LegalSection,
  LegalSourceBlock,
  LegalSourceRow,
} from "@/components/landing/LegalLayout";
import { PATHS } from "@/routes";

// 개인정보처리방침 본문(영어) — PrivacyBodyKo.tsx의 번역. 이 파일의 조항을 고치면
// PrivacyBodyKo.tsx의 같은 조항도 함께 고친다. 조항 수(11)·순서·구조(p/ul/ol/table/Link/
// mailto)는 한국어판과 동일하다 — 번역만 하고 재구성하지 않았다.
//
// 앵커 id는 절대 불변이다 — 제2조 id="sources"와 소스 블록 9개(#github·#slack·#jira·
// #discord·#google-chat·#notion·#linear·#asana·#clickup)는 Ko와 같은 위치·같은 id를 쓴다.
// 앞의 셋(github·slack·jira)은 외부 앱 심사에 제출된 URL이라 바꾸면 제출된 링크가 깨진다
// (PrivacyBodyKo.tsx 상단 주석 참고). LegalSourceRow의 label(Requested scopes/Data
// collected/Purpose/Deletion 등)은 이 파일에서 영어 리터럴로 직접 쓰고, 같은 라벨은 전
// 블록에서 동일 문자열로 통일한다.
export function PrivacyBodyEn() {
  return (
    <>
      <LegalNotice />

      <LegalSection index={1} heading="Categories of Personal Information Processed">
        <p>
          The Service processes four broad categories of information. Of these,{" "}
          <strong>records collected through connections</strong> include information
          about team members other than the User (see Article 7).
        </p>
        <div className="lp-legal-table-scroll">
          <table className="lp-legal-table">
            <thead>
              <tr>
                <th>Category</th>
                <th>Items</th>
                <th>Collection method</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Account</td>
                <td>GitHub account identifier, email, display name, profile image URL</td>
                <td>Collected automatically on GitHub sign-in</td>
              </tr>
              <tr>
                <td>Connection credentials</td>
                <td>
                  GitHub App installation token, Slack and ClickUp access tokens,
                  Jira, Google Chat, Linear, Asana, and Notion access and refresh
                  tokens, Discord refresh token
                </td>
                <td>
                  Issued when the User consents to a connection with each service. For Slack,
                  there is also a path where the User pastes a User OAuth Token from an app
                  they created in their own workspace. Other sources store only credentials
                  issued through consent.
                </td>
              </tr>
              <tr>
                <td>Records collected through connections</td>
                <td>
                  <ul>
                    <li>
                      GitHub — commit messages, author name, email, and timestamp,
                      changed file paths and diffs, and the titles, bodies, and
                      authors of pull requests and issues
                    </li>
                    <li>Jira — issue title, body, status, assignee, and update time</li>
                    <li>
                      Slack — the channel list of the connected workspace, channel
                      messages and thread replies, and members' display names and
                      emails
                    </li>
                    <li>
                      Discord — the channel and thread list of the connected server,
                      channel messages and thread replies, and authors' display
                      names (email is not collected)
                    </li>
                    <li>
                      Google Chat — messages and thread replies from the connected
                      space, and authors' display names and emails
                    </li>
                    <li>
                      Notion — the title and body of connected pages (including
                      sub-pages of a selected page), and authors' and editors'
                      display names and emails
                    </li>
                    <li>
                      Linear — the title, body, status, assignee, and author of
                      issues in the connected team
                    </li>
                    <li>
                      Asana — the title, body, completion status, assignee, and
                      author of tasks in the connected project
                    </li>
                    <li>
                      ClickUp — the title, body, status, priority, assignee, and
                      author of tasks in the connected list
                    </li>
                  </ul>
                </td>
                <td>Collected on initial connection and incrementally thereafter</td>
              </tr>
              <tr>
                <td>Service usage records</td>
                <td>
                  Project names and settings, questions entered by the User and the
                  Service's answers, and access and error logs
                </td>
                <td>Generated in the course of using the Service</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="lp-legal-note">
          The Service does not collect sensitive information or unique identifying
          information such as resident registration numbers or payment details.
        </p>
      </LegalSection>

      <LegalSection id="sources" index={2} heading="Notice by Connected Service">
        <p>
          For each connected service, the Service requests only the{" "}
          <strong>read-only scopes</strong> listed below. The Service does not write
          to or modify data in any external service, and does not{" "}
          <strong>sell data obtained through a connection or use it for
          advertising</strong>.
        </p>

        <LegalSourceBlock id="github" name="GitHub">
          <LegalSourceRow label="Requested scopes">
            On GitHub App installation, read access to{" "}
            <strong>the repositories the User selects</strong> (contents, issues,
            pull requests, metadata). The installation scope can be changed at any
            time from GitHub settings.
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            Commits (message, author name and email, timestamp, changed file paths
            and diffs, added/removed line counts), and pull requests and issues
            (title, body, author, status, timestamps)
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            Code changes are used as the central axis of the graph, linked to
            issues and conversations, so the background of a change can be cited
            as evidence in answers.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting deletes the stored credential and the graph data
            collected from that repository. Because the GitHub App installation
            belongs to the User's account and may be used by other projects, it is
            left in place — to remove the app itself, do so from GitHub settings.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="slack" name="Slack">
          <LegalSourceRow label="Requested scopes">
            <ul>
              <li>
                <code>channels:read</code> · <code>groups:read</code> — to list the
                channels of the connected workspace and determine what to collect
              </li>
              <li>
                <code>channels:history</code> · <code>groups:history</code> — to
                read channel messages and thread replies and extract decision
                context
              </li>
              <li>
                <code>users:read</code> · <code>users:read.email</code> — to
                identify message authors as individual people, and to use email to
                determine whether they are the same person as a GitHub commit
                author
              </li>
              <li>
                <code>commands</code> — so the User can ask questions with the{" "}
                <code>/why-code</code> slash command in the connected workspace
                (bot)
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            The channel list (name and identifier), channel messages and thread
            replies (body, author, timestamp), and workspace members' display
            names and emails
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the reasons behind decisions left in conversations to commits
            and issues. Email is used only to determine whether people are the
            same individual, and is never used for marketing or disclosed to
            outside parties.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting deletes the stored credential and the message and member
            data collected from that workspace. For a connection made by consenting
            to our app (OAuth), we also request that Slack revoke the access token,
            cutting off access. For a connection made by pasting a token from the
            User's own app (BYO), we do not request remote revocation from Slack;
            remove that app from Slack settings.
          </LegalSourceRow>
          <LegalSourceRow label="Write access">
            None is requested. The Service does not send, edit, or delete
            messages, create channels, or perform any action that changes the
            workspace.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="jira" name="Jira (Atlassian)">
          <LegalSourceRow label="Requested scopes">
            <ul>
              <li>
                <code>read:jira-work</code> — to read issues in the selected
                project
              </li>
              <li>
                <code>read:jira-user</code> — to identify an issue's assignee and
                reporter as individual people
              </li>
              <li>
                <code>offline_access</code> — to refresh the token so incremental
                collection continues without the User having to sign in again each
                time
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            Issues in the selected project (title, body, status, assignee, update
            time)
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the requirements and decisions recorded in tickets to code
            changes.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting requests that Atlassian revoke the token, cutting off
            access, and deletes the stored token and the issue data collected from
            that project.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="discord" name="Discord">
          <LegalSourceRow label="Requested scopes">
            <ul>
              <li>
                <code>bot</code> — to add a bot to the server the User selects. The
                bot requests only two permissions:{" "}
                <strong>View Channels</strong> and{" "}
                <strong>Read Message History</strong>.
              </li>
              <li>
                <code>identify</code> — to identify the connecting User, used only
                to revoke the granted permission when the connection is disabled.
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="Who collects the data">
            Messages are read by <strong>the bot added to the server</strong>; the
            User's token stored at connection time is not used for collection. That
            token is kept only to revoke the granted permission when the
            connection is disabled. As a result, while the bot remains in the
            server, channels within the scope above are collected regardless of
            whether the User who set up the connection is online.
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            The list of text and announcement channels and active threads in the
            connected server, and the messages within them (body, author
            identifier and display name, timestamp). Messages sent by the bot and
            system messages such as join notices are not collected.{" "}
            <strong>Email is not collected</strong> — the Discord bot cannot access
            other members' emails, so determining whether people are the same
            individual relies on display name alone.
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the reasons behind decisions left in conversations to commits
            and issues.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting requests that Discord revoke the granted permission, and{" "}
            <strong>the bot leaves that server</strong>. The stored token and the
            message data collected from that server are deleted as well.
          </LegalSourceRow>
          <LegalSourceRow label="Write access">
            None is requested. The bot does not send messages or change channel or
            server settings, and holds no permission beyond the two read
            permissions above.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="google-chat" name="Google Chat">
          <LegalSourceRow label="Requested scopes">
            <ul>
              <li>
                <code>chat.spaces.readonly</code> — to list spaces available for
                connection and determine what to collect
              </li>
              <li>
                <code>chat.messages.readonly</code> — to read messages and thread
                replies in the selected space and extract decision context
              </li>
              <li>
                <code>directory.readonly</code> — to identify message authors as
                individual people. Google Chat API responses include only an
                author's identifier, not their name, so the{" "}
                <strong>Google People API</strong> is used to look up display name
                and email separately.
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            Messages and thread replies in the selected space (body, author,
            timestamp), and the <strong>display name and email</strong> of those
            message authors. Name and email lookups target only people who
            actually appear in collected messages, not the organization's entire
            directory.
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the reasons behind decisions left in conversations to commits
            and issues. Email is used only to determine whether people are the
            same individual, and is never used for marketing or disclosed to
            outside parties.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting requests that Google revoke the token, cutting off
            access (revoking the refresh token also invalidates any access token
            issued from it), and deletes the stored token and the message and
            author data collected from that space.
          </LegalSourceRow>
          <LegalSourceRow label="Write access">
            None is requested. All three requested scopes are read-only.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="notion" name="Notion">
          <LegalSourceRow label="Requested scopes">
            <ul>
              <li>
                <strong>Read content</strong> — to read the pages and blocks the
                User selects when connecting. Permission to create or edit pages
                is not requested.
              </li>
              <li>
                <strong>Read user information including email</strong> — to
                identify page authors and editors as individual people, and to use
                email to determine whether they are the same person as a GitHub
                commit author.
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="Selection scope">
            The User selects which pages to share directly on Notion's consent
            screen. <strong>Sub-pages of a selected page are shared and included
            in collection as well</strong> — selecting a single top-level wiki page
            can bring every page beneath it into scope.
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            The title and body of selected pages (including sub-pages). Because a
            page's original author and editor information contains only an
            identifier, the <strong>Notion user list API</strong> is used to look
            up display name and email separately. This targets the user list of
            the workspace connected at lookup time, not the entire workspace
            membership, and guest accounts without an email may have only a name
            stored.
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the design and decision background left in documents to
            commits and issues. Email is used only to determine whether people are
            the same individual, and is never used for marketing or disclosed to
            outside parties.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting requests that Notion revoke the token, cutting off
            access, and deletes the stored token and the collected page and author
            data.
          </LegalSourceRow>
          <LegalSourceRow label="Write access">
            None is requested. The Service does not create, edit, or delete pages
            in any way.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="linear" name="Linear">
          <LegalSourceRow label="Requested scopes">
            Only the <code>read</code> scope is requested — to read issues in the
            connected team. Write access to create or edit issues is not
            requested.
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            Issues in the connected team (title, body, status), and the name and
            email of their authors and assignees. A full collection runs once
            right after connecting, and incremental collection runs thereafter
            whenever a GitHub pull request is merged.
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the requirements and decisions recorded in issues to code
            changes.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting requests that Linear revoke the token, cutting off
            access, and deletes the stored token and the issue data collected from
            that team.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="asana" name="Asana">
          <LegalSourceRow label="Requested scopes">
            <ul>
              <li>
                <code>workspaces:read</code> — to list the workspaces available for
                connection
              </li>
              <li>
                <code>projects:read</code> — to list a workspace's projects and
                determine what to collect
              </li>
              <li>
                <code>tasks:read</code> — to read tasks in the connected project
              </li>
              <li>
                <code>users:read</code> — to identify a task's author and assignee
                as individual people
              </li>
            </ul>
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            Tasks in the connected project (title, body, completion status,
            timestamps), and the name and email of their authors and assignees. A
            full collection runs once right after connecting, and incremental
            collection runs thereafter whenever a GitHub pull request is merged.
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the requirements and decisions recorded in tasks to code
            changes.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting requests that Asana revoke the token, cutting off
            access, and deletes the stored token and the task data collected from
            that project.
          </LegalSourceRow>
        </LegalSourceBlock>

        <LegalSourceBlock id="clickup" name="ClickUp">
          <LegalSourceRow label="Requested scopes">
            ClickUp does not offer fine-grained permission scopes. Consenting
            grants the app API access to the entire approved workspace, and the
            Service actually collects only the tasks in the lists the User
            selects.
          </LegalSourceRow>
          <LegalSourceRow label="Data collected">
            Tasks in the selected lists (title, body, status, priority, creation
            and completion timestamps, parent-task relationships), and the name
            and email of their authors and assignees
          </LegalSourceRow>
          <LegalSourceRow label="Purpose">
            To link the requirements and decisions recorded in tasks to code
            changes.
          </LegalSourceRow>
          <LegalSourceRow label="Deletion">
            Disconnecting deletes the stored credential and the graph data
            collected from that list. However, because ClickUp does not provide an
            API to remotely revoke a token, removing the app's access requires
            disconnecting it directly from ClickUp settings (Apps).
          </LegalSourceRow>
        </LegalSourceBlock>
      </LegalSection>

      <LegalSection index={3} heading="Purposes of Processing">
        <ul>
          <li>Member identification and maintaining sign-in sessions</li>
          <li>
            Collecting records from connected data sources to build and update the
            knowledge graph
          </li>
          <li>
            Generating answers, together with supporting evidence, to the User's
            natural-language questions
          </li>
          <li>
            Improving graph quality (such as determining whether people are the
            same individual), error analysis, and maintaining service stability
          </li>
        </ul>
      </LegalSection>

      <LegalSection index={4} heading="Outsourcing of Processing and Cross-Border Transfer">
        <p>
          The Service outsources processing to the provider below for answer
          generation and semantic search. A User's questions and part of the
          records stored in the graph (titles, bodies, summaries, and the like)
          are transmitted in this process.
        </p>
        <div className="lp-legal-table-scroll">
          <table className="lp-legal-table">
            <thead>
              <tr>
                <th>Recipient</th>
                <th>Outsourced task</th>
                <th>Destination country</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>OpenAI, L.L.C.</td>
                <td>Generating text embeddings, and natural-language question
                  answering and summarization</td>
                <td>United States</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          Other than as described above, the Service does not provide a User's
          personal information to third parties, and under no circumstances does
          it <strong>sell such information or use it for advertising or
          marketing</strong>. An exception applies where a law enforcement agency
          makes a lawful request based on statute.
        </p>
      </LegalSection>

      <LegalSection index={5} heading="Retention Period and Destruction">
        <ol>
          <li>
            <strong>Disconnection</strong> — Disconnecting a data source promptly
            deletes the stored credential and the data collected from that service
            (including the corresponding nodes in the knowledge graph). Whether we
            also request that the corresponding service revoke access follows the
            per-source deletion rules in Article 2 — some sources (Slack connected
            through our app, and others) do, and some (Slack connected by pasting a
            customer-app token, ClickUp, and others) do not. Other connections and
            conversation history remain unaffected.
          </li>
          <li>
            <strong>Project deletion</strong> — Deleting a project deletes that
            project's connection information, conversation history, collected
            records, and knowledge graph together.
          </li>
          <li>
            <strong>Membership withdrawal</strong> — Withdrawing immediately
            deactivates the account and stops use of the Service, and after 30
            days deletes the account and related data. This 30-day period exists
            to allow a mistaken withdrawal to be undone. At that point we also
            revoke the access granted to connected external services and delete
            the collected records and knowledge graph. If revoking access is
            delayed for reasons on the external service's side, we retry for up
            to 7 more days, after which we delete the data regardless of whether
            the access was successfully revoked (within 37 days of withdrawal
            at the latest).
            <br />
            <strong>GitHub App installation records are retained</strong>,
            however — an installation belongs to an account rather than to an
            individual, and other users in the same organization may still rely
            on it. Such a record contains the GitHub account name and an
            encrypted access token; the link between you and the installation
            (that is, who may access it) is deleted when you withdraw. If you
            want the record itself removed, please contact us at the address below.
          </li>
          <li>
            Records that a law requires to be retained are kept separately for
            the period the law specifies before being destroyed.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={6} heading="Security Measures">
        <ul>
          <li>
            Credentials for external services are never stored in plain text and
            are encrypted with AES-256-GCM.
          </li>
          <li>
            Sign-in refresh tokens are stored only as hash values, not in their
            original form.
          </li>
          <li>
            Only the minimum read access necessary is requested when connecting a
            service, and internal calls between services are restricted with a
            separate authentication token.
          </li>
          <li>
            Access to personal-information processing systems is granted to staff
            on a minimum-necessary basis.
          </li>
        </ul>
      </LegalSection>

      <LegalSection index={7} heading="Information About Members Other Than the User">
        <p>
          Records the Service collects include the names, emails, and authored
          content of people other than the User who set up a connection — such as
          commit authors, issue assignees, and conversation message authors. This
          information is brought into the Service when the User connects their
          organization's collaboration tools, so responsibility for following the
          organization's internal notice and consent procedures rests with the
          User who set up the connection and with that organization (see the{" "}
          <Link to={PATHS.terms}>Terms of Service</Link>, Article 4).
        </p>
        <p>
          A member whose information appears in a record may request access to or
          deletion of it through the contact information below, and may also
          request that the relevant project's administrator disconnect a data
          source or delete the project.
        </p>
      </LegalSection>

      <LegalSection index={8} heading="User Rights and How to Exercise Them">
        <p>
          A User may at any time request access to, correction of, deletion of,
          or suspension of processing of their personal information. The
          following can be done directly from the Service:
        </p>
        <ul>
          <li>Data Sources screen — connecting or disconnecting a data source</li>
          <li>
            Project settings — deleting a project and all data belonging to it
          </li>
          <li>Account settings — withdrawing membership</li>
        </ul>
        <p>For any other request, please contact us using the information in Article 10.</p>
      </LegalSection>

      <LegalSection index={9} heading="Information Stored in the Browser">
        <p>
          The Service does not use cookies for advertising or analytics purposes.
          To keep a User signed in, a refresh token is stored in an httpOnly
          cookie that scripts cannot read, and is deleted on sign-out. The
          short-lived access token is kept in memory only and is not written to
          storage.
        </p>
        <p>
          Beyond this, display preferences — the app theme (<code>ht.theme</code>) and
          the public pages' language (<code>ht.lang</code>) and theme (
          <code>ht.lp-theme</code>) — are stored in local storage only when a User
          selects them explicitly. These values are device-local convenience settings,
          not personal information; they persist independently of sign-out and can be
          removed at any time by clearing the browser's site data.
        </p>
      </LegalSection>

      <LegalSection index={10} heading="Contact">
        <p>
          Please direct any inquiry, complaint, or request for relief regarding
          the processing of personal information to the contacts below. We will
          respond without delay after receiving it.
        </p>
        <ul>
          <li>
            Email —{" "}
            <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>
          </li>
          <li>
            Issue tracker —{" "}
            <a href={LEGAL_CONTACT_URL} target="_blank" rel="noreferrer">
              {LEGAL_CONTACT_URL}
            </a>
          </li>
        </ul>
        <p className="lp-legal-note">
          Reports of and inquiries about personal-information infringement in
          Korea may also be filed with the Personal Information Infringement
          Report Center (privacy.kisa.or.kr, 118 without an area code), the
          Supreme Prosecutors' Office Cyber Investigation Division
          (spo.go.kr, 1301), or the Korean National Police Agency Cyber
          Investigation Bureau (ecrm.police.go.kr, 182).
        </p>
      </LegalSection>

      <LegalSection index={11} heading="Changes to This Policy">
        <p>
          Changes to this Policy, together with their effective date, will be
          announced within the Service at least 7 days before they take effect.
          Changes that materially affect User rights will be announced at least
          30 days in advance.
        </p>
      </LegalSection>
    </>
  );
}
