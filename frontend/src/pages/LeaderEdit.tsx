import { useState, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Form, Input, Button, message, Typography, Space, Spin } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { Leader } from '../types'

const { Title } = Typography

const LeaderEdit: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)
  const leaderId = searchParams.get('id')

  useEffect(() => {
    if (leaderId) {
      fetchLeaderDetail(parseInt(leaderId))
    } else {
      message.error(t('leaderEdit.invalidId'))
      navigate('/leaders')
    }
  }, [leaderId, navigate])

  const fetchLeaderDetail = async (id: number) => {
    setFetching(true)
    try {
      const response = await apiService.leaders.detail({ leaderId: id })
      if (response.data.code === 0 && response.data.data) {
        const leader: Leader = response.data.data
        form.setFieldsValue({
          leaderName: leader.leaderName || '',
          remark: leader.remark || '',
          website: leader.website || ''
        })
      } else {
        message.error(response.data.msg || t('leaderEdit.fetchFailed'))
        navigate('/leaders')
      }
    } catch (error: any) {
      message.error(error.message || t('leaderEdit.fetchFailed'))
      navigate('/leaders')
    } finally {
      setFetching(false)
    }
  }

  const handleSubmit = async (values: any) => {
    if (!leaderId) {
      message.error(t('leaderEdit.invalidId'))
      return
    }

    setLoading(true)
    try {
      const response = await apiService.leaders.update({
        leaderId: parseInt(leaderId),
        leaderName: values.leaderName?.trim() || undefined,
        remark: values.remark?.trim() || undefined,
        website: values.website?.trim() || undefined
      })

      if (response.data.code === 0) {
        message.success(t('leaderEdit.saveSuccess'))
        navigate('/leaders')
      } else {
        message.error(response.data.msg || t('leaderEdit.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderEdit.saveFailed'))
    } finally {
      setLoading(false)
    }
  }

  if (fetching) {
    return (
      <div style={{ textAlign: 'center', padding: '40px' }}>
        <Spin size="large" />
      </div>
    )
  }

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/leaders')}
          style={{ marginBottom: '16px' }}
        >
          返回
        </Button>
        <Title level={2} style={{ margin: 0 }}>{t('leaderEdit.title')}</Title>
      </div>

      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label={t('leaderEdit.leaderName')}
            name="leaderName"
            tooltip={t('leaderEdit.leaderNameTooltip')}
          >
            <Input placeholder={t('leaderEdit.leaderNamePlaceholder')} />
          </Form.Item>

          <Form.Item
            label={t('leaderEdit.remark')}
            name="remark"
            tooltip={t('leaderEdit.remarkTooltip')}
          >
            <Input.TextArea
              placeholder={t('leaderEdit.remarkPlaceholder')}
              rows={3}
              maxLength={500}
              showCount
            />
          </Form.Item>

          <Form.Item
            label={t('leaderEdit.website')}
            name="website"
            tooltip={t('leaderEdit.websiteTooltip')}
            rules={[
              {
                type: 'url',
                message: t('leaderEdit.websiteInvalid')
              }
            ]}
          >
            <Input placeholder={t('leaderEdit.websitePlaceholder')} />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size={isMobile ? 'middle' : 'large'}
              >
                {t('leaderEdit.save')}
              </Button>
              <Button onClick={() => navigate('/leaders')}>
                {t('leaderEdit.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default LeaderEdit

