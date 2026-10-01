import { useEffect, useState } from 'react'
import { Card, Form, Button, Switch, Input, InputNumber, message, Typography, Space, Alert } from 'antd'
import { SaveOutlined, CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'

const { Title, Text } = Typography

interface ProxyConfig {
  id?: number
  type: string
  enabled: boolean
  host?: string
  port?: number
  username?: string
  subscriptionUrl?: string
  lastSubscriptionUpdate?: number
  createdAt: number
  updatedAt: number
}

interface ProxyCheckResponse {
  success: boolean
  message: string
  responseTime?: number
  latency?: number
}

const ProxySettings: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [checking, setChecking] = useState(false)
  const [checkResult, setCheckResult] = useState<ProxyCheckResponse | null>(null)
  const [currentConfig, setCurrentConfig] = useState<ProxyConfig | null>(null)
  
  useEffect(() => {
    fetchConfig()
  }, [])
  
  const fetchConfig = async () => {
    try {
      const response = await apiService.proxyConfig.get()
      if (response.data.code === 0) {
        const data = response.data.data
        setCurrentConfig(data)
        if (data) {
          form.setFieldsValue({
            enabled: data.enabled,
            host: data.host || '',
            port: data.port || undefined,
            username: data.username || '',
            password: '',  // 密码不预填充
          })
        } else {
          form.resetFields()
        }
      } else {
        message.error(response.data.msg || t('proxySettings.getFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('proxySettings.getFailed'))
    }
  }
  
  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const requestData: any = {
        enabled: values.enabled || false,
        host: values.host,
        port: values.port,
        username: values.username || undefined,
      }
      
      // 只有在输入了新密码时才包含密码字段
      if (values.password && values.password.trim()) {
        requestData.password = values.password
      }
      
      const response = await apiService.proxyConfig.saveHttp(requestData)
      if (response.data.code === 0) {
        message.success(t('proxySettings.saveSuccess'))
        setCheckResult(null)
        fetchConfig()
      } else {
        message.error(response.data.msg || t('proxySettings.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('proxySettings.saveFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  const handleCheck = async () => {
    setChecking(true)
    setCheckResult(null)
    try {
      const response = await apiService.proxyConfig.check()
      if (response.data.code === 0 && response.data.data) {
        setCheckResult(response.data.data)
      } else {
        setCheckResult({
          success: false,
          message: response.data.msg || t('proxySettings.checkFailed')
        })
      }
    } catch (error: any) {
      setCheckResult({
        success: false,
        message: error.message || t('proxySettings.checkFailed')
      })
    } finally {
      setChecking(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('proxySettings.title')}</Title>
      </div>
      
      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label={t('proxySettings.enabled')}
            name="enabled"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.host')}
            name="host"
            rules={[
              { required: true, message: t('proxySettings.hostRequired') },
              { pattern: /^[\w\.-]+$/, message: t('proxySettings.hostInvalid') }
            ]}
          >
            <Input placeholder={t('proxySettings.hostPlaceholder')} />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.port')}
            name="port"
            rules={[
              { required: true, message: t('proxySettings.portRequired') },
              { type: 'number', min: 1, max: 65535, message: t('proxySettings.portInvalid') }
            ]}
          >
            <InputNumber
              min={1}
              max={65535}
              style={{ width: '100%' }}
              placeholder={t('proxySettings.portPlaceholder')}
            />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.username')}
            name="username"
          >
            <Input placeholder={t('proxySettings.usernamePlaceholder')} />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.password')}
            name="password"
            help={currentConfig ? (t('proxySettings.passwordHelpUpdate')) : (t('proxySettings.passwordHelp'))}
          >
            <Input.Password placeholder={currentConfig ? (t('proxySettings.passwordPlaceholderUpdate')) : (t('proxySettings.passwordPlaceholder'))} />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('common.save')}
              </Button>
              <Button
                icon={<CheckCircleOutlined />}
                onClick={handleCheck}
                loading={checking}
              >
                {t('proxySettings.check')}
              </Button>
              {checkResult && (
                <Button
                  icon={<ReloadOutlined />}
                  onClick={fetchConfig}
                >
                  {t('common.refresh')}
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>
        
        {checkResult && (
          <Alert
            type={checkResult.success ? 'success' : 'error'}
            message={checkResult.success ? (t('proxySettings.checkSuccess')) : (t('proxySettings.checkFailed'))}
            description={
              <div>
                <Text>{checkResult.message}</Text>
                {(checkResult.responseTime !== undefined || checkResult.latency !== undefined) && (
                  <div style={{ marginTop: '8px' }}>
                    <Text type="secondary">
                      {t('proxySettings.latency')}: {(checkResult.latency ?? checkResult.responseTime) ?? 0}ms
                    </Text>
                  </div>
                )}
              </div>
            }
            style={{ marginTop: '16px' }}
            showIcon
          />
        )}
      </Card>
    </div>
  )
}

export default ProxySettings

