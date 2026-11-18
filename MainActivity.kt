package com.example.btcassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.btcassistant.data.MarketService
import com.example.btcassistant.data.AdaptiveLearningEngine
import com.example.btcassistant.data.PredictionRecord
import com.example.btcassistant.data.SignalStore
import com.example.btcassistant.data.KlineChartStore
import com.example.btcassistant.data.ElliottWaveEngine
import com.example.btcassistant.data.StrategyCandle
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalStore.load(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val i=Intent(this,MarketService::class.java)
        if (Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF22C55E),background=Color(0xFF0B1220),surface=Color(0xFF172235))) { Screen(this) } }
    }
}

@Composable private fun Screen(activity: MainActivity) {
    val price by SignalStore.price.collectAsState(); val connected by SignalStore.connected.collectAsState(); val records by SignalStore.predictions.collectAsState()
    val marketStatus by SignalStore.marketStatus.collectAsState(); val marketSource by SignalStore.marketSource.collectAsState(); val lastUpdate by SignalStore.lastMarketUpdate.collectAsState(); val activeFeeds by SignalStore.activeFeeds.collectAsState()
    val status10 by SignalStore.status10.collectAsState()
    val backtestStatus by SignalStore.backtestStatus.collectAsState()
    val chartInterval by KlineChartStore.interval.collectAsState(); val chartCandles by KlineChartStore.candles.collectAsState(); val chartStatus by KlineChartStore.status.collectAsState()
    val futures by SignalStore.futures.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    var showKnowledge by remember { mutableStateOf(true) }
    val pendingRecords = records.filter { it.result == "待结算" && it.strategyGroup == "SHORT" && it.horizon == 10 }
    val settledRecords = records.filter { it.result != "待结算" && it.strategyGroup == "SHORT" && it.horizon == 10 }
    val settled10=settledRecords.filter { it.horizon==10 }
    val decisive10 = settled10.filter { it.snapshot != null && it.snapshot != it.entry }
    val rate10=if(decisive10.isEmpty()) 0 else decisive10.count { it.result=="正确" }*100/decisive10.size
    val rawRows10 = decisive10.filter { it.rawSide.isNotBlank() }
    val rawRate10 = if(rawRows10.isEmpty()) 0 else rawRows10.count { r ->
        if(r.rawSide == "看涨") r.snapshot!! > r.entry else r.snapshot!! < r.entry
    } * 100 / rawRows10.size
    Surface(Modifier.fillMaxSize(), color=Color(0xFF0B1220)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(42.dp),contentAlignment=Alignment.Center) { Surface(shape=RoundedCornerShape(14.dp),color=Color(0xFF163B32)) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("₿",fontSize=27.sp,fontWeight=FontWeight.Bold,color=Color(0xFF34D399)) } } }
                Spacer(Modifier.width(10.dp)); Column { Text("BTC 智能信号助手 V8",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Color.White); Text("V8 · 10分钟专用方向引擎",fontSize=10.sp,color=Color(0xFF94A3B8)) }
                Spacer(Modifier.weight(1f)); Text(if(connected) "● 在线" else "● 连接中",color=if(connected) Color(0xFF22C55E) else Color(0xFFF59E0B),fontSize=11.sp)
            }
            when(page) {
                0 -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(17.dp)) { Text("BTC/USDT 实时现货",color=Color(0xFF94A3B8),fontSize=12.sp); Text(if(price>0) "$"+String.format(Locale.US,"%,.2f",price) else "等待行情…",fontSize=32.sp,fontWeight=FontWeight.Bold,color=Color.White); Text("行情源：$marketSource · 有效通道：$activeFeeds/3",fontSize=11.sp,color=Color(0xFF94A3B8)); Text(marketStatus,fontSize=11.sp,color=if(activeFeeds>=2) Color(0xFF22C55E) else Color(0xFFF59E0B)); Text(if(lastUpdate>0) "更新：${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(lastUpdate))}" else "尚无行情更新时间",fontSize=10.sp,color=Color(0xFF64748B)) } } }
                    item { KlineChartPanel(chartInterval,chartCandles,chartStatus,price) { KlineChartStore.selectInterval(it) } }
                    item { FuturesPanel(futures) }
                    item { PredictionPanel(status10,pendingRecords.isNotEmpty()) { startPrediction(activity,10) } }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Stat("最终输出胜率","$rate10%",Modifier.weight(1f)); Stat("原始方向胜率","$rawRate10%",Modifier.weight(1f)); Stat("已结算","${settled10.size}笔",Modifier.weight(1f)) } }
                    item { Text("实时分析",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold); Text("系统仅分析未来10分钟，基于多周期K线与多维知识库给出方向和评分。",color=Color(0xFF94A3B8),fontSize=12.sp) }
                }
                1 -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    item { Text("技术知识库与策略验证",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Color.White); Text("展示参与方向评分的技术模块、自主学习验证和历史回测。",color=Color(0xFF94A3B8),fontSize=12.sp) }
                    item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Row(verticalAlignment=Alignment.CenterVertically) { Text("六大技术知识库",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp,modifier=Modifier.weight(1f)); TextButton(onClick={showKnowledge=!showKnowledge}) { Text(if(showKnowledge) "收起" else "展开",color=Color(0xFF34D399)) } }; if(showKnowledge) { KnowledgeRow("波浪结构","推动浪1–5、调整浪ABC及复杂调整；结构不清晰时降低权重，不强行数浪。") ; KnowledgeRow("K线形态","吞没、Pin Bar、十字星、连续阳线/阴线、有效突破与假突破。") ; KnowledgeRow("量价关系","相对成交量放大、缩量回调、放量突破、价格与量能背离。") ; KnowledgeRow("支撑与阻力","前高前低、关键价格区、突破后回踩确认及失效位置。") ; KnowledgeRow("动能指标","RSI、MACD柱体方向/变化、短线动量与价格加速度。") ; KnowledgeRow("合约与市场环境","资金费率、OI、多空比、爆仓数据接口预留、波动率、盘口/主动买卖力量、成交额与突发行情。") } } } }
                    item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("自主学习与验证",fontWeight=FontWeight.Bold,color=Color.White,fontSize=16.sp); Text(AdaptiveLearningEngine.summary(settledRecords),color=Color(0xFF34D399),fontSize=12.sp); AdaptiveLearningEngine.reports(settledRecords).takeLast(5).forEach { report -> Text("${report.horizon}分钟 · ${report.regime} · ${report.totalSamples}笔",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold); report.modules.forEach { m -> Text("${m.module}：训练${m.trainSamples}/验证${m.validationSamples} · 验证胜率${String.format(Locale.US,"%.1f",m.validationWinRate)}% · ${if(m.approved) "验证通过" else "暂不调整权重"}",color=if(m.approved) Color(0xFF22C55E) else Color(0xFF94A3B8),fontSize=10.sp) } }; Text("新策略先积累样本，再按时间顺序拆分训练集与验证集；验证通过才调整权重，不能用训练数据直接证明有效。",color=Color(0xFFF59E0B),fontSize=10.sp) } } }
                    item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) { Text("历史规则回测",fontWeight=FontWeight.Bold,color=Color.White,fontSize=16.sp); Text("下载历史K线检验规则表现；回测结果不代表未来收益。",color=Color(0xFFCBD5E1),fontSize=12.sp); Button(onClick={startBacktest(activity)},modifier=Modifier.fillMaxWidth()) { Text("运行历史回测") }; Text(backtestStatus,color=Color(0xFF94A3B8),fontSize=11.sp) } } }
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    item { Text("开单与战绩",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Color.White); Text("只展示10分钟待结算信号与已完成快照；旧版本历史数据仍保留在本地。",color=Color(0xFF94A3B8),fontSize=12.sp) }
                    item { Text("当前开单中 · ${pendingRecords.size}笔",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp) }
                    if(pendingRecords.isEmpty()) item { Text("目前没有待结算信号。",color=Color(0xFF94A3B8),fontSize=12.sp) }
                    items(pendingRecords) { PredictionCard(it) }
                    item { Divider(color=Color(0xFF334155)); Text("历史快照战绩 · ${settledRecords.size}笔",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp) }
                    if(settledRecords.isEmpty()) item { Text("暂无已结算记录。",color=Color(0xFF94A3B8),fontSize=12.sp) }
                    items(settledRecords) { PredictionCard(it) }
                }
            }
            NavigationBar(containerColor=Color(0xFF111C2D),contentColor=Color.White) {
                listOf("行情" to "⌂", "知识库" to "⌁", "战绩" to "▤").forEachIndexed { index, pair -> NavigationBarItem(selected=page==index,onClick={page=index},icon={Text(pair.second,fontSize=20.sp)},label={Text(pair.first,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Color(0xFF34D399),selectedTextColor=Color(0xFF34D399),indicatorColor=Color(0xFF1D3A35),unselectedTextColor=Color(0xFF94A3B8))) }
            }
        }
    }
}

@Composable private fun FuturesPanel(f: com.example.btcassistant.data.FuturesSnapshot) {
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("币安 U本位永续 · 合约数据",fontWeight=FontWeight.Bold,color=Color.White,fontSize=15.sp)
        Text("资金费率：${f.fundingRate?.let { String.format(Locale.US,"%.4f%%",it*100) } ?: "--"}   标记价：${f.markPrice?.let { String.format(Locale.US,"%,.2f",it) } ?: "--"}",color=Color(0xFFCBD5E1),fontSize=12.sp)
        Text("未平仓量 OI：${f.openInterest?.let { String.format(Locale.US,"%,.2f BTC",it) } ?: "--"}   多空账户比：${f.longShortRatio?.let { String.format(Locale.US,"%.2f",it) } ?: "--"}",color=Color(0xFFCBD5E1),fontSize=12.sp)
        Text("主动买卖量比：${f.takerBuySellRatio?.let { String.format(Locale.US,"%.2f",it) } ?: "--"} · OI变化：${f.oiChangePct?.let { String.format(Locale.US,"%.2f%%",it) } ?: "--"}",color=Color(0xFF94A3B8),fontSize=11.sp)
        Text("盘口深度差：${f.orderBookImbalance?.let { String.format(Locale.US,"%.2f",it) } ?: "--"} · 24h成交额：${f.quoteVolume24h?.let { String.format(Locale.US,"%.0f USDT",it) } ?: "--"}",color=Color(0xFF94A3B8),fontSize=11.sp)
        Text("强平数据：尚未接入实时强平流 · ${f.status}",color=Color(0xFFF59E0B),fontSize=10.sp)
        Text(if(f.updatedAt>0) "更新：${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(f.updatedAt))}" else "合约数据等待更新",color=Color(0xFF64748B),fontSize=10.sp)
    } }
}
@Composable private fun KnowledgeRow(title:String,detail:String) { Column(verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(title,color=Color(0xFF34D399),fontSize=12.sp,fontWeight=FontWeight.SemiBold); Text(detail,color=Color(0xFFCBD5E1),fontSize=11.sp) } }


@Composable private fun KlineChartPanel(
    interval: String, candles: List<com.example.btcassistant.data.ChartCandle>,
    status: String, livePrice: Double, onSelect: (String) -> Unit
) {
    val strategyCandles = remember(candles) { candles.map { StrategyCandle(it.openTime,it.open,it.high,it.low,it.close,it.volume) } }
    val wave = remember(strategyCandles) { if(strategyCandles.size>=24) ElliottWaveEngine.analyze(strategyCandles) else null }
    val stage = remember(strategyCandles) { if(strategyCandles.size>=30) ElliottWaveEngine.trendStage(strategyCandles) else null }
    // Precompute chart bounds, volume scale and EMA outside Canvas draw passes.
    val chartStats = remember(candles) {
        if (candles.isEmpty()) null else {
            val high = candles.maxOf { it.high }
            val low = candles.minOf { it.low }
            val maxVol = candles.maxOf { it.volume }.coerceAtLeast(1e-9)
            val k = 2.0 / 10.0
            var ema = candles.first().close
            val emaValues = candles.mapIndexed { i, c -> if (i > 0) ema = c.close * k + ema * (1-k); ema }
            Triple(high, low, Pair(maxVol, emaValues))
        }
    }
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BTC/USDT K线图 · 实时价格",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp)
                    Text("最新价格：${if(livePrice>0) "$"+String.format(Locale.US,"%,.2f",livePrice) else "--"}",color=Color(0xFFCBD5E1),fontSize=12.sp)
                }
                Text("现货",color=Color(0xFF22C55E),fontSize=11.sp)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf("1m","3m","5m","10m","15m").forEach { tf ->
                    FilterChip(selected=interval==tf,onClick={onSelect(tf)},label={Text(tf)},colors=FilterChipDefaults.filterChipColors(
                        selectedContainerColor=Color(0xFF166534),selectedLabelColor=Color.White,labelColor=Color(0xFFCBD5E1)))
                }
            }
            if(candles.size>=3) {
                Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                    val topPad=8f; val volumeHeight=size.height*0.19f; val chartBottom=size.height-volumeHeight-8f
                    val stats=chartStats
                    val high=stats?.first ?: 1.0; val low=stats?.second ?: 0.0
                    val span=(high-low).coerceAtLeast(0.01)
                    val step=size.width/candles.size.toFloat(); val bodyW=(step*0.58f).coerceAtLeast(2f)
                    val maxVol=stats?.third?.first ?: 1.0
                    candles.forEachIndexed { i,c ->
                        val x=step*(i+0.5f)
                        val yHigh=topPad+((high-c.high)/span).toFloat()*(chartBottom-topPad)
                        val yLow=topPad+((high-c.low)/span).toFloat()*(chartBottom-topPad)
                        val yOpen=topPad+((high-c.open)/span).toFloat()*(chartBottom-topPad)
                        val yClose=topPad+((high-c.close)/span).toFloat()*(chartBottom-topPad)
                        val up=c.close>=c.open
                        val color=if(up) Color(0xFF22C55E) else Color(0xFFEF4444)
                        drawLine(color,Offset(x,yHigh),Offset(x,yLow),1.3f)
                        val bodyTop=minOf(yOpen,yClose); val bodyH=kotlin.math.abs(yClose-yOpen).coerceAtLeast(1.5f)
                        drawRect(color,Offset(x-bodyW/2,bodyTop),Size(bodyW,bodyH))
                        val vh=(c.volume/maxVol).toFloat()*(volumeHeight-5f)
                        drawRect(color.copy(alpha=0.55f),Offset(x-bodyW/2,size.height-vh),Size(bodyW,vh))
                    }
                    // EMA 9 overlay
                    val emaValues=stats?.third?.second.orEmpty()
                    val points=emaValues.mapIndexed { i, ema ->
                        val y=topPad+((high-ema)/span).toFloat()*(chartBottom-topPad)
                        Offset(step*(i+0.5f),y)
                    }
                    points.zipWithNext().forEach { (a,b)->drawLine(Color(0xFFFBBF24),a,b,1.5f) }
                    drawLine(Color(0xFF475569),Offset(0f,chartBottom),Offset(size.width,chartBottom),1f)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("● 涨",color=Color(0xFF22C55E),fontSize=10.sp); Text("● 跌",color=Color(0xFFEF4444),fontSize=10.sp); Text("— EMA9",color=Color(0xFFFBBF24),fontSize=10.sp); Text("柱体：成交量",color=Color(0xFF94A3B8),fontSize=10.sp)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(170.dp),contentAlignment=Alignment.Center) { Text("正在加载K线数据…",color=Color(0xFF94A3B8)) }
            }
            Text(status,color=Color(0xFF64748B),fontSize=10.sp)
            if(wave!=null) Text("波浪识别：${wave.label} · 结构可信度${wave.confidence}/100\n${wave.detail}",color=Color(0xFFCBD5E1),fontSize=11.sp)
            if(stage!=null) Text("趋势阶段：${stage.label} · 强度参考${stage.strength}/100",color=Color(0xFF22C55E),fontSize=11.sp)
            Text("说明：仅使用已收盘K线识别波浪；最新价格独立实时显示。10分钟K线由1分钟数据聚合。",color=Color(0xFFF59E0B),fontSize=10.sp)
        }
    }
}

private fun startBacktest(activity: MainActivity) {
    val i=Intent(activity,MarketService::class.java).setAction(MarketService.ACTION_BACKTEST)
    if(Build.VERSION.SDK_INT>=26) activity.startForegroundService(i) else activity.startService(i)
}

private fun startPrediction(activity: MainActivity, horizon: Int) {
    val i=Intent(activity,MarketService::class.java).setAction(MarketService.ACTION_PREDICT_10)
    if(Build.VERSION.SDK_INT>=26) activity.startForegroundService(i) else activity.startService(i)
}

@Composable private fun PredictionPanel(status:String,pending:Boolean,onStart:()->Unit) {
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text("10 分钟智能预测",fontWeight=FontWeight.Bold,color=Color.White,fontSize=18.sp,modifier=Modifier.weight(1f)); Text(if(pending) "结算中" else "独立模块",color=if(pending) Color(0xFFF59E0B) else Color(0xFF22C55E),fontSize=11.sp) }
            Text("1分钟捕捉短线变化，3分钟与5分钟判断主要方向，15分钟确认背景趋势。",color=Color(0xFFCBD5E1),fontSize=13.sp)
            Text("智能知识库：小级别推动浪/调整浪、K线形态、量价、支撑阻力、动能、合约情绪与市场环境共同评分。任意时刻可启动；不再因缺少突破或回踩而拒绝给方向，条件不足只降低评分。",color=Color(0xFF22C55E),fontSize=11.sp)
            Button(onClick=onStart,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) { Text(if(pending) "已有未结算记录 · 继续分析新机会" else "立即分析并给出方向") }
            Text(status,color=Color(0xFF94A3B8),fontSize=12.sp)
        }
    }
}

@Composable private fun Stat(label:String,value:String,modifier:Modifier=Modifier) { Card(modifier,colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(12.dp)) { Column(Modifier.padding(10.dp)) { Text(label,color=Color(0xFF94A3B8),fontSize=10.sp); Text(value,color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp) } } }

@Composable private fun PredictionCard(r: PredictionRecord) {
    val green=Color(0xFF22C55E); val red=Color(0xFFEF4444)
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text("10分钟融合 · ${r.side}",color=if(r.side=="看涨") green else red,fontWeight=FontWeight.Bold,fontSize=17.sp); Spacer(Modifier.weight(1f)); Text("规则信心 ${r.confidence}/100",color=Color(0xFFCBD5E1),fontSize=11.sp) }
            Text("启动价格：$"+String.format(Locale.US,"%,.2f",r.entry),color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            Text("启动时间："+SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(Date(r.createdAt)),color=Color(0xFF94A3B8),fontSize=11.sp)
            if(r.rawSide.isNotBlank() && r.rawSide != r.side) Text("原始方向：${r.rawSide} → 风险规则后：${r.side}",color=Color(0xFFFBBF24),fontSize=11.sp)
            Text("市场状态：${r.marketRegime} · 风险等级：${r.riskLevel}",color=if(r.riskLevel=="高") Color(0xFFF87171) else if(r.riskLevel=="中") Color(0xFFFBBF24) else Color(0xFF86EFAC),fontWeight=FontWeight.SemiBold,fontSize=11.sp)
            if(r.riskWarning.isNotBlank()) Text("风险提示：${r.riskWarning}",color=Color(0xFFFBBF24),fontSize=11.sp)
            Text("策略依据：${r.reason}",color=Color(0xFFCBD5E1),fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically) { Text("到期快照：",color=Color(0xFF94A3B8),fontSize=12.sp); Text(r.snapshot?.let { "$"+String.format(Locale.US,"%,.2f",it) } ?: "等待${r.horizon}分钟后记录",color=Color.White,fontSize=12.sp); Spacer(Modifier.weight(1f)); Text(r.result,color=when(r.result){"正确"->green;"错误"->red;else->Color(0xFFF59E0B)},fontWeight=FontWeight.Bold,fontSize=12.sp) }
        }
    }
}
