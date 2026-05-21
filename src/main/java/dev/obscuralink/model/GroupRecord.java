package dev.obscuralink.model;

import java.time.Instant;
import java.util.List;

public record GroupRecord(String name, List<String> members, Instant createdAt) {
}
