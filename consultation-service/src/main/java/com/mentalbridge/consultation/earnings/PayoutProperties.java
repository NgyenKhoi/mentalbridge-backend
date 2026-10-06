package com.mentalbridge.consultation.earnings;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.consultation.payout")
public class PayoutProperties {

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

	public String getMode() { return mode; }
	public void setMode(String mode) { this.mode = mode; }
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

	boolean momoReady() {
		return mode.equals("MOMO") && productionApproved && present(momoPartnerCode) && present(momoAccessKey)
				&& present(momoSecretKey) && present(momoStoreId) && present(momoPublicKey)
				&& https(momoBaseUrl) && https(momoIpnUrl);
	}

	private boolean present(String value) { return value != null && !value.isBlank(); }
	private boolean https(String value) { return present(value) && value.startsWith("https://"); }
}
