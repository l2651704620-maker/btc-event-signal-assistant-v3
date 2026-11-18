package com.example.btcassistant.data

/** Conservative, regime-specific learning from settled snapshots. New candidates are evaluated
 * chronologically: first 70% for discovery, final 30% as untouched validation. */
data class LearningModuleReport(
    val module: String, val trainSamples: Int, val validationSamples: Int,
    val validationWinRate: Double, val baselineWinRate: Double, val approved: Boolean
)
data class LearningRegimeReport(
    val horizon: Int, val regime: String, val totalSamples: Int,
    val modules: List<LearningModuleReport>
)

object AdaptiveLearningEngine {
    private val moduleNames = listOf("艾略特波浪")

    fun reports(records: List<PredictionRecord>): List<LearningRegimeReport> {
        val settled = records.filter { it.strategyGroup == "SHORT" && it.horizon == 10 && it.result != "待结算" && it.result != "持平" && it.marketRegime != "未记录" }
        return settled.groupBy { it.horizon to it.marketRegime }.map { (key, rows) ->
            val ordered = rows.filter { it.waveSide.isNotBlank() || it.raschkeSide.isNotBlank() || it.wyckoffSide.isNotBlank() }.sortedBy { it.createdAt }
            val split = (ordered.size * 0.70).toInt().coerceIn(0, ordered.size)
            val train = ordered.take(split); val validation = ordered.drop(split)
            val baseline = rate(validation)
            val modules = moduleNames.map { name ->
                val trainN = train.count { moduleSide(it, name).isNotBlank() }
                val validRows = validation.filter { moduleSide(it, name).isNotBlank() }
                val hit = validRows.count { moduleSide(it, name) == actualSide(it) }
                val wr = if (validRows.isEmpty()) 0.0 else hit * 100.0 / validRows.size
                val approved = trainN >= 20 && validRows.size >= 12 && wr >= 55.0 && wr >= baseline + 5.0
                LearningModuleReport(name, trainN, validRows.size, wr, baseline, approved)
            }
            LearningRegimeReport(key.first, key.second, ordered.size, modules)
        }.sortedWith(compareBy<LearningRegimeReport> { it.horizon }.thenBy { it.regime })
    }

    /** Only validated modules receive a modest vote multiplier; no candidate is promoted on training data. */
    fun approvedWeights(records: List<PredictionRecord>, horizon: Int, regime: String): Map<String, Double> {
        val report = reports(records).firstOrNull { it.horizon == horizon && it.regime == regime } ?: return emptyMap()
        return report.modules.filter { it.approved }.associate { it.module to 1.15 }
    }

    fun summary(records: List<PredictionRecord>): String {
        val short = records.filter { it.strategyGroup == "SHORT" && it.horizon == 10 && it.result != "待结算" }
        val reports = reports(records)
        val approved = reports.sumOf { r -> r.modules.count { it.approved } }
        val candidate = reports.sumOf { r -> r.modules.count { it.validationSamples > 0 && !it.approved } }
        return when {
            short.isEmpty() -> "等待快照结算后开始学习；目前不会调整正式策略"
            approved > 0 -> "已验证并启用 $approved 个环境-策略组合；其余候选继续观察。学习样本 ${short.size} 笔"
            candidate > 0 -> "正在验证候选策略；尚无组合同时达到样本量与验证门槛。已结算 ${short.size} 笔"
            else -> "学习数据积累中：每个周期、每种行情独立统计；已结算 ${short.size} 笔，暂不改动策略"
        }
    }

    private fun moduleSide(r: PredictionRecord, name: String): String = r.waveSide
    private fun actualSide(r: PredictionRecord): String = if (r.result == "正确") r.side else if (r.side == "看涨") "看跌" else "看涨"
    private fun rate(rows: List<PredictionRecord>): Double = if (rows.isEmpty()) 0.0 else rows.count { it.result == "正确" } * 100.0 / rows.size
}
