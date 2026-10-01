import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { Card, Form, Input, Button, message, Typography } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import { wsManager } from '../services/websocket'
import { useAuthStore } from '../store/authStore'
import { setToken } from '../utils'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const Login: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [form] = Form.useForm()

  const handleLogin = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      const response = await apiService.auth.login(values)
      if (response.data.code === 0 && response.data.data) {
        const token = response.data.data.token
        setToken(token)
        // 新登录用户需要重新确认管理员权限
        useAuthStore.getState().resetAdminStatus()
        // 登录成功后显式建立 WebSocket 连接（首次登录或应用内登出后重新登录）
        wsManager.connect()
        message.success(t('message.loginSuccess'))
        // 跳转到首页
        navigate('/')
      } else {
        message.error(response.data.msg || t('message.loginFailed'))
      }
    } catch (error: any) {
      console.error('登录失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('message.loginFailed')
      message.error(errorMsg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      padding: isMobile ? '20px' : '40px',
      background: '#f0f2f5'
    }}>
      <Card
        style={{
          width: isMobile ? '100%' : '400px',
          boxShadow: '0 2px 8px rgba(0,0,0,0.1)'
        }}
      >
        <Title level={2} style={{ textAlign: 'center', marginBottom: '32px' }}>
          {t('login.title')}
        </Title>
        <Form
          form={form}
          onFinish={handleLogin}
          layout="vertical"
          size={isMobile ? 'large' : 'middle'}
        >
          <Form.Item
            name="username"
            label={t('login.username')}
            rules={[
              { required: true, message: t('login.usernameRequired') }
            ]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder={t('login.usernamePlaceholder')}
              autoComplete="username"
            />
          </Form.Item>
          <Form.Item
            name="password"
            label={t('login.password')}
            rules={[
              { required: true, message: t('login.passwordRequired') }
            ]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder={t('login.passwordPlaceholder')}
              autoComplete="current-password"
            />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              size={isMobile ? 'large' : 'middle'}
            >
              {t('login.title')}
            </Button>
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Link to="/reset-password" style={{ fontSize: isMobile ? '14px' : '13px' }}>
              {t('login.forgotPassword')}
            </Link>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default Login
