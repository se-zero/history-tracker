// 웹 정적 동작(S3 오리진)의 viewer-request. React Router 라우트를 /index.html로 돌린다.
// modules/edge가 이 파일을 CloudFront Function(cloudfront-js-2.0)으로 올린다. 동작 계약은 spa-rewrite.test.mjs에 있다.
// var·function만 쓴다 — 런타임이 어디까지 지원하는지 단정하지 못해 테스트가 ES5 꼴로 제한한다.
//
// /auth/callback·/mcp/setup·/projects/123 같은 라우트는 S3에 같은 이름의 파일이 없어서, 그대로 두면 S3가 403을 돌려준다.
// 운영 nginx의 try_files $uri $uri/ /index.html과 같은 동작을 엣지에서 한다.
//
// 판정은 uri의 끝이 확장자(점 + 영숫자)인지로 한다. 확장자가 있으면 실제 파일(에셋·robots.txt 등)이라 그대로 두고, 없으면 라우트로 본다.
// 쿼리스트링은 request.uri가 아니라 request.querystring에 따로 있어서 판정에 끼지 않는다.
// OAuth 콜백(/auth/callback?code=...)의 code 값이 어떤 모양이어도 라우트로 판정된다.
//
// 라우트 허용 목록(/auth, /projects 등만 index로 보내는 방식)을 쓰지 않은 이유: 목록은 프론트에 라우트를 추가할 때마다 Terraform도
// 같이 고쳐야 하고, 빠뜨리면 그 라우트로 새로고침하거나 직접 들어갔을 때 403이 난다.
// 대가로 점이 든 마지막 조각(/users/john.doe)은 파일로 오인되어 index로 가지 않는다. 라우트에는 그런 이름을 만들지 않는다.
var FILE_EXTENSION = /\.[A-Za-z0-9]+$/;

function handler(event) {
  var request = event.request;

  if (!FILE_EXTENSION.test(request.uri)) {
    request.uri = '/index.html';
  }

  return request;
}
