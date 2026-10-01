import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Button, Switch, Input, InputNumber, message, Typography, Space, Alert, Select } from 'antd'
import { SaveOutlined, CheckCircleOutlined, ReloadOutlined, GlobalOutlined, NotificationOutlined, KeyOutlined, LinkOutlined, RightOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { SystemConfig, BuilderApiKeyUpdateRequest } from '../types'
import SystemUpdate from './SystemUpdate'

const { Title, Text, Paragraph } = Typography

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

const SystemSettings: React.FC = () => {
  const { t, i18n: i18nInstance } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const navigate = useNavigate()

  // 第一部分：多语言
  const [languageForm] = Form.useForm()
  const [currentLang, setCurrentLang] = useState<string>('auto')

  // 第二部分：Relayer配置
  const [relayerForm] = Form.useForm()
  const [autoRedeemForm] = Form.useForm()
  const [systemConfig, setSystemConfig] = useState<SystemConfig | null>(null)
  const [relayerLoading, setRelayerLoading] = useState(false)
  const [autoRedeemLoading, setAutoRedeemLoading] = useState(false)

  // 第四部分：代理设置
  const [proxyForm] = Form.useForm()
  const [proxyLoading, setProxyLoading] = useState(false)
  const [proxyChecking, setProxyChecking] = useState(false)
  const [proxyCheckResult, setProxyCheckResult] = useState<ProxyCheckResponse | null>(null)
  const [currentProxyConfig, setCurrentProxyConfig] = useState<ProxyConfig | null>(null)

  useEffect(() => {
    // 初始化多语言设置
    const savedLanguage = localStorage.getItem('i18n_language') || 'auto'
    setCurrentLang(savedLanguage)
    languageForm.setFieldsValue({ language: savedLanguage })

    // 加载其他配置
    fetchSystemConfig()
    fetchProxyConfig()
  }, [])

  // ==================== 第一部分：多语言 ====================
  const detectSystemLanguage = (): string => {
    const systemLanguage = navigator.language || navigator.languages?.[0] || 'en'
    const lang = systemLanguage.toLowerCase()
    if (lang.startsWith('zh')) {
      if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo')) {
        return 'zh-TW'
      }
      return 'zh-CN'
    }
    return 'en'
  }

  const handleLanguageSubmit = async (values: { language: string }) => {
    try {
      let actualLang = values.language
      if (values.language === 'auto') {
        actualLang = detectSystemLanguage()
        localStorage.setItem('i18n_language', 'auto')
      } else {
        localStorage.setItem('i18n_language', values.language)
      }

      setCurrentLang(values.language)
      await i18nInstance.changeLanguage(actualLang)
      message.success(t('languageSettings.changeSuccess'))
    } catch (error) {
      message.error(t('languageSettings.changeFailed'))
    }
  }

  // ==================== 第二部分：Relayer配置 ====================
  const fetchSystemConfig = async () => {
    try {
      const response = await apiService.systemConfig.get()
      if (response.data.code === 0 && response.data.data) {
        const config = response.data.data
        setSystemConfig(config)
        // 后端返回的是掩码值，不填入输入框（仅作为占位提示），留空提交即保持原值
        relayerForm.setFieldsValue({
          builderApiKey: '',
          builderSecret: '',
          builderPassphrase: '',
        })
        autoRedeemForm.setFieldsValue({
          autoRedeemEnabled: config.autoRedeemEnabled
        })
      }
    } catch (error: any) {
      console.error('获取系统配置失败:', error)
    }
  }

  const handleRelayerSubmit = async (values: BuilderApiKeyUpdateRequest) => {
    setRelayerLoading(true)
    try {
      const updateData: BuilderApiKeyUpdateRequest = {}
      // 只提交用户实际输入的新值；空值或掩码值（含 ****）表示保持原值，不提交
      const isNewValue = (value?: string) => !!value && !!value.trim() && !value.includes('****')
      if (isNewValue(values.builderApiKey)) {
        updateData.builderApiKey = values.builderApiKey!.trim()
      }
      if (isNewValue(values.builderSecret)) {
        updateData.builderSecret = values.builderSecret!.trim()
      }
      if (isNewValue(values.builderPassphrase)) {
        updateData.builderPassphrase = values.builderPassphrase!.trim()
      }

      if (!updateData.builderApiKey && !updateData.builderSecret && !updateData.builderPassphrase) {
        message.warning(t('builderApiKey.noChanges'))
        setRelayerLoading(false)
        return
      }

      const response = await apiService.systemConfig.updateBuilderApiKey(updateData)
      if (response.data.code === 0) {
        message.success(t('builderApiKey.saveSuccess'))
        fetchSystemConfig()
        relayerForm.resetFields()
      } else {
        message.error(response.data.msg || t('builderApiKey.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('builderApiKey.saveFailed'))
    } finally {
      setRelayerLoading(false)
    }
  }

  const handleAutoRedeemSubmit = async (values: { autoRedeemEnabled: boolean }) => {
    setAutoRedeemLoading(true)
    try {
      const response = await apiService.systemConfig.updateAutoRedeem({ enabled: values.autoRedeemEnabled })
      if (response.data.code === 0) {
        message.success(t('systemSettings.autoRedeem.saveSuccess'))
        fetchSystemConfig()
      } else {
        message.error(response.data.msg || t('systemSettings.autoRedeem.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('systemSettings.autoRedeem.saveFailed'))
    } finally {
      setAutoRedeemLoading(false)
    }
  }

  // ==================== 第四部分：代理设置 ====================
  const fetchProxyConfig = async () => {
    try {
      const response = await apiService.proxyConfig.get()
      if (response.data.code === 0) {
        const data = response.data.data
        setCurrentProxyConfig(data)
        if (data) {
          proxyForm.setFieldsValue({
            enabled: data.enabled,
            host: data.host || '',
            port: data.port || undefined,
            username: data.username || '',
            password: '',
          })
        } else {
          proxyForm.resetFields()
        }
      } else {
        message.error(response.data.msg || '获取代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取代理配置失败')
    }
  }

  const handleProxySubmit = async (values: any) => {
    setProxyLoading(true)
    try {
      const response = await apiService.proxyConfig.saveHttp({
        enabled: values.enabled || false,
        host: values.host,
        port: values.port,
        username: values.username || undefined,
        password: values.password || undefined,
      })
      if (response.data.code === 0) {
        message.success('保存代理配置成功。新配置将立即生效，已建立的 WebSocket 连接需要重新连接才能使用新代理。')
        fetchProxyConfig()
        setProxyCheckResult(null)
      } else {
        message.error(response.data.msg || '保存代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '保存代理配置失败')
    } finally {
      setProxyLoading(false)
    }
  }

  const handleProxyCheck = async () => {
    setProxyChecking(true)
    setProxyCheckResult(null)
    try {
      const response = await apiService.proxyConfig.check()
      if (response.data.code === 0 && response.data.data) {
        const result = response.data.data
        setProxyCheckResult(result)
        if (result.success) {
          message.success(`代理检查成功：${result.message}${result.responseTime ? ` (响应时间: ${result.responseTime}ms)` : ''}`)
        } else {
          message.warning(`代理检查失败：${result.message}`)
        }
      } else {
        message.error(response.data.msg || '代理检查失败')
      }
    } catch (error: any) {
      message.error(error.message || '代理检查失败')
    } finally {
      setProxyChecking(false)
    }
  }

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('systemSettings.title')}</Title>
      </div>

      {/* 系统更新 */}
      <SystemUpdate />

      {/* 第一部分：多语言 */}
      <Card
        title={
          <Space>
            <GlobalOutlined />
            <span>{t('systemSettings.language.title')}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Form
          form={languageForm}
          layout="vertical"
          onFinish={handleLanguageSubmit}
          size={isMobile ? 'middle' : 'large'}
          initialValues={{ language: currentLang }}
        >
          <Form.Item
            label={t('systemSettings.language.currentLanguage')}
            name="language"
            rules={[{ required: true, message: t('systemSettings.language.languageRequired') }]}
          >
            <Select
              options={[
                { value: 'auto', label: t('languageSettings.followSystem') },
                { value: 'zh-CN', label: '简体中文' },
                { value: 'zh-TW', label: '繁體中文' },
                { value: 'en', label: 'English' }
              ]}
            />
          </Form.Item>
          {currentLang === 'auto' && (
            <Form.Item>
              <Text type="secondary" style={{ fontSize: '12px' }}>
                {t('languageSettings.currentSystemLanguage')}: {
                  detectSystemLanguage() === 'zh-CN' ? '简体中文' :
                    detectSystemLanguage() === 'zh-TW' ? '繁體中文' : 'English'
                }
              </Text>
            </Form.Item>
          )}
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
            >
              {t('common.save')}
            </Button>
          </Form.Item>
        </Form>
      </Card>

      {/* 第二部分：消息推送设置（独立页面入口） */}
      <Card
        title={
          <Space>
            <NotificationOutlined />
            <span>{t('systemSettings.notification.title')}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
        extra={
          <Button
            type="primary"
            icon={<RightOutlined />}
            onClick={() => navigate('/system-settings/notification')}
          >
            {t('notificationSettings.title')}
          </Button>
        }
      >
        <Paragraph type="secondary" style={{ marginBottom: 16 }}>
          {t('notificationSettings.botConfig')}、{t('notificationSettings.templateConfig')}等请在独立页面中配置。
        </Paragraph>
        <Button
          type="link"
          icon={<RightOutlined />}
          onClick={() => navigate('/system-settings/notification')}
          style={{ padding: 0 }}
        >
          {t('notificationSettings.title')} →
        </Button>
      </Card>

      {/* 第三部分：Relayer配置 */}
      <Card
        title={
          <Space>
            <KeyOutlined />
            <span>{t('systemSettings.relayer.title')}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        {/* Builder API Key 配置 */}
        <div style={{ marginBottom: '24px' }}>
          <Title level={4} style={{ marginBottom: '16px' }}>
            {t('builderApiKey.title')}
          </Title>
          <Alert
            message={t('builderApiKey.alertTitle')}
            description={
              <div>
                <Paragraph style={{ marginBottom: '8px' }}>
                  {t('builderApiKey.description')}
                </Paragraph>
                <Paragraph style={{ marginBottom: '8px' }}>
                  <Text strong>{t('builderApiKey.purposeTitle')}</Text>
                  <ul style={{ marginTop: '8px', marginBottom: 0, paddingLeft: '20px' }}>
                    <li>{t('builderApiKey.purpose1')}</li>
                    <li>{t('builderApiKey.purpose2')}</li>
                    <li>{t('builderApiKey.purpose3')}</li>
                  </ul>
                </Paragraph>
                <Paragraph style={{ marginBottom: 0 }}>
                  <Text strong>{t('builderApiKey.getApiKey')}</Text>
                  <Space style={{ marginLeft: '8px' }}>
                    <a
                      href="https://polymarket.com/settings?tab=builder"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      <LinkOutlined /> {t('builderApiKey.openSettings')}
                    </a>
                  </Space>
                </Paragraph>
              </div>
            }
            type="info"
            showIcon
            style={{ marginBottom: '16px' }}
          />

          <Form
            form={relayerForm}
            layout="vertical"
            onFinish={handleRelayerSubmit}
            size={isMobile ? 'middle' : 'large'}
          >
            <Form.Item
              label={t('builderApiKey.apiKey')}
              name="builderApiKey"
            >
              <Input
                placeholder={systemConfig?.builderApiKeyConfigured
                  ? t('builderApiKey.configuredPlaceholder', { value: systemConfig.builderApiKeyDisplay || '****' })
                  : t('builderApiKey.apiKeyPlaceholder')}
                style={{ fontFamily: 'monospace' }}
              />
            </Form.Item>

            <Form.Item
              label={t('builderApiKey.secret')}
              name="builderSecret"
            >
              <Input.Password
                placeholder={systemConfig?.builderSecretConfigured
                  ? t('builderApiKey.configuredPlaceholder', { value: systemConfig.builderSecretDisplay || '****' })
                  : t('builderApiKey.secretPlaceholder')}
                style={{ fontFamily: 'monospace' }}
                iconRender={(visible) => (visible ? <span>👁️</span> : <span>👁️‍🗨️</span>)}
              />
            </Form.Item>

            <Form.Item
              label={t('builderApiKey.passphrase')}
              name="builderPassphrase"
            >
              <Input.Password
                placeholder={systemConfig?.builderPassphraseConfigured
                  ? t('builderApiKey.configuredPlaceholder', { value: systemConfig.builderPassphraseDisplay || '****' })
                  : t('builderApiKey.passphrasePlaceholder')}
                style={{ fontFamily: 'monospace' }}
                iconRender={(visible) => (visible ? <span>👁️</span> : <span>👁️‍🗨️</span>)}
              />
            </Form.Item>

            <Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={relayerLoading}
              >
                {t('common.save')}
              </Button>
            </Form.Item>
          </Form>
        </div>

        {/* 自动赎回配置 */}
        <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: '24px' }}>
          <Title level={4} style={{ marginBottom: '16px' }}>
            {t('systemSettings.autoRedeem.title')}
          </Title>
          <Form
            form={autoRedeemForm}
            layout="vertical"
            onFinish={handleAutoRedeemSubmit}
            size={isMobile ? 'middle' : 'large'}
          >
            <Form.Item
              label={t('systemSettings.autoRedeem.label')}
              name="autoRedeemEnabled"
              tooltip={t('systemSettings.autoRedeem.tooltip')}
              valuePropName="checked"
            >
              <Switch loading={autoRedeemLoading} />
            </Form.Item>

            {!systemConfig?.builderApiKeyConfigured && (
              <Alert
                message={t('systemSettings.autoRedeem.builderApiKeyNotConfigured')}
                description={t('systemSettings.autoRedeem.builderApiKeyNotConfiguredDesc')}
                type="warning"
                showIcon
                style={{ marginBottom: '16px' }}
              />
            )}

            <Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={autoRedeemLoading}
              >
                {t('common.save')}
              </Button>
            </Form.Item>
          </Form>
        </div>
      </Card>

      {/* 第四部分：代理设置 */}
      <Card
        title={
          <Space>
            <LinkOutlined />
            <span>{t('systemSettings.proxy.title')}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Form
          form={proxyForm}
          layout="vertical"
          onFinish={handleProxySubmit}
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
            help={currentProxyConfig ? (t('proxySettings.passwordHelpUpdate')) : (t('proxySettings.passwordHelp'))}
          >
            <Input.Password placeholder={currentProxyConfig ? (t('proxySettings.passwordPlaceholderUpdate')) : (t('proxySettings.passwordPlaceholder'))} />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={proxyLoading}
              >
                {t('common.save')}
              </Button>
              <Button
                icon={<CheckCircleOutlined />}
                onClick={handleProxyCheck}
                loading={proxyChecking}
              >
                {t('proxySettings.check')}
              </Button>
              {proxyCheckResult && (
                <Button
                  icon={<ReloadOutlined />}
                  onClick={fetchProxyConfig}
                >
                  {t('common.refresh')}
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>

        {proxyCheckResult && (
          <Alert
            type={proxyCheckResult.success ? 'success' : 'error'}
            message={proxyCheckResult.success ? (t('proxySettings.checkSuccess')) : (t('proxySettings.checkFailed'))}
            description={
              <div>
                <Text>{proxyCheckResult.message}</Text>
                {(proxyCheckResult.responseTime !== undefined || proxyCheckResult.latency !== undefined) && (
                  <div style={{ marginTop: '8px' }}>
                    <Text type="secondary">
                      {t('proxySettings.latency')}: {(proxyCheckResult.latency ?? proxyCheckResult.responseTime) ?? 0}ms
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

export default SystemSettings
