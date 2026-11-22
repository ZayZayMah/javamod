package dev.zay.staff;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Lightweight anti-cheat: Fly, Speed, Reach, KillAura angle, AutoClicker, Nuker, FastPlace and an
 * ESP/X-ray heuristic. Each check builds up "violation levels" and only flags after several in a row,
 * to keep false positives low. Real X-ray prevention is Paper's built-in anti-xray (see README).
 */
final class AntiCheat implements Listener {

    private static final class S {
        int air, vlFly, vlSpeed, vlReach, vlAura, vlClick, vlNuker, vlPlace;
        int vlTimer, vlStep, vlPhase, vlNoFall, jesus, vlEat, vlBow, vlMulti, vlBlockReach, moves;
        long timerWin, lastEat, bowStart, lastHitMs;
        UUID lastVictim;
        double airStartY, winDist;
        long winStart, breakWin, placeWin;
        int breaks, places;
        Location lastGround;
        final ArrayDeque<Long> swings = new ArrayDeque<>();
        final ArrayDeque<Long> hidden = new ArrayDeque<>();
        final ArrayDeque<Long> allBreaks = new ArrayDeque<>();
        final Map<String, Long> cooldown = new HashMap<>();
    }

    private final StaffPlugin pl;
    private final Map<UUID, S> map = new HashMap<>();

    AntiCheat(StaffPlugin pl) {
        this.pl = pl;
    }

    private S s(Player p) {
        return map.computeIfAbsent(p.getUniqueId(), k -> new S());
    }

    private double leniency(Player p) {
        String prefix = pl.getConfig().getString("bedrock-prefix", ".");
        return p.getName().startsWith(prefix) ? pl.getConfig().getDouble("anticheat.bedrock-leniency", 1.25) : 1.0;
    }

    private boolean skip(Player p) {
        if (!pl.getConfig().getBoolean("anticheat.enabled", true)) return true;
        if (p.hasPermission("zaystaff.bypass")) return true;
        GameMode g = p.getGameMode();
        if (g == GameMode.CREATIVE || g == GameMode.SPECTATOR) return true;
        UUID id = p.getUniqueId();
        if (pl.vanished.contains(id) || pl.staff.contains(id)) return true;
        return false;
    }

    private boolean exempt(Player p) {
        Long until = pl.exempt.get(p.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void violate(Player p, String check, String detail, int level, String category) {
        S st = s(p);
        long now = System.currentTimeMillis();
        Long last = st.cooldown.get(check);
        if (last != null && now - last < 30_000L) return;
        st.cooldown.put(check, now);
        pl.flagSafe(p.getUniqueId(), p.getName(), level, check + ": " + detail, category, "AntiCheat");
    }

    // ------------------------------------------------------------------ block helpers

    private static boolean solidAt(World w, double x, double y, double z) {
        Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return !b.isPassable();
    }

    private static boolean onGround(Location l) {
        World w = l.getWorld();
        for (double dx : new double[]{-0.3, 0, 0.3}) {
            for (double dz : new double[]{-0.3, 0, 0.3}) {
                if (solidAt(w, l.getX() + dx, l.getY() - 0.05, l.getZ() + dz)) return true;
                if (solidAt(w, l.getX() + dx, l.getY() - 0.5, l.getZ() + dz)) return true;
            }
        }
        return false;
    }

    private static boolean inLiquid(Location l) {
        World w = l.getWorld();
        for (double dy : new double[]{-0.4, 0.0, 1.0}) {
            if (w.getBlockAt((int) Math.floor(l.getX()), (int) Math.floor(l.getY() + dy), (int) Math.floor(l.getZ())).isLiquid()) {
                return true;
            }
        }
        return false;
    }

    private static boolean sticky(Location l) {
        World w = l.getWorld();
        for (double dy : new double[]{0.0, 1.0}) {
            Block b = w.getBlockAt((int) Math.floor(l.getX()), (int) Math.floor(l.getY() + dy), (int) Math.floor(l.getZ()));
            Material m = b.getType();
            if (Tag.CLIMBABLE.isTagged(m) || m == Material.COBWEB || m == Material.SWEET_BERRY_BUSH
                    || m == Material.POWDER_SNOW || m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK
                    || m == Material.BUBBLE_COLUMN) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ movement: fly + speed

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null || t.getWorld() == null || f.getWorld() != t.getWorld()) return;
        if (f.getX() == t.getX() && f.getY() == t.getY() && f.getZ() == t.getZ()) return;
        S st = s(p);
        long now = System.currentTimeMillis();
        double dyAll = t.getY() - f.getY();

        if (!skip(p) && Math.abs(t.getPitch()) > 90.5f) {
            violate(p, "BadPackets", "impossible pitch " + t.getPitch(), 4, "hacking");
            e.setTo(f);
            return;
        }

        boolean ex = skip(p) || exempt(p) || p.isInsideVehicle() || p.isGliding() || p.isRiptiding()
                || p.getAllowFlight() || p.isFlying()
                || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        boolean ground = onGround(t);
        boolean liquid = inLiquid(t);
        boolean sticky = sticky(t);

        if (!ex) {
            // Timer: far more movement packets per second than the game tick rate allows
            if (st.timerWin == 0) st.timerWin = now;
            st.moves++;
            if (now - st.timerWin >= 1000) {
                if (st.moves > 32 * leniency(p)) st.vlTimer++;
                else st.vlTimer = Math.max(0, st.vlTimer - 1);
                st.moves = 0;
                st.timerWin = now;
                if (st.vlTimer >= 4) {
                    st.vlTimer = 0;
                    violate(p, "Timer", "sending movement too fast", 3, "hacking");
                    if (st.lastGround != null) e.setTo(st.lastGround);
                }
            }
            // Step: climbing a full block in one move without jumping
            PotionEffect jbs = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
            if (ground && !sticky && jbs == null && dyAll > 0.8 && onGround(f)) {
                if (++st.vlStep >= 2) {
                    st.vlStep = 0;
                    violate(p, "Step", "stepped up " + String.format("%.1f", dyAll) + " blocks", 3, "hacking");
                }
                e.setTo(f);
                return;
            }
            // Phase / NoClip: moving the head into a solid block
            double eh = p.getEyeHeight();
            if (t.clone().add(0, eh, 0).getBlock().getType().isOccluding()
                    && !f.clone().add(0, eh, 0).getBlock().getType().isOccluding()) {
                if (++st.vlPhase >= 2) {
                    st.vlPhase = 0;
                    violate(p, "Phase", "moved into a solid block", 4, "hacking");
                }
                e.setTo(f);
                return;
            }
            // Jesus: walking on the water surface
            if (liquid && !p.isSwimming() && Math.abs(dyAll) < 0.0001
                    && Math.hypot(t.getX() - f.getX(), t.getZ() - f.getZ()) > 0.1) {
                if (++st.jesus >= 12) {
                    st.jesus = 0;
                    violate(p, "Jesus", "walking on liquid", 3, "hacking");
                }
            } else {
                st.jesus = 0;
            }
        }

        if (ex || ground || liquid || sticky) {
            if (ground && !ex) st.lastGround = t.clone();
            st.air = 0;
            st.vlFly = Math.max(0, st.vlFly - 1);
            if (ex) {
                st.winDist = 0;
                st.winStart = now;
                st.vlSpeed = 0;
            }
        } else {
            if (st.air == 0) st.airStartY = f.getY();
            st.air++;
            double dy = t.getY() - f.getY();
            PotionEffect jb = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
            double maxRise = 1.65 + (jb == null ? 0 : 0.9 * (jb.getAmplifier() + 1));
            boolean rise = t.getY() - st.airStartY > maxRise;
            if (st.air > 12 && dy > -0.04) st.vlFly++;
            if (st.air > 6 && dy < -0.5 && p.getFallDistance() == 0f) {
                if (++st.vlNoFall >= 3) {
                    st.vlNoFall = 0;
                    violate(p, "NoFall", "falling without fall distance", 3, "hacking");
                }
            }
            if (rise || st.vlFly >= 6) {
                violate(p, "Fly", rise ? "rose " + String.format("%.1f", t.getY() - st.airStartY) + " blocks in the air"
                        : "hovering for " + st.air + " ticks", 3, "hacking");
                if (st.lastGround != null) e.setTo(st.lastGround);
                st.air = 0;
                st.vlFly = 0;
                return;
            }
        }

        if (!ex) {
            double dx = t.getX() - f.getX();
            double dz = t.getZ() - f.getZ();
            if (st.winStart == 0) st.winStart = now;
            st.winDist += Math.sqrt(dx * dx + dz * dz);
            long dt = now - st.winStart;
            if (dt >= 1000) {
                double rate = st.winDist * 1000.0 / dt;
                PotionEffect sp = p.getPotionEffect(PotionEffectType.SPEED);
                double lim = 11.5 * (1 + 0.25 * (sp == null ? 0 : sp.getAmplifier() + 1)) * leniency(p);
                Material below = t.clone().subtract(0, 0.5, 0).getBlock().getType();
                if (below.name().contains("ICE")) lim *= 1.8;
                if (rate > lim) st.vlSpeed++;
                else st.vlSpeed = Math.max(0, st.vlSpeed - 1);
                st.winDist = 0;
                st.winStart = now;
                if (st.vlSpeed >= 3) {
                    st.vlSpeed = 0;
                    violate(p, "Speed", String.format("%.1f blocks/s (limit %.1f)", rate, lim), 3, "hacking");
                    if (st.lastGround != null) e.setTo(st.lastGround);
                }
            }
        }
    }

    // ------------------------------------------------------------------ combat: reach, aura, autoclicker

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (skip(p) || exempt(p)) return;
        Entity v = e.getEntity();
        S st = s(p);
        long nowHit = System.currentTimeMillis();
        if (st.lastVictim != null && !st.lastVictim.equals(v.getUniqueId()) && nowHit - st.lastHitMs < 40) {
            e.setCancelled(true);
            if (++st.vlMulti >= 3) {
                st.vlMulti = 0;
                violate(p, "MultiAura", "hit several targets in the same tick", 4, "hacking");
            }
        }
        st.lastVictim = v.getUniqueId();
        st.lastHitMs = nowHit;
        Location eye = p.getEyeLocation();
        BoundingBox bb = v.getBoundingBox();
        double cx = Math.max(bb.getMinX(), Math.min(eye.getX(), bb.getMaxX()));
        double cy = Math.max(bb.getMinY(), Math.min(eye.getY(), bb.getMaxY()));
        double cz = Math.max(bb.getMinZ(), Math.min(eye.getZ(), bb.getMaxZ()));
        double dist = Math.sqrt(Math.pow(cx - eye.getX(), 2) + Math.pow(cy - eye.getY(), 2) + Math.pow(cz - eye.getZ(), 2));
        double limit = 4.3 * leniency(p);
        if (dist > limit) {
            e.setCancelled(true);
            if (++st.vlReach >= 3) {
                st.vlReach = 0;
                violate(p, "Reach", String.format("hit from %.1f blocks", dist), 4, "hacking");
            }
        } else {
            st.vlReach = Math.max(0, st.vlReach - 1);
        }
        if (dist > 2.5) {
            Vector toV = bb.getCenter().subtract(eye.toVector());
            double angle = eye.getDirection().angle(toV);
            if (angle > 1.4) {
                e.setCancelled(true);
                if (++st.vlAura >= 4) {
                    st.vlAura = 0;
                    violate(p, "KillAura", String.format("hit %.0f degrees off-target", Math.toDegrees(angle)), 4, "hacking");
                }
            } else {
                st.vlAura = Math.max(0, st.vlAura - 1);
            }
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        S st = s(p);
        long now = System.currentTimeMillis();
        st.swings.addLast(now);
        while (!st.swings.isEmpty() && now - st.swings.peekFirst() > 1000) st.swings.removeFirst();
        if (st.swings.size() > 22 * leniency(p)) {
            st.swings.clear();
            if (++st.vlClick >= 2) {
                st.vlClick = 0;
                violate(p, "AutoClicker", "more than 22 clicks per second", 2, "hacking");
            }
        }
    }

    // ------------------------------------------------------------------ blocks: nuker, fast place, ESP/x-ray

    private static boolean exposed(Block b) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            Block n = b.getRelative(o[0], o[1], o[2]);
            if (n.isPassable() || n.isLiquid()) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        S st = s(p);
        long now = System.currentTimeMillis();

        if (p.getEyeLocation().distance(e.getBlock().getLocation().add(0.5, 0.5, 0.5)) > 7.0 * leniency(p)) {
            e.setCancelled(true);
            if (++st.vlBlockReach >= 3) {
                st.vlBlockReach = 0;
                violate(p, "BlockReach", "broke a block from too far away", 3, "hacking");
            }
            return;
        }

        if (now - st.breakWin > 1000) {
            st.breakWin = now;
            st.breaks = 0;
        }
        if (++st.breaks > 14) {
            e.setCancelled(true);
            if (++st.vlNuker >= 3) {
                st.vlNuker = 0;
                violate(p, "Nuker", "breaking more than 14 blocks per second", 3, "hacking");
            }
            return;
        }

        // ESP / x-ray heuristic: lots of hidden valuable ores compared to everything else mined
        Block b = e.getBlock();
        String n = b.getType().name();
        st.allBreaks.addLast(now);
        while (!st.allBreaks.isEmpty() && now - st.allBreaks.peekFirst() > 120_000L) st.allBreaks.removeFirst();
        boolean valuable = n.contains("DIAMOND_ORE") || n.contains("EMERALD_ORE") || n.equals("ANCIENT_DEBRIS");
        if (valuable && !exposed(b)) st.hidden.addLast(now);
        while (!st.hidden.isEmpty() && now - st.hidden.peekFirst() > 120_000L) st.hidden.removeFirst();
        if (st.hidden.size() >= 6 && st.hidden.size() * 1.0 / Math.max(1, st.allBreaks.size()) >= 0.35) {
            st.hidden.clear();
            violate(p, "Xray/ESP", "mined 6+ hidden valuable ores in 2 minutes with very few other blocks", 2, "esp");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        S st = s(p);
        long now = System.currentTimeMillis();
        if (p.getEyeLocation().distance(e.getBlockAgainst().getLocation().add(0.5, 0.5, 0.5)) > 7.0 * leniency(p)) {
            e.setCancelled(true);
            if (++st.vlBlockReach >= 3) {
                st.vlBlockReach = 0;
                violate(p, "BlockReach", "placed a block from too far away", 3, "hacking");
            }
            return;
        }
        if (now - st.placeWin > 1000) {
            st.placeWin = now;
            st.places = 0;
        }
        if (++st.places > 12) {
            e.setCancelled(true);
            if (++st.vlPlace >= 3) {
                st.vlPlace = 0;
                violate(p, "FastPlace", "placing more than 12 blocks per second", 2, "hacking");
            }
        }
    }

    // ------------------------------------------------------------------ FastEat / FastBow

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        S st = s(p);
        long now = System.currentTimeMillis();
        if (st.lastEat != 0 && now - st.lastEat < 700) {
            e.setCancelled(true);
            if (++st.vlEat >= 2) {
                st.vlEat = 0;
                violate(p, "FastEat", "consuming items too quickly", 2, "hacking");
            }
        }
        st.lastEat = now;
    }

    @EventHandler
    public void onInteractBow(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getItem() != null && e.getItem().getType() == Material.BOW) s(e.getPlayer()).bowStart = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || skip(p)) return;
        if (e.getBow() == null || e.getBow().getType() != Material.BOW) return;
        S st = s(p);
        long el = System.currentTimeMillis() - st.bowStart;
        if (st.bowStart != 0 && el < 450 && e.getForce() >= 0.95f) {
            e.setCancelled(true);
            if (++st.vlBow >= 2) {
                st.vlBow = 0;
                violate(p, "FastBow", "full-power shot after " + el + "ms", 3, "hacking");
            }
        }
    }

    // ------------------------------------------------------------------ exemptions

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        pl.exempt(p, 2500);
        S st = s(p);
        st.air = 0;
        st.vlFly = 0;
        st.lastGround = null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) pl.exempt(p, 1500);
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        pl.exempt(e.getPlayer(), 1500);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        pl.exempt(e.getPlayer(), 3000);
        map.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        map.remove(e.getPlayer().getUniqueId());
    }
}
