package io.hsiaopingkuo.stockholdings.calc;

/** 交易方向（第 6 題）。同一天重播時不分買賣，依輸入順序（id）處理（見 HoldingReplayCalculator）。 */
public enum TradeSide {
	BUY, SELL
}
