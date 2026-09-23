import { useState } from 'react'
import { Form, Input, Alert, Button, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../../services/api'

interface TelegramConfigFormProps {
  form: any
}

/**
 * Telegram 配置表单组件
 */
const TelegramConfigForm: React.FC<TelegramConfigFormProps> = ({ form }) => {
  const { t } = useTranslation()
  const [loading, setLoading] = useState(false)
  
  /**
   * 自动获取 Chat IDs
   */
  const handleGetChatIds = async () => {
    const botToken = form.getFieldValue(['config', 'botToken'])
    if (!botToken || botToken.trim() === '') {
      message.warning(t('notificationSettings.getChatIdsNoToken'))
      return
    }
    
    setLoading(true)
    try {
      const response = await apiService.notifications.getTelegramChatIds({ botToken: botToken.trim() })
      if (response.data.code === 0 && response.data.data) {
        const chatIds = response.data.data
        if (chatIds.length > 0) {
          // 获取现有的 Chat IDs
          const existingChatIds = form.getFieldValue(['config', 'chatIds']) || ''
          const existingArray = typeof existingChatIds === 'string' 
            ? existingChatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
            : Array.isArray(existingChatIds) ? existingChatIds : []
          
          // 合并并去重
          const allChatIds = [...new Set([...existingArray, ...chatIds])]
          form.setFieldsValue({
            config: {
              ...form.getFieldValue('config'),
              chatIds: allChatIds.join(',')
            }
          })
          message.success(t('notificationSettings.getChatIdsSuccess', { count: chatIds.length }))
        } else {
          message.warning(t('notificationSettings.getChatIdsNoMessage'))
        }
      } else {
        message.error(response.data.msg || t('notificationSettings.getChatIdsFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.getChatIdsFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <>
      <Alert
        message={t('telegramConfig.title')}
        description={
          <div style={{ fontSize: '13px', lineHeight: '1.8' }}>
            <p style={{ margin: '4px 0' }} dangerouslySetInnerHTML={{ __html: `1. ${t('telegramConfig.step1')}` }} />
            <p style={{ margin: '4px 0' }} dangerouslySetInnerHTML={{ __html: `2. ${t('telegramConfig.step2')}` }} />
            <p style={{ margin: '4px 0' }} dangerouslySetInnerHTML={{ __html: `3. ${t('telegramConfig.step3')}` }} />
            <p style={{ margin: '4px 0' }}>{t('telegramConfig.step4')}</p>
            <p style={{ margin: '4px 0' }}>{t('telegramConfig.step5')}</p>
          </div>
        }
        type="info"
        showIcon
        style={{ fontSize: '12px', marginBottom: 16 }}
      />
      
      <Form.Item
        label={t('notificationSettings.chatIds')}
        required
      >
        <Form.Item
          name={['config', 'botToken']}
          rules={[{ required: true, message: t('telegramConfig.botTokenRequired') }]}
          style={{ marginBottom: 16 }}
        >
          <Input.Password
            placeholder={t('telegramConfig.botTokenPlaceholder')}
            prefix={t('telegramConfig.botToken')}
            suffix={
              <Button
                type="link"
                size="small"
                icon={<ReloadOutlined />}
                loading={loading}
                onClick={handleGetChatIds}
                style={{ padding: 0, height: 'auto' }}
              >
                {t('notificationSettings.getChatIdsButton')}
              </Button>
            }
          />
        </Form.Item>
        
        <Form.Item
          name={['config', 'chatIds']}
          rules={[{ required: true, message: t('notificationSettings.chatIdsRequired') }]}
          extra={t('notificationSettings.chatIdsExtra')}
        >
          <Input.TextArea
            placeholder={t('notificationSettings.chatIdsPlaceholder')}
            rows={3}
          />
        </Form.Item>
      </Form.Item>
    </>
  )
}

export default TelegramConfigForm
