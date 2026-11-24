-- 第 3 題：持股記錄市場（上市／上櫃）和股票名稱
-- 注意：這個檔案一旦 commit 就不能再改；之後資料表有任何變動，一律新增 V3__、V4__…

-- 兩個欄位都允許 NULL：第 2 題時已經存進去的持股沒有這兩項資料，升級後仍要能正常讀取
-- 市場存 TWSE（上市）或 TPEX（上櫃），剛好 4 個字
ALTER TABLE holding ADD COLUMN market VARCHAR(4);
-- 名稱只在新增時從官方取得一次；50 字足以容納 ETF 等較長的簡稱
ALTER TABLE holding ADD COLUMN name VARCHAR(50);

-- 資料庫層也擋掉 TWSE、TPEX 以外的值（NULL 不受 CHECK 影響）；約束自己取名，日後才能用名字調整
ALTER TABLE holding ADD CONSTRAINT ck_holding_market CHECK (market IN ('TWSE', 'TPEX'));
