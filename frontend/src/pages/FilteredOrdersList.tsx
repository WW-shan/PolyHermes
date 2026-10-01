import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card, Table, Button, Tag, Select, Space, message, Divider, Spin } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { FilteredOrder, FilteredOrderListResponse } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'

const { Option } = Select

const FilteredOrdersList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [filteredOrders, setFilteredOrders] = useState<FilteredOrder[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit] = useState(20)
  const [filterType, setFilterType] = useState<string | undefined>(undefined)
  
  useEffect(() => {
    if (id) {
      fetchFilteredOrders()
    }
  }, [id, page, filterType])
  
  const fetchFilteredOrders = async () => {
    if (!id) return
    
    setLoading(true)
    try {
      const response = await apiService.copyTrading.getFilteredOrders({
        copyTradingId: parseInt(id),
        filterType: filterType,
        page: page,
        limit: limit
      })
      
      if (response.data.code === 0 && response.data.data) {
        const data: FilteredOrderListResponse = response.data.data
        setFilteredOrders(data.list || [])
        setTotal(data.total || 0)
      } else {
        message.error(response.data.msg || t('filteredOrdersList.fetchFailed'))
      }
    } catch (error: any) {
      console.error('获取被过滤订单列表失败:', error)
      message.error(error.message || t('filteredOrdersList.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  const getFilterTypeTag = (type: string) => {
    const typeMap: Record<string, { color: string; label: string }> = {
      'ORDER_DEPTH': { color: 'orange', label: t('filteredOrdersList.filterTypes.orderDepth') },
      'SPREAD': { color: 'red', label: t('filteredOrdersList.filterTypes.spread') },
      'ORDERBOOK_DEPTH': { color: 'volcano', label: t('filteredOrdersList.filterTypes.orderbookDepth') },
      'PRICE_VALIDITY': { color: 'purple', label: t('filteredOrdersList.filterTypes.priceValidity') },
      'MARKET_STATUS': { color: 'blue', label: t('filteredOrdersList.filterTypes.marketStatus') },
      'ORDERBOOK_ERROR': { color: 'default', label: t('filteredOrdersList.filterTypes.orderbookError') },
      'ORDERBOOK_EMPTY': { color: 'default', label: t('filteredOrdersList.filterTypes.orderbookEmpty') },
      'PRICE_RANGE': { color: 'purple', label: t('filteredOrdersList.filterTypes.priceRange') },
      'MAX_POSITION_VALUE': { color: 'volcano', label: t('filteredOrdersList.filterTypes.maxPositionValue') },
      'MARKET_END_DATE': { color: 'cyan', label: t('filteredOrdersList.filterTypes.marketEndDate') },
      'KEYWORD_FILTER': { color: 'geekblue', label: t('filteredOrdersList.filterTypes.keywordFilter') },
      'UNKNOWN': { color: 'default', label: t('filteredOrdersList.filterTypes.unknown') }
    }
    const config = typeMap[type] || typeMap['UNKNOWN']
    return <Tag color={config.color}>{config.label}</Tag>
  }
  
  const getMarketLink = (order: FilteredOrder) => {
    if (order.marketSlug) {
      return `https://polymarket.com/event/${order.marketSlug}`
    }
    if (order.marketId && order.marketId.startsWith('0x')) {
      return `https://polymarket.com/condition/${order.marketId}`
    }
    return null
  }
  
  const columns = [
    {
      title: t('filteredOrdersList.market'),
      key: 'market',
      width: isMobile ? 150 : 200,
      render: (_: any, record: FilteredOrder) => {
        const link = getMarketLink(record)
        const marketTitle = record.marketTitle || record.marketId.slice(0, 10) + '...'
        return link ? (
          <a href={link} target="_blank" rel="noopener noreferrer" style={{ fontSize: isMobile ? 12 : 14 }}>
            {marketTitle}
          </a>
        ) : (
          <span style={{ fontSize: isMobile ? 12 : 14 }}>{marketTitle}</span>
        )
      }
    },
    {
      title: t('filteredOrdersList.side'),
      key: 'side',
      width: isMobile ? 80 : 100,
      render: (_: any, record: FilteredOrder) => (
        <Tag color={record.side === 'BUY' ? 'green' : 'red'} style={{ fontSize: isMobile ? 11 : 12 }}>
          {record.side === 'BUY' ? (t('order.buy')) : (t('order.sell'))}
        </Tag>
      )
    },
    {
      title: t('filteredOrdersList.outcome'),
      key: 'outcome',
      width: isMobile ? 80 : 100,
      render: (_: any, record: FilteredOrder) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>
          {record.outcome || (record.outcomeIndex !== undefined ? `Index ${record.outcomeIndex}` : '-')}
        </span>
      )
    },
    {
      title: t('filteredOrdersList.price'),
      key: 'price',
      width: isMobile ? 80 : 100,
      render: (_: any, record: FilteredOrder) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{record.price}</span>
      )
    },
    {
      title: t('filteredOrdersList.size'),
      key: 'size',
      width: isMobile ? 80 : 100,
      render: (_: any, record: FilteredOrder) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(record.size)}</span>
      )
    },
    {
      title: t('filteredOrdersList.calculatedQuantity'),
      key: 'calculatedQuantity',
      width: isMobile ? 80 : 100,
      render: (_: any, record: FilteredOrder) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>
          {record.calculatedQuantity ? formatUSDC(record.calculatedQuantity) : '-'}
        </span>
      )
    },
    {
      title: t('filteredOrdersList.filterType'),
      key: 'filterType',
      width: isMobile ? 120 : 150,
      render: (_: any, record: FilteredOrder) => getFilterTypeTag(record.filterType)
    },
    {
      title: t('filteredOrdersList.filterReason'),
      key: 'filterReason',
      width: isMobile ? 150 : 250,
      ellipsis: true,
      render: (_: any, record: FilteredOrder) => (
        <span style={{ fontSize: isMobile ? 11 : 12 }} title={record.filterReason}>
          {record.filterReason}
        </span>
      )
    },
    {
      title: t('filteredOrdersList.createdAt'),
      key: 'createdAt',
      width: isMobile ? 120 : 160,
      render: (_: any, record: FilteredOrder) => {
        const date = new Date(record.createdAt)
        const format = isMobile 
          ? `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
          : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
        return (
          <span style={{ fontSize: isMobile ? 11 : 12 }}>
            {format}
          </span>
        )
      }
    }
  ]
  
  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/copy-trading')}
        >
          {t('common.back')}
        </Button>
      </div>
      
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
          <h3 style={{ margin: 0 }}>{t('filteredOrdersList.title')}</h3>
          <Space>
            <Select
              placeholder={t('filteredOrdersList.filterByType')}
              value={filterType}
              onChange={(value) => {
                setFilterType(value)
                setPage(1)
              }}
              allowClear
              style={{ width: isMobile ? 120 : 150 }}
            >
              <Option value="ORDER_DEPTH">{t('filteredOrdersList.filterTypes.orderDepth')}</Option>
              <Option value="SPREAD">{t('filteredOrdersList.filterTypes.spread')}</Option>
              <Option value="ORDERBOOK_DEPTH">{t('filteredOrdersList.filterTypes.orderbookDepth')}</Option>
              <Option value="PRICE_VALIDITY">{t('filteredOrdersList.filterTypes.priceValidity')}</Option>
              <Option value="MARKET_STATUS">{t('filteredOrdersList.filterTypes.marketStatus')}</Option>
              <Option value="ORDERBOOK_ERROR">{t('filteredOrdersList.filterTypes.orderbookError')}</Option>
              <Option value="ORDERBOOK_EMPTY">{t('filteredOrdersList.filterTypes.orderbookEmpty')}</Option>
              <Option value="PRICE_RANGE">{t('filteredOrdersList.filterTypes.priceRange')}</Option>
            </Select>
          </Space>
        </div>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : filteredOrders.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
                {t('filteredOrdersList.noData')}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {filteredOrders.map((order) => {
                  const date = new Date(order.createdAt)
                  const formattedDate = date.toLocaleString('zh-CN', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit'
                  })
                  const marketLink = getMarketLink(order)
                  const marketTitle = order.marketTitle || order.marketId.slice(0, 10) + '...'
                  
                  return (
                    <Card
                      key={order.id}
                      style={{
                        borderRadius: '12px',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8'
                      }}
                      styles={{ body: { padding: '16px' } }}
                    >
                      {/* 市场信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ 
                          fontSize: '16px', 
                          fontWeight: 'bold', 
                          marginBottom: '8px',
                          color: '#1890ff'
                        }}>
                          {marketLink ? (
                            <a href={marketLink} target="_blank" rel="noopener noreferrer" style={{ color: '#1890ff' }}>
                              {marketTitle}
                            </a>
                          ) : (
                            marketTitle
                          )}
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                          <Tag color={order.side === 'BUY' ? 'green' : 'red'}>
                            {order.side === 'BUY' ? (t('order.buy')) : (t('order.sell'))}
                          </Tag>
                          {getFilterTypeTag(order.filterType)}
                        </div>
                      </div>
                      
                      <Divider style={{ margin: '12px 0' }} />
                      
                      {/* 订单详情 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>
                          {t('filteredOrdersList.outcome')}
                        </div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {order.outcome || (order.outcomeIndex !== undefined ? `Index ${order.outcomeIndex}` : '-')}
                        </div>
                      </div>
                      
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>
                          {t('filteredOrdersList.price')}
                        </div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {order.price}
                        </div>
                      </div>
                      
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>
                          {t('filteredOrdersList.size')}
                        </div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {formatUSDC(order.size)}
                        </div>
                      </div>
                      
                      {order.calculatedQuantity && (
                        <div style={{ marginBottom: '12px' }}>
                          <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>
                            {t('filteredOrdersList.calculatedQuantity')}
                          </div>
                          <div style={{ fontSize: '14px', fontWeight: '500' }}>
                            {formatUSDC(order.calculatedQuantity)}
                          </div>
                        </div>
                      )}
                      
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>
                          {t('filteredOrdersList.filterReason')}
                        </div>
                        <div style={{ fontSize: '13px', color: '#333', wordBreak: 'break-word' }}>
                          {order.filterReason}
                        </div>
                      </div>
                      
                      {/* 时间 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#999' }}>
                          {t('filteredOrdersList.createdAt')}: {formattedDate}
                        </div>
                      </div>
                    </Card>
                  )
                })}
              </div>
            )}
            
            {/* 移动端分页 */}
            {filteredOrders.length > 0 && (
              <div style={{ 
                marginTop: '16px', 
                display: 'flex', 
                justifyContent: 'space-between', 
                alignItems: 'center',
                flexWrap: 'wrap',
                gap: '8px'
              }}>
                <div style={{ fontSize: '14px', color: '#666' }}>
                  {t('common.total')} {total} {t('common.items')}
                </div>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <Button
                    size="small"
                    disabled={page === 1}
                    onClick={() => setPage(page - 1)}
                  >
                    {t('common.prev')}
                  </Button>
                  <span style={{ lineHeight: '32px', fontSize: '14px' }}>
                    {page} / {Math.ceil(total / limit)}
                  </span>
                  <Button
                    size="small"
                    disabled={page >= Math.ceil(total / limit)}
                    onClick={() => setPage(page + 1)}
                  >
                    {t('common.next')}
                  </Button>
                </div>
              </div>
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={filteredOrders}
            rowKey="id"
            loading={loading}
            pagination={{
              current: page,
              pageSize: limit,
              total: total,
              showSizeChanger: false,
              showTotal: (total) => t('common.total') + `: ${total}`,
              onChange: (page) => setPage(page)
            }}
            scroll={{ x: 'auto' }}
            size="middle"
          />
        )}
      </Card>
    </div>
  )
}

export default FilteredOrdersList

