package com.mentalbridge.consultation.video;

import java.net.URI;
import java.time.Duration;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.consultation.video")
public class LiveKitProperties {

	private boolean enabled;
	private String url = "";
	private String apiKey = "";
	private String apiSecret = "";
	private Duration tokenTtl = Duration.ofMinutes(2);

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }
	public String getApiKey() { return apiKey; }
	public void setApiKey(String apiKey) { this.apiKey = apiKey; }
	public String getApiSecret() { return apiSecret; }
	public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
	public Duration getTokenTtl() { return tokenTtl; }
	public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }

	@PostConstruct
	void validateEnabledConfiguration() {
		if (enabled && !configured()) throw new IllegalStateException("Enabled video requires valid LiveKit Cloud configuration");
	}

	public boolean configured() {
		if (url == null || apiKey == null || apiSecret == null) return false;
		try {
			var origin = URI.create(url);
			return "wss".equals(origin.getScheme()) && origin.getHost() != null
					&& origin.getHost().endsWith(".livekit.cloud") && origin.getUserInfo() == null
					&& origin.getPort() == -1 && origin.getQuery() == null && origin.getFragment() == null
					&& (origin.getPath().isEmpty() || origin.getPath().equals("/"))
					&& !apiKey.isBlank() && !apiSecret.isBlank() && tokenTtl != null
					&& tokenTtl.compareTo(Duration.ofSeconds(30)) >= 0
					&& tokenTtl.compareTo(Duration.ofMinutes(5)) <= 0;
		}
		catch (IllegalArgumentException exception) { return false; }
	}
}
