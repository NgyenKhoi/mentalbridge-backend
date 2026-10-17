package com.mentalbridge.care.supportguide;

import java.util.List;
import java.util.Map;

import com.mentalbridge.care.assessment.ScreeningLevel;

final class SupportGuideResourceMap {

	static final String VERSION = "mb-support-guide-resource-map-v1";
	static final int MAX_RESOURCES = 2;

	private static final String DEPRESSION = "DEPRESSIVE_SYMPTOMS";
	private static final String ANXIETY = "ANXIETY_SYMPTOMS";
	private static final Candidate SLOW_BREATHING = new Candidate("00000000-0000-4000-8000-000000000201", "0", ANXIETY);
	private static final Candidate GROUNDING = new Candidate("00000000-0000-4000-8000-000000000202", "0", ANXIETY);
	private static final Candidate DEPRESSION_SIGNS = new Candidate("00000000-0000-4000-8000-000000000205", "0", DEPRESSION);
	private static final Candidate ANXIETY_SIGNS = new Candidate("00000000-0000-4000-8000-000000000206", "0", ANXIETY);
	private static final Candidate SMALL_ACTIVITY = new Candidate("00000000-0000-4000-8000-000000000208", "0", DEPRESSION);
	private static final Candidate WORRY_TIME = new Candidate("00000000-0000-4000-8000-000000000211", "0", ANXIETY);

	private static final List<Candidate> MAINTENANCE = List.of(SMALL_ACTIVITY, SLOW_BREATHING, GROUNDING);
	private static final List<Candidate> MILD_ANXIETY = List.of(SLOW_BREATHING, WORRY_TIME, GROUNDING);
	private static final List<Candidate> MILD_MOOD = List.of(SMALL_ACTIVITY, DEPRESSION_SIGNS);
	private static final List<Candidate> ANXIETY_PROFESSIONAL = List.of(ANXIETY_SIGNS, SLOW_BREATHING);
	private static final List<Candidate> MOOD_PROFESSIONAL = List.of(DEPRESSION_SIGNS, SMALL_ACTIVITY);
	private static final List<Candidate> BOTH_MOOD_FIRST = List.of(DEPRESSION_SIGNS, ANXIETY_SIGNS, SMALL_ACTIVITY,
			SLOW_BREATHING);
	private static final List<Candidate> BOTH_ANXIETY_FIRST = List.of(ANXIETY_SIGNS, DEPRESSION_SIGNS, SLOW_BREATHING,
			SMALL_ACTIVITY);

	static final Map<String, List<Candidate>> ROWS = Map.ofEntries(
			Map.entry("PHQ9_MINIMAL_GAD7_MINIMAL", MAINTENANCE),
			Map.entry("PHQ9_MINIMAL_GAD7_MILD", MILD_ANXIETY),
			Map.entry("PHQ9_MINIMAL_GAD7_MODERATE", ANXIETY_PROFESSIONAL),
			Map.entry("PHQ9_MINIMAL_GAD7_SEVERE", ANXIETY_PROFESSIONAL),
			Map.entry("PHQ9_MILD_GAD7_MINIMAL", MILD_MOOD),
			Map.entry("PHQ9_MILD_GAD7_MILD", MAINTENANCE),
			Map.entry("PHQ9_MILD_GAD7_MODERATE", ANXIETY_PROFESSIONAL),
			Map.entry("PHQ9_MILD_GAD7_SEVERE", ANXIETY_PROFESSIONAL),
			Map.entry("PHQ9_MODERATE_GAD7_MINIMAL", MOOD_PROFESSIONAL),
			Map.entry("PHQ9_MODERATE_GAD7_MILD", MOOD_PROFESSIONAL),
			Map.entry("PHQ9_MODERATE_GAD7_MODERATE", BOTH_MOOD_FIRST),
			Map.entry("PHQ9_MODERATE_GAD7_SEVERE", BOTH_ANXIETY_FIRST),
			Map.entry("PHQ9_MODERATELY_SEVERE_GAD7_MINIMAL", MOOD_PROFESSIONAL),
			Map.entry("PHQ9_MODERATELY_SEVERE_GAD7_MILD", MOOD_PROFESSIONAL),
			Map.entry("PHQ9_MODERATELY_SEVERE_GAD7_MODERATE", BOTH_MOOD_FIRST),
			Map.entry("PHQ9_MODERATELY_SEVERE_GAD7_SEVERE", BOTH_ANXIETY_FIRST),
			Map.entry("PHQ9_SEVERE_GAD7_MINIMAL", MOOD_PROFESSIONAL),
			Map.entry("PHQ9_SEVERE_GAD7_MILD", MOOD_PROFESSIONAL),
			Map.entry("PHQ9_SEVERE_GAD7_MODERATE", BOTH_MOOD_FIRST),
			Map.entry("PHQ9_SEVERE_GAD7_SEVERE", BOTH_MOOD_FIRST));

	private SupportGuideResourceMap() {
	}

	static List<Candidate> candidates(ScreeningLevel phq9, ScreeningLevel gad7) {
		var row = ROWS.get("PHQ9_" + phq9.name() + "_GAD7_" + gad7.name());
		if (row == null) {
			throw new IllegalStateException("No reviewed Support Guide resource row for this screening pair");
		}
		return row;
	}

	record Candidate(String resourceId, String contentVersion, String domain) {
		String key() {
			return resourceId + ":" + contentVersion;
		}
	}
}
