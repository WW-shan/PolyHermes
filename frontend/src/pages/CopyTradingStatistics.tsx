import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Row, Col, Statistic, Tag, Button, message, Spin } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined, LeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { formatUSDC, formatNumber } from '../utils'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { CopyTradingStatistics } from '../types'
import CopyTradingRiskSeatbeltPanel from '../components/CopyTradingRiskSeatbeltPanel'

const CopyTradingStatisticsPage: React.FC = () => {
  const { copyTradingId } = useParams<{ copyTradingId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  useMediaQuery({ maxWidth: 768 }) // 用于响应式布局，但当前页面未使用
  const [loading, setLoading] = useState(false)
  const [statistics, setStatistics] = useState<CopyTradingStatistics | null>(null)

  useEffect(() => {
    if (copyTradingId) {
      fetchStatistics()
    }
  }, [copyTradingId])

  const fetchStatistics = async () => {
    if (!copyTradingId) return

    setLoading(true)
    try {
      const response = await apiService.statistics.detail({ copyTradingId: parseInt(copyTradingId) })
      if (response.data.code === 0 && response.data.data) {
        setStatistics(response.data.data)
      } else {
        message.error(response.data.msg || t('copyTradingStatistics.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingStatistics.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }

  const getPnlIcon = (value: string) => {
    const num = parseFloat(value)
    if (isNaN(num)) return null
    return num >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />
  }

  const formatPercent = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '-'
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
  }

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (!statistics) {
    return (
      <Card>
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <p>{t('copyTradingStatistics.noData')}</p>
          <Button onClick={() => navigate('/copy-trading')}>{t('copyTradingStatistics.backToList')}</Button>
        </div>
      </Card>
    )
  }

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button icon={<LeftOutlined />} onClick={() => navigate('/copy-trading')}>
              {t('copyTradingStatistics.back')}
            </Button>
            <h2 style={{ margin: 0 }}>{t('copyTradingStatistics.title')}</h2>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button onClick={() => navigate(`/copy-trading/orders/buy/${copyTradingId}`)}>
              {t('copyTradingStatistics.buyOrders')}
            </Button>
            <Button onClick={() => navigate(`/copy-trading/orders/sell/${copyTradingId}`)}>
              {t('copyTradingStatistics.sellOrders')}
            </Button>
            <Button onClick={() => navigate(`/copy-trading/orders/matched/${copyTradingId}`)}>
              {t('copyTradingStatistics.matchedOrders')}
            </Button>
          </div>
        </div>
      </Card>

      {/* 基本信息卡片 */}
      <Card title={t('copyTradingStatistics.basicInfo')} style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <div>
              <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>{t('copyTradingStatistics.accountName')}</div>
              <div style={{ fontSize: 16, fontWeight: 500 }}>
                {statistics.accountName || t('positionList.accountFallback', { id: statistics.accountId })}
              </div>
            </div>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <div>
              <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>{t('copyTradingStatistics.leaderName')}</div>
              <div style={{ fontSize: 16, fontWeight: 500 }}>
                {statistics.leaderName || `Leader ${statistics.leaderId}`}
              </div>
            </div>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <div>
              <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>{t('copyTradingStatistics.status')}</div>
              <div>
                <Tag color={statistics.enabled ? 'green' : 'red'}>
                  {statistics.enabled ? t('copyTradingStatistics.enabled') : t('copyTradingStatistics.disabled')}
                </Tag>
              </div>
            </div>
          </Col>
        </Row>
      </Card>

      {/* 买入统计卡片 */}
      <Card title={t('copyTradingStatistics.buyStats')} style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalBuyQuantity')}
              value={formatNumber(statistics.totalBuyQuantity, 4)}
              suffix=""
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalBuyAmount')}
              value={formatUSDC(statistics.totalBuyAmount)}
              prefix="$"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalBuyOrders')}
              value={formatNumber(statistics.totalBuyOrders)}
              suffix={t('copyTradingStatistics.ordersUnit')}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.avgBuyPrice')}
              value={formatNumber(statistics.avgBuyPrice, 4)}
              suffix=""
            />
          </Col>
        </Row>
      </Card>

      {/* 卖出统计卡片 */}
      <Card title={t('copyTradingStatistics.sellStats')} style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={8}>
            <Statistic
              title={t('copyTradingStatistics.totalSellQuantity')}
              value={formatNumber(statistics.totalSellQuantity, 4)}
              suffix=""
            />
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Statistic
              title={t('copyTradingStatistics.totalSellAmount')}
              value={formatUSDC(statistics.totalSellAmount)}
              prefix="$"
            />
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Statistic
              title={t('copyTradingStatistics.totalSellOrders')}
              value={formatNumber(statistics.totalSellOrders)}
              suffix={t('copyTradingStatistics.ordersUnit')}
            />
          </Col>
        </Row>
      </Card>

      {/* 持仓统计卡片 */}
      <Card title={t('copyTradingStatistics.positionStats')} style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.currentPositionQuantity')}
              value={formatNumber(statistics.currentPositionQuantity, 4)}
              suffix=""
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.currentPositionCost')}
              value={formatUSDC(statistics.currentPositionCost)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.currentPositionValue')}
              value={formatUSDC(statistics.currentPositionValue)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.avgBuyPrice')}
              value={formatNumber(statistics.avgBuyPrice, 4)}
              suffix=""
            />
          </Col>
        </Row>
      </Card>

      {/* 盈亏统计卡片 */}
      <Card title={t('copyTradingStatistics.pnlStats')}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalRealizedPnl')}
              value={formatUSDC(statistics.totalRealizedPnl)}
              valueStyle={{ color: getPnlColor(statistics.totalRealizedPnl) }}
              prefix={<>{getPnlIcon(statistics.totalRealizedPnl)} $</>}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalUnrealizedPnl')}
              value={formatUSDC(statistics.totalUnrealizedPnl)}
              valueStyle={{ color: getPnlColor(statistics.totalUnrealizedPnl) }}
              prefix={<>{getPnlIcon(statistics.totalUnrealizedPnl)} $</>}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalPnl')}
              value={formatUSDC(statistics.totalPnl)}
              valueStyle={{ color: getPnlColor(statistics.totalPnl) }}
              prefix={<>{getPnlIcon(statistics.totalPnl)} $</>}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title={t('copyTradingStatistics.totalPnlPercent')}
              value={formatPercent(statistics.totalPnlPercent)}
              valueStyle={{ color: getPnlColor(statistics.totalPnlPercent) }}
              prefix={getPnlIcon(statistics.totalPnlPercent)}
            />
          </Col>
        </Row>
      </Card>

      <CopyTradingRiskSeatbeltPanel statistics={statistics} onApplied={fetchStatistics} />
    </div>
  )
}

export default CopyTradingStatisticsPage
