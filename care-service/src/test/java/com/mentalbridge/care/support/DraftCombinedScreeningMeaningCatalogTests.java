package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

class DraftCombinedScreeningMeaningCatalogTests {

	private final DraftCombinedScreeningMeaningCatalog catalog = new DraftCombinedScreeningMeaningCatalog();

	@ParameterizedTest
	@MethodSource("pairs")
	void eachPairKeepsSeparateLevelsExistingRoutingAndReviewableNeutralCopy(ScreeningLevel phq9, ScreeningLevel gad7)
			throws Exception {
		var meaning = catalog.resolve(phq9, gad7);
		assertThat(meaning.code()).isEqualTo("PHQ9_" + phq9 + "_GAD7_" + gad7).hasSizeLessThanOrEqualTo(64);
		assertThat(meaning.phq9()).isEqualTo(phq9);
		assertThat(meaning.gad7()).isEqualTo(gad7);
		assertThat(meaning.supportTier()).isEqualTo(
				new SupportRoutingPolicy().resolve(phq9, SafetyStatus.NEGATIVE_SAFETY_SCREEN, gad7).tier());
		for (var copy : new String[] { meaning.text(), meaning.rationale() }) {
			assertThat(copy).isNotBlank().hasSizeLessThanOrEqualTo(1000)
					.doesNotContain("\uFFFD", "Ã", "Ä", "cháº", "tổng điểm", "mắc bệnh", "nguy cơ cao");
			assertThat(Normalizer.isNormalized(copy, Normalizer.Form.NFC)).isTrue();
		}
		assertThat(meaning.text().trim().split("\\s+").length).isBetween(9, 22);
		assertThat(meaning.text()).doesNotContain("Trong 14 ngày qua", "trong 14 ngày qua");
		if (meaning.supportTier() == SupportTier.SELF_GUIDED_SUPPORT) {
			assertThat(meaning.rationale()).contains("có thể");
		}
		else {
			assertThat(meaning.rationale()).contains("chuyên gia");
		}
		var review = Files.readString(Path.of("..", "docs", "policies", "combined-screening-meaning-draft-v1.md"));
		assertThat(review).contains("| " + meaning.code() + " | " + phq9 + " | " + gad7 + " | "
				+ meaning.text() + " | " + meaning.rationale() + " | " + meaning.supportTier() + " |");
	}

	@Test
	void twentyBaseVariantsHaveDistinctKeysTextsAndRationales() {
		var codes = new HashSet<String>();
		var texts = new HashSet<String>();
		var rationales = new HashSet<String>();
		pairs().forEach(pair -> {
			var values = pair.get();
			var meaning = catalog.resolve((ScreeningLevel) values[0], (ScreeningLevel) values[1]);
			codes.add(meaning.code());
			texts.add(meaning.text());
			rationales.add(meaning.rationale());
		});
		assertThat(codes).hasSize(20);
		assertThat(texts).hasSize(20);
		assertThat(rationales).hasSize(20);
		assertThat(DraftCombinedScreeningMeaningCatalog.CONTENT_VERSION).endsWith("-draft.2");
	}

	@Test
	void invalidGad7BandOrAbsentLevelNeverReceivesMeaning() {
		assertThatThrownBy(() -> catalog.resolve(ScreeningLevel.MILD, ScreeningLevel.MODERATELY_SEVERE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> catalog.resolve(null, ScreeningLevel.MINIMAL)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> catalog.resolve(ScreeningLevel.MINIMAL, null)).isInstanceOf(IllegalArgumentException.class);
	}

	static Stream<Arguments> pairs() {
		return Arrays.stream(ScreeningLevel.values()).flatMap(phq9 -> Arrays.stream(ScreeningLevel.values())
				.filter(gad7 -> gad7 != ScreeningLevel.MODERATELY_SEVERE).map(gad7 -> Arguments.of(phq9, gad7)));
	}
}
