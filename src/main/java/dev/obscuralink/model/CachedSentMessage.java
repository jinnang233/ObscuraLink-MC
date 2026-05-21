package dev.obscuralink.model;

import java.time.Instant;
import java.util.List;

public record CachedSentMessage(String messageId, String receiver, Instant createdAt, List<String> fragments) {
}
