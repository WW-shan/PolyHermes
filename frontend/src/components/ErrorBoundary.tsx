import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Result, Button } from 'antd'
import i18n from '../i18n/config'

interface ErrorBoundaryProps {
  children: ReactNode
}

interface ErrorBoundaryState {
  hasError: boolean
}

/**
 * 顶层错误边界：页面渲染异常时显示提示，避免整页白屏
 */
class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('页面渲染异常:', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title={i18n.t('errorBoundary.title')}
          subTitle={i18n.t('errorBoundary.description')}
          extra={
            <Button type="primary" onClick={() => window.location.reload()}>
              {i18n.t('errorBoundary.reload')}
            </Button>
          }
        />
      )
    }
    return this.props.children
  }
}

export default ErrorBoundary
