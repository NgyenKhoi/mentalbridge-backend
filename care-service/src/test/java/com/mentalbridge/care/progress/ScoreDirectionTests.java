package com.mentalbridge.care.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreDirectionTests {

	@ParameterizedTest
	@CsvSource({ "1,INCREASED", "-1,DECREASED", "0,UNCHANGED" })
	void derivesOnlyTheArithmeticDirection(int delta, ScoreDirection expected) {
		assertThat(ScoreDirection.fromDelta(delta)).isEqualTo(expected);
	}
}
