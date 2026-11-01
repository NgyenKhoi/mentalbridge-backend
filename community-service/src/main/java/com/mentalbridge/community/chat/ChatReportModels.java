package com.mentalbridge.community.chat;

import java.util.UUID;

final class ChatReportModels {
    private ChatReportModels() {}
    record Input(UUID messageId, String reasonCode, String details) { @Override public String toString() { return "ChatReport[REDACTED]"; } }
    record Result(UUID caseId, String state) {}
}
