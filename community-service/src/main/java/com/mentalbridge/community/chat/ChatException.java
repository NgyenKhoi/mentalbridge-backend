package com.mentalbridge.community.chat;

final class ChatException extends RuntimeException {
    final int status;
    final String code;
    private ChatException(int status, String code) { super(code); this.status = status; this.code = code; }
    static ChatException invalid() { return new ChatException(400, "INVALID_REQUEST"); }
    static ChatException target() { return new ChatException(404, "TARGET_UNAVAILABLE"); }
    static ChatException version() { return new ChatException(409, "VERSION_CONFLICT"); }
    static ChatException conflict() { return new ChatException(409, "IDEMPOTENCY_CONFLICT"); }
    static ChatException notAllowed() { return new ChatException(409, "REQUEST_NOT_ALLOWED"); }
    static ChatException limited() { return new ChatException(429, "RATE_LIMITED"); }
    static ChatException unavailable() { return new ChatException(503, "DEPENDENCY_UNAVAILABLE"); }
}
