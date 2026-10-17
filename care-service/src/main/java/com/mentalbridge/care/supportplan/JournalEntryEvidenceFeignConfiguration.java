package com.mentalbridge.care.supportplan;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.JournalEntryEvidenceProperties;

import feign.Request;
import feign.Retryer;

public class JournalEntryEvidenceFeignConfiguration {
	@Bean
	Request.Options journalEntryEvidenceRequestOptions(JournalEntryEvidenceProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer journalEntryEvidenceRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
