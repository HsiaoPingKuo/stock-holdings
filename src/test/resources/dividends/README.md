# 除權息 API 測試資料

這些檔案是從官方網站實際取得的回應，只給單元測試使用。單元測試不連網（題目規定），改用這些檔案模擬官方回應。

取得時間：2026-11-25 03:08～03:09（台北），內容未修改。

## 檔案來源

| 檔案 | 用途 | 來源網址 |
|---|---|---|
| `twse-twt49u-20250601-20250731.json` | 上市列表，題目例子的期間（2330 息、2884 權息、3311 閎暉 權、6423 億而得-創 權），共 673 列 | `https://www.twse.com.tw/rwd/zh/exRight/TWT49U?startDate=20250601&endDate=20250731&response=json` |
| `twse-twt49u-20210101-20210131.json` | 上市列表，系統允許的最早範圍（2021-01），共 15 列 | `https://www.twse.com.tw/rwd/zh/exRight/TWT49U?startDate=20210101&endDate=20210131&response=json` |
| `twse-twt49u-20250127-20250131-no-data.json` | 上市列表沒有資料（2025 春節） | `https://www.twse.com.tw/rwd/zh/exRight/TWT49U?startDate=20250127&endDate=20250131&response=json` |
| `twse-twt49u-detail-2884-20250722.json` | 上市明細，權息（現金 1.2 元、每千股配 10 股） | `https://www.twse.com.tw/rwd/zh/exRight/TWT49UDetail?STK_NO=2884&T1=20250722&response=json` |
| `twse-twt49u-detail-2330-20250612.json` | 上市明細，只除息（4.50002 元） | `https://www.twse.com.tw/rwd/zh/exRight/TWT49UDetail?STK_NO=2330&T1=20250612&response=json` |
| `twse-twt49u-detail-9999-not-found.json` | 上市明細查不到（不存在的代號） | `https://www.twse.com.tw/rwd/zh/exRight/TWT49UDetail?STK_NO=9999&T1=20250722&response=json` |
| `tpex-ex-daily-q-20250601-20250731.json` | 上櫃列表，題目例子的期間（8069 除息 4.99013930），共 481 列 | `https://www.tpex.org.tw/www/zh-tw/bulletin/exDailyQ?startDate=2025/06/01&endDate=2025/07/31&response=json` |
| `tpex-ex-daily-q-20250127-20250131-no-data.json` | 上櫃列表沒有資料（2025 春節） | `https://www.tpex.org.tw/www/zh-tw/bulletin/exDailyQ?startDate=2025/01/27&endDate=2025/01/31&response=json` |

## 格式重點

- **上市列表**：`stat` 是 `OK`，資料在最外層 `data`，日期是 `114年06月12日`；數字有千分位（`1,065.00`）。沒有每股現金股利、每千股配股的欄位，只有「權值+息值」（6 位小數）。「詳細資料」是明細 API 的參數（`2884,20250722`）。
- **上市列表沒有資料**：只有 `stat`（「很抱歉，沒有符合條件的資料!」），沒有 `data`。
- **上市明細**：`stat` 是小寫 `ok`；代號和名稱尾端有空白（`"2884  "`）；數字帶單位（`1.2 元／股`，斜線是全形）和千分位（`18,000,000 股`）；有的欄位是空字串；欄位名稱 `" c. 原股東認購"` 開頭有空白。查不到時 HTTP 200、`stat` 是「無相關資料」。
- **上櫃列表**：`stat` 是 `ok`，資料在 `tables[0].data`，日期是 `114/07/24`；名稱尾端有空白；有「現金股利」「每仟股無償配股」欄位（8 位小數，例如 `99.99999623`）；數字沒有千分位。最外層 `date` 是查詢範圍（`20250601~20250731`），不是資料日期。沒有資料時 `stat` 仍是 `ok`，`data` 是 0 列。
