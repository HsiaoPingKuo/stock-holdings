# 行情 API 測試資料

這些檔案是從官方網站實際取得的回應，只給單元測試使用。單元測試不連網（題目規定），改用這些檔案模擬官方回應。

## 檔案來源

| 檔案 | 情境 | 來源網址 | 取得時間（台北） |
|---|---|---|---|
| `twse-stock-day-2330.json` | 證交所查到（台積電） | `https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date=20261123&stockNo=2330&response=json` | 2026-11-23 16:49 |
| `twse-stock-day-9999-not-found.json` | 證交所查不到（不存在的代號） | `https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date=20261124&stockNo=9999&response=json` | 2026-11-24 12:50 |
| `twse-stock-day-6488-not-found.json` | 證交所查不到（上櫃股票查證交所） | `https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date=20261124&stockNo=6488&response=json` | 2026-11-24 12:50 |
| `tpex-trading-stock-6488.json` | 櫃買查到（環球晶） | `https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/23&response=json` | 2026-11-23 16:49 |
| `tpex-trading-stock-2330-not-found.json` | 櫃買查不到（上市股票查櫃買） | `https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock?code=2330&date=2026/11/24&response=json` | 2026-11-24 12:50 |
| `twse-mi-index-20261123.json` | 證交所全市場收盤行情（批次） | `https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=20261123&type=ALLBUT0999&response=json` | 2026-11-23 16:49 |
| `tpex-otc-20261123.json` | 櫃買全市場收盤行情（批次） | `https://www.tpex.org.tw/www/zh-tw/afterTrading/otc?date=2026/11/23&type=EW&response=json` | 2026-11-23 16:49 |

三個「查不到」的檔案，是在瀏覽器開啟網址後，照畫面上的內容存成 JSON，內容與官方回應相同。

## 格式重點

- **證交所查不到時**，回應只有 `stat`（「很抱歉，沒有符合條件的資料!」）和 `total`，**沒有** `data`、`fields`。這個月還沒有交易日時，回的也是同一個訊息，所以要再查上一個月，兩個月都沒有資料才算查不到。
- **櫃買查不到時**，`stat` 仍然是 `"ok"`，但 `tables[0].data` 是空陣列，`name` 是空字串。要用資料列有沒有資料來判斷，不能只看 `stat`。
- `stat` 的大小寫不同：證交所是 `OK`，櫃買是 `ok`。
- 證交所的股票名稱要從 `title` 切出來，名稱後面有很多空白，要去掉；櫃買在最外層就有 `name`。
- 櫃買部分欄位名稱的前後有空白（例如 `"日 期"`、`"收盤 "`），比對前要先去掉空白。
- 資料列的日期是民國年（例如 `115/11/23`）；價格有千分位逗號（例如 `2,030.00`）。
