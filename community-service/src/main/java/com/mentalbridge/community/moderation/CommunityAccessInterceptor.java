package com.mentalbridge.community.moderation;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mentalbridge.community.shared.CommunityApiException;

@Component
class CommunityAccessInterceptor implements HandlerInterceptor {

	private final CommunityModerationService moderation;

	CommunityAccessInterceptor(CommunityModerationService moderation) {
		this.moderation = moderation;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getRequestURI().startsWith("/api/v1/community/admin/")) return true;
		if (request.getUserPrincipal() instanceof Authentication authentication
				&& authentication.getPrincipal() instanceof Jwt jwt) {
			try {
				if (moderation.isRestricted(UUID.fromString(jwt.getSubject()))) {
					throw CommunityApiException.communityAccessUnavailable();
				}
			}
			catch (IllegalArgumentException exception) {
				throw CommunityApiException.invalidSubject();
			}
		}
		return true;
	}
}
