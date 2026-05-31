import { Component, type ErrorInfo, type ReactNode } from "react";

import { StatusView } from "./StatusView";

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error("ErrorBoundary caught", error, info);
  }

  reset = () => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    if (this.state.error) {
      return (
        <StatusView
          tone="error"
          title="화면을 표시할 수 없습니다"
          description={
            <>
              예기치 못한 오류가 발생했어요.
              <br />
              <span
                className="mono"
                style={{ fontSize: 11, color: "var(--fg-subtle)" }}
              >
                {this.state.error.message}
              </span>
            </>
          }
          action={
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn" onClick={this.reset}>
                다시 시도
              </button>
              <button
                className="btn btn-primary"
                onClick={() => (window.location.href = "/")}
              >
                홈으로
              </button>
            </div>
          }
          fullPage
        />
      );
    }
    return this.props.children;
  }
}
