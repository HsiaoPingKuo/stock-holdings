# 持股記帳

記錄每一次買賣，用證交所、櫃買中心的官方資料算出每檔股票和全部持股的損益。

2.0 的重點：
- **每次買賣記一筆交易**：同一檔可以分好幾次買、分好幾次賣，用平均成本計算
- **可以記零股**：直接填股數（例如 500 股），不用換算成張
- **已實現／未實現損益分開**：賣掉的部分算已實現，還拿在手上的用收盤價算未實現
- **依官方資料自動計算除權息**：依照你持有的股數，自動算出領到的現金股利和配股，不用自己輸入
- **含息總報酬** = 已實現損益 + 未實現損益 + 累計現金股利

有三種使用方式，都讀寫同一個資料庫：

| 使用方式 | 網址 | 說明 |
|---|---|---|
| 網頁 | http://localhost:8080 | 見下方「使用方式」 |
| App 1.0 | `/api/v1` | 1.0 的規格不變，舊版 App 不用改版就能繼續使用，見「App 1.0 API」 |
| App 2.0 | `/api/v2` | 交易、除權息明細、含息總報酬，見「App 2.0 API」 |

## 啟動

需要先安裝：**JDK 17 以上**（不用裝 Maven、不用裝資料庫）。在終端機執行 `java -version`，顯示 17 以上即可。

```bash
git clone https://github.com/HsiaoPingKuo/stock-holdings.git
cd stock-holdings
./mvnw spring-boot:run
```

Windows PowerShell 的最後一行改成 `.\mvnw.cmd spring-boot:run`。

看到 `Started StockHoldingsApplication` 後，打開 **http://localhost:8080**。
第一次啟動會自動下載相依套件，需要網路，約 1～5 分鐘。

執行測試：`./mvnw test`（Windows PowerShell：`.\mvnw.cmd test`）

**從 1.0 升級**：請看 [UPGRADE.md](UPGRADE.md)。

## 使用方式

打開 http://localhost:8080 就是持股清單（http://localhost:8080/holdings 也是同一頁）。

### 新增交易

在清單頁的「新增交易」輸入代號、買進或賣出、日期、股數、價格，按「新增」。

- **股票代號**：只能是英文字母或數字，最多 10 個字；前後空白會去掉，英文字母會轉成大寫
  - 輸入後（停止輸入 0.5 秒，或離開欄位），欄位旁會顯示名稱和市場，例如「台積電（上市）」
  - 已經有的代號：交易加在那檔持股下面（已賣光的也一樣）
  - 還沒有的代號：只能買進，系統會先確認是上市還是上櫃。行情快取已經有這個代號時直接使用，不向官方查；
    沒有時才向證交所、櫃買中心查詢，**這時需要能連上網路**，按下新增後最多約 9 秒一定會有結果
  - 查不到、官方連不上、無法判斷上市或上櫃時，會顯示原因，資料不儲存
- **日期**：成交日，不能早於 2021-01-01、不能晚於今天（台北時間）、不能是週末。國定假日不會擋
- **股數**：1～1,000,000,000 的整數，單位是股。零股直接填，例如 `500`
- **價格**：每股成交價，大於 0，最多兩位小數
- **賣超過持有的股數**：顯示原因和「查看交易」連結，資料不儲存。系統會依日期從頭檢查，
  補登一筆以前的賣出、或改掉以前的買進，只要任何一天的股數變成負的都會擋下

輸入有誤時，錯誤訊息顯示在該欄位旁邊。每張表單只能送出一次：按兩下或按「上一頁」再送，
會顯示「這個表單已經送出過，畫面已重新整理，請確認後再操作」，不會重複新增。

### 持股清單

- 頁面最上面是全部持股的總計：總成本、市值、未實現損益、已實現損益、累計現金股利、含息總報酬。
- 每一檔顯示總股數、平均成本、收盤價與日期、市值、未實現損益、已實現損益、累計現金股利、含息總報酬。
- 全部賣光的股票列在最下面的「已賣光」，因為它還有已實現損益和股利。
- 有零股的持股會提示「含零股 500 股・App 1.0 只顯示整張，請更新 App 2.0」。
- 顏色照台股慣例：**紅色是賺、綠色是虧**，剛好打平不上色。
- 按「查看交易」進入那檔的明細頁。

### 明細頁（修改、刪除交易）

- **交易**：依計算順序列出（先比日期，同一天照輸入順序）。
  - **修改**：可以改日期、股數、價格；買進或賣出不能改，要改請刪掉再重新新增
  - **刪除**：確認後刪除。刪掉的是這檔的最後一筆交易時，持股會一起從清單移除
  - 修改、刪除後會從頭重算，造成超賣的話一樣會擋下，並在交易清單標出是哪一筆
  - 另一個分頁或裝置已經改過同一筆時，會顯示訊息並重新讀取最新內容，不會蓋掉別人的修改
- **推估日期**：從 1.0 轉過來的持股、App 1.0 新增的持股，沒有實際交易日，用建立日期代替，
  日期旁會標示「推估」。實際交易日可能更早，請按「修改」改成實際日期，除權息才會算對。
- **除權息**：列出持有期間的每一次除權息（除權息日、種類、可參與股數、現金股利、配股）和資料來源：
  - 官方：官方公布的數字
  - 估算：官方沒有明細，用參考價估算
  - 無法取得：抓不到資料，**不計入**現金股利和配股
  - 單筆現金股利 20,000 元以上時會提示「入帳時會先扣 2.11% 二代健保補充保費」（只是提示，計算不扣）

## 收盤價怎麼更新

- 清單上的收盤價來自本機資料庫的行情快取，打開清單不會向官方查詢。
- 伺服器啟動時，會在背景向官方查一次最近一個交易日的收盤價（例如週六啟動拿到週五的資料），不影響啟動速度；官方連不上也能正常啟動。
- 交易日（週一到週五，台北時間）13:40 起由背景排程更新，上市、上櫃各自查詢：
  13:40～14:30 約每 60 秒一次，14:30～15:40 每 10 分鐘一次；
  15:40～23:40 只有今天還沒拿到資料的市場繼續查，每 20 分鐘一次，讓官方延遲公布時仍能在公布後一小時內更新。
  拿到當天資料後，該市場當天就不再查整個市場。
  （依實測，官方約 13:50～14:02 公布當天收盤價。）
- 全市場批次查詢失敗時，改成逐檔查詢有人持有的股票；被官方擋下時該市場暫停 30 分鐘。
- 全市場批次只有某一天的資料：有人持有的股票那天沒有成交（或批次裡沒有這一檔）時，會再逐檔查詢，找出前一個有成交日的收盤價。
  查到結果（包括確定兩個月內都沒有成交）就當天不再查；官方暫時查不了時，下一輪再試，同一檔一天最多 6 次。
  當天資料已拿到、但還有待補查的股票時，排程照上面的時段繼續，只做補查。
- 排程會讀取證交所的休市日表，事先公布的休市日當天不查詢；休市日表查不到時（例如測試伺服器沒有這支 API）當成交易日，照常查詢。
  颱風假等臨時休市不在表裡，那天會查到「沒有資料」，重試到 23:40 為止。
- 23:40 之後到下一個交易日 13:40 之前，若官方或測試伺服器的資料有變動，要等下一個交易日 13:40 之後，或重新啟動伺服器才會更新。

## 清單的收盤價怎麼顯示

- 一般：顯示最近一個交易日的收盤價與日期。
- 當天沒有成交：顯示前一個有成交日的收盤價，並標示「{最近交易日} 無成交，顯示 {成交日} 收盤價」。
- 顯示「未取得」：還沒拿到這檔股票的行情（例如剛啟動、官方連不上），或這檔股票這個月和上個月都沒有成交（例如剛上市、長期停牌）。

## 除權息資料

除權息資料來自證交所（上市）和櫃買中心（上櫃）的除權息公告，和收盤價一樣只由背景排程抓取，打開頁面不會向官方查詢。

- **每天 07:30**（台北時間，交易日；休市日跳過）重抓今年的資料。官方當天稍晚才公布的除權息，隔天才會出現。
- **伺服器啟動時**也會抓一次：每個市場在收盤價的啟動查詢結束後開始，抓「最早一筆交易的那一年（最早 2021 年）到今年」。
  以前年份抓完過的就不再抓，今年每次都會重抓。
- **補登了更早年份的交易**時，每 30 分鐘會檢查一次，有缺的年份在背景補抓。
- 每兩個請求之間隨機等 3.2～5.8 秒；被官方擋下時，和收盤價一起暫停 30 分鐘。

**「股利資料更新中」的意思**：這檔持股持有期間的除權息資料還沒抓完（例如剛啟動、剛補登更早的交易、官方暫時連不上）。
這時累計現金股利、含息總報酬可能少算，請稍後重新整理；抓完後提示就會消失。
抓不到的單筆除權息，明細頁的來源會顯示「無法取得」，不計入股利和配股。

## 盈虧怎麼算

每次打開頁面時，把這檔的所有交易和除權息**依日期從頭重算一遍**，結果不存進資料庫，
所以補登、修改、刪除以前的交易後，數字會自動更新。

- **平均成本**：買進時，成交金額加上手續費算進總成本；平均成本 = 總成本 ÷ 持有股數。
- **已實現損益**：賣出時，依賣出的比例扣掉成本，賣出金額扣掉手續費、證交稅和這部分成本，就是這次的已實現損益。
- **未實現損益**：假設用最新收盤價把剩下的股票全部賣掉，扣掉手續費和證交稅後，比總成本多（或少）多少。
- 手續費 0.1425%、元以下捨去、最低 20 元；證交稅只有賣出才收，一般股票 0.3%，ETF（代號 `00` 開頭）0.1%。

計算順序、每一步的公式、捨入規則，以及刻意不處理的規則（當沖、股票分割等），請看 [API.md 第 5 節](API.md#5-計算規則)和第 7 節。

### 總計

- 總成本、已實現損益、累計現金股利：全部持股加總，含已賣光的。
- 市值、未實現損益：只加有收盤價的持股。有持股還沒拿到收盤價時，會提示「N 檔未取得收盤價，市值、未實現損益、含息總報酬不含這幾檔」。
- 資料異常（例如有人直接改過資料庫，算出某天的股數是負的）的持股不計入總計，清單上會標示「資料異常」，到明細頁修改或刪除有問題的交易後就會恢復。

## App 1.0 API

給 Android App 1.0 用的 API，完全照附件《App 1.0 API 規格》：路徑開頭 `/api/v1`，回應一律是 JSON（`Content-Type: application/json;charset=UTF-8`），欄位名稱小寫加底線（`buy_price`）。

**升級到 2.0 之後**，路徑、欄位、錯誤格式都不變，但因為持股改成用交易計算，App 1.0 會看到一些差異，例如：
有零股時 `lots` 捨去到整張並在名稱後加註、全部賣光的持股不再出現、有多筆交易的持股不能從 App 1.0 修改或刪除（回 409 `UPGRADE_REQUIRED`）。
完整的差異請看 [API.md 第 8 節](API.md#8-apiv1app-10)。

| 方法 | 路徑 | 成功 | 失敗 |
|---|---|---|---|
| GET | `/api/v1/holdings` | 200，持股物件陣列；沒有持股回 `[]` | |
| GET | `/api/v1/holdings/{id}` | 200，持股物件 | 404 `NOT_FOUND` |
| POST | `/api/v1/holdings` | 201，新建的持股物件 | 400 `VALIDATION_ERROR`、404 `STOCK_NOT_FOUND`、409 `DUPLICATE_CODE`、503（見下方） |
| PUT | `/api/v1/holdings/{id}` | 200，修改後的持股物件 | 400 `VALIDATION_ERROR`、404 `NOT_FOUND` |
| DELETE | `/api/v1/holdings/{id}` | 204，沒有內容 | 404 `NOT_FOUND` |
| GET | `/api/v1/summary` | 200，總計物件 | |

新增持股的範例（Mac／Linux、Git Bash）：
```bash
curl -i -X POST http://localhost:8080/api/v1/holdings -H "Content-Type: application/json" -d '{"code":"2330","lots":1,"buy_price":1000}'
```

Windows PowerShell 5.1：先執行 `chcp 65001`，中文才會正常顯示；指令要寫 `curl.exe`（`curl` 在 PowerShell 5.1 是別的指令），JSON 裡的 `"` 要寫成 `\"`：
```powershell
chcp 65001
curl.exe -i -X POST http://localhost:8080/api/v1/holdings -H "Content-Type: application/json" -d '{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}'
```

所有錯誤都是同一個格式，App 會直接顯示 `message`：
```json
{ "error": { "code": "DUPLICATE_CODE", "message": "2330 已在持股中" } }
```

### 規格沒有寫到的情況

| 情況 | 回應 |
|---|---|
| 新增時官方網站連不上，無法確認代號 | 503 `QUOTE_UNAVAILABLE`「暫時無法連線官方網站確認代號，請稍後再試」 |
| 新增時上市、上櫃都查到，而且最近交易日相同 | 503 `MARKET_UNDETERMINED`「無法判斷 {代號} 是上市或上櫃，請聯絡管理者」 |
| 型別不對（張數 1.5、`"1"`，買進價 `"1000"`，代號 `2330` 數字） | 400 `VALIDATION_ERROR`，不會自動轉換或截斷 |
| JSON 格式壞掉、沒有內容、Content-Type 不是 `application/json` | 400 `VALIDATION_ERROR` |
| `{id}` 不是數字或超出範圍 | 404 `NOT_FOUND` |
| 路徑不存在 | 404 `NOT_FOUND` |
| 方法不支援（例如 PATCH） | 405 `METHOD_NOT_ALLOWED` |
| 伺服器內部錯誤 | 500 `INTERNAL_ERROR`，不會露出錯誤細節 |
| 請求的 Accept 不是 JSON（例如只接受 `text/html`） | `/api/` 一律回 JSON（成功和錯誤都是），不會回 HTML 或 406 |
| PUT 的請求帶了 `code` | 忽略（代號不能改），不回錯誤；其他不認得的欄位也忽略 |
| 第 2 題時存的舊持股，還沒補到名稱或市場 | 先用行情快取補上；行情快取也沒有時（例如官方一直連不上），`name`、`market` 回空字串 `""`，不會是 `null` |
| 全部持股都沒有收盤價 | 總計的 `market_value`、`pnl` 是 0，`return_pct` 是 0.00（網頁顯示「—」，API 照規格回數字） |

### App 1.0 怎麼連

- **Android Studio 模擬器**：App 的「設定」填 `http://10.0.2.2:8080`。模擬器裡的 `localhost` 指的是模擬器自己，`10.0.2.2` 才是你的電腦。
- **實體手機**：手機和電腦連同一個 Wi-Fi，「設定」填電腦的區網 IP，例如 `http://192.168.1.20:8080`。
  - 查電腦的 IP：Windows 執行 `ipconfig`，看「IPv4 位址」；Mac 到「系統設定 → Wi-Fi → 詳細資訊」。
  - 電腦的防火牆要允許 8080 埠的連線。Windows 用系統管理員身分開 PowerShell 執行：
    ```powershell
    New-NetFirewallRule -DisplayName "stock-holdings 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
    ```

## App 2.0 API

`/api/v2` 的規格（交易的新增修改刪除、除權息明細、`Idempotency-Key`、錯誤代碼）請看 [API.md](API.md)。

## 更換行情主機

股票資料來自證交所（上市）與櫃買中心（上櫃）。主機網址可以用環境變數換成自己的測試伺服器，**路徑和參數不變**。
每個主機都有「全市場批次」和「逐檔」兩支 API：背景排程優先用批次，批次失敗才用逐檔；新增持股時判斷市場用逐檔。
除權息 API 也用同樣的 `TWSE_BASE_URL`、`TPEX_BASE_URL`，換主機時會一起換：
上市列表 `{TWSE_BASE_URL}/rwd/zh/exRight/TWT49U?startDate=20260101&endDate=20261125&response=json`、
上市明細 `{TWSE_BASE_URL}/rwd/zh/exRight/TWT49UDetail?STK_NO=2330&T1=20260616&response=json`、
上櫃列表 `{TPEX_BASE_URL}/www/zh-tw/bulletin/exDailyQ?startDate=2026/01/01&endDate=2026/11/25&response=json`。

| 環境變數 | 預設值 | 實際呼叫的網址 |
|---|---|---|
| `TWSE_BASE_URL` | `https://www.twse.com.tw` | 批次：`{TWSE_BASE_URL}/rwd/zh/afterTrading/MI_INDEX?date=20261124&type=ALLBUT0999&response=json`<br>逐檔：`{TWSE_BASE_URL}/rwd/zh/afterTrading/STOCK_DAY?date=20261124&stockNo=2330&response=json` |
| `TPEX_BASE_URL` | `https://www.tpex.org.tw` | 批次：`{TPEX_BASE_URL}/www/zh-tw/afterTrading/otc?date=2026/11/24&type=EW&response=json`<br>逐檔：`{TPEX_BASE_URL}/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/24&response=json` |
| `TWSE_HOLIDAY_URL` | `https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule` | 休市日表的**完整網址**（主機和行情不同，所以獨立設定） |
| `QUOTE_REFRESH_ENABLED` | `true` | 設成 `false` 可關閉背景更新 |
| `DIVIDEND_SYNC_ENABLED` | `true` | 設成 `false` 可關閉除權息同步 |
| `IDEMPOTENCY_CLEANUP_ENABLED` | `true` | 設成 `false` 可關閉每小時清除過期的冪等鍵紀錄 |

Mac／Linux（只對這一次啟動有效）：
```bash
TWSE_BASE_URL=http://localhost:9000 TPEX_BASE_URL=http://localhost:9000 ./mvnw spring-boot:run
```

Windows PowerShell：
```powershell
$env:TWSE_BASE_URL="http://localhost:9000"; $env:TPEX_BASE_URL="http://localhost:9000"; .\mvnw.cmd spring-boot:run
```
注意：PowerShell 設定的環境變數，在**同一個視窗會一直有效**。要改回官方網站時，先清掉再啟動（或開一個新視窗）：
```powershell
Remove-Item Env:TWSE_BASE_URL, Env:TPEX_BASE_URL, Env:TWSE_HOLIDAY_URL -ErrorAction SilentlyContinue
```

Windows 命令提示字元（cmd）：每個 `set` 各寫一行，再另起一行啟動。不要寫成 `set X=值 && 指令`，`&&` 前面的空格會被算進變數值。
```bat
set TWSE_BASE_URL=http://localhost:9000
set TPEX_BASE_URL=http://localhost:9000
.\mvnw.cmd spring-boot:run
```
cmd 設定的環境變數一樣在**同一個視窗會一直有效**。要改回官方網站時，先清掉再啟動（或開一個新視窗）：
```bat
set TWSE_BASE_URL=
set TPEX_BASE_URL=
set TWSE_HOLIDAY_URL=
```

其他設定在 `src/main/resources/application.properties`：

| 設定 | 預設 | 說明 |
|---|---|---|
| `quote.connect-timeout` | 3s | 建立連線的上限 |
| `quote.read-timeout` | 5s | 等待回應的上限 |
| `quote.lookup-timeout` | 8s | 同時查上市、上櫃的上限；剩下的時間預算比較少時，以剩下的為準 |
| `quote.request-budget`<br>（環境變數 `QUOTE_REQUEST_BUDGET`） | 9s | 新增持股、即時提示的時間預算，從收到請求開始算，排隊等候也算在內（題目要求 10 秒內看到結果，留 1 秒給寫入資料庫和網路） |
| `quote.max-concurrent-lookups`<br>（環境變數 `QUOTE_MAX_CONCURRENT_LOOKUPS`） | 4 | 同時查官方的代號數上限，至少 1；新增持股、即時提示、背景補查舊持股共用 |
| `quote.lookup-min-remaining`<br>（環境變數 `QUOTE_LOOKUP_MIN_REMAINING`） | 2s | 剩下的時間少於這個值就不查官方，直接回「暫時無法確認」 |
| `quote.lookup-cache-ttl` | 10m | 查過的代號記住多久，避免短時間重複查詢官方 |
| `quote.refresh.start-time`／`slowdown-time`／`end-time`／`late-end-time` | 13:40／14:30／15:40／23:40 | 背景排程的時段（台北時間）；15:40～23:40 只有今天還沒拿到資料、或還有待補查的市場會查 |
| `quote.refresh.fast-interval`／`slow-interval`／`late-interval` | 60s／10m／20m | 快速、慢速、延長時段每一輪的間隔 |
| `quote.refresh.per-request-delay-min`／`max` | 3200ms／5800ms | 逐檔查詢時，請求之間的隨機等待 |
| `quote.refresh.cooldown` | 30m | 被官方擋下後暫停多久 |
| `quote.refresh.startup-lookback-days` | 10 | 啟動時今天沒有資料，最多往前找幾天 |
| `quote.dividend-read-timeout`<br>（環境變數 `DIVIDEND_READ_TIMEOUT`） | 20s | 除權息 API 等待回應的上限（一次查一整年，實測約 5 秒，行情的 5 秒不夠）；建立連線沿用 `quote.connect-timeout` |
| `dividend.sync.enabled` | true | 除權息同步的開關 |
| `dividend.sync.daily-time` | 07:30 | 每個交易日重抓今年的時間（台北時間） |
| `dividend.sync.backfill-check-interval` | 30m | 多久檢查一次有沒有年份還沒抓（只讀資料庫，有缺才發請求） |
| `dividend.sync.retry-delay` | 5m | 收盤價或另一輪除權息正在查時，延後多久再試 |
| `idempotency.retention` | 24h | 同一個 `Idempotency-Key` 保留多久 |
| `idempotency.cleanup-enabled` | true | 是否定期刪除過期紀錄 |
| `idempotency.cleanup-interval` | 1h | 多久刪一次過期紀錄 |

有標示環境變數的設定，可以不改檔案直接調整。時間的寫法是 `9s`（秒）或 `500ms`（毫秒）。
`QUOTE_REQUEST_BUDGET` 要小於 10 秒才符合題目要求，`QUOTE_LOOKUP_MIN_REMAINING` 要小於 `QUOTE_REQUEST_BUDGET`。
以下範例把同時查詢上限改成 8：

Mac／Linux（只對這一次啟動有效）：
```bash
QUOTE_MAX_CONCURRENT_LOOKUPS=8 ./mvnw spring-boot:run
```

Windows PowerShell：
```powershell
$env:QUOTE_MAX_CONCURRENT_LOOKUPS="8"; .\mvnw.cmd spring-boot:run
```
改回預設值（同一個視窗會一直有效，或開新視窗）：
```powershell
Remove-Item Env:QUOTE_REQUEST_BUDGET, Env:QUOTE_MAX_CONCURRENT_LOOKUPS, Env:QUOTE_LOOKUP_MIN_REMAINING -ErrorAction SilentlyContinue
```

Windows 命令提示字元（cmd）：先 `set`，再另起一行啟動（不要寫成 `set X=值 && 指令`）。
```bat
set QUOTE_MAX_CONCURRENT_LOOKUPS=8
.\mvnw.cmd spring-boot:run
```
改回預設值（同一個視窗會一直有效，或開新視窗）：
```bat
set QUOTE_REQUEST_BUDGET=
set QUOTE_MAX_CONCURRENT_LOOKUPS=
set QUOTE_LOOKUP_MIN_REMAINING=
```

## 專案架構

| 項目 | 使用技術 |
|---|---|
| 後端 | Java 17、Spring Boot 4.2（內嵌 Tomcat） |
| 畫面 | Thymeleaf |
| 資料庫 | H2 檔案模式，資料存在 `./data/` |
| 資料表 | Flyway migration 建立 |

**為什麼用 Spring Boot 4**：start.spring.io 已不提供 3.x，題目也允許 Java 17 以上。

```
src/main/java/io/hsiaopingkuo/stockholdings/
├── StockHoldingsApplication.java   程式進入點
├── api/                            App 1.0 的 REST API（/api/v1）、App 2.0 的 REST API（/api/v2 持股、交易）、統一的錯誤格式
├── calc/                           計算規則，不碰資料庫、不連網：交易與除權息依日期重算（HoldingReplayCalculator）、
                                    手續費與證交稅（ProfitCalculator）、總計、股利來源與狀態、App 1.0 相容換算（V1Compatibility）
├── config/                         台北時區 Clock、行情與除權息 API 設定、排程、冪等鍵、CSRF、JSON 嚴格型別檢查、App API 一律回 JSON
├── controller/                     網頁：持股清單、明細頁、交易新增／修改／刪除、代號即時查詢
├── dto/                            表單、App API 的請求與回應、JSON 錯誤格式、清單和明細的一列
├── entity/                         持股、交易（Trade）、行情快取、除權息事件、已抓的除權息年份、冪等鍵紀錄
├── exception/                      查無代號、官方連不上、超賣、版本衝突、冪等鍵錯誤、需要升級 App 等
├── quote/                          官方 API：收盤行情（批次與逐檔）、休市日表、除權息列表與明細、判斷上市或上櫃
├── repository/                     資料庫存取
└── service/                        業務規則：交易（TradeService）、持股查詢與組裝、冪等鍵、
                                    收盤價背景更新（QuoteRefreshScheduler）、除權息同步（DividendSyncScheduler）
src/main/resources/
├── application.properties          埠號、資料庫、行情主機、逾時、排程、冪等鍵設定
├── messages.properties             表單型別錯誤的中文訊息
├── db/migration/                   Flyway：V1 持股表、V2 名稱與市場、V3 行情快取、V4 交易表（1.0 持股轉成交易）、
                                    V5 除權息事件、V6 冪等鍵紀錄
└── templates/                      畫面（持股清單 holdings.html、明細頁 holding-detail.html）
```

主機時區不是台北，所以「現在時間」一律從 `ClockConfig` 的 `Asia/Taipei` Clock 取得。

**為什麼選 H2 檔案模式**：clone 下來就能啟動，不用另外安裝資料庫；用檔案模式，重開伺服器資料還在。
資料表一律由 Flyway 建立（`src/main/resources/db/migration/`），已經發布的 migration 不會再修改，資料表有變動時一律新增下一個版本號的檔案。
