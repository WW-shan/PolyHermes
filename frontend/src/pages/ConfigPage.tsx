import { Card, Typography, Alert } from 'antd'
import { InfoCircleOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'

const { Title } = Typography

/**
 * 全局配置页面
 * 注意：全局配置功能已迁移到模板和跟单关系管理
 * 请使用"跟单模板"和"跟单配置"页面进行配置
 */
const ConfigPage: React.FC = () => {
  const { t } = useTranslation()
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('configPage.title')}</Title>
      </div>
      
      <Card>
        <Alert
          message={t('configPage.message')}
          description={
            <div>
              <p>{t('configPage.description')}</p>
              <ul>
                <li><strong>{t('configPage.templates')}</strong>：{t('configPage.templatesDesc')}</li>
                <li><strong>{t('configPage.copyTrading')}</strong>：{t('configPage.copyTradingDesc')}</li>
                <li><strong>{t('configPage.systemSettings')}</strong>：{t('configPage.systemSettingsDesc')}</li>
              </ul>
              <p>{t('configPage.footer')}</p>
            </div>
          }
          type="info"
          icon={<InfoCircleOutlined />}
          showIcon
        />
      </Card>
    </div>
  )
}

export default ConfigPage
