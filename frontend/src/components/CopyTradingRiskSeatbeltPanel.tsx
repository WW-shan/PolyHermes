import { Alert, Button, Card, Col, Modal, Row, Space, Statistic, Table, Tag, message } from 'antd'
import { useState } from 'react'
import { apiService } from '../services/api'
import type { CopyTradingStatistics } from '../types'
import { formatUSDC } from '../utils'
import { useTranslation } from 'react-i18next'

interface Props {
  statistics: CopyTradingStatistics
  onApplied?: () => void
  compact?: boolean
}

// 字段名 -> i18n key（未知字段显示原始字段名）
const fieldLabelKeys: Record<string, string> = {
  maxDailyOrders: 'riskSeatbelt.field.maxDailyOrders',
  maxDailyLoss: 'riskSeatbelt.field.maxDailyLoss',
  minPrice: 'riskSeatbelt.field.minPrice',
  maxPrice: 'riskSeatbelt.field.maxPrice',
  maxPositionValue: 'riskSeatbelt.field.maxPositionValue',
  minOrderDepth: 'riskSeatbelt.field.minOrderDepth',
  maxSpread: 'riskSeatbelt.field.maxSpread',
  priceTolerance: 'riskSeatbelt.field.priceTolerance'
}

const statusTextKeys: Record<string, string> = {
  AVAILABLE: 'riskSeatbelt.quoteStatus.AVAILABLE',
  NO_MATCH: 'riskSeatbelt.quoteStatus.NO_MATCH',
  UNAVAILABLE: 'riskSeatbelt.quoteStatus.UNAVAILABLE'
}

const statusColor: Record<string, string> = {
  AVAILABLE: 'green',
  NO_MATCH: 'orange',
  UNAVAILABLE: 'red'
}

type ApplyConservativeConfigPayload = Parameters<typeof apiService.safetyConfig.applyConservative>[0]

const CopyTradingRiskSeatbeltPanel: React.FC<Props> = ({ statistics, onApplied, compact = false }) => {
  const { t } = useTranslation()
  const diagnosis = statistics.riskDiagnosis
  const fieldLabel = (field: string) => (fieldLabelKeys[field] ? t(fieldLabelKeys[field]) : field)
  const [applying, setApplying] = useState(false)

  if (!diagnosis) {
    return (
      <Card title={t('riskSeatbelt.title')} style={{ marginTop: 16 }}>
        <Alert type="info" showIcon message={t('riskSeatbelt.noDiagnosis')} description={t('riskSeatbelt.noDiagnosisDesc')} />
      </Card>
    )
  }

  const riskWarnings = diagnosis.riskWarnings || []
  const dangerousWarnings = riskWarnings.filter(item => item.severity === 'HIGH' || item.severity === 'MEDIUM')

  /**
   * 剔除与现有配置冲突的价格建议（如建议 maxPrice 0.80 但现有 minPrice 为 0.85）
   * currentMinPrice/currentMaxPrice 为跟单配置当前值；返回可应用的建议项与被剔除的建议项
   */
  const splitConflictingWarnings = (currentMinPrice?: string | null, currentMaxPrice?: string | null) => {
    const suggestionOf = (field: string) => dangerousWarnings.find(item => item.field === field && item.suggestedValue != null)
    const minSuggestion = suggestionOf('minPrice')
    const maxSuggestion = suggestionOf('maxPrice')
    const toNum = (v?: string | null) => (v == null || v === '' ? null : parseFloat(v))
    const finalMin = toNum(minSuggestion?.suggestedValue ?? currentMinPrice)
    const finalMax = toNum(maxSuggestion?.suggestedValue ?? currentMaxPrice)
    const conflictFields = new Set<string>()
    if (finalMin != null && finalMax != null && !isNaN(finalMin) && !isNaN(finalMax) && finalMin >= finalMax) {
      if (minSuggestion) conflictFields.add('minPrice')
      if (maxSuggestion) conflictFields.add('maxPrice')
    }
    return {
      applicable: dangerousWarnings.filter(item => !conflictFields.has(item.field)),
      excluded: dangerousWarnings.filter(item => conflictFields.has(item.field))
    }
  }

  const buildApplyPayload = (warnings: typeof riskWarnings): ApplyConservativeConfigPayload => {
    const payload: ApplyConservativeConfigPayload = {
      copyTradingId: statistics.copyTradingId,
      confirm: true
    }
    warnings.forEach(item => {
      if (item.suggestedValue === undefined || item.suggestedValue === null) {
        return
      }

      switch (item.field) {
        case 'maxDailyOrders':
          payload.maxDailyOrders = Number(item.suggestedValue)
          break
        case 'maxDailyLoss':
          payload.maxDailyLoss = item.suggestedValue
          break
        case 'minPrice':
          payload.minPrice = item.suggestedValue
          break
        case 'maxPrice':
          payload.maxPrice = item.suggestedValue
          break
        case 'maxPositionValue':
          payload.maxPositionValue = item.suggestedValue
          break
        case 'minOrderDepth':
          payload.minOrderDepth = item.suggestedValue
          break
        case 'maxSpread':
          payload.maxSpread = item.suggestedValue
          break
        case 'priceTolerance':
          payload.priceTolerance = item.suggestedValue
          break
      }
    })
    return payload
  }

  const applyConservativeConfig = async () => {
    if (dangerousWarnings.length === 0) {
      message.info(t('riskSeatbelt.alreadyConservativeNoApply'))
      return
    }

    // 读取跟单配置当前的价格区间，用于剔除与现值冲突的建议
    let currentMinPrice: string | null | undefined
    let currentMaxPrice: string | null | undefined
    try {
      const response = await apiService.copyTrading.list({})
      const list: Array<{ id: number; minPrice?: string | null; maxPrice?: string | null }> =
        response.data.code === 0 ? (response.data.data?.list ?? []) : []
      const current = list.find(item => item.id === statistics.copyTradingId)
      currentMinPrice = current?.minPrice
      currentMaxPrice = current?.maxPrice
    } catch (error) {
      console.error('获取跟单配置失败:', error)
    }
    const { applicable, excluded } = splitConflictingWarnings(currentMinPrice, currentMaxPrice)
    if (applicable.length === 0) {
      message.warning(t('riskSeatbelt.allSuggestionsConflict'))
      return
    }

    Modal.confirm({
      title: t('riskSeatbelt.confirmTitle'),
      content: (
        <div>
          <p>{t('riskSeatbelt.confirmDesc')}</p>
          <Table
            size="small"
            pagination={false}
            rowKey="field"
            dataSource={applicable}
            columns={[
              { title: t('riskSeatbelt.col.field'), dataIndex: 'field', render: (field: string) => fieldLabel(field) },
              { title: t('riskSeatbelt.col.currentValue'), dataIndex: 'currentValue', render: (value: string | null) => value ?? t('riskSeatbelt.notSet') },
              { title: t('riskSeatbelt.col.suggestedValue'), dataIndex: 'suggestedValue' }
            ]}
          />
          {excluded.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 12 }}
              message={t('riskSeatbelt.conflictExcluded', {
                fields: excluded.map(item => fieldLabel(item.field)).join(', ')
              })}
            />
          )}
        </div>
      ),
      okText: t('riskSeatbelt.confirmOk'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        setApplying(true)
        try {
          const response = await apiService.safetyConfig.applyConservative(buildApplyPayload(applicable))
          if (response.data.code === 0) {
            message.success(t('riskSeatbelt.applySuccess'))
            onApplied?.()
          } else {
            message.error(response.data.msg || t('riskSeatbelt.applyFailed'))
          }
        } finally {
          setApplying(false)
        }
      }
    })
  }

  return (
    <Card title={t('riskSeatbelt.panelTitle')} style={{ marginTop: compact ? 12 : 16 }}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {diagnosis.dataIncomplete && (
          <Alert
            type="warning"
            showIcon
            message={t('riskSeatbelt.dataIncomplete')}
            description={t('riskSeatbelt.dataIncompleteDesc', { sources: diagnosis.missingSources.join(', ') || t('riskSeatbelt.unknown') })}
          />
        )}
        {diagnosis.lowConfidence && (
          <Alert type="info" showIcon message={t('riskSeatbelt.lowConfidence')} description={diagnosis.confidenceReason} />
        )}

        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic title={t('riskSeatbelt.zeroValuePositionCost')} value={formatUSDC(diagnosis.zeroValuePositionCost)} prefix="$" />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic title={t('riskSeatbelt.confirmedZeroValuePositionCost')} value={formatUSDC(diagnosis.confirmedZeroValuePositionCost)} prefix="$" />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic title={t('riskSeatbelt.zeroSellLoss')} value={formatUSDC(diagnosis.zeroSellLoss)} prefix="$" />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>{t('riskSeatbelt.quoteStatusTitle')}</div>
            <Tag color={statusColor[diagnosis.quoteOverallStatus] || 'default'}>
              {statusTextKeys[diagnosis.quoteOverallStatus] ? t(statusTextKeys[diagnosis.quoteOverallStatus]) : diagnosis.quoteOverallStatus}
            </Tag>
            <div style={{ color: '#999', marginTop: 8, fontSize: 12 }}>
              {t('riskSeatbelt.quoteCounts', { available: diagnosis.quoteAvailableCount, noMatch: diagnosis.quoteNoMatchCount, unavailable: diagnosis.quoteUnavailableCount })}
            </div>
          </Col>
        </Row>

        <div>
          <h4 style={{ marginBottom: 8 }}>{t('riskSeatbelt.topLosingMarkets')}</h4>
          {diagnosis.topLosingMarkets.length === 0 ? (
            <Alert type="success" showIcon message={t('riskSeatbelt.noLosingMarkets')} />
          ) : (
            <Table
              size="small"
              pagination={false}
              rowKey="marketId"
              dataSource={diagnosis.topLosingMarkets}
              columns={[
                { title: t('riskSeatbelt.col.market'), dataIndex: 'marketId' },
                { title: t('riskSeatbelt.col.realizedPnl'), dataIndex: 'realizedPnl', render: (value: string) => `$${formatUSDC(value)}` },
                { title: t('riskSeatbelt.col.matchedOrders'), dataIndex: 'matchedOrders' }
              ]}
            />
          )}
        </div>

        <div>
          <h4 style={{ marginBottom: 8 }}>{t('riskSeatbelt.riskCheck')}</h4>
          {riskWarnings.length === 0 ? (
            <Alert type="success" showIcon message={t('riskSeatbelt.alreadyConservative')} />
          ) : (
            <Table
              size="small"
              pagination={false}
              rowKey="field"
              dataSource={riskWarnings}
              columns={[
                { title: t('riskSeatbelt.col.field'), dataIndex: 'field', render: (field: string) => fieldLabel(field) },
                { title: t('riskSeatbelt.col.currentValue'), dataIndex: 'currentValue', render: (value: string | null) => value ?? t('riskSeatbelt.notSet') },
                { title: t('riskSeatbelt.col.suggestedValue'), dataIndex: 'suggestedValue' },
                { title: t('riskSeatbelt.col.severity'), dataIndex: 'severity', render: (severity: string) => <Tag color={severity === 'HIGH' ? 'red' : 'orange'}>{severity}</Tag> },
                { title: t('riskSeatbelt.col.reason'), dataIndex: 'reason' }
              ]}
            />
          )}
        </div>

        <Button type="primary" danger disabled={dangerousWarnings.length === 0} loading={applying} onClick={applyConservativeConfig}>
          {t('riskSeatbelt.applyButton')}
        </Button>
      </Space>
    </Card>
  )
}

export default CopyTradingRiskSeatbeltPanel
