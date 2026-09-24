import { Link } from "react-router-dom";

import { LEGAL_CONTACT_EMAIL, LEGAL_OPERATOR, LegalNotice, LegalSection } from "@/components/landing/LegalLayout";
import { PRO_MONTHLY_PRICE_KRW } from "@/lib/plans";
import { PATHS } from "@/routes";

// 이용약관 본문(영어) — TermsBodyKo.tsx의 번역. 이 파일의 조항을 고치면 TermsBodyKo.tsx의
// 같은 조항도 함께 고친다. 조항 수(13)·순서·구조(p/ul/ol/strong/Link)는 한국어판과 동일하다
// — 번역만 하고 재구성하지 않았다. Article 6(Paid Subscription) §2의 Paddle 고지는 핸드북
// 원문을 그대로 쓴다(번역하지 않는다).
// Article 10(옛 Article 9) §2에 자기참조("제7조를 위반한 경우" → "violates Article 7")가
// 있다 — 유료 구독(신설, Article 6)이 앞에 끼어들며 이용자의 의무가 6에서 7로 밀렸다.
// 같은 조 §3도 Article 6(유료 구독)을 참조한다.
export function TermsBodyEn() {
  return (
    <>
      <LegalNotice />

      <LegalSection index={1} heading="Purpose">
        <p>
          These Terms set out the conditions and procedures for using the Service, and
          the rights, obligations, and responsibilities of Users and the operator.
        </p>
      </LegalSection>

      <LegalSection index={2} heading="Definitions">
        <ul>
          <li>
            <strong>Service</strong> — the web application that connects records from
            collaboration tools such as GitHub, Jira, and Slack into a knowledge graph,
            and answers natural-language questions together with supporting evidence.
          </li>
          <li>
            <strong>User</strong> — a person who agrees to these Terms and uses the
            Service.
          </li>
          <li>
            <strong>Project</strong> — a unit of work that a User creates within the
            Service, under which data source connections, the knowledge graph, and
            conversations are grouped.
          </li>
          <li>
            <strong>Data Source</strong> — an external service (a GitHub repository, a
            Jira project, a Slack workspace) that a User connects to a Project.
          </li>
          <li>
            <strong>Free</strong> — the default plan used without any payment.
          </li>
          <li>
            <strong>Pro</strong> — the paid plan billed monthly. The scope of use and
            price for each plan are posted on the{" "}
            <Link to={PATHS.pricing}>Pricing</Link> page.
          </li>
        </ul>
      </LegalSection>

      <LegalSection index={3} heading="Account">
        <ol>
          <li>
            The Service supports sign-in only through GitHub social login. Because the
            Service does not maintain a separate password, the security of the User's
            GitHub account remains the User's own responsibility.
          </li>
          <li>
            A User may withdraw at any time from account settings. Withdrawal processing
            and data destruction follow the <Link to={PATHS.privacy}>Privacy Policy</Link>.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={4} heading="Data Source Connections and User Warranties">
        <p>
          Given the nature of the Service, this Article is the most important. The
          Service reads records from the data sources a User connects in order to build
          the graph, and those records include content authored by members other than
          the User.
        </p>
        <ol>
          <li>
            The User warrants that they hold legitimate authority to access the
            repository, project, or workspace they connect.
          </li>
          <li>
            Responsibility for obtaining any approval required under the User's
            organization's policies to bring that data into the Service, and for
            notifying members whose information is included in the records, rests with
            the User who set up the connection and with that organization.
          </li>
          <li>
            The Service uses only the scope of read access requested at the time of
            connection and does not write to or modify data in the external service.
            The User may disconnect a connection at any time.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={5} heading="Provision of the Service">
        <ol>
          <li>
            The Service provides data source connection, knowledge graph construction,
            natural-language question answering, and conversation history management.
          </li>
          <li>
            The Service is provided under two plans, Free and Pro; the scope of use and
            price for each are posted on the <Link to={PATHS.pricing}>Pricing</Link>{" "}
            page. {LEGAL_OPERATOR.en} may decide to add, change, or discontinue features,
            and will give advance notice of any planned discontinuation.
          </li>
          <li>
            The Service may be temporarily suspended for reasons beyond the operator's
            control, such as outages in external APIs, scheduled maintenance, or force
            majeure.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={6} heading="Paid Subscription">
        <ol>
          <li>
            The Pro plan costs ₩{PRO_MONTHLY_PRICE_KRW.toLocaleString("en-US")} per month
            (VAT included), and renews and is charged automatically at each monthly billing
            cycle unless canceled.
          </li>
          <li>
            Our order process is conducted by our online reseller Paddle.com. Paddle.com
            is the Merchant of Record for all our orders. Paddle provides all customer
            service inquiries and handles returns. Billing, receipt issuance, and payment
            method management are all handled through Paddle.
          </li>
          <li>
            A User may cancel the subscription at any time from the subscription
            management screen (the Paddle customer portal).
          </li>
          <li>
            Refunds follow the <Link to={PATHS.refund}>Refund Policy</Link>.
          </li>
          <li>
            Upon the end of a subscription, the account switches to Free. Projects and
            data source connections are kept, automatic sync of new activity
            (incremental sync) stops, Free usage limits apply, and a free query
            allowance is granted anew.
          </li>
          <li>
            If the operator changes the price, it will give notice at least 30 days
            before the change takes effect and obtain the consent of existing
            subscribers. The changed price will not be charged to a subscriber who does not
            consent; that subscription ends without renewal when the period already paid
            for ends.
          </li>
          <li>
            The operator will notify the payment amount and date at least 7 days before
            each scheduled charge.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={7} heading="User Obligations">
        <p>A User must not do any of the following:</p>
        <ul>
          <li>
            Connecting a data source to which the User does not have access, or using
            another person's account without authorization
          </li>
          <li>
            Using information about other members obtained through the Service for
            purposes unrelated to its original purpose, or disclosing it to third
            parties
          </li>
          <li>
            Placing an excessive load on the Service through automated means, or
            reverse-engineering or reproducing it without authorization
          </li>
          <li>Violating applicable laws or infringing the rights of third parties</li>
        </ul>
      </LegalSection>

      <LegalSection index={8} heading="Limitations of AI-Generated Answers">
        <ol>
          <li>
            Answers from the Service are generated by a large language model based on
            the knowledge graph, and may be inaccurate or incomplete.
          </li>
          <li>
            Answers are provided for reference only, and the operator does not warrant
            their accuracy, completeness, or fitness for any particular purpose. Before
            making an important decision, the User must directly verify the original
            records (commits, pull requests, issues, messages) cited in an answer.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={9} heading="Intellectual Property">
        <ol>
          <li>
            Rights to the content of a data source that a User connects remain with its
            original rightsholder. The operator processes that content only to the
            extent necessary to provide the Service.
          </li>
          <li>
            Rights to the Service's software, design, and documentation belong to the
            operator or another legitimate rightsholder, and any portion released as
            open source is governed by its applicable license.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={10} heading="Termination of the Service Agreement">
        <ol>
          <li>
            A User may stop using the Service at any time by disconnecting data sources,
            deleting a project, or withdrawing their membership. Deleting a project also
            deletes that project's conversations, connection information, and knowledge
            graph.
          </li>
          <li>
            If a User violates Article 7, the operator may restrict use or terminate the
            agreement after prior notice. In urgent cases, however, notice may be given
            after the fact.
          </li>
          <li>
            Cancellation and refunds for a paid subscription follow Article 6 and the{" "}
            <Link to={PATHS.refund}>Refund Policy</Link>.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={11} heading="Limitation of Liability">
        <ol>
          <li>
            The operator is not liable for damages arising from use of Free, unless
            caused by the operator's intent or gross negligence.
          </li>
          <li>
            If a Pro subscriber is unable to use the Service due to a cause attributable
            to the operator, the operator is liable as provided by applicable law.
          </li>
          <li>
            Responsibility for any dispute or damage arising from data that a User
            connected without authorization rests with that User.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={12} heading="Amendments to These Terms and Inquiries">
        <ol>
          <li>
            The operator may amend these Terms when necessary, and will announce the
            amended content and effective date within the Service at least 7 days
            before it takes effect (or at least 30 days before, for changes unfavorable
            to Users).
          </li>
          <li>
            If a User continues to use the Service after such notice without objecting
            by the effective date, the User is considered to have agreed to the
            amendment.
          </li>
          <li>
            For questions about these Terms, please contact{" "}
            <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={13} heading="Governing Law and Jurisdiction">
        <p>
          These Terms are governed by the laws of the Republic of Korea, and any dispute
          related to use of the Service shall be brought before the court of competent
          jurisdiction under the Civil Procedure Act.
        </p>
      </LegalSection>
    </>
  );
}
