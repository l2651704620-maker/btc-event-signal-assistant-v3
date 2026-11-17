package com.example.btcassistant.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Closed exchange candle used by the deterministic three-strategy engine. */
data class StrategyCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class ModuleSignal(val direction: Int, val strength: Double, val detail: String) {
    // direction: +1 bullish, -1 bearish, 0 neutral. Strength is a rule score, not probability.
}

data class HallOfFameSignal(val master: String, val horizonMinutes: Int, val direction: String, val strength: Int, val detail: String)

data class FusionResult(
    val direction: String,
    val score: Int,
    val confidence: Int,
    val reason: String,
    val gann: ModuleSignal,
    val raschke: ModuleSignal,
    val wyckoff: ModuleSignal,
    val marketRegime: String = "待识别",
    val riskLevel: String = "中",
    val riskWarning: String = "",
    val vwap: Double = 0.0,
    val atrPercent: Double = 0.0
)

/**
 * Rule-based adaptations of public Gann, Linda Raschke and Wyckoff concepts.
 * These are experimental, testable approximations, not a claim to reproduce any trader's private system.
 */
object FusionStrategyEngine {
    /** Fast, fixed-rule short-term engine. It intentionally does not require all three masters to agree. */
    fun evaluate(
        oneMinute: List<StrategyCandle>,
        fiveMinute: List<StrategyCandle>,
        fifteenMinute: List<StrategyCandle>,
        oneHour: List<StrategyCandle>,
        horizonMinutes: Int
    ): FusionResult {
        require(horizonMinutes == 10 || horizonMinutes == 30) { "仅支持10分钟或30分钟周期" }
        val primary = if (horizonMinutes == 10) oneMinute else fiveMinute
        val context = if (horizonMinutes == 10) fiveMinute else fifteenMinute
        val macro = if (horizonMinutes == 10) fifteenMinute else oneHour
        require(primary.size >= 60 && context.size >= 30 && macro.size >= 20) { "历史K线不足：请等待主周期60根、确认周期30根和背景周期20根已收盘K线" }

        val g = gann(primary)
        val r = raschke(primary, context)
        val w = wyckoff(primary)
        val closes = primary.map { it.close }
        val last = primary.last()
        val fast = ema(closes, 9)
        val slow = ema(closes, 21)
        val atrNow = atr(primary, 14).coerceAtLeast(last.close * 0.0001)
        val trend = when { fast > slow -> 1; fast < slow -> -1; else -> 0 }
        val contextCloses = context.map { it.close }
        val contextFast = ema(contextCloses, 9)
        val contextSlow = ema(contextCloses, 21)
        val contextTrend = when { contextFast > contextSlow -> 1; contextFast < contextSlow -> -1; else -> 0 }

        // Entry trigger: a 3-bar close breakout OR a confirmed EMA9 pullback rejection.
        val prior = primary.dropLast(1).takeLast(3)
        val priorHigh = prior.maxOf { it.high }
        val priorLow = prior.minOf { it.low }
        val bullishBreak = last.close > priorHigh
        val bearishBreak = last.close < priorLow
        val bullishPullback = last.low <= fast + atrNow * 0.20 && last.close > fast && last.close > last.open
        val bearishPullback = last.high >= fast - atrNow * 0.20 && last.close < fast && last.close < last.open
        val trigger = if (trend > 0) bullishBreak || bullishPullback else if (trend < 0) bearishBreak || bearishPullback else false
        val rsi = rsi(closes, if (horizonMinutes == 10) 7 else 14)
        val rsi14 = rsi(closes, 14)
        val momentumOk = if (trend > 0) rsi in 42.0..76.0 else if (trend < 0) rsi in 24.0..58.0 else false
        val volumeAvg = primary.dropLast(1).takeLast(10).map { it.volume }.average().coerceAtLeast(1e-12)
        val volumeRatio = last.volume / volumeAvg
        val vwapNow = rollingVwap(primary, 20)
        val vwapDirection = when { last.close > vwapNow -> 1; last.close < vwapNow -> -1; else -> 0 }
        val macdHist = macdHistogram(closes)
        val macdDirection = when { macdHist > 0.0 -> 1; macdHist < 0.0 -> -1; else -> 0 }
        val obvDirection = obvSlopeDirection(primary, 8)
        val atrPercent = atrNow / last.close * 100.0
        val vwapDistanceAtr = abs(last.close - vwapNow) / atrNow
        val regimeAdx = adx(primary, 14)
        val marketRegime = when {
            atrPercent >= if (horizonMinutes == 10) 0.80 else 1.50 -> "高波动"
            abs(ema(closes, 9) - ema(closes, 21)) / atrNow < 0.12 && regimeAdx < 18.0 -> "震荡"
            trend > 0 && contextTrend >= 0 -> "趋势上涨"
            trend < 0 && contextTrend <= 0 -> "趋势下跌"
            else -> "方向切换"
        }
        val macroCloses = macro.map { it.close }
        val macroTrend = if (macroCloses.size >= 20) ema(macroCloses, 9) - ema(macroCloses, 20) else 0.0
        val macroAtr = atr(macro, 14).coerceAtLeast(macro.last().close * 0.0001)
        val macroOpposes = (trend > 0 && macroTrend < -macroAtr * 0.08) || (trend < 0 && macroTrend > macroAtr * 0.08)

        // Always return a directional assessment when valid market data exists.
        // A missing breakout/pullback trigger reduces the score; it no longer blocks the user's requested analysis.
        val masterSupport = listOf(g, r, w).count { it.direction == trend && it.strength >= 55.0 }
        val vote = (trend * 3) + (contextTrend * 2) + (if (macroTrend > 0) 1 else if (macroTrend < 0) -1 else 0) +
            (g.direction * (g.strength / 35.0).toInt()) + (r.direction * (r.strength / 35.0).toInt()) +
            (w.direction * (w.strength / 35.0).toInt()) +
            (if (rsi14 >= 52.0) 1 else if (rsi14 <= 48.0) -1 else 0) +
            vwapDirection + macdDirection + obvDirection +
            (if (last.close > last.open) 1 else if (last.close < last.open) -1 else 0)
        val side = when {
            trend != 0 && trigger && momentumOk -> trend
            vote > 0 -> 1
            vote < 0 -> -1
            last.close >= last.open -> 1
            else -> -1
        }
        val vwapBonus = if (vwapDirection == side) 4 else -3
        val macdBonus = if (macdDirection == side) 4 else -3
        val obvBonus = if (obvDirection == side) 3 else if (obvDirection == -side) -2 else 0
        val rsiBonus = if ((side > 0 && rsi14 in 50.0..72.0) || (side < 0 && rsi14 in 28.0..50.0)) 3 else -2
        val stretchPenalty = if (vwapDistanceAtr > 1.8) 5 else 0
        val volatilityPenalty = if (marketRegime == "高波动") 7 else 0
        val rawScore = (48 + (if (trend != 0) 10 else 0) + (if (trigger) 12 else 0) + (if (momentumOk) 6 else 0) +
            (if (contextTrend == side) 6 else 0) + (if (volumeRatio >= 0.75) 3 else 0) + masterSupport * 2 +
            vwapBonus + macdBonus + obvBonus + rsiBonus - stretchPenalty - volatilityPenalty -
            (if (contextTrend == -side && contextTrend != 0) 7 else 0) - (if (macroOpposes) 7 else 0) -
            (if (!trigger) 7 else 0) - (if (!momentumOk) 4 else 0)).coerceIn(30, 92)
        val riskFlags = mutableListOf<String>()
        if (macroOpposes) riskFlags += "背景周期与信号方向相反"
        if (marketRegime == "高波动") riskFlags += "当前ATR波动偏高"
        if (vwapDistanceAtr > 1.8) riskFlags += "价格偏离VWAP超过1.8倍ATR，追价风险较高"
        if (contextTrend == -side && contextTrend != 0) riskFlags += "确认周期方向冲突"
        if (!trigger) riskFlags += "尚未出现突破或回踩确认"
        val riskLevel = when { riskFlags.size >= 3 || marketRegime == "高波动" && macroOpposes -> "高"; riskFlags.size >= 1 -> "中"; else -> "低" }
        val riskWarning = riskFlags.joinToString("；").ifEmpty { "暂未发现明显的规则型风险警报，仍需关注实时波动" }
        val direction = if (side > 0) "看涨" else "看跌"
        val reason = "多策略综合研判：市场状态=$marketRegime；EMA9/21趋势${label(trend)}；突破/回踩${if (trigger) "确认" else "未触发（降低评分）"}；RSI${if (horizonMinutes == 10) 7 else 14}=${"%.1f".format(rsi)}，RSI14=${"%.1f".format(rsi14)}；MACD柱体方向${label(macdDirection)}；VWAP=${"%.2f".format(vwapNow)}（价格${if (vwapDirection > 0) "在上方" else if (vwapDirection < 0) "在下方" else "附近"}）；OBV方向${label(obvDirection)}；ATR=${"%.3f".format(atrPercent)}%；确认周期${label(contextTrend)}；背景周期${if (macroOpposes) "反向" else "未明显反向"}；量能${"%.2f".format(volumeRatio)}倍；江恩/拉什克/威科夫共振${masterSupport}/3；风险等级=$riskLevel（$riskWarning）；多维投票$vote。方向始终给出，评分代表规则匹配度，不是胜率概率。"
        return FusionResult(direction, rawScore, rawScore, reason, g, r, w, marketRegime, riskLevel, riskWarning, vwapNow, atrPercent)
    }

    /** Independent classic-strategy alerts; these are not mixed into short-term win-rate statistics. */
    fun evaluateHallOfFame(
        oneMinute: List<StrategyCandle>, fiveMinute: List<StrategyCandle>, fifteenMinute: List<StrategyCandle>
    ): List<HallOfFameSignal> {
        val out = mutableListOf<HallOfFameSignal>()
        for (horizon in listOf(10, 30)) {
            val primary = if (horizon == 10) oneMinute else fiveMinute
            val context = if (horizon == 10) fiveMinute else fifteenMinute
            if (primary.size < 80 || context.size < 40) continue
            val modules = listOf("江恩" to gann(primary), "琳达·拉什克" to raschke(primary, context), "威科夫" to wyckoff(primary))
            modules.forEach { (name, signal) ->
                if (signal.direction != 0 && signal.strength >= 75.0) {
                    out += HallOfFameSignal(name, horizon, if (signal.direction > 0) "看涨" else "看跌", signal.strength.toInt(), signal.detail)
                }
            }
        }
        return out
    }

    /** Rolling volume-weighted typical price; used as a local VWAP approximation. */
    private fun rollingVwap(xs: List<StrategyCandle>, period: Int): Double {
        val rows = xs.takeLast(period)
        val totalVolume = rows.sumOf { it.volume }.coerceAtLeast(1e-12)
        return rows.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / totalVolume
    }

    /** MACD(12,26,9) histogram from closed candle closes. */
    private fun macdHistogram(xs: List<Double>): Double {
        if (xs.size < 35) return 0.0
        fun emaSeries(values: List<Double>, period: Int): List<Double> {
            val k = 2.0 / (period + 1.0)
            val out = ArrayList<Double>(values.size)
            var current = values.first()
            out += current
            values.drop(1).forEach { value -> current = value * k + current * (1.0 - k); out += current }
            return out
        }
        val fast = emaSeries(xs, 12)
        val slow = emaSeries(xs, 26)
        val line = fast.indices.map { fast[it] - slow[it] }
        val signal = emaSeries(line, 9)
        return line.last() - signal.last()
    }

    /** OBV direction proxy over recent closed candles, not exchange trade-level CVD. */
    private fun obvSlopeDirection(xs: List<StrategyCandle>, lookback: Int): Int {
        if (xs.size < lookback + 2) return 0
        var obv = 0.0
        val rows = xs.takeLast(lookback + 1)
        for (i in 1 until rows.size) {
            val current = rows[i]; val previous = rows[i - 1]
            obv += when { current.close > previous.close -> current.volume; current.close < previous.close -> -current.volume; else -> 0.0 }
        }
        val scale = rows.drop(1).sumOf { it.volume }.coerceAtLeast(1e-12)
        val normalized = obv / scale
        return when { normalized > 0.12 -> 1; normalized < -0.12 -> -1; else -> 0 }
    }

    private fun rsi(xs: List<Double>, period: Int): Double {
        if (xs.size < period + 1) return 50.0
        val changes = xs.takeLast(period + 1).zipWithNext { a, b -> b - a }
        val gains = changes.filter { it > 0.0 }.sum() / period
        val losses = -changes.filter { it < 0.0 }.sum() / period
        if (losses <= 1e-12) return if (gains <= 1e-12) 50.0 else 100.0
        val rs = gains / losses
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun gann(xs: List<StrategyCandle>): ModuleSignal {
        val a = atr(xs, 14).coerceAtLeast(xs.last().close * 0.0001)
        val last = xs.last()
        val slope = (last.close - xs[xs.size - 21].close) / (a * 20.0)
        val trendDir = when { slope > 0.12 -> 1; slope < -0.12 -> -1; else -> 0 }
        val pivots = confirmedPivots(xs, 3)
        val pivot = pivots.lastOrNull()
        var timeBonus = 0.0
        var levelDir = 0
        val notes = mutableListOf<String>()
        if (pivot != null) {
            val barsSince = xs.lastIndex - pivot.first
            val cycle = listOf(7, 14, 21, 30, 45, 60).minByOrNull { abs(it - barsSince) }
            if (cycle != null && abs(cycle - barsSince) <= 1) {
                timeBonus = 15.0
                notes += "接近${cycle}根K线时间窗口"
            }
            val swing = pivots.dropLast(1).lastOrNull()
            if (swing != null) {
                val high = max(pivot.second, swing.second)
                val low = min(pivot.second, swing.second)
                val range = high - low
                if (range > a) {
                    val levels = if (pivot.second > swing.second) listOf(0.25, 1.0/3.0, 0.5, 2.0/3.0, 0.75).map { high - range * it }
                    else listOf(0.25, 1.0/3.0, 0.5, 2.0/3.0, 0.75).map { low + range * it }
                    val near = levels.minByOrNull { abs(last.close - it) }
                    if (near != null && abs(last.close - near) <= a * 0.35) {
                        levelDir = when { last.close > last.open -> 1; last.close < last.open -> -1; else -> 0 }
                        notes += "接近关键回撤位"
                    }
                }
            }
            // Square-of-Nine-style projections are treated only as confluence zones, never standalone signals.
            val root = sqrt(pivot.second.coerceAtLeast(1.0))
            val squareLevels = listOf(45.0, 90.0, 180.0, 270.0, 360.0).flatMap { degrees ->
                val step = degrees / 180.0
                listOf((root + step) * (root + step), (root - step) * (root - step)).filter { it > 0.0 }
            }
            val squareNear = squareLevels.minByOrNull { abs(last.close - it) }
            if (squareNear != null && abs(last.close - squareNear) <= a * 0.35) {
                notes += "接近九方图投影价格区"
                if (levelDir == 0) levelDir = when { last.close > last.open -> 1; last.close < last.open -> -1; else -> 0 }
            }
            if (timeBonus > 0.0 && squareNear != null && abs(last.close - squareNear) <= a * 0.35) notes += "价格与时间窗口共振"
        }
        val direction = when {
            trendDir != 0 && (levelDir == 0 || levelDir == trendDir) -> trendDir
            levelDir != 0 && timeBonus > 0 -> levelDir
            else -> 0
        }
        val strength = (abs(slope).coerceIn(0.0, 1.0) * 65.0 + timeBonus + if (levelDir == direction && direction != 0) 10.0 else 0.0).coerceIn(0.0, 100.0)
        notes.add(0, "ATR标准化角度斜率=${"%.2f".format(slope)}")
        notes += "九方图价格投影仅作观察，不单独触发交易"
        return ModuleSignal(direction, strength, notes.joinToString("、"))
    }

    private fun raschke(primary: List<StrategyCandle>, context: List<StrategyCandle>): ModuleSignal {
        val last = primary.last()
        val closes = primary.map { it.close }
        val ema20 = ema(closes, 20)
        val ema50 = ema(closes, 50)
        val adxNow = adx(primary, 14)
        val adxPrev = adx(primary.dropLast(3), 14)
        val atrNow = atr(primary, 14).coerceAtLeast(last.close * 0.0001)
        val trend = when { ema20 > ema50 && context.last().close >= ema(context.map { it.close }, 20) -> 1; ema20 < ema50 && context.last().close <= ema(context.map { it.close }, 20) -> -1; else -> 0 }
        val notes = mutableListOf<String>()
        var dir = 0
        var strength = 0.0
        // Holy Grail: established trend, ADX strong/rising, EMA20 pullback and close-confirmed trigger.
        val nearEma = abs(last.close - ema20) <= atrNow * 0.35 || primary.takeLast(3).any { abs(it.low - ema20) <= atrNow * 0.25 || abs(it.high - ema20) <= atrNow * 0.25 }
        if (trend != 0 && adxNow >= 30.0 && adxNow > adxPrev && nearEma) {
            val trigger = if (trend > 0) last.close > primary[primary.lastIndex - 1].high else last.close < primary[primary.lastIndex - 1].low
            if (trigger) { dir = trend; strength = max(strength, 88.0); notes += "Holy Grail趋势回调确认" }
        }
        // Turtle Soup: sweep the prior 20-bar extreme and close back inside the prior range.
        val prior = primary.dropLast(1).takeLast(20)
        if (prior.size == 20) {
            val priorLow = prior.minOf { it.low }; val priorHigh = prior.maxOf { it.high }
            if (last.low < priorLow && last.close > priorLow) { dir = 1; strength = max(strength, 82.0); notes += "Turtle Soup向下假突破收回" }
            if (last.high > priorHigh && last.close < priorHigh) { dir = -1; strength = max(strength, 82.0); notes += "Turtle Soup向上假突破回落" }
        }
        // Anti-style momentum turn: stochastic K/D cross in the direction of the established trend.
        val stochNow = stochastic(primary)
        val stochPrev = stochastic(primary.dropLast(1))
        if (trend > 0 && stochPrev.first <= stochPrev.second && stochNow.first > stochNow.second && stochNow.first < 65.0) { dir = 1; strength = max(strength, 68.0); notes += "Anti动量向上转折" }
        if (trend < 0 && stochPrev.first >= stochPrev.second && stochNow.first < stochNow.second && stochNow.first > 35.0) { dir = -1; strength = max(strength, 68.0); notes += "Anti动量向下转折" }
        // NR7: previous bar is the narrowest of seven; current close breaks its range.
        if (primary.size >= 9) {
            val seven = primary.takeLast(8).dropLast(1)
            val narrow = seven.minByOrNull { it.high - it.low }
            val prev = primary[primary.lastIndex - 1]
            if (narrow != null && prev == narrow && last.close > prev.high) { dir = 1; strength = max(strength, 72.0); notes += "NR7向上突破" }
            if (narrow != null && prev == narrow && last.close < prev.low) { dir = -1; strength = max(strength, 72.0); notes += "NR7向下突破" }
        }
        if (notes.isEmpty()) notes += "未出现有效趋势回调、假突破或动量触发"
        return ModuleSignal(dir, strength, "ADX=${"%.1f".format(adxNow)}、EMA20/50趋势=${label(trend)}、" + notes.joinToString("、"))
    }

    private fun wyckoff(xs: List<StrategyCandle>): ModuleSignal {
        val last = xs.last()
        val rangeBars = xs.dropLast(1).takeLast(20)
        val avgVol = rangeBars.map { it.volume }.average().coerceAtLeast(1e-12)
        val avgRange = rangeBars.map { it.high - it.low }.average().coerceAtLeast(last.close * 0.0001)
        val resistance = rangeBars.maxOf { it.high }
        val support = rangeBars.minOf { it.low }
        val volRatio = last.volume / avgVol
        val candleRange = (last.high - last.low).coerceAtLeast(1e-12)
        val closeLocation = (last.close - last.low) / candleRange
        val notes = mutableListOf<String>()
        var dir = 0
        var strength = 0.0
        if (last.low < support && last.close > support && closeLocation > 0.60 && volRatio >= 1.15) { dir = 1; strength = 88.0; notes += "Spring：跌破支撑后收回" }
        if (last.high > resistance && last.close < resistance && closeLocation < 0.40 && volRatio >= 1.15) { dir = -1; strength = 88.0; notes += "Upthrust：突破阻力后回落" }
        if (last.close > resistance + atr(xs, 14) * 0.10 && volRatio >= 1.25 && closeLocation > 0.70) { dir = 1; strength = max(strength, 78.0); notes += "SOS：放量有效突破区间" }
        if (last.close < support - atr(xs, 14) * 0.10 && volRatio >= 1.25 && closeLocation < 0.30) { dir = -1; strength = max(strength, 78.0); notes += "SOW：放量有效跌破区间" }
        if (volRatio >= 1.8 && candleRange <= avgRange * 0.55) {
            notes += "高成交量但价格推进有限，出现吸收迹象"
            if (strength < 70.0) { dir = 0; strength = 0.0 }
        }
        if (notes.isEmpty()) notes += "尚无明确吸筹、派发或供需突破证据"
        return ModuleSignal(dir, strength, "成交量为近期均量${"%.2f".format(volRatio)}倍、" + notes.joinToString("、"))
    }

    private fun confirmedPivots(xs: List<StrategyCandle>, wing: Int): List<Pair<Int, Double>> {
        val out = mutableListOf<Pair<Int, Double>>()
        for (i in wing until xs.size - wing) {
            val c = xs[i]
            val leftRight = (i - wing..i + wing).filter { it != i }.map { xs[it] }
            if (leftRight.all { c.high > it.high }) out += i to c.high
            else if (leftRight.all { c.low < it.low }) out += i to c.low
        }
        return out
    }

    /** Stochastic (7,10,3): raw %K over 7 bars, %D SMA10, signal SMA3 of %D. */
    private fun stochastic(xs: List<StrategyCandle>): Pair<Double, Double> {
        fun raw(end: Int): Double {
            val s = xs.subList(end - 6, end + 1)
            val lo = s.minOf { it.low }; val hi = s.maxOf { it.high }
            return if (hi <= lo) 50.0 else ((xs[end].close - lo) / (hi - lo) * 100.0).coerceIn(0.0, 100.0)
        }
        val rawValues = (xs.size - 12..xs.lastIndex).map { raw(it) }
        val dValues = rawValues.indices.filter { it >= 9 }.map { i -> rawValues.subList(i - 9, i + 1).average() }
        val d = dValues.last()
        val signal = dValues.takeLast(3).average()
        return d to signal
    }

    private fun ema(xs: List<Double>, period: Int): Double {
        if (xs.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        return xs.drop(1).fold(xs.first()) { acc, v -> v * k + acc * (1.0 - k) }
    }

    private fun atr(xs: List<StrategyCandle>, period: Int): Double {
        if (xs.size < 2) return 0.0
        return xs.takeLast(period + 1).drop(1).mapIndexed { i, c ->
            val prev = xs[xs.size - min(xs.size, period + 1) + i]
            max(c.high - c.low, max(abs(c.high - prev.close), abs(c.low - prev.close)))
        }.average()
    }

    private fun adx(xs: List<StrategyCandle>, period: Int): Double {
        if (xs.size < period + 2) return 0.0
        var plus = 0.0; var minus = 0.0; var tr = 0.0
        val rows = xs.takeLast(period + 1)
        for (i in 1 until rows.size) {
            val a = rows[i - 1]; val b = rows[i]
            val up = b.high - a.high; val down = a.low - b.low
            if (up > down && up > 0) plus += up
            if (down > up && down > 0) minus += down
            tr += max(b.high - b.low, max(abs(b.high - a.close), abs(b.low - a.close)))
        }
        if (tr <= 0) return 0.0
        val p = plus / tr * 100.0; val m = minus / tr * 100.0
        return (abs(p - m) / (p + m).coerceAtLeast(1e-9) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun label(direction: Int) = when (direction) { 1 -> "看涨"; -1 -> "看跌"; else -> "中性" }
}
