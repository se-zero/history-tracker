import { initializePaddle, type Paddle } from "@paddle/paddle-js";

type PaddleEventHandler = (eventName: string) => void;

let paddlePromise: Promise<Paddle | undefined> | null = null;
let initializedToken: string | null = null;
let eventHandler: PaddleEventHandler | null = null;

// 토큰·환경은 체크아웃 응답에서 온다. 빌드 시점 환경변수로 넣지 않는다 —
// 결제 버튼이 설정 스위치에 묶여 있고, 토큰은 그때의 서버 설정을 따른다.
// 스크립트는 누를 때 한 번만 로드한다. 토큰이 바뀌면(샌드박스↔라이브) 다시 초기화한다.
export async function openPaddleCheckout(options: {
  token: string;
  environment: string;
  transactionId: string;
  onEvent: PaddleEventHandler;
}): Promise<void> {
  eventHandler = options.onEvent;
  if (!paddlePromise || initializedToken !== options.token) {
    initializedToken = options.token;
    paddlePromise = initializePaddle({
      environment: options.environment === "production" ? "production" : "sandbox",
      token: options.token,
      eventCallback(event) {
        if (event.name) eventHandler?.(event.name);
      },
    }).catch((error: unknown) => {
      paddlePromise = null;
      initializedToken = null;
      throw error;
    });
  }

  const paddle = await paddlePromise;
  if (!paddle) {
    paddlePromise = null;
    initializedToken = null;
    throw new Error("Paddle.js failed to initialize.");
  }
  paddle.Checkout.open({ transactionId: options.transactionId });
}
