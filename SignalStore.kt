package com.example.btcassistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class FuturesSnapshot(
    val fundingRate: Double? = null,
    val markPrice: Double? = null,
    val openInterest: Double? = null,
    val longShortRatio: Double? = null,
    val takerBuySellRatio: Double? = null,
    val updatedAt: Long = 0L,
    val status: String = "等待合约数据"
)

data class PredictionRecord(
    val id: Long, val horizon: Int, val side: String, val entry: Double,
    val createdAt: Long, val settleAt: Long, val confidence: Int,
    val reason: String, val snapshot: Double? = null, val result: String = "待结算",
    val strategyGroup: String = "SHORT", val masterName: String = "短线融合",
    val marketRegime: String = "未记录", val riskLevel: String = "中", val riskWarning: String = ""
)

object SignalStore {
    private const val PREF = "btc_v5_store"
    private const val KEY = "predictions"
    private val _price = MutableStateFlow(0.0)
    val price = _price.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _marketStatus = MutableStateFlow("尚未连接")
    val marketStatus = _marketStatus.asStateFlow()
    private val _marketSource = MutableStateFlow("无")
    val marketSource = _marketSource.asStateFlow()
    private val _lastMarketUpdate = MutableStateFlow(0L)
    val lastMarketUpdate = _lastMarketUpdate.asStateFlow()
    private val _activeFeeds = MutableStateFlow(0)
    val activeFeeds = _activeFeeds.asStateFlow()
    private val _priceSpreadPct = MutableStateFlow(0.0)
    val priceSpreadPct = _priceSpreadPct.asStateFlow()
    private val _futures = MutableStateFlow(FuturesSnapshot())
    val futures = _futures.asStateFlow()
    private val _predictions = MutableStateFlow<List<PredictionRecord>>(emptyList())
    val predictions = _predictions.asStateFlow()
    private val _status10 = MutableStateFlow("尚未启动")
    val status10 = _status10.asStateFlow()
    private val _status30 = MutableStateFlow("尚未启动")
    val status30 = _status30.asStateFlow()
    private val _backtestStatus = MutableStateFlow("尚未运行历史回测")
    val backtestStatus = _backtestStatus.asStateFlow()

    fun setFutures(v: FuturesSnapshot) { _futures.value = v }
    fun setPrice(v: Double) { if (v > 0) _price.value = v }
    fun setConnected(v: Boolean) { _connected.value = v }
    fun setMarketStatus(v: String) { _marketStatus.value = v }
    fun setMarketHealth(healthy: Boolean, source: String, updatedAt: Long, feeds: Int, spreadPct: Double) {
        _connected.value = healthy
        _marketSource.value = source
        _lastMarketUpdate.value = updatedAt
        _activeFeeds.value = feeds
        _priceSpreadPct.value = spreadPct
    }
    fun setStatus(horizon: Int, value: String) { if (horizon == 10) _status10.value = value else _status30.value = value }
    fun setBacktestStatus(value: String) { _backtestStatus.value = value }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // Remove legacy API credentials left by the previous dual-AI build.
        prefs.edit().remove("gemini_key").remove("groq_key").apply()
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw); val list = mutableListOf<PredictionRecord>()
        for (i in 0 until arr.length()) runCatching {
            val o = arr.getJSONObject(i)
            list += PredictionRecord(o.getLong("id"), o.getInt("horizon"), o.getString("side"), o.getDouble("entry"), o.getLong("createdAt"), o.getLong("settleAt"), o.optInt("confidence"), o.optString("reason"), o.optNullableDouble("snapshot"), o.optString("result", "待结算"), o.optString("strategyGroup", "SHORT"), o.optString("masterName", "短线融合"), o.optString("marketRegime", "未记录"), o.optString("riskLevel", "中"), o.optString("riskWarning", ""))
        }
        _predictions.value = list.sortedByDescending { it.createdAt }
    }
    fun add(context: Context, r: PredictionRecord) { _predictions.value = (listOf(r) + _predictions.value).take(500); save(context) }
    fun updatePrice(context: Context, now: Long, price: Double) {
        val before = _predictions.value
        val after = before.map { r ->
            if (r.result != "待结算" || now < r.settleAt) r else {
                val win = if (r.side == "看涨") price > r.entry else price < r.entry
                r.copy(snapshot = price, result = if (win) "正确" else if (price == r.entry) "持平" else "错误")
            }
        }
        if (after != before) { _predictions.value = after; save(context) }
    }
    fun hasPending(horizon: Int): Boolean = _predictions.value.any { it.horizon == horizon && it.strategyGroup == "SHORT" && it.result == "待结算" }
    fun hasRecentShortSignal(horizon: Int, now: Long, cooldownMs: Long = 60_000L): Boolean =
        _predictions.value.any { it.strategyGroup == "SHORT" && it.horizon == horizon && now - it.createdAt in 0 until cooldownMs }
    fun hasRecentHallSignal(master: String, horizon: Int, side: String, now: Long, cooldownMs: Long = 15 * 60_000L): Boolean =
        _predictions.value.any { it.strategyGroup == "HALL_OF_FAME" && it.masterName == master && it.horizon == horizon && it.side == side && now - it.createdAt < cooldownMs }
    private fun save(c: Context) {
        val a = JSONArray(); _predictions.value.forEach { r ->
            val o = JSONObject().put("id",r.id).put("horizon",r.horizon).put("side",r.side).put("entry",r.entry).put("createdAt",r.createdAt).put("settleAt",r.settleAt).put("confidence",r.confidence).put("reason",r.reason).put("result",r.result).put("strategyGroup",r.strategyGroup).put("masterName",r.masterName).put("marketRegime",r.marketRegime).put("riskLevel",r.riskLevel).put("riskWarning",r.riskWarning)
            if (r.snapshot != null) o.put("snapshot",r.snapshot)
            a.put(o)
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply()
    }
    private fun JSONObject.optNullableDouble(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
}
