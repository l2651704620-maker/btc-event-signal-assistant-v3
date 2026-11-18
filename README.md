# BTC Signal Assistant V8 — 10-minute edition

Android BTC/USDT spot + USDT perpetual market signal assistant. Human-confirmed signals only; it does not place orders.

## V8 focus
- The app now exposes a single prediction horizon: the next 10 minutes. The 30-minute prediction button, service action, signal status, chart interval, and 30-minute backtest path have been removed.
- Reworked direction engine: 1m execution/momentum, locally aggregated 3m structure, 5m primary confirmation, 15m trend context, and a light 1h background filter. The last 1m candle no longer overrides the multi-timeframe vote.
- Added explicit trend-agreement and momentum-agreement diagnostics, anti-chase stretch checks, low-volume warnings, and short-term exhaustion checks.
- Kept the requested display rule: low risk preserves the raw direction; medium/high risk reverses the displayed direction. Raw direction and final direction are stored separately for review.
- Retains Binance spot + USDT perpetual data, Elliott-wave structure, technical knowledge modules, adaptive learning with held-out validation, snapshot settlement, and the existing faster chart refresh path.
- Historical backtest is now 10-minute only with chronological development/validation/out-of-sample splits and fee/slippage assumptions.

## Important data limits
- Liquidation/forced-order stream is not connected; the app must not infer or fabricate liquidation values.
- Order-book imbalance is a snapshot, not spoofing-resistant full-depth flow.
- Rule score is not a calibrated win probability or a guarantee of profit.
- 10-minute BTC direction is noisy. Compare out-of-sample results and maintain enough samples before changing live strategy weights.

## Build
Use JDK 17 and Gradle 8.9:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.
