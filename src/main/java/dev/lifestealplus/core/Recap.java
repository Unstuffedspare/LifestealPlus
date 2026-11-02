package dev.lifestealplus.core;

import java.time.Duration;
import java.util.List;

/** What a returning player missed: the newest {@code items}, out of {@code total}, from {@code away}. */
public record Recap(Duration away, List<NewsItem> items, int total) {
}
