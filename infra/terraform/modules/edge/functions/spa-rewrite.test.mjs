// CloudFront Function spa-rewrite.js(웹 정적 동작의 viewer-request)의 단위 테스트. 실행: node --test (의존 패키지 없음)
//
// 왜 필요한가: CloudFront Functions는 AWS에 올리기 전에는 돌려 볼 방법이 없고, 함수가 틀리면 사이트 전체가 403·404가 된다.
// 그래서 같은 코드를 node:vm 샌드박스(require·fetch·타이머가 없는 빈 전역, eval·new Function 금지)에서 직접 실행해 계약을 확인한다.
//
// 계약: React SPA 라우트(/auth/callback·/projects/123 ...)는 S3에 파일이 없다. 요청 uri 전체가 \.[A-Za-z0-9]+$ 에 맞지 않으면
// (= 마지막 조각에 확장자가 없으면) request.uri를 /index.html로 바꾸고, 맞으면(= 실제 파일) 그대로 둔다.
// 항상 요청 객체를 돌려주고(응답 객체 아님) method·querystring·headers·cookies·viewer는 건드리지 않는다. 메서드와 상관없다.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const CODE_URL = new URL('./spa-rewrite.js', import.meta.url);
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
    vm.runInContext(source, sandbox, { filename: 'spa-rewrite.js' });
  } catch (e) {
    throw new Error(`spa-rewrite.js를 실행하다 예외가 났다: ${e.name}: ${e.message}`);
  }
  // function·var·const 어느 꼴로 정의해도 꺼낸다. 꼴이 맞는지는 아래 구문 검사가 따로 본다.
  const handler = vm.runInContext('typeof handler === "function" ? handler : undefined', sandbox);
  if (!handler) throw new Error('handler 없음: spa-rewrite.js가 전역 function handler(event)를 정의하지 않았다');
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
  assert.ok(result !== null && typeof result === 'object', `handler가 요청 객체를 돌려주지 않았다: ${typeof result}`);
  assert.ok(typeof result.then !== 'function', 'handler가 Promise를 돌려줬다: CloudFront Functions는 async를 쓸 수 없다');
  return plain(result);
}

// CloudFront viewer-request 이벤트 모양: 헤더 이름은 항상 소문자이고 값은 { value } 객체다.
function makeEvent(uri, { method = 'GET', querystring } = {}) {
  return {
    viewer: { ip: '203.0.113.7' },
    request: {
      method,
      uri,
      querystring: querystring ?? { code: { value: 'abc.js' }, state: { value: 'xyz' } },
      headers: { host: { value: 'd111111abcdef8.cloudfront.net' }, accept: { value: 'text/html' }, 'user-agent': { value: 'unit-test/1.0' } },
      cookies: { sid: { value: 'cookie-sid' } },
    },
  };
}

const INDEX = '/index.html';

// 확장자가 없는 라우트 → /index.html (S3에는 이 경로의 파일이 없다)
// /foo. 은 점만 있고 영숫자가 없어 확장자가 아니다. /a.b/ 는 점이 있어도 끝이 슬래시라 확장자가 아니다.
const ROUTES = ['/', '/auth/callback', '/mcp/setup', '/projects/123', '/projects/123/', '/a.b/page', '/caf%C3%A9', '/foo.', '/a.b/'];

// 확장자가 있는 실제 파일 → 그대로. 확장자 길이(woff2·webmanifest)·대소문자(JPG)를 제한하거나 퍼센트 인코딩을 풀어 쓰는 구현을 잡는다.
const FILES = [
  INDEX, '/assets/index-DxK_3a.js', '/assets/app.min.css', '/robots.txt', '/sitemap.xml', '/favicon.ico', '/hero.mp4', '/logo.svg',
  '/fonts/a.woff2', '/manifest.webmanifest', '/Photo.JPG', '/assets/my%20icon.png',
];

test('코드가 전역 function handler(event)를 정의한다', () => {
  assert.equal(typeof load().handler, 'function');
});

for (const uri of ROUTES) {
  test(`라우트 ${uri} → ${INDEX}`, () => {
    const event = makeEvent(uri);
    const expected = { ...structuredClone(event.request), uri: INDEX };
    assert.deepStrictEqual(call(event), expected);
  });
}

// 기대값은 call()보다 먼저 복제한다. handler는 입력 객체를 제자리에서 고쳐도 되므로, 호출 뒤에 복제하면 기대값이 결과를 따라가 항상 통과한다.
for (const uri of FILES) {
  test(`파일 ${uri} → 그대로`, () => {
    const event = makeEvent(uri);
    const expected = structuredClone(event.request);
    assert.deepStrictEqual(call(event), expected);
  });
}

test('항상 요청 객체를 돌려준다(응답 객체가 아니다)', () => {
  for (const uri of ['/auth/callback', '/assets/app.min.css']) {
    const result = call(makeEvent(uri));
    for (const key of ['statusCode', 'statusDescription', 'body']) assert.ok(!(key in result), `${uri}: 응답 필드 ${key}가 있다`);
    assert.equal(typeof result.uri, 'string', `${uri}: 요청 객체의 uri가 없다`);
  }
});

test('method·querystring·headers·cookies·viewer는 건드리지 않는다', () => {
  for (const uri of ['/auth/callback', '/logo.svg']) {
    const event = makeEvent(uri, { method: 'OPTIONS' });
    const before = structuredClone(event);
    const result = call(event);
    for (const field of ['method', 'querystring', 'headers', 'cookies']) {
      assert.deepStrictEqual(result[field], before.request[field], `${uri}: ${field}가 바뀌었다`);
    }
    assert.deepStrictEqual(event.viewer, before.viewer, `${uri}: viewer가 바뀌었다`);
  }
});

// 메서드와 상관없이 같다: 메서드로 분기하면 HEAD·OPTIONS 요청만 S3에서 403·404가 난다.
for (const method of ['GET', 'HEAD', 'OPTIONS']) {
  test(`메서드 ${method}: 라우트는 ${INDEX}로, 파일은 그대로, 메서드 값은 그대로`, () => {
    const route = call(makeEvent('/projects/123', { method }));
    assert.deepStrictEqual([route.method, route.uri], [method, INDEX]);
    const file = call(makeEvent('/assets/app.min.css', { method }));
    assert.deepStrictEqual([file.method, file.uri], [method, '/assets/app.min.css']);
  });
}

// 판정은 uri만 본다. 쿼리 값의 모양(확장자가 있든 없든)이 uri 판정을 바꾸면 OAuth 콜백(/auth/callback?code=...)이 깨진다.
test('querystring은 판정에 영향이 없다(uri만 본다)', () => {
  const route = call(makeEvent('/auth/callback', { querystring: { next: { value: '/app.js' } } }));
  assert.equal(route.uri, INDEX, '라우트인데 쿼리 값의 확장자를 보고 그대로 뒀다');
  const file = call(makeEvent('/assets/app.js', { querystring: { v: { value: 'abc' } } }));
  assert.equal(file.uri, '/assets/app.js', '파일인데 쿼리 값을 보고 index로 바꿨다');
});

// 멱등: 같은 입력은 몇 번 불러도 같은 결과여야 하고(호출 사이에 남는 상태가 없다), 결과를 다시 넣어도 그대로여야 한다.
test('멱등: 같은 입력을 두 번 불러도, 결과를 다시 넣어도 같다', () => {
  for (const uri of [...ROUTES, ...FILES]) {
    const first = call(makeEvent(uri));
    assert.deepStrictEqual(call(makeEvent(uri)), first, `${uri}: 같은 입력인데 결과가 다르다`);
    const again = { ...makeEvent(uri), request: structuredClone(first) };
    assert.deepStrictEqual(call(again), first, `${uri}: 결과를 다시 넣으면 달라진다`);
  }
});

// ── 코드 자체의 검사 ──────────────────────────────────────────────────────────────────────────────
// 주석·문자열 안의 글자는 구문이 아니므로 비운다(주석에 "export"라고 적었다고 실패하면 안 된다).
// 정규식 리터럴은 따로 처리하지 않는다 — 이 함수들의 정규식에는 따옴표·// 가 없다.
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
