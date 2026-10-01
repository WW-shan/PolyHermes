import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Input, Modal, Form, Radio, InputNumber, Switch, Divider, Spin, Empty, List, Tooltip } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, CopyOutlined, SearchOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { CopyTradingTemplate } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC, parsePercentInput } from '../utils'

const TemplateList: React.FC = () => {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [copyModalVisible, setCopyModalVisible] = useState(false)
  const [copyForm] = Form.useForm()
  const [copyLoading, setCopyLoading] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  const [_sourceTemplate, setSourceTemplate] = useState<CopyTradingTemplate | null>(null) // 用于跟踪复制的源模板
  
  useEffect(() => {
    fetchTemplates()
  }, [])
  
  const fetchTemplates = async () => {
    setLoading(true)
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      } else {
        message.error(response.data.msg || t('templateList.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('templateList.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  const handleDelete = async (templateId: number) => {
    try {
      const response = await apiService.templates.delete({ templateId })
      if (response.data.code === 0) {
        message.success(t('templateList.deleteSuccess'))
        fetchTemplates()
      } else {
        message.error(response.data.msg || t('templateList.deleteFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('templateList.deleteFailed'))
    }
  }
  
  const handleCopy = (template: CopyTradingTemplate) => {
    setSourceTemplate(template)
    setCopyMode(template.copyMode)
    
    // 填充表单数据
    copyForm.setFieldsValue({
      templateName: `${template.templateName}-${t('templateList.copySuffix')}`,
      copyMode: template.copyMode,
      copyRatio: template.copyRatio ? parseFloat(template.copyRatio) * 100 : 100,
      fixedAmount: template.fixedAmount ? parseFloat(template.fixedAmount) : undefined,
      maxOrderSize: template.maxOrderSize ? parseFloat(template.maxOrderSize) : undefined,
      minOrderSize: template.minOrderSize ? parseFloat(template.minOrderSize) : undefined,
      maxDailyOrders: template.maxDailyOrders,
      priceTolerance: parseFloat(template.priceTolerance),
      supportSell: template.supportSell,
      pushFilteredOrders: template.pushFilteredOrders ?? false,
      minOrderDepth: template.minOrderDepth ? parseFloat(template.minOrderDepth) : undefined,
      maxSpread: template.maxSpread ? parseFloat(template.maxSpread) : undefined,
      minPrice: template.minPrice ? parseFloat(template.minPrice) : undefined,
      maxPrice: template.maxPrice ? parseFloat(template.maxPrice) : undefined
    })
    
    setCopyModalVisible(true)
  }
  
  const handleCopySubmit = async (values: any) => {
    // 前端校验：如果填写了 minOrderSize，必须 >= 1
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && values.minOrderSize !== '' && Number(values.minOrderSize) < 1) {
      message.error(t('templateList.minAmountError'))
      return
    }
    
    // 前端校验：固定金额模式下，fixedAmount 必填且必须 >= 1
    if (values.copyMode === 'FIXED') {
      const fixedAmount = values.fixedAmount
      if (fixedAmount === undefined || fixedAmount === null || fixedAmount === '') {
        message.error(t('templateList.fixedAmountRequired'))
        return
      }
      const amount = Number(fixedAmount)
      if (isNaN(amount)) {
        message.error(t('templateList.invalidNumber'))
        return
      }
      if (amount < 1) {
        message.error(t('templateList.fixedAmountError'))
        return
      }
    }
    
    setCopyLoading(true)
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
        message.success(t('templateList.copySuccess'))
        setCopyModalVisible(false)
        copyForm.resetFields()
        fetchTemplates()
      } else {
        message.error(response.data.msg || t('templateList.copyFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('templateList.copyFailed'))
    } finally {
      setCopyLoading(false)
    }
  }
  
  const handleCopyCancel = () => {
    setCopyModalVisible(false)
    copyForm.resetFields()
    setSourceTemplate(null)
  }
  
  const filteredTemplates = templates.filter(template =>
    template.templateName.toLowerCase().includes(searchText.toLowerCase())
  )
  
  const columns = [
    {
      title: t('templateList.templateName'),
      dataIndex: 'templateName',
      key: 'templateName',
      render: (text: string) => <strong>{text}</strong>
    },
    {
      title: t('templateList.copyMode'),
      dataIndex: 'copyMode',
      key: 'copyMode',
      render: (mode: string) => (
        <Tag color={mode === 'RATIO' ? 'blue' : 'green'}>
          {mode === 'RATIO' ? t('templateList.ratio') : t('templateList.fixedAmount')}
        </Tag>
      )
    },
    {
      title: t('templateList.copyConfig'),
      key: 'copyConfig',
      render: (_: any, record: CopyTradingTemplate) => {
        if (record.copyMode === 'RATIO') {
          return `${t('templateList.ratio')} ${record.copyRatio}x`
        } else if (record.copyMode === 'FIXED' && record.fixedAmount) {
          return `$${formatUSDC(record.fixedAmount)}`
        }
        return '-'
      }
    },
    {
      title: t('templateList.supportSell'),
      dataIndex: 'supportSell',
      key: 'supportSell',
      render: (support: boolean) => (
        <Tag color={support ? 'green' : 'red'}>
          {support ? t('common.yes') : t('common.no')}
        </Tag>
      )
    },
    {
      title: t('common.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (timestamp: number) => {
        const date = new Date(timestamp)
        return date.toLocaleString(i18n.language || 'zh-CN', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit'
        })
      },
      sorter: (a: CopyTradingTemplate, b: CopyTradingTemplate) => a.createdAt - b.createdAt,
      defaultSortOrder: 'descend' as const
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: isMobile ? 120 : 120,
      fixed: 'right' as const,
      render: (_: any, record: CopyTradingTemplate) => (
        <Space size={4}>
          <Tooltip title={t('common.edit')}>
            <div
              onClick={() => navigate(`/templates/edit/${record.id}`)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <EditOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Tooltip title={t('templateList.copy')}>
            <div
              onClick={() => handleCopy(record)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <CopyOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Popconfirm
            title={t('templateList.deleteConfirm')}
            description={t('templateList.deleteConfirmDesc')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#fff1f0'}
                onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
              >
                <DeleteOutlined style={{ fontSize: '16px', color: '#ff4d4f' }} />
              </div>
            </Tooltip>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <h2 style={{ margin: 0, fontSize: isMobile ? '20px' : '24px' }}>{t('templateList.title')}</h2>
        <Space size={8}>
          <Input
            placeholder={t('templateList.searchPlaceholder')}
            allowClear
            style={{ width: isMobile ? 120 : 200 }}
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            suffix={<SearchOutlined />}
          />
          <Tooltip title={t('templateList.addTemplate')}>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/templates/add')}
              size={isMobile ? 'middle' : 'large'}
              style={{ borderRadius: '8px', height: isMobile ? '40px' : '48px', fontSize: isMobile ? '14px' : '16px' }}
            />
          </Tooltip>
        </Space>
      </div>

      <Card style={{ borderRadius: '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', border: '1px solid #e8e8e8' }} styles={{ body: { padding: isMobile ? '12px' : '24px' } }}>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : filteredTemplates.length === 0 ? (
              <Empty description={t('templateList.noData')} />
            ) : (
              <List
                dataSource={filteredTemplates}
                renderItem={(template) => {
                  return (
                    <Card
                      key={template.id}
                      style={{
                        marginBottom: '10px',
                        borderRadius: '10px',
                        boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8',
                        overflow: 'hidden'
                      }}
                      styles={{ body: { padding: '0' } }}
                    >
                      {/* 头部区域 - 模板名称 */}
                      <div style={{
                        padding: '10px 12px',
                        background: 'var(--ant-color-primary, #1677ff)',
                        color: '#fff'
                      }}>
                        <div style={{ fontSize: '15px', fontWeight: '600', marginBottom: '2px' }}>
                          {template.templateName}
                        </div>
                        <div style={{ fontSize: '12px', opacity: '0.9' }}>
                          {template.copyMode === 'RATIO' 
                            ? `${t('templateList.ratioMode')} ${(parseFloat(template.copyRatio || '0') * 100).toFixed(0).replace(/\.0+$/, '')}%`
                            : `$${formatUSDC(template.fixedAmount || '0')}`
                          }
                        </div>
                      </div>

                      {/* 配置信息区域 */}
                      <div style={{
                        padding: '8px 12px',
                        backgroundColor: '#fafafa',
                        borderBottom: '1px solid #f0f0f0',
                        minHeight: '42px',
                        display: 'flex',
                        alignItems: 'center'
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                          <div>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              {t('templateList.supportSell')}
                            </div>
                            <div style={{ fontSize: '12px', fontWeight: '500' }}>
                              <Tag color={template.supportSell ? 'green' : 'red'} style={{ margin: 0, fontSize: '10px' }}>
                                {template.supportSell ? (t('common.yes')) : (t('common.no'))}
                              </Tag>
                            </div>
                          </div>
                          <div style={{ textAlign: 'right' }}>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              {t('templateList.maxDailyOrders')}
                            </div>
                            <div style={{ fontSize: '12px', fontWeight: '500', color: '#1890ff' }}>
                              {template.maxDailyOrders} {t('common.orders')}
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* 金额限制区域（仅比例模式显示） */}
                      {template.copyMode === 'RATIO' && (
                        <div style={{
                          padding: '6px 12px',
                          fontSize: '11px',
                          color: '#8c8c8c',
                          borderBottom: '1px solid #f0f0f0'
                        }}>
                          <span style={{ color: '#d48806' }}>{t('templateList.amountLimit')}: </span>
                          {template.maxOrderSize && (
                            <span>{t('templateList.max')} ${formatUSDC(template.maxOrderSize)}</span>
                          )}
                          {template.maxOrderSize && template.minOrderSize && <span> | </span>}
                          {template.minOrderSize && (
                            <span>{t('templateList.min')} ${formatUSDC(template.minOrderSize)}</span>
                          )}
                          {!template.maxOrderSize && !template.minOrderSize && <span style={{ color: '#bfbfbf' }}>{t('templateList.notSet')}</span>}
                        </div>
                      )}

                      {/* 创建时间 */}
                      <div style={{
                        padding: '6px 12px',
                        fontSize: '11px',
                        color: '#8c8c8c'
                      }}>
                        {t('common.createdAt')}: {new Date(template.createdAt).toLocaleString(i18n.language || 'zh-CN', {
                          year: 'numeric',
                          month: '2-digit',
                          day: '2-digit',
                          hour: '2-digit',
                          minute: '2-digit'
                        })}
                      </div>

                      {/* 图标操作栏 */}
                      <div style={{
                        padding: '8px 12px',
                        display: 'flex',
                        justifyContent: 'space-around',
                        alignItems: 'center'
                      }}>
                        <Tooltip title={t('common.edit')}>
                          <div
                            onClick={() => navigate(`/templates/edit/${template.id}`)}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <EditOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.edit')}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('templateList.copy')}>
                          <div
                            onClick={() => handleCopy(template)}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <CopyOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('templateList.copy')}</span>
                          </div>
                        </Tooltip>

                        <Popconfirm
                          title={t('templateList.deleteConfirm')}
                          description={t('templateList.deleteConfirmDesc')}
                          onConfirm={() => handleDelete(template.id)}
                          okText={t('common.confirm')}
                          cancelText={t('common.cancel')}
                        >
                          <Tooltip title={t('common.delete')}>
                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                              <DeleteOutlined style={{ fontSize: '18px', color: '#ff4d4f' }} />
                              <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.delete')}</span>
                            </div>
                          </Tooltip>
                        </Popconfirm>
                      </div>
                    </Card>
                  )
                }}
              />
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={filteredTemplates}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => t('templateList.totalCount', { total })
            }}
          />
        )}
      </Card>
      
      <Modal
        title={t('templateList.copyTemplateTitle')}
        open={copyModalVisible}
        onCancel={handleCopyCancel}
        footer={null}
        width={isMobile ? '90%' : 800}
        destroyOnHidden
      >
        <Form
          form={copyForm}
          layout="vertical"
          onFinish={handleCopySubmit}
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
            <Radio.Group disabled>
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
                  min={0.01}
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
                        return Promise.resolve()
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
            label={t('templateAdd.supportSell')}
            name="supportSell"
            tooltip={t('templateAdd.supportSellTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('templateList.pushFilteredOrders')}
            name="pushFilteredOrders"
            tooltip={t('templateList.pushFilteredOrdersTooltip')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Divider>{t('templateList.filterConditions')}</Divider>
          
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
          
          <Form.Item shouldUpdate>
            {({ getFieldsError }) => {
              const errors = getFieldsError()
              const hasErrors = errors.some(({ errors }) => errors && errors.length > 0)
              return (
                <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
                  <Button onClick={handleCopyCancel}>
                    {t('common.cancel')}
                  </Button>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={copyLoading}
                    disabled={hasErrors}
                  >
                    {t('templateAdd.create')}
                  </Button>
                </Space>
              )
            }}
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default TemplateList
