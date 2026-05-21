package dev.obscuralink.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.obscuralink.model.GroupRecord;
import dev.obscuralink.util.JsonSupport;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GroupService {
    private static final Type GROUPS_TYPE = new TypeToken<Map<String, GroupRecord>>() {
    }.getType();

    private final Path groupsFile;
    private final Gson gson = JsonSupport.prettyGson();

    public GroupService(Path root) {
        this.groupsFile = root.resolve("groups.json");
    }

    public synchronized GroupRecord create(String name, List<String> members) throws IOException {
        if (members.isEmpty()) {
            throw new IOException("Group must contain at least one member");
        }
        Map<String, GroupRecord> groups = readGroups();
        GroupRecord group = new GroupRecord(name, List.copyOf(members), Instant.now());
        groups.put(normalize(name), group);
        writeGroups(groups);
        return group;
    }

    public synchronized Optional<GroupRecord> find(String name) throws IOException {
        return Optional.ofNullable(readGroups().get(normalize(name)));
    }

    public synchronized List<GroupRecord> list() throws IOException {
        return new ArrayList<>(readGroups().values());
    }

    public synchronized void delete(String name) throws IOException {
        Map<String, GroupRecord> groups = readGroups();
        groups.remove(normalize(name));
        writeGroups(groups);
    }

    private Map<String, GroupRecord> readGroups() throws IOException {
        if (!Files.exists(groupsFile)) {
            return new LinkedHashMap<>();
        }
        Map<String, GroupRecord> groups = gson.fromJson(Files.readString(groupsFile, StandardCharsets.UTF_8), GROUPS_TYPE);
        return groups == null ? new LinkedHashMap<>() : new LinkedHashMap<>(groups);
    }

    private void writeGroups(Map<String, GroupRecord> groups) throws IOException {
        Files.createDirectories(groupsFile.getParent());
        Files.writeString(groupsFile, gson.toJson(groups, GROUPS_TYPE), StandardCharsets.UTF_8);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
