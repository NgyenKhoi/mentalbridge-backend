package com.mentalbridge.care;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.mentalbridge.care.configuration.AssessmentProperties;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CareServiceApplicationTests extends CareTestProperties {

	@Autowired
	private AssessmentProperties assessmentProperties;

	@Test
	void contextLoads() {
		assertThat(assessmentProperties.toString()).contains("idempotencyHmacKey=[REDACTED]")
				.doesNotContain("test-only-idempotency-hmac-key-at-least-32-characters");
	}

}
