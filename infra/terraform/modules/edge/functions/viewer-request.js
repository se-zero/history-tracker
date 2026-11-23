// ALB로 가는 동적 동작(/api·/mcp·/oauth2·/.well-known 경로)의 viewer-request.
// modules/edge가 이 파일을 CloudFront Function(cloudfront-js-2.0)으로 올린다. 동작 계약은 viewer-request.test.mjs에 있다.
// var·function만 쓴다 — 런타임이 어디까지 지원하는지 단정하지 못해 테스트가 ES5 꼴로 제한한다.
//
// backend는 Forwarded·X-Forwarded-*를 믿는 설정(forward-headers-strategy: framework)이고, 요청 한도는 클라이언트 IP 헤더로 센다.
// 클라이언트가 이 헤더들을 직접 보내면 ALB를 지나 그대로 backend에 닿으므로, 엣지에서 지우고 클라이언트 IP는 CloudFront가 본 값으로 다시 채운다.

// 클라이언트가 보낸 값을 지우는 헤더. 헤더 키는 CloudFront가 항상 소문자로 주므로 대소문자 변형은 따로 지우지 않는다.
//  - forwarded, x-forwarded-host, x-forwarded-port, x-forwarded-prefix, x-forwarded-ssl: backend가 믿는 헤더라,
//    클라이언트 값이 닿으면 요청의 host·scheme·port·경로 접두사를 위조할 수 있다.
//    CloudFront가 VPC 원본에 고정 Forwarded 값을 붙여 브라우저가 쓴 HTTPS를 backend에 알린다.
//  - true-client-ip, cf-connecting-ip: 클라이언트 IP를 읽는 쪽이 흔히 믿는 이름이다. backend의 IP 헤더 기본값이 CF-Connecting-IP라서,
//    설정을 놓쳐도 위조값이 요청 한도를 피해 가게 두지 않으려고 지운다.
//  - x-whycode-client-ip: 아래에서 viewer.ip로 덮어쓰는 전용 헤더다. 먼저 지워 두어야 viewer.ip가 없는 이벤트에서도 위조값이 남지 않는다.
var STRIPPED_HEADERS = [
  'forwarded',
  'x-forwarded-host',
  'x-forwarded-port',
  'x-forwarded-prefix',
  'x-forwarded-ssl',
  'true-client-ip',
  'cf-connecting-ip',
  'x-whycode-client-ip'
];

// 건드리지 않는 헤더. 읽는 것도 host뿐이다.
//  - host, x-forwarded-proto, x-real-ip: CloudFront 함수가 수정할 수 없는 헤더라 고치려 하면 요청이 502로 끝난다.
//  - x-forwarded-for: 경유지마다 값이 이어붙으며 요청자가 앞부분을 쓸 수 있다. 요청 한도에는 전용 헤더를 쓴다.
function handler(event) {
  var request = event.request;
  var headers = request.headers;
  var host = headers.host ? headers.host.value : '';
  var distributionHost = event.context && event.context.distributionDomainName;

  // Host가 ALB로 전달되므로 현재 표준 배포의 기본 주소만 받는다. Host 헤더 원본은 그대로 둔다.
  if (!distributionHost || host.toLowerCase() !== distributionHost.toLowerCase()) {
    return {
      statusCode: 403,
      statusDescription: 'Forbidden',
      headers: { 'content-type': { value: 'application/json' } },
      body: '{"error":"forbidden_host"}'
    };
  }

  for (var i = 0; i < STRIPPED_HEADERS.length; i++) {
    delete headers[STRIPPED_HEADERS[i]];
  }

  // viewer가 없는 비정상 이벤트는 헤더를 만들지 않는다("undefined" 문자열이 IP로 읽히면 안 된다).
  // 그 요청은 backend가 연결 주소로 센다.
  if (event.viewer && event.viewer.ip) {
    headers['x-whycode-client-ip'] = { value: event.viewer.ip };
  }

  return request;
}
