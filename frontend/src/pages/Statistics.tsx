import { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, message, DatePicker, Space, Button, Typography } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined, ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import type { Dayjs } from 'dayjs'
import { apiService } from '../services/api'
import type { Statistics as StatisticsType } from '../types'
import { formatUSDC, formatNumber } from '../utils'
import { useMediaQuery } from 'react-responsive'

const { RangePicker } = DatePicker
const { Title } = Typography

const Statistics: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [stats, setStats] = useState<StatisticsType | null>(null)
  const [loading, setLoading] = useState(false)
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null]>([null, null])

  useEffect(() => {
    fetchStatistics()
  }, [])

  // range 显式传入，避免重置后读取到旧闭包中的日期
  const fetchStatistics = async (range: [Dayjs | null, Dayjs | null] = dateRange) => {
    setLoading(true)
    try {
      // 日期区间按整天计算：开始日 00:00:00.000 ~ 结束日 23:59:59.999
      const startTime = range[0] ? range[0].startOf('day').valueOf() : undefined
      const endTime = range[1] ? range[1].endOf('day').valueOf() : undefined

      const response = await apiService.statistics.global({ startTime, endTime })
      if (response.data.code === 0 && response.data.data) {
        setStats(response.data.data)
      } else {
        message.error(response.data.msg || t('statistics.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('statistics.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const handleDateRangeChange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    setDateRange(dates || [null, null])
  }

  const handleReset = () => {
    const emptyRange: [Dayjs | null, Dayjs | null] = [null, null]
    setDateRange(emptyRange)
    // 重置后立即用空区间刷新
    fetchStatistics(emptyRange)
  }

  return (
    <div>
      <div style={{ marginBottom: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('statistics.title')}</Title>
        <Space size="middle" wrap>
          <RangePicker
            value={dateRange}
            onChange={handleDateRangeChange}
            format="YYYY-MM-DD"
            placeholder={[t('statistics.startDate'), t('statistics.endDate')]}
            size={isMobile ? 'middle' : 'large'}
            allowClear
          />
          <Button
            type="primary"
            icon={<ReloadOutlined />}
            onClick={() => fetchStatistics()}
            loading={loading}
            size={isMobile ? 'middle' : 'large'}
          >
            {t('statistics.refresh')}
          </Button>
          {(dateRange[0] || dateRange[1]) && (
            <Button
              onClick={handleReset}
              size={isMobile ? 'middle' : 'large'}
            >
              {t('statistics.reset')}
            </Button>
          )}
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.totalOrders')}
              value={formatNumber(stats?.totalOrders || 0)}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.totalPnl')}
              value={formatUSDC(stats?.totalPnl || '0')}
              prefix={<>{stats?.totalPnl && parseFloat(stats.totalPnl) >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} $</>}
              valueStyle={{ color: stats?.totalPnl && parseFloat(stats.totalPnl || '0') >= 0 ? '#3f8600' : '#cf1322' }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.winRate')}
              value={stats?.winRate || '0'}
              precision={2}
              suffix="%"
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.avgPnl')}
              value={formatUSDC(stats?.avgPnl || '0')}
              prefix={<>{stats?.avgPnl && parseFloat(stats.avgPnl || '0') >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} $</>}
              valueStyle={{ color: stats?.avgPnl && parseFloat(stats.avgPnl || '0') >= 0 ? '#3f8600' : '#cf1322' }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.maxProfit')}
              value={formatUSDC(stats?.maxProfit || '0')}
              prefix={<><ArrowUpOutlined /> $</>}
              valueStyle={{ color: '#3f8600' }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.maxLoss')}
              value={formatUSDC(stats?.maxLoss || '0')}
              prefix={<><ArrowDownOutlined /> $</>}
              valueStyle={{ color: '#cf1322' }}
              loading={loading}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}

export default Statistics

