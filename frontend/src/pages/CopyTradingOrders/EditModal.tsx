import React, { useEffect, useState, useRef } from 'react'
import { Modal, Form, Button, message, Radio, InputNumber, Divider, Spin, Select, Input, Space, Switch, Tag, InputRef, Card, Row, Col, Statistic } from 'antd'
import { SaveOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import type { CopyTrading, CopyTradingUpdateRequest } from '../../types'
import { useTranslation } from 'react-i18next'
import { formatUSDC, parsePercentInput } from '../../utils'

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
          message.error(t('copyTradingEdit.fetchFailed'))
          onClose()
        }
      } else {
        message.error(response.data.msg || t('copyTradingEdit.fetchFailed'))
        onClose()
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.fetchFailed'))
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
        message.error(response.data.msg || t('copyTradingAdd.fetchAssetInfoFailed'))
      }
    } catch (error: any) {
      console.error('获取 Leader 资产失败:', error)
      message.error(error.message || t('copyTradingAdd.fetchAssetInfoFailed'))
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
      message.warning(t('copyTradingEdit.keywordExists') || t('copyTradingAdd.keywordExists'))
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
        message.error(t('copyTradingEdit.fixedAmountMin'))
        return
      }
    }

    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error(t('copyTradingEdit.minOrderSizeMin'))
      return
    }

    if (!copyTradingId) {
      message.error(t('copyTradingEdit.configIdMissing'))
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
        // 不回写打开弹窗时的 enabled（期间可能已在列表中启停），启停只通过 update-status 修改
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
        message.success(t('copyTradingEdit.saveSuccess'))
        onClose()
        if (onSuccess) {
          onSuccess()
        }
      } else {
        message.error(response.data.msg || t('copyTradingEdit.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.saveFailed'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title={t('copyTradingEdit.title')}
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
          <p>{t('copyTradingEdit.fetchFailed')}</p>
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
            label={t('copyTradingEdit.configName')}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingEdit.configNameRequired') },
              { whitespace: true, message: t('copyTradingEdit.configNameRequired') }
            ]}
            tooltip={t('copyTradingEdit.configNameTooltip')}
          >
            <Input
              placeholder={t('copyTradingEdit.configNamePlaceholder')}
              maxLength={255}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.selectWallet') || t('copyTradingEdit.selectWallet')}
            name="accountId"
          >
            <Select disabled>
              <Option value={copyTrading.accountId}>
                {copyTrading.accountName || t('positionList.accountFallback', { id: copyTrading.accountId })} ({copyTrading.walletAddress.slice(0, 6)}...{copyTrading.walletAddress.slice(-4)})
              </Option>
            </Select>
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.selectLeader') || t('copyTradingEdit.selectLeader')}
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
            ) : leaderAssetInfo ? (
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
            ) : null}
          </Card>

          <Divider>{t('copyTradingEdit.basicConfig')}</Divider>

          <Form.Item
            label={t('copyTradingEdit.copyMode')}
            name="copyMode"
            tooltip={t('copyTradingEdit.copyModeTooltip')}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingEdit.ratioMode')}</Radio>
              <Radio value="FIXED">{t('copyTradingEdit.fixedAmountMode')}</Radio>
            </Radio.Group>
          </Form.Item>

          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('copyTradingEdit.copyRatio')}
              name="copyRatio"
              tooltip={t('copyTradingEdit.copyRatioTooltip')}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                suffix="%"
                placeholder={t('copyTradingEdit.copyRatioPlaceholder')}
                parser={(value) => parsePercentInput(value)}
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
              label={t('copyTradingEdit.fixedAmount')}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingEdit.fixedAmountRequired') },
                {
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingEdit.invalidNumber')))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingEdit.fixedAmountMin')))
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
                placeholder={t('copyTradingEdit.fixedAmountPlaceholder')}
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
                label={t('copyTradingEdit.maxOrderSize')}
                name="maxOrderSize"
                tooltip={t('copyTradingEdit.maxOrderSizeTooltip')}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingEdit.maxOrderSizePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>

              <Form.Item
                label={t('copyTradingEdit.minOrderSize')}
                name="minOrderSize"
                tooltip={t('copyTradingEdit.minOrderSizeTooltip')}
                rules={[
                  {
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('copyTradingEdit.minOrderSizeMin')))
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
                  placeholder={t('copyTradingEdit.minOrderSizePlaceholder')}
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
            label={t('copyTradingEdit.maxDailyLoss')}
            name="maxDailyLoss"
            tooltip={t('copyTradingEdit.maxDailyLossTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxDailyLossPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.maxDailyOrders')}
            name="maxDailyOrders"
            tooltip={t('copyTradingEdit.maxDailyOrdersTooltip')}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxDailyOrdersPlaceholder')}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.priceTolerance')}
            name="priceTolerance"
            tooltip={t('copyTradingEdit.priceToleranceTooltip')}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.priceTolerancePlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.delaySeconds')}
            name="delaySeconds"
            tooltip={t('copyTradingEdit.delaySecondsTooltip')}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.delaySecondsPlaceholder')}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.minOrderDepth')}
            name="minOrderDepth"
            tooltip={t('copyTradingEdit.minOrderDepthTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.minOrderDepthPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.maxSpread')}
            name="maxSpread"
            tooltip={t('copyTradingEdit.maxSpreadTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxSpreadPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Divider>{t('copyTradingEdit.priceRangeFilter')}</Divider>

          <Form.Item
            label={t('copyTradingEdit.priceRange')}
            name="priceRange"
            tooltip={t('copyTradingEdit.priceRangeTooltip')}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingEdit.minPricePlaceholder')}
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
                  placeholder={t('copyTradingEdit.maxPricePlaceholder')}
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

          <Divider>{t('copyTradingEdit.positionLimitFilter')}</Divider>

          <Form.Item
            label={t('copyTradingEdit.maxPositionValue')}
            name="maxPositionValue"
            tooltip={t('copyTradingEdit.maxPositionValueTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxPositionValuePlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          {/* 关键字过滤 */}
          <Divider>{t('copyTradingEdit.keywordFilter') || t('copyTradingAdd.keywordFilter')}</Divider>

          <Form.Item
            label={t('copyTradingEdit.keywordFilterMode') || t('copyTradingAdd.keywordFilterMode')}
            name="keywordFilterMode"
            tooltip={t('copyTradingEdit.keywordFilterModeTooltip') || t('copyTradingAdd.keywordFilterModeTooltip')}
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingEdit.disabled') || t('copyTradingAdd.disabled')}</Radio>
              <Radio value="WHITELIST">{t('copyTradingEdit.whitelist') || t('copyTradingAdd.whitelist')}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingEdit.blacklist') || t('copyTradingAdd.blacklist')}</Radio>
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
                  <Form.Item label={t('copyTradingEdit.keywords') || t('copyTradingAdd.keywords')}>
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        ref={keywordInputRef}
                        placeholder={t('copyTradingEdit.keywordPlaceholder') || t('copyTradingAdd.keywordPlaceholder')}
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
                        ? (t('copyTradingEdit.whitelistTooltip') || t('copyTradingAdd.whitelistTooltip'))
                        : (t('copyTradingEdit.blacklistTooltip') || t('copyTradingAdd.blacklistTooltip'))
                      }
                    </div>
                  </Form.Item>
                </>
              )
            }}
          </Form.Item>

          {/* 市场截止时间限制 */}
          <Divider>{t('copyTradingEdit.marketEndDateFilter')}</Divider>

          <Form.Item
            label={t('copyTradingEdit.maxMarketEndDate')}
            tooltip={t('copyTradingEdit.maxMarketEndDateTooltip')}
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
                placeholder={t('copyTradingEdit.maxMarketEndDatePlaceholder')}
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
                placeholder={t('copyTradingEdit.timeUnit')}
              >
                <Option value="HOUR">{t('copyTradingEdit.hour')}</Option>
                <Option value="DAY">{t('copyTradingEdit.day')}</Option>
              </Select>
            </Space.Compact>
          </Form.Item>

          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ fontSize: 12, color: '#999' }}>
              {t('copyTradingEdit.maxMarketEndDateNote')}
            </div>
          </Form.Item>

          <Divider>{t('copyTradingEdit.advancedSettings')}</Divider>

          <Form.Item
            label={t('copyTradingEdit.supportSell')}
            name="supportSell"
            tooltip={t('copyTradingEdit.supportSellTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.pushFailedOrders')}
            name="pushFailedOrders"
            tooltip={t('copyTradingEdit.pushFailedOrdersTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.pushFilteredOrders')}
            name="pushFilteredOrders"
            tooltip={t('copyTradingEdit.pushFilteredOrdersTooltip')}
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
                {t('copyTradingEdit.save')}
              </Button>
              <Button onClick={onClose}>
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      )}
    </Modal>
  )
}

export default EditModal

