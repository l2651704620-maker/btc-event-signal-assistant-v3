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

data class FusionResult(
    val direction: String,
    val score: Int,
    val confidence: Int,
    val reason: String,
    val wave: WaveAnalysis,
    val raschke: ModuleSignal,
    val wyckoff: ModuleSignal,
    val marketRegime: String = "待识别",
    val riskLevel: String = "中",
    val riskWarning: String = "",
    val vwap: Double = 0.0,
    val atrPercent: Double = 0.0,
    val trendStage: TrendStage = TrendStage("待识别", 0, 0, ""),
    val rawDirection: String = ""
)

/** Rule-based fusion of Elliott-style structure candidates, Linda Raschke and Wyckoff concepts. */
object FusionStrategyEngine {
    /** V8: 10-minute-only direction engine. Uses closed candles and cross-timeframe agreement. */
    fun evaluate(
        oneMinute: List<StrategyCandle>,
        fiveMinute: List<StrategyCandle>,
        fifteenMinute: List<StrategyCandle>,
        oneHour: List<StrategyCandle>,
        horizonMinutes: Int,
        learningRecords: List<PredictionRecord> = emptyList(),
        futuresSnapshot: FuturesSnapshot = FuturesSnapshot()
    ): FusionResult {
        require(horizonMinutes == 10) { "V8仅支持10分钟预测" }
        require(oneMinute.size >= 60 && fiveMinute.size >= 30 && fifteenMinute.size >= 20) {
            "历史K线不足：至少需要60根1分钟、30根5分钟和20根15分钟已收盘K线"
        }
        val primary = oneMinute
        val last = primary.last()
        val closes = primary.map { it.close }
        val threeMinute = aggregate(primary, 3).takeLast(80)
        require(threeMinute.size >= 20) { "3分钟聚合K线不足，请等待数据完整" }
        val threeCloses = threeMinute.map { it.close }
        val fiveCloses = fiveMinute.map { it.close }
        val fifteenCloses = fifteenMinute.map { it.close }
        val hourlyCloses = oneHour.map { it.close }

        val wave = ElliottWaveEngine.analyze(primary)
        val trendStage = ElliottWaveEngine.trendStage(primary)
        val knowledge = TechnicalKnowledgeEngine.evaluate(primary, futuresSnapshot)
        val raschkeSignal = raschke(primary, fiveMinute)
        val wyckoffSignal = wyckoff(primary)

        fun trend(values: List<Double>): Int {
            if (values.size < 25) return 0
            val fast = ema(values, 9); val slow = ema(values, 21)
            val slope = values.last() - values[values.size - 4]
            return when {
                fast > slow && slope > 0 -> 1
                fast < slow && slope < 0 -> -1
                else -> 0
            }
        }
        val trend1 = trend(closes)
        val trend3 = trend(threeCloses)
        val trend5 = trend(fiveCloses)
        val trend15 = trend(fifteenCloses)
        val trend1h = trend(hourlyCloses)
        val atrNow = atr(primary, 14).coerceAtLeast(last.close * 0.0001)
        val atrPercent = atrNow / last.close * 100.0
        val vwapNow = rollingVwap(primary, 20)
        val vwapDirection = when { last.close > vwapNow -> 1; last.close < vwapNow -> -1; else -> 0 }
        val vwapDistanceAtr = abs(last.close - vwapNow) / atrNow
        val macdHist = macdHistogram(closes)
        val macdDirection = when { macdHist > 0 -> 1; macdHist < 0 -> -1; else -> 0 }
        val obvDirection = obvSlopeDirection(primary, 8)
        val rsi7 = rsi(closes, 7)
        val rsi14 = rsi(closes, 14)
        val rsiDirection = when { rsi14 >= 54 -> 1; rsi14 <= 46 -> -1; else -> 0 }
        val volumeAvg = primary.dropLast(1).takeLast(10).map { it.volume }.average().coerceAtLeast(1e-12)
        val volumeRatio = last.volume / volumeAvg
        val candleDirection = when { last.close > last.open -> 1; last.close < last.open -> -1; else -> 0 }
        val adx5 = adx(fiveMinute, 14)
        val emaSpread5 = abs(ema(fiveCloses, 9) - ema(fiveCloses, 21)) / atr(fiveMinute, 14).coerceAtLeast(fiveMinute.last().close * 0.0001)
        val marketRegime = when {
            atrPercent >= 0.20 -> "高波动"
            adx5 < 17.0 && emaSpread5 < 0.18 -> "震荡"
            trend5 > 0 && trend15 >= 0 -> "趋势上涨"
            trend5 < 0 && trend15 <= 0 -> "趋势下跌"
            else -> "方向切换"
        }
        val recent5 = fiveMinute.takeLast(6).dropLast(1)
        val latestFiveClose = fiveMinute.last().close
        val breakoutUp = recent5.isNotEmpty() && latestFiveClose > recent5.maxOf { it.high }
        val breakoutDown = recent5.isNotEmpty() && latestFiveClose < recent5.minOf { it.low }
        val breakoutDirection = if (breakoutUp) 1 else if (breakoutDown) -1 else 0
        val macroOpposes = (trend5 > 0 && trend15 < 0) || (trend5 < 0 && trend15 > 0)
        val stretchPenalty = vwapDistanceAtr > 2.0
        val overboughtReversal = rsi7 >= 88.0 && candleDirection < 0 && macdDirection <= 0
        val oversoldReversal = rsi7 <= 12.0 && candleDirection > 0 && macdDirection >= 0

        // Weight the 3m/5m structure above the noisy last 1m candle. 15m/1h are context only.
        val learning = AdaptiveLearningEngine.approvedWeights(learningRecords, 10, marketRegime)
        val waveWeight = learning["艾略特波浪"] ?: 1.0
        var vote = trend1 * 1.0 + trend3 * 2.0 + trend5 * 3.0 + trend15 * 1.5 + trend1h * 0.5
        vote += wave.direction * (wave.confidence / 100.0) * 1.5 * waveWeight
        vote += knowledge.direction * 1.5
        vote += raschkeSignal.direction * 1.0 + wyckoffSignal.direction * 0.75
        vote += vwapDirection * 0.75 + macdDirection * 1.0 + obvDirection * 0.75 + rsiDirection * 0.5
        vote += breakoutDirection * 0.75
        if (volumeRatio >= 1.25) vote += candleDirection * 0.5
        if (macroOpposes) vote -= trend5 * 1.25
        if (stretchPenalty) vote -= vwapDirection * 1.0
        if (overboughtReversal) vote -= 1.5
        if (oversoldReversal) vote += 1.5
        val momentum3 = threeCloses.last() - threeCloses[threeCloses.lastIndex - 3]
        val momentum5 = fiveCloses.last() - fiveCloses[fiveCloses.lastIndex - 2]
        val side = when {
            vote > 0.15 -> 1
            vote < -0.15 -> -1
            trend3 != 0 -> trend3
            trend5 != 0 -> trend5
            macdDirection != 0 -> macdDirection
            momentum3 > 0 -> 1
            momentum3 < 0 -> -1
            momentum5 > 0 -> 1
            momentum5 < 0 -> -1
            candleDirection != 0 -> candleDirection
            else -> 1
        }

        val trendAgreement = listOf(trend1, trend3, trend5, trend15).count { it == side }
        val trendConflict = listOf(trend3, trend5, trend15).count { it == -side }
        val momentumAgreement = listOf(vwapDirection, macdDirection, obvDirection, rsiDirection).count { it == side }
        val score = (45 + trendAgreement * 5 + momentumAgreement * 3 +
            (if (breakoutDirection == side) 5 else 0) + (if (wave.direction == side && wave.confidence >= 60) 4 else 0) +
            (if (volumeRatio >= 1.1 && candleDirection == side) 3 else 0) - trendConflict * 5 -
            (if (macroOpposes) 5 else 0) - (if (stretchPenalty) 6 else 0) -
            (if (marketRegime == "高波动") 7 else 0) - (if (marketRegime == "震荡") 4 else 0) +
            knowledge.adjustment / 2).coerceIn(25, 90)

        val riskFlags = mutableListOf<String>()
        if (trendConflict >= 2) riskFlags += "3/5/15分钟方向明显分歧"
        if (macroOpposes) riskFlags += "15分钟背景与5分钟方向相反"
        if (marketRegime == "高波动") riskFlags += "1分钟ATR波动异常偏高"
        if (marketRegime == "震荡") riskFlags += "当前处于低趋势强度震荡区"
        if (stretchPenalty && (macdDirection == -side || candleDirection == -side || trend3 != side)) riskFlags += "价格偏离VWAP超过2倍ATR且短线动能转弱"
        if (overboughtReversal || oversoldReversal) riskFlags += "短线动能出现极端反转迹象"
        if (volumeRatio < 0.55) riskFlags += "最近1分钟成交量明显萎缩"
        val riskLevel = when {
            riskFlags.size >= 3 || (marketRegime == "高波动" && macroOpposes) -> "高"
            riskFlags.isNotEmpty() -> "中"
            else -> "低"
        }
        val riskWarning = riskFlags.joinToString("；").ifEmpty { "未检测到明显规则型风险；仍需注意短周期随机波动" }
        val rawDirection = if (side > 0) "看涨" else "看跌"
        val reverse = riskLevel == "中" || riskLevel == "高"
        val direction = if (reverse) if (rawDirection == "看涨") "看跌" else "看涨" else rawDirection
        val outputRule = if (reverse) "${riskLevel}风险反向规则：原始$rawDirection → 最终$direction" else "低风险保持原方向$direction"
        val reason = "V8十分钟多周期研判：$outputRule；原始方向=$rawDirection；市场状态=$marketRegime；趋势投票1m=${label(trend1)}、3m=${label(trend3)}、5m=${label(trend5)}、15m=${label(trend15)}、1h=${label(trend1h)}；动能共振${momentumAgreement}/4；RSI7=${"%.1f".format(rsi7)}、RSI14=${"%.1f".format(rsi14)}；MACD=${label(macdDirection)}；VWAP=${label(vwapDirection)}；OBV=${label(obvDirection)}；成交量=${"%.2f".format(volumeRatio)}倍；ATR=${"%.3f".format(atrPercent)}%；波浪=${wave.label}(${wave.confidence}/100)；趋势阶段=${trendStage.label}；Raschke=${label(raschkeSignal.direction)}；Wyckoff=${label(wyckoffSignal.direction)}；知识库=${knowledge.detail}；风险=$riskLevel（$riskWarning）；加权投票=${"%.2f".format(vote)}。评分是规则匹配度，不是胜率概率。"
        return FusionResult(direction, score, score, reason, wave, raschkeSignal, wyckoffSignal, marketRegime, riskLevel, riskWarning, vwapNow, atrPercent, trendStage, rawDirection)
    }

    private fun aggregate(source: List<StrategyCandle>, minutes: Int): List<StrategyCandle> {
        val width = minutes * 60_000L
        return source.groupBy { it.openTime / width }.toSortedMap().values
            .filter { group -> group.size == minutes && group.zipWithNext().all { (a, b) -> b.openTime - a.openTime == 60_000L } }
            .map { group ->
                val rows = group.sortedBy { it.openTime }
                StrategyCandle(rows.first().openTime / width * width, rows.first().open,
                    rows.maxOf { it.high }, rows.minOf { it.low }, rows.last().close, rows.sumOf { it.volume })
            }
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
