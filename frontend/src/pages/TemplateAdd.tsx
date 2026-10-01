import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Input, Button, Radio, InputNumber, Switch, message, Typography, Space, Divider } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { parsePercentInput } from '../utils'
import { useTranslation } from 'react-i18next'

const { Title } = Typography

const TemplateAdd: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  
  const handleSubmit = async (values: any) => {
    // 前端校验：如果填写了 minOrderSize，必须 >= 1
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && values.minOrderSize !== '' && Number(values.minOrderSize) < 1) {
      message.error(t('templateAdd.minOrderSizeError'))
      return
    }
    
    // 前端校验：固定金额模式下，fixedAmount 必填且必须 >= 1
    if (values.copyMode === 'FIXED') {
      const fixedAmount = values.fixedAmount
      if (fixedAmount === undefined || fixedAmount === null || fixedAmount === '') {
        message.error(t('templateAdd.fixedAmountRequired'))
        return
      }
      const amount = Number(fixedAmount)
      if (isNaN(amount)) {
        message.error(t('templateAdd.invalidNumber'))
        return
      }
      if (amount < 1) {
        message.error(t('templateAdd.fixedAmountError'))
        return
      }
    }
    
    setLoading(true)
    try {
      const response = await apiService.templates.create({
        templateName: values.templateName,
        copyMode: values.copyMode || 'RATIO',
        // 将百分比转换为小数：100% -> 1.0
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.copyMode === 'RATIO' ? values.maxOrderSize?.toString() : undefined,
        minOrderSize: values.copyMode === 'RATIO' ? values.minOrderSize?.toString() : undefined,
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        supportSell: values.supportSell !== false,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        pushFilteredOrders: values.pushFilteredOrders ?? false
      })
      
      if (response.data.code === 0) {
        message.success(t('templateAdd.createSuccess'))
        navigate('/templates')
      } else {
        message.error(response.data.msg || t('templateAdd.createFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('templateAdd.createFailed'))
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
          {t('templateAdd.back') || t('common.back')}
        </Button>
      </div>
      
      <Card>
        <Title level={4}>{t('templateAdd.title')}</Title>
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 100, // 默认 100%（显示为百分比）
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyOrders: 100,
            priceTolerance: 5,
            supportSell: true,
            pushFilteredOrders: false
          }}
        >
          <Form.Item
            label={t('templateAdd.templateName')}
            name="templateName"
            tooltip={t('templateAdd.templateNameTooltip')}
            rules={[{ required: true, message: t('templateAdd.templateNameRequired') }]}
          >
            <Input placeholder={t('templateAdd.templateNamePlaceholder')} />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.copyMode')}
            name="copyMode"
            tooltip={t('templateAdd.copyModeTooltip')}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => setCopyMode(e.target.value)}>
              <Radio value="RATIO">{t('templateAdd.ratioMode')}</Radio>
              <Radio value="FIXED">{t('templateAdd.fixedAmountMode')}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('templateAdd.copyRatio')}
              name="copyRatio"
              tooltip={t('templateAdd.copyRatioTooltip')}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                suffix="%"
                placeholder={t('templateAdd.copyRatioPlaceholder')}
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
              label={t('templateAdd.fixedAmount')}
              name="fixedAmount"
              rules={[
                { required: true, message: t('templateAdd.fixedAmountRequired') },
                { 
                  validator: (_, value) => {
                    // required 已经处理了空值情况，这里只处理非空值的校验
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('templateAdd.invalidNumber')))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('templateAdd.fixedAmountError')))
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
                placeholder={t('templateAdd.fixedAmountPlaceholder')}
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
                label={t('templateAdd.maxOrderSize')}
                name="maxOrderSize"
                tooltip={t('templateAdd.maxOrderSizeTooltip')}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('templateAdd.maxOrderSizePlaceholder')}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              
              <Form.Item
                label={t('templateAdd.minOrderSize')}
                name="minOrderSize"
                tooltip={t('templateAdd.minOrderSizeTooltip')}
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve() // 可选字段，允许为空
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('templateAdd.minOrderSizeError')))
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
                  placeholder={t('templateAdd.minOrderSizePlaceholder')}
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
            label={t('templateAdd.maxDailyOrders')}
            name="maxDailyOrders"
            tooltip={t('templateAdd.maxDailyOrdersTooltip')}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.maxDailyOrdersPlaceholder')}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.priceTolerance')}
            name="priceTolerance"
            tooltip={t('templateAdd.priceToleranceTooltip')}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.priceTolerancePlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.minOrderDepth')}
            name="minOrderDepth"
            tooltip={t('templateAdd.minOrderDepthTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.minOrderDepthPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.maxSpread')}
            name="maxSpread"
            tooltip={t('templateAdd.maxSpreadTooltip')}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.maxSpreadPlaceholder')}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('templateAdd.priceRangeFilter')}</Divider>
          
          <Form.Item
            label={t('templateAdd.priceRange')}
            name="priceRange"
            tooltip={t('templateAdd.priceRangeTooltip')}
          >
            <Space.Compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('templateAdd.minPricePlaceholder')}
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
                  placeholder={t('templateAdd.maxPricePlaceholder')}
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
            label={t('templateAdd.supportSell')}
            name="supportSell"
            tooltip={t('templateAdd.supportSellTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.pushFilteredOrders')}
            name="pushFilteredOrders"
            tooltip={t('templateAdd.pushFilteredOrdersTooltip')}
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
                    {t('templateAdd.create')}
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

export default TemplateAdd

