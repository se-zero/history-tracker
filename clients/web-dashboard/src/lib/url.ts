// 외부에서 들어온 URL 문자열을 <a href>에 그대로 넣기 전의 가드. MCP 클라이언트의 client_uri는
// 클라이언트가 스스로 선언한 값이라(CIMD 문서·DCR 요청) `javascript:` 같은 스킴이 올 수 있고,
// React는 href의 스킴을 막지 않는다 — http(s)만 링크로 그리고 나머지는 텍스트로 떨어뜨린다.
export function isHttpUrl(value: string): boolean {
  try {
    const { protocol } = new URL(value);
    return protocol === "http:" || protocol === "https:";
  } catch {
    return false;
  }
}
