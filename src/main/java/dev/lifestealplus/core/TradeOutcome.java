package dev.lifestealplus.core;

/**
 * Hearts handed from one player to another.
 *
 * @param tax hearts lost on the way (trading.tax-percent)
 * @param linked the two accounts share an address (alt detection), so admins hear about it
 */
public record TradeOutcome(HeartChange sent, HeartChange received, int tax, boolean linked) {
}
