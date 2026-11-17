package de.kopfgeld;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Kopfgeld - jeder bekommt ein Ziel, 15 Minuten Zeit, sonst Tod + Bann.
 */
public class KopfgeldPlugin extends JavaPlugin implements Listener {

    private enum Phase { IDLE, COUNTDOWN, WAITING, DRAW, HUNT, ENDED }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX =
            "<gradient:#ff0000:#ffaa00><bold>KOPFGELD</bold></gradient> <dark_gray>» <gray>";

    private Phase phase = Phase.IDLE;
    private int gen = 0; // wird bei /kopfgeldstop erhoeht, alte Tasks brechen dann ab

    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, UUID> targets = new LinkedHashMap<>(); // Jaeger -> Ziel
    private final Set<UUID> done = new HashSet<>();   // Jaeger, die "sicher" sind
    private final Set<UUID> doomed = new HashSet<>(); // werden gerade hingerichtet

    private BossBar bar;
    private BukkitTask ticker;
    private BukkitTask compassTask;
    private int round = 0;
    private int remaining = 0;
    private int total = 1;
    private int pendingRoulettes = 0;

    // ------------------------------------------------------------------ Lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        bar = BossBar.bossBar(mm("<gold>Kopfgeld"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        resetBorder();
        getLogger().info("Kopfgeld geladen - /start zum Starten.");
    }

    @Override
    public void onDisable() {
        hardReset(false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "start" -> beginCountdown(sender);
            case "kopfgeldstop" -> {
                hardReset(true);
                sender.sendMessage(mm(PREFIX + "Spiel abgebrochen und zurückgesetzt."));
            }
            default -> { return false; }
        }
        return true;
    }

    // ------------------------------------------------------------------ Helpers

    private Component mm(String s) { return MM.deserialize(s); }

    private void all(Consumer<Player> c) {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) c.accept(p);
    }

    private void title(Player p, String t, String s, int in, int stay, int out) {
        p.showTitle(Title.title(mm(t), mm(s), Title.Times.times(
                Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L))));
    }

    private void snd(Player p, Sound s, float vol, float pitch) {
        p.playSound(p.getLocation(), s, vol, pitch);
    }

    private void announce(String msg) { Bukkit.broadcast(mm(PREFIX + msg)); }

    private String fmt(int sec) { return String.format("%02d:%02d", sec / 60, sec % 60); }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "???" : n;
    }

    private World world() { return Bukkit.getWorlds().get(0); }

    private void resetBorder() {
        WorldBorder b = world().getWorldBorder();
        b.setCenter(world().getSpawnLocation());
        b.setSize(getConfig().getDouble("border-start-size", 50));
    }

    private ItemStack named(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void firework(Location loc) {
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta m = fw.getFireworkMeta();
        m.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(org.bukkit.Color.RED, org.bukkit.Color.ORANGE, org.bukkit.Color.YELLOW)
                .withFade(org.bukkit.Color.WHITE)
                .trail(true).flicker(true).build());
        fw.setFireworkMeta(m);
        fw.detonate();
    }

    private int nextWait() {
        return getConfig().getInt("initial-wait-seconds", 300)
                + round * getConfig().getInt("wait-increase-seconds", 300);
    }

    private List<Player> alivePlayersOnline() {
        List<Player> l = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) l.add(p);
        }
        return l;
    }

    private void hardReset(boolean resetBorderToo) {
        gen++;
        if (ticker != null) { ticker.cancel(); ticker = null; }
        if (compassTask != null) { compassTask.cancel(); compassTask = null; }
        phase = Phase.IDLE;
        alive.clear(); targets.clear(); done.clear(); doomed.clear();
        round = 0; pendingRoulettes = 0;
        if (bar != null) all(p -> p.hideBossBar(bar));
        all(p -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof RouletteHolder h) {
                h.active = false;
                p.closeInventory();
            }
            p.getInventory().remove(Material.COMPASS);
        });
        if (resetBorderToo) resetBorder();
    }

    // ------------------------------------------------------------------ Start-Countdown

    private void beginCountdown(CommandSender sender) {
        if (phase != Phase.IDLE) {
            sender.sendMessage(mm(PREFIX + "<red>Das Spiel läuft bereits!"));
            return;
        }
        List<Player> ps = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (ps.size() < 2) {
            sender.sendMessage(mm(PREFIX + "<red>Es werden mindestens 2 Spieler benötigt."));
            return;
        }
        alive.clear(); targets.clear(); done.clear(); doomed.clear();
        round = 0;
        for (Player p : ps) alive.add(p.getUniqueId());
        phase = Phase.COUNTDOWN;
        final int myGen = gen;
        final String[] colors = {"#ff5555", "#ffaa00", "#ffff55", "#aaff55", "#55ff55"}; // 1..5

        new BukkitRunnable() {
            int n = 5;

            @Override
            public void run() {
                if (myGen != gen) { cancel(); return; }
                if (n > 0) {
                    final int num = n;
                    all(p -> {
                        title(p, "<" + colors[num - 1] + "><bold>" + num, "<gray>Das Spiel beginnt...", 0, 22, 0);
                        snd(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f + (5 - num) * 0.2f);
                    });
                    n--;
                } else {
                    go();
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void go() {
        double size = getConfig().getDouble("border-end-size", 3000);
        long secs = getConfig().getLong("border-expand-seconds", 15);
        world().getWorldBorder().setSize(size, secs);

        all(p -> {
            title(p, "<gradient:#ff0000:#ffaa00><bold>LOS GEHT'S!", "<gray>Die Border öffnet sich...", 0, 50, 15);
            snd(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1f);
            snd(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        });

        // Partikel-Ring, der sich um jeden Spieler ausbreitet
        final int myGen = gen;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (myGen != gen || t > 20) { cancel(); return; }
                final double r = 1 + t * 0.35;
                all(p -> {
                    Location c = p.getLocation().add(0, 0.2, 0);
                    for (int i = 0; i < 36; i++) {
                        double a = Math.toRadians(i * 10);
                        p.getWorld().spawnParticle(Particle.END_ROD,
                                c.clone().add(Math.cos(a) * r, t * 0.05, Math.sin(a) * r), 0, 0, 0.02, 0, 1);
                    }
                });
                t++;
            }
        }.runTaskTimer(this, 0L, 2L);

        announce("<gold>Die Border wächst! <gray>In <yellow>" + fmt(getConfig().getInt("initial-wait-seconds", 300))
                + " <gray>wird das erste Kopfgeld vergeben.");
        startTimer(Phase.WAITING, getConfig().getInt("initial-wait-seconds", 300));
    }

    // ------------------------------------------------------------------ Timer / Bossbar

    private void startTimer(Phase p, int seconds) {
        phase = p;
        total = Math.max(1, seconds);
        remaining = seconds;
        if (ticker != null) ticker.cancel();
        all(pl -> pl.showBossBar(bar));
        updateBar();
        final int myGen = gen;
        ticker = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (myGen != gen) return;
            tick();
        }, 20L, 20L);
    }

    private void tick() {
        remaining--;
        updateBar();

        if (remaining > 0 && remaining <= 10) {
            final int r = remaining;
            all(p -> {
                snd(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                if (r <= 5) {
                    String c = r <= 2 ? "#ff0000" : (r <= 4 ? "#ff8800" : "#ffdd00");
                    title(p, "<" + c + "><bold>" + r, phase == Phase.HUNT ? "<gray>Zeit läuft ab..." : "<gray>Gleich geht's los...", 0, 15, 0);
                }
            });
        }

        if (remaining <= 0) {
            ticker.cancel();
            ticker = null;
            if (phase == Phase.WAITING) startDraw();
            else if (phase == Phase.HUNT) evaluateHunt();
        }
    }

    private void updateBar() {
        float prog = Math.max(0f, Math.min(1f, remaining / (float) total));
        bar.progress(prog);
        boolean urgent = remaining <= 10;
        String icon = remaining % 2 == 0 ? "⌛" : "⏳";
        if (phase == Phase.WAITING) {
            bar.color(urgent && remaining % 2 == 0 ? BossBar.Color.RED : BossBar.Color.YELLOW);
            bar.name(mm("<gradient:#ffd000:#ff6a00><bold>» NÄCHSTES KOPFGELD IN «</bold></gradient> <white>" + fmt(remaining)));
        } else if (phase == Phase.HUNT) {
            bar.color(urgent && remaining % 2 == 0 ? BossBar.Color.WHITE : BossBar.Color.RED);
            bar.name(mm("<gradient:#ff0000:#ff9900><bold>☠ DIE JAGD LÄUFT ☠</bold></gradient> <white>" + fmt(remaining)));
        }
    }

    // ------------------------------------------------------------------ Auslosung

    private void startDraw() {
        phase = Phase.DRAW;
        round++;
        List<Player> pool = alivePlayersOnline();
        if (pool.size() < 2) {
            if (!checkWinner()) {
                announce("<red>Zu wenige Spieler online - die Auslosung wird verschoben.");
                startTimer(Phase.WAITING, nextWait());
            }
            return;
        }
        bar.color(BossBar.Color.PURPLE);
        bar.progress(1f);
        bar.name(mm("<gradient:#aa00ff:#ff55ff><bold>» AUSLOSUNG «"));

        final int myGen = gen;
        final String word = "KOPFGELD";
        new BukkitRunnable() {
            int f = 0;

            @Override
            public void run() {
                if (myGen != gen) { cancel(); return; }
                int reveal = Math.min(word.length(), f / 2);
                String shown = word.substring(0, reveal);
                String hidden = word.substring(reveal);
                String color = f % 2 == 0 ? "#ff0000" : "#ffaa00";
                String t = "<" + color + "><bold>" + shown
                        + (hidden.isEmpty() ? "" : "<obfuscated>" + hidden + "</obfuscated>");
                final int frame = f;
                all(p -> {
                    title(p, t, "<gray>Die Jagd wird ausgelost...", 0, 8, 0);
                    snd(p, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f + frame * 0.05f);
                    if (frame == 0) snd(p, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.8f);
                });
                f++;
                if (f > word.length() * 2 + 2) {
                    cancel();
                    all(p -> {
                        p.getWorld().strikeLightningEffect(p.getLocation());
                        title(p, "<gradient:#ff0000:#ffaa00><bold>KOPFGELD", "<white>Wer ist dein Ziel?", 0, 40, 10);
                        snd(p, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
                    });
                    Bukkit.getScheduler().runTaskLater(KopfgeldPlugin.this, () -> {
                        if (myGen == gen) startRoulettes();
                    }, 50L);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    private void startRoulettes() {
        List<Player> online = alivePlayersOnline();
        if (online.size() < 2) {
            if (!checkWinner()) startTimer(Phase.WAITING, nextWait());
            return;
        }
        Collections.shuffle(online);
        targets.clear();
        done.clear();
        int n = online.size();
        // zufaelliger Ring: jeder jagt genau einen anderen, jeder wird von genau einem gejagt
        for (int i = 0; i < n; i++) {
            targets.put(online.get(i).getUniqueId(), online.get((i + 1) % n).getUniqueId());
        }
        pendingRoulettes = n;
        for (Player hunter : online) {
            Player target = Bukkit.getPlayer(targets.get(hunter.getUniqueId()));
            List<Player> pool = new ArrayList<>(online);
            pool.remove(hunter);
            new Roulette(hunter, target, pool).begin();
        }
    }

    private static class RouletteHolder implements InventoryHolder {
        Inventory inv;
        boolean active = true;

        @Override
        public Inventory getInventory() { return inv; }
    }

    /** Glücksspiel-Animation: Spielerköpfe rollen durch die Mitte, Name darunter. */
    private final class Roulette {
        private final Player hunter;
        private final Player target;
        private final RouletteHolder holder = new RouletteHolder();
        private final Inventory inv;
        private final List<Player> seq = new ArrayList<>();
        private final Map<UUID, ItemStack> heads = new HashMap<>();
        private final int steps = 32;
        private final int myGen = gen;
        private int s = 0;
        private boolean finished = false;

        Roulette(Player hunter, Player target, List<Player> pool) {
            this.hunter = hunter;
            this.target = target;
            this.inv = Bukkit.createInventory(holder, 27, mm("<gradient:#ff5555:#ffaa00><bold>» DEIN KOPFGELD «"));
            holder.inv = inv;

            ItemStack fill = named(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> ");
            for (int i = 0; i < 27; i++) inv.setItem(i, fill);
            inv.setItem(4, named(Material.HOPPER, "<gold><bold>▼"));

            for (Player p : pool) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta m = (SkullMeta) head.getItemMeta();
                m.setOwningPlayer(p);
                m.displayName(mm("<white>" + p.getName()).decoration(TextDecoration.ITALIC, false));
                head.setItemMeta(m);
                heads.put(p.getUniqueId(), head);
            }

            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            Player prev = null;
            for (int k = 0; k < steps + 9; k++) {
                Player c;
                do { c = pool.get(rnd.nextInt(pool.size())); } while (pool.size() > 1 && c == prev);
                seq.add(c);
                prev = c;
            }
            seq.set(steps + 4, target); // hier landet die Animation
        }

        void begin() {
            hunter.openInventory(inv);
            next();
        }

        private void next() {
            if (myGen != gen) return;
            if (!hunter.isOnline()) { finish(); return; }
            render();
            if (s >= steps) { reveal(); return; }
            long delay = 2 + Math.round(11.0 * Math.pow(s / (double) steps, 3));
            s++;
            Bukkit.getScheduler().runTaskLater(KopfgeldPlugin.this, this::next, delay);
        }

        private void render() {
            for (int col = 0; col < 9; col++) {
                inv.setItem(9 + col, heads.get(seq.get(s + col).getUniqueId()).clone());
            }
            Player center = seq.get(s + 4);
            inv.setItem(22, named(Material.NAME_TAG, "<yellow><bold>" + center.getName()));
            snd(hunter, Sound.UI_BUTTON_CLICK, 0.7f, 0.8f + 1.0f * s / steps);
        }

        private void reveal() {
            ItemStack lime = named(Material.LIME_STAINED_GLASS_PANE, "<green> ");
            for (int slot : new int[]{3, 5, 12, 14, 21, 23}) inv.setItem(slot, lime);
            inv.setItem(22, named(Material.EMERALD, "<green><bold>✔ " + target.getName()));
            snd(hunter, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            Location l = hunter.getLocation().add(0, 1, 0);
            hunter.getWorld().spawnParticle(Particle.END_ROD, l, 40, 0.6, 0.8, 0.6, 0.05);
            Bukkit.getScheduler().runTaskLater(KopfgeldPlugin.this, this::finish, 50L);
        }

        private void finish() {
            if (finished) return;
            finished = true;
            holder.active = false;
            if (hunter.isOnline() && hunter.getOpenInventory().getTopInventory() == inv) hunter.closeInventory();
            if (myGen != gen) return;
            pendingRoulettes--;
            if (pendingRoulettes <= 0) startHunt();
        }
    }

    // ------------------------------------------------------------------ Jagd

    private void startHunt() {
        int huntSec = getConfig().getInt("hunt-seconds", 900);
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            Player h = Bukkit.getPlayer(e.getKey());
            if (h == null || !alive.contains(e.getKey())) continue;
            String tn = nameOf(e.getValue());
            ItemStack compass = named(Material.COMPASS, "<gradient:#ff0000:#ff9900><bold>Kopfgeld-Kompass");
            Map<Integer, ItemStack> left = h.getInventory().addItem(compass);
            left.values().forEach(it -> h.getWorld().dropItem(h.getLocation(), it));
            title(h, "<gradient:#ff0000:#ff9900><bold>☠ DIE JAGD BEGINNT", "<white>Töte <red><bold>" + tn
                    + "<reset> <white>in <yellow>" + fmt(huntSec), 5, 70, 15);
            h.sendMessage(mm(PREFIX + "Dein Ziel: <red><bold>" + tn + "<reset><gray>. Du hast <yellow>" + fmt(huntSec)
                    + "<gray> Zeit - sonst stirbst du!"));
            snd(h, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
        }
        startTimer(Phase.HUNT, huntSec);

        if (compassTask != null) compassTask.cancel();
        final int myGen = gen;
        compassTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (myGen != gen) return;
            updateCompasses();
        }, 0L, 10L);
    }

    private void updateCompasses() {
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            if (done.contains(e.getKey()) || !alive.contains(e.getKey())) continue;
            Player h = Bukkit.getPlayer(e.getKey());
            if (h == null) continue;
            Player t = Bukkit.getPlayer(e.getValue());
            if (t == null) {
                h.sendActionBar(mm("<red>☠ Dein Ziel ist gerade offline"));
                continue;
            }
            if (t.getWorld().equals(h.getWorld())) {
                h.setCompassTarget(t.getLocation());
                int d = (int) h.getLocation().distance(t.getLocation());
                h.sendActionBar(mm("<gradient:#ff0000:#ff9900><bold>☠ " + t.getName()
                        + "</bold></gradient> <dark_gray>» <white>" + d + " Blöcke"));
            } else {
                h.sendActionBar(mm("<gradient:#ff0000:#ff9900><bold>☠ " + t.getName()
                        + "</bold></gradient> <dark_gray>» <gray>in einer anderen Dimension"));
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (phase == Phase.IDLE || phase == Phase.ENDED || phase == Phase.COUNTDOWN) return;
        Player victim = e.getEntity();
        UUID vid = victim.getUniqueId();
        if (!alive.contains(vid) || doomed.contains(vid)) return;
        Player killer = victim.getKiller();

        if (phase == Phase.HUNT) {
            for (Map.Entry<UUID, UUID> en : targets.entrySet()) {
                UUID hid = en.getKey();
                if (!en.getValue().equals(vid) || done.contains(hid) || !alive.contains(hid)) continue;
                done.add(hid);
                Player h = Bukkit.getPlayer(hid);
                if (h == null) continue;
                h.getInventory().remove(Material.COMPASS);
                if (killer != null && killer.getUniqueId().equals(hid)) {
                    success(h, victim);
                } else {
                    h.sendMessage(mm(PREFIX + "<yellow>Dein Ziel ist anders gestorben - du bist sicher."));
                }
            }
        }

        if (getConfig().getBoolean("ban-on-any-death", false)) {
            eliminate(vid, "Kopfgeld: gestorben");
            if (phase == Phase.HUNT || phase == Phase.WAITING) checkWinner();
        }
    }

    private void success(Player hunter, Player victim) {
        title(hunter, "<gradient:#55ff55:#00aa00><bold>✔ KOPFGELD KASSIERT", "<white>" + victim.getName() + " ist erledigt", 5, 50, 15);
        snd(hunter, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        firework(hunter.getLocation());
        announce("<gold>" + hunter.getName() + " <gray>hat das Kopfgeld auf <red>" + victim.getName() + " <gray>kassiert!");
    }

    // ------------------------------------------------------------------ Auswertung

    private void evaluateHunt() {
        phase = Phase.DRAW; // Zwischenphase: Auswertung
        if (compassTask != null) { compassTask.cancel(); compassTask = null; }
        all(p -> p.getInventory().remove(Material.COMPASS));

        List<UUID> fails = new ArrayList<>();
        for (UUID h : targets.keySet()) {
            if (alive.contains(h) && !done.contains(h)) fails.add(h);
        }

        all(p -> {
            title(p, "<gradient:#ff0000:#880000><bold>ZEIT ABGELAUFEN", "<gray>Die Jagd ist vorbei...", 5, 50, 15);
            snd(p, Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.7f);
        });

        if (fails.isEmpty()) {
            announce("<green>Alle haben ihr Kopfgeld erfüllt - niemand stirbt!");
        } else {
            for (UUID id : fails) doom(id);
        }

        final int myGen = gen;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (myGen != gen) return;
            if (!checkWinner()) {
                int wait = nextWait();
                announce("<gray>Nächstes Kopfgeld in <yellow>" + fmt(wait) + "<gray>.");
                startTimer(Phase.WAITING, wait);
            }
        }, 120L);
    }

    private void doom(UUID id) {
        doomed.add(id);
        alive.remove(id);
        String name = nameOf(id);
        announce("<red><bold>" + name + "<reset> <gray>hat sein Kopfgeld verfehlt und scheidet aus!");

        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            title(p, "<dark_red><bold>☠ DU HAST VERSAGT", "<gray>Dein Ziel lebt noch...", 5, 40, 10);
            p.getWorld().strikeLightningEffect(p.getLocation());
            snd(p, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 0.6f);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player pp = Bukkit.getPlayer(id);
                if (pp != null && pp.getGameMode() != GameMode.SPECTATOR) pp.setHealth(0.0);
            }, 40L);
        }
        if (getConfig().getBoolean("ban-on-fail", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> banByName(name, "Kopfgeld: Ziel nicht erledigt"), 80L);
        }
    }

    private void eliminate(UUID id, String reason) {
        alive.remove(id);
        String name = nameOf(id);
        Bukkit.getScheduler().runTaskLater(this, () -> banByName(name, reason), 40L);
    }

    private void banByName(String name, String reason) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason);
    }

    // ------------------------------------------------------------------ Ende

    private boolean checkWinner() {
        if (phase == Phase.IDLE || phase == Phase.ENDED || phase == Phase.COUNTDOWN) return false;
        if (alive.size() > 1) return false;
        endGame(alive.isEmpty() ? null : alive.iterator().next());
        return true;
    }

    private void endGame(UUID winner) {
        phase = Phase.ENDED;
        if (ticker != null) { ticker.cancel(); ticker = null; }
        if (compassTask != null) { compassTask.cancel(); compassTask = null; }
        all(p -> { p.hideBossBar(bar); p.getInventory().remove(Material.COMPASS); });

        final String wn = winner == null ? null : nameOf(winner);
        all(p -> {
            if (wn != null) {
                title(p, "<gradient:#ffd000:#ffffff><bold>♛ GEWINNER ♛", "<white>" + wn, 5, 100, 20);
            } else {
                title(p, "<gradient:#ff0000:#880000><bold>NIEMAND ÜBERLEBT", "<gray>Das Kopfgeld hat alle geholt", 5, 100, 20);
            }
            snd(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        });
        announce(wn != null ? "<gold><bold>" + wn + "<reset> <gray>gewinnt das Kopfgeld!" : "<red>Niemand hat überlebt.");

        final int myGen = gen;
        final Player wp = winner == null ? null : Bukkit.getPlayer(winner);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (myGen != gen || t >= 20) {
                    cancel();
                    if (myGen == gen) {
                        phase = Phase.IDLE;
                        alive.clear(); targets.clear(); done.clear(); doomed.clear();
                        resetBorder();
                    }
                    return;
                }
                if (wp != null && wp.isOnline()) {
                    Location l = wp.getLocation().add(ThreadLocalRandom.current().nextInt(-4, 5), 0,
                            ThreadLocalRandom.current().nextInt(-4, 5));
                    firework(l);
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    // ------------------------------------------------------------------ Events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (phase == Phase.IDLE) return;
        if (ticker != null) p.showBossBar(bar);
        if (!alive.contains(p.getUniqueId()) && !doomed.contains(p.getUniqueId())) {
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage(mm(PREFIX + "<gray>Das Spiel läuft schon - du schaust zu."));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof RouletteHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RouletteHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof RouletteHolder h) || !h.active) return;
        Player p = (Player) e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (h.active && p.isOnline()) p.openInventory(h.inv);
        });
    }
}
