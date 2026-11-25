# 持股管理 API（給 App 2.0 團隊）

- 伺服器網址：模擬器連本機用 `http://10.0.2.2:8080`，其他情況換成實際主機
- 請求和回應都是 JSON（UTF-8）。送 JSON 時要帶 `Content-Type: application/json`
- 欄位名稱都用 snake_case。**值是 `null` 的欄位照樣輸出，不會省略**
- 日期格式 `YYYY-MM-DD`，以台北時間為準
- 不用登入，也不用 cookie

| 方法 | 路徑 | 用途 |
|---|---|---|
| GET | `/api/v2/holdings` | 持股清單 |
| GET | `/api/v2/holdings/{id}` | 單筆持股＋交易＋除權息明細 |
| GET | `/api/v2/summary` | 總計 |
| POST | `/api/v2/trades` | 新增交易（買進或賣出） |
| PUT | `/api/v2/trades/{id}` | 修改交易 |
| DELETE | `/api/v2/trades/{id}?version=N` | 刪除交易 |

`/api/v1` 給 App 1.0 用，見最後一節。

---

## 1. 數字的格式

| 種類 | 欄位 | 格式 |
|---|---|---|
| 金額 | `total_cost`、`market_value`、`unrealized_pnl`、`realized_pnl`、`cash_dividends`、`total_return`、`cash` | **整數元** |
| 價格 | `average_cost`、`current_price`、交易的 `price` | 數字，**小數 2 位**（例如 `1100.00`） |
| 股數 | `shares`、`eligible_shares`、`stock_shares` | 整數，單位是**股**（不是張） |

在下面這些情況，欄位會是 `null`：

| 情況 | 是 `null` 的欄位 |
|---|---|
| 還有股數，但沒有收盤價（官方還沒公布、伺服器還沒抓到） | `current_price`、`price_date`、`market_value`、`unrealized_pnl`、`total_return` |
| 已賣光（`shares` 是 0） | `average_cost`。`market_value` 和 `unrealized_pnl` 是 **0**，不需要收盤價 |
| 資料異常（`data_error: true`） | `shares`、`average_cost`、`total_cost`、`market_value`、`unrealized_pnl`、`realized_pnl`、`cash_dividends`、`total_return` |
| 舊持股還沒判斷出市場 | `market` |

---

## 2. 讀取

伺服器只從自己的資料庫讀資料，不會在這時候連證交所或櫃買中心，所以回應很快，官方網站掛掉也不影響。

### 2.1 GET /api/v2/holdings

回傳全部持股，依代號排序。**已賣光但還有交易的持股也會列出**，因為它還有已實現損益和股利。

```json
[
  {
    "id": 1,
    "code": "2330",
    "name": "台積電",
    "market": "TWSE",
    "shares": 1000,
    "average_cost": 1001.43,
    "total_cost": 1001425,
    "current_price": 1100.00,
    "price_date": "2026-11-23",
    "market_value": 1100000,
    "unrealized_pnl": 93708,
    "realized_pnl": 93708,
    "cash_dividends": 10000,
    "total_return": 197416,
    "dividend_status": "COMPLETE",
    "data_error": false
  },
  {
    "id": 3,
    "code": "2454",
    "name": "聯發科",
    "market": "TWSE",
    "shares": 1000,
    "average_cost": 500.71,
    "total_cost": 500712,
    "current_price": null,
    "price_date": null,
    "market_value": null,
    "unrealized_pnl": null,
    "realized_pnl": 0,
    "cash_dividends": 0,
    "total_return": null,
    "dividend_status": "PENDING",
    "data_error": false
  }
]
```

| 欄位 | 說明 |
|---|---|
| `market` | `TWSE`（上市）或 `TPEX`（上櫃） |
| `shares` | 目前股數，含配股 |
| `average_cost` | 平均成本 = 總成本 ÷ 股數，含買進手續費 |
| `total_cost` | 剩餘部位的總成本，含買進手續費 |
| `current_price`、`price_date` | 最近一個有成交的交易日的收盤價，以及那是哪一天 |
| `market_value` | 收盤價 × 股數，元以下捨去 |
| `unrealized_pnl` | 未實現損益：以收盤價全部賣掉時的損益（市值 − 賣出手續費 − 證交稅 − 總成本） |
| `realized_pnl` | 已實現損益：歷次賣出的損益加總 |
| `cash_dividends` | 累計領到的現金股利 |
| `total_return` | 含息總報酬 = `realized_pnl` + `unrealized_pnl` + `cash_dividends` |
| `dividend_status` | 股利資料抓完了沒，見第 6 節 |
| `data_error` | 資料異常，見第 2.4 節 |

### 2.2 GET /api/v2/holdings/{id}

回傳清單的全部欄位，再加上 `trades` 和 `dividends`。

```json
{
  "id": 1,
  "code": "2330",
  "name": "台積電",
  "market": "TWSE",
  "shares": 1000,
  "average_cost": 1001.43,
  "total_cost": 1001425,
  "current_price": 1100.00,
  "price_date": "2026-11-23",
  "market_value": 1100000,
  "unrealized_pnl": 93708,
  "realized_pnl": 93708,
  "cash_dividends": 10000,
  "total_return": 197416,
  "dividend_status": "COMPLETE",
  "data_error": false,
  "trades": [
    { "id": 1, "side": "BUY",  "date": "2026-11-02", "shares": 2000, "price": 1000.00, "date_estimated": false, "version": 0 },
    { "id": 2, "side": "SELL", "date": "2026-11-20", "shares": 1000, "price": 1100.00, "date_estimated": false, "version": 0 }
  ],
  "dividends": [
    { "ex_date": "2026-11-10", "kind": "CASH", "eligible_shares": 2000, "cash": 10000, "stock_shares": 0, "source": "OFFICIAL" }
  ]
}
```

**trades**：依計算順序排列，也就是先比日期，同一天再照輸入順序。

| 欄位 | 說明 |
|---|---|
| `side` | `BUY` 或 `SELL` |
| `date_estimated` | `true` 代表日期是推估的：1.0 轉入的資料和 App 1.0 新增的資料，用建立日期當交易日期。請在畫面上提示使用者改成實際日期 |
| `version` | 修改、刪除時要帶回來的版本號 |

**dividends**：持有期間的每一次除權息，只列出可參與股數大於 0 的。

| 欄位 | 說明 |
|---|---|
| `kind` | `CASH`（除息）、`STOCK`（除權）、`CASH_AND_STOCK`（除權息） |
| `eligible_shares` | 可參與股數：除權息日前一個交易日收盤時的持股 |
| `cash` | 這次領到的現金股利（元）；`source` 是 `UNAVAILABLE` 時是 `null` |
| `stock_shares` | 這次配到的股數；`source` 是 `UNAVAILABLE` 時是 `null` |
| `source` | `OFFICIAL`：官方數字<br>`ESTIMATED`：官方沒有明細，用參考價估算的<br>`UNAVAILABLE`：無法取得，**不計入**股利和配股。請在畫面上提示使用者 |

找不到持股時回 404 `NOT_FOUND`。已賣光的持股照樣查得到。

### 2.3 GET /api/v2/summary

```json
{
  "total_cost": 1502137,
  "market_value": 1100000,
  "unrealized_pnl": 93708,
  "realized_pnl": 103080,
  "cash_dividends": 10000,
  "total_return": 206788,
  "quote_missing": 1,
  "dividend_pending": 1,
  "data_error": 0
}
```

每個欄位都是數字，不會是 `null`。加總的是清單每一檔捨入後的數字，所以 App 自己把清單加起來，會跟總計一樣。

| 欄位 | 怎麼算 |
|---|---|
| `total_cost`、`realized_pnl`、`cash_dividends` | 全部持股加總，含已賣光的 |
| `market_value`、`unrealized_pnl` | **只加有收盤價的持股**。`quote_missing` 大於 0 時，這兩個數字只是部分加總，請在畫面上註明 |
| `total_return` | `realized_pnl` + `unrealized_pnl` + `cash_dividends` |
| `quote_missing` | 還有股數、但沒有收盤價的檔數 |
| `dividend_pending` | `dividend_status` 是 `PENDING` 的檔數 |
| `data_error` | 資料異常的檔數。這些持股完全不計入總計 |

### 2.4 資料異常（`data_error: true`）

每次寫入前，伺服器都會檢查不會超賣。如果讀取時還是算出超賣，通常是有人直接改過資料庫。這時：

- 清單照樣列出這一檔，但第 1 節表格列出的那些數字欄位都是 `null`。
- 單筆查詢仍回 **200**，`trades` 照樣列出，`dividends` 是空陣列。使用者可以找出有問題的交易，修改或刪除之後就恢復正常。

---

## 3. 寫入（一律要帶 `Idempotency-Key`）

### 3.1 Idempotency-Key（冪等鍵）

冪等鍵是 App 幫每一次操作產生的一個唯一識別碼。網路斷掉時 App 不知道伺服器有沒有收到，只要用**同一個鍵**重送，伺服器就不會重複處理。

- 每一個新增、修改、刪除都要帶 header `Idempotency-Key`，建議用 UUID，長度 1～64 個字元。
- 使用者每按一次送出就產生一個**新的**鍵；因為逾時或斷線而重送時，沿用**同一個**鍵和同樣的內容。

| 情況 | 回應 |
|---|---|
| 沒帶鍵、空白，或超過 64 字元 | 400 `IDEMPOTENCY_KEY_REQUIRED` |
| 第一次收到 | 正常處理 |
| 同一個鍵、同樣的內容，上次已經完成 | 不再處理，**原樣回傳上次的狀態碼和 JSON**。裡面的持股狀態是當時的，要最新的狀態請再 GET |
| 同一個鍵、同樣的內容，上次還在處理中 | 409 `REQUEST_IN_PROGRESS`，稍等再重送 |
| 同一個鍵，但 API 或內容不同 | 422 `IDEMPOTENCY_KEY_REUSED` |
| 上次失敗了（驗證錯誤、超賣、版本衝突等） | 失敗時鍵不會保留，修正內容後可以用同一個鍵重送 |

- 「同樣的內容」看的是方法、路徑（含交易 id）和各欄位的值。價格 `1000` 和 `1000.00` 算相同。
- 鍵保留 24 小時。超過 24 小時再送同一個鍵，會被當成新的操作。

### 3.2 POST /api/v2/trades：新增交易

```http
POST /api/v2/trades
Content-Type: application/json
Idempotency-Key: 3f2c9a7e-8b1d-4c5e-9f00-6a1b2c3d4e5f

{"code":"2330","side":"BUY","date":"2026-11-20","shares":1500,"price":1000}
```

| 欄位 | 型別 | 規則 |
|---|---|---|
| `code` | 字串 | 股票代號，例如 `"2330"` |
| `side` | 字串 | `"BUY"` 或 `"SELL"`，要大寫 |
| `date` | 字串 | 成交日 `YYYY-MM-DD`。不能早於 2021-01-01、不能晚於今天（台北時間）、不能是週末。國定假日不會擋 |
| `shares` | 整數 | 股數，1～1,000,000,000。零股直接填股數，例如 `500` |
| `price` | 數字 | 成交價，大於 0，最多兩位小數。要送數字，不能送字串 |

- 代號已經有持股（含已賣光的）：交易加在那一檔下面。
- 代號還沒有持股：只能買進，伺服器會一起建立持股。如果伺服器還不認得這個代號，會去問官方，最多約 10 秒。

成功回 **201**，回應裡有新的交易，和那檔持股的最新狀態（清單欄位，不含 `trades`、`dividends`）：

```json
{
  "trade": { "id": 7, "side": "BUY", "date": "2026-11-20", "shares": 1500, "price": 1000.00, "date_estimated": false, "version": 0 },
  "holding": {
    "id": 5, "code": "2330", "name": "台積電", "market": "TWSE",
    "shares": 1500, "average_cost": 1001.42, "total_cost": 1502137,
    "current_price": 1100.00, "price_date": "2026-11-23",
    "market_value": 1650000, "unrealized_pnl": 140562, "realized_pnl": 0,
    "cash_dividends": 0, "total_return": 140562,
    "dividend_status": "COMPLETE", "data_error": false
  }
}
```

### 3.3 PUT /api/v2/trades/{id}：修改交易

可以改日期、股數、價格。買賣方向和代號不能改，要改請刪掉再重新新增。

```http
PUT /api/v2/trades/7
Content-Type: application/json
Idempotency-Key: 9b0e4d12-5a6f-4e2b-8c3d-1f2e3a4b5c6d

{"date":"2026-11-20","shares":2000,"price":1000,"version":0}
```

- `version` 是使用者畫面上那筆交易的版本號。如果別的裝置已經改過這筆交易，會回 409 `VERSION_CONFLICT`，請重新讀取後再改。
- 推估的日期如果沒改，就不檢查週末；改了日期，就照新增的規則檢查。

成功回 **200**，格式和新增一樣，交易的 `version` 會加 1。

### 3.4 DELETE /api/v2/trades/{id}?version=N：刪除交易

```http
DELETE /api/v2/trades/7?version=1
Idempotency-Key: 0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f
```

成功回 **200**：

```json
{ "deleted_trade_id": 7, "holding_id": 5, "holding": { "id": 5, "code": "2330", "shares": 500, "...": "其他清單欄位" } }
```

刪掉的如果是那檔持股的**最後一筆交易**，持股會一起刪掉，回應的 `holding` 是 `null`。App 可以用 `holding_id` 把那一列從畫面上拿掉：

```json
{ "deleted_trade_id": 7, "holding_id": 5, "holding": null }
```

### 3.5 超賣

新增、修改、刪除之後，伺服器會從頭重算這檔持股。只要**任何一個時間點**的股數小於 0，整個操作都會被拒絕，回 409 `OVERSELL`。這時 `error` 裡會多帶兩個欄位：

```json
{
  "error": {
    "code": "OVERSELL",
    "message": "2026-11-10 的賣出 1,000 股不夠賣：當時只持有 0 股；2026-11-20 那筆 1,000 股的買進日期是推估的，實際買進日可能更早，請先把它改成實際的買進日期",
    "oversold_trade_id": null,
    "estimated_buy_trade_id": 3
  }
}
```

| 欄位 | 說明 |
|---|---|
| `message` | 直接顯示給使用者看，裡面寫了原因和建議的做法 |
| `oversold_trade_id` | 不夠賣的是哪一筆賣出；如果就是這次要新增的那一筆（還沒有 id），是 `null` |
| `estimated_buy_trade_id` | 擋住這筆賣出的那筆推估日期的買進；如果不是這個原因，是 `null` |

`message` 可能提到的原因：
- 同一天的交易照輸入順序計算：先輸入了賣出、後補登買進。
- 有除權資料無法取得，配股可能沒算進去。

---

## 4. 錯誤

所有錯誤的格式都是 `{"error":{"code":"...","message":"..."}}`，只有 `OVERSELL` 多帶兩個欄位。`message` 是給使用者看的中文，App 可以直接顯示；`code` 給程式判斷用。回應不會出現 stack trace。

| HTTP | code | 什麼時候 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 少欄位、型別不對（`shares` 送 1.5、`price` 送字串、`side` 小寫）、日期／股數／價格不符規則、JSON 格式壞掉、`Content-Type` 不是 JSON、DELETE 沒帶 `version` 或 `version` 不是整數 |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 寫入時沒帶 `Idempotency-Key`、是空白，或超過 64 字元 |
| 400 | `BAD_REQUEST` | 網址格式不正確（含 `//`、`;` 等） |
| 404 | `NOT_FOUND` | 找不到持股或交易（可能已經被刪除），或路徑不存在 |
| 404 | `STOCK_NOT_FOUND` | 新增全新代號，但上市、上櫃都查不到 |
| 405 | `METHOD_NOT_ALLOWED` | 路徑存在，但不支援這個 HTTP 方法 |
| 409 | `OVERSELL` | 這次操作會造成超賣（見 3.5） |
| 409 | `VERSION_CONFLICT` | 這筆交易已經被其他裝置修改過，請重新整理 |
| 409 | `HOLDING_BUSY` | 這檔持股正在被其他裝置修改，請稍後再試 |
| 409 | `REQUEST_IN_PROGRESS` | 同一個冪等鍵的第一個請求還在處理中 |
| 409 | `DUPLICATE_CODE` | 極少見：兩支手機同時新增同一個新代號，對方建好後又馬上刪掉。重送一次即可 |
| 422 | `IDEMPOTENCY_KEY_REUSED` | 同一個冪等鍵用在不同的 API，或送了不同的內容 |
| 500 | `INTERNAL_ERROR` | 伺服器發生沒預料到的錯誤 |
| 503 | `QUOTE_UNAVAILABLE` | 新增全新代號時連不上官方網站，暫時無法確認代號 |
| 503 | `MARKET_UNDETERMINED` | 新增全新代號時，上市、上櫃都查到這個代號，暫時無法判斷是哪一個市場 |

---

## 5. 計算規則

股利不存在資料庫裡。每次讀取時，伺服器把交易和除權息事件依日期從頭重算一遍，所以補登、修改或刪除以前的交易之後，數字會自動更新。

### 5.1 計算順序

- 先依日期排序。**同一天**的話，先處理除權息，再**照輸入順序**處理交易。
  - 例如同一天先賣光、再買回，新買的成本不會混進舊的那一輪。
  - 代價是：同一天如果先輸入賣出、後補登買進，會被當成超賣擋下。請先輸入買進，或修改日期。
- 可參與除權息的股數，是**除權息日之前**的持股：
  - 除權息日當天買進：領不到
  - 除權息日當天賣出：領得到
- 同一天除權又除息：現金股利用配股**之前**的股數算。

### 5.2 每一步怎麼算

| 項目 | 算法 |
|---|---|
| 成交金額 | 價格 × 股數，元以下捨去（一筆交易視為一筆委託） |
| 手續費 | 成交金額 × 0.1425%，元以下捨去，最低 20 元（零股也一樣） |
| 證交稅 | 只有賣出才收。一般股票 0.3%，ETF（代號 00 開頭）0.1%，元以下捨去 |
| 買進 | 總成本 += 成交金額 + 手續費 |
| 賣出 | 扣掉的成本 = 總成本 × 賣出股數 ÷ 持有股數<br>已實現損益 += 成交金額 − 手續費 − 證交稅 − 扣掉的成本 |
| 現金股利 | 可參與股數 × 每股現金股利，元以下捨去 |
| 配股 | 可參與股數 × 每千股配股 ÷ 1,000，不足一股捨去。股數增加，總成本不變 |
| 股數歸零 | 總成本設為 0 |

### 5.3 捨入

- 計算過程中，成本和已實現損益保留小數 10 位（`HALF_UP`），不會提前捨入到元。
- 輸出時才捨入，而且只捨入一次：
  - 總成本、已實現損益、未實現損益 → 整數元，`HALF_UP`（四捨五入）
  - 平均成本 → 小數 2 位，`HALF_UP`，直接從還沒捨入的成本算
  - 負數往遠離 0 的方向捨入，例如 −0.5 → −1
- `total_return` 用的是**捨入後**的三個數字相加，所以畫面上的已實現、未實現、股利加起來，剛好等於含息總報酬。
- 因為每個數字是各自捨入的，畫面上有時會差 1 元，例如「市值 − 賣出費用 − 顯示的總成本」不一定剛好等於顯示的未實現損益。

---

## 6. 股利資料狀態 `dividend_status`

| 值 | 意思 | 建議的畫面 |
|---|---|---|
| `COMPLETE` | 持有期間的除權息資料都抓過了 | 正常顯示 |
| `PENDING` | 還有年份或明細沒抓完 | 顯示「股利資料更新中，稍後重新整理」 |

- **股利資料每天更新一次**：交易日早上 07:30 抓今年的資料。官方當天稍晚才公布的除權息，隔天才會出現。
- 補登了更早年份的交易時，伺服器會在背景補抓那一年，這段期間狀態是 `PENDING`。
- `dividend_status` 是整檔持股的狀態。除此之外，只要 `dividends` 裡有 `source: "UNAVAILABLE"` 的事件，也請另外提示使用者，並在那一筆明細上標示「無法取得」。
- 上市股票如果官方沒有提供除息明細，每股現金股利改用除權息列表上的「權值＋息值」，這個數字只有 6 位小數。持股很多時，現金股利可能比官方實際發放的少 1 元。

---

## 7. 刻意不處理的規則

| 實際的規則 | 本系統的做法 | 原因 |
|---|---|---|
| 現股當沖的證交稅減半（0.15%） | 一律照一般稅率 | 系統不知道使用者是不是當沖，照一般稅率比較保守 |
| 配股不足一股的部分，公司通常按面額折發現金 | 不足一股直接捨去，不發現金 | 題目明訂 |
| 現金股利入帳時會扣二代健保補充保費、匯費 | 不扣 | 題目定義的含息總報酬不扣這些 |
| 股票分割、減資 | 不調整股數 | 題目明訂這一版不處理；官方的除權息資料也不包含這兩種 |
| 零股手續費（部分券商最低 1 元） | 最低 20 元 | 沿用 1.0 的規則 |
| 被動式債券 ETF（例如 00679B）賣出免證交稅：現行法律停徵到 2026-12-31；延長到 2036-12-31 的修正草案，2026-11-25 查詢時立法院還沒三讀 | 00 開頭一律收 0.1% | 維持跟 1.0 相同的規則，`/api/v1` 升級前後的回應才會一致，而且延長案還沒定案。結果是債券 ETF 會被多扣 0.1% 證交稅，已實現、未實現損益會比實際少 |
| 一筆委託分好幾次成交 | 一筆交易視為一筆委託 | 使用者記帳時通常只記一筆 |
| 國定假日不能交易 | 不擋 | 假日表只有當年度；不影響「交易日期是否早於除權息日」的判斷 |

### 7.1 股票分割、減資時，使用者會看到什麼

系統不知道有分割或減資，所以股數不會變，但收盤價會依新的股本改變：

- **分割**（例如 1 股拆成 4 股，股價變成約 1/4）：股數沒有增加，市值和未實現損益會看起來大跌。
- **減資**（股數減少、股價上升）：股數沒有減少，市值和未實現損益會看起來大漲。退還的股款也不會算進現金股利。

這兩種情況的數字都和實際不符，請以券商的對帳單為準。這是這一版的已知限制，以後會再處理。

---

## 8. /api/v1（App 1.0）

**維持 1.0 的規格**：路徑、欄位、型別、錯誤格式都不變，App 1.0 不用改版就能繼續使用。`/api/v1` 不用帶 `Idempotency-Key`。

升級到 2.0 之後，因為持股改成用交易計算，App 1.0 會看到這些差異：

| 情況 | App 1.0 看到的 |
|---|---|
| 有零股，例如 1,500 股 | `lots` 是 1（捨去到整張），名稱後面加上「【請更新 App 2.0：含零股 500 股】」 |
| 只剩零股，例如 300 股 | `lots` 是 0，名稱一樣加註 |
| `buy_price` | 只有 1 筆整張買進，而且股數沒變過時，是那筆的成交價（跟 1.0 一樣）；其他情況是平均成本（含手續費） |
| `pnl`、市值、總計 | 用全部股數計算，含零股。`pnl` 仍然是以收盤價全部賣掉的未實現損益，不含股利和已實現損益 |
| 已在 2.0 全部賣光 | 清單和總計都不會出現；用 id 查詢、修改、刪除都回 404 `NOT_FOUND` |
| 有多筆交易、有零股，或配過股的持股 | 修改、刪除回 409 `UPGRADE_REQUIRED`，請使用者改用網頁或 App 2.0 |
| 新增一個已賣光的代號 | 加在原本的持股下面（回傳原本的 id），交易日期是今天（推估） |
| 別的裝置正在修改同一檔 | 409 `HOLDING_BUSY` |
| 資料異常 | 清單和總計不列出；單筆查詢回 409 `DATA_ERROR` |

`return_pct` = 未實現損益 ÷ 總成本 × 100，用捨入到元之後的數字計算。

股利、已實現損益、含息總報酬只有網頁和 `/api/v2` 看得到。
