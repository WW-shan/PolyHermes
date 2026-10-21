package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.SignedOrderObject

object EventMakerOrderRequestFactory {
    fun build(signedOrder: SignedOrderObject, apiKey: String, postOnly: Boolean = true): NewOrderRequest =
        NewOrderRequest(
            order = signedOrder,
            owner = apiKey,
            orderType = "GTC",
            postOnly = postOnly
        )
}