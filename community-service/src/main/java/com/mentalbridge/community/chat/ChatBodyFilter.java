package com.mentalbridge.community.chat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-90)
class ChatBodyFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    ChatBodyFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/community/chat/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        if (!Set.of("POST", "PUT").contains(request.getMethod())) { chain.doFilter(request, response); return; }
        var body = request.getInputStream().readNBytes(32769);
        try {
            if (body.length > 32768) throw ChatException.invalid();
            try (var parser = mapper.getFactory().createParser(body)) {
                parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                validate(request.getRequestURI(), mapper.readTree(parser));
                if (parser.nextToken() != null) throw ChatException.invalid();
            }
        } catch (Exception exception) {
            response.setStatus(400); response.setContentType("application/problem+json");
            mapper.writeValue(response.getOutputStream(), ChatProblems.body(400, "INVALID_REQUEST",
                    CommunityChatController.correlation(request.getHeader("X-Correlation-Id"))));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous request body"); }
                };
            }
        }, response);
    }
    private void validate(String path, JsonNode body) {
        if (body == null || !body.isObject()) throw ChatException.invalid();
        if (path.equals("/api/v1/community/chat/privacy")) {
            exact(body, Set.of("directMessagePolicy", "presenceVisible", "invisible", "typingVisible", "readReceiptsVisible"));
            if (!body.get("directMessagePolicy").isTextual()) throw ChatException.invalid();
            for (var name : Set.of("presenceVisible", "invisible", "typingVisible", "readReceiptsVisible"))
                if (!body.get(name).isBoolean()) throw ChatException.invalid();
        } else if (path.equals("/api/v1/community/chat/direct-conversations")) {
            exact(body, Set.of("recipientCommunityProfileId")); uuid(body, "recipientCommunityProfileId");
        } else if (path.matches("/api/v1/community/chat/requests/[^/]+/decision")) {
            exact(body, Set.of("decision")); if (!body.get("decision").isTextual()) throw ChatException.invalid();
        } else if (path.matches("/api/v1/community/chat/conversations/[^/]+/messages")) {
            exact(body, Set.of("clientMessageId", "content")); uuid(body, "clientMessageId");
            if (!body.get("content").isTextual()) throw ChatException.invalid();
        } else if (path.matches("/api/v1/community/chat/conversations/[^/]+/read-cursor")) {
            exact(body, Set.of("lastReadMessageId")); uuid(body, "lastReadMessageId");
        }
    }
    private void exact(JsonNode value, Set<String> fields) {
        if (value.size() != fields.size() || fields.stream().anyMatch(name -> !value.has(name))) throw ChatException.invalid();
    }
    private void uuid(JsonNode value, String name) {
        if (!value.get(name).isTextual()) throw ChatException.invalid();
        ChatModels.uuid(value.get(name).textValue());
    }
}
