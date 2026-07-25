package com.adkhambek.reader.nfc.card.bean;

import java.util.Arrays;
import java.util.Objects;

import com.adkhambek.reader.nfc.card.Currency;

public record CardApp(
        byte[] aid,
        String label,
        String pan,
        String panSequence,
        String cardholder,
        String country,
        Currency currency,
        String effectiveDate,
        String expiryDate,
        String appVersion
) {
    // A record's generated equals/hashCode/toString compare and render the
    // byte[] aid by identity, which breaks the value-equality contract a record
    // implies (two reads of the same card would be unequal, and toString shows
    // "[B@1a2b3c"). Override to treat the AID by contents.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CardApp other)) return false;
        return Arrays.equals(aid, other.aid)
                && Objects.equals(label, other.label)
                && Objects.equals(pan, other.pan)
                && Objects.equals(panSequence, other.panSequence)
                && Objects.equals(cardholder, other.cardholder)
                && Objects.equals(country, other.country)
                && currency == other.currency
                && Objects.equals(effectiveDate, other.effectiveDate)
                && Objects.equals(expiryDate, other.expiryDate)
                && Objects.equals(appVersion, other.appVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(aid), label, pan, panSequence,
                cardholder, country, currency, effectiveDate, expiryDate, appVersion);
    }

    @Override
    public String toString() {
        return "CardApp[aid=" + Arrays.toString(aid) + ", label=" + label
                + ", pan=" + pan + ", panSequence=" + panSequence
                + ", cardholder=" + cardholder + ", country=" + country
                + ", currency=" + currency + ", effectiveDate=" + effectiveDate
                + ", expiryDate=" + expiryDate + ", appVersion=" + appVersion + "]";
    }
}
