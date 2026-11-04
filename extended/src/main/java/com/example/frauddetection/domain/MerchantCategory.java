package com.example.frauddetection.domain;

/**
 * Merchant category of a transaction. Categories that are easy to resell or cash out
 * (electronics, digital goods, gambling, money transfers) are historically higher risk.
 */
public enum MerchantCategory {
    // TODO(customize): use your own categories and map real merchant codes to them. This changes the features, so retrain
    GROCERY(false),
    RESTAURANT(false),
    FUEL(false),
    RETAIL(false),
    ELECTRONICS(true),
    TRAVEL(false),
    DIGITAL_GOODS(true),
    ENTERTAINMENT(false),
    GAMBLING(true),
    MONEY_TRANSFER(true);

    private final boolean highRisk;

    MerchantCategory(boolean highRisk) {
        this.highRisk = highRisk;
    }

    public boolean isHighRisk() {
        return highRisk;
    }
}
