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
import com.example.btcassistant.data.PredictionRecord
import com.example.btcassistant.data.SignalStore
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
    val status10 by SignalStore.status10.collectAsState(); val status30 by SignalStore.status30.collectAsState()
    val backtestStatus by SignalStore.backtestStatus.collectAsState()
    val futures by SignalStore.futures.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    var showKnowledge by remember { mutableStateOf(false) }
    val pendingRecords = records.filter { it.result == "待结算" }
    val settledRecords = records.filter { it.result != "待结算" }
    val settled10=records.filter { it.strategyGroup=="SHORT" && it.horizon==10 && it.result!="待结算" }; val settled30=records.filter { it.strategyGroup=="SHORT" && it.horizon==30 && it.result!="待结算" }
    val wins10=settled10.count { it.result=="正确" }; val wins30=settled30.count { it.result=="正确" }
    val rate10=if(settled10.isEmpty()) 0 else wins10*100/settled10.size; val rate30=if(settled30.isEmpty()) 0 else wins30*100/settled30.size
    val hallSettled=records.filter { it.strategyGroup=="HALL_OF_FAME" && it.result!="待结算" }; val hallWins=hallSettled.count { it.result=="正确" }; val hallRate=if(hallSettled.isEmpty()) 0 else hallWins*100/hallSettled.size
    Surface(Modifier.fillMaxSize(), color=Color(0xFF0B1220)) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("BTC 智能信号助手",fontSize=23.sp,fontWeight=FontWeight.Bold,color=Color.White); Text("V5.6 · 多指标融合与风险评估 · 人工确认，不自动下单",color=Color(0xFF94A3B8),fontSize=11.sp) }
                }
            }
            item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp)) { Row(verticalAlignment=Alignment.CenterVertically) { Text("BTC/USDT 实时价格",color=Color(0xFF94A3B8),modifier=Modifier.weight(1f)); Text(if(connected) "● 收到实时数据" else "● 未收到数据",color=if(connected) Color(0xFF22C55E) else Color(0xFFF59E0B),fontSize=12.sp) }; Text(if(price>0) "$"+String.format(Locale.US,"%,.2f",price) else "等待行情…",fontSize=31.sp,fontWeight=FontWeight.Bold,color=Color.White); Text("行情源：$marketSource · 有效通道：$activeFeeds/3",fontSize=11.sp,color=Color(0xFF94A3B8)); Text(marketStatus,fontSize=11.sp,color=if(activeFeeds>=2) Color(0xFF22C55E) else Color(0xFFF59E0B)); Text(if(lastUpdate>0) "最近更新：${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(lastUpdate))}" else "尚无行情更新时间",fontSize=10.sp,color=Color(0xFF64748B)) } } }
            item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("币安 U本位永续 · 合约辅助数据",fontWeight=FontWeight.Bold,color=Color.White,fontSize=15.sp)
                Text("资金费率：${futures.fundingRate?.let { String.format(Locale.US,"%.4f%%",it*100) } ?: "--"}   标记价：${futures.markPrice?.let { String.format(Locale.US,"%,.2f",it) } ?: "--"}",color=Color(0xFFCBD5E1),fontSize=12.sp)
                Text("未平仓量：${futures.openInterest?.let { String.format(Locale.US,"%,.2f BTC",it) } ?: "--"}   多空账户比：${futures.longShortRatio?.let { String.format(Locale.US,"%.2f",it) } ?: "--"}",color=Color(0xFFCBD5E1),fontSize=12.sp)
                Text("主动买卖量比：${futures.takerBuySellRatio?.let { String.format(Locale.US,"%.2f",it) } ?: "--"} · ${futures.status}",color=Color(0xFF94A3B8),fontSize=11.sp)
                Text(if(futures.updatedAt>0) "合约数据更新时间：${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(futures.updatedAt))}" else "合约数据尚未成功更新",color=Color(0xFF64748B),fontSize=10.sp)
            } } }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Stat("10分钟胜率","$rate10%",Modifier.weight(1f)); Stat("10分钟结算",settled10.size.toString(),Modifier.weight(1f)); Stat("30分钟胜率","$rate30%",Modifier.weight(1f)); Stat("30分钟结算",settled30.size.toString(),Modifier.weight(1f)) } }
            item { PredictionPanel(10,status10,records.any { it.strategyGroup=="SHORT" && it.horizon==10 && it.result=="待结算" }) { startPrediction(activity,10) } }
            item { PredictionPanel(30,status30,records.any { it.strategyGroup=="SHORT" && it.horizon==30 && it.result=="待结算" }) { startPrediction(activity,30) } }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("技术知识库与策略规则",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp); Text("指标适用条件、共振逻辑与失效风险",color=Color(0xFF94A3B8),fontSize=11.sp) }; TextButton(onClick={ showKnowledge = !showKnowledge }) { Text(if(showKnowledge) "收起 ▲" else "展开 ▼",color=Color(0xFF22C55E)) } }
                        if(showKnowledge) {
                            Text("趋势：EMA9/21/50/200，多周期方向与趋势结构；动能：RSI7/14、MACD柱体与背离；量价：相对成交量、OBV方向代理、突破与回踩；位置：滚动VWAP、近端支撑压力；波动：ATR与高波动识别；合约：OI、资金费率、账户多空比、主动买卖量比。",color=Color(0xFFCBD5E1),fontSize=12.sp)
                            Text("规则提醒：RSI超买超卖不单独反转；放量突破需收盘确认；OBV是K线量价代理，不等同逐笔CVD；合约数据只作有限权重确认。",color=Color(0xFFF59E0B),fontSize=11.sp)
                        }
                    }
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text("大师名人堂 · 独立策略提醒",fontWeight=FontWeight.Bold,color=Color.White,fontSize=18.sp)
                        Text("江恩、琳达·拉什克、威科夫各自独立运行。每分钟检查已收盘K线；任一大师出现强度≥75的独立策略点位，就发出手机提醒，并单独记录快照战绩。名人堂不混入10/30分钟短线胜率。",color=Color(0xFFCBD5E1),fontSize=12.sp)
                        Text("提醒只是策略条件触发，不代表一定盈利，也不会自动下单。相同大师、周期、方向设15分钟冷却，避免重复轰炸。",color=Color(0xFF22C55E),fontSize=11.sp)
                        Text("名人堂已结算：${hallSettled.size}笔 · 胜率${hallRate}%（与短线胜率分开统计）",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                        Text(listOf("江恩","琳达·拉什克","威科夫").joinToString("  |  ") { master -> val rows=hallSettled.filter { it.masterName==master }; val wins=rows.count { it.result=="正确" }; "$master ${if(rows.isEmpty()) "暂无" else "${wins*100/rows.size}% (${rows.size})"}" },color=Color(0xFFCBD5E1),fontSize=11.sp)
                    }
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("短线大厅历史回测",fontWeight=FontWeight.Bold,color=Color.White,fontSize=18.sp)
                        Text("下载约92天Binance现货1分钟历史数据，自动聚合5m/15m/1h，检验简化后的10分钟和30分钟固定入场规则。历史模拟不等于未来胜率。首次运行可能需要几分钟。",color=Color(0xFFCBD5E1),fontSize=12.sp)
                        Button(onClick={ startBacktest(activity) },modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) { Text("运行90天以上历史回测") }
                        Text(backtestStatus,color=Color(0xFF94A3B8),fontSize=11.sp)
                    }
                }
            }
            item { Text("当前开单中 · ${pendingRecords.size}笔",fontWeight=FontWeight.Bold,color=Color.White,fontSize=18.sp) }
            if(pendingRecords.isEmpty()) item { Text("目前没有待结算信号。启动10分钟或30分钟分析后，记录会显示在这里。",color=Color(0xFF94A3B8),fontSize=12.sp) }
            items(pendingRecords) { PredictionCard(it) }
            item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(15.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("历史快照战绩",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp); Text("已结算${settledRecords.size}笔 · 短线与名人堂分别统计",color=Color(0xFF94A3B8),fontSize=11.sp) }
                    TextButton(onClick={ showHistory = !showHistory }) { Text(if(showHistory) "收起 ▲" else "展开 ▼",color=Color(0xFF22C55E)) }
                }
                if(showHistory) {
                    if(settledRecords.isEmpty()) Text("暂无已结算记录",color=Color(0xFF94A3B8),fontSize=12.sp)
                    settledRecords.forEach { PredictionCard(it) }
                }
            } } }
            item { Text("现货采用 Binance、OKX、Bybit 多平台实时交叉验证；合约辅助数据每30秒更新资金费率、标记价、未平仓量、多空账户比和主动买卖量比；至少1路新鲜行情即可启动分析，多路行情会提高数据可靠性。智能知识库综合EMA趋势、RSI动量、量能、多周期结构及江恩/拉什克/威科夫策略。每次点击均给出看涨或看跌方向，不强制观望；条件不足只降低评分。快照战绩保留并自动结算，旧记录保留在本机。模型评分不是胜率概率。",color=Color(0xFF64748B),fontSize=11.sp) }
        }
    }
}

private fun startBacktest(activity: MainActivity) {
    val i=Intent(activity,MarketService::class.java).setAction(MarketService.ACTION_BACKTEST)
    if(Build.VERSION.SDK_INT>=26) activity.startForegroundService(i) else activity.startService(i)
}

private fun startPrediction(activity: MainActivity, horizon: Int) {
    val i=Intent(activity,MarketService::class.java).setAction(if(horizon==10) MarketService.ACTION_PREDICT_10 else MarketService.ACTION_PREDICT_30)
    if(Build.VERSION.SDK_INT>=26) activity.startForegroundService(i) else activity.startService(i)
}

@Composable private fun PredictionPanel(horizon:Int,status:String,pending:Boolean,onStart:()->Unit) {
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF172235)),shape=RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text("$horizon 分钟智能预测",fontWeight=FontWeight.Bold,color=Color.White,fontSize=18.sp,modifier=Modifier.weight(1f)); Text(if(pending) "结算中" else "独立模块",color=if(pending) Color(0xFFF59E0B) else Color(0xFF22C55E),fontSize=11.sp) }
            Text(if(horizon==10) "1分钟K线寻找入场，5分钟K线辅助确认；适合更快节奏。" else "5分钟K线寻找入场，15分钟K线辅助确认；适合稍长的短线节奏。",color=Color(0xFFCBD5E1),fontSize=13.sp)
            Text("智能知识库：趋势、动量、量价、多周期结构、江恩/拉什克/威科夫策略共同投票。任意时刻可启动；不再因缺少突破或回踩而拒绝给方向，条件不足只降低评分。",color=Color(0xFF22C55E),fontSize=11.sp)
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
            Row(verticalAlignment=Alignment.CenterVertically) { Text("${if(r.strategyGroup=="HALL_OF_FAME") "名人堂 · ${r.masterName}" else "短线融合"} · ${r.horizon}分钟 · ${r.side}",color=if(r.side=="看涨") green else red,fontWeight=FontWeight.Bold,fontSize=17.sp); Spacer(Modifier.weight(1f)); Text("规则信心 ${r.confidence}/100",color=Color(0xFFCBD5E1),fontSize=11.sp) }
            Text("启动价格：$"+String.format(Locale.US,"%,.2f",r.entry),color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            Text("启动时间："+SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(Date(r.createdAt)),color=Color(0xFF94A3B8),fontSize=11.sp)
            Text("市场状态：${r.marketRegime} · 风险等级：${r.riskLevel}",color=if(r.riskLevel=="高") Color(0xFFF87171) else if(r.riskLevel=="中") Color(0xFFFBBF24) else Color(0xFF86EFAC),fontWeight=FontWeight.SemiBold,fontSize=11.sp)
            if(r.riskWarning.isNotBlank()) Text("风险提示：${r.riskWarning}",color=Color(0xFFFBBF24),fontSize=11.sp)
            Text("策略依据：${r.reason}",color=Color(0xFFCBD5E1),fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically) { Text("到期快照：",color=Color(0xFF94A3B8),fontSize=12.sp); Text(r.snapshot?.let { "$"+String.format(Locale.US,"%,.2f",it) } ?: "等待${r.horizon}分钟后记录",color=Color.White,fontSize=12.sp); Spacer(Modifier.weight(1f)); Text(r.result,color=when(r.result){"正确"->green;"错误"->red;else->Color(0xFFF59E0B)},fontWeight=FontWeight.Bold,fontSize=12.sp) }
        }
    }
}
