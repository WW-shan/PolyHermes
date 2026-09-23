import React, { useEffect, useState, useRef } from 'react'
import { Modal, Form, Button, message, Radio, InputNumber, Divider, Spin, Select, Input, Space, Switch, Tag, InputRef, Card, Row, Col, Statistic } from 'antd'
import { SaveOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import type { CopyTrading, CopyTradingUpdateRequest } from '../../types'
import { useTranslation } from 'react-i18next'
import { formatUSDC } from '../../utils'

const { Option } = Select

interface EditModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
  onSuccess?: () => void
}

const EditModal: React.FC<EditModalProps> = ({
  open,
  onClose,
  copyTradingId,
  onSuccess
}) => {
  const { t } = useTranslation()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)
  const [copyTrading, setCopyTrading] = useState<CopyTrading | null>(null)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
    const [originalEnabled, setOriginalEnabled] = useState<boolean>(true)
    const [keywords, setKeywords] = useState<string[]>([])
    const keywordInputRef = useRef<InputRef>(null)
    const [maxMarketEndDateValue, setMaxMarketEndDateValue] = useState<number | undefined>()
    const [maxMarketEndDateUnit, setMaxMarketEndDateUnit] = useState<'HOUR' | 'DAY'>('HOUR')
    const [leaderAssetInfo, setLeaderAssetInfo] = useState<{ total: string; available: string; position: string } | null>(null)
    const [loadingAssetInfo, setLoadingAssetInfo] = useState(false)
  
  useEffect(() => {
    if (open && copyTradingId) {
      fetchCopyTrading(parseInt(copyTradingId))
    }
  }, [open, copyTradingId])
  
  const fetchCopyTrading = async (copyTradingId: number) => {
    setFetching(true)
    try {
      const response = await apiService.copyTrading.list({})
      if (response.data.code === 0 && response.data.data) {
        const found = response.data.data.list.find((ct: CopyTrading) => ct.id === copyTradingId)
        if (found) {
          setCopyTrading(found)
          setCopyMode(found.copyMode)
          setOriginalEnabled(found.enabled)
          
          // 解析市场截止时间（毫秒转换为小时或天）
          if (found.maxMarketEndDate) {
            const hours = found.maxMarketEndDate / (60 * 60 * 1000)
            if (hours >= 24 && Number.isInteger(hours / 24)) {
              // 大于等于24小时且是24的整数倍，使用天作为单位
              setMaxMarketEndDateUnit('DAY')
              setMaxMarketEndDateValue(hours / 24)
            } else {
              // 使用小时作为单位
              setMaxMarketEndDateUnit('HOUR')
              setMaxMarketEndDateValue(hours)
            }
          } else {
            setMaxMarketEndDateValue(undefined)
            setMaxMarketEndDateUnit('HOUR')
          }
          
          form.setFieldsValue({
            accountId: found.accountId,
            leaderId: found.leaderId,
            copyMode: found.copyMode,
            copyRatio: found.copyRatio ? parseFloat(found.copyRatio) * 100 : 100,
            fixedAmount: found.fixedAmount ? parseFloat(found.fixedAmount) : undefined,
            maxOrderSize: found.maxOrderSize ? parseFloat(found.maxOrderSize) : undefined,
            minOrderSize: found.minOrderSize ? parseFloat(found.minOrderSize) : undefined,
            maxDailyLoss: found.maxDailyLoss ? parseFloat(found.maxDailyLoss) : undefined,
            maxDailyOrders: found.maxDailyOrders,
            priceTolerance: found.priceTolerance ? parseFloat(found.priceTolerance) : undefined,
            delaySeconds: found.delaySeconds,
            pollIntervalSeconds: found.pollIntervalSeconds,
            useWebSocket: found.useWebSocket,
            websocketReconnectInterval: found.websocketReconnectInterval,
            websocketMaxRetries: found.websocketMaxRetries,
            supportSell: found.supportSell,
            minOrderDepth: found.minOrderDepth ? parseFloat(found.minOrderDepth) : undefined,
            maxSpread: found.maxSpread ? parseFloat(found.maxSpread) : undefined,
            minPrice: found.minPrice ? parseFloat(found.minPrice) : undefined,
            maxPrice: found.maxPrice ? parseFloat(found.maxPrice) : undefined,
            maxPositionValue: found.maxPositionValue ? parseFloat(found.maxPositionValue) : undefined,
            keywordFilterMode: found.keywordFilterMode || 'DISABLED',
            configName: found.configName || '',
            pushFailedOrders: found.pushFailedOrders ?? false,
            pushFilteredOrders: found.pushFilteredOrders ?? false
          })
          // 设置关键字列表
          setKeywords(found.keywords || [])
          
          // 获取 Leader 资产信息
          fetchLeaderAssetInfo(found.leaderId)
        } else {
          message.error(t('copyTradingEdit.fetchFailed') || '跟单配置不存在')
          onClose()
        }
      } else {
        message.error(response.data.msg || t('copyTradingEdit.fetchFailed') || '获取跟单配置失败')
        onClose()
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.fetchFailed') || '获取跟单配置失败')
      onClose()
    } finally {
      setFetching(false)
    }
  }
  
  const handleCopyModeChange = (mode: 'RATIO' | 'FIXED') => {
    setCopyMode(mode)
  }
  
  // 获取 Leader 资产信息
  const fetchLeaderAssetInfo = async (leaderId: number) => {
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
  
  // 添加关键字
  const handleAddKeyword = (e?: React.KeyboardEvent<HTMLInputElement>) => {
    let inputValue = ''
    
    if (e) {
      const target = e.target as HTMLInputElement
      inputValue = target.value.trim()
    } else if (keywordInputRef.current) {
      inputValue = keywordInputRef.current.input?.value?.trim() || ''
    }
    
    if (!inputValue) {
      return
    }
    
    if (keywords.includes(inputValue)) {
      message.warning(t('copyTradingEdit.keywordExists') || t('copyTradingAdd.keywordExists') || '关键字已存在')
      return
    }
    
    const newKeywords = [...keywords, inputValue]
    setKeywords(newKeywords)
    
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
    if (values.copyMode === 'FIXED') {
      if (!values.fixedAmount || Number(values.fixedAmount) < 1) {
        message.error('固定金额必须 >= 1')
        return
      }
    }
    
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error('最小金额必须 >= 1')
      return
    }
    
    if (!copyTradingId) {
      message.error('配置ID不存在')
      return
    }
    
    // 计算市场截止时间（毫秒）
    // 如果用户清空了，传 -1 表示要清空（后端会识别并设置为 null）
    let maxMarketEndDate: number | undefined
    if (maxMarketEndDateValue !== undefined && maxMarketEndDateValue !== null && maxMarketEndDateValue > 0) {
      const multiplier = maxMarketEndDateUnit === 'HOUR' 
        ? 60 * 60 * 1000  // 小时转毫秒
        : 24 * 60 * 60 * 1000  // 天转毫秒
      maxMarketEndDate = maxMarketEndDateValue * multiplier
    } else {
      // 如果值为 null/undefined/0/负数，传 -1 表示要清空
      // 这样无论之前是否有值，清空后都会设置为 null
      maxMarketEndDate = -1
    }
    
    setLoading(true)
    try {
      const request: CopyTradingUpdateRequest = {
        copyTradingId: parseInt(copyTradingId),
        enabled: originalEnabled,
        copyMode: values.copyMode,
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
        supportSell: values.supportSell,
        // 对于可选字段，始终发送（即使为空也发送空字符串，让后端知道要清空）
        minOrderDepth: values.minOrderDepth != null ? values.minOrderDepth.toString() : '',
        maxSpread: values.maxSpread != null ? values.maxSpread.toString() : '',
        minPrice: values.minPrice != null ? values.minPrice.toString() : '',
        maxPrice: values.maxPrice != null ? values.maxPrice.toString() : '',
        maxPositionValue: values.maxPositionValue != null ? values.maxPositionValue.toString() : '',
        keywordFilterMode: values.keywordFilterMode || 'DISABLED',
        keywords: (values.keywordFilterMode === 'WHITELIST' || values.keywordFilterMode === 'BLACKLIST') 
          ? keywords 
          : undefined,
        configName: values.configName?.trim() || undefined,
        pushFailedOrders: values.pushFailedOrders,
        pushFilteredOrders: values.pushFilteredOrders,
        maxMarketEndDate
      }
      
      const response = await apiService.copyTrading.update(request)
      
      if (response.data.code === 0) {
        message.success(t('copyTradingEdit.saveSuccess') || '更新跟单配置成功')
        onClose()
        if (onSuccess) {
          onSuccess()
        }
      } else {
        message.error(response.data.msg || t('copyTradingEdit.saveFailed') || '更新跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.saveFailed') || '更新跟单配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <Modal
      title={t('copyTradingEdit.title') || '编辑跟单配置'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      styles={{ body: { padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' } }}
    >
      {fetching ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <Spin size="large" />
        </div>
      ) : !copyTrading ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <p>{t('copyTradingEdit.fetchFailed') || '跟单配置不存在'}</p>
        </div>
      ) : (
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            keywordFilterMode: 'DISABLED'
          }}
        >
          <Form.Item
            label={t('copyTradingEdit.configName') || '配置名'}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingEdit.configNameRequired') || '请输入配置名' },
              { whitespace: true, message: t('copyTradingEdit.configNameRequired') || '配置名不能为空' }
            ]}
            tooltip={t('copyTradingEdit.configNameTooltip') || '为跟单配置设置一个名称，便于识别和管理'}
          >
            <Input 
              placeholder={t('copyTradingEdit.configNamePlaceholder') || '例如：跟单配置1'} 
              maxLength={255}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectWallet') || t('copyTradingEdit.selectWallet') || '钱包'}
            name="accountId"
          >
            <Select disabled>
              <Option value={copyTrading.accountId}>
                {copyTrading.accountName || `账户 ${copyTrading.accountId}`} ({copyTrading.walletAddress.slice(0, 6)}...{copyTrading.walletAddress.slice(-4)})
              </Option>
            </Select>
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectLeader') || t('copyTradingEdit.selectLeader') || 'Leader'}
            name="leaderId"
          >
            <Select disabled>
              <Option value={copyTrading.leaderId}>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <span>{copyTrading.leaderName || `Leader ${copyTrading.leaderId}`}</span>
                  <span style={{ fontSize: '12px', color: '#999' }}>{copyTrading.leaderAddress}</span>
                </div>
              </Option>
            </Select>
          </Form.Item>
          
          {/* Leader 资产信息 */}
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
            ) : leaderAssetInfo ? (
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
            ) : null}
          </Card>
          
          <Divider>{t('copyTradingEdit.basicConfig') || '基础配置'}</Divider>
     
          <Form.Item
            label={t('copyTradingEdit.copyMode') || '跟单金额模式'}
            name="copyMode"
            tooltip={t('copyTradingEdit.copyModeTooltip') || '选择跟单金额的计算方式'}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingEdit.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('copyTradingEdit.fixedAmountMode') || '固定金额模式'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('copyTradingEdit.copyRatio') || '跟单比例'}
              name="copyRatio"
              tooltip={t('copyTradingEdit.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比'}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                suffix="%"
                placeholder={t('copyTradingEdit.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单）'}
                parser={(value) => {
                  console.log('[EditModal copyRatio parser] 输入值:', value, '类型:', typeof value)
                  // 移除 % 符号和其他非数字字符（保留小数点和负号）
                  const cleaned = (value || '').toString().replace(/%/g, '').trim()
                  console.log('[EditModal copyRatio parser] 清理后:', cleaned)
                  const parsed = parseFloat(cleaned) || 0
                  console.log('[EditModal copyRatio parser] 解析后:', parsed)
                  if (parsed > 10000) {
                    console.log('[EditModal copyRatio parser] 超过最大值，返回 10000')
                    return 10000
                  }
                  if (parsed < 0.01) {
                    console.log('[EditModal copyRatio parser] 小于最小值，返回 0.01')
                    return 0.01
                  }
                  console.log('[EditModal copyRatio parser] 返回:', parsed)
                  return parsed
                }}
                formatter={(value) => {
                  console.log('[EditModal copyRatio formatter] 输入值:', value, '类型:', typeof value)
                  if (!value && value !== 0) {
                    console.log('[EditModal copyRatio formatter] 空值，返回空字符串')
                    return ''
                  }
                  const num = parseFloat(value.toString())
                  console.log('[EditModal copyRatio formatter] 解析后:', num)
                  if (isNaN(num)) {
                    console.log('[EditModal copyRatio formatter] NaN，返回空字符串')
                    return ''
                  }
                  if (num > 10000) {
                    console.log('[EditModal copyRatio formatter] 超过最大值，返回 10000')
                    return '10000'
                  }
                  const result = num.toString().replace(/\.0+$/, '')
                  console.log('[EditModal copyRatio formatter] 格式化后返回:', result)
                  return result
                }}
              />
            </Form.Item>
          )}
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('copyTradingEdit.fixedAmount') || '固定跟单金额 ($)'}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingEdit.fixedAmountRequired') || '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingEdit.invalidNumber') || '请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingEdit.fixedAmountMin') || '固定金额必须 >= 1'))
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
                placeholder={t('copyTradingEdit.fixedAmountPlaceholder') || '固定金额，不随 Leader 订单大小变化，必须 >= 1'}
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
                label={t('copyTradingEdit.maxOrderSize') || '单笔订单最大金额 ($)'}
                name="maxOrderSize"
                tooltip={t('copyTradingEdit.maxOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最大金额上限'}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingEdit.maxOrderSizePlaceholder') || '仅在比例模式下生效（可选）'}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              
              <Form.Item
                label={t('copyTradingEdit.minOrderSize') || '单笔订单最小金额 ($)'}
                name="minOrderSize"
                tooltip={t('copyTradingEdit.minOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最小金额下限，必须 >= 1'}
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('copyTradingEdit.minOrderSizeMin') || '最小金额必须 >= 1'))
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
                  placeholder={t('copyTradingEdit.minOrderSizePlaceholder') || '仅在比例模式下生效，必须 >= 1（可选）'}
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
            label={t('copyTradingEdit.maxDailyLoss') || '每日最大亏损限制 ($)'}
            name="maxDailyLoss"
            tooltip={t('copyTradingEdit.maxDailyLossTooltip') || '限制每日最大亏损金额，用于风险控制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxDailyLossPlaceholder') || '默认 10000 $（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.maxDailyOrders') || '每日最大跟单订单数'}
            name="maxDailyOrders"
            tooltip={t('copyTradingEdit.maxDailyOrdersTooltip') || '限制每日最多跟单的订单数量'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxDailyOrdersPlaceholder') || '默认 100（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.priceTolerance') || '价格容忍度 (%)'}
            name="priceTolerance"
            tooltip={t('copyTradingEdit.priceToleranceTooltip') || '允许跟单价格在 Leader 价格基础上的调整范围'}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.priceTolerancePlaceholder') || '默认 5%（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.delaySeconds') || '跟单延迟 (秒)'}
            name="delaySeconds"
            tooltip={t('copyTradingEdit.delaySecondsTooltip') || '跟单延迟时间，0 表示立即跟单'}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.delaySecondsPlaceholder') || '默认 0（立即跟单）'}
            />
          </Form.Item>
   
          <Form.Item
            label={t('copyTradingEdit.minOrderDepth') || '最小订单深度 ($)'}
            name="minOrderDepth"
            tooltip={t('copyTradingEdit.minOrderDepthTooltip') || '检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.minOrderDepthPlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.maxSpread') || '最大价差（绝对价格）'}
            name="maxSpread"
            tooltip={t('copyTradingEdit.maxSpreadTooltip') || '最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxSpreadPlaceholder') || '例如：0.05（5美分，可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingEdit.priceRangeFilter') || '价格区间过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.priceRange') || '价格区间'}
            name="priceRange"
            tooltip={t('copyTradingEdit.priceRangeTooltip') || '配置价格区间，仅在指定价格区间内的订单才会下单。例如：0.11-0.89 表示区间在0.11和0.89之间；-0.89 表示0.89以下都可以；0.11- 表示0.11以上都可以'}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingEdit.minPricePlaceholder') || '最低价（可选）'}
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
                  placeholder={t('copyTradingEdit.maxPricePlaceholder') || '最高价（可选）'}
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
          
          <Divider>{t('copyTradingEdit.positionLimitFilter') || '最大仓位限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.maxPositionValue') || '最大仓位金额 ($)'}
            name="maxPositionValue"
            tooltip={t('copyTradingEdit.maxPositionValueTooltip') || '限制单个市场的最大仓位金额。如果该市场的当前仓位金额 + 跟单金额超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxPositionValuePlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          {/* 关键字过滤 */}
          <Divider>{t('copyTradingEdit.keywordFilter') || t('copyTradingAdd.keywordFilter') || '关键字过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.keywordFilterMode') || t('copyTradingAdd.keywordFilterMode') || '过滤模式'}
            name="keywordFilterMode"
            tooltip={t('copyTradingEdit.keywordFilterModeTooltip') || t('copyTradingAdd.keywordFilterModeTooltip') || '选择关键字过滤模式。白名单：只跟单包含关键字的市场；黑名单：不跟单包含关键字的市场；不启用：不进行关键字过滤'}
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingEdit.disabled') || t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingEdit.whitelist') || t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingEdit.blacklist') || t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
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
                  <Form.Item label={t('copyTradingEdit.keywords') || t('copyTradingAdd.keywords') || '关键字'}>
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        ref={keywordInputRef}
                        placeholder={t('copyTradingEdit.keywordPlaceholder') || t('copyTradingAdd.keywordPlaceholder') || '输入关键字，按回车添加'}
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
                        ? (t('copyTradingEdit.whitelistTooltip') || t('copyTradingAdd.whitelistTooltip') || '💡 白名单模式：只跟单包含上述任意关键字的市场标题')
                        : (t('copyTradingEdit.blacklistTooltip') || t('copyTradingAdd.blacklistTooltip') || '💡 黑名单模式：不跟单包含上述任意关键字的市场标题')
                      }
                    </div>
                  </Form.Item>
                </>
              )
            }}
          </Form.Item>
          
          {/* 市场截止时间限制 */}
          <Divider>{t('copyTradingEdit.marketEndDateFilter') || '市场截止时间限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.maxMarketEndDate') || '最大市场截止时间'}
            tooltip={t('copyTradingEdit.maxMarketEndDateTooltip') || '仅跟单截止时间小于设定时间的订单。例如：24 小时表示只跟单距离结算还剩24小时以内的市场'}
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
                placeholder={t('copyTradingEdit.maxMarketEndDatePlaceholder') || '输入时间值（可选）'}
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
                placeholder={t('copyTradingEdit.timeUnit') || '单位'}
              >
                <Option value="HOUR">{t('copyTradingEdit.hour') || '小时'}</Option>
                <Option value="DAY">{t('copyTradingEdit.day') || '天'}</Option>
              </Select>
            </Space.Compact>
          </Form.Item>
          
          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ fontSize: 12, color: '#999' }}>
              {t('copyTradingEdit.maxMarketEndDateNote') || '💡 说明：不填写表示不启用此限制'}
            </div>
          </Form.Item>
          
          <Divider>{t('copyTradingEdit.advancedSettings') || '高级设置'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.supportSell') || '跟单卖出'}
            name="supportSell"
            tooltip={t('copyTradingEdit.supportSellTooltip') || '是否跟单 Leader 的卖出订单'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.pushFailedOrders') || '推送失败订单'}
            name="pushFailedOrders"
            tooltip={t('copyTradingEdit.pushFailedOrdersTooltip') || '开启后，失败的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.pushFilteredOrders') || '推送已过滤订单'}
            name="pushFilteredOrders"
            tooltip={t('copyTradingEdit.pushFilteredOrdersTooltip') || '开启后，被过滤的订单会推送到 Telegram'}
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
                {t('copyTradingEdit.save') || '保存'}
              </Button>
              <Button onClick={onClose}>
                {t('common.cancel') || '取消'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      )}
    </Modal>
  )
}

export default EditModal

