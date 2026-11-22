package dev.zay.staff;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Chat, command and sign filter. Level 1 = mild profanity, level 2 = severe / slurs. */
final class Automod implements Listener {

    private static final String SYMBOLS = "@$!|+";
    private static final Pattern IP = Pattern.compile("(?i)\\b(\\d{1,3}\\s?[.,]\\s?){3}\\d{1,3}\\b");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b[a-z0-9-]{2,}\\s?(\\.|\\(dot\\)|\\sdot\\s)\\s?(com|net|org|gg|io|xyz|me|co|club|fun|host|ru|tk|gs|lol|cc)\\b");
    private static final String[] SUFFIX = {"s", "es", "ed", "ing", "er", "ers", "y", "in"};

    private final StaffPlugin pl;
    private Set<String> severeContains, severeExact, mildContains, mildExact, safe;
    private List<String> phrases;
    private final Map<UUID, ArrayDeque<Long>> chatTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> spamFlagCd = new ConcurrentHashMap<>();
    private Set<String> filteredCommands;
    private final Map<UUID, ArrayDeque<Long>> cmdTimes = new ConcurrentHashMap<>();
    private final Map<UUID, String[]> lastMsg = new ConcurrentHashMap<>(); // {text, count, time}

    Automod(StaffPlugin pl) {
        this.pl = pl;
        FileConfiguration c = pl.getConfig();
        severeContains = lower(c.getStringList("automod.severe-contains"));
        severeExact = lower(c.getStringList("automod.severe-exact"));
        mildContains = lower(c.getStringList("automod.mild-contains"));
        mildExact = lower(c.getStringList("automod.mild-exact"));
        safe = lower(c.getStringList("automod.safe-words"));
        phrases = new ArrayList<>(lower(c.getStringList("automod.phrases")));
        filteredCommands = lower(c.getStringList("automod.filtered-commands"));
    }

    private static Set<String> lower(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String x : l) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }

    // ------------------------------------------------------------------ detection

    private static char leet(char ch) {
        return switch (ch) {
            case '0' -> 'o';
            case '1', '!', '|' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7', '+' -> 't';
            default -> ch;
        };
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || SYMBOLS.indexOf(c) >= 0;
    }

    /** Turns "Sh!t", "f.u.c.k", "f0ck" into clean lowercase tokens. */
    static List<String> tokens(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (SYMBOLS.indexOf(ch) >= 0) {
                boolean prev = i > 0 && isWord(s.charAt(i - 1));
                boolean next = i + 1 < s.length() && isWord(s.charAt(i + 1));
                sb.append(prev && next ? leet(ch) : ' ');
            } else if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            } else {
                sb.append(' ');
            }
        }
        List<String> raws = new ArrayList<>();
        for (String t : sb.toString().trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            boolean hasLetter = false;
            for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) hasLetter = true;
            if (hasLetter) {
                StringBuilder m = new StringBuilder();
                for (int i = 0; i < t.length(); i++) m.append(leet(t.charAt(i)));
                t = m.toString();
            }
            t = t.replaceAll("[^a-z0-9]", "");
            if (!t.isEmpty()) raws.add(t);
        }
        // merge runs of 3+ single letters ("f u c k")
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < raws.size()) {
            int j = i;
            StringBuilder run = new StringBuilder();
            while (j < raws.size() && raws.get(j).length() == 1 && Character.isLetter(raws.get(j).charAt(0))) {
                run.append(raws.get(j));
                j++;
            }
            if (j - i >= 3) {
                out.add(run.toString());
                i = j;
            } else {
                out.add(raws.get(i));
                i++;
            }
        }
        return out;
    }

    private static List<String> variants(String t) {
        List<String> v = new ArrayList<>();
        v.add(t);
        v.add(t.replaceAll("(.)\\1{2,}", "$1"));
        v.add(t.replaceAll("(.)\\1{2,}", "$1$1"));
        v.add(t.replace('o', 'u')); // f0ck -> fock -> fuck
        v.add(t.replaceAll("(.)\\1+", "$1").replace('o', 'u')); // fuuck, shiit
        v.add(t.replaceAll("(.)\\1+", "$1"));
        return v;
    }

    private static boolean matches(String v, Set<String> exact, Set<String> contains) {
        if (exact.contains(v)) return true;
        for (String suf : SUFFIX) {
            if (v.length() > suf.length() && v.endsWith(suf) && exact.contains(v.substring(0, v.length() - suf.length()))) {
                return true;
            }
        }
        for (String w : contains) if (v.contains(w)) return true;
        return false;
    }

    /** 0 = clean, 1 = mild, 2 = severe. */
    int level(String raw) {
        List<String> toks = tokens(raw);
        int lv = 0;
        for (String t : toks) {
            if (safe.contains(t)) continue;
            for (String v : variants(t)) {
                boolean squashed = !v.equals(t) && v.length() < t.length();
                // fully squashed forms ("ass" -> "as") are only compared against 4+ letter stems
                Set<String> se = squashed ? Set.of() : severeExact;
                Set<String> me = squashed ? Set.of() : mildExact;
                if (matches(v, se, severeContains)) return 2;
                if (matches(v, me, mildContains)) lv = 1;
            }
        }
        String joined = String.join(" ", toks);
        for (String ph : phrases) if (joined.contains(ph)) return 2;
        return lv;
    }

    private boolean isAdvert(String msg) {
        if (!pl.getConfig().getBoolean("automod.advertising.enabled", true)) return false;
        String low = msg.toLowerCase(Locale.ROOT);
        for (String ok : pl.getConfig().getStringList("automod.advertising.allowed")) {
            if (low.contains(ok.toLowerCase(Locale.ROOT))) return false;
        }
        return IP.matcher(msg).find() || DOMAIN.matcher(msg).find();
    }

    // ------------------------------------------------------------------ reactions

    private void prohibited(Player p) {
        p.sendActionBar(Component.text("That content is prohibited", NamedTextColor.RED));
    }

    private boolean exempt(Player p) {
        return !pl.getConfig().getBoolean("automod.enabled", true) || p.hasPermission("zaystaff.bypass");
    }

    private void flagLater(Player p, int level, String reason) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        Bukkit.getScheduler().runTask(pl, () -> {
            prohibited(p);
            pl.addFlag(id, name, level, reason, "automod", "Automod");
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        Long until = pl.data.mutes.get(id);
        if (until != null) {
            if (now < until) {
                // shadow mute: only the muted player sees their own message
                e.viewers().removeIf(a -> !a.equals(p));
                return;
            }
            pl.data.mutes.remove(id);
        }
        if (exempt(p)) return;
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());

        if (pl.getConfig().getBoolean("automod.spam.enabled", true)) {
            ArrayDeque<Long> q = chatTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
            int max = pl.getConfig().getInt("automod.spam.max-messages", 5);
            long win = pl.getConfig().getInt("automod.spam.per-seconds", 5) * 1000L;
            boolean spam;
            synchronized (q) {
                q.addLast(now);
                while (!q.isEmpty() && now - q.peekFirst() > win) q.removeFirst();
                spam = q.size() > max;
            }
            if (spam) {
                e.setCancelled(true);
                Long last = spamFlagCd.get(id);
                if (last == null || now - last > 60_000L) {
                    spamFlagCd.put(id, now);
                    flagLater(p, 1, "Chat spam");
                } else {
                    Bukkit.getScheduler().runTask(pl, () -> prohibited(p));
                }
                return;
            }
        }

        // repeated message (3rd identical message in 30s) and shouting (mostly capitals)
        String key = msg.toLowerCase(Locale.ROOT).trim();
        String[] lm = lastMsg.get(id);
        if (lm != null && lm[0].equals(key) && now - Long.parseLong(lm[2]) < 30_000L) {
            int c = Integer.parseInt(lm[1]) + 1;
            lastMsg.put(id, new String[]{key, String.valueOf(c), String.valueOf(now)});
            if (c >= 3) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(pl, () -> prohibited(p));
                return;
            }
        } else {
            lastMsg.put(id, new String[]{key, "1", String.valueOf(now)});
        }
        int letters = 0, upper = 0;
        for (char ch : msg.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) upper++;
            }
        }
        if (letters >= 10 && upper * 100 / letters > 70) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(pl, () -> prohibited(p));
            return;
        }

        int lv = level(msg);
        String why = "Chat profanity: " + Util.clip(msg, 60);
        if (lv == 0 && isAdvert(msg)) {
            lv = 1;
            why = "Chat advertising: " + Util.clip(msg, 60);
        }
        if (lv > 0) {
            e.setCancelled(true);
            flagLater(p, lv, why);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        String m = e.getMessage();
        if (!pl.staff.contains(p.getUniqueId())) {
            long now = System.currentTimeMillis();
            ArrayDeque<Long> q = cmdTimes.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > 3000) q.removeFirst();
            if (q.size() > 8) {
                e.setCancelled(true);
                q.clear();
                prohibited(p);
                pl.addFlag(p.getUniqueId(), p.getName(), 1, "Command spam", "automod", "Automod");
                return;
            }
        }
        int sp = m.indexOf(' ');
        if (sp < 0) return;
        String cmd = m.substring(1, sp).toLowerCase(Locale.ROOT);
        if (cmd.startsWith("minecraft:")) cmd = cmd.substring(10);
        if (!filteredCommands.contains(cmd)) return;
        String rest = m.substring(sp + 1);
        int lv = level(rest);
        if (lv > 0) {
            e.setCancelled(true);
            prohibited(p);
            pl.addFlag(p.getUniqueId(), p.getName(), lv, "Command profanity: /" + cmd + " " + Util.clip(rest, 50), "automod", "Automod");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        StringBuilder spaced = new StringBuilder();
        StringBuilder tight = new StringBuilder();
        for (Component line : e.lines()) {
            String t = PlainTextComponentSerializer.plainText().serialize(line);
            spaced.append(t).append(' ');
            tight.append(t);
        }
        int lv = Math.max(level(spaced.toString()), level(tight.toString()));
        if (lv > 0) {
            e.line(0, Component.text("[censored]"));
            for (int i = 1; i < 4; i++) e.line(i, Component.empty());
            prohibited(p);
            pl.addFlag(p.getUniqueId(), p.getName(), lv, "Sign profanity: " + Util.clip(spaced.toString().trim(), 60), "automod", "Automod");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        StringBuilder sb = new StringBuilder();
        for (Component pg : e.getNewBookMeta().pages()) sb.append(PlainTextComponentSerializer.plainText().serialize(pg)).append(' ');
        if (e.getNewBookMeta().hasTitle()) sb.append(PlainTextComponentSerializer.plainText().serialize(e.getNewBookMeta().title()));
        int lv = level(sb.toString());
        if (lv > 0) {
            e.setCancelled(true);
            prohibited(p);
            pl.addFlag(p.getUniqueId(), p.getName(), lv, "Book profanity: " + Util.clip(sb.toString().trim(), 60), "automod", "Automod");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        chatTimes.remove(id);
        cmdTimes.remove(id);
        lastMsg.remove(id);
    }
}
