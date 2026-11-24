# 持股記帳

記下自己買了哪些股票，用官方收盤價算出「現在全部賣掉，扣掉手續費和證交稅後淨賺多少」。

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

## 使用方式

打開 http://localhost:8080 ，點「持股清單」（或直接打開 http://localhost:8080/holdings ）：

- **新增**：輸入股票代號、張數、買進價後按「新增」。
  - 行情快取已有這個代號時（伺服器啟動後會自動抓全市場行情），直接使用快取的市場與名稱，不向官方查詢
  - 快取沒有時才向證交所、櫃買中心確認代號，**這時需要能連上網路**
  - 股票代號：必填，只能是英文字母或數字，最多 10 個字；前後空白會自動去掉，英文字母會轉成大寫（例如 `00631l` 存成 `00631L`）
  - 輸入代號後（停止輸入 0.5 秒，或離開欄位），欄位旁會顯示股票名稱與市場，例如「台積電（上市）」
    - 這只是提示，文字比按下新增後的錯誤訊息短：官方連不上時顯示「暫時無法確認」，查不到時顯示「查無此股票代號」
    - 修改或清空代號時，舊的提示會立刻清掉
  - 系統會自動判斷上市或上櫃，並存下股票名稱
  - 上市、上櫃都查不到（例如打錯代號、興櫃股票）：顯示「查無此股票代號：9999（上市、上櫃都找不到）」，資料不儲存
  - 官方網站連不上、回應太慢，或同時查詢官方的人太多：顯示「暫時無法連線官方網站確認代號，請稍後再試」，資料不儲存
  - 上市、上櫃都查到，而且最近交易日相同，無法判斷是哪一個市場：顯示「無法判斷 1234 是上市或上櫃，請聯絡管理者」，資料不儲存（重試也不會好，需要人工確認）
  - 按下新增後最多約 9 秒一定會有結果（官方很慢時也一樣），不會一直轉圈
  - 張數：1～999 的整數（1 張 = 1,000 股）
  - 買進價：每股價格，大於 0，最多兩位小數
- 清單會顯示每筆的名稱、市場（上市／上櫃）、收盤價與收盤價日期（見下方「清單的收盤價怎麼顯示」）。
- **同一個股票代號只能有一筆**：重複新增會顯示「2330 已在持股中」，資料不儲存。
- **修改**：按該筆的「修改」，只能改張數和買進價，股票代號不能改。
- **刪除**：按「刪除」，確認後刪除。

輸入有誤時，錯誤訊息會顯示在該欄位旁邊，資料不會儲存。

## 收盤價怎麼更新

- 清單上的收盤價來自本機資料庫的行情快取，打開清單不會向官方查詢。
- 伺服器啟動時，會在背景向官方查一次最近一個交易日的收盤價（例如週六啟動拿到週五的資料），不影響啟動速度；官方連不上也能正常啟動。
- 交易日（週一到週五，台北時間）13:40～15:40 由背景排程更新，上市、上櫃各自查詢：
  13:40～14:30 約每 60 秒一次，14:30～15:40 每 10 分鐘一次；拿到當天資料後該市場當天就停止。
  （依實測，官方約 13:50～14:02 公布當天收盤價。）
- 全市場批次查詢失敗時，改成逐檔查詢有人持有的股票；被官方擋下時該市場暫停 30 分鐘。
- 排程會讀取證交所的休市日表，事先公布的休市日當天不查詢；休市日表查不到時（例如測試伺服器沒有這支 API）當成交易日，照常查詢。
  颱風假等臨時休市不在表裡，那天會查到「沒有資料」，重試到 15:40 為止。
- 非交易時段若官方或測試伺服器的資料有變動，要等下一個交易日 13:40 之後，或重新啟動伺服器才會更新。

## 清單的收盤價怎麼顯示

- 一般：顯示最近一個交易日的收盤價與日期。
- 當天沒有成交：顯示前一個有成交日的收盤價，並標示「{最近交易日} 無成交，顯示 {成交日} 收盤價」。
- 顯示「未取得」：還沒拿到這檔股票的行情（例如剛啟動、官方連不上），或這檔股票一直沒有成交紀錄。

## 更換行情主機

股票資料來自證交所（上市）與櫃買中心（上櫃）。主機網址可以用環境變數換成自己的測試伺服器，**路徑和參數不變**。
每個主機都有「全市場批次」和「逐檔」兩支 API：背景排程優先用批次，批次失敗才用逐檔；新增持股時判斷市場用逐檔。

| 環境變數 | 預設值 | 實際呼叫的網址 |
|---|---|---|
| `TWSE_BASE_URL` | `https://www.twse.com.tw` | 批次：`{TWSE_BASE_URL}/rwd/zh/afterTrading/MI_INDEX?date=20261124&type=ALLBUT0999&response=json`<br>逐檔：`{TWSE_BASE_URL}/rwd/zh/afterTrading/STOCK_DAY?date=20261124&stockNo=2330&response=json` |
| `TPEX_BASE_URL` | `https://www.tpex.org.tw` | 批次：`{TPEX_BASE_URL}/www/zh-tw/afterTrading/otc?date=2026/11/24&type=EW&response=json`<br>逐檔：`{TPEX_BASE_URL}/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/24&response=json` |
| `TWSE_HOLIDAY_URL` | `https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule` | 休市日表的**完整網址**（主機和行情不同，所以獨立設定） |
| `QUOTE_REFRESH_ENABLED` | `true` | 設成 `false` 可關閉背景更新 |

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

其他行情設定在 `src/main/resources/application.properties`：

| 設定 | 預設 | 說明 |
|---|---|---|
| `quote.connect-timeout` | 3s | 建立連線的上限 |
| `quote.read-timeout` | 5s | 等待回應的上限 |
| `quote.lookup-timeout` | 8s | 同時查上市、上櫃的上限；剩下的時間預算比較少時，以剩下的為準 |
| `quote.request-budget`<br>（環境變數 `QUOTE_REQUEST_BUDGET`） | 9s | 新增持股、即時提示的時間預算，從收到請求開始算，排隊等候也算在內（題目要求 10 秒內看到結果，留 1 秒給寫入資料庫和網路） |
| `quote.max-concurrent-lookups`<br>（環境變數 `QUOTE_MAX_CONCURRENT_LOOKUPS`） | 4 | 同時查官方的代號數上限，至少 1；新增持股、即時提示、背景補查舊持股共用 |
| `quote.lookup-min-remaining`<br>（環境變數 `QUOTE_LOOKUP_MIN_REMAINING`） | 2s | 剩下的時間少於這個值就不查官方，直接回「暫時無法確認」 |
| `quote.lookup-cache-ttl` | 10m | 查過的代號記住多久，避免短時間重複查詢官方 |
| `quote.refresh.start-time`／`slowdown-time`／`end-time` | 13:40／14:30／15:40 | 背景排程的時段（台北時間） |
| `quote.refresh.fast-interval`／`slow-interval` | 60s／10m | 快速、慢速時段每一輪的間隔 |
| `quote.refresh.per-request-delay-min`／`max` | 3200ms／5800ms | 逐檔查詢時，請求之間的隨機等待 |
| `quote.refresh.cooldown` | 30m | 被官方擋下後暫停多久 |
| `quote.refresh.startup-lookback-days` | 10 | 啟動時今天沒有資料，最多往前找幾天 |

上面三個有標示環境變數的設定，可以不改檔案直接調整。時間的寫法是 `9s`（秒）或 `500ms`（毫秒）。
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
├── config/                         台北時區 Clock、行情 API 設定（主機網址、逾時、排程）
├── controller/                     網頁：首頁、持股清單（新增／修改／刪除、代號即時查詢）
├── dto/                            表單欄位與檢查規則、JSON 回應格式、清單的一列（含收盤價）
├── entity/                         持股（Holding）、行情快取（Quote）、上市／上櫃（Market）
├── exception/                      重複代號、找不到持股、查無代號、官方連不上、無法判斷上市或上櫃
├── quote/                          官方 API：批次與逐檔行情、休市日表、判斷上市或上櫃（MarketResolver，
                                    含同一代號共用查詢、同時查詢上限與時間預算）
├── repository/                     資料庫存取
└── service/                        業務規則：持股（HoldingService）、行情快取（QuoteService）、
                                    背景更新（QuoteRefreshService 一輪的流程、QuoteRefreshScheduler 排程）
src/main/resources/
├── application.properties          埠號、資料庫、行情主機、逾時與排程設定
├── messages.properties             表單型別錯誤的中文訊息
├── db/migration/                   Flyway 資料表（V1 建立持股表、V2 加上名稱與市場、V3 建立行情快取表）
└── templates/                      畫面（首頁、持股清單、修改持股）
```

主機時區不是台北，所以「現在時間」一律從 `ClockConfig` 的 `Asia/Taipei` Clock 取得。

**為什麼選 H2 檔案模式**：clone 下來就能啟動，不用另外安裝資料庫；用檔案模式，重開伺服器資料還在。
資料表一律由 Flyway 建立（`src/main/resources/db/migration/`），已經發布的 migration 不會再修改，資料表有變動時一律新增下一個版本號的檔案。
