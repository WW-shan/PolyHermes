import { useState, useEffect } from 'react'
import { Modal, Tabs } from 'antd'
import { useTranslation } from 'react-i18next'
import BuyOrdersTab from './BuyOrdersTab'
import SellOrdersTab from './SellOrdersTab'
import MatchedOrdersTab from './MatchedOrdersTab'

type TabType = 'buy' | 'sell' | 'matched'

interface CopyTradingOrdersModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
  defaultTab?: TabType
}

const CopyTradingOrdersModal: React.FC<CopyTradingOrdersModalProps> = ({
  open,
  onClose,
  copyTradingId,
  defaultTab = 'buy'
}) => {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<TabType>(defaultTab)

  useEffect(() => {
    if (open) {
      setActiveTab(defaultTab)
    }
  }, [open, defaultTab])

  return (
    <Modal
      title={t('copyTradingOrders.title')}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      styles={{ body: { padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' } }}
      destroyOnHidden
    >
      <Tabs
        activeKey={activeTab}
        onChange={(key) => setActiveTab(key as TabType)}
        items={[
          {
            key: 'buy',
            label: t('copyTradingOrders.buyOrders'),
            children: <BuyOrdersTab copyTradingId={copyTradingId} active={activeTab === 'buy'} />
          },
          {
            key: 'sell',
            label: t('copyTradingOrders.sellOrders'),
            children: <SellOrdersTab copyTradingId={copyTradingId} active={activeTab === 'sell'} />
          },
          {
            key: 'matched',
            label: t('copyTradingOrders.matchedOrders'),
            children: <MatchedOrdersTab copyTradingId={copyTradingId} active={activeTab === 'matched'} />
          }
        ]}
      />
    </Modal>
  )
}

export default CopyTradingOrdersModal

