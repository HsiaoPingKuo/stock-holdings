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
  - 股票代號：必填，只能是英文字母或數字，最多 10 個字；前後空白會自動去掉，英文字母會轉成大寫（例如 `00631l` 存成 `00631L`）
  - 張數：1～999 的整數（1 張 = 1,000 股）
  - 買進價：每股價格，大於 0，最多兩位小數
- **同一個股票代號只能有一筆**：重複新增會顯示「2330 已在持股中」，資料不儲存。
- **修改**：按該筆的「修改」，只能改張數和買進價，股票代號不能改。
- **刪除**：按「刪除」，確認後刪除。

輸入有誤時，錯誤訊息會顯示在該欄位旁邊，資料不會儲存。

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
├── config/ClockConfig.java         全系統共用的台北時區 Clock
├── controller/                     網頁：首頁、持股清單（新增／修改／刪除）
├── dto/                            表單欄位與檢查規則
├── entity/Holding.java             持股資料
├── exception/                      重複代號、找不到持股
├── repository/                     資料庫存取
└── service/HoldingService.java     持股的業務規則（代號正規化、同代號只能一筆）
src/main/resources/
├── application.properties          埠號、資料庫設定
├── messages.properties             表單型別錯誤的中文訊息
├── db/migration/                   Flyway 資料表（V1__create_holding.sql）
└── templates/                      畫面（首頁、持股清單、修改持股）
```

主機時區不是台北，所以「現在時間」一律從 `ClockConfig` 的 `Asia/Taipei` Clock 取得。

**為什麼選 H2 檔案模式**：clone 下來就能啟動，不用另外安裝資料庫；用檔案模式，重開伺服器資料還在。
資料表一律由 Flyway 建立（`src/main/resources/db/migration/`），已經發布的 migration 不會再修改，資料表有變動時一律新增下一個版本號的檔案。
