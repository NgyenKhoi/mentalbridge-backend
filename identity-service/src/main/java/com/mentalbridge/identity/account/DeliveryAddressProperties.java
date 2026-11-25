package com.mentalbridge.identity.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.identity.delivery-address")
public record DeliveryAddressProperties(String serviceToken) {
}
