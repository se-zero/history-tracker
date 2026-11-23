// CloudFront Function viewer-request.js(ALB로 가는 동적 동작 5개의 viewer-request)의 단위 테스트. 실행: node --test (의존 패키지 없음)
//
// 왜 필요한가: CloudFront Functions는 AWS에 올리기 전에는 돌려 볼 방법이 없고, 함수가 틀리면 사이트 전체가 403·502가 된다.
// 그래서 같은 코드를 node:vm 샌드박스(require·fetch·타이머가 없는 빈 전역, eval·new Function 금지)에서 직접 실행해 계약을 확인한다.
//
// 계약: backend는 X-Forwarded-*·Forwarded를 믿고 요청 한도를 클라이언트 IP 헤더로 세므로 클라이언트가 보낸 값이 닿으면 안 된다.
//  1. host가 현재 배포의 기본 도메인과 일치해야 통과한다(대소문자 무시). 불일치하거나 도메인 정보가 없으면 403을 돌려준다.
//  2. 전달 헤더 8개(STRIPPED)를 요청에서 지운다(속성 삭제).
//  3. x-whycode-client-ip를 event.viewer.ip로 덮어쓴다. viewer·ip가 없으면 설정하지 않는다("undefined" 문자열 금지).
//  4. 그 밖은 건드리지 않는다. host는 읽기 전용이고 x-forwarded-proto·x-real-ip는 CloudFront가 추가를 막는 헤더(추가하면 502)다.
//     x-forwarded-for도 건드리지 않는다.
//  5. 통과하면 요청 객체를 돌려준다. 입력 객체를 고쳐도 되므로 돌려준 값만 검사한다. 예외는 403일 때 "헤더를 안 건드림"이다.
//     돌려준 값이 응답이라 입력 객체에서만 볼 수 있다.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const CODE_URL = new URL('./viewer-request.js', import.meta.url);
let loaded;

// 코드를 읽어 샌드박스에서 실행하고 전역 handler를 꺼낸다. 실패하면 이유(파일 없음·실행 예외·handler 없음)를 메시지로 말한다.
function load() {
  if (loaded) return loaded;
  let source;
  try {
    source = readFileSync(CODE_URL, 'utf8');
  } catch (e) {
    throw new Error(`코드 파일을 읽지 못했다(${e.code}): ${CODE_URL.pathname} — 구현 단계가 이 파일을 만들어야 한다`);
  }
  const sandbox = vm.createContext({}, { codeGeneration: { strings: false, wasm: false } });
  try {
    vm.runInContext(source, sandbox, { filename: 'viewer-request.js' });
  } catch (e) {
    throw new Error(`viewer-request.js를 실행하다 예외가 났다: ${e.name}: ${e.message}`);
  }
  // function·var·const 어느 꼴로 정의해도 꺼낸다. 꼴이 맞는지는 아래 구문 검사가 따로 본다.
  const handler = vm.runInContext('typeof handler === "function" ? handler : undefined', sandbox);
  if (!handler) throw new Error('handler 없음: viewer-request.js가 전역 function handler(event)를 정의하지 않았다');
  return (loaded = { source, handler });
}

// 샌드박스(다른 realm)가 만든 객체는 Object.prototype이 달라 deepStrictEqual이 "구조는 같은데 다르다"며 실패한다.
// 값만 옮겨 담는다(undefined 값도 키로 남겨서 "지우지 않고 undefined로 둔" 구현이 보이게 한다).
const plain = (v) =>
  Array.isArray(v) ? Array.from(v, plain)
    : v !== null && typeof v === 'object' ? Object.fromEntries(Object.keys(v).map((k) => [k, plain(v[k])]))
      : v;

function call(event) {
  const result = load().handler(event);
  assert.ok(result !== null && typeof result === 'object', `handler가 요청·응답 객체를 돌려주지 않았다: ${typeof result}`);
  assert.ok(typeof result.then !== 'function', 'handler가 Promise를 돌려줬다: CloudFront Functions는 async를 쓸 수 없다');
  return plain(result);
}

const GOOD_HOST = 'd111111abcdef8.cloudfront.net';
const VIEWER_IP = '203.0.113.7';
const IP_HEADER = 'x-whycode-client-ip';
const STRIPPED = ['forwarded', 'x-forwarded-host', 'x-forwarded-port', 'x-forwarded-prefix', 'x-forwarded-ssl', 'true-client-ip', 'cf-connecting-ip', IP_HEADER];
const FORGED = Object.fromEntries(STRIPPED.map((name) => [name, `forged-${name}`]));
const ONLY_DELETED = STRIPPED.filter((name) => name !== IP_HEADER); // IP_HEADER는 지운 뒤 viewer.ip로 다시 채워지므로 따로 본다

// 건드리면 안 되는 헤더(host는 값이 GOOD_HOST로 정해져 있어 아래 "그대로" 표에서 따로 얹는다). 값이 서로 달라서, 지우거나 다른 헤더 값으로 바꾸면 보인다.
// x-forwarded-proto를 https가 아닌 값으로 둔 것은 "https로 고쳐 쓰는" 구현을 잡으려는 것이다.
// content-length·via·transfer-encoding은 CloudFront가 읽기만 허용하는 헤더라, 지우거나 고치려 하면 요청이 오류로 끝난다.
const KEEP = {
  'x-forwarded-for': '198.51.100.9, 203.0.113.50',
  'x-forwarded-proto': 'http',
  'x-real-ip': '198.51.100.77',
  'content-length': '1234',
  via: '1.1 d111111abcdef8.cloudfront.net (CloudFront)',
  'transfer-encoding': 'chunked',
  authorization: 'Bearer token-a1',
  cookie: 'sid=cookie-b2',
  'content-type': 'application/json; charset=utf-8',
  accept: 'application/json, text/plain, */*',
  'x-hub-signature-256': 'sha256=0badc0de',
  'x-slack-signature': 'v0=5a5a5a',
  'paddle-signature': 'ts=1700000000;h1=cafe',
  origin: 'https://example.test',
  'user-agent': 'unit-test/1.0',
  'mcp-session-id': 'sess-7f3a',
};

// headers는 { 이름: '값' }으로 받아 CloudFront 모양({ 이름: { value } })으로 바꾼다. host: null은 host 헤더가 없는 요청, viewer: null은 viewer가 없는 이벤트다.
function makeEvent({
  headers = {},
  host = GOOD_HOST,
  context = { distributionDomainName: GOOD_HOST },
  viewer = { ip: VIEWER_IP },
  method = 'POST',
  uri = '/api/Projects/AbC-1/chat',
} = {}) {
  const all = host === null ? headers : { host, ...headers };
  const event = {
    request: {
      method,
      uri,
      querystring: { q: { value: 'why' }, Page: { value: '2' } },
      headers: Object.fromEntries(Object.entries(all).map(([name, value]) => [name, { value }])),
      cookies: { sid: { value: 'cookie-sid' } },
    },
  };
  if (context) event.context = context;
  if (viewer) event.viewer = viewer;
  return event;
}

const isResponse = (result) => 'statusCode' in result;
const hasHeader = (result, name) => Object.hasOwn(result.headers, name);

test('코드가 전역 function handler(event)를 정의한다', () => {
  assert.equal(typeof load().handler, 'function');
});

// ── 1. 호스트 확인 ────────────────────────────────────────────────────────────────────────────────
// 대문자 host는 통과하고, 통과해도 host 값은 소문자로 바꾸지 않는다(host는 CloudFront가 수정을 막는 헤더다).
for (const [host, distributionDomainName] of [
  [GOOD_HOST, GOOD_HOST],
  ['d1.cloudfront.net', 'd1.cloudfront.net'],
  [GOOD_HOST.toUpperCase(), GOOD_HOST],
]) {
  test(`호스트 통과: ${host} (요청 객체를 돌려주고 host 값은 그대로)`, () => {
    const result = call(makeEvent({ host, context: { distributionDomainName } }));
    assert.ok(!isResponse(result), `통과해야 하는데 응답을 돌려줬다: ${JSON.stringify(result)}`);
    assert.deepStrictEqual(result.headers.host, { value: host });
  });
}

// null은 host 헤더가 없는 경우다. 다른 CloudFront 배포의 기본 주소도 허용하지 않는다.
const BAD_HOSTS = [
  null, '', 'cloudfront.net', 'evil.com', 'localhost', 'd1.cloudfront.net', `${GOOD_HOST}.evil.com`, 'evil.cloudfront.net.evil', `${GOOD_HOST}:443`,
  'xcloudfront.net', 'a.b.cloudfront.net', '.cloudfront.net', 'd1-2.cloudfront.net', `${GOOD_HOST}.`,
];
const hostLabel = (host) => (host === null ? '(host 헤더 없음)' : JSON.stringify(host));

for (const host of BAD_HOSTS) {
  test(`호스트 거절: ${hostLabel(host)} → 403 응답`, () => {
    const result = call(makeEvent({ host, headers: FORGED }));
    assert.equal(result.statusCode, 403, `거절해야 하는데: ${JSON.stringify(result)}`);
  });
}

test('표준 배포 도메인이 없는 이벤트는 403을 돌려준다', () => {
  for (const context of [null, {}]) {
    assert.equal(call(makeEvent({ context })).statusCode, 403);
  }
});

const rejected = () => call(makeEvent({ host: 'evil.com' }));

test('거절 응답: statusCode는 숫자 403이다', () => assert.strictEqual(rejected().statusCode, 403));
test('거절 응답: statusDescription은 Forbidden이다', () => assert.strictEqual(rejected().statusDescription, 'Forbidden'));
test('거절 응답: content-type 헤더는 application/json이다', () => {
  assert.deepStrictEqual(rejected().headers['content-type'], { value: 'application/json' });
});
test('거절 응답: 본문은 {"error":"forbidden_host"}인 JSON 문자열이다', () => {
  const { body } = rejected();
  assert.equal(typeof body, 'string', `본문이 문자열이 아니다: ${typeof body}`);
  assert.deepStrictEqual(JSON.parse(body), { error: 'forbidden_host' });
});

// 호스트 확인에서 막히면 헤더를 건드리지 않고 바로 403을 돌려준다. 돌려준 값이 응답이라 입력 객체에서만 보이는, 이 파일에서 유일한 입력 검사다.
test('거절할 때는 입력 요청을 건드리지 않는다(헤더 삭제·IP 설정 금지)', () => {
  for (const host of ['evil.com', null]) {
    const event = makeEvent({ host, headers: { ...KEEP, ...FORGED } });
    const before = structuredClone(event.request);
    call(event);
    assert.deepStrictEqual(event.request, before, `${hostLabel(host)}: 거절하면서 요청을 고쳤다`);
  }
});

// ── 2. 전달 헤더 제거 ─────────────────────────────────────────────────────────────────────────────
// 하나씩 따로: 목록에서 하나만 빠진 구현은 그 헤더의 테스트만 실패한다. 속성 자체가 없어야 한다(빈 값·undefined로 두면 안 된다).
for (const name of ONLY_DELETED) {
  test(`삭제: 위조된 ${name} 헤더는 요청에서 지워진다`, () => {
    const result = call(makeEvent({ headers: { [name]: FORGED[name] } }));
    assert.ok(!isResponse(result), '요청 객체를 돌려줘야 한다(응답 아님)');
    assert.ok(!hasHeader(result, name), `${name}이 남아 있다: ${JSON.stringify(result.headers[name])}`);
  });
}

// 한꺼번에: 첫 번째만 지우고 멈추는 구현은 하나씩 따로 보면 드러나지 않는다.
test('삭제: 8개가 한꺼번에 와도 7개는 모두 지워지고 x-whycode-client-ip만 viewer.ip로 남는다', () => {
  const result = call(makeEvent({ headers: FORGED }));
  for (const name of ONLY_DELETED) assert.ok(!hasHeader(result, name), `${name}이 남아 있다`);
  assert.deepStrictEqual(result.headers[IP_HEADER], { value: VIEWER_IP });
});

// ── 3. 클라이언트 IP ──────────────────────────────────────────────────────────────────────────────
for (const ip of [VIEWER_IP, '2001:db8::1']) {
  test(`IP: viewer.ip ${ip}를 x-whycode-client-ip에 그대로 넣는다`, () => {
    const result = call(makeEvent({ viewer: { ip } }));
    assert.deepStrictEqual(result.headers[IP_HEADER], { value: ip });
  });
}

test('IP: 클라이언트가 보낸 x-whycode-client-ip는 viewer.ip로 덮어쓴다', () => {
  const result = call(makeEvent({ headers: { [IP_HEADER]: '1.2.3.4' } }));
  assert.deepStrictEqual(result.headers[IP_HEADER], { value: VIEWER_IP });
});

test('IP: x-forwarded-for·cf-connecting-ip·true-client-ip가 달라도 viewer.ip만 쓴다', () => {
  const headers = { 'x-forwarded-for': '9.9.9.9', 'cf-connecting-ip': '8.8.8.8', 'true-client-ip': '7.7.7.7' };
  assert.deepStrictEqual(call(makeEvent({ headers })).headers[IP_HEADER], { value: VIEWER_IP });
});

// viewer가 없는 이벤트는 비정상이다. 그래도 요청은 통과하고, 위조값이 남거나 "undefined" 문자열이 들어가면 안 된다.
for (const [what, viewer] of [['event.viewer가 없다', null], ['viewer.ip가 없다', {}]]) {
  test(`IP: ${what} → x-whycode-client-ip를 만들지 않고 위조값도 지운다`, () => {
    for (const headers of [{}, { [IP_HEADER]: '1.2.3.4' }]) {
      const result = call(makeEvent({ viewer, headers }));
      assert.ok(!isResponse(result), '비정상 이벤트여도 요청은 통과해야 한다');
      assert.ok(!hasHeader(result, IP_HEADER), `헤더가 남았다(입력 ${JSON.stringify(headers)}): ${JSON.stringify(result.headers[IP_HEADER])}`);
    }
  });
}

// ── 4. 건드리면 안 되는 것 ─────────────────────────────────────────────────────────────────────────
// 위조 헤더 8개와 한 요청에 담아 돌린다. 삭제가 지나쳐서 이웃 헤더까지 지우는 구현(x-forwarded-* 전부 삭제 등)을 잡는다.
for (const [name, value] of [['host', GOOD_HOST], ...Object.entries(KEEP)]) {
  test(`그대로: ${name} 헤더의 값은 바뀌지 않는다`, () => {
    const result = call(makeEvent({ headers: { ...KEEP, ...FORGED } }));
    assert.deepStrictEqual(result.headers[name], { value });
  });
}

test('헤더 집합은 (원래 − 삭제 대상 7개) + x-whycode-client-ip이고, 그 밖의 헤더를 더하지 않는다', () => {
  const result = call(makeEvent({ headers: { ...KEEP, ...FORGED } }));
  assert.deepStrictEqual(Object.keys(result.headers).sort(), ['host', ...Object.keys(KEEP), IP_HEADER].sort());
});

// 최소 요청은 x-forwarded-proto·x-real-ip 같은 "추가하면 502"인 헤더를 함수가 새로 만드는지 드러낸다.
test('host만 있는 최소 요청은 host와 x-whycode-client-ip만 남는다', () => {
  assert.deepStrictEqual(Object.keys(call(makeEvent()).headers).sort(), ['host', IP_HEADER]);
});

// 메서드·경로(동적 동작 5개가 받는 것)와 상관없이 같은 처리여야 하고, method·uri·querystring·cookies는 그대로다.
const SAMPLES = [
  ['GET', '/api/Projects/AbC-1/chat'], ['POST', '/mcp'], ['OPTIONS', '/oauth2/authorize'], ['PUT', '/.well-known/oauth-authorization-server'],
  ['DELETE', '/api/webhooks/github'],
];
test('method·uri·querystring·cookies는 그대로다(메서드·경로와 상관없이)', () => {
  for (const [method, uri] of SAMPLES) {
    const event = makeEvent({ method, uri, headers: { ...KEEP, ...FORGED } });
    const before = structuredClone(event.request);
    const result = call(event);
    assert.ok(!isResponse(result), `${method} ${uri}: 요청을 돌려줘야 한다`);
    for (const field of ['method', 'uri', 'querystring', 'cookies']) {
      assert.deepStrictEqual(result[field], before[field], `${method} ${uri}: ${field}가 바뀌었다`);
    }
    assert.deepStrictEqual(result.headers[IP_HEADER], { value: VIEWER_IP }, `${method} ${uri}: IP 헤더가 다르다`);
    assert.ok(!hasHeader(result, 'forwarded'), `${method} ${uri}: forwarded가 남았다`);
  }
});

// 멱등: 같은 입력은 몇 번 불러도 같은 결과여야 하고(호출 사이에 남는 상태가 없다), 통과한 요청을 다시 넣어도 그대로여야 한다.
test('멱등: 같은 입력을 두 번 불러도 같고, 통과한 요청을 다시 넣어도 같다', () => {
  for (const host of [GOOD_HOST, 'evil.com']) {
    const make = () => makeEvent({ host, headers: { ...KEEP, ...FORGED } });
    const first = call(make());
    assert.deepStrictEqual(call(make()), first, `${host}: 같은 입력인데 결과가 다르다`);
    if (!isResponse(first)) {
      const again = { ...make(), request: structuredClone(first) };
      assert.deepStrictEqual(call(again), first, `${host}: 결과를 다시 넣으면 달라진다`);
    }
  }
});

// ── 코드 자체의 검사 ──────────────────────────────────────────────────────────────────────────────
// 주석·문자열 안의 글자는 구문이 아니므로 비운다(주석에 "export"라고 적었다고 실패하면 안 된다).
const skeleton = (src) => src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|'(?:\\.|[^'\\\n])*'|"(?:\\.|[^"\\\n])*"/g, ' ');

// CloudFront Functions에서 쓸 수 없는 것(공식 문서의 제한).
const FORBIDDEN = [
  [/\brequire\s*\(/, 'require('], [/\bimport\b/, 'import'], [/\bexport\b/, 'export'], [/\basync\b/, 'async'], [/\bawait\b/, 'await'],
  [/\beval\s*\(/, 'eval('], [/\bfetch\s*\(/, 'fetch('], [/\bsetTimeout\s*\(/, 'setTimeout('], [/\bnew\s+Function\b/, 'new Function'],
];

// 런타임이 어디까지 지원하는지 단정하지 못해서 ES5 꼴(var·function)로 묶는 보수적 검사.
const ES5_ONLY = [
  [/=>/, '=> (화살표 함수)'], [/\blet\b/, 'let'], [/\bconst\b/, 'const'], [/`/, '` (템플릿 리터럴)'], [/\?\./, '?. (옵셔널 체이닝)'],
  [/\?\?/, '?? (널 병합)'], [/\bclass\b/, 'class'], [/\.\.\./, '... (펼침·나머지)'],
];

test('코드 크기가 10KB(10240바이트) 이하다(주석 포함, 한글은 글자당 3바이트)', () => {
  const bytes = Buffer.byteLength(load().source, 'utf8');
  assert.ok(bytes <= 10240, `코드가 ${bytes}바이트다 — CloudFront Functions 상한 10240바이트를 넘는다`);
});

test('CloudFront Functions에서 쓸 수 없는 구문이 없다', () => {
  const code = skeleton(load().source);
  const found = FORBIDDEN.filter(([re]) => re.test(code)).map(([, label]) => label);
  assert.deepStrictEqual(found, [], `쓸 수 없는 구문: ${found.join(', ')}`);
});

test('구문이 ES5 꼴이다(화살표 함수·let·const·템플릿 리터럴·?.·?? 등을 쓰지 않는다)', () => {
  const code = skeleton(load().source);
  const found = ES5_ONLY.filter(([re]) => re.test(code)).map(([, label]) => label);
  assert.deepStrictEqual(found, [], `ES5를 넘는 구문: ${found.join(', ')}`);
});
