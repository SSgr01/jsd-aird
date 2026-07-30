import { Component, type ErrorInfo, type PropsWithChildren, type ReactNode } from 'react';

import { Button, Result } from 'antd';

interface State {
  hasError: boolean;
}

export class ErrorBoundary extends Component<PropsWithChildren, State> {
  public state: State = { hasError: false };

  public static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  public componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Unhandled render error', error, info);
  }

  public render(): ReactNode {
    if (this.state.hasError) {
      return (
        <Result
          status="500"
          title="页面加载失败"
          subTitle="请刷新页面重试；如果问题持续存在，请联系系统管理员。"
          extra={
            <Button type="primary" onClick={() => window.location.reload()}>
              刷新页面
            </Button>
          }
        />
      );
    }

    return this.props.children;
  }
}
