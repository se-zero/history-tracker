// 문제 정의 섹션 — 이미지·아이콘 없이 타이포그래피만으로 구성한다.
// "git blame"만 모노스페이스로 도드라져 보이게 해, DESIGN.md의 "기술 토큰은 모노" 규칙을
// 헤드라인 안에서 직접 보여준다.
export function ProblemSection() {
  return (
    <section className="lp-problem">
      <div className="lp-problem-inner">
        <h2 className="lp-problem-headline">
          <span className="lp-problem-line">
            <span className="lp-problem-mono">git blame</span>은 누가 바꿨는지 알려준다.
          </span>
          <span className="lp-problem-line">왜 바꿨는지는 아무도 모른다.</span>
        </h2>
        <p className="lp-problem-body">
          <span className="lp-problem-body-line">
            결정은 Slack에, 근거는 티켓에, 결과만 코드에 남는다.
          </span>
          <span className="lp-problem-body-line">
            셋 다 검색되지만, 그 사이의 관계는 어디에도 저장되지 않는다.
          </span>
        </p>
      </div>
    </section>
  );
}
