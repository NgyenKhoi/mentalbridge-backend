package com.mentalbridge.consultation.billing;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.consultation.billing")
public class BillingProperties {

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

	public boolean checkoutEnabled() {
		return "FAKE".equals(mode) || momoReady();
	}

	public boolean fake() {
		return "FAKE".equals(mode);
	}

	public boolean momoReady() {
		return "MOMO".equals(mode) && productionApproved && present(momoPartnerCode)
				&& present(momoAccessKey) && present(momoSecretKey) && present(momoSignatureKeyVersion)
				&& safeHttps(momoBaseUrl) && safeHttps(momoIpnUrl) && safeHttps(momoRedirectUrl);
	}

	private boolean present(String value) { return value != null && !value.isBlank(); }

	private boolean safeHttps(String value) {
		try {
			var uri = URI.create(value);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
		}
		catch (IllegalArgumentException ignored) {
			return false;
		}
	}
}
