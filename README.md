# AirBattery

Android スマホで、近くにある AirPods の **電池残量** と **位置の手がかり** を確認するアプリです。

## できること

| 機能 | 内容 |
|---|---|
| 電池残量 | 左・右・ケースの残量(10%単位)、充電中かどうか、装着中かどうか |
| モデル表示 | AirPods 第1〜3世代 / Pro / Pro 第2・第3世代 / Max など |
| 接続中の機器 | スマホに Bluetooth(A2DP) で接続中のオーディオ機器名 |
| 位置 | ・いま近くにあるときは電波の強さ(dBm)による「近い/やや離れている」の目安<br>・アプリが最後に AirPods を検出した日時と、そのときの**スマホの位置**を記録し、地図アプリで開けます |

## 制限事項(大事)

- **AirPods 自体の現在地は Android からは取得できません。** AirPods の現在地を表示する「探す(Find My)」は Apple の機能で、Android 向けには提供されていません。このアプリの「位置」は、*アプリが最後に AirPods を検出したときのスマホの位置* です。
- 位置が記録されるのは **アプリを開いている間** に AirPods を検出したときだけです(30秒に1回まで)。
- 電池残量は、AirPods が発信している Bluetooth Low Energy の広告データ(Apple 会社ID `0x004C`)を解析しています。この形式は Apple が公開している仕様ではなく、オープンソースアプリ [OpenPods](https://github.com/adolfintel/OpenPods) で使われている解析方法を参考にしています。そのため 10% 単位であり、将来の機種やファームウェアで表示できなくなる可能性があります。
- 近くにある他人の AirPods を拾う可能性があります。誤検出を減らすため、直近10秒で最も電波が強いもの(-60 dBm 以上)だけを表示します。
- 電波の強さによる距離の目安は、正確な距離ではありません。

## インストール方法

### 方法1: Releases から APK をダウンロード(おすすめ)
1. スマホのブラウザで https://github.com/momijan111/air-battery/releases/latest を開く
2. **Assets** の中の `AirBattery.apk` をタップしてダウンロード
3. ダウンロードした APK をタップしてインストール(「提供元不明のアプリ」の許可が必要です)

### 方法2: Android Studio でビルド
1. Android Studio でこのフォルダを開く
2. スマホを USB で接続し、▶(Run)ボタンを押す

## 使い方
1. アプリを開き「権限を許可する」を押して、Bluetooth(付近のデバイス)と位置情報を許可
2. スマホの Bluetooth をオンにする
3. AirPods のケースのふたを開ける、または耳に装着すると残量が表示されます

## 動作環境
- Android 8.0 (API 26) 以上
- Bluetooth Low Energy 対応端末

## 構成
- `app/src/main/java/com/airbattery/AirPodsParser.kt` … BLE データの解析
- `app/src/main/java/com/airbattery/AirPodsScanner.kt` … BLE スキャン
- `app/src/main/java/com/airbattery/LastSeenStore.kt` … 最後に検出した日時・場所の保存
- `app/src/main/java/com/airbattery/MainActivity.kt` … 画面(Jetpack Compose)
- `app/src/test/...` … 解析処理の単体テスト

ビルド・テスト: `./gradlew testDebugUnitTest assembleDebug`
