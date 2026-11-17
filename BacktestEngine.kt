package com.example.btcassistant.data

import kotlin.math.abs
import kotlin.math.max

/** Historical simulation with next-bar-open entry, fixed holding horizon and configurable costs. */
data class BacktestStats(
    val horizonMinutes: Int,
    val signals: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val winRatePct: Double,
    val coveragePct: Double,
    val netReturnPct: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double
)

data class BacktestPeriodReport(val tenMinute: BacktestStats, val thirtyMinute: BacktestStats)
data class WalkForwardReport(val development: BacktestPeriodReport, val validation: BacktestPeriodReport, val outOfSample: BacktestPeriodReport)

object BacktestEngine {
    /** feePerSide and slippagePerSide are fractions; defaults model 0.10% fee + 0.02% slippage per side. */
    fun run(
        oneMinute: List<StrategyCandle>,
        fiveMinute: List<StrategyCandle>,
        fifteenMinute: List<StrategyCandle>,
        oneHour: List<StrategyCandle>,
        feePerSide: Double = 0.001,
        slippagePerSide: Double = 0.0002,
        evaluationStartTime: Long? = null,
        evaluationEndTime: Long? = null
    ): BacktestPeriodReport {
        require(oneMinute.size >= 129_600 && fiveMinute.size >= 25_920 && fifteenMinute.size >= 8_640 && oneHour.size >= 2_160) {
            "回测数据不足：建议至少90天1分钟数据，并提供完整5分钟、15分钟、1小时K线"
        }
        val tenTrades = mutableListOf<Double>()
        var tenWins = 0; var tenLosses = 0; var tenTies = 0; var tenCandidates = 0
        var lastTenExit = -1
        val tenStart = evaluationStartTime?.let { (lowerBound(oneMinute, it) - 1).coerceAtLeast(120) } ?: 120
        val tenEnd = evaluationEndTime?.let { lowerBound(oneMinute, it) } ?: (oneMinute.size - 10)
        for (i in tenStart until minOf(tenEnd, oneMinute.size - 10)) {
            val signalTime = oneMinute[i].openTime + 60_000L
            if (evaluationStartTime != null && signalTime < evaluationStartTime) continue
            if (evaluationEndTime != null && signalTime + 10 * 60_000L > evaluationEndTime) continue
            val f = indexAtOrBefore(fiveMinute, signalTime, 300_000L)
            val q = indexAtOrBefore(fifteenMinute, signalTime, 900_000L)
            val h = indexAtOrBefore(oneHour, signalTime, 3_600_000L)
            if (f < 39 || q < 49 || h < 59) continue
            tenCandidates++
            if (i <= lastTenExit) continue
            val result = runCatching {
                FusionStrategyEngine.evaluate(
                    oneMinute.subList(max(0, i - 119), i + 1),
                    fiveMinute.subList(max(0, f - 119), f + 1),
                    fifteenMinute.subList(max(0, q - 79), q + 1),
                    oneHour.subList(max(0, h - 59), h + 1), 10
                )
            }.getOrNull() ?: continue
            if (result.direction == "观望") continue
            val entry = oneMinute[i + 1].open
            val exit = oneMinute[i + 10].close
            val gross = if (result.direction == "看涨") (exit - entry) / entry else (entry - exit) / entry
            val net = gross - 2.0 * (feePerSide + slippagePerSide)
            tenTrades += net
            if (abs(gross) < 1e-12) tenTies++ else if (net > 0) tenWins++ else tenLosses++
            lastTenExit = i + 10
        }

        val thirtyTrades = mutableListOf<Double>()
        var thirtyWins = 0; var thirtyLosses = 0; var thirtyTies = 0; var thirtyCandidates = 0
        var lastThirtyExit = -1
        val thirtyStart = evaluationStartTime?.let { (lowerBound(fiveMinute, it) - 1).coerceAtLeast(120) } ?: 120
        val thirtyEnd = evaluationEndTime?.let { lowerBound(fiveMinute, it) } ?: (fiveMinute.size - 6)
        for (i in thirtyStart until minOf(thirtyEnd, fiveMinute.size - 6)) {
            val signalTime = fiveMinute[i].openTime + 300_000L
            if (evaluationStartTime != null && signalTime < evaluationStartTime) continue
            if (evaluationEndTime != null && signalTime + 30 * 60_000L > evaluationEndTime) continue
            val oneIdx = indexAtOrBefore(oneMinute, signalTime, 60_000L)
            val q = indexAtOrBefore(fifteenMinute, signalTime, 900_000L)
            val h = indexAtOrBefore(oneHour, signalTime, 3_600_000L)
            if (oneIdx < 79 || q < 79 || h < 59) continue
            thirtyCandidates++
            if (i <= lastThirtyExit) continue
            val result = runCatching {
                FusionStrategyEngine.evaluate(
                    oneMinute.subList(max(0, oneIdx - 119), oneIdx + 1),
                    fiveMinute.subList(max(0, i - 119), i + 1),
                    fifteenMinute.subList(max(0, q - 79), q + 1),
                    oneHour.subList(max(0, h - 59), h + 1), 30
                )
            }.getOrNull() ?: continue
            if (result.direction == "观望") continue
            val entry = fiveMinute[i + 1].open
            val exit = fiveMinute[i + 6].close
            val gross = if (result.direction == "看涨") (exit - entry) / entry else (entry - exit) / entry
            val net = gross - 2.0 * (feePerSide + slippagePerSide)
            thirtyTrades += net
            if (abs(gross) < 1e-12) thirtyTies++ else if (net > 0) thirtyWins++ else thirtyLosses++
            lastThirtyExit = i + 6
        }
        return BacktestPeriodReport(
            stats(10, tenTrades, tenWins, tenLosses, tenTies, tenCandidates),
            stats(30, thirtyTrades, thirtyWins, thirtyLosses, thirtyTies, thirtyCandidates)
        )
    }

    /** Chronological 60/20/20 split. Each segment uses earlier candles only as indicator warm-up. */
    fun runWalkForward(
        oneMinute: List<StrategyCandle>, fiveMinute: List<StrategyCandle>,
        fifteenMinute: List<StrategyCandle>, oneHour: List<StrategyCandle>,
        feePerSide: Double = 0.001, slippagePerSide: Double = 0.0002
    ): WalkForwardReport {
        require(oneMinute.isNotEmpty()) { "没有历史数据" }
        val start = oneMinute.first().openTime
        val end = oneMinute.last().openTime + 60_000L
        val span = end - start
        val cut60 = start + (span * 0.60).toLong()
        val cut80 = start + (span * 0.80).toLong()
        fun runPart(a: Long, b: Long) = run(oneMinute, fiveMinute, fifteenMinute, oneHour, feePerSide, slippagePerSide, a, b)
        return WalkForwardReport(runPart(start, cut60), runPart(cut60, cut80), runPart(cut80, end))
    }

    private fun stats(h: Int, returns: List<Double>, wins: Int, losses: Int, ties: Int, candidates: Int): BacktestStats {
        val grossProfit = returns.filter { it > 0 }.sum()
        val grossLoss = -returns.filter { it < 0 }.sum()
        var equity = 1.0; var peak = 1.0; var maxDd = 0.0
        returns.forEach { r -> equity += r; peak = max(peak, equity); maxDd = max(maxDd, (peak - equity) / peak) }
        val decisive = wins + losses
        return BacktestStats(h, returns.size, wins, losses, ties,
            if (decisive == 0) 0.0 else wins * 100.0 / decisive,
            if (candidates == 0) 0.0 else returns.size * 100.0 / candidates,
            returns.sum() * 100.0,
            if (grossLoss <= 0.0) if (grossProfit > 0.0) Double.POSITIVE_INFINITY else 0.0 else grossProfit / grossLoss,
            maxDd * 100.0)
    }

    private fun lowerBound(xs: List<StrategyCandle>, time: Long): Int {
        var low = 0; var high = xs.size
        while (low < high) { val mid = (low + high) ushr 1; if (xs[mid].openTime < time) low = mid + 1 else high = mid }
        return low
    }

    private fun indexAtOrBefore(xs: List<StrategyCandle>, time: Long, intervalMs: Long): Int {
        var low = 0; var high = xs.lastIndex; var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (xs[mid].openTime + intervalMs <= time) { answer = mid; low = mid + 1 } else high = mid - 1
        }
        return answer
    }
}
