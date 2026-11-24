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
└── controller/HomeController.java  首頁（標題＋每秒更新的台北時間）
src/main/resources/
├── application.properties          埠號、資料庫設定
└── templates/index.html            首頁畫面
```

主機時區不是台北，所以「現在時間」一律從 `ClockConfig` 的 `Asia/Taipei` Clock 取得。

**為什麼選 H2 檔案模式**：clone 下來就能啟動，不用另外安裝資料庫；用檔案模式，重開伺服器資料還在。
