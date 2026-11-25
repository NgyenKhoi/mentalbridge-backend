package com.mentalbridge.consultation.appointment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatSessionSettlementScheduler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionSettlementScheduler.class);

	private final ChatSessionSettlementService settlement;

	public ChatSessionSettlementScheduler(ChatSessionSettlementService settlement) {
		this.settlement = settlement;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.consultation.chat-settlement-interval:PT15S}")
	public void settleDueSessions() {
		for (var appointmentId : settlement.dueEndIds()) {
			try {
				settlement.end(appointmentId);
			}
			catch (RuntimeException exception) {
				LOGGER.error("Chat session end failed for appointmentId={}", appointmentId, exception);
			}
		}
		for (var appointmentId : settlement.dueSettlementIds()) {
			try {
				settlement.settle(appointmentId);
			}
			catch (RuntimeException exception) {
				LOGGER.error("Chat session settlement failed for appointmentId={}", appointmentId, exception);
			}
		}
	}
}
