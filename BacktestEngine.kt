package com.example.btcassistant.data

import kotlin.math.max

/** 10-minute-only walk-forward backtest. Entries use next 1m open and exits use the 10th following close. */
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

data class WalkForwardReport(
    val development: BacktestStats,
    val validation: BacktestStats,
    val outOfSample: BacktestStats
)

object BacktestEngine {
    /** Costs are fractions per side; defaults approximate 0.10% fee + 0.02% slippage per side. */
    fun run(
        oneMinute: List<StrategyCandle>,
        fiveMinute: List<StrategyCandle>,
        fifteenMinute: List<StrategyCandle>,
        oneHour: List<StrategyCandle>,
        feePerSide: Double = 0.001,
        slippagePerSide: Double = 0.0002,
        evaluationStartTime: Long? = null,
        evaluationEndTime: Long? = null
    ): BacktestStats {
        require(oneMinute.size >= 129_600 && fiveMinute.size >= 25_920 && fifteenMinute.size >= 8_640 && oneHour.size >= 2_160) {
            "回测数据不足：建议至少90天1分钟数据，并提供完整5分钟、15分钟、1小时K线"
        }
        val returns = mutableListOf<Double>()
        var wins = 0; var losses = 0; var ties = 0; var candidates = 0
        var lastExit = -1
        val start = evaluationStartTime?.let { (lowerBound(oneMinute, it) - 1).coerceAtLeast(120) } ?: 120
        val end = evaluationEndTime?.let { lowerBound(oneMinute, it) } ?: (oneMinute.size - 10)
        for (i in start until minOf(end, oneMinute.size - 10)) {
            val signalTime = oneMinute[i].openTime + 60_000L
            if (evaluationStartTime != null && signalTime < evaluationStartTime) continue
            if (evaluationEndTime != null && signalTime + 10 * 60_000L > evaluationEndTime) continue
            val f = indexAtOrBefore(fiveMinute, signalTime, 300_000L)
            val q = indexAtOrBefore(fifteenMinute, signalTime, 900_000L)
            val h = indexAtOrBefore(oneHour, signalTime, 3_600_000L)
            if (f < 39 || q < 19 || h < 59) continue
            candidates++
            if (i <= lastExit) continue
            val result = runCatching {
                FusionStrategyEngine.evaluate(
                    oneMinute.subList(max(0, i - 119), i + 1),
                    fiveMinute.subList(max(0, f - 119), f + 1),
                    fifteenMinute.subList(max(0, q - 79), q + 1),
                    oneHour.subList(max(0, h - 59), h + 1), 10
                )
            }.getOrNull() ?: continue
            val entry = oneMinute[i + 1].open
            val exit = oneMinute[i + 10].close
            val gross = if (result.direction == "看涨") (exit - entry) / entry else (entry - exit) / entry
            val net = gross - 2.0 * (feePerSide + slippagePerSide)
            returns += net
            if (kotlin.math.abs(gross) < 1e-12) ties++ else if (net > 0) wins++ else losses++
            lastExit = i + 10
        }
        return stats(returns, wins, losses, ties, candidates)
    }

    /** Chronological 60/20/20 split; prior candles are used only as indicator warm-up. */
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
        fun part(a: Long, b: Long) = run(oneMinute, fiveMinute, fifteenMinute, oneHour, feePerSide, slippagePerSide, a, b)
        return WalkForwardReport(part(start, cut60), part(cut60, cut80), part(cut80, end))
    }

    private fun stats(returns: List<Double>, wins: Int, losses: Int, ties: Int, candidates: Int): BacktestStats {
        val profit = returns.filter { it > 0 }.sum()
        val loss = -returns.filter { it < 0 }.sum()
        var equity = 1.0; var peak = 1.0; var drawdown = 0.0
        returns.forEach { r -> equity += r; peak = max(peak, equity); drawdown = max(drawdown, (peak - equity) / peak) }
        val decisive = wins + losses
        return BacktestStats(10, returns.size, wins, losses, ties,
            if (decisive == 0) 0.0 else wins * 100.0 / decisive,
            if (candidates == 0) 0.0 else returns.size * 100.0 / candidates,
            returns.sum() * 100.0,
            if (loss <= 0.0) if (profit > 0.0) Double.POSITIVE_INFINITY else 0.0 else profit / loss,
            drawdown * 100.0)
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
