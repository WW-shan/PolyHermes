import React, { useEffect, useState, useRef } from 'react'
import { Modal, Form, Button, Switch, message, Space, Radio, InputNumber, Table, Select, Divider, Input, Tag, InputRef, Card, Row, Col, Statistic, Spin } from 'antd'
import { SaveOutlined, FileTextOutlined, PlusOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import { useAccountStore } from '../../store/accountStore'
import type { Leader, CopyTradingTemplate, CopyTradingCreateRequest } from '../../types'
import { formatUSDC } from '../../utils'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import AccountImportForm from '../../components/AccountImportForm'
import LeaderAddForm from '../../components/LeaderAddForm'
import LeaderSelect from '../../components/LeaderSelect'

const { Option } = Select

interface AddModalProps {
  open: boolean
  onClose: () => void
  onSuccess?: () => void
  preFilledConfig?: {
    leaderId?: number
    copyMode?: 'RATIO' | 'FIXED'
    copyRatio?: number
    fixedAmount?: string
    maxOrderSize?: number
    minOrderSize?: number
    maxDailyLoss?: number
    maxDailyOrders?: number
    supportSell?: boolean
    keywordFilterMode?: string
    keywords?: string[]
    maxPositionValue?: number
    configName?: string
  }
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
    return `跟单配置-${dateStr}-${timeStr}`
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
        message.error(response.data.msg || t('copyTradingAdd.fetchAssetInfoFailed') || '获取资产信息失败')
      }
    } catch (error: any) {
      console.error('获取 Leader 资产失败:', error)
      message.error(error.message || t('copyTradingAdd.fetchAssetInfoFailed') || '获取资产信息失败')
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
      maxPositionValue: config.maxPositionValue
    }
    console.log('[AddModal] fillPreFilledConfig: setting form values:', formValues)
    
    form.setFieldsValue(formValues)
    setCopyMode(config.copyMode || 'RATIO')
    setKeywords(config.keywords || [])
    
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
      message.error(error.message || t('copyTradingAdd.fetchLeaderFailed') || '获取 Leader 列表失败')
    }
  }
  
  const fetchTemplates = async () => {
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchTemplateFailed') || '获取模板列表失败')
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
    message.success(t('copyTradingAdd.templateFilled') || '模板内容已填充，您可以修改')
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
    message.success(t('leaderAdd.addSuccess') || '添加 Leader 成功')
    
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
      message.warning(t('copyTradingAdd.keywordExists') || '关键字已存在')
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
        message.error(t('copyTradingAdd.fixedAmountMin') || '固定金额必须 >= 1')
        return
      }
    }
    
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error(t('copyTradingAdd.minOrderSizeMin') || '最小金额必须 >= 1')
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
        enabled: true, // 默认启用
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
        message.success(t('copyTradingAdd.createSuccess') || '创建跟单配置成功')
        onClose()
        if (onSuccess) {
          onSuccess()
        }
      } else {
        message.error(response.data.msg || t('copyTradingAdd.createFailed') || '创建跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.createFailed') || '创建跟单配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <>
      <Modal
        title={t('copyTradingAdd.title') || '新增跟单配置'}
        open={open}
        onCancel={onClose}
        footer={null}
        width="90%"
        style={{ top: 20 }}
        styles={{ body: { padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' } }}
        destroyOnHidden
        forceRender
      >
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
            label={t('copyTradingAdd.configName') || '配置名'}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingAdd.configNameRequired') || '请输入配置名' },
              { whitespace: true, message: t('copyTradingAdd.configNameRequired') || '配置名不能为空' }
            ]}
            tooltip={t('copyTradingAdd.configNameTooltip') || '为跟单配置设置一个名称，便于识别和管理'}
          >
            <Input 
              placeholder={t('copyTradingAdd.configNamePlaceholder') || '例如：跟单配置1'} 
              maxLength={255}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectWallet') || '选择钱包'}
            name="accountId"
            rules={[{ required: true, message: t('copyTradingAdd.walletRequired') || '请选择钱包' }]}
          >
            <Select 
              placeholder={t('copyTradingAdd.selectWalletPlaceholder') || '请选择钱包'}
              notFoundContent={
                accounts.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noAccounts') || '暂无账户'}</div>
                    <Button 
                      type="primary" 
                      icon={<PlusOutlined />}
                      onClick={() => setAccountImportModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.importAccount') || '导入账户'}
                    </Button>
                  </div>
                ) : null
              }
            >
              {accounts.map(account => (
                <Option key={account.id} value={account.id}>
                  {account.accountName || `账户 ${account.id}`} ({account.walletAddress.slice(0, 6)}...{account.walletAddress.slice(-4)})
                </Option>
              ))}
            </Select>
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectLeader') || '选择 Leader'}
            name="leaderId"
            rules={[{ required: true, message: t('copyTradingAdd.leaderRequired') || '请选择 Leader' }]}
          >
            <LeaderSelect
              leaders={leaders}
              placeholder={t('copyTradingAdd.selectLeaderPlaceholder') || '请选择 Leader'}
              onSelectChange={(value) => value !== undefined && fetchLeaderAssetInfo(value)}
              notFoundContent={
                leaders.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noLeaders') || '暂无 Leader'}</div>
                    <Button 
                      type="primary" 
                      icon={<PlusOutlined />}
                      onClick={() => setLeaderAddModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.addLeader') || '添加 Leader'}
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
                  <span>{t('copyTradingAdd.leaderAssetInfo') || 'Leader 资产信息'}</span>
                </Space>
              }
              size="small"
              style={{ marginBottom: '16px', backgroundColor: '#f5f5f5', border: '1px solid #d9d9d9' }}
            >
              {loadingAssetInfo ? (
                <div style={{ textAlign: 'center', padding: '24px' }}>
                  <Spin />
                  <div style={{ marginTop: '8px', color: '#999' }}>
                    {t('copyTradingAdd.loadingAssetInfo') || '加载资产信息中...'}
                  </div>
                </div>
              ) : (
                <Row gutter={16}>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.totalAsset') || '总资产'}
                      value={parseFloat(leaderAssetInfo.total)}
                      precision={4}
                      valueStyle={{ color: '#52c41a', fontWeight: 'bold', fontSize: '16px' }}
                      prefix="$"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.availableBalance') || '可用余额'}
                      value={parseFloat(leaderAssetInfo.available)}
                      precision={4}
                      valueStyle={{ color: '#1890ff', fontSize: '14px' }}
                      prefix="$"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.positionAsset') || '仓位资产'}
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
              {t('copyTradingAdd.selectTemplateFromModal') || '从模板填充配置'}
            </Button>
          </Form.Item>
          
          {/* 跟单金额模式 */}
          <Form.Item
            label={t('copyTradingAdd.copyMode') || '跟单金额模式'}
            name="copyMode"
            tooltip={t('copyTradingAdd.copyModeTooltip') || '选择跟单金额的计算方式。比例模式：跟单金额随 Leader 订单大小按比例变化；固定金额模式：无论 Leader 订单大小如何，跟单金额都固定不变。'}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingAdd.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('copyTradingAdd.fixedAmountMode') || '固定金额模式'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('copyTradingAdd.copyRatio') || '跟单比例'}
              name="copyRatio"
              tooltip={t('copyTradingAdd.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单'}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                suffix="%"
                placeholder={t('copyTradingAdd.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单），默认 100%'}
                parser={(value) => {
                  const cleaned = (value || '').toString().replace(/%/g, '').trim()
                  const parsed = parseFloat(cleaned) || 0
                  if (parsed > 10000) return 10000
                  if (parsed < 0.01) return 0.01
                  return parsed
                }}
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
              label={t('copyTradingAdd.fixedAmount') || '固定跟单金额 ($)'}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingAdd.fixedAmountRequired') || '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingAdd.invalidNumber') || '请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.fixedAmountMin') || '固定金额必须 >= 1'))
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
                placeholder={t('copyTradingAdd.fixedAmountPlaceholder') || '固定金额，不随 Leader 订单大小变化，必须 >= 1'}
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
                label={t('copyTradingAdd.maxOrderSize') || '单笔订单最大金额 ($)'}
                name="maxOrderSize"
                tooltip={t('copyTradingAdd.maxOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最大金额上限'}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingAdd.maxOrderSizePlaceholder') || '仅在比例模式下生效（可选）'}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              
              <Form.Item
                label={t('copyTradingAdd.minOrderSize') || '单笔订单最小金额 ($)'}
                name="minOrderSize"
                tooltip={t('copyTradingAdd.minOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最小金额下限，必须 >= 1'}
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.minOrderSizeMin') || '最小金额必须 >= 1'))
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
                  placeholder={t('copyTradingAdd.minOrderSizePlaceholder') || '仅在比例模式下生效，必须 >= 1（可选）'}
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
            label={t('copyTradingAdd.maxDailyLoss') || '每日最大亏损限制 ($)'}
            name="maxDailyLoss"
            tooltip={t('copyTradingAdd.maxDailyLossTooltip') || '限制每日最大亏损金额，用于风险控制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyLossPlaceholder') || '默认 10000 $（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxDailyOrders') || '每日最大跟单订单数'}
            name="maxDailyOrders"
            tooltip={t('copyTradingAdd.maxDailyOrdersTooltip') || '限制每日最多跟单的订单数量'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyOrdersPlaceholder') || '默认 100（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.priceTolerance') || '价格容忍度 (%)'}
            name="priceTolerance"
            tooltip={t('copyTradingAdd.priceToleranceTooltip') || '允许跟单价格在 Leader 价格基础上的调整范围'}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.priceTolerancePlaceholder') || '默认 5%（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.delaySeconds') || '跟单延迟 (秒)'}
            name="delaySeconds"
            tooltip={t('copyTradingAdd.delaySecondsTooltip') || '跟单延迟时间，0 表示立即跟单'}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.delaySecondsPlaceholder') || '默认 0（立即跟单）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.minOrderDepth') || '最小订单深度 ($)'}
            name="minOrderDepth"
            tooltip={t('copyTradingAdd.minOrderDepthTooltip') || '检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.minOrderDepthPlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxSpread') || '最大价差（绝对价格）'}
            name="maxSpread"
            tooltip={t('copyTradingAdd.maxSpreadTooltip') || '最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxSpreadPlaceholder') || '例如：0.05（5美分，可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.priceRangeFilter') || '价格区间过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.priceRange') || '价格区间'}
            name="priceRange"
            tooltip={t('copyTradingAdd.priceRangeTooltip') || '配置价格区间，仅在指定价格区间内的订单才会下单。例如：0.11-0.89 表示区间在0.11和0.89之间；-0.89 表示0.89以下都可以；0.11- 表示0.11以上都可以'}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingAdd.minPricePlaceholder') || '最低价（可选）'}
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
                  placeholder={t('copyTradingAdd.maxPricePlaceholder') || '最高价（可选）'}
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
          
          <Divider>{t('copyTradingAdd.positionLimitFilter') || '最大仓位限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.maxPositionValue') || '最大仓位金额 ($)'}
            name="maxPositionValue"
            tooltip={t('copyTradingAdd.maxPositionValueTooltip') || '限制单个市场的最大仓位金额。如果该市场的当前仓位金额 + 跟单金额超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxPositionValuePlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.keywordFilter') || '关键字过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.keywordFilterMode') || '过滤模式'}
            name="keywordFilterMode"
            tooltip={t('copyTradingAdd.keywordFilterModeTooltip') || '选择关键字过滤模式。白名单：只跟单包含关键字的市场；黑名单：不跟单包含关键字的市场；不启用：不进行关键字过滤'}
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
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
                  <Form.Item label={t('copyTradingAdd.keywords') || '关键字'}>
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        ref={keywordInputRef}
                        placeholder={t('copyTradingAdd.keywordPlaceholder') || '输入关键字，按回车添加'}
                        onPressEnter={(e) => handleAddKeyword(e)}
                      />
                      <Button 
                        type="primary" 
                        onClick={() => handleAddKeyword()}
                      >
                        {t('common.add') || '添加'}
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
                        ? (t('copyTradingAdd.whitelistTooltip') || '💡 白名单模式：只跟单包含上述任意关键字的市场标题')
                        : (t('copyTradingAdd.blacklistTooltip') || '💡 黑名单模式：不跟单包含上述任意关键字的市场标题')
                      }
                    </div>
                  </Form.Item>
                </>
              )
            }}
          </Form.Item>
          
          {/* 市场截止时间限制 */}
          <Divider>{t('copyTradingAdd.marketEndDateFilter') || '市场截止时间限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.maxMarketEndDate') || '最大市场截止时间'}
            tooltip={t('copyTradingAdd.maxMarketEndDateTooltip') || '仅跟单截止时间小于设定时间的订单。例如：24 小时表示只跟单距离结算还剩24小时以内的市场'}
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
                placeholder={t('copyTradingAdd.maxMarketEndDatePlaceholder') || '输入时间值（可选）'}
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
                placeholder={t('copyTradingAdd.timeUnit') || '单位'}
              >
                <Option value="HOUR">{t('copyTradingAdd.hour') || '小时'}</Option>
                <Option value="DAY">{t('copyTradingAdd.day') || '天'}</Option>
              </Select>
            </Space.Compact>
          </Form.Item>
          
          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ fontSize: 12, color: '#999' }}>
              {t('copyTradingAdd.maxMarketEndDateNote') || '💡 说明：不填写表示不启用此限制'}
            </div>
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.advancedSettings') || '高级设置'}</Divider>
          
          {/* 跟单卖出 */}
          <Form.Item
            label={t('copyTradingAdd.supportSell') || '跟单卖出'}
            name="supportSell"
            tooltip={t('copyTradingAdd.supportSellTooltip') || '是否跟单 Leader 的卖出订单'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 推送失败订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFailedOrders') || '推送失败订单'}
            name="pushFailedOrders"
            tooltip={t('copyTradingAdd.pushFailedOrdersTooltip') || '开启后，失败的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 推送已过滤订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFilteredOrders') || '推送已过滤订单'}
            name="pushFilteredOrders"
            tooltip={t('copyTradingAdd.pushFilteredOrdersTooltip') || '开启后，被过滤的订单会推送到 Telegram'}
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
                {t('copyTradingAdd.create') || '创建跟单配置'}
              </Button>
              <Button onClick={onClose}>
                {t('common.cancel') || '取消'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
      
      {/* 模板选择 Modal */}
      <Modal
        title={t('copyTradingAdd.selectTemplate') || '选择模板'}
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
              title: t('copyTradingAdd.templateName') || '模板名称',
              dataIndex: 'templateName',
              key: 'templateName'
            },
            {
              title: t('copyTradingAdd.copyMode') || '跟单模式',
              key: 'copyMode',
              render: (_: any, record: CopyTradingTemplate) => (
                <span>
                  {record.copyMode === 'RATIO' 
                    ? `${t('copyTradingAdd.ratioMode') || '比例'} ${record.copyRatio}x`
                    : `${t('copyTradingAdd.fixedAmountMode') || '固定'} $${formatUSDC(record.fixedAmount || '0')}`
                  }
                </span>
              )
            },
            {
              title: t('copyTradingAdd.supportSell') || '跟单卖出',
              dataIndex: 'supportSell',
              key: 'supportSell',
              render: (supportSell: boolean) => supportSell ? (t('common.yes') || '是') : (t('common.no') || '否')
            }
          ]}
        />
      </Modal>
      
      {/* 导入账户 Modal */}
      <Modal
        title={t('accountImport.title') || '导入账户'}
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
        title={t('leaderAdd.title') || '添加 Leader'}
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
