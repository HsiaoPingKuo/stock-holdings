-- 第 3 題：行情快取。網頁和 App 只讀這張表，官方 API 只由背景排程呼叫（打開清單不會連官方）
-- 注意：這個檔案一旦 commit 就不能再改；之後資料表有任何變動，一律新增 V4__、V5__…

CREATE TABLE quote (
    -- 一個代號一列；批次查詢會一次寫入整個市場
    code              VARCHAR(10)    NOT NULL,
    name              VARCHAR(50),
    market            VARCHAR(4)     NOT NULL,
    -- 最近一個「有成交」那天的收盤價和日期；從來沒有成交紀錄時兩個都是 NULL（清單顯示「未取得」）
    close_price       DECIMAL(12, 2),
    close_date        DATE,
    -- 官方已公布、這檔有出現的最近一個交易日（不論當天有沒有成交）。
    -- 和 close_date 分開記：當天沒成交（官方顯示 --）時，清單才知道要提示「今天無成交，顯示前一個有成交日」
    latest_trade_date DATE           NOT NULL,
    -- 由程式用台北時區的 Clock 寫入
    updated_at        TIMESTAMP      NOT NULL,
    CONSTRAINT pk_quote        PRIMARY KEY (code),
    CONSTRAINT ck_quote_market CHECK (market IN ('TWSE', 'TPEX'))
);
