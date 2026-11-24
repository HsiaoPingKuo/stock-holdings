# SECURITY

相依套件的已知弱點（CVE）查詢紀錄。打 v1.0.0 前查一次；交卷前用當下的相依套件再查一次並更新本文件。

## 查詢結果（v1.0.0 前）

這是 v1.0.0 前的查詢結果。交卷前會用同樣的方法再查一次並更新本文件。

| 項目 | 內容 |
|---|---|
| 查詢日期 | 2026-11-24 23:07（台北時間） |
| 查詢的程式版本 | `feature/q5-app-api-v1` 分支 `ef6a1eb`（Part A 五題完成，合併進 master 之前） |
| 查詢範圍 | `./mvnw dependency:list` 列出的全部 122 個相依套件，含間接相依：compile 76 個、runtime 11 個（會打包進執行檔），test 35 個（只在跑測試時使用） |
| 查詢方式 | [OSV.dev](https://osv.dev/) 批次查詢 API（`https://api.osv.dev/v1/querybatch`）。OSV 是 Google 維護的開源弱點資料庫，彙整 GitHub Advisory Database、NVD 等來源；用「Maven 套件名稱 + 確切版本」比對 |
| 查詢方式的驗證 | 用同樣的請求格式查已知有弱點的 `org.apache.logging.log4j:log4j-core` 2.14.1，查到 8 筆（含 Log4Shell `GHSA-jfh8-c2jp-5v3q`），確認不是查詢寫錯才查不到 |
| 結果 | **沒有已知弱點**（122 個套件都沒有查到） |

### 主要套件版本

| 套件 | 版本 |
|---|---|
| Spring Boot | 4.2.0 |
| Spring Framework | 7.1.0 |
| Tomcat（內嵌） | 11.0.27 |
| Hibernate ORM | 7.4.15.Final |
| Hibernate Validator | 9.1.6.Final |
| Jackson（`tools.jackson`） | 3.1.8（annotations 2.21） |
| Thymeleaf | 3.1.7.RELEASE |
| Flyway | 12.11.0 |
| H2 | 2.5.252 |
| HikariCP | 7.1.0 |
| Logback | 1.6.6 |

## 查詢範圍以外的部分

- **JDK 本身**：查詢的是專案的相依套件，不含執行時用的 JDK。請使用仍有安全性更新的 JDK 17 以上版本。
- **Maven 外掛**（例如 `spring-boot-maven-plugin`）：只在建置時使用，不會打包進執行檔，沒有查詢。

## 相關的設定

- **H2 Console 沒有開啟**：沒有設定 `spring.h2.console.enabled`，預設是關閉。過去 H2 的嚴重弱點主要是透過網頁版 Console 利用。
- **H2 只用檔案模式**：資料庫存在 `./data/`，只有本程式自己開啟，沒有開放網路連線埠。
- **密碼不寫在 repo 裡**：資料庫密碼用環境變數 `DB_PASSWORD`，開發時預設是空字串。

## 怎麼重新查詢

在 Git Bash（或 Mac／Linux）執行，需要網路：

```bash
./mvnw -q dependency:list -DoutputFile=deps.txt
grep -E '^\s+[a-z]' deps.txt | sed -E 's/ -- module.*//; s/^\s+//' | sort -u \
  | awk -F: 'BEGIN{printf "{\"queries\":["} {if(NR>1)printf ","; printf "{\"package\":{\"name\":\"%s:%s\",\"ecosystem\":\"Maven\"},\"version\":\"%s\"}", $1, $2, $(NF-1)} END{print "]}"}' > osv-query.json
curl -s -X POST https://api.osv.dev/v1/querybatch -H "Content-Type: application/json" --data-binary @osv-query.json -o osv-result.json
grep -c '"vulns"' osv-result.json   # 0 代表沒有已知弱點；大於 0 時打開 osv-result.json 看是哪個套件
```

版本號取倒數第 2 欄：dependency:list 的最後一欄一定是範圍（compile、runtime、test），不論有沒有 classifier，倒數第 2 欄都是版本號。

查到弱點時，每一筆要寫：會不會被打到、為什麼、怎麼處理（升版、覆寫版本，或暫不處理的理由）。

`deps.txt`、`osv-query.json`、`osv-result.json` 是查詢用的暫存檔，不用 commit。
