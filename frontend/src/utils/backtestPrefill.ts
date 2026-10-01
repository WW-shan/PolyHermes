import type { BacktestConfigDto } from '../types/backtest'

/**
 * 创建跟单配置弹窗的预填充参数（数值字段为表单显示值，copyRatio 为百分比）
 */
export interface CopyTradingPreFilledConfig {
  leaderId?: number
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: number
  fixedAmount?: string
  maxOrderSize?: number
  minOrderSize?: number
  maxDailyLoss?: number
  maxDailyOrders?: number
  priceTolerance?: number
  delaySeconds?: number
  supportSell?: boolean
  minOrderDepth?: number
  maxSpread?: number
  minPrice?: number
  maxPrice?: number
  maxPositionValue?: number
  keywordFilterMode?: string
  keywords?: string[]
  /** 市场截止时间限制（毫秒） */
  maxMarketEndDate?: number
  configName?: string
  /** 创建后是否立即启用（回测一键创建默认 false，需用户确认后再启用） */
  enabled?: boolean
}

/** 可选数值字符串转为数字；空值或非法值返回 undefined */
const toOptionalNumber = (value: string | number | null | undefined): number | undefined => {
  if (value === null || value === undefined || value === '') return undefined
  const num = typeof value === 'number' ? value : parseFloat(value)
  return isNaN(num) ? undefined : num
}

/**
 * 由回测任务配置生成"一键创建跟单配置"的预填充参数
 * 完整带上回测使用的全部参数（含价格区间、最大仓位等），并默认不启用
 */
export const buildBacktestPreFilledConfig = (
  leaderId: number,
  taskName: string,
  config: BacktestConfigDto,
  configNamePrefix: string
): CopyTradingPreFilledConfig => {
  const ratio = toOptionalNumber(config.copyRatio)
  return {
    leaderId,
    copyMode: config.copyMode,
    // 比例转百分比显示（修正浮点误差，如 0.07 * 100 = 7.000000000000001）
    copyRatio: config.copyMode === 'RATIO' && ratio !== undefined ? Number((ratio * 100).toFixed(8)) : undefined,
    fixedAmount: config.copyMode === 'FIXED' ? (config.fixedAmount ?? undefined) : undefined,
    maxOrderSize: toOptionalNumber(config.maxOrderSize),
    minOrderSize: toOptionalNumber(config.minOrderSize),
    maxDailyLoss: toOptionalNumber(config.maxDailyLoss),
    maxDailyOrders: config.maxDailyOrders,
    priceTolerance: toOptionalNumber(config.priceTolerance),
    delaySeconds: config.delaySeconds,
    supportSell: config.supportSell,
    minOrderDepth: toOptionalNumber(config.minOrderDepth),
    maxSpread: toOptionalNumber(config.maxSpread),
    minPrice: toOptionalNumber(config.minPrice),
    maxPrice: toOptionalNumber(config.maxPrice),
    maxPositionValue: toOptionalNumber(config.maxPositionValue),
    keywordFilterMode: config.keywordFilterMode || 'DISABLED',
    keywords: config.keywords || [],
    maxMarketEndDate: config.maxMarketEndDate ?? undefined,
    configName: `${configNamePrefix}-${taskName}`,
    enabled: false
  }
}
