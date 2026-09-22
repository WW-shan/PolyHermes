import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Col,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  message
} from 'antd'
import { EyeOutlined, LinkOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { Account, Leader, LeaderPoolItem, LeaderPoolListResponse, LeaderPoolOptimizationItem, LeaderPoolOptimizationResponse, LeaderPoolStatus } from '../types'

const { Text, Title, Paragraph } = Typography

const VISIBLE_STATUSES: Array<{ value: LeaderPoolStatus; color: string }> = [
  { value: 'CANDIDATE', color: 'default' },
  { value: 'WATCH', color: 'blue' },
  { value: 'TRIAL', color: 'green' },
  { value: 'COOLDOWN', color: 'orange' },
  { value: 'RETIRED', color: 'red' }
]

type LeaderPoolFilterValue = LeaderPoolStatus | 'ALL'

const formatDate = (timestamp?: number) => {
  if (!timestamp) return '-'
  return dayjs(timestamp).format('YYYY-MM-DD HH:mm')
}

const canCreateTrialConfig = (item: LeaderPoolItem) =>
  !item.researchCandidateId || item.researchState === 'TRIAL_READY'

const LeaderPool: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [poolData, setPoolData] = useState<LeaderPoolListResponse>({
    summary: {
      totalCount: 0,
      trialCount: 0,
      estimatedWorstExposure: '0',
      pendingRiskCount: 0,
      defaultExperimentBudget: '50'
    },
    list: [],
    total: 0
  })
  const [optimization, setOptimization] = useState<LeaderPoolOptimizationResponse | null>(null)
  const [optimizationLoading, setOptimizationLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [accounts, setAccounts] = useState<Account[]>([])
  const [statusFilter, setStatusFilter] = useState<LeaderPoolStatus | undefined>()
  const [loading, setLoading] = useState(false)
  const [adding, setAdding] = useState(false)
  const [creatingMap, setCreatingMap] = useState<Record<number, boolean>>({})
  const [selectedLeaderId, setSelectedLeaderId] = useState<number | undefined>()
  const [statusModalItem, setStatusModalItem] = useState<LeaderPoolItem | null>(null)
  const [planModalItem, setPlanModalItem] = useState<LeaderPoolItem | null>(null)
  const [trialModalItem, setTrialModalItem] = useState<LeaderPoolItem | null>(null)
  const [statusForm] = Form.useForm()
  const [planForm] = Form.useForm()
  const [trialForm] = Form.useForm()

  const statusLabel = (status: LeaderPoolStatus) =>
    t(`leaderPool.statuses.${status}`, { defaultValue: status })

  const fetchPool = async (status = statusFilter) => {
    setLoading(true)
    try {
      const response = await apiService.leaderPool.list(status ? { status } : {})
      if (response.data.code === 0 && response.data.data) {
        setPoolData(response.data.data)
      } else {
        message.error(response.data.msg || t('leaderPool.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderPool.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const fetchOptimization = async () => {
    setOptimizationLoading(true)
    try {
      const response = await apiService.leaderPool.optimization({ limit: 10 })
      if (response.data.code === 0 && response.data.data) {
        setOptimization(response.data.data)
      } else {
        message.error(response.data.msg || t('leaderPool.optimization.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderPool.optimization.fetchFailed'))
    } finally {
      setOptimizationLoading(false)
    }
  }

  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list()
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error) {
      console.error('加载 Leader 列表失败:', error)
    }
  }

  const fetchAccounts = async () => {
    try {
      const response = await apiService.accounts.list()
      if (response.data.code === 0 && response.data.data) {
        setAccounts(response.data.data.list || [])
      }
    } catch (error) {
      console.error('加载账户列表失败:', error)
    }
  }

  useEffect(() => {
    fetchPool()
    fetchOptimization()
    fetchLeaders()
    fetchAccounts()
  }, [])

  useEffect(() => {
    fetchPool(statusFilter)
  }, [statusFilter])

  const handleAddLeader = async () => {
    if (!selectedLeaderId) {
      message.warning(t('leaderPool.selectLeaderFirst'))
      return
    }
    setAdding(true)
    try {
      const response = await apiService.leaderPool.add({ leaderId: selectedLeaderId })
      if (response.data.code === 0) {
        message.success(t('leaderPool.addSuccess'))
        setSelectedLeaderId(undefined)
        fetchPool()
      } else {
        message.warning(response.data.msg || t('leaderPool.addFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderPool.addFailed'))
    } finally {
      setAdding(false)
    }
  }

  const openStatusModal = (item: LeaderPoolItem) => {
    setStatusModalItem(item)
    statusForm.setFieldsValue({
      status: item.status,
      cooldownUntil: item.cooldownUntil ? dayjs(item.cooldownUntil) : undefined,
      locked: item.locked
    })
  }

  const handleUpdateStatus = async () => {
    if (!statusModalItem) return
    const values = await statusForm.validateFields()
    const response = await apiService.leaderPool.updateStatus({
      poolId: statusModalItem.id,
      status: values.status,
      cooldownUntil: values.cooldownUntil ? values.cooldownUntil.valueOf() : undefined,
      locked: values.locked
    })
    if (response.data.code === 0) {
      message.success(t('leaderPool.statusUpdated'))
      setStatusModalItem(null)
      fetchPool()
    } else {
      message.error(response.data.msg || t('leaderPool.statusUpdateFailed'))
    }
  }

  const openPlanModal = (item: LeaderPoolItem) => {
    setPlanModalItem(item)
    planForm.setFieldsValue({
      suggestedFixedAmount: item.suggestedFixedAmount,
      suggestedMaxDailyOrders: item.suggestedMaxDailyOrders,
      suggestedMaxDailyLoss: item.suggestedMaxDailyLoss,
      suggestedMinPrice: item.suggestedMinPrice,
      suggestedMaxPrice: item.suggestedMaxPrice,
      suggestedMaxPositionValue: item.suggestedMaxPositionValue,
      notes: item.notes
    })
  }

  const handleUpdatePlan = async () => {
    if (!planModalItem) return
    const values = await planForm.validateFields()
    const response = await apiService.leaderPool.updatePlan({
      poolId: planModalItem.id,
      suggestedFixedAmount: values.suggestedFixedAmount?.toString(),
      suggestedMaxDailyOrders: values.suggestedMaxDailyOrders,
      suggestedMaxDailyLoss: values.suggestedMaxDailyLoss?.toString(),
      suggestedMinPrice: values.suggestedMinPrice?.toString(),
      suggestedMaxPrice: values.suggestedMaxPrice?.toString(),
      suggestedMaxPositionValue: values.suggestedMaxPositionValue?.toString(),
      notes: values.notes
    })
    if (response.data.code === 0) {
      message.success(t('leaderPool.planUpdated'))
      setPlanModalItem(null)
      fetchPool()
    } else {
      message.error(response.data.msg || t('leaderPool.planUpdateFailed'))
    }
  }

  const openTrialModal = (item: LeaderPoolItem) => {
    setTrialModalItem(item)
    trialForm.setFieldsValue({ accountId: accounts[0]?.id })
  }

  const handleCreateTrialConfig = async () => {
    if (!trialModalItem) return
    if (creatingMap[trialModalItem.id]) return
    const values = await trialForm.validateFields()
    setCreatingMap(prev => ({ ...prev, [trialModalItem.id]: true }))
    try {
      const response = await apiService.leaderPool.createTrialConfig({
        poolId: trialModalItem.id,
        accountId: values.accountId,
        enableImmediately: false,
        confirm: false
      })
      if (response.data.code === 0) {
        message.success({
          content: (
            <Space>
              <span>{t('leaderPool.trialCreated')}</span>
              <Button type="link" size="small" onClick={() => navigate('/copy-trading')}>
                {t('leaderPool.goCopyTrading')}
              </Button>
            </Space>
          )
        })
        setTrialModalItem(null)
        fetchPool()
      } else {
        message.error(response.data.msg || t('leaderPool.trialCreateFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderPool.trialCreateFailed'))
    } finally {
      setCreatingMap(prev => ({ ...prev, [trialModalItem.id]: false }))
    }
  }

  const handleRemove = async (item: LeaderPoolItem) => {
    const response = await apiService.leaderPool.remove({ poolId: item.id })
    if (response.data.code === 0) {
      message.success(t('leaderPool.removeSuccess'))
      fetchPool()
    } else {
      message.error(response.data.msg || t('leaderPool.removeFailed'))
    }
  }

  const optimizationColumns = [
    {
      title: t('leaderPool.optimization.rank'),
      dataIndex: 'rank',
      width: 60,
      render: (rank: number) => <Tag color={rank <= 3 ? 'gold' : 'default'}>#{rank}</Tag>
    },
    {
      title: t('leaderPool.leader'),
      key: 'leader',
      width: 250,
      render: (_: unknown, item: LeaderPoolOptimizationItem) => (
        <Space direction="vertical" size={0}>
          <Text strong>{item.leaderName || `Leader ${item.leaderId ?? item.candidateId}`}</Text>
          <Text copyable style={{ fontSize: 12, fontFamily: 'monospace' }} type="secondary">
            {item.leaderAddress}
          </Text>
          <Tag>{t(`leaderResearch.states.${item.researchState}`, { defaultValue: item.researchState })}</Tag>
        </Space>
      )
    },
    {
      title: t('leaderPool.optimization.tier'),
      dataIndex: 'recommendationTier',
      width: 150,
      render: (tier: string) => {
        const color: Record<string, string> = {
          AUTO_READY: 'green',
          HIGH_RISK_PILOT: 'orange',
          PAPER_WATCH: 'blue',
          BLOCKED: 'red'
        }
        return <Tag color={color[tier] || 'default'}>{t(`leaderPool.optimization.tiers.${tier}`, { defaultValue: tier })}</Tag>
      }
    },
    {
      title: t('leaderPool.optimization.score'),
      dataIndex: 'optimizationScore',
      width: 100,
      render: (value: string) => <Text strong>{value}</Text>
    },
    {
      title: t('leaderResearch.trades'),
      dataIndex: 'paperTradeCount',
      width: 90
    },
    {
      title: t('leaderResearch.copyablePnl'),
      dataIndex: 'paperCopyablePnl',
      width: 120,
      render: (value: string) => <Text type={Number(value) >= 0 ? 'success' : 'danger'}>{value}</Text>
    },
    {
      title: t('leaderPool.optimization.filteredRatio'),
      dataIndex: 'paperFilteredRatio',
      width: 110
    },
    {
      title: t('leaderPool.optimization.maxDrawdown'),
      dataIndex: 'paperMaxDrawdown',
      width: 110
    },
    {
      title: t('leaderPool.optimization.reason'),
      dataIndex: 'reasonCode',
      width: 220,
      render: (reasonCode: string) => t(`leaderPool.optimization.reasons.${reasonCode}`, { defaultValue: reasonCode })
    }
  ]

  const columns = [
    {
      title: t('leaderPool.leader'),
      key: 'leader',
      width: 260,
      render: (_: unknown, item: LeaderPoolItem) => (
        <Space direction="vertical" size={0}>
          <Text strong>{item.leaderName || `Leader ${item.leaderId}`}</Text>
          <Text copyable style={{ fontSize: 12, fontFamily: 'monospace' }} type="secondary">
            {item.leaderAddress}
          </Text>
          {item.researchBadge && (
            <Space size={4}>
              <Tag color={item.researchState === 'TRIAL_READY' ? 'green' : 'blue'}>{t(`leaderResearch.states.${item.researchState}`, { defaultValue: item.researchState })}</Tag>
              {item.researchScore && <Text type="secondary">{t('leaderResearch.score')}: {item.researchScore}</Text>}
            </Space>
          )}
          {item.researchCandidateId && (
            <Button type="link" size="small" style={{ padding: 0 }} onClick={() => navigate('/leader-research')}>
              {t('leaderPool.viewResearch')}
            </Button>
          )}
        </Space>
      )
    },
    {
      title: t('common.status'),
      dataIndex: 'status',
      width: 120,
      render: (status: LeaderPoolStatus) => {
        const meta = VISIBLE_STATUSES.find(item => item.value === status)
        return <Tag color={meta?.color || 'default'}>{statusLabel(status)}</Tag>
      }
    },
    {
      title: t('leaderPool.source'),
      dataIndex: 'source',
      width: 120
    },
    {
      title: t('leaderPool.suggestedConfig'),
      key: 'plan',
      width: 220,
      render: (_: unknown, item: LeaderPoolItem) => (
        <Space direction="vertical" size={0}>
          <Text>{t('leaderPool.fixedAmount')}: {item.suggestedFixedAmount}</Text>
          <Text type="secondary">{t('leaderPool.maxDailyOrders')}: {item.suggestedMaxDailyOrders}</Text>
          <Text type="secondary">{t('leaderPool.maxDailyLoss')}: {item.suggestedMaxDailyLoss}</Text>
        </Space>
      )
    },
    {
      title: t('leaderPool.copyTradingState'),
      key: 'copyTradingState',
      width: 160,
      render: (_: unknown, item: LeaderPoolItem) => (
        <Space direction="vertical" size={0}>
          <Text>{item.copyTradingCount} {t('leaderPool.configs')}</Text>
          <Tag color={item.hasEnabledCopyTrading ? 'green' : 'default'}>
            {item.hasEnabledCopyTrading ? t('leaderPool.hasEnabled') : t('leaderPool.noEnabled')}
          </Tag>
        </Space>
      )
    },
    {
      title: t('leaderPool.lastReviewedAt'),
      dataIndex: 'lastReviewedAt',
      width: 150,
      render: (value?: number) => formatDate(value)
    },
    {
      title: t('common.actions'),
      key: 'actions',
      fixed: 'right' as const,
      width: 320,
      render: (_: unknown, item: LeaderPoolItem) => {
        const trialAllowed = canCreateTrialConfig(item)
        const trialButton = (
          <Button
            size="small"
            type="primary"
            loading={creatingMap[item.id]}
            disabled={creatingMap[item.id] || !trialAllowed}
            onClick={() => openTrialModal(item)}
          >
            {t('leaderPool.createTrial')}
          </Button>
        )
        return (
          <Space wrap size={4}>
            <Button size="small" icon={<EyeOutlined />} onClick={() => window.open(item.profileUrl, '_blank', 'noopener,noreferrer')}>
              Profile
            </Button>
            <Button size="small" onClick={() => openStatusModal(item)}>
              {t('leaderPool.updateStatus')}
            </Button>
            <Button size="small" onClick={() => openPlanModal(item)}>
              {t('leaderPool.editPlan')}
            </Button>
            {trialAllowed ? trialButton : (
              <Tooltip title={t('leaderPool.trialRequiresResearchReady')}>
                <span>{trialButton}</span>
              </Tooltip>
            )}
            <Popconfirm
              title={t('leaderPool.removeConfirm')}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
              onConfirm={() => handleRemove(item)}
            >
              <Button size="small" danger>{t('common.delete')}</Button>
            </Popconfirm>
          </Space>
        )
      }
    }
  ]

  return (
    <div>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card>
          <Space direction="vertical" style={{ width: '100%' }}>
            <Space align="start" style={{ justifyContent: 'space-between', width: '100%' }}>
              <div>
                <Title level={3} style={{ marginBottom: 4 }}>{t('leaderPool.title')}</Title>
                <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {t('leaderPool.subtitle')}
                </Paragraph>
              </div>
              <Button icon={<ReloadOutlined />} onClick={() => fetchPool()}>{t('common.refresh')}</Button>
            </Space>
            <Alert
              type="info"
              showIcon
              message={t('leaderPool.safetyHint')}
              description={t('leaderPool.safetyDesc')}
            />
            <Space wrap>
              <Select
                style={{ minWidth: 260 }}
                allowClear
                showSearch
                placeholder={t('leaderPool.selectLeaderPlaceholder')}
                optionFilterProp="label"
                value={selectedLeaderId}
                onChange={setSelectedLeaderId}
                options={leaders.map(leader => ({
                  label: `${leader.leaderName || `Leader ${leader.id}`} - ${leader.leaderAddress.slice(0, 8)}...`,
                  value: leader.id
                }))}
                notFoundContent={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('leaderPool.noLeaders')} />}
              />
              <Button type="primary" icon={<PlusOutlined />} loading={adding} onClick={handleAddLeader}>
                {t('leaderPool.addExistingLeader')}
              </Button>
              <Button icon={<LinkOutlined />} onClick={() => navigate('/leaders')}>
                {t('leaderPool.goLeaders')}
              </Button>
            </Space>
          </Space>
        </Card>

        <Card>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space align="start" style={{ justifyContent: 'space-between', width: '100%' }}>
              <div>
                <Title level={4} style={{ marginBottom: 4 }}>{t('leaderPool.optimization.title')}</Title>
                <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {t('leaderPool.optimization.subtitle')}
                </Paragraph>
              </div>
              <Button icon={<ReloadOutlined />} loading={optimizationLoading} onClick={fetchOptimization}>
                {t('leaderPool.optimization.refresh')}
              </Button>
            </Space>
            <Alert
              type={optimization?.staleReason ? 'warning' : 'info'}
              showIcon
              message={t('leaderPool.optimization.safeTitle')}
              description={optimization?.staleReason
                ? t(`leaderPool.optimization.staleReasons.${optimization.staleReason}`, { defaultValue: optimization.staleReason })
                : t('leaderPool.optimization.safeDesc')}
            />
            <Row gutter={[16, 16]}>
              <Col xs={24} sm={8}>
                <Statistic title={t('leaderPool.optimization.eligible')} value={optimization?.eligibleCount ?? 0} />
              </Col>
              <Col xs={24} sm={8}>
                <Statistic title={t('leaderPool.optimization.candidates')} value={optimization?.candidateCount ?? 0} />
              </Col>
              <Col xs={24} sm={8}>
                <Statistic title={t('leaderPool.optimization.top3Count')} value={optimization?.top3.length ?? 0} />
              </Col>
            </Row>
            <Table
              rowKey="candidateId"
              loading={optimizationLoading}
              columns={optimizationColumns}
              dataSource={optimization?.items || []}
              pagination={false}
              scroll={{ x: 1200 }}
              locale={{
                emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('leaderPool.optimization.empty')} />
              }}
            />
          </Space>
        </Card>

        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} lg={6}>
            <Card><Statistic title={t('leaderPool.totalCount')} value={poolData.summary.totalCount} /></Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card><Statistic title={t('leaderPool.trialCount')} value={poolData.summary.trialCount} /></Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card><Statistic prefix="$" title={t('leaderPool.estimatedWorstExposure')} value={poolData.summary.estimatedWorstExposure} /></Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card><Statistic title={t('leaderPool.pendingRiskCount')} value={poolData.summary.pendingRiskCount} /></Card>
          </Col>
        </Row>

        <Card>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Select
              style={{ width: 220 }}
              placeholder={t('leaderPool.filterStatus')}
              value={statusFilter ?? 'ALL'}
              onChange={(value: LeaderPoolFilterValue) => setStatusFilter(value === 'ALL' ? undefined : value)}
              options={[
                { value: 'ALL', label: t('common.all') },
                ...VISIBLE_STATUSES.map(item => ({ value: item.value, label: statusLabel(item.value) }))
              ]}
            />
            <Table
              rowKey="id"
              loading={loading}
              columns={columns}
              dataSource={poolData.list}
              scroll={{ x: 1280 }}
              locale={{
                emptyText: (
                  <Empty
                    description={t('leaderPool.emptyDesc')}
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                  />
                )
              }}
            />
          </Space>
        </Card>
      </Space>

      <Modal
        title={t('leaderPool.updateStatus')}
        open={!!statusModalItem}
        onCancel={() => setStatusModalItem(null)}
        onOk={handleUpdateStatus}
      >
        <Form form={statusForm} layout="vertical">
          <Form.Item name="status" label={t('common.status')} rules={[{ required: true }]}>
            <Select options={VISIBLE_STATUSES.map(item => ({ value: item.value, label: statusLabel(item.value) }))} />
          </Form.Item>
          <Form.Item shouldUpdate noStyle>
            {({ getFieldValue }) => getFieldValue('status') === 'COOLDOWN' ? (
              <Form.Item name="cooldownUntil" label={t('leaderPool.cooldownUntil')}>
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            ) : null}
          </Form.Item>
          <Form.Item name="locked" label={t('leaderPool.locked')}>
            <Select
              options={[
                { value: false, label: t('common.no') },
                { value: true, label: t('common.yes') }
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('leaderPool.editPlan')}
        open={!!planModalItem}
        onCancel={() => setPlanModalItem(null)}
        onOk={handleUpdatePlan}
      >
        <Form form={planForm} layout="vertical">
          <Form.Item name="suggestedFixedAmount" label={t('leaderPool.fixedAmount')} rules={[{ required: true }]}>
            <InputNumber min={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="suggestedMaxDailyOrders" label={t('leaderPool.maxDailyOrders')} rules={[{ required: true }]}>
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="suggestedMaxDailyLoss" label={t('leaderPool.maxDailyLoss')} rules={[{ required: true }]}>
            <InputNumber min={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="suggestedMinPrice" label={t('leaderPool.minPrice')}>
            <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="suggestedMaxPrice" label={t('leaderPool.maxPrice')}>
            <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="suggestedMaxPositionValue" label={t('leaderPool.maxPositionValue')}>
            <InputNumber min={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="notes" label={t('leaderPool.notes')}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('leaderPool.createTrial')}
        open={!!trialModalItem}
        onCancel={() => setTrialModalItem(null)}
        onOk={handleCreateTrialConfig}
        confirmLoading={trialModalItem ? creatingMap[trialModalItem.id] : false}
      >
        {trialModalItem && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert
              type="warning"
              showIcon
              icon={<SafetyCertificateOutlined />}
              message={t('leaderPool.trialConfirmTitle')}
              description={t('leaderPool.trialConfirmDesc')}
            />
            <Form form={trialForm} layout="vertical">
              <Form.Item name="accountId" label={t('leaderPool.account')} rules={[{ required: true, message: t('leaderPool.selectAccount') }]}>
                <Select
                  options={accounts.map(account => ({
                    value: account.id,
                    label: `${account.accountName || account.walletAddress} (${account.proxyAddress?.slice(0, 8)}...)`
                  }))}
                  notFoundContent={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('leaderPool.noAccounts')} />}
                />
              </Form.Item>
            </Form>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label={t('leaderPool.fixedAmount')}>{trialModalItem.suggestedFixedAmount}</Descriptions.Item>
              <Descriptions.Item label={t('leaderPool.maxDailyOrders')}>{trialModalItem.suggestedMaxDailyOrders}</Descriptions.Item>
              <Descriptions.Item label={t('leaderPool.maxDailyLoss')}>{trialModalItem.suggestedMaxDailyLoss}</Descriptions.Item>
              <Descriptions.Item label={t('leaderPool.priceRange')}>
                {trialModalItem.suggestedMinPrice || '-'} - {trialModalItem.suggestedMaxPrice || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderPool.maxPositionValue')}>{trialModalItem.suggestedMaxPositionValue || '5'}</Descriptions.Item>
              <Descriptions.Item label={t('common.status')}>{t('common.disabled')}</Descriptions.Item>
            </Descriptions>
          </Space>
        )}
      </Modal>
    </div>
  )
}

export default LeaderPool
