package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SupportPlanReminderPropertiesTests {
	private static final String TOKEN = "synthetic-content-to-care-reminder-token-32-plus";

	@Test
	void emptyTokenDisablesAndOnlyTheExactTokenAuthorizes() {
		assertThat(new SupportPlanReminderProperties(null).authorizes("")).isFalse();
		assertThat(new SupportPlanReminderProperties("").authorizes(null)).isFalse();
		var properties = new SupportPlanReminderProperties(TOKEN);
		assertThat(properties.authorizes(TOKEN)).isTrue();
		assertThat(properties.authorizes(TOKEN + "x")).isFalse();
		assertThat(properties.authorizes(null)).isFalse();
		assertThat(properties.toString()).doesNotContain(TOKEN);
	}

	@Test
	void tokenMustDifferFromOtherPurposeCredentials() {
		new SupportPlanReminderProperties("").requireDistinctCredential("", "");
		new SupportPlanReminderProperties(TOKEN).requireDistinctCredential("", "another-credential");
		assertThatThrownBy(() -> new SupportPlanReminderProperties(TOKEN).requireDistinctCredential("", TOKEN))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(TOKEN);
	}
}
