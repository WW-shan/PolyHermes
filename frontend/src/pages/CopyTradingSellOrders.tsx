import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Table, Button, Tag, Select, Input, message, Divider, Spin } from 'antd'
import { LeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { formatUSDC } from '../utils'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { SellOrderInfo, OrderTrackingRequest, OrderTrackingListResponse } from '../types'

const { Option } = Select

const CopyTradingSellOrdersPage: React.FC = () => {
  const { t } = useTranslation()
  const { copyTradingId } = useParams<{ copyTradingId: string }>()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [orders, setOrders] = useState<SellOrderInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit, setLimit] = useState(20)
  const [filters, setFilters] = useState<{
    marketId?: string
    side?: string
  }>({})
  
  useEffect(() => {
    if (copyTradingId) {
      fetchOrders()
    }
  }, [copyTradingId, page, limit, filters])
  
  const fetchOrders = async () => {
    if (!copyTradingId) return
    
    setLoading(true)
    try {
      const request: OrderTrackingRequest = {
        copyTradingId: parseInt(copyTradingId),
        type: 'sell',
        page,
        limit,
        ...filters
      }
      
      const response = await apiService.orderTracking.list(request)
      if (response.data.code === 0 && response.data.data) {
        const data = response.data.data as OrderTrackingListResponse
        setOrders((data.list || []) as SellOrderInfo[])
        setTotal(data.total || 0)
      } else {
        message.error(response.data.msg || t('ordersPage.fetchSellFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('ordersPage.fetchSellFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }
  
  const columns = [
    {
      title: t('ordersPage.orderId'),
      dataIndex: 'orderId',
      key: 'orderId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: t('ordersPage.leaderTradeId'),
      dataIndex: 'leaderTradeId',
      key: 'leaderTradeId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: t('ordersPage.market'),
      dataIndex: 'marketId',
      key: 'marketId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: t('ordersPage.side'),
      dataIndex: 'side',
      key: 'side',
      width: isMobile ? 60 : 80,
      render: (side: string) => {
        // 将0/1转换为YES/NO
        const displaySide = side === '0' ? 'YES' : side === '1' ? 'NO' : side
        return <Tag style={{ fontSize: isMobile ? 11 : 12 }}>{displaySide}</Tag>
      }
    },
    {
      title: t('ordersPage.sellQuantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('ordersPage.sellPrice'),
      dataIndex: 'price',
      key: 'price',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('ordersPage.sellAmount'),
      key: 'amount',
      width: isMobile ? 100 : 120,
      render: (_: any, record: SellOrderInfo) => {
        const amount = (parseFloat(record.quantity) * parseFloat(record.price)).toString()
        return (
          <span style={{ fontSize: isMobile ? 12 : 14 }}>
            {isMobile ? formatUSDC(amount) : `$${formatUSDC(amount)}`}
          </span>
        )
      }
    },
    {
      title: t('ordersPage.realizedPnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      width: isMobile ? 100 : 120,
      render: (value: string) => (
        <span style={{ 
          color: getPnlColor(value), 
          fontWeight: 500,
          fontSize: isMobile ? 12 : 14
        }}>
          {isMobile ? formatUSDC(value) : `$${formatUSDC(value)}`}
        </span>
      )
    },
    {
      title: t('ordersPage.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 120 : 160,
      render: (timestamp: number) => (
        <span style={{ fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? new Date(timestamp).toLocaleDateString('zh-CN')
            : new Date(timestamp).toLocaleString('zh-CN')
          }
        </span>
      )
    }
  ]
  
  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button icon={<LeftOutlined />} onClick={() => navigate(-1)}>
              {t('common.back')}
            </Button>
            <h2 style={{ margin: 0 }}>{t('ordersPage.sellOrdersTitle')}</h2>
          </div>
        </div>
        
        <div style={{ marginBottom: 16, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
          <Input
            placeholder={t('ordersPage.filterMarketId')}
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.marketId}
            onChange={(e) => setFilters({ ...filters, marketId: e.target.value || undefined })}
          />
          
          <Select
            placeholder={t('ordersPage.filterSide')}
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.side}
            onChange={(value) => setFilters({ ...filters, side: value || undefined })}
          >
            <Option value="0">YES</Option>
            <Option value="1">NO</Option>
            <Option value="YES">YES</Option>
            <Option value="NO">NO</Option>
          </Select>
          
          <Button onClick={fetchOrders}>{t('ordersPage.search')}</Button>
        </div>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : orders.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
                {t('ordersPage.noSellOrders')}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {orders.map((order) => {
                  const date = new Date(order.createdAt)
                  const formattedDate = date.toLocaleString('zh-CN', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit'
                  })
                  const amount = (parseFloat(order.quantity) * parseFloat(order.price)).toString()
                  const displaySide = order.side === '0' ? 'YES' : order.side === '1' ? 'NO' : order.side
                  
                  return (
                    <Card
                      key={order.orderId}
                      style={{
                        borderRadius: '12px',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8'
                      }}
                      styles={{ body: { padding: '16px' } }}
                    >
                      {/* 订单ID和方向 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ 
                          fontSize: '14px', 
                          fontWeight: 'bold', 
                          marginBottom: '8px',
                          fontFamily: 'monospace'
                        }}>
                          {order.orderId.slice(0, 8)}...{order.orderId.slice(-6)}
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                          <Tag>{displaySide}</Tag>
                        </div>
                      </div>
                      
                      <Divider style={{ margin: '12px 0' }} />
                      
                      {/* 卖出信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('ordersPage.sellInfo')}</div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {t('ordersPage.quantity')}: {formatUSDC(order.quantity)} | {t('ordersPage.price')}: {formatUSDC(order.price)}
                        </div>
                        <div style={{ fontSize: '14px', fontWeight: '500', marginTop: '4px' }}>
                          {t('ordersPage.amount')}: ${formatUSDC(amount)}
                        </div>
                      </div>
                      
                      {/* 已实现盈亏 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('ordersPage.realizedPnl')}</div>
                        <div style={{ 
                          fontSize: '16px', 
                          fontWeight: 'bold',
                          color: getPnlColor(order.realizedPnl)
                        }}>
                          ${formatUSDC(order.realizedPnl)}
                        </div>
                      </div>
                      
                      {/* Leader 交易ID */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('ordersPage.leaderTradeId')}</div>
                        <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
                          {order.leaderTradeId.slice(0, 8)}...{order.leaderTradeId.slice(-6)}
                        </div>
                      </div>
                      
                      {/* 市场ID */}
                      <div style={{ marginBottom: '16px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('ordersPage.marketId')}</div>
                        <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
                          {order.marketId.slice(0, 8)}...{order.marketId.slice(-6)}
                        </div>
                      </div>
                      
                      {/* 创建时间 */}
                      <div style={{ marginBottom: '16px' }}>
                        <div style={{ fontSize: '12px', color: '#999' }}>
                          {t('ordersPage.createdAt')}: {formattedDate}
                        </div>
                      </div>
                    </Card>
                  )
                })}
              </div>
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={orders}
            rowKey="orderId"
            loading={loading}
            pagination={{
              current: page,
              pageSize: limit,
              total,
              showSizeChanger: true,
              showTotal: (total) => t('templateList.totalCount', { total }),
              onChange: (newPage, newLimit) => {
                setPage(newPage)
                setLimit(newLimit)
              }
            }}
          />
        )}
      </Card>
    </div>
  )
}

export default CopyTradingSellOrdersPage

