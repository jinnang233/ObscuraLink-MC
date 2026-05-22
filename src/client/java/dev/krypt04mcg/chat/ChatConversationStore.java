package dev.krypt04mcg.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChatConversationStore {
    private static final int MAX_MESSAGES = 300;
    private final List<Entry> entries = new ArrayList<>();

    public synchronized void incoming(String player, String message) {
        record(player, message, false);
    }

    public synchronized void outgoing(String player, String message) {
        record(player, message, true);
    }

    public synchronized List<Entry> messagesFor(String player) {
        return entries.stream()
                .filter(entry -> entry.player().equalsIgnoreCase(player))
                .toList();
    }

    public synchronized List<String> peers() {
        Set<String> peers = new LinkedHashSet<>();
        entries.stream()
                .sorted(Comparator.comparing(Entry::createdAt))
                .forEach(entry -> peers.add(entry.player()));
        return List.copyOf(peers);
    }

    private void record(String player, String message, boolean outgoing) {
        if (player == null || player.isBlank() || message == null || message.isBlank()) {
            return;
        }
        entries.add(new Entry(player.trim(), message, outgoing, Instant.now()));
        while (entries.size() > MAX_MESSAGES) {
            entries.removeFirst();
        }
    }

    public record Entry(String player, String message, boolean outgoing, Instant createdAt) {
    }
}
