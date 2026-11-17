package com.example.btcassistant.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Multi-exchange BTC spot feed. A socket is considered healthy only after a real price tick. */
class MarketService : Service() {
    companion object {
        const val ACTION_PREDICT_10 = "com.example.btcassistant.PREDICT_10"
        const val ACTION_PREDICT_30 = "com.example.btcassistant.PREDICT_30"
        const val ACTION_BACKTEST = "com.example.btcassistant.BACKTEST"
        private const val STALE_MS = 15_000L
    }
    private data class Tick(val price: Double, val at: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val wsClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val reconnecting = ConcurrentHashMap<String, Boolean>()
    private val ticks = ConcurrentHashMap<String, Tick>()
    private val socketOpenedAt = ConcurrentHashMap<String, Long>()
    private val retry = ConcurrentHashMap<String, Long>()
    private var predicting10 = false
    private var predicting30 = false
    private var backtesting = false

    override fun onCreate() {
        super.onCreate(); SignalStore.load(this)
        startForeground(51, notification("正在连接多平台 BTC 行情"))
        connectAll()
        scope.launch { marketWatchdog() }
        scope.launch { futuresDataLoop() }
        scope.launch { snapshotLoop() }
        scope.launch { hallOfFameWatchLoop() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREDICT_10 -> if (!predicting10) scope.launch { runPrediction(10) }
            ACTION_PREDICT_30 -> if (!predicting30) scope.launch { runPrediction(30) }
            ACTION_BACKTEST -> if (!backtesting) { backtesting = true; scope.launch { runHistoricalBacktest() } }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { sockets.values.forEach { it.close(1000, "service stopped") }; sockets.clear(); scope.cancel(); super.onDestroy() }

    private fun notification(s: String): Notification {
        val channel = "btc_market_v5"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"BTC行情监控",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("BTC信号助手 V5.6").setContentText(s).setOngoing(true).build()
    }
    private fun updateNotification(s: String) { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).notify(51,notification(s)) }

    private fun connectAll() {
        connect("Binance", "wss://stream.binance.com:9443/ws/btcusdt@trade")
        connect("OKX", "wss://ws.okx.com:8443/ws/v5/public", JSONObject().put("op","subscribe").put("args",JSONArray().put(JSONObject().put("channel","tickers").put("instId","BTC-USDT"))).toString())
        connect("Bybit", "wss://stream.bybit.com/v5/public/spot", JSONObject().put("op","subscribe").put("args",JSONArray().put("tickers.BTCUSDT")).toString())
    }
    private fun connect(name: String, url: String, subscribe: String? = null) {
        if (sockets.containsKey(name)) return
        val request = Request.Builder().url(url).build()
        val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketOpenedAt[name] = System.currentTimeMillis()
                retry[name] = 2L
                if (subscribe != null) webSocket.send(subscribe)
                SignalStore.setMarketStatus("已建立${name}通道，等待真实行情数据")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    val price: Double
                    val ts: Long
                    when (name) {
                        "Binance" -> { price = root.optString("p").toDouble(); ts = root.optLong("T", System.currentTimeMillis()) }
                        "OKX" -> { val item = root.optJSONArray("data")?.optJSONObject(0) ?: return; price = item.optString("last").toDouble(); ts = item.optString("ts").toLongOrNull() ?: System.currentTimeMillis() }
                        else -> { val item = root.optJSONObject("data") ?: return; price = item.optString("lastPrice").toDouble(); ts = root.optLong("ts", System.currentTimeMillis()) }
                    }
                    if (price.isFinite() && price in 1_000.0..10_000_000.0) onTick(name, price, ts)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(name, webSocket); socketOpenedAt.remove(name); scheduleReconnect(name, url, subscribe, t.message ?: "网络错误")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(name, webSocket); socketOpenedAt.remove(name); scheduleReconnect(name, url, subscribe, "连接关闭($code)")
            }
        })
        sockets[name] = ws
    }
    private fun scheduleReconnect(name: String, url: String, subscribe: String?, reason: String) {
        SignalStore.setMarketStatus("${name}连接中断：$reason，正在自动重连")
        if (reconnecting.putIfAbsent(name, true) == true) return
        scope.launch {
            val wait = retry[name] ?: 2L
            delay(wait * 1000)
            retry[name] = (wait * 2).coerceAtMost(60)
            reconnecting.remove(name)
            if (sockets[name] == null) connect(name, url, subscribe)
        }
    }
    private fun onTick(source: String, price: Double, exchangeTime: Long) {
        val now = System.currentTimeMillis()
        // Exchange timestamps can drift; receipt time is used for freshness, exchange time is retained only as metadata.
        ticks[source] = Tick(price, now)
        val fresh = ticks.filterValues { now - it.at <= STALE_MS }
        if (fresh.isEmpty()) return
        val sorted = fresh.values.map { it.price }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val spreadPct = if (median > 0) (sorted.last() - sorted.first()) / median * 100 else 100.0
        // Reject a wildly divergent venue; with two feeds use their midpoint only if close enough.
        val accepted = fresh.filterValues { abs(it.price - median) / median * 100 <= 0.35 }
        if (accepted.isEmpty()) return
        val acceptedPrices = accepted.values.map { it.price }.sorted()
        val consensus = if (acceptedPrices.size % 2 == 1) acceptedPrices[acceptedPrices.size / 2] else acceptedPrices.average()
        SignalStore.setPrice(consensus)
        SignalStore.setMarketHealth(true, accepted.keys.sorted().joinToString(" + "), now, accepted.size, spreadPct)
    }
    private suspend fun marketWatchdog() {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            // A successful WebSocket handshake without actual price ticks is NOT a healthy feed.
            for (name in listOf("Binance", "OKX", "Bybit")) {
                val lastActivity = ticks[name]?.at ?: socketOpenedAt[name] ?: now
                if (sockets.containsKey(name) && now - lastActivity > STALE_MS) {
                    val staleSocket = sockets.remove(name)
                    staleSocket?.close(4000, "no market ticks")
                    val endpoint = when (name) {
                        "Binance" -> "wss://stream.binance.com:9443/ws/btcusdt@trade"
                        "OKX" -> "wss://ws.okx.com:8443/ws/v5/public"
                        else -> "wss://stream.bybit.com/v5/public/spot"
                    }
                    val sub = when (name) {
                        "OKX" -> JSONObject().put("op","subscribe").put("args",JSONArray().put(JSONObject().put("channel","tickers").put("instId","BTC-USDT"))).toString()
                        "Bybit" -> JSONObject().put("op","subscribe").put("args",JSONArray().put("tickers.BTCUSDT")).toString()
                        else -> null
                    }
                    scheduleReconnect(name, endpoint, sub, "超过15秒没有收到价格推送")
                }
            }
            val fresh = ticks.filterValues { now - it.at <= STALE_MS }
            if (fresh.isEmpty()) {
                SignalStore.setMarketHealth(false, "无有效数据", 0L, 0, 0.0)
                SignalStore.setMarketStatus("未收到实时行情数据，正在连接/重连 Binance、OKX、Bybit")
            } else {
                val prices = fresh.values.map { it.price }.sorted()
                val med = if (prices.size % 2 == 1) prices[prices.size / 2] else (prices[prices.size / 2 - 1] + prices[prices.size / 2]) / 2.0
                val accepted = fresh.filterValues { abs(it.price - med) / med * 100 <= 0.35 }
                val acceptedPrices = accepted.values.map { it.price }.sorted()
                val spread = if (acceptedPrices.size > 1 && med > 0) (acceptedPrices.last() - acceptedPrices.first()) / med * 100 else 0.0
                SignalStore.setMarketHealth(accepted.isNotEmpty(), accepted.keys.sorted().joinToString(" + ").ifEmpty { "价格分歧过大" }, accepted.values.maxOfOrNull { it.at } ?: 0L, accepted.size, spread)
                SignalStore.setMarketStatus(when { accepted.size >= 2 -> "多平台实时行情正常（${accepted.size}路）"; accepted.size == 1 -> "仅${accepted.keys.first()}有一致性行情，等待第二数据源"; else -> "多平台价格分歧过大，暂停分析" })
            }
            delay(3_000)
        }
    }

    private suspend fun runPrediction(horizon: Int) {
        if (horizon == 10) predicting10 = true else predicting30 = true
        SignalStore.setStatus(horizon, "正在读取实时行情并运行量化规则…")
        try {
            val freshCount = SignalStore.activeFeeds.value
            if (SignalStore.price.value <= 0 || !SignalStore.connected.value || System.currentTimeMillis() - SignalStore.lastMarketUpdate.value > STALE_MS) error("尚未收到新鲜实时行情，请检查网络连接")
            if (freshCount < 1) error("至少需要1路交易所实时行情才能分析")
            // Use sufficiently long histories for EMA50, ADX14, pivots and Wyckoff range analysis.
            // fetchKlines removes the still-forming candle to prevent look-ahead/repainting.
            val oneMin = fetchKlines("1m", 120)
            val fiveMin = fetchKlines("5m", 120)
            val fifteenMin = fetchKlines("15m", 80)
            val oneHour = fetchKlines("1h", 80)
            val futures = runCatching { fetchFuturesSnapshot() }.getOrElse { SignalStore.futures.value.copy(status = "合约数据暂不可用：${it.message ?: "网络异常"}") }
            SignalStore.setFutures(futures)
            val baseResult = evaluateRules(oneMin, fiveMin, fifteenMin, oneHour, horizon)
            val result = applyFuturesConfirmation(baseResult, futures)
            if (!SignalStore.connected.value || SignalStore.activeFeeds.value < 1 || System.currentTimeMillis()-SignalStore.lastMarketUpdate.value > STALE_MS) {
                error("分析期间实时行情失效，已取消本次信号")
            }
            val side = result.direction
            val signalAt = System.currentTimeMillis()
            val referencePrice = SignalStore.price.value
            if (referencePrice <= 0.0) error("无法取得信号生成时的实时参考价格")
            val feedQuality = if (freshCount >= 2) "多源交叉验证${freshCount}路" else "单源行情，评分已提示数据风险"
            SignalStore.add(this, PredictionRecord(signalAt, horizon, side, referencePrice, signalAt,
                signalAt + horizon*60_000L, result.confidence,
                "智能知识库评分 ${result.score}/100：${result.reason}（${feedQuality}；行情源：${SignalStore.marketSource.value}）",
                marketRegime = result.marketRegime, riskLevel = result.riskLevel, riskWarning = result.riskWarning))
            SignalStore.setStatus(horizon, "已生成${side}方向 · 模型匹配评分${result.score}/100 · ${horizon}分钟后自动结算")
            updateNotification("${horizon}分钟方向判断：$side，请人工确认")
        } catch (e: Exception) { SignalStore.setStatus(horizon, "分析失败：${e.message ?: "行情或网络异常"}") }
        finally { if (horizon == 10) predicting10 = false else predicting30 = false }
    }



    /** Watches the three independent Hall-of-Fame rule sets using closed candles only. */
    private suspend fun hallOfFameWatchLoop() {
        delay(20_000)
        while (currentCoroutineContext().isActive) {
            try {
                if (SignalStore.connected.value && SignalStore.activeFeeds.value >= 2 &&
                    System.currentTimeMillis() - SignalStore.lastMarketUpdate.value <= STALE_MS && SignalStore.price.value > 0.0) {
                    val one = fetchKlines("1m", 120)
                    val five = fetchKlines("5m", 120)
                    val fifteen = fetchKlines("15m", 80)
                    val signals = withContext(Dispatchers.Default) {
                        FusionStrategyEngine.evaluateHallOfFame(
                            one.map { it.toStrategyCandle() }, five.map { it.toStrategyCandle() }, fifteen.map { it.toStrategyCandle() }
                        )
                    }
                    val now = System.currentTimeMillis()
                    signals.forEach { signal ->
                        if (!SignalStore.hasRecentHallSignal(signal.master, signal.horizonMinutes, signal.direction, now)) {
                            val entry = SignalStore.price.value
                            if (entry > 0.0) {
                                SignalStore.add(this@MarketService, PredictionRecord(
                                    id = now + signal.master.hashCode().toLong() + signal.horizonMinutes, horizon = signal.horizonMinutes,
                                    side = signal.direction, entry = entry, createdAt = now,
                                    settleAt = now + signal.horizonMinutes * 60_000L,
                                    confidence = signal.strength,
                                    reason = "名人堂独立触发：${signal.detail}（仅作策略提醒，不与短线融合信号混算）",
                                    strategyGroup = "HALL_OF_FAME", masterName = signal.master
                                ))
                                showHallAlert(signal.master, signal.horizonMinutes, signal.direction, entry)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // A temporary API/network failure must not stop the foreground market service.
            }
            delay(60_000)
        }
    }

    private fun showHallAlert(master: String, horizon: Int, side: String, price: Double) {
        val channel = "btc_hall_alerts"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "名人堂策略提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("名人堂策略触发：$master")
            .setContentText("${horizon}分钟 · $side · 参考价 ${"%.2f".format(price)}；请自行确认是否参与")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$master 独立策略点位已触发。周期：${horizon}分钟；方向：$side；参考价：${"%.2f".format(price)}。这不是自动下单指令，请检查行情与风险后自行决定。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify((master.hashCode() xor horizon xor side.hashCode()), n)
    }

    private suspend fun runHistoricalBacktest() {
        try {
            SignalStore.setBacktestStatus("正在从 Binance 下载约92天的1分钟历史K线…")
            updateNotification("正在下载历史K线，准备运行短线策略回测")
            val one = fetchHistoricalOneMinute(92)
            SignalStore.setBacktestStatus("历史数据 ${one.size} 根，正在聚合5m/15m/1h并回测…")
            val five = aggregateCandles(one, 5)
            val fifteen = aggregateCandles(one, 15)
            val hourly = aggregateCandles(one, 60)
            val report = withContext(Dispatchers.Default) { BacktestEngine.runWalkForward(one.map { it.toStrategyCandle() }, five.map { it.toStrategyCandle() }, fifteen.map { it.toStrategyCandle() }, hourly.map { it.toStrategyCandle() }) }
            fun line(s: BacktestStats) = "${s.horizonMinutes}分钟：信号${s.signals}笔，净胜率${"%.1f".format(s.winRatePct)}%，覆盖率${"%.1f".format(s.coveragePct)}%，净收益${"%.2f".format(s.netReturnPct)}%，PF${"%.2f".format(s.profitFactor)}，最大回撤${"%.2f".format(s.maxDrawdownPct)}%"
            fun period(name: String, p: BacktestPeriodReport) = "$name\n${line(p.tenMinute)}\n${line(p.thirtyMinute)}"
            SignalStore.setBacktestStatus("回测完成（Binance现货历史数据，按时间顺序切分）\n${period("开发60%", report.development)}\n${period("验证20%", report.validation)}\n${period("最终样本外20%", report.outOfSample)}\n注意：历史模拟不代表未来表现；重点观察最终样本外结果。")
            updateNotification("短线策略历史回测完成")
        } catch (e: Exception) {
            SignalStore.setBacktestStatus("回测失败：${e.message ?: "历史数据或网络异常"}")
            updateNotification("短线策略历史回测失败，请查看应用")
        } finally { backtesting = false }
    }

    private suspend fun fetchHistoricalOneMinute(days: Int): List<Candle> = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis() / 60_000L * 60_000L
        var start = end - days * 24L * 60L * 60_000L
        val all = mutableListOf<Candle>()
        var pages = 0
        while (start < end && pages < 160) {
            val url = "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=$start&endTime=$end&limit=1000"
            val request = Request.Builder().url(url).build()
            val rows = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Binance历史K线HTTP ${response.code}")
                val array = JSONArray(response.body?.string().orEmpty())
                (0 until array.length()).map { i ->
                    val k = array.getJSONArray(i)
                    Candle(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble())
                }
            }
            if (rows.isEmpty()) break
            all += rows.filter { it.openTime + 60_000L <= System.currentTimeMillis() }
            start = rows.last().openTime + 60_000L
            pages++
            if (pages % 10 == 0) SignalStore.setBacktestStatus("正在下载历史数据：${all.size} 根1分钟K线，已请求${pages}批…")
            delay(80)
        }
        val unique = all.sortedBy { it.openTime }.distinctBy { it.openTime }
        if (unique.size < 129_600) error("历史数据不足90天：仅取得${unique.size}根1分钟K线")
        if (unique.zipWithNext().any { (a, b) -> b.openTime - a.openTime != 60_000L }) error("历史K线存在缺口，不能进行可靠回测")
        unique
    }

    private fun aggregateCandles(source: List<Candle>, minutes: Int): List<Candle> {
        val intervalMs = minutes * 60_000L
        return source.groupBy { it.openTime / intervalMs }.toSortedMap().values
            .filter { it.size == minutes }
            .map { group ->
                val rows = group.sortedBy { it.openTime }
                Candle(rows.first().openTime / intervalMs * intervalMs, rows.first().open, rows.maxOf { it.high }, rows.minOf { it.low }, rows.last().close, rows.sumOf { it.volume })
            }
    }

    private fun Candle.toStrategyCandle() = StrategyCandle(openTime, open, high, low, close, volume)

    private data class Candle(val openTime: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
    private suspend fun fetchKlines(interval: String, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        // The first available exchange is used for each timeframe; all URLs are public market-data endpoints.
        val paths = listOf(
            "Binance" to "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit",
            "OKX" to "https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=${intervalToOkx(interval)}&limit=$limit",
            "Bybit" to "https://api.bybit.com/v5/market/kline?category=spot&symbol=BTCUSDT&interval=${intervalToBybit(interval)}&limit=$limit"
        )
        for ((venue,url) in paths) try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val body = res.body?.string().orEmpty()
                val rows: List<Candle> = when (venue) {
                    "Binance" -> { val a=JSONArray(body); (0 until a.length()).map { i -> val k=a.getJSONArray(i); Candle(k.getLong(0),k.getString(1).toDouble(),k.getString(2).toDouble(),k.getString(3).toDouble(),k.getString(4).toDouble(),k.getString(5).toDouble()) } }
                    "OKX" -> { val a=JSONObject(body).getJSONArray("data"); (0 until a.length()).map { i -> val k=a.getJSONArray(i); Candle(k.getString(0).toLong(),k.getString(1).toDouble(),k.getString(2).toDouble(),k.getString(3).toDouble(),k.getString(4).toDouble(),k.getString(5).toDouble()) }.reversed() }
                    else -> { val a=JSONObject(body).getJSONObject("result").getJSONArray("list"); (0 until a.length()).map { i -> val k=a.getJSONArray(i); Candle(k.getString(0).toLong(),k.getString(1).toDouble(),k.getString(2).toDouble(),k.getString(3).toDouble(),k.getString(4).toDouble(),k.getString(5).toDouble()) }.reversed() }
                }
                val intervalMs = intervalMillis(interval)
                val closed = rows.filter { it.openTime + intervalMs <= System.currentTimeMillis() }
                    .sortedBy { it.openTime }.distinctBy { it.openTime }
                if (closed.size < 3) error("已收盘K线数量不足")
                if (closed.zipWithNext().any { (a, b) -> b.openTime - a.openTime > intervalMs * 1.5 }) error("$interval K线存在时间缺口，拒绝使用不连续数据")
                if (closed.any { it.high < it.low || it.volume < 0.0 || it.open <= 0.0 || it.close <= 0.0 }) error("$interval K线数据异常")
                return@withContext closed
            }
        } catch (e: Exception) { errors += "$venue:${e.message}" }
        error("所有交易所K线接口均不可用（${errors.joinToString("；")}）")
    }
    private fun intervalToBybit(interval: String) = when(interval) { "1m" -> "1"; "5m" -> "5"; "15m" -> "15"; "1h" -> "60"; else -> "1" }
    private fun intervalToOkx(interval: String) = when(interval) { "1m" -> "1m"; "5m" -> "5m"; "15m" -> "15m"; "1h" -> "1H"; else -> "1m" }
    private fun intervalMillis(interval: String) = when(interval) { "1m" -> 60_000L; "5m" -> 300_000L; "15m" -> 900_000L; "1h" -> 3_600_000L; else -> 60_000L }

    /** Binance USD-M perpetual context. The /futures/data endpoints return JSON arrays directly. */
    private suspend fun fetchFuturesSnapshot(): FuturesSnapshot = withContext(Dispatchers.IO) {
        fun getObject(path: String): JSONObject {
            val req = Request.Builder().url("https://fapi.binance.com$path").build()
            return client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                JSONObject(res.body?.string().orEmpty())
            }
        }
        fun getFirstArrayRow(path: String): JSONObject? = runCatching {
            val req = Request.Builder().url("https://fapi.binance.com$path").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                JSONArray(res.body?.string().orEmpty()).optJSONObject(0)
            }
        }.getOrNull()

        // Keep each feed independent: a permissions/rate-limit issue on one endpoint must not
        // discard the funding rate, mark price, or open-interest data from the other endpoints.
        val premium = runCatching { getObject("/fapi/v1/premiumIndex?symbol=BTCUSDT") }.getOrNull()
        val oi = runCatching { getObject("/fapi/v1/openInterest?symbol=BTCUSDT") }.getOrNull()
        val lsRow = getFirstArrayRow("/futures/data/globalLongShortAccountRatio?symbol=BTCUSDT&period=5m&limit=1")
        val takerRow = getFirstArrayRow("/futures/data/takerlongshortRatio?symbol=BTCUSDT&period=5m&limit=1")
        val available = listOf(premium != null, oi != null, lsRow != null, takerRow != null).count { it }
        FuturesSnapshot(
            fundingRate = premium?.optString("lastFundingRate")?.toDoubleOrNull(),
            markPrice = premium?.optString("markPrice")?.toDoubleOrNull(),
            openInterest = oi?.optString("openInterest")?.toDoubleOrNull(),
            longShortRatio = lsRow?.optString("longShortRatio")?.toDoubleOrNull(),
            takerBuySellRatio = takerRow?.optString("buySellRatio")?.toDoubleOrNull(),
            updatedAt = System.currentTimeMillis(),
            status = if (available == 4) "合约数据完整（4/4）" else "合约数据部分可用（$available/4）"
        )
    }

    private suspend fun futuresDataLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching { fetchFuturesSnapshot() }
                .onSuccess { SignalStore.setFutures(it) }
                .onFailure { SignalStore.setFutures(SignalStore.futures.value.copy(status = "合约数据更新失败：${it.message ?: "稍后重试"}")) }
            delay(30_000)
        }
    }

    /** Futures positioning is a confirmation/contrarian input, deliberately capped to avoid dominating price structure. */
    private fun applyFuturesConfirmation(base: RuleResult, f: FuturesSnapshot): RuleResult {
        var adjustment = 0
        val notes = mutableListOf<String>()
        val funding = f.fundingRate
        if (funding != null) {
            when {
                funding >= 0.0005 -> { adjustment -= if (base.direction == "看涨") 2 else 1; notes += "资金费率偏高(${"%.4f".format(funding * 100)}%)，多头拥挤风险" }
                funding <= -0.0003 -> { adjustment += if (base.direction == "看涨") 1 else 2; notes += "资金费率为负，空头拥挤需留意反弹" }
                else -> notes += "资金费率中性"
            }
        }
        f.longShortRatio?.let { ratio ->
            when {
                ratio >= 1.8 -> { adjustment += if (base.direction == "看跌") 2 else -1; notes += "账户多空比${"%.2f".format(ratio)}，多头明显占优" }
                ratio <= 0.65 -> { adjustment += if (base.direction == "看涨") 2 else -1; notes += "账户多空比${"%.2f".format(ratio)}，空头明显占优" }
                else -> notes += "账户多空比${"%.2f".format(ratio)}接近均衡"
            }
        }
        f.takerBuySellRatio?.let { ratio ->
            when { ratio >= 1.15 -> { adjustment += if (base.direction == "看涨") 2 else -2; notes += "主动买卖量比${"%.2f".format(ratio)}偏买方" }
                ratio <= 0.87 -> { adjustment += if (base.direction == "看跌") 2 else -2; notes += "主动买卖量比${"%.2f".format(ratio)}偏卖方" }
                else -> notes += "主动买卖量比${"%.2f".format(ratio)}中性" }
        }
        val score = (base.score + adjustment).coerceIn(1, 99)
        return base.copy(score = score, confidence = score,
            reason = base.reason + "；合约交叉验证：" + (notes.joinToString("、").ifEmpty { "合约指标缺失，本次未参与评分" }) + "；合约评分修正${if (adjustment >= 0) "+" else ""}$adjustment")
    }
    private fun buildMarketSummary(one: List<Candle>, five: List<Candle>, fifteen: List<Candle>, horizon: Int): String {
        fun series(name: String, xs: List<Candle>): String {
            val closes=xs.map { it.close }; val last=closes.last(); val prev=closes.getOrElse(closes.size-2){last}
            val emaFast=ema(closes, minOf(5, closes.size)); val emaSlow=ema(closes, minOf(12, closes.size))
            val gains=closes.zipWithNext().map { (a,b) -> b-a }; val up=gains.filter { it>0 }.sum(); val down=abs(gains.filter { it<0 }.sum()).coerceAtLeast(0.000001)
            val rsi=100-100/(1+up/down); val avgVol=xs.dropLast(1).takeLast(5).map { it.volume }.average().takeIf { it.isFinite() && it>0 } ?: 1.0
            return "$name: close=$last, lastChangePct=${(last-prev)/prev*100}, EMAfast=$emaFast, EMAslow=$emaSlow, RSI14approx=$rsi, lastVolume=${xs.last().volume}, volumeVsRecentAvg=${xs.last().volume/avgVol}, recentCandles=" + xs.takeLast(if(horizon==10) 3 else 10).joinToString(";") { "O${it.open},H${it.high},L${it.low},C${it.close},V${it.volume}" }
        }
        return "BTCUSDT spot market data. Prediction horizon=$horizon minutes. Multi-exchange consensus price=${SignalStore.price.value}.\n" + series("1m",one) + "\n" + series("5m",five) + "\n" + series("15m",fifteen) + "\nKlines may be forming; cross-exchange tick consensus is used for live price."
    }
    private data class RuleResult(
        val direction: String, val score: Int, val confidence: Int, val reason: String,
        val marketRegime: String = "未识别", val riskLevel: String = "中", val riskWarning: String = ""
    )

    /** Deterministic rule engine inspired by public trend, breakout, volume/price and momentum frameworks. */
    private fun evaluateRules(one: List<Candle>, five: List<Candle>, fifteen: List<Candle>, oneHour: List<Candle>, horizon: Int): RuleResult {
        val result = FusionStrategyEngine.evaluate(
            one.map { StrategyCandle(it.openTime, it.open, it.high, it.low, it.close, it.volume) },
            five.map { StrategyCandle(it.openTime, it.open, it.high, it.low, it.close, it.volume) },
            fifteen.map { StrategyCandle(it.openTime, it.open, it.high, it.low, it.close, it.volume) },
            oneHour.map { StrategyCandle(it.openTime, it.open, it.high, it.low, it.close, it.volume) },
            horizon
        )
        return RuleResult(result.direction, result.score, result.confidence, result.reason, result.marketRegime, result.riskLevel, result.riskWarning)
    }

    private fun ema(xs: List<Double>, period: Int): Double {
        val k=2.0/(period+1)
        return xs.drop(1).fold(xs.first()) { acc,v -> v*k+acc*(1-k) }
    }
    private suspend fun snapshotLoop() {
        while (currentCoroutineContext().isActive) {
            val px=SignalStore.price.value
            if (px>0 && SignalStore.connected.value && System.currentTimeMillis()-SignalStore.lastMarketUpdate.value <= STALE_MS) SignalStore.updatePrice(this@MarketService,System.currentTimeMillis(),px)
            delay(5_000)
        }
    }
}
