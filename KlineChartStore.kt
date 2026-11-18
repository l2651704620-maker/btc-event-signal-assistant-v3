package com.example.btcassistant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChartCandle(
    val openTime: Long, val open: Double, val high: Double,
    val low: Double, val close: Double, val volume: Double
)

object KlineChartStore {
    private val _interval = MutableStateFlow("1m")
    val interval = _interval.asStateFlow()
    private val _candles = MutableStateFlow<List<ChartCandle>>(emptyList())
    val candles = _candles.asStateFlow()
    private val _status = MutableStateFlow("等待K线数据")
    val status = _status.asStateFlow()

    fun selectInterval(value: String) { if (value in listOf("1m","3m","5m","10m","15m")) _interval.value = value }
    fun update(rows: List<ChartCandle>) { _candles.value = rows }
    fun setStatus(value: String) { _status.value = value }
}
