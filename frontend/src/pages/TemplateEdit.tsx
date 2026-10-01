import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card, Form, Input, Button, Radio, InputNumber, Switch, message, Typography, Space, Divider } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { parsePercentInput } from '../utils'
import type { CopyTradingTemplate } from '../types'
import { useTranslation } from 'react-i18next'

const { Title } = Typography

const TemplateEdit: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  
  useEffect(() => {
    if (id) {
      fetchTemplate(parseInt(id))
    }
  }, [id])
  
  const fetchTemplate = async (templateId: number) => {
    setFetching(true)
    try {
      const response = await apiService.templates.detail({ templateId })
      if (response.data.code === 0 && response.data.data) {
        const template: CopyTradingTemplate = response.data.data
        setCopyMode(template.copyMode)
        form.setFieldsValue({
          ...template,
          // 将小数转换为百分比：1.0 -> 100%
          copyRatio: template.copyRatio ? parseFloat(template.copyRatio) * 100 : 100,
          fixedAmount: template.fixedAmount ? parseFloat(template.fixedAmount) : undefined,
          maxOrderSize: template.maxOrderSize ? parseFloat(template.maxOrderSize) : undefined,
          minOrderSize: template.minOrderSize ? parseFloat(template.minOrderSize) : undefined,
          priceTolerance: parseFloat(template.priceTolerance),
          minOrderDepth: template.minOrderDepth ? parseFloat(template.minOrderDepth) : undefined,
          maxSpread: template.maxSpread ? parseFloat(template.maxSpread) : undefined,
          minPrice: template.minPrice ? parseFloat(template.minPrice) : undefined,
          maxPrice: template.maxPrice ? parseFloat(template.maxPrice) : undefined,
          pushFilteredOrders: template.pushFilteredOrders ?? false
        })
      } else {
        message.error(response.data.msg || t('templateEdit.fetchFailed'))
        navigate('/templates')
      }
    } catch (error: any) {
      message.error(error.message || t('templateEdit.fetchFailed'))
      navigate('/templates')
    } finally {
      setFetching(false)
    }
  }
  
  const handleSubmit = async (values: any) => {
    if (!id) return
    
    // 前端校验：如果填写了 minOrderSize，必须 >= 1
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && values.minOrderSize !== '' && Number(values.minOrderSize) < 1) {
      message.error(t('templateEdit.minOrderSizeError'))
      return
    }
    
    // 前端校验：固定金额模式下，fixedAmount 必填且必须 >= 1
    if (values.copyMode === 'FIXED') {
      const fixedAmount = values.fixedAmount
      if (fixedAmount === undefined || fixedAmount === null || fixedAmount === '') {
        message.error(t('templateEdit.fixedAmountRequired'))
        return
      }
      const amount = Number(fixedAmount)
      if (isNaN(amount)) {
        message.error(t('templateEdit.invalidNumber'))
        return
      }
      if (amount < 1) {
        message.error(t('templateEdit.fixedAmountError'))
        return
      }
    }
    
    setLoading(true)
    try {
      const response = await apiService.templates.update({
        templateId: parseInt(id),
        templateName: values.templateName,
        copyMode: values.copyMode,
        // 将百分比转换为小数：100% -> 1.0
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.copyMode === 'RATIO' ? values.maxOrderSize?.toString() : undefined,
        minOrderSize: values.copyMode === 'RATIO' ? values.minOrderSize?.toString() : undefined,
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        supportSell: values.supportSell,
        // 可选字段清空时发送空字符串（后端识别为清空），不能发送 undefined（会被视为不修改）
        minOrderDepth: values.minOrderDepth != null ? values.minOrderDepth.toString() : '',
        maxSpread: values.maxSpread != null ? values.maxSpread.toString() : '',
        minPrice: values.minPrice != null ? values.minPrice.toString() : '',
        maxPrice: values.maxPrice != null ? values.maxPrice.toString() : '',
        pushFilteredOrders: values.pushFilteredOrders
      })
      
      if (response.data.code === 0) {
        message.success(t('templateEdit.saveSuccess'))
        navigate('/templates')
      } else {
        message.error(response.data.msg || t('templateEdit.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('templateEdit.saveFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/templates')}
        >
          {t('templateEdit.back') || t('common.back')}
        </Button>
      </div>
      
      <Card loading={fetching}>
        <Title level={4}>{t('templateEdit.title')}</Title>
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
        >
          <Form.Item
            label={t('templateEdit.templateName')}
            name="templateName"
            tooltip={t('templateEdit.templateNameTooltip')}
            rules={[{ required: true, message: t('templateEdit.templateNameRequired') }]}
          >
            <Input placeholder={t('templateEdit.templateNamePlaceholder')} />
          </Form.Item>
          
          <Form.Item
            label={t('templateEdit.copyMode')}
            name="copyMode"
            tooltip={t('templateEdit.copyModeTooltip')}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => setCopyMode(e.target.value)}>
              <Radio value="RATIO">{t('templateEdit.ratioMode')}</Radio>
              <Radio value="FIXED">{t('templateEdit.fixedAmountMode')}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('templateEdit.copyRatio')}
              name="copyRatio"
              tooltip={t('templateEdit.copyRatioTooltip')}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                suffix="%"
                placeholder={t('templateEdit.copyRatioPlaceholder')}
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
              label={t('templateEdit.fixedAmount')}
              name="fixedAmount"
              tooltip={t('templateEdit.fixedAmountTooltip')}
              rules={[
                { required: true, message: t('templateEdit.fixedAmountRequired') },
                { 
                  validator: (_, value) => {
                    // required 已经处理了空值情况，这里只处理非空值的校验
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('templateEdit.invalidNumber')))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('templateEdit.fixedAmountError')))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder={t('templateEdit.fixedAmountPlaceholder')}
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
                label={t('templateEdit.maxOrderSize')}
                name="maxOrderSize"
                tooltip={t('templateEdit.maxOrderSizeTooltip')}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('templateEdit.maxOrderSizePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              
              <Form.Item
                label={t('templateEdit.minOrderSize')}
                name="minOrderSize"
                tooltip={t('templateEdit.minOrderSizeTooltip')}
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve() // 可选字段，允许为空
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('templateEdit.minOrderSizeError')))
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
                  placeholder={t('templateEdit.minOrderSizePlaceholder')}
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
            label={t('templateEdit.maxDailyOrders')}
            name="maxDailyOrders"
            tooltip={t('templateEdit.maxDailyOrdersTooltip')}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('templateEdit.maxDailyOrdersPlaceholder')}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateEdit.priceTolerance')}
            name="priceTolerance"
            tooltip={t('templateEdit.priceToleranceTooltip')}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('templateEdit.priceTolerancePlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateEdit.minOrderDepth')}
            name="minOrderDepth"
            tooltip={t('templateEdit.minOrderDepthTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('templateEdit.minOrderDepthPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateEdit.maxSpread')}
            name="maxSpread"
            tooltip={t('templateEdit.maxSpreadTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('templateEdit.maxSpreadPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('templateEdit.priceRangeFilter')}</Divider>
          
          <Form.Item
            label={t('templateEdit.priceRange')}
            name="priceRange"
            tooltip={t('templateEdit.priceRangeTooltip')}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('templateEdit.minPricePlaceholder')}
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
                  placeholder={t('templateEdit.maxPricePlaceholder')}
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
          
          {/* 跟单卖出 - 表单最底部 */}
          <Form.Item
            label={t('templateEdit.supportSell')}
            name="supportSell"
            tooltip={t('templateEdit.supportSellTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('templateEdit.pushFilteredOrders')}
            name="pushFilteredOrders"
            tooltip={t('templateEdit.pushFilteredOrdersTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item shouldUpdate>
            {({ getFieldsError }) => {
              const errors = getFieldsError()
              const hasErrors = errors.some(({ errors }) => errors && errors.length > 0)
              return (
                <Space>
                  <Button
                    type="primary"
                    htmlType="submit"
                    icon={<SaveOutlined />}
                    loading={loading}
                    disabled={hasErrors}
                  >
                    {t('templateEdit.save')}
                  </Button>
                  <Button onClick={() => navigate('/templates')}>
                    {t('common.cancel')}
                  </Button>
                </Space>
              )
            }}
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default TemplateEdit

