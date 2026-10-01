import { useNavigate } from 'react-router-dom'
import { Card, Form, Button, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { message } from 'antd'
import LeaderAddForm from '../components/LeaderAddForm'

const { Title } = Typography

const LeaderAdd: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  
  const handleSuccess = async () => {
    message.success(t('leaderAdd.addSuccess'))
    navigate('/leaders')
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/leaders')}
          style={{ marginBottom: '16px' }}
        >
          {t('leaderAdd.back')}
        </Button>
        <Title level={2} style={{ margin: 0 }}>{t('leaderAdd.title')}</Title>
      </div>
      
      <Card>
        <LeaderAddForm
          form={form}
          onSuccess={handleSuccess}
          onCancel={() => navigate('/leaders')}
          showCancelButton={true}
        />
      </Card>
    </div>
  )
}

export default LeaderAdd

