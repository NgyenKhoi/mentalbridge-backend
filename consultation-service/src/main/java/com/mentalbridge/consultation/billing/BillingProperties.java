package com.mentalbridge.consultation.billing;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@ConfigurationProperties("mentalbridge.consultation.billing")
public class BillingProperties implements EnvironmentAware {

	private String mode = "DISABLED";
	private boolean productionApproved;
	private Duration checkoutTtl = Duration.ofMinutes(15);
	private String momoPartnerCode = "";
	private String momoAccessKey = "";
	private String momoSecretKey = "";
	private String momoSignatureKeyVersion = "v1";
	private String momoBaseUrl = "https://test-payment.momo.vn";
	private String momoIpnUrl = "";
	private String momoRedirectUrl = "";
	private String payosClientId = "";
	private String payosApiKey = "";
	private String payosChecksumKey = "";
	private String payosBaseUrl = "https://api-merchant.payos.vn";
	private String payosWebhookUrl = "";
	private String payosReturnUrl = "";
	private String payosCancelUrl = "";
	private Environment environment;

	public String getMode() { return mode; }
	public void setMode(String mode) { this.mode = mode; }
	public boolean isProductionApproved() { return productionApproved; }
	public void setProductionApproved(boolean productionApproved) { this.productionApproved = productionApproved; }
	public Duration getCheckoutTtl() { return checkoutTtl; }
	public void setCheckoutTtl(Duration checkoutTtl) { this.checkoutTtl = checkoutTtl; }
	public String getMomoPartnerCode() { return momoPartnerCode; }
	public void setMomoPartnerCode(String momoPartnerCode) { this.momoPartnerCode = momoPartnerCode; }
	public String getMomoAccessKey() { return momoAccessKey; }
	public void setMomoAccessKey(String momoAccessKey) { this.momoAccessKey = momoAccessKey; }
	public String getMomoSecretKey() { return momoSecretKey; }
	public void setMomoSecretKey(String momoSecretKey) { this.momoSecretKey = momoSecretKey; }
	public String getMomoSignatureKeyVersion() { return momoSignatureKeyVersion; }
	public void setMomoSignatureKeyVersion(String momoSignatureKeyVersion) { this.momoSignatureKeyVersion = momoSignatureKeyVersion; }
	public String getMomoBaseUrl() { return momoBaseUrl; }
	public void setMomoBaseUrl(String momoBaseUrl) { this.momoBaseUrl = momoBaseUrl; }
	public String getMomoIpnUrl() { return momoIpnUrl; }
	public void setMomoIpnUrl(String momoIpnUrl) { this.momoIpnUrl = momoIpnUrl; }
	public String getMomoRedirectUrl() { return momoRedirectUrl; }
	public void setMomoRedirectUrl(String momoRedirectUrl) { this.momoRedirectUrl = momoRedirectUrl; }
	public String getPayosClientId() { return payosClientId; }
	public void setPayosClientId(String value) { payosClientId = value; }
	public String getPayosApiKey() { return payosApiKey; }
	public void setPayosApiKey(String value) { payosApiKey = value; }
	public String getPayosChecksumKey() { return payosChecksumKey; }
	public void setPayosChecksumKey(String value) { payosChecksumKey = value; }
	public String getPayosBaseUrl() { return payosBaseUrl; }
	public void setPayosBaseUrl(String value) { payosBaseUrl = value; }
	public String getPayosWebhookUrl() { return payosWebhookUrl; }
	public void setPayosWebhookUrl(String value) { payosWebhookUrl = value; }
	public String getPayosReturnUrl() { return payosReturnUrl; }
	public void setPayosReturnUrl(String value) { payosReturnUrl = value; }
	public String getPayosCancelUrl() { return payosCancelUrl; }
	public void setPayosCancelUrl(String value) { payosCancelUrl = value; }
	@Override
	public void setEnvironment(Environment value) { environment = value; }

	public boolean checkoutEnabled() {
		return fake() || momoReady() || payosReady();
	}

	public boolean fake() {
		return "FAKE".equals(mode);
	}

	public boolean sandboxCheckoutAllowed() {
		if (fake()) return true;
		if (payosReady()) return true;
		if (!momoReady()) return false;
		var origin = URI.create(momoBaseUrl);
		return "test-payment.momo.vn".equalsIgnoreCase(origin.getHost())
				&& (origin.getPort() == -1 || origin.getPort() == 443);
	}

	public boolean momoReady() {
		return "MOMO".equals(mode) && productionApproved && present(momoPartnerCode)
				&& present(momoAccessKey) && present(momoSecretKey) && present(momoSignatureKeyVersion)
				&& safeHttps(momoBaseUrl) && safeHttps(momoIpnUrl) && safeHttps(momoRedirectUrl);
	}

	public boolean payosReady() {
		return "PAYOS".equals(mode) && payosVerificationReady()
				&& approvedOrigin(payosBaseUrl, "api-merchant.payos.vn")
				&& approvedStagingUrl(payosWebhookUrl) && approvedStagingUrl(payosReturnUrl)
				&& approvedStagingUrl(payosCancelUrl);
	}

	public boolean payosVerificationReady() {
		return environment != null && environment.acceptsProfiles(Profiles.of("staging"))
				&& !environment.acceptsProfiles(Profiles.of("prod", "production"))
				&& present(payosClientId) && present(payosApiKey) && present(payosChecksumKey);
	}

	private boolean approvedStagingUrl(String value) {
		return approvedOrigin(value, "staging.mentalbridge.online");
	}

	private boolean approvedOrigin(String value, String host) {
		if (!safeHttps(value)) return false;
		var uri = URI.create(value);
		return host.equalsIgnoreCase(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443)
				&& uri.getFragment() == null && (!host.equals("api-merchant.payos.vn")
				|| ((uri.getPath().isEmpty() || "/".equals(uri.getPath())) && uri.getQuery() == null));
	}

	private boolean present(String value) { return value != null && !value.isBlank(); }

	private boolean safeHttps(String value) {
		try {
			var uri = URI.create(value);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
		}
		catch (IllegalArgumentException | NullPointerException ignored) {
			return false;
		}
	}
}
