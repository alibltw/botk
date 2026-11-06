package com.botplus.locator;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.types.NpcFlag;
import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Lockable;
import eu.darkbot.api.game.other.Location;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Bulucu Bulunca Git, NPC'yi Birak ve Vurulan NPC'yi Yok Say.
 * Bulucu sadece konum verir (NPC kimligi yok), bu yuzden yok sayilan NPC'nin son konumuna yakin sinyaller de atlanir.
 */
public final class Locator {

    private static final long IGNORE_MS = 60_000;
    private static final int IGNORE_ZONE = 1500;
    private static final int NEAR_LOC = 1000;
    private static final long PING_LOST_MS = 3000;
    private static final long GO_TIMEOUT_MS = 90_000;
    private static final int EVADE_STEP = 2000;
    private static final long EVADE_TIMEOUT_MS = 15_000;

    private static final class Ignored {
        public final int id;
        public final String name;
        public Location loc;
        public final long until;

        public Ignored(Npc n, long until) {
            this.id = n.getId();
            this.name = n.getInfo() == null ? "?" : n.getInfo().getName();
            this.loc = Location.of(n);
            this.until = until;
        }
    }

    private static final long GO_COOLDOWN_MS = 10_000;
    private static final long BLOCK_MAX_MS = 10_000;
    private static final long NPC_BLOCK_MS = 2000;
    private int blockId = Integer.MIN_VALUE;
    /** Bulucu yolda bir kez bu kutu icin durdu; ayni kutu icin tekrar durmaz. */
    private int skipBox = Integer.MIN_VALUE;
    private long blockSince, blockSeen;

    private final Context context;
    private final List<Ignored> ignored = new ArrayList<>();
    /** Varilip NPC bulunamayan sinyal yerleri (x, y, bitis): 60 sn tekrar gidilmez. */
    private final List<double[]> emptyZones = new ArrayList<>();
    /** Yok Say sonrasi yeni sinyal: bekleme ve 10 sn kilit atlanir. */
    private long goNowUntil;

    public boolean goNow() {
        return Context.now() < goNowUntil;
    }

    private double[] emptyNear(Location loc) {
        // Bos bulunan yer sadece isaret ayni yerdeyse (300) gecerli: isaret kaydiysa orada yeni NPC var.
        for (double[] z : emptyZones) if (z[2] > Context.now() && Math.hypot(z[0] - loc.getX(), z[1] - loc.getY()) < EMPTY_R) return z;
        return null;
    }
    private static final double EMPTY_R = 300;
    /** Olen NPC'lerin yeri (x, y, bitis), son 3: bulucunun isareti bir sure orada kalir, gidilmez. */
    private final List<double[]> deadZones = new ArrayList<>();

    private double deadR() {
        return context.config.LOCATOR.DEAD_R;
    }

    /** NPC oldu: DEAD_SEC sn bu yerin DEAD_R yakinindaki isarete gidilmez (oldurulen NPC'nin eski isareti). */
    public void npcDied(Location at) {
        if (at == null) return;
        deadZones.add(new double[]{at.getX(), at.getY(), Context.now() + context.config.LOCATOR.DEAD_SEC * 1000L});
        while (deadZones.size() > 3) deadZones.remove(0);
    }

    /**
     * Olu yerler: suresi dolan silinir; yakininda kesilecek gercek NPC gorulurse ya da canli isaret olumden sonra
     * yerden DEAD_R'den uzakta goruldu (isaret kaydi) ise erken silinir.
     */
    private void tickDead() {
        if (deadZones.isEmpty()) return;
        long now = Context.now();
        double r = deadR();
        // Gidilecek isaret (atlama korumasi 1 sn eski yeri tutar): olu yer ancak o da yerden ayrilinca silinir;
        // ham isaretle silinirse tutulan eski olu yere gidiliyordu.
        Location live = context.ping().orElse(null);
        Iterator<double[]> it = deadZones.iterator();
        while (it.hasNext()) {
            double[] z = it.next();
            if (z[2] < now) {
                it.remove();
                continue;
            }
            Location zl = Location.of(z[0], z[1]);
            if (killableNear(zl, r)) {
                Log.d("BULUCU", "olu yer " + Log.loc(zl) + " yakininda kesilecek NPC var, olu yer kaldirildi");
                it.remove();
            } else if (live != null && live.distanceTo(zl) > r) {
                Log.d("BULUCU", "isaret olu yerden " + Log.loc(zl) + " kaydi, olu yer kaldirildi");
                it.remove();
            }
        }
    }

    /** Isaret olen NPC'nin yerinde kalmis eski isaret mi. */
    private boolean deadNear(Location loc) {
        tickDead();
        return deadRaw(loc);
    }

    /** Olu yer testi, yan etkisiz (tickDead / ping cagirmaz): Context.readPing atlama korumasi kullanir. */
    private boolean deadRaw(Location loc) {
        if (loc == null || deadZones.isEmpty()) return false;
        long now = Context.now();
        double r = deadR();
        for (double[] z : deadZones) if (z[2] >= now && Math.hypot(z[0] - loc.getX(), z[1] - loc.getY()) < r) return true;
        return false;
    }

    /** Reddedilen / birakilan sinyal yerleri (x, y, bitis): 3 sn tekrar gidilmez. */
    private final List<double[]> rejected = new ArrayList<>();
    private static final long REJECT_MS = 3000;
    private static final double REJECT_R = 500;
    /** Son bulucu isi NPC bulamadan bitti: 1,5 sn bekleme kosulsuz (goNow haric). */
    private long failCooldown;

    private void reject(Location loc) {
        if (loc == null) return;
        rejected.add(new double[]{loc.getX(), loc.getY(), Context.now() + REJECT_MS});
    }

    private double[] rejectedNear(Location loc) {
        long now = Context.now();
        rejected.removeIf(z -> z[2] < now);
        for (double[] z : rejected) if (Math.hypot(z[0] - loc.getX(), z[1] - loc.getY()) < REJECT_R) return z;
        return null;
    }
    private static final long EMPTY_MS = 15_000;
    private long goCooldown;
    /** Isaret varken gidilmeme nedeni (her saniye loga) ve ne zamandan beri. */
    private String whyNot;
    private long whySince;
    /** Son bulucu isinin buldugu NPC: 10 sn bekleme sadece o NPC hala hedefteyse / yakindaysa gecerli. */
    private int foundId = -1;
    /** Bulucu "Birak" ile birakilan NPC: sinyal ona donerse yok sayilmis sayilmaz, geri donulur. */
    private int droppedId = -1;
    private Location pingAt;
    private long pingSince;

    /** Bekleme suruyor mu: bulunan NPC olduyse / kaybolduysa / birakildiysa yeni sinyale hemen gidilir. */
    private boolean foundStillHere() {
        if (foundId < 0) return false;
        Npc n = context.findNpc(foundId);
        if (n == null || n.isBlacklisted()) return false;
        Npc cur = context.targetNpc();
        // Dovus bulunan NPC'yi secip ona yaklasiyorsa da bekleme surer (saldiri hedefi yaklasirken henuz kurulmamis
        // olabiliyor: NPC 1500'den uzaksa bulucu ayni sinyale yeni is baslatip dovusu kesiyordu).
        return (cur != null && cur.getId() == foundId) || context.combatTargetId.getAsInt() == foundId
                || context.hero.distanceTo(n) < 1500;
    }

    public Locator(Context context) {
        this.context = context;
        context.deadSpot = this::deadRaw;
    }

    /** Harita degisti: onceki haritanin olu / bos / reddedilen yerleri silinir. */
    public void reset() {
        deadZones.clear();
        emptyZones.clear();
        rejected.clear();
        pingAt = null;
    }

    private int mapEpoch = Integer.MIN_VALUE;

    private void checkMap() {
        int e = context.mapEpoch();
        if (e == mapEpoch) return;
        if (mapEpoch != Integer.MIN_VALUE) {
            if (!deadZones.isEmpty() || !emptyZones.isEmpty() || !rejected.isEmpty())
                Log.d("BULUCU", "harita degisti, olu / bos / reddedilen isaret yerleri silindi");
            reset();
        }
        mapEpoch = e;
    }

    // ---------------------------------------------------------------- yok sayma

    /** Her tik: yok sayilanlarin konumu guncellenir, onlarin yanindaki bulucu sinyali (sahte NPC) atlanir. */
    public void tickIgnored() {
        long now = Context.now();
        checkMap();
        tickKillers(now);
        Iterator<Ignored> it = ignored.iterator();
        while (it.hasNext()) {
            Ignored i = it.next();
            if (now > i.until) {
                Log.i("YOK SAY", i.name + " #" + i.id + " suresi doldu");
                it.remove();
                continue;
            }
            Npc n = context.findNpc(i.id);
            if (n != null) {
                i.loc = Location.of(n);
                context.bans.ban(n, Bans.Owner.IGNORE, i.until - now);
            }
        }
        emptyZones.removeIf(z -> z[2] < now);
        if (ignored.isEmpty()) return;
        for (Npc n : context.ents.getNpcs()) {
            if (!Context.isFake(n)) continue;
            Ignored i = nearIgnored(n);
            if (i == null) continue;
            block(n, now);
            Log.every("fake" + i.id, 5000, "YOK SAY", "bulucu sinyali " + Log.loc(n) + " yok sayilan " + i.name
                    + " #" + i.id + " yaninda, gidilmiyor");
        }
    }

    public boolean isIgnored(int id) {
        for (Ignored i : ignored) if (i.id == id) return true;
        return false;
    }

    private Ignored nearIgnored(eu.darkbot.api.game.other.Locatable l) {
        for (Ignored i : ignored) if (i.loc.distanceTo(l) < IGNORE_ZONE) return i;
        return null;
    }

    /** Baskasinin vurdugu hedef: 60 sn yok sayilir, gemi uzaklasir. */
    public PlusModule.Job checkIgnore() {
        if (!context.config.LOCATOR.IGNORE) return null;
        Npc cur = context.targetNpc();
        if (cur == null || isIgnored(cur.getId())) return null;
        NpcInfo info = cur.getInfo();
        if (Context.ignoresAttacked(cur) || info.hasExtraFlag(BotPlusFlag.STEAL)) return null;
        if (cur.getLockType() == Lockable.Lock.OWNED && context.shooting(cur)) return null;
        if (!context.attackedByOthers(cur)) return null;
        // NPC Steal bu NPC'yi calacaksa yok sayilmaz (eskiden yok say uzaklasiyor, steal geri donuyordu).
        if (context.stealCan.test(cur)) return null;

        Player by = context.otherAttacker(cur);
        long now = Context.now();
        ignored.add(new Ignored(cur, now + IGNORE_MS));
        context.bans.ban(cur, Bans.Owner.IGNORE, IGNORE_MS);
        context.attack.setBlacklisted(IGNORE_MS);
        if (context.hero.isAttacking(cur)) context.attack.stopAttack(); // uzaklasirken ates surmesin
        context.hero.setLocalTarget(null);
        context.attack.setTarget(null); // yok sayilan NPC hedefte kalmasin (bulucu "NPC bitiriliyor" demesin)
        Log.i("YOK SAY", Log.npc(cur) + " baskasi vuruyor (" + (by == null ? "PET" : Log.ship(by))
                + "), 60 sn yok sayiliyor, uzaklasiliyor");
        return new Evade(Location.of(cur), "YOK SAY", "vurulan NPC'den uzaklaşılıyor", PlusModule.P_IGNORE);
    }

    /** Uzaklasma: PET koruma modunda, NPC ve dusmanlardan uzak bir noktaya kisa adim. */
    public final class Evade implements PlusModule.Job {
        private final Location target;
        private long start = Context.now();
        private long lastTick = Context.now();
        private final String tag, status;
        private final int priority;

        public Evade(Location from, String tag, String status, int priority) {
            this.priority = priority;
            this.tag = tag;
            this.status = status;
            Location best = context.safePoint(EVADE_STEP, from);
            double bestScore = Math.min(from.distanceTo(best), danger(best));
            this.target = best;
            Log.i(tag, "uzaklasma noktasi " + Log.loc(target) + " (en yakin tehlike " + (int) bestScore + ")");
        }

        private double danger(Location p) {
            double d = Double.MAX_VALUE;
            for (Npc n : context.ents.getNpcs()) if (n.isValid() && !Context.isFake(n)) d = Math.min(d, n.distanceTo(p));
            for (Player pl : context.ents.getPlayers())
                if (context.isEnemyPlayer(pl)) d = Math.min(d, pl.distanceTo(p));
            return d;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String name() {
            return tag;
        }

        @Override
        public String status() {
            return status;
        }

        private long arrivedAt;

        @Override
        public boolean tick() {
            long t = Context.now();
            // Bot tiklemedigi sure (durduruldu / olu) sayilmaz.
            if (t - lastTick > 2000) start += t - lastTick;
            lastTick = t;
            context.pet.setEnabled(true);
            context.hero.setRoamMode();
            if (t - start > EVADE_TIMEOUT_MS) return false;
            if (context.hero.distanceTo(target) < 150) {
                // Yok Say sonrasi: DarkBot'a birakmadan bulucunun yeni sinyali beklenir (en fazla 4 sn), sinyal gelince
                // ya da kesilecek baska NPC gorulunce biter; BotPlus hemen ona gider.
                if (priority != PlusModule.P_IGNORE || !context.config.LOCATOR.GO) return false;
                long now = Context.now();
                if (arrivedAt == 0) arrivedAt = now;
                Optional<Location> ping = context.ping();
                boolean newPing = ping.isPresent() && nearIgnored(ping.get()) == null && emptyNear(ping.get()) == null
                        && context.hero.distanceTo(ping.get()) >= NEAR_LOC;
                if (newPing) goNowUntil = now + 3000;
                if (newPing || realNpcNear(Location.of(context.hero), 2000) != null || now - arrivedAt > 4000) {
                    Log.i(tag, newPing ? "yeni bulucu sinyali " + Log.loc(ping.get()) + ", ona gidiliyor"
                            : now - arrivedAt > 4000 ? "4 sn yeni sinyal gelmedi" : "yakinda kesilecek NPC var");
                    return false;
                }
                return true;
            }
            context.moveTo(target);
            Log.dEvery("evade" + tag, 3000, tag, "uzaklasiliyor " + Log.loc(target) + " " + (int) context.hero.distanceTo(target)
                    + " | " + context.dangerInfo(context.hero));
            return true;
        }

        @Override
        public void end(String why) {
            Log.i(tag, "uzaklasildi " + Log.loc(context.hero) + " (" + why + ") | " + context.dangerInfo(context.hero));
        }
    }

    // ---------------------------------------------------------------- Oldureni Hatirla

    private static final int KILLER_DIST = 2000;
    private static final long KILLER_COOLDOWN_MS = 5000;
    private final java.util.Map<String, Long> killers = new java.util.HashMap<>();
    private java.time.Instant lastDeath;
    private long killerCooldown;

    /**
     * Olumu kaydeder: oldureni hatirlar. Her tik (bot calisirken ve dururken) cagrilir.
     * Gemi oluyken DarkBot BotPlus'i tiklemiyor, olum canlaninca gorulur; bu yuzden sure degil
     * BotPlus'in acildigi an karsilastirilir.
     */
    public void trackDeath() {
        java.time.Instant t = context.repair.getLastDeathTime();
        if (t == null || t.equals(lastDeath)) return;
        lastDeath = t;
        String name = context.repair.getLastDestroyerName();
        if (t.toEpochMilli() < context.startedAt) return; // bot acilmadan onceki olum
        Stats.count("olum");
        Log.i("OLUM", "gemi patladi, olduren: " + (name == null || name.isEmpty() ? "bilinmiyor" : name)
                + " | " + (Context.now() - t.toEpochMilli()) / 1000 + " sn once");
        context.deaths.report(name, t.toEpochMilli());
        if (!context.config.PROTECT.KILLER.ENABLED || name == null || name.isEmpty()) return;
        if (isNpcName(name)) {
            Log.i("DUSMAN", name + " bir NPC, hatirlanmiyor (sadece oyuncular hatirlanir)");
            return;
        }
        killers.put(name, Context.now() + context.config.PROTECT.KILLER.MINUTES * 60_000L);
        context.killers.add(name);
        eu.darkbot.api.game.other.Locatable at = null;
        try {
            at = context.repair.getLastDeathLocation();
        } catch (RuntimeException ignored) {
        }
        int map = -1;
        try {
            map = context.star.getCurrentMap() == null ? -1 : context.star.getCurrentMap().getId();
        } catch (RuntimeException ignored) {
        }
        context.killerAt(name, at, map);
        Log.i("DUSMAN", name + " seni patlatti, " + context.config.PROTECT.KILLER.MINUTES + " dk hatirlanacak"
                + (at == null ? "" : ", olunen yer " + Log.loc(at) + " bolgesine gidilmeyecek"));
    }

    /** Gorulen NPC adlari (sade, kucuk harf): olduren NPC mi oyuncu mu ayirmak icin. */
    private final java.util.Set<String> seenNpcNames = new java.util.HashSet<>();
    private eu.darkbot.api.config.ConfigSetting<java.util.Map<String, ?>> npcInfos;
    private boolean npcInfosTried;

    private static String simple(String raw) {
        String d = displayName(raw);
        return d == null ? null : d.toLowerCase(java.util.Locale.ROOT);
    }

    /** Adlari kaydedilmis NPC id'leri: her tik her NPC icin ad yeniden islenmez. */
    private final java.util.Set<Integer> seenNpcIds = new java.util.HashSet<>();

    private void rememberNpcNames() {
        if (seenNpcNames.size() > 1000 || seenNpcIds.size() > 5000) {
            seenNpcNames.clear();
            seenNpcIds.clear();
        }
        for (Npc n : context.ents.getNpcs()) {
            boolean fake = Context.isFake(n); // bulucu NPC'si (adi = aranan tur): kimligi sabit degil, her tik bakilir
            if (!fake && seenNpcIds.contains(n.getId())) continue;
            if (n.getInfo() != null) seenNpcNames.add(simple(n.getInfo().getName()));
            String user = n.getEntityInfo() == null ? null : n.getEntityInfo().getUsername();
            if (user == null || user.isEmpty()) continue; // ad henuz yuklenmedi: sonraki tik tekrar bakilir
            seenNpcNames.add(simple(user));
            if (!fake) seenNpcIds.add(n.getId());
        }
        seenNpcNames.remove(null);
    }

    /** Ad DarkBot NPC tablosunda ya da gorulen NPC'lerde var mi. */
    private boolean isNpcName(String name) {
        String s = simple(name);
        if (s == null) return false;
        rememberNpcNames();
        if (seenNpcNames.contains(s)) return true;
        if (!npcInfosTried) {
            npcInfosTried = true;
            try {
                npcInfos = context.pluginApi.requireAPI(eu.darkbot.api.managers.ConfigAPI.class).requireConfig("loot.npc_infos");
            } catch (Throwable t) {
                Log.i("DUSMAN", "NPC tablosu okunamadi: " + t);
            }
        }
        try {
            java.util.Map<String, ?> m = npcInfos == null ? null : npcInfos.getValue();
            if (m != null) for (String k : m.keySet()) if (s.equals(simple(k))) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Bulucu sinyali / NPC kisa sure yok sayilir (Bans: baskasinin yok saymasi kisaltilmaz). */
    private void block(Npc n, long now) {
        context.bans.ban(n, Bans.Owner.LOCATOR_BLOCK, 1500);
    }

    /** Bu NPC hatirlanan dusman ya da yok sayilan NPC bolgesinde mi (tutma kaldirilirken korunur). */
    public boolean blockedZone(Npc n) {
        if (nearIgnored(n) != null) return true;
        return context.config.PROTECT.KILLER.ENABLED && context.killerZone(n, KILLER_DIST + 500) != null;
    }

    /**
     * Hatirlanan oldurenler: sure dolanlar silinir, yanindaki NPC'ler secilmez (DarkBot oraya gitmesin).
     */
    private void tickKillers(long now) {
        // Gorulen NPC adlari sadece Dusmani Hatirla acikken gerekli (olduren NPC mi oyuncu mu).
        if (context.config.PROTECT.KILLER.ENABLED) rememberNpcNames();
        if (!context.config.PROTECT.KILLER.ENABLED && !killers.isEmpty()) {
            Log.i("DUSMAN", "Dusmani Hatirla kapali, " + killers.size() + " hatirlanan dusman silindi");
            killers.clear();
        }
        // "Patlatan + Saldiran": sana ates eden oyuncu, ates kestikten sonra hatirlanir (ates ederken Dusman Saldirinca
        // ayari gecerli; hatirlama sonrasi yaklasinca uzaklasilir, yanindaki NPC secilmez).
        if (context.config.PROTECT.KILLER.ENABLED && context.config.REMEMBER_ATTACKERS) {
            for (String name : context.quietAttackers(Context.ESCAPE_MEMORY_MS, Context.THREAT_MEMORY_MS)) {
                if (killers.containsKey(name)) continue;
                killers.put(name, now + context.config.PROTECT.KILLER.MINUTES * 60_000L);
                Log.i("DUSMAN", name + " sana saldirdi, " + context.config.PROTECT.KILLER.MINUTES + " dk hatirlanacak");
            }
        }
        if (killers.isEmpty()) {
            context.killers.clear();
            context.trackKillers();
            return;
        }
        killers.entrySet().removeIf(e -> {
            if (e.getValue() >= now) return false;
            Log.i("DUSMAN", e.getKey() + " artik hatirlanmiyor (sure doldu)");
            return true;
        });
        context.killers.retainAll(killers.keySet());
        context.killers.addAll(killers.keySet());
        context.trackKillers();
        if (!context.config.PROTECT.KILLER.ENABLED) return;
        for (Npc n : context.ents.getNpcs()) {
            // Bulucunun sahte NPC'si de (DarkBot kendisi ona gider) yok sayilir.
            boolean fake = n.isValid() && Context.isFake(n);
            if (!fake && !Context.killable(n)) continue;
            String k = context.killerZone(n, KILLER_DIST + 500);
            if (k == null) continue;
            block(n, now);
            Log.every("killernpc" + n.getId(), 10_000, "DUSMAN", (fake ? "bulucu sinyali " + Log.loc(n) : Log.npc(n))
                    + " secilmiyor: hatirlanan dusman " + k + " yakininda");
        }
    }

    /** Hatirlanan olduren 2000 icindeyse ondan uzaklasilir. */
    public PlusModule.Job checkKiller() {
        if (!context.config.PROTECT.KILLER.ENABLED || killers.isEmpty() || Context.now() < killerCooldown) return null;
        long now = Context.now();
        for (Player p : context.ents.getPlayers()) {
            if (p.getId() == context.hero.getId() || !p.isValid() || p.getEntityInfo() == null) continue;
            if (!killers.containsKey(p.getEntityInfo().getUsername())) continue;
            double d = context.hero.distanceTo(p);
            if (d > KILLER_DIST) continue;
            killerCooldown = now + KILLER_COOLDOWN_MS;
            context.attack.stopAttack();
            context.attack.setTarget(null);
            context.hero.setLocalTarget(null);
            Log.i("DUSMAN", Log.ship(p) + " " + (int) d + " uzakta, uzaklasiliyor");
            return new Evade(Location.of(p), "DUSMAN", "hatırlanan düşmandan uzaklaşılıyor", PlusModule.P_KILLER);
        }
        return null;
    }

    // ---------------------------------------------------------------- bulucu

    /** Bulucu sinyaline hemen gidilmesi gerekiyorsa isi doner. */
    public PlusModule.Job checkGo() {
        whyNot = null;
        PlusModule.Job job = checkGo0();
        long now = Context.now();
        // Sadece gemi bostayken (hedef yok) yazilir: dovus sirasinda her saniye log olmasin.
        if (job != null || whyNot == null || context.targetNpc() != null) {
            whySince = 0;
            return job;
        }
        if (whySince == 0) whySince = now;
        Log.every("gowhy", 1000, "BULUCU", "isaret var, gidilmiyor: " + whyNot + " (" + (now - whySince) / 1000 + " sn)");
        return null;
    }

    private PlusModule.Job no(String why) {
        whyNot = why;
        return null;
    }

    private PlusModule.Job checkGo0() {
        BotPlusConfig.Locator c = context.config.LOCATOR;
        if (!c.GO) return null;
        // "Sadece Kutu" modunda NPC sinyaline gidilmez.
        if (context.config.COMBAT.ENABLED && context.botMode() == BotPlusConfig.BotMode.BOX) return null;
        Optional<Location> ping = context.ping();
        if (!ping.isPresent()) return null;
        Location loc = ping.get();
        if (pingAt == null || pingAt.distanceTo(loc) > 500) pingSince = Context.now();
        pingAt = loc;
        if (context.hero.distanceTo(loc) < NEAR_LOC) return null;
        if (Context.now() < goCooldown && foundStillHere()) return no("bulunan NPC hala hedefte / yakinda");
        if (Context.now() < failCooldown && !goNow()) return no("bulucu isi NPC bulmadan bitti, 1,5 sn bekleniyor");
        double[] rej = rejectedNear(loc);
        if (rej != null) return no("az once birakilan sinyal yeri (" + Math.max(0, (long) (rej[2] - Context.now()) / 1000) + " sn)");

        Ignored ig = nearIgnored(loc);
        if (ig != null) return no("yok sayilan " + ig.name + " yaninda");
        double[] empty = emptyNear(loc);
        if (empty != null) return no("az once bos bulunan yerde (" + Math.max(0, (long) (empty[2] - Context.now()) / 1000) + " sn)");
        if (deadNear(loc)) return no("olen NPC'nin yerindeki eski isaret");
        // Sinyalin yanindaki NPC'yi dovus su an alabiliyorsa beklenir; alamiyorsa (mesafe siniri vb.) isarete gidilir.
        Npc atSignal = realNpcNear(loc);
        if (atSignal != null && (!context.config.COMBAT.ENABLED || context.combatCanTake.test(atSignal)))
            return no("sinyaldeki NPC'yi dovus aliyor: " + Log.npc(atSignal));
        String killer = context.config.PROTECT.KILLER.ENABLED ? context.killerZone(loc, KILLER_DIST + 500) : null;
        if (killer != null) return no("hatirlanan dusman " + killer + " yakininda");
        String zone = context.inDangerZone(loc);
        if (zone != null) return no("tehlikeli bolge: " + zone);
        Player threat = context.enemyThreat();
        if (threat != null) return no("dusman " + Log.ship(threat) + " " + (int) threat.distanceTo(context.hero) + " uzakta saldiriyor / yaklasiyor");
        // Yakinda kutu ya da kesilecek NPC varken bulucuya gidilmez; DarkBot almazsa 10 sn sonra gidilir.
        // Once NPC: sinyal kutudan once gelir (kutu sinyali bekletmez); once kutu modunda yakindaki kutu beklenir.
        eu.darkbot.api.game.entities.Box box = context.collecting() && boxBeforeLocator()
                ? context.nearestBox(Math.max(1000, context.collectRadius())) : null;
        Npc near = context.targetNpc() == null ? realNpcNear(Location.of(context.hero), 1500) : null;
        // Tam kontrolde: yakindaki NPC'yi dovus hemen alabiliyorsa beklenir, alamiyorsa beklenmez (10 sn bosa gidiyordu).
        if (near != null && context.config.COMBAT.ENABLED) {
            if (context.combatCanTake.test(near)) return no("yakinda kesilecek NPC var, dovus aliyor: " + Log.npc(near));
            near = null;
        }
        int blockId = box != null ? box.getId() : near != null ? near.getId() : Integer.MIN_VALUE;
        if (goNow()) blockId = Integer.MIN_VALUE; // Yok Say sonrasi: hemen yeni sinyale
        if (blockId != Integer.MIN_VALUE) {
            long now = Context.now();
            // Sure, engel degisse de (kutu / NPC sirayla) surer; sadece 3 sn engel yoksa sifirlanir.
            if (this.blockId == Integer.MIN_VALUE || now - blockSeen > 3000) blockSince = now;
            this.blockId = blockId;
            blockSeen = now;
            long max = box != null ? BLOCK_MAX_MS : NPC_BLOCK_MS; // NPC icin 2 sn (DarkBot almazsa beklenmez)
            if (now - blockSince < max)
                return no("yakinda " + (box != null ? "kutu var: " + box.getTypeName() + " " + (int) context.hero.distanceTo(box)
                        + ", once kutu" : "kesilecek NPC var: " + Log.npc(near) + " " + (int) context.hero.distanceTo(near)
                        + ", DarkBot onu alacak"));
            Log.every("goblockmax", 30_000, "BULUCU", (box != null ? "kutu " + box.getTypeName() : "NPC " + Log.npc(near))
                    + " " + max / 1000 + " sn'dir alinmadi, bulucuya gidiliyor");
        } else if (Context.now() - blockSeen > 3000) this.blockId = Integer.MIN_VALUE;
        String bad = badPing(loc);
        if (bad != null) return no(bad);
        // Atlayan sinyalin 1 sn beklemesi Context.ping()'te (burada ikinci bekleme yok).

        Npc cur = context.targetNpc();
        if (cur != null) {
            if (!c.DROP) return null;
            // Ayni turu (bulucunun aradigi tur) baska bir aynisi icin birakmanin anlami yok; bize vuran da birakilmaz.
            String pingType = pingType(loc);
            if (pingType != null && cur.getInfo() != null && sameType(pingType, cur.getInfo().getName())) return null;
            if (cur.isAttacking(context.hero)) return null;
            // Vurdugumuz (cani dusmus, ates ediliyor) hedef birakilmaz.
            if (context.shooting(cur) && Context.hp(cur) < 1.0) {
                Log.every("godropshoot", 5000, "BULUCU", "sinyal " + Log.loc(loc) + " var ama " + Log.npc(cur)
                        + " vuruluyor (can %" + Math.round(Context.hp(cur) * 100) + "), birakilmiyor");
                return null;
            }
            // Sinyal bir anlik atlayabiliyor: 2 sn ayni yerde durmali ve hedeften 1500+ uzakta olmali.
            if (Context.now() - pingSince < 2000 || cur.distanceTo(loc) < 1500) return null;
            double hp = Context.hp(cur);
            if (hp <= c.DROP_HP / 100.0) {
                Log.every("godrop", 5000, "BULUCU", "sinyal " + Log.loc(loc) + " var ama " + Log.npc(cur)
                        + " bitiriliyor (can <= %" + c.DROP_HP + ")");
                return null;
            }
            Log.i("BULUCU", Log.npc(cur) + " birakildi (can %" + Math.round(hp * 100) + " > %" + c.DROP_HP + "), 30 sn yok sayiliyor");
            context.bans.ban(cur, Bans.Owner.DROP, 30_000); // Go hemen ayni NPC'yi "bulundu" deyip bitmesin
            droppedId = cur.getId();
            context.attack.stopAttack();
            context.attack.setTarget(null);
            context.hero.setLocalTarget(null);
        } else if (context.attack.hasTarget()) {
            // Hedef bulucunun sahte NPC'si: DarkBot zaten gidiyor, biz yine de dogrudan gideriz.
            context.attack.setTarget(null);
        }
        Log.i("BULUCU", "sinyal " + Log.loc(loc) + " " + (int) context.hero.distanceTo(loc) + " uzakta, hemen gidiliyor"
                + " | " + context.dangerInfo(context.hero));
        Stats.count("bulucu");
        return new Go(loc);
    }

    /** Dolasma sinyale dogru gidebilir mi (az once bos bulunan / yok sayilan / kesilmeyen sinyal degil). */
    public boolean pingWorthGoing(Location loc) {
        return emptyNear(loc) == null && nearIgnored(loc) == null && !deadNear(loc) && rejectedNear(loc) == null
                && badPing(loc) == null && context.inDangerZone(loc) == null; // dusman yuzunden birakilan yere geri donulmez
    }

    private static double segDist(eu.darkbot.api.game.other.Locatable a, eu.darkbot.api.game.other.Locatable c,
                                  eu.darkbot.api.game.other.Locatable b) {
        double dx = c.getX() - a.getX(), dy = c.getY() - a.getY();
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((b.getX() - a.getX()) * dx + (b.getY() - a.getY()) * dy) / len));
        return Math.hypot(a.getX() + t * dx - b.getX(), a.getY() + t * dy - b.getY());
    }

    /** Kutu bulucudan once mi (Combat Plus kapaliyken eski davranis: DarkBot toplar). */
    private boolean boxBeforeLocator() {
        return !context.config.COMBAT.ENABLED || context.botMode() == BotPlusConfig.BotMode.BOX_NPC;
    }

    private static final String UNKNOWN = "sinyalin NPC turu bilinmiyor";

    /** Sinyaldeki NPC turu (DarkBot'un sahte NPC'si), yoksa bulucunun secili turu. */
    private String pingType(Location loc) {
        for (Npc n : context.ents.getNpcs())
            if (Context.isFake(n) && n.distanceTo(loc) < 100 && n.getInfo() != null) return n.getInfo().getName();
        return context.locatorPick;
    }

    /** Ekranda NPC adi: "( UberKristallon )" -> "UberKristallon", "..::{ Boss Lordakia }::.." -> "Boss Lordakia". */
    private static final java.util.regex.Pattern EDGE_JUNK = java.util.regex.Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    private static final java.util.regex.Pattern NON_ALNUM = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]");

    public static String displayName(String raw) {
        if (raw == null) return null;
        String n = EDGE_JUNK.matcher(raw).replaceAll("").trim();
        return n.isEmpty() ? null : n;
    }

    /** Turkce yonelme eki: UberKristallon'a, Sibelonit'e, Lordakia'ya. */
    public static String dative(String name) {
        String vowels = "aeıioöuüAEIİOÖUÜ";
        char lastV = 'a';
        for (int i = name.length() - 1; i >= 0; i--) {
            if (vowels.indexOf(name.charAt(i)) >= 0) {
                lastV = Character.toLowerCase(name.charAt(i));
                break;
            }
        }
        boolean back = "aıou".indexOf(lastV) >= 0;
        boolean endsVowel = vowels.indexOf(name.charAt(name.length() - 1)) >= 0;
        return "'" + (endsVowel ? (back ? "ya" : "ye") : (back ? "a" : "e"));
    }

    /** Bulucunun aradigi turun tablo onceligi (sinyaldeki sahte NPC ya da secili tur), bilinmiyorsa null. */
    private Integer pickPriority(Location loc) {
        for (Npc n : context.ents.getNpcs())
            if (Context.isFake(n) && n.distanceTo(loc) < 100 && n.getInfo() != null) return n.getInfo().getPriority();
        NpcInfo pick = context.locatorPickInfo;
        return pick == null ? null : pick.getPriority();
    }

    private static boolean sameType(String a, String b) {
        if (a == null || b == null) return false;
        String x = NON_ALNUM.matcher(a).replaceAll("").toLowerCase(java.util.Locale.ROOT);
        String y = NON_ALNUM.matcher(b).replaceAll("").toLowerCase(java.util.Locale.ROOT);
        return !x.isEmpty() && (x.equals(y) || x.contains(y) || y.contains(x));
    }

    /** Son badPing nedeni kesin mi (sinyalin / bulucunun turu kesilmiyor): Go sadece bunda birakir. */
    private boolean badHard;

    /** Bulucu sinyalinin turu (sahte NPC'nin bilgisi) kesilecek listede degilse nedeni doner. */
    private String badPing(Location loc) {
        badHard = false;
        Npc fake = null;
        for (Npc n : context.ents.getNpcs())
            if (Context.isFake(n) && n.distanceTo(loc) < 100) fake = n;
        if (fake == null) {
            // DarkBot sahte NPC'yi bazen bir iki tik gec koyuyor / sinyalden kaymis: bulucu zaten kesilecek turleri
            // ariyor, beklenmeden gidilir. Sadece sinyalde kesilmeyen bir gercek NPC varsa gidilmez.
            eu.darkbot.api.config.types.NpcInfo pick = context.locatorPickInfo;
            if (pick != null && !pick.getShouldKill()) {
                badHard = true;
                return "bulucu turu (" + context.locatorPick + ") kesilecek listede degil";
            }
            // Bulucunun turu kesilecek: sinyalin yanindaki listede olmayan / yok sayilan NPC sinyali gecersiz kilmaz.
            if (pick != null) return null;
            // Sinyalin yaninda kesilecek bir NPC varsa sinyal gecerli (Uber NPC'lerin yaninda hep Boss dolasiyor: en
            // yakin NPC'ye bakilinca sinyal yarida birakilip geri donuluyordu).
            if (killableNear(loc, 700)) return null;
            Npc real = context.nearestNpc(loc);
            if (real != null && real.distanceTo(loc) < 700 && real.getInfo() != null && !real.getInfo().getShouldKill())
                return "sinyaldeki NPC kesilecek listede degil (" + Log.npc(real) + ")";
            if (real != null && real.distanceTo(loc) < 700 && real.isBlacklisted() && real.getId() != droppedId)
                return UNKNOWN + " (sinyalde yok sayilmis NPC: " + Log.npc(real) + ")";
            return null;
        }
        NpcInfo i = fake.getInfo();
        if (i == null) return UNKNOWN;
        if (!i.getShouldKill()) {
            badHard = true;
            return i.getName() + " kesilecek listede degil";
        }
        return null;
    }

    /** Konumun yakininda kesilecek (tabloda kesiliyor, yok sayilmamis) gercek NPC var mi. */
    private boolean killableNear(Location loc, double dist) {
        for (Npc n : context.ents.getNpcs())
            if (Context.killable(n) && !n.isBlacklisted() && n.distanceTo(loc) < dist) return true;
        return false;
    }

    private Npc realNpcNear(Location loc) {
        return realNpcNear(loc, NEAR_LOC);
    }

    private Npc realNpcNear(Location loc, double dist) {
        for (Npc n : context.ents.getNpcs()) {
            if (!context.darkBotWouldKill(n) || context.move.getClosestDistance(n) >= 500) continue;
            if (n.distanceTo(loc) < dist) return n;
        }
        return null;
    }

    private static final int ARRIVED = 600;
    private static final long ARRIVED_MS = 2000;

    /** Teshis icin: sinyale en yakin gercek NPC ve neden secilmedigi. */
    private String nearInfo(Location loc) {
        Npc best = null;
        double bd = Double.MAX_VALUE;
        for (Npc n : context.ents.getNpcs()) {
            if (!n.isValid() || Context.isFake(n)) continue;
            double d = n.distanceTo(loc);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        if (best == null) return "yok";
        String why = best.getInfo() == null || !best.getInfo().getShouldKill() ? " (listede kesilmiyor)"
                : best.isBlacklisted() ? " (yok sayilmis)" : "";
        return Log.npc(best) + " sinyale " + (int) bd + why;
    }

    public final class Go implements PlusModule.Job {
        private long arrivedAt;
        private Location loc;
        private long lastPing = Context.now();
        private long start = Context.now();
        private long lastTick = Context.now();
        /** Onceki hedef ve ondan ayrilma ani: 5 sn icinde ona geri atlanmaz (A -> B -> A). */
        private Location prevLoc;
        private long leftAt;
        /** Isaretin hedef yerde (loc) son goruldugu an: A -> B -> A korumasi sadece B yeni gorulduyse. */
        private long lastHere = Context.now();
        /** Is kutu icin bitti (sinyal yeri reddedilmez). */
        private boolean forBox;

        public Go(Location loc) {
            this.loc = loc;
        }

        private long cooldown = GO_COOLDOWN_MS;
        private int found = -1;
        private long jumpSince;

        @Override
        public String name() {
            return "BULUCU";
        }

        @Override
        public int priority() {
            return PlusModule.P_LOCATOR;
        }

        @Override
        public String status() {
            long sec = (long) Math.ceil(context.hero.distanceTo(loc) / Math.max(1, context.hero.getSpeed()));
            String name = displayName(pingType(loc));
            return (name == null ? "bulucu NPC'sine gidiliyor" : name + dative(name) + " gidiliyor") + " (" + sec + " sn)";
        }

        private void switchTo(Location np, long now) {
            prevLoc = loc;
            leftAt = now;
            loc = np;
            lastHere = now;
            jumpSince = 0;
        }

        private void setFound(int id) {
            found = id;
            context.locatorFoundId = id;
            context.locatorFoundAt = Context.now();
        }

        /** Isaret baska yere atlarsa eski hedefin bu kadar sure gorunmemesi beklenir (eskiden 1 sn). */
        private static final long JUMP_SWITCH_MS = 3000;
        /** A -> B -> A gidip gelmesinde birakilan hedefe bu sure icinde donulmez (eskiden 5 sn). */
        private static final long PINGPONG_MS = 15_000;

        @Override
        public boolean tick() {
            long now = Context.now();
            // Bot tiklemedigi sure (durduruldu / olu) zaman asimina ve loglara sayilmaz.
            if (now - lastTick > 2000) start += now - lastTick;
            lastTick = now;
            Optional<Location> ping = context.ping();
            if (ping.isPresent()) {
                Location np = ping.get();
                if (loc.distanceTo(np) > 3000) {
                    // Sinyal baska yere atladi.
                    if (deadNear(np)) {
                        // Olen NPC'nin yerindeki eski isaret: gecilmez, sinyal yenilenmis sayilmaz.
                        jumpSince = 0;
                        Log.every("godeadjump", 3000, "BULUCU", "sinyal olen NPC'nin yerine atladi " + Log.loc(np)
                                + ", gecilmiyor, hedef " + Log.loc(loc));
                    } else if (prevLoc != null && prevLoc.distanceTo(np) < 1500 && now - leftAt < PINGPONG_MS && now - lastHere < JUMP_SWITCH_MS) {
                        // A -> B -> A: isaret iki NPC arasinda gidip geliyor (ayni turden iki NPC). 15 sn icinde
                        // birakilan hedefe geri donulmez; B'nin isareti 3 sn'dir yoksa donulur. Eskiden 5 sn / 1 sn:
                        // gemi 4-5 sn'de bir yon degistirip haritada zikzak ciziyordu (yolculuk 2 kat uzuyordu).
                        jumpSince = 0;
                        lastPing = now;
                        Log.every("goback", 3000, "BULUCU", "sinyal az once birakilan yere geri atladi " + Log.loc(np)
                                + ", eski hedefe devam " + Log.loc(loc));
                    } else if (context.hero.distanceTo(np) < context.hero.distanceTo(loc) * 0.7) {
                        Log.i("BULUCU", "sinyal atladi " + Log.loc(loc) + " -> " + Log.loc(np) + " ("
                                + (int) context.hero.distanceTo(np) + "), belirgin yakin, ona geciliyor");
                        switchTo(np, now);
                        lastPing = now;
                    } else {
                        if (jumpSince == 0) {
                            jumpSince = now;
                            Log.i("BULUCU", "sinyal atladi " + Log.loc(loc) + " -> " + Log.loc(np) + " ("
                                    + (int) context.hero.distanceTo(np) + "), yakin degil, eski hedefe devam");
                        }
                        if (now - jumpSince > JUMP_SWITCH_MS) {
                            Log.i("BULUCU", "eski sinyal " + JUMP_SWITCH_MS / 1000 + " sn'dir yok, yenisine geciliyor " + Log.loc(np));
                            switchTo(np, now);
                        }
                        lastPing = now;
                    }
                } else {
                    loc = np;
                    lastHere = now;
                    jumpSince = 0;
                    lastPing = now;
                }
            } else if (context.pingFrozen.getAsBoolean()) {
                // Bulucu tazelemesi tur degistiriyor: isaret gizli, kayip sayilmaz.
                lastPing = now;
            } else if (now - lastPing > PING_LOST_MS) {
                Log.i("BULUCU", "sinyal kayboldu");
                return false;
            }
            if (nearIgnored(loc) != null) {
                Log.i("BULUCU", "sinyal yok sayilan NPC'nin yaninda, birakildi");
                return false;
            }
            if (deadNear(loc)) {
                Log.i("BULUCU", "sinyal olen NPC'nin yerinde kalmis, birakildi");
                return false;
            }
            String killer = context.config.PROTECT.KILLER.ENABLED ? context.killerZone(loc, KILLER_DIST + 500) : null;
            if (killer != null) {
                Log.i("BULUCU", "sinyal hatirlanan dusman " + killer + " yakininda, birakildi");
                return false;
            }
            Player threat = context.enemyThreat();
            if (threat != null) {
                Log.i("BULUCU", "dusman " + Log.ship(threat) + " saldiriyor / yaklasiyor, bulucu birakildi");
                // Sinyal bolgesi ve dusmanin yeri 60 sn yasak: dusman bir an uzaklasinca ayni sinyale donulmesin.
                context.dangerZone(loc, 2500, 60_000, "bulucu dusman yuzunden birakildi (" + Log.name(threat) + ")");
                context.dangerZone(threat, 2500, 60_000, "dusman " + Log.name(threat));
                return false;
            }
            String zone = context.inDangerZone(loc);
            if (zone != null) {
                Log.i("BULUCU", "sinyal tehlikeli bolgede (" + zone + "), bulucu birakildi");
                return false;
            }
            // Sahte NPC listeden zaman zaman cikiyor: tur bilinmiyorsa devam edilir, sadece tur kesilmeyen bir NPC ise birakilir.
            String bad = badPing(loc);
            if (bad != null && badHard) {
                Log.i("BULUCU", "sinyal birakildi: " + bad);
                return false;
            }
            // Yoldaki kutu: "NPC'ye giderken yoldaki kutulari al" acikken ya da once kutu modunda.
            BotPlusConfig.BoxWay way = context.config.BOX_WAY;
            double boxR = boxBeforeLocator() || way == BotPlusConfig.BoxWay.NEARBY ? context.collectRadius()
                    : way == BotPlusConfig.BoxWay.ON_ROUTE ? 800 : 0;
            eu.darkbot.api.game.entities.Box box = context.collecting() && boxR > 0 ? context.nearestBox(Math.min(800, boxR)) : null;
            // Yoldakileri Al: sadece sinyale giden yolun 300 yakinindaki kutu (geri donulmez).
            if (box != null && way == BotPlusConfig.BoxWay.ON_ROUTE && !boxBeforeLocator()
                    && (box.distanceTo(loc) > context.hero.distanceTo(loc) || segDist(context.hero, loc, box) > 300)) box = null;
            if (box != null && box.getId() != skipBox) {
                skipBox = box.getId();
                Log.i("BULUCU", "yolda kutu var: " + box.getTypeName() + " " + (int) context.hero.distanceTo(box)
                        + ", once kutu (sonra bulucuya devam)");
                cooldown = 3000;
                forBox = true;
                return false;
            }
            // Sinyal "Birak" ile biraktigimiz NPC'ye donduyse: yok sayma kaldirilir, ona geri donulur.
            if (droppedId >= 0) {
                Npc d = context.findNpc(droppedId);
                if (d != null && d.distanceTo(loc) < NEAR_LOC) {
                    context.bans.release(d, Bans.Owner.DROP);
                    droppedId = -1;
                    setFound(d.getId());
                    Log.i("BULUCU", "sinyal birakilan NPC'yi gosteriyor (" + Log.npc(d) + "), ona geri donuluyor");
                    return false;
                }
            }
            Npc n = realNpcNear(loc);
            // Tam kontrolde: dovusun alamayacagi NPC (mesafe siniri vb.) "bulundu" sayilmaz, yaklasmaya devam edilir
            // (yoksa bulucu ile dovus her tik birbirini kesiyordu).
            if (n != null && context.config.COMBAT.ENABLED && !context.combatCanTake.test(n)) n = null;
            // Yoldaki NPC: sadece bulucunun aradigi turden daha oncelikliyse ona donulur; yoksa durulmaz, menzildeyse
            // gecerken ates edilir (bulucunun NPC'sine gitmek oncelikli).
            if (n == null) n = betterOnWay(loc);
            if (n != null && context.config.COMBAT.ENABLED && !context.combatCanTake.test(n)) n = null;
            if (n == null) shootPassing();
            if (n != null) {
                setFound(n.getId());
                Log.i("BULUCU", "NPC bulundu: " + Log.npc(n) + " " + (int) context.hero.distanceTo(n) + " uzakta, "
                        + (now - start) / 1000.0 + " sn");
                return false;
            }
            // Sinyalin yanina varildi: NPC'yi DarkBot secsin (NPC yok sayilmis ya da listede olmasa da beklenmez).
            if (context.hero.distanceTo(loc) < ARRIVED) {
                if (arrivedAt == 0) arrivedAt = now;
                if (now - arrivedAt > ARRIVED_MS) {
                    Npc here = context.nearestNpc(loc);
                    boolean taken = here != null && here.distanceTo(loc) < 700 && Context.killable(here) && here.isBlacklisted();
                    if (!taken) emptyZones.add(new double[]{loc.getX(), loc.getY(), now + EMPTY_MS});
                    Log.i("BULUCU", "sinyalin yanina varildi " + Log.loc(loc) + ", alinacak NPC yok"
                            + (taken ? "" : ": isaret burada kalirsa " + EMPTY_MS / 1000 + " sn gidilmeyecek") + " | yakindaki NPC: " + nearInfo(loc));
                    return false;
                }
            } else {
                arrivedAt = 0;
            }
            if (Log.due("gostat", 5000))
                Log.i("BULUCU", "gidiliyor, sinyal " + Log.loc(loc) + " " + (int) context.hero.distanceTo(loc)
                        + " uzakta | yakindaki NPC: " + nearInfo(loc));
            if (now - start > GO_TIMEOUT_MS) {
                Log.i("BULUCU", "90 sn icinde NPC'ye varilamadi");
                return false;
            }
            context.pet.setEnabled(true);
            context.hero.setRoamMode();
            context.travelTo(loc);
            context.speedAbility(context.hero.distanceTo(loc), "bulucu");
            return true;
        }

        /** Gecilen yoldaki, aranan turden daha oncelikli (tablodaki sayi kucuk) kesilecek NPC. */
        private Npc betterOnWay(Location loc) {
            Integer pickPri = pickPriority(loc);
            if (pickPri == null) return null;
            Npc best = null;
            for (Npc o : context.ents.getNpcs()) {
                if (!context.darkBotWouldKill(o) || context.move.getClosestDistance(o) >= 500) continue;
                if (context.hero.distanceTo(o) >= NEAR_LOC || o.getInfo().getPriority() >= pickPri) continue;
                if (best == null || o.getInfo().getPriority() < best.getInfo().getPriority()) best = o;
            }
            if (best != null) Log.i("BULUCU", "yolda daha oncelikli NPC: " + Log.npc(best) + " (oncelik " + best.getInfo().getPriority()
                    + " < " + pickPri + "), ona donuluyor");
            return best;
        }

        private int passingId = -1;

        /** Gecerken ates: menzildeki kesilecek NPC'ye kilit + ates, gemi durmaz (yol bulucuya devam). */
        private void shootPassing() {
            Npc cur = passingId >= 0 ? context.findNpc(passingId) : null;
            // Menzilden cikti, baskasi vurmaya basladi (Yok Say yolculugu kesmesin) ya da artik kesilmiyor: birakilir.
            if (cur != null && (!context.darkBotWouldKill(cur) || context.attackedByOthers(cur)
                    || context.hero.distanceTo(cur) > cur.getInfo().getRadius() * 1.2)) {
                context.attack.stopAttack();
                context.attack.setTarget(null);
                passingId = -1;
                cur = null;
            }
            if (cur == null) {
                for (Npc o : context.ents.getNpcs()) {
                    if (!context.darkBotWouldKill(o) || context.attackedByOthers(o)) continue;
                    if (context.hero.distanceTo(o) <= o.getInfo().getRadius()) {
                        cur = o;
                        break;
                    }
                }
                if (cur == null) return;
                passingId = cur.getId();
                Log.d("BULUCU", "gecerken ates: " + Log.npc(cur) + " (durulmuyor)");
            }
            if (!java.util.Objects.equals(context.attack.getTarget(), cur)) context.attack.setTarget(cur);
            context.attack.tryLockAndAttack();
        }

        @Override
        public void end(String why) {
            if (passingId >= 0 && found != passingId) {
                // Gecerken vurulan NPC birakilir; secimi dovus yapar (oncelige gore).
                context.attack.stopAttack();
                context.attack.setTarget(null);
            }
            // 10 sn bekleme sadece NPC bulunduysa (ayni NPC'nin sinyaline tekrar gidilmesin); o NPC olunce /
            // kaybolunca bekleme kalkar. Sinyal kayboldu / NPC yok / kesildi: 1,5 sn sonra yeni sinyale gidilir.
            foundId = found;
            long now = Context.now();
            goCooldown = now + (found >= 0 ? cooldown : Math.min(cooldown, 1500));
            if (found < 0) {
                // NPC bulunamadi: 1,5 sn kosulsuz bekleme, kutu icin bitmediyse sinyal yeri 3 sn reddedilir.
                failCooldown = now + 1500;
                if (!forBox) reject(loc);
            }
        }
    }
}