package com.mentalbridge.consultation.earnings;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@ConfigurationProperties("mentalbridge.consultation.payout")
public class PayoutProperties implements EnvironmentAware {

	private String mode = "FAKE";
	private Duration settlementHold = Duration.ofDays(7);
	private long minimumWithdrawalVnd = 100_000;
	private String encryptionKey = "";
	private String encryptionKeyVersion = "v1";
	private String momoPartnerCode = "";
	private String momoAccessKey = "";
	private String momoSecretKey = "";
	private boolean productionApproved;
	private String momoBaseUrl = "https://test-payment.momo.vn";
	private String momoStoreId = "";
	private String momoPublicKey = "";
	private String momoIpnUrl = "";
	private String payosClientId = "";
	private String payosApiKey = "";
	private String payosChecksumKey = "";
	private String payosBaseUrl = "https://api-merchant.payos.vn";
	private Environment environment;

	public String getMode() { return mode; }
	public void setMode(String mode) {
		if (mode == null || !Set.of("DISABLED", "FAKE", "MOMO", "PAYOS").contains(mode)) {
			throw new IllegalArgumentException("Payout mode must be DISABLED, FAKE, MOMO or PAYOS");
		}
		this.mode = mode;
	}
	public Duration getSettlementHold() { return settlementHold; }
	public void setSettlementHold(Duration settlementHold) { this.settlementHold = settlementHold; }
	public long getMinimumWithdrawalVnd() { return minimumWithdrawalVnd; }
	public void setMinimumWithdrawalVnd(long minimumWithdrawalVnd) { this.minimumWithdrawalVnd = minimumWithdrawalVnd; }
	public String getEncryptionKey() { return encryptionKey; }
	public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
	public String getEncryptionKeyVersion() { return encryptionKeyVersion; }
	public void setEncryptionKeyVersion(String encryptionKeyVersion) { this.encryptionKeyVersion = encryptionKeyVersion; }
	public String getMomoPartnerCode() { return momoPartnerCode; }
	public void setMomoPartnerCode(String momoPartnerCode) { this.momoPartnerCode = momoPartnerCode; }
	public String getMomoAccessKey() { return momoAccessKey; }
	public void setMomoAccessKey(String momoAccessKey) { this.momoAccessKey = momoAccessKey; }
	public String getMomoSecretKey() { return momoSecretKey; }
	public void setMomoSecretKey(String momoSecretKey) { this.momoSecretKey = momoSecretKey; }
	public boolean isProductionApproved() { return productionApproved; }
	public void setProductionApproved(boolean productionApproved) { this.productionApproved = productionApproved; }
	public String getMomoBaseUrl() { return momoBaseUrl; }
	public void setMomoBaseUrl(String momoBaseUrl) { this.momoBaseUrl = momoBaseUrl; }
	public String getMomoStoreId() { return momoStoreId; }
	public void setMomoStoreId(String momoStoreId) { this.momoStoreId = momoStoreId; }
	public String getMomoPublicKey() { return momoPublicKey; }
	public void setMomoPublicKey(String momoPublicKey) { this.momoPublicKey = momoPublicKey; }
	public String getMomoIpnUrl() { return momoIpnUrl; }
	public void setMomoIpnUrl(String momoIpnUrl) { this.momoIpnUrl = momoIpnUrl; }
	public String getPayosClientId() { return payosClientId; }
	public void setPayosClientId(String value) { payosClientId = value; }
	public String getPayosApiKey() { return payosApiKey; }
	public void setPayosApiKey(String value) { payosApiKey = value; }
	public String getPayosChecksumKey() { return payosChecksumKey; }
	public void setPayosChecksumKey(String value) { payosChecksumKey = value; }
	public String getPayosBaseUrl() { return payosBaseUrl; }
	public void setPayosBaseUrl(String value) { payosBaseUrl = present(value) ? value : "https://api-merchant.payos.vn"; }
	@Override
	public void setEnvironment(Environment value) { environment = value; }

	boolean ready() { return submitReady(mode); }

	boolean submitReady(String provider) {
		return !"DISABLED".equals(mode) && verificationReady(provider);
	}

	boolean verificationReady(String provider) {
		if (provider == null) return false;
		return switch (provider) {
			case "FAKE" -> true;
			case "MOMO" -> momoVerificationReady();
			case "PAYOS" -> payosVerificationReady();
			default -> false;
		};
	}

	boolean payosReady() { return "PAYOS".equals(mode) && submitReady("PAYOS"); }

	boolean payosVerificationReady() {
		return environment != null && environment.acceptsProfiles(Profiles.of("staging"))
				&& !environment.acceptsProfiles(Profiles.of("prod", "production"))
				&& present(payosClientId) && present(payosApiKey) && present(payosChecksumKey)
				&& approvedPayosOrigin() && encryptionReady();
	}

	boolean encryptionReady() {
		try {
			return present(encryptionKeyVersion) && Base64.getDecoder().decode(encryptionKey).length == 32;
		}
		catch (IllegalArgumentException | NullPointerException ignored) { return false; }
	}

	private boolean approvedPayosOrigin() {
		try {
			var uri = URI.create(payosBaseUrl);
			return "https".equalsIgnoreCase(uri.getScheme()) && "api-merchant.payos.vn".equalsIgnoreCase(uri.getHost())
					&& uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
					&& (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
					&& uri.getQuery() == null && uri.getFragment() == null;
		}
		catch (IllegalArgumentException | NullPointerException ignored) { return false; }
	}

	boolean momoReady() {
		return mode.equals("MOMO") && submitReady("MOMO");
	}

	boolean momoVerificationReady() {
		return productionApproved && present(momoPartnerCode) && present(momoAccessKey)
				&& present(momoSecretKey) && present(momoStoreId) && present(momoPublicKey)
				&& https(momoBaseUrl) && https(momoIpnUrl);
	}

	private boolean present(String value) { return value != null && !value.isBlank(); }
	private boolean https(String value) { return present(value) && value.startsWith("https://"); }
}
