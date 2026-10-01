package com.wrbug.polymarketbot.event

/**
 * 代理配置变更事件（保存、启用、禁用、删除代理后发布），用于通知 HTTP/WS 客户端按新代理重建连接
 */
data class ProxyConfigChangedEvent(
    val changedAt: Long = System.currentTimeMillis()
)
