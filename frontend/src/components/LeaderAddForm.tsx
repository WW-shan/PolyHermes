import { useState } from 'react'
import { Form, Input, Button, Space, message } from 'antd'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import { isValidWalletAddress } from '../utils'

interface LeaderAddFormProps {
  form: any
  onSuccess?: (leaderId: number) => void
  onCancel?: () => void
  showCancelButton?: boolean
}

const LeaderAddForm: React.FC<LeaderAddFormProps> = ({
  form,
  onSuccess,
  onCancel,
  showCancelButton = true
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const response = await apiService.leaders.add({
        leaderAddress: values.leaderAddress.trim(),
        leaderName: values.leaderName?.trim() || undefined,
        remark: values.remark?.trim() || undefined,
        website: values.website?.trim() || undefined
      })

      if (response.data.code === 0) {
        if (response.data.data && onSuccess) {
          onSuccess(response.data.data.id)
        }
      } else {
        // onFinish 的返回值不会被 Form 处理，失败时直接提示，避免未处理的 Promise 拒绝
        message.error(response.data.msg || t('leaderAdd.addFailed'))
      }
    } catch (error: any) {
      message.error(error.response?.data?.msg || error.message || t('leaderAdd.addFailed'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleSubmit}
      size={isMobile ? 'middle' : 'large'}
    >
      <Form.Item
        label={t('leaderAdd.leaderAddress')}
        name="leaderAddress"
        rules={[
          { required: true, message: t('leaderAdd.leaderAddressRequired') },
          {
            validator: (_, value) => {
              if (!value) {
                return Promise.reject(new Error(t('leaderAdd.leaderAddressRequired')))
              }
              if (!isValidWalletAddress(value.trim())) {
                return Promise.reject(new Error(t('leaderAdd.leaderAddressInvalid')))
              }
              return Promise.resolve()
            }
          }
        ]}
        tooltip={t('leaderAdd.leaderAddressTooltip')}
      >
        <Input placeholder="0x..." style={{ fontFamily: 'monospace' }} />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.leaderName')}
        name="leaderName"
        tooltip={t('leaderAdd.leaderNameTooltip')}
      >
        <Input placeholder={t('leaderAdd.leaderNamePlaceholder')} />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.remark')}
        name="remark"
        tooltip={t('leaderAdd.remarkTooltip')}
      >
        <Input.TextArea
          placeholder={t('leaderAdd.remarkPlaceholder')}
          rows={3}
          maxLength={500}
          showCount
        />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.website')}
        name="website"
        tooltip={t('leaderAdd.websiteTooltip')}
        rules={[
          {
            type: 'url',
            message: t('leaderAdd.websiteInvalid')
          }
        ]}
      >
        <Input placeholder={t('leaderAdd.websitePlaceholder')} />
      </Form.Item>

      <Form.Item>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            size={isMobile ? 'middle' : 'large'}
          >
            {t('leaderAdd.add')}
          </Button>
          {showCancelButton && onCancel && (
            <Button onClick={onCancel}>
              {t('common.cancel')}
            </Button>
          )}
        </Space>
      </Form.Item>
    </Form>
  )
}

export default LeaderAddForm

