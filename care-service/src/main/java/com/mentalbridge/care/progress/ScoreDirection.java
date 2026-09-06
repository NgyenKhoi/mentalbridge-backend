package com.mentalbridge.care.progress;

public enum ScoreDirection {
	INCREASED,
	DECREASED,
	UNCHANGED;

	static ScoreDirection fromDelta(int delta) {
		if (delta > 0) return INCREASED;
		if (delta < 0) return DECREASED;
		return UNCHANGED;
	}
}
