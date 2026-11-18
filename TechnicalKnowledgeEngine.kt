package com.example.btcassistant.data

import kotlin.math.abs
import kotlin.math.max

/**
 * Transparent short-horizon knowledge votes. Scores are rule-fit points, never win probabilities.
 * Missing exchange feeds are deliberately neutral rather than fabricated.
 */
data class KnowledgeAssessment(
    val direction: Int,
    val adjustment: Int,
    val detail: String,
    val modules: Map<String, Int>
)

object TechnicalKnowledgeEngine {
    fun evaluate(candles: List<StrategyCandle>, futures: FuturesSnapshot): KnowledgeAssessment {
        if (candles.size < 35) return KnowledgeAssessment(0, 0, "技术知识库样本不足", emptyMap())
        val xs = candles.takeLast(60)
        val last = xs.last(); val prev = xs[xs.lastIndex - 1]
        val modules = linkedMapOf<String, Int>()
        fun vote(name: String, score: Int) { modules[name] = score.coerceIn(-3, 3) }

        // Candle patterns: engulfing, pin bar, doji, consecutive candles and break/fake-break.
        val body = abs(last.close - last.open).coerceAtLeast(last.close * 0.00001)
        val range = (last.high - last.low).coerceAtLeast(body)
        val upper = last.high - max(last.open, last.close)
        val lower = minOf(last.open, last.close) - last.low
        val bullEngulf = last.close > last.open && prev.close < prev.open && last.open <= prev.close && last.close >= prev.open
        val bearEngulf = last.close < last.open && prev.close > prev.open && last.open >= prev.close && last.close <= prev.open
        val pin = when { lower / range > 0.58 && body / range < 0.34 -> 1; upper / range > 0.58 && body / range < 0.34 -> -1; else -> 0 }
        val doji = body / range < 0.10
        val recent = xs.takeLast(4)
        val upRun = recent.all { it.close > it.open }; val downRun = recent.all { it.close < it.open }
        val prior = xs.dropLast(1).takeLast(8)
        val priorHigh = prior.maxOf { it.high }; val priorLow = prior.minOf { it.low }
        val bullBreak = last.close > priorHigh; val bearBreak = last.close < priorLow
        val fakeBull = last.high > priorHigh && last.close < priorHigh
        val fakeBear = last.low < priorLow && last.close > priorLow
        val candleScore = when { bullEngulf || (pin > 0 && !doji) || upRun || bullBreak || fakeBear -> 2; bearEngulf || (pin < 0 && !doji) || downRun || bearBreak || fakeBull -> -2; else -> 0 }
        vote("K线形态", candleScore)

        // Volume and price/volume divergence.
        val avgVol = xs.dropLast(1).takeLast(20).map { it.volume }.average().coerceAtLeast(1e-12)
        val volumeRatio = last.volume / avgVol
        val recentPriceMove = last.close - xs[xs.size - 6].close
        val priorPriceMove = xs[xs.size - 6].close - xs[xs.size - 11].close
        val volumeScore = when {
            volumeRatio >= 1.6 && recentPriceMove > 0 && (bullBreak || last.close > last.open) -> 3
            volumeRatio >= 1.6 && recentPriceMove < 0 && (bearBreak || last.close < last.open) -> -3
            volumeRatio < 0.75 && recentPriceMove * priorPriceMove < 0 -> if (recentPriceMove > 0) 1 else -1
            volumeRatio >= 1.5 && recentPriceMove * priorPriceMove < 0 -> if (recentPriceMove > 0) -1 else 1
            else -> 0
        }
        vote("量价关系", volumeScore)

        // Nearby swing support/resistance and breakout retest proxy.
        val srRows = xs.dropLast(1).takeLast(30)
        val resistance = srRows.maxOf { it.high }; val support = srRows.minOf { it.low }
        val nearSupport = (last.close - support) / last.close < 0.0025
        val nearResistance = (resistance - last.close) / last.close < 0.0025
        val retestBull = last.low <= resistance * 1.001 && last.close > resistance
        val retestBear = last.high >= support * 0.999 && last.close < support
        vote("支撑阻力", when { retestBull -> 2; retestBear -> -2; nearSupport && last.close > last.open -> 1; nearResistance && last.close < last.open -> -1; else -> 0 })

        // RSI, MACD histogram direction, short momentum and acceleration.
        val closes = xs.map { it.close }
        fun rsi(period: Int): Double {
            val changes = closes.takeLast(period + 1).zipWithNext { a, b -> b - a }
            val gains = changes.filter { it > 0 }.sum() / period
            val losses = -changes.filter { it < 0 }.sum() / period
            return if (losses <= 1e-12) if (gains <= 1e-12) 50.0 else 100.0 else 100.0 - 100.0 / (1.0 + gains / losses)
        }
        val rsi = rsi(7)
        val momNow = closes.last() - closes[closes.size - 4]
        val momPrev = closes[closes.size - 4] - closes[closes.size - 7]
        val acceleration = momNow - momPrev
        val macd = macdHist(closes)
        val momentumScore = (if (rsi > 52 && rsi < 78) 1 else if (rsi < 48 && rsi > 22) -1 else 0) +
            (if (macd > 0) 1 else if (macd < 0) -1 else 0) +
            (if (momNow > 0 && acceleration > 0) 1 else if (momNow < 0 && acceleration < 0) -1 else 0)
        vote("动能指标", momentumScore)

        // Futures sentiment: extreme funding and long/short crowding are contrarian; taker flow is directional.
        val funding = futures.fundingRate
        val ratio = futures.longShortRatio
        val taker = futures.takerBuySellRatio
        var derivatives = 0
        if (funding != null) { if (funding > 0.0005) derivatives--; if (funding < -0.0005) derivatives++ }
        if (ratio != null) { if (ratio > 1.25) derivatives--; if (ratio < 0.80) derivatives++ }
        if (taker != null) { if (taker > 1.08) derivatives++; if (taker < 0.92) derivatives-- }
        futures.oiChangePct?.let { if (it > 1.0 && last.close > prev.close) derivatives++; if (it > 1.0 && last.close < prev.close) derivatives-- }
        futures.orderBookImbalance?.let { if (it > 0.15) derivatives++; if (it < -0.15) derivatives-- }
        vote("合约情绪", derivatives)

        // Volatility and abrupt range expansion; liquidation data is not fabricated when unavailable.
        val atr = xs.takeLast(14).map { it.high - it.low }.average().coerceAtLeast(last.close * 0.0001)
        val atrPct = atr / last.close * 100.0
        val expansion = range / atr
        val environment = when { atrPct > 0.20 -> -2; expansion > 2.2 && volumeRatio < 1.0 -> -1; else -> 0 }
        vote("市场环境", environment)

        val total = modules.values.sum()
        val direction = when { total > 0 -> 1; total < 0 -> -1; else -> 0 }
        val adjustment = (total * 2).coerceIn(-12, 12)
        val detail = modules.entries.joinToString("；") { "${it.key}${if (it.value > 0) "偏多" else if (it.value < 0) "偏空" else "中性"}(${it.value})" } +
            "；量能${"%.2f".format(volumeRatio)}倍；RSI${"%.1f".format(rsi)}；ATR波动${"%.3f".format(atrPct)}%" +
            (if (futures.openInterest == null) "；OI数据缺失" else "；OI=${"%.2f".format(futures.openInterest)}") +
            (futures.oiChangePct?.let { "；OI变化${"%.2f".format(it)}%" } ?: "；OI变化率缺失") +
            (futures.orderBookImbalance?.let { "；盘口买卖深度差${"%.2f".format(it)}" } ?: "；盘口深度缺失") +
            (futures.quoteVolume24h?.let { "；24小时成交额${"%.0f".format(it)} USDT" } ?: "；成交额缺失")
        return KnowledgeAssessment(direction, adjustment, detail, modules)
    }

    private fun macdHist(values: List<Double>): Double {
        if (values.size < 35) return 0.0
        fun series(xs: List<Double>, n: Int): List<Double> { val k=2.0/(n+1); val out=mutableListOf(xs.first()); xs.drop(1).forEach { out += it*k+out.last()*(1-k) }; return out }
        val line = series(values,12).zip(series(values,26)) { a,b -> a-b }
        return line.last() - series(line,9).last()
    }
}
