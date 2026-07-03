package com.dontry.app

data class Purchase(
    val planId: String = "",
    val amount: Int = 0,
    val tryonsCredited: Int = 0,
    val purchasedAt: Long = 0
)