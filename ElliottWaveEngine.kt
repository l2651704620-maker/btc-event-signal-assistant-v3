package com.example.btcassistant.data

import kotlin.math.abs
import kotlin.math.max

data class WaveAnalysis(
    val label: String,
    val direction: Int,
    val confidence: Int,
    val detail: String,
    val invalidationPrice: Double? = null
)

data class TrendStage(
    val label: String,
    val direction: Int,
    val strength: Int,
    val detail: String
)

/**
 * Conservative Elliott-style structure candidate finder.
 * Uses only confirmed local pivots; the last two bars are never treated as confirmed pivots.
 * It reports a candidate, not a definitive Elliott count.
 */
object ElliottWaveEngine {
    private data class Pivot(val index: Int, val price: Double, val high: Boolean)

    fun analyze(candles: List<StrategyCandle>): WaveAnalysis {
        if (candles.size < 24) return WaveAnalysis("结构样本不足", 0, 0, "至少需要24根已收盘K线")
        val atr = averageTrueRange(candles, 14).coerceAtLeast(candles.last().close * 0.0001)
        val pivots = confirmedPivots(candles, 2, atr * 0.45).takeLast(6)
        if (pivots.size < 4) return WaveAnalysis("波浪结构不清晰", 0, 20, "已确认摆动高低点不足，不强行计数")
        val moves = pivots.zipWithNext { a, b -> b.price - a.price }
        val upMoves = moves.count { it > 0 }
        val downMoves = moves.count { it < 0 }
        val direction = when {
            upMoves >= moves.size - 1 -> 1
            downMoves >= moves.size - 1 -> -1
            else -> 0
        }
        val first = pivots.first()
        val last = pivots.last()
        val range = abs(last.price - first.price)
        val noise = moves.sumOf { abs(it) }.coerceAtLeast(atr)
        val efficiency = (range / noise).coerceIn(0.0, 1.0)
        val recent = candles.takeLast(8)
        val lastClose = candles.last().close
        val priorRangeHigh = recent.dropLast(1).maxOf { it.high }
        val priorRangeLow = recent.dropLast(1).minOf { it.low }

        // Candidate five-wave impulse: six alternating pivots, with Elliott's key
        // non-overlap / wave-2 retracement / wave-3-not-shortest checks.
        val six = pivots.takeLast(6)
        val bullImpulse = six.size == 6 &&
            !six[0].high && six[1].high && !six[2].high && six[3].high && !six[4].high && six[5].high &&
            six[2].price > six[0].price && six[3].price > six[1].price && six[4].price > six[1].price &&
            abs(six[3].price-six[2].price) >= minOf(abs(six[1].price-six[0].price), abs(six[5].price-six[4].price))
        val bearImpulse = six.size == 6 &&
            six[0].high && !six[1].high && six[2].high && !six[3].high && six[4].high && !six[5].high &&
            six[2].price < six[0].price && six[3].price < six[1].price && six[4].price < six[1].price &&
            abs(six[3].price-six[2].price) >= minOf(abs(six[1].price-six[0].price), abs(six[5].price-six[4].price))
        val impulse = (bullImpulse || bearImpulse) && efficiency >= 0.42
        val correction = !impulse && six.size >= 4 && direction == 0 &&
            ((six.last().price < six[six.lastIndex - 1].price && six[six.lastIndex - 1].price > six.first().price) ||
             (six.last().price > six[six.lastIndex - 1].price && six[six.lastIndex - 1].price < six.first().price))

        val label: String
        val dir: Int
        val confidence: Int
        val detail: String
        if (impulse) {
            dir = if (bullImpulse) 1 else -1
            label = if (dir > 0) "推动浪候选（上行1-5结构）" else "推动浪候选（下行1-5结构）"
            confidence = (48 + efficiency * 27 + if (lastClose > priorRangeHigh || lastClose < priorRangeLow) 8 else 0).toInt().coerceIn(40, 82)
            detail = "六个已确认摆动点满足第2浪不越起点、第4浪不重叠及第3浪非最短等检查；仍需后续行情确认"
        } else if (correction) {
            dir = if (last.price >= first.price) 1 else -1
            label = "调整浪候选（ABC/复杂调整待确认）"
            confidence = (35 + efficiency * 20).toInt().coerceIn(25, 58)
            detail = "摆动结构呈重叠/回撤特征，暂不能区分简单ABC与复杂调整"
        } else {
            dir = when {
                lastClose > priorRangeHigh -> 1
                lastClose < priorRangeLow -> -1
                else -> 0
            }
            label = "波浪结构不清晰"
            confidence = if (dir == 0) 18 else 30
            detail = "未满足可靠推动浪或调整浪条件；不强制套用五浪计数"
        }
        val invalidation = when (dir) {
            1 -> pivots.filter { !it.high }.lastOrNull()?.price
            -1 -> pivots.filter { it.high }.lastOrNull()?.price
            else -> null
        }
        return WaveAnalysis(label, dir, confidence, detail, invalidation)
    }

    fun trendStage(candles: List<StrategyCandle>): TrendStage {
        if (candles.size < 30) return TrendStage("趋势样本不足", 0, 0, "等待更多已收盘K线")
        val closes = candles.map { it.close }
        val ema9 = ema(closes, 9)
        val ema21 = ema(closes, 21)
        val ema50 = ema(closes, 50)
        val atr = averageTrueRange(candles, 14).coerceAtLeast(closes.last() * 0.0001)
        val slope = (ema9 - ema21) / atr
        val direction = when { ema9 > ema21 && closes.last() > ema50 -> 1; ema9 < ema21 && closes.last() < ema50 -> -1; else -> 0 }
        val ranges = candles.takeLast(6)
        val body = ranges.sumOf { abs(it.close - it.open) }
        val total = ranges.sumOf { (it.high - it.low).coerceAtLeast(0.0) }.coerceAtLeast(atr)
        val expansion = total / (atr * 6.0)
        val recentMomentum = (closes.last() - closes[closes.size - 6]) / atr
        val stage = when {
            direction == 0 -> "震荡/方向切换"
            abs(slope) < 0.18 -> "趋势衰竭或整理"
            abs(recentMomentum) > 2.2 && expansion > 1.15 -> "趋势加速"
            abs(recentMomentum) > 0.65 -> "趋势延续"
            else -> "趋势启动/确认中"
        }
        val strength = (35 + abs(slope) * 20 + abs(recentMomentum) * 8).toInt().coerceIn(20, 85)
        return TrendStage(stage, direction, strength, "EMA9/21/50结构、近5根动量与ATR波动扩张综合识别")
    }

    private fun confirmedPivots(xs: List<StrategyCandle>, wing: Int, minMove: Double): List<Pivot> {
        val out = mutableListOf<Pivot>()
        for (i in wing until xs.size - wing) {
            val c = xs[i]
            val neighborhood = xs.subList(i - wing, i + wing + 1)
            val high = neighborhood.all { c.high >= it.high }
            val low = neighborhood.all { c.low <= it.low }
            if (high == low) continue
            val p = Pivot(i, if (high) c.high else c.low, high)
            val prev = out.lastOrNull()
            if (prev == null) out += p
            else if (prev.high == p.high) {
                if ((p.high && p.price > prev.price) || (!p.high && p.price < prev.price)) out[out.lastIndex] = p
            } else if (abs(p.price - prev.price) >= minMove) out += p
        }
        return out
    }

    private fun averageTrueRange(xs: List<StrategyCandle>, period: Int): Double {
        val rows = xs.takeLast(period + 1)
        if (rows.size < 2) return 0.0
        return rows.zipWithNext { a, b ->
            max(b.high - b.low, max(abs(b.high - a.close), abs(b.low - a.close)))
        }.average()
    }
    private fun ema(xs: List<Double>, period: Int): Double {
        val k = 2.0 / (period + 1.0)
        return xs.drop(1).fold(xs.first()) { a, b -> b * k + a * (1.0 - k) }
    }
}
