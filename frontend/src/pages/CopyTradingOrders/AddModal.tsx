import React, { useEffect, useState, useRef } from 'react'
import { Modal, Form, Button, Switch, message, Space, Radio, InputNumber, Table, Select, Divider, Input, Tag, InputRef, Card, Row, Col, Statistic, Spin, Alert } from 'antd'
import { SaveOutlined, FileTextOutlined, PlusOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import { useAccountStore } from '../../store/accountStore'
import type { Leader, CopyTradingTemplate, CopyTradingCreateRequest } from '../../types'
import { formatUSDC, parsePercentInput } from '../../utils'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import AccountImportForm from '../../components/AccountImportForm'
import LeaderAddForm from '../../components/LeaderAddForm'
import LeaderSelect from '../../components/LeaderSelect'
import type { CopyTradingPreFilledConfig } from '../../utils/backtestPrefill'

const { Option } = Select

interface AddModalProps {
  open: boolean
  onClose: () => void
  onSuccess?: () => void
  preFilledConfig?: CopyTradingPreFilledConfig
}

const AddModal: React.FC<AddModalProps> = ({
  open,
  onClose,
  onSuccess,
  preFilledConfig
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [templateModalVisible, setTemplateModalVisible] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  const [keywords, setKeywords] = useState<string[]>([])
  const keywordInputRef = useRef<InputRef>(null)
  const [maxMarketEndDateValue, setMaxMarketEndDateValue] = useState<number | undefined>()
  const [maxMarketEndDateUnit, setMaxMarketEndDateUnit] = useState<'HOUR' | 'DAY'>('HOUR')
  const [leaderAssetInfo, setLeaderAssetInfo] = useState<{ total: string; available: string; position: string } | null>(null)
  const [loadingAssetInfo, setLoadingAssetInfo] = useState(false)

  // 导入账户modal相关状态
  const [accountImportModalVisible, setAccountImportModalVisible] = useState(false)
  const [accountImportForm] = Form.useForm()

  // 添加leader modal相关状态
  const [leaderAddModalVisible, setLeaderAddModalVisible] = useState(false)
  const [leaderAddForm] = Form.useForm()

  // 生成默认配置名
  const generateDefaultConfigName = (): string => {
    const now = new Date()
    const dateStr = now.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).replace(/\//g, '-')
    const timeStr = now.toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })
    return `${t('copyTradingAdd.defaultConfigNamePrefix')}-${dateStr}-${timeStr}`
  }

  // 获取 Leader 资产信息
  const fetchLeaderAssetInfo = async (leaderId: number) => {
    if (!leaderId) return

    setLoadingAssetInfo(true)
    setLeaderAssetInfo(null)
    try {
      const response = await apiService.leaders.balance({ leaderId })
      if (response.data.code === 0 && response.data.data) {
        const balance = response.data.data
        setLeaderAssetInfo({
          total: balance.totalBalance || '0',
          available: balance.availableBalance || '0',
          position: balance.positionBalance || '0'
        })
      } else {
        message.error(response.data.msg || t('copyTradingAdd.fetchAssetInfoFailed'))
      }
    } catch (error: any) {
      console.error('获取 Leader 资产失败:', error)
      message.error(error.message || t('copyTradingAdd.fetchAssetInfoFailed'))
    } finally {
      setLoadingAssetInfo(false)
    }
  }

  // 填充预配置数据到表单（复用模板填充逻辑）
  const fillPreFilledConfig = (config: typeof preFilledConfig) => {
    console.log('[AddModal] fillPreFilledConfig called with config:', config)
    if (!config) {
      console.log('[AddModal] fillPreFilledConfig: config is null/undefined')
      return
    }

    const formValues = {
      configName: config.configName || generateDefaultConfigName(),
      leaderId: config.leaderId,
      copyMode: config.copyMode || 'RATIO',
      copyRatio: config.copyRatio,
      fixedAmount: config.fixedAmount,
      maxOrderSize: config.maxOrderSize,
      minOrderSize: config.minOrderSize,
      maxDailyLoss: config.maxDailyLoss,
      maxDailyOrders: config.maxDailyOrders,
      supportSell: config.supportSell,
      keywordFilterMode: config.keywordFilterMode || 'DISABLED',
      maxPositionValue: config.maxPositionValue,
      // 回测参数中的价格容忍度、延迟、深度/价差与价格区间一并回填（未提供时不覆盖表单默认值）
      ...(config.priceTolerance !== undefined ? { priceTolerance: config.priceTolerance } : {}),
      ...(config.delaySeconds !== undefined ? { delaySeconds: config.delaySeconds } : {}),
      minOrderDepth: config.minOrderDepth,
      maxSpread: config.maxSpread,
      minPrice: config.minPrice,
      maxPrice: config.maxPrice
    }
    console.log('[AddModal] fillPreFilledConfig: setting form values:', formValues)

    form.setFieldsValue(formValues)
    setCopyMode(config.copyMode || 'RATIO')
    setKeywords(config.keywords || [])
    // 市场截止时间（毫秒）换算为表单的数值 + 单位
    if (config.maxMarketEndDate && config.maxMarketEndDate > 0) {
      const dayMs = 24 * 60 * 60 * 1000
      const hourMs = 60 * 60 * 1000
      if (config.maxMarketEndDate % dayMs === 0) {
        setMaxMarketEndDateValue(config.maxMarketEndDate / dayMs)
        setMaxMarketEndDateUnit('DAY')
      } else {
        setMaxMarketEndDateValue(Math.round(config.maxMarketEndDate / hourMs))
        setMaxMarketEndDateUnit('HOUR')
      }
    }

    console.log('[AddModal] fillPreFilledConfig: form values set, copyMode:', config.copyMode, 'keywords:', config.keywords)

    // 自动获取 Leader 资产信息
    if (config.leaderId) {
      console.log('[AddModal] fillPreFilledConfig: fetching leader asset info for leaderId:', config.leaderId)
      fetchLeaderAssetInfo(config.leaderId)
    }
  }

  // 处理 Modal 打开/关闭
  useEffect(() => {
    console.log('[AddModal] useEffect triggered, open:', open, 'preFilledConfig:', preFilledConfig)
    if (open) {
      console.log('[AddModal] Modal opened, fetching accounts, leaders, templates')
      fetchAccounts()
      fetchLeaders()
      fetchTemplates()

      // 如果有预填充配置，填充表单（延迟执行确保数据已加载）
      if (preFilledConfig) {
        console.log('[AddModal] preFilledConfig exists, will fill form after 100ms')
        // 使用 setTimeout 确保在下一个事件循环执行，此时 Modal 已完全打开
        setTimeout(() => {
          console.log('[AddModal] setTimeout callback executed, calling fillPreFilledConfig')
          fillPreFilledConfig(preFilledConfig)
        }, 100)
      } else {
        console.log('[AddModal] No preFilledConfig, using default values')
        // 没有预填充配置时，生成默认配置名
      const defaultConfigName = generateDefaultConfigName()
        form.setFieldsValue({
          configName: defaultConfigName,
          copyMode: 'RATIO',
          copyRatio: 100,
          maxOrderSize: 1000,
          minOrderSize: 1,
          maxDailyLoss: 10000,
          maxDailyOrders: 100,
          supportSell: true,
          keywordFilterMode: 'DISABLED'
        })
        setCopyMode('RATIO')
      setKeywords([])
      }
    } else {
      console.log('[AddModal] Modal closed, resetting form')
      // 关闭时重置表单
      form.resetFields()
      setKeywords([])
      setCopyMode('RATIO')
      setLeaderAssetInfo(null)
    }
  }, [open, preFilledConfig])

  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchLeaderFailed'))
    }
  }

  const fetchTemplates = async () => {
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchTemplateFailed'))
    }
  }

  const handleSelectTemplate = (template: CopyTradingTemplate) => {
    // 填充模板数据到表单（只填充模板中存在的字段）
    form.setFieldsValue({
      copyMode: template.copyMode,
      copyRatio: template.copyRatio ? parseFloat(template.copyRatio) * 100 : 100, // 转换为百分比显示
      fixedAmount: template.fixedAmount ? parseFloat(template.fixedAmount) : undefined,
      maxOrderSize: template.maxOrderSize ? parseFloat(template.maxOrderSize) : undefined,
      minOrderSize: template.minOrderSize ? parseFloat(template.minOrderSize) : undefined,
      maxDailyOrders: template.maxDailyOrders,
      priceTolerance: template.priceTolerance ? parseFloat(template.priceTolerance) : undefined,
      supportSell: template.supportSell,
      minOrderDepth: template.minOrderDepth ? parseFloat(template.minOrderDepth) : undefined,
      maxSpread: template.maxSpread ? parseFloat(template.maxSpread) : undefined,
      minPrice: template.minPrice ? parseFloat(template.minPrice) : undefined,
      maxPrice: template.maxPrice ? parseFloat(template.maxPrice) : undefined,
      maxPositionValue: (template as any).maxPositionValue ? parseFloat((template as any).maxPositionValue) : undefined,
      pushFilteredOrders: template.pushFilteredOrders ?? false
    })
    setCopyMode(template.copyMode)
    setTemplateModalVisible(false)
    message.success(t('copyTradingAdd.templateFilled'))
  }

  const handleCopyModeChange = (mode: 'RATIO' | 'FIXED') => {
    setCopyMode(mode)
  }

  // 处理导入账户成功
  const handleAccountImportSuccess = async (accountId: number) => {
    message.success(t('accountImport.importSuccess'))

    // 刷新账户列表
    await fetchAccounts()

    // 自动选择新添加的账户
    form.setFieldsValue({ accountId })

    // 关闭modal并重置表单
    setAccountImportModalVisible(false)
    accountImportForm.resetFields()
  }

  // 处理添加leader成功
  const handleLeaderAddSuccess = async (leaderId: number) => {
    message.success(t('leaderAdd.addSuccess'))

    // 刷新leader列表
    await fetchLeaders()

    // 自动选择新添加的leader
    form.setFieldsValue({ leaderId })

    // 关闭modal并重置表单
    setLeaderAddModalVisible(false)
    leaderAddForm.resetFields()
  }

  // 添加关键字
  const handleAddKeyword = (e?: React.KeyboardEvent<HTMLInputElement>) => {
    let inputValue = ''

    if (e) {
      // 从键盘事件获取输入值
      const target = e.target as HTMLInputElement
      inputValue = target.value.trim()
    } else if (keywordInputRef.current) {
      // 从输入框 ref 获取值
      inputValue = keywordInputRef.current.input?.value?.trim() || ''
    }

    if (!inputValue) {
      return
    }

    // 检查是否已存在
    if (keywords.includes(inputValue)) {
      message.warning(t('copyTradingAdd.keywordExists'))
      return
    }

    // 添加关键字
    const newKeywords = [...keywords, inputValue]
    setKeywords(newKeywords)

    // 清空输入框
    if (keywordInputRef.current) {
      keywordInputRef.current.input!.value = ''
    }
  }

  // 删除关键字
  const handleRemoveKeyword = (index: number) => {
    const newKeywords = keywords.filter((_, i) => i !== index)
    setKeywords(newKeywords)
  }

    const handleSubmit = async (values: any) => {
    // 前端校验
    if (values.copyMode === 'FIXED') {
      if (!values.fixedAmount || Number(values.fixedAmount) < 1) {
        message.error(t('copyTradingAdd.fixedAmountMin'))
        return
      }
    }

    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error(t('copyTradingAdd.minOrderSizeMin'))
      return
    }

    // 计算市场截止时间（毫秒）
    let maxMarketEndDate: number | undefined
    if (maxMarketEndDateValue !== undefined && maxMarketEndDateValue > 0) {
      const multiplier = maxMarketEndDateUnit === 'HOUR'
        ? 60 * 60 * 1000  // 小时转毫秒
        : 24 * 60 * 60 * 1000  // 天转毫秒
      maxMarketEndDate = maxMarketEndDateValue * multiplier
    }

    setLoading(true)
    try {
      const request: CopyTradingCreateRequest = {
        accountId: values.accountId,
        leaderId: values.leaderId,
        // 默认启用；回测一键创建等预填充场景按 preFilledConfig.enabled（默认停用，需用户确认后再启用）
        enabled: preFilledConfig?.enabled ?? true,
        copyMode: values.copyMode || 'RATIO',
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.maxOrderSize?.toString(),
        minOrderSize: values.minOrderSize?.toString(),
        maxDailyLoss: values.maxDailyLoss?.toString(),
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        delaySeconds: values.delaySeconds,
        pollIntervalSeconds: values.pollIntervalSeconds,
        useWebSocket: values.useWebSocket,
        websocketReconnectInterval: values.websocketReconnectInterval,
        websocketMaxRetries: values.websocketMaxRetries,
        supportSell: values.supportSell !== false,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        maxPositionValue: values.maxPositionValue?.toString(),
        keywordFilterMode: values.keywordFilterMode || 'DISABLED',
        keywords: (values.keywordFilterMode === 'WHITELIST' || values.keywordFilterMode === 'BLACKLIST')
          ? keywords
          : undefined,
        configName: values.configName?.trim(),
        pushFailedOrders: values.pushFailedOrders ?? false,
        pushFilteredOrders: values.pushFilteredOrders ?? false,
        maxMarketEndDate
      }

      const response = await apiService.copyTrading.create(request)

      if (response.data.code === 0) {
        if (preFilledConfig?.enabled === false) {
          message.success(t('copyTradingAdd.createSuccessDisabled'))
        } else {
          message.success(t('copyTradingAdd.createSuccess'))
        }
        onClose()
        if (onSuccess) {
          onSuccess()
        }
      } else {
        message.error(response.data.msg || t('copyTradingAdd.createFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.createFailed'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Modal
        title={t('copyTradingAdd.title')}
        open={open}
        onCancel={onClose}
        footer={null}
        width="90%"
        style={{ top: 20 }}
        styles={{ body: { padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' } }}
        destroyOnHidden
        forceRender
      >
        {preFilledConfig?.enabled === false && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('copyTradingAdd.createDisabledHint')}
          />
        )}
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 100,
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyLoss: 10000,
            maxDailyOrders: 100,
            priceTolerance: 5,
            delaySeconds: 0,
            pollIntervalSeconds: 5,
            useWebSocket: true,
            websocketReconnectInterval: 5000,
            websocketMaxRetries: 10,
            supportSell: true,
            pushFailedOrders: false,
            pushFilteredOrders: false,
            keywordFilterMode: 'DISABLED'
          }}
        >
          {/* 基础信息 */}
          <Form.Item
            label={t('copyTradingAdd.configName')}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingAdd.configNameRequired') },
              { whitespace: true, message: t('copyTradingAdd.configNameRequired') }
            ]}
            tooltip={t('copyTradingAdd.configNameTooltip')}
          >
            <Input
              placeholder={t('copyTradingAdd.configNamePlaceholder')}
              maxLength={255}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.selectWallet')}
            name="accountId"
            rules={[{ required: true, message: t('copyTradingAdd.walletRequired') }]}
          >
            <Select
              placeholder={t('copyTradingAdd.selectWalletPlaceholder')}
              notFoundContent={
                accounts.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noAccounts')}</div>
                    <Button
                      type="primary"
                      icon={<PlusOutlined />}
                      onClick={() => setAccountImportModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.importAccount')}
                    </Button>
                  </div>
                ) : null
              }
            >
              {accounts.map(account => (
                <Option key={account.id} value={account.id}>
                  {account.accountName || t('positionList.accountFallback', { id: account.id })} ({account.walletAddress.slice(0, 6)}...{account.walletAddress.slice(-4)})
                </Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.selectLeader')}
            name="leaderId"
            rules={[{ required: true, message: t('copyTradingAdd.leaderRequired') }]}
          >
            <LeaderSelect
              leaders={leaders}
              placeholder={t('copyTradingAdd.selectLeaderPlaceholder')}
              onSelectChange={(value) => value !== undefined && fetchLeaderAssetInfo(value)}
              notFoundContent={
                leaders.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noLeaders')}</div>
                    <Button
                      type="primary"
                      icon={<PlusOutlined />}
                      onClick={() => setLeaderAddModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.addLeader')}
                    </Button>
                  </div>
                ) : null
              }
            />
          </Form.Item>

          {/* Leader 资产信息 */}
          {leaderAssetInfo && (
            <Card
              title={
                <Space>
                  <span>{t('copyTradingAdd.leaderAssetInfo')}</span>
                </Space>
              }
              size="small"
              style={{ marginBottom: '16px', backgroundColor: '#f5f5f5', border: '1px solid #d9d9d9' }}
            >
              {loadingAssetInfo ? (
                <div style={{ textAlign: 'center', padding: '24px' }}>
                  <Spin />
                  <div style={{ marginTop: '8px', color: '#999' }}>
                    {t('copyTradingAdd.loadingAssetInfo')}
                  </div>
                </div>
              ) : (
                <Row gutter={16}>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.totalAsset')}
                      value={parseFloat(leaderAssetInfo.total)}
                      precision={4}
                      valueStyle={{ color: '#52c41a', fontWeight: 'bold', fontSize: '16px' }}
                      prefix="$"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.availableBalance')}
                      value={parseFloat(leaderAssetInfo.available)}
                      precision={4}
                      valueStyle={{ color: '#1890ff', fontSize: '14px' }}
                      prefix="$"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.positionAsset')}
                      value={parseFloat(leaderAssetInfo.position)}
                      precision={4}
                      valueStyle={{ color: '#722ed1', fontSize: '14px' }}
                      prefix="$"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                </Row>
              )}
            </Card>
          )}

          {/* 模板填充按钮 */}
          <Form.Item>
            <Button
              type="dashed"
              icon={<FileTextOutlined />}
              onClick={() => setTemplateModalVisible(true)}
              style={{ width: '100%' }}
            >
              {t('copyTradingAdd.selectTemplateFromModal')}
            </Button>
          </Form.Item>

          {/* 跟单金额模式 */}
          <Form.Item
            label={t('copyTradingAdd.copyMode')}
            name="copyMode"
            tooltip={t('copyTradingAdd.copyModeTooltip')}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingAdd.ratioMode')}</Radio>
              <Radio value="FIXED">{t('copyTradingAdd.fixedAmountMode')}</Radio>
            </Radio.Group>
          </Form.Item>

          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('copyTradingAdd.copyRatio')}
              name="copyRatio"
              tooltip={t('copyTradingAdd.copyRatioTooltip')}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                suffix="%"
                placeholder={t('copyTradingAdd.copyRatioPlaceholder')}
                parser={(value) => parsePercentInput(value)}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  if (num > 10000) return '10000'
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}

          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('copyTradingAdd.fixedAmount')}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingAdd.fixedAmountRequired') },
                {
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingAdd.invalidNumber')))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.fixedAmountMin')))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                min={1}
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder={t('copyTradingAdd.fixedAmountPlaceholder')}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}

          {copyMode === 'RATIO' && (
            <>
              <Form.Item
                label={t('copyTradingAdd.maxOrderSize')}
                name="maxOrderSize"
                tooltip={t('copyTradingAdd.maxOrderSizeTooltip')}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingAdd.maxOrderSizePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>

              <Form.Item
                label={t('copyTradingAdd.minOrderSize')}
                name="minOrderSize"
                tooltip={t('copyTradingAdd.minOrderSizeTooltip')}
                rules={[
                  {
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.minOrderSizeMin')))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <InputNumber
                  min={1}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingAdd.minOrderSizePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            </>
          )}

          <Form.Item
            label={t('copyTradingAdd.maxDailyLoss')}
            name="maxDailyLoss"
            tooltip={t('copyTradingAdd.maxDailyLossTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyLossPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.maxDailyOrders')}
            name="maxDailyOrders"
            tooltip={t('copyTradingAdd.maxDailyOrdersTooltip')}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyOrdersPlaceholder')}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.priceTolerance')}
            name="priceTolerance"
            tooltip={t('copyTradingAdd.priceToleranceTooltip')}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.priceTolerancePlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.delaySeconds')}
            name="delaySeconds"
            tooltip={t('copyTradingAdd.delaySecondsTooltip')}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.delaySecondsPlaceholder')}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.minOrderDepth')}
            name="minOrderDepth"
            tooltip={t('copyTradingAdd.minOrderDepthTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.minOrderDepthPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.maxSpread')}
            name="maxSpread"
            tooltip={t('copyTradingAdd.maxSpreadTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxSpreadPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Divider>{t('copyTradingAdd.priceRangeFilter')}</Divider>

          <Form.Item
            label={t('copyTradingAdd.priceRange')}
            name="priceRange"
            tooltip={t('copyTradingAdd.priceRangeTooltip')}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingAdd.minPricePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              <span style={{ display: 'inline-block', width: '20px', textAlign: 'center', lineHeight: '32px' }}>-</span>
              <Form.Item name="maxPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingAdd.maxPricePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            </Space.Compact>
          </Form.Item>

          <Divider>{t('copyTradingAdd.positionLimitFilter')}</Divider>

          <Form.Item
            label={t('copyTradingAdd.maxPositionValue')}
            name="maxPositionValue"
            tooltip={t('copyTradingAdd.maxPositionValueTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxPositionValuePlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Divider>{t('copyTradingAdd.keywordFilter')}</Divider>

          <Form.Item
            label={t('copyTradingAdd.keywordFilterMode')}
            name="keywordFilterMode"
            tooltip={t('copyTradingAdd.keywordFilterModeTooltip')}
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled')}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist')}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist')}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.keywordFilterMode !== currentValues.keywordFilterMode
          }>
            {({ getFieldValue }) => {
              const filterMode = getFieldValue('keywordFilterMode')
              if (filterMode !== 'WHITELIST' && filterMode !== 'BLACKLIST') {
                return null
              }

              return (
                <>
                  <Form.Item label={t('copyTradingAdd.keywords')}>
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        ref={keywordInputRef}
                        placeholder={t('copyTradingAdd.keywordPlaceholder')}
                        onPressEnter={(e) => handleAddKeyword(e)}
                      />
                      <Button
                        type="primary"
                        onClick={() => handleAddKeyword()}
                      >
                        {t('common.add')}
                      </Button>
                    </Space.Compact>

                    {keywords.length > 0 && (
                      <div style={{ marginTop: 8 }}>
                        <Space wrap>
                          {keywords.map((keyword, index) => (
                            <Tag
                              key={index}
                              closable
                              onClose={() => handleRemoveKeyword(index)}
                              color={filterMode === 'WHITELIST' ? 'green' : 'red'}
                            >
                              {keyword}
                            </Tag>
                          ))}
                        </Space>
                      </div>
                    )}

                    <div style={{ marginTop: 8, fontSize: 12, color: '#999' }}>
                      {filterMode === 'WHITELIST'
                        ? (t('copyTradingAdd.whitelistTooltip'))
                        : (t('copyTradingAdd.blacklistTooltip'))
                      }
                    </div>
                  </Form.Item>
                </>
              )
            }}
          </Form.Item>

          {/* 市场截止时间限制 */}
          <Divider>{t('copyTradingAdd.marketEndDateFilter')}</Divider>

          <Form.Item
            label={t('copyTradingAdd.maxMarketEndDate')}
            tooltip={t('copyTradingAdd.maxMarketEndDateTooltip')}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <InputNumber
                min={0}
                max={9999}
                step={1}
                precision={0}
                value={maxMarketEndDateValue}
                onChange={(value) => {
                  // 允许设置为 null 或 undefined（清空）
                  if (value === null || value === undefined) {
                    setMaxMarketEndDateValue(undefined)
                  } else {
                    const num = Math.floor(value)
                    // 如果值为 0，也设置为 undefined（表示清空）
                    setMaxMarketEndDateValue(num > 0 ? num : undefined)
                  }
                }}
                onBlur={(e) => {
                  // 失去焦点时，如果值为 0 或空，设置为 undefined
                  const input = e.target as HTMLInputElement
                  const value = input.value
                  if (!value || value === '0') {
                    setMaxMarketEndDateValue(undefined)
                  }
                }}
                style={{ width: '60%' }}
                placeholder={t('copyTradingAdd.maxMarketEndDatePlaceholder')}
                parser={(value) => {
                  if (!value) return 0
                  const num = parseInt(value.replace(/\D/g, ''), 10)
                  return isNaN(num) ? 0 : num
                }}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  return Math.floor(value).toString()
                }}
              />
              <Select
                value={maxMarketEndDateUnit}
                onChange={(value) => setMaxMarketEndDateUnit(value)}
                style={{ width: '40%' }}
                placeholder={t('copyTradingAdd.timeUnit')}
              >
                <Option value="HOUR">{t('copyTradingAdd.hour')}</Option>
                <Option value="DAY">{t('copyTradingAdd.day')}</Option>
              </Select>
            </Space.Compact>
          </Form.Item>

          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ fontSize: 12, color: '#999' }}>
              {t('copyTradingAdd.maxMarketEndDateNote')}
            </div>
          </Form.Item>

          <Divider>{t('copyTradingAdd.advancedSettings')}</Divider>

          {/* 跟单卖出 */}
          <Form.Item
            label={t('copyTradingAdd.supportSell')}
            name="supportSell"
            tooltip={t('copyTradingAdd.supportSellTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          {/* 推送失败订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFailedOrders')}
            name="pushFailedOrders"
            tooltip={t('copyTradingAdd.pushFailedOrdersTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          {/* 推送已过滤订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFilteredOrders')}
            name="pushFilteredOrders"
            tooltip={t('copyTradingAdd.pushFilteredOrdersTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('copyTradingAdd.create')}
              </Button>
              <Button onClick={onClose}>
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* 模板选择 Modal */}
      <Modal
        title={t('copyTradingAdd.selectTemplate')}
        open={templateModalVisible}
        onCancel={() => setTemplateModalVisible(false)}
        footer={null}
        width={800}
      >
        <Table
          dataSource={templates}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          onRow={(record) => ({
            onClick: () => handleSelectTemplate(record),
            style: { cursor: 'pointer' }
          })}
          columns={[
            {
              title: t('copyTradingAdd.templateName'),
              dataIndex: 'templateName',
              key: 'templateName'
            },
            {
              title: t('copyTradingAdd.copyMode'),
              key: 'copyMode',
              render: (_: any, record: CopyTradingTemplate) => (
                <span>
                  {record.copyMode === 'RATIO'
                    ? `${t('copyTradingAdd.ratioMode')} ${record.copyRatio}x`
                    : `${t('copyTradingAdd.fixedAmountMode')} $${formatUSDC(record.fixedAmount || '0')}`
                  }
                </span>
              )
            },
            {
              title: t('copyTradingAdd.supportSell'),
              dataIndex: 'supportSell',
              key: 'supportSell',
              render: (supportSell: boolean) => supportSell ? (t('common.yes')) : (t('common.no'))
            }
          ]}
        />
      </Modal>

      {/* 导入账户 Modal */}
      <Modal
        title={t('accountImport.title')}
        open={accountImportModalVisible}
        onCancel={() => {
          setAccountImportModalVisible(false)
          accountImportForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 640}
        style={{ top: isMobile ? 20 : 50 }}
        styles={{ body: { padding: isMobile ? '16px 20px' : '24px 28px', maxHeight: 'calc(100vh - 140px)', overflow: 'auto' } }}
        destroyOnHidden
        forceRender
        maskClosable
        closable
      >
        <AccountImportForm
          form={accountImportForm}
          onSuccess={handleAccountImportSuccess}
          onCancel={() => {
            setAccountImportModalVisible(false)
            accountImportForm.resetFields()
          }}
        />
      </Modal>

      {/* 添加 Leader Modal */}
      <Modal
        title={t('leaderAdd.title')}
        open={leaderAddModalVisible}
        onCancel={() => {
          setLeaderAddModalVisible(false)
          leaderAddForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        styles={{ body: { padding: '24px', maxHeight: 'calc(100vh - 150px)', overflow: 'auto' } }}
        destroyOnHidden
        forceRender
        maskClosable
        closable
      >
        <LeaderAddForm
          form={leaderAddForm}
          onSuccess={handleLeaderAddSuccess}
          onCancel={() => {
            setLeaderAddModalVisible(false)
            leaderAddForm.resetFields()
          }}
          showCancelButton={true}
        />
      </Modal>
    </>
  )
}

export default AddModal
