package dev.zay.staff;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Flags, mutes, frozen players and the admin log, saved to plugins/ZayStaff/data.yml. */
final class Data {
    static final class Flag {
        int id;
        UUID uuid;
        String name;
        int level;
        String reason;
        String category; // automod, hacking, esp, manual
        long time;
        String by;
    }

    private final File file;
    final List<Flag> flags = new ArrayList<>();
    final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    final Set<UUID> frozen = new HashSet<>();
    final LinkedList<String> logs = new LinkedList<>();
    /** uuid -> {until-millis, name, reason} */
    final Map<UUID, String[]> tempbans = new HashMap<>();

    static final double[] SCORE = {0, 1, 1.5, 2, 5, 10};
    static final long WINDOW = 30L * 24 * 60 * 60 * 1000;

    static double scoreOf(int level) {
        return SCORE[Math.max(1, Math.min(5, level))];
    }

    /** Ban score: every flag from the last 30 days adds its score. 10 = automatic ban. */
    synchronized double score(UUID id) {
        long now = System.currentTimeMillis();
        double t = 0;
        for (Flag f : flags) if (f.uuid.equals(id) && now - f.time <= WINDOW) t += scoreOf(f.level);
        return t;
    }
    int nextId = 1;

    Data(File file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next", 1);
        ConfigurationSection fs = y.getConfigurationSection("flags");
        if (fs != null) {
            for (String k : fs.getKeys(false)) {
                ConfigurationSection s = fs.getConfigurationSection(k);
                if (s == null) continue;
                try {
                    Flag f = new Flag();
                    f.id = Integer.parseInt(k);
                    f.uuid = UUID.fromString(Objects.requireNonNull(s.getString("uuid")));
                    f.name = s.getString("name", "?");
                    f.level = s.getInt("level", 1);
                    f.reason = s.getString("reason", "");
                    f.category = s.getString("category", "manual");
                    f.time = s.getLong("time");
                    f.by = s.getString("by", "?");
                    flags.add(f);
                } catch (Exception ignored) {
                }
            }
        }
        ConfigurationSection ms = y.getConfigurationSection("mutes");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                try {
                    mutes.put(UUID.fromString(k), ms.getLong(k));
                } catch (Exception ignored) {
                }
            }
        }
        for (String s : y.getStringList("frozen")) {
            try {
                frozen.add(UUID.fromString(s));
            } catch (Exception ignored) {
            }
        }
        logs.addAll(y.getStringList("logs"));
        ConfigurationSection ts = y.getConfigurationSection("tempbans");
        if (ts != null) {
            for (String k : ts.getKeys(false)) {
                try {
                    tempbans.put(UUID.fromString(k), new String[]{String.valueOf(ts.getLong(k + ".until")),
                            ts.getString(k + ".name", "?"), ts.getString(k + ".reason", "")});
                } catch (Exception ignored) {
                }
            }
        }
    }

    synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next", nextId);
        for (Flag f : flags) {
            String p = "flags." + f.id + ".";
            y.set(p + "uuid", f.uuid.toString());
            y.set(p + "name", f.name);
            y.set(p + "level", f.level);
            y.set(p + "reason", f.reason);
            y.set(p + "category", f.category);
            y.set(p + "time", f.time);
            y.set(p + "by", f.by);
        }
        for (Map.Entry<UUID, Long> e : mutes.entrySet()) y.set("mutes." + e.getKey(), e.getValue());
        List<String> fz = new ArrayList<>();
        for (UUID u : frozen) fz.add(u.toString());
        y.set("frozen", fz);
        y.set("logs", new ArrayList<>(logs));
        for (Map.Entry<UUID, String[]> e : tempbans.entrySet()) {
            y.set("tempbans." + e.getKey() + ".until", Long.parseLong(e.getValue()[0]));
            y.set("tempbans." + e.getKey() + ".name", e.getValue()[1]);
            y.set("tempbans." + e.getKey() + ".reason", e.getValue()[2]);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    synchronized void addLog(String line) {
        logs.add(line);
        while (logs.size() > 500) logs.removeFirst();
        save();
    }

    synchronized void removeAllFor(UUID id) {
        flags.removeIf(f -> f.uuid.equals(id));
        mutes.remove(id);
        frozen.remove(id);
        tempbans.remove(id);
        save();
    }
}
