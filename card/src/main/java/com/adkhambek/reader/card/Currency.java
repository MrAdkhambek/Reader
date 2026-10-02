/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

/**
 * Currencies this reader maps from an EMV ISO-4217 numeric code
 * (tag 9F42 application currency, falling back to 5F2A transaction currency).
 *
 * <p>An unrecognised code reads back as {@code null} on {@code CardApp} rather
 * than a placeholder constant — the card said something we don't have a name
 * for, which is different from the card saying nothing.
 */
public enum Currency {
	USD, EUR, GBP, JPY, CHF, CAD, AUD, NZD,
	CNY, HKD, TWD, KRW, SGD, INR, IDR, THB, MYR, PHP, VND,
	RUB, TRY, AED, SAR, ILS, ZAR, BRL, MXN,
	UZS, KZT, KGS, AZN, GEL, AMD, BYN, UAH,
}
