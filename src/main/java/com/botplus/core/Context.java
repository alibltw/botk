package com.botplus.core;

import com.botplus.locator.*;
import com.botplus.push.PushGuard;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.PercentRange;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.enums.PetGear;
import eu.darkbot.api.game.entities.Pet;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.other.EntityInfo;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.AttackAPI;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.GroupAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.HeroItemsAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.PetAPI;
import eu.darkbot.api.managers.RepairAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.managers.StatsAPI;
import eu.darkbot.shared.utils.SafetyFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Ozelliklerin ortak kullandigi API'ler ve yardimcilar. */
public final class Context {

    /** Normal saldiri menzili (DarkBot Entity: "was 800 before"). */
    public static final int ATTACK_RANGE = 800;

    public final PluginAPI api;
    public final BotAPI bot;
    /** Gerektiginde ek API (cikis penceresi gibi) icin; yoksa ozellik atlanir. */
    public final PluginAPI pluginApi;
    public final HeroAPI hero;
    public final MovementAPI move;
    public final AttackAPI attack;
    public final EntitiesAPI ents;
    public final PetAPI pet;
    public final HeroItemsAPI items;
    public final GroupAPI group;
    public final StarSystemAPI star;
    public final RepairAPI repair;
    public final StatsAPI stats;
    /** DarkBot'un dusmandan / tamir icin portala kacis sistemi (BotPlus'in kendi kopyasi). */
    public final SafetyFinder safety;
    /** BotPlus'in acildigi an (bundan onceki olumler sayilmaz). */
    public final long startedAt = System.currentTimeMillis();

    public final ConfigSetting<Character> abilityKey;
    public final ConfigSetting<PercentRange> repairRange;
    public final ConfigSetting<ShipMode> runMode;
    public final ConfigSetting<ShipMode> repairMode;
    public final ConfigSetting<ShipMode> roamMode;
    public final ConfigSetting<Integer> workingMap;
    public final ConfigSetting<Integer> collectRadius;
    public final ConfigSetting<Integer> npcDistanceIgnore;
    public final ConfigSetting<Boolean> onlyKillPreferred;
    public final ConfigSetting<Boolean> runConfigInCircle;
    public final ConfigSetting<Boolean> ignoreContested;
    public final ConfigSetting<eu.darkbot.api.game.enums.PetGear> petModule;
    public final ConfigSetting<Double> repairNoNpc;

    public BotPlusConfig config = new BotPlusConfig();

    /** Merkezi yok sayma: tum ozellikler NPC'yi buradan yok sayar (baskasinin yok saymasi kisaltilmaz). */
    public final Bans bans = new Bans();
    /** Olum raporu: son 30 sn ornekleri. */
    public final DeathLog deaths = new DeathLog(this);
    /** Push Korumasi: oyuncu bazinda oldurme / olme kaydi. */
    public final PushGuard push = new PushGuard(this::heroInfoForPush, this::pushSettings);
    private eu.darkbot.api.game.other.EntityInfo heroInfoForPush() {
        return hero.getEntityInfo();
    }

    private BotPlusConfig.PushSettings pushSettings() {
        return config.ON_ENEMY.PUSH;
    }

    /** Saldir (PvP) isinin hedefi (yoksa null; Escape kurar). */
    public volatile java.util.function.Supplier<Player> pvpTarget = () -> null;
    /** Bulastirma atisi / PvP'nin istedigi cephane (yoksa null; BotPlus'in cephane secicisi kullanir). */
    public volatile java.util.function.Supplier<eu.darkbot.api.game.items.SelectableItem.Laser> strikeLaser = () -> null;
    public volatile java.util.function.Supplier<eu.darkbot.api.game.items.SelectableItem.Laser> pvpLaser = () -> null;

    /**
     * Saldiri tamamen birakilir: ates tusu kapatilir, saldiri hedefi ve geminin kendi hedefi temizlenir (sadece
     * stopAttack + setTarget(null) yetmiyordu: saldiri denemesi suruyorken ates acik kalabiliyordu).
     */
    public void releaseAttack() {
        unlock.request("hedef birakildi");
    }

    /**
     * Kilit Birakma rutini (CTRL ac / kapa kontrollu, kilit oyuncudaysa yakindaki NPC secilir). DarkBot'un
     * stopAttack'i kisa sure icinde ikinci komutu yutuyordu; bkz. LockRelease.
     */
    public final LockRelease unlock = new LockRelease(this);

    // ---------------------------------------------------------------- Kimlere (sirket)

    /** Kendi sirketimizin oyuncusu kendi sirketine 5 sn ates etmezse ona ates edilmez (seref kaybi). */
    public static final long OWN_FIRM_FIRE_MS = 5000;

    /** Kendi sirketimizden mi: ayni sirket ve klan savasi yok (oyunda saldirmak seref kaybettirir). */
    public boolean ownFirm(Player p) {
        Boolean k = firmKnown(p);
        return k == null || k; // bilinmiyorsa (yok / NONE) kendi sirketi gibi davranilir: dosta ates edilmesin
    }

    /**
     * Sirket bilgisi: true kendi sirketi, false dusman sirket, null bilinmiyor (oyun henuz yollamadi / NONE).
     * Bilinmeyen kaydedilmez: bilgi gelince yeniden bakilir.
     */
    public Boolean firmKnown(Player p) {
        if (p == null) return null;
        try {
            // Klan savasi her zaman once: onbellekte kendi sirketi olarak kalmis olsa da savastaki klan dusmandir.
            eu.darkbot.api.game.other.EntityInfo wi = p.getEntityInfo();
            if (wi != null && wi.getClanDiplomacy() == eu.darkbot.api.game.other.EntityInfo.Diplomacy.WAR) {
                ownIds.remove(p.getId());
                return false;
            }
        } catch (RuntimeException ignored) {
        }
        if (ownIds.contains(p.getId())) return true;
        try {
            eu.darkbot.api.game.other.EntityInfo i = p.getEntityInfo(), me = hero.getEntityInfo();
            eu.darkbot.api.game.other.EntityInfo.Faction f = i == null ? null : i.getFaction();
            eu.darkbot.api.game.other.EntityInfo.Faction mf = me == null ? null : me.getFaction();
            if (mf != null && mf != eu.darkbot.api.game.other.EntityInfo.Faction.NONE) heroFaction = mf;
            else mf = heroFaction;
            // Bilinmiyorsa (yok / NONE: harita degisiminden sonra bir sure boyle okunabiliyor) kendi sirketi sayilir:
            // yanlislikla dosta ates edilmesin (06:13'te harita donusunden sonra dosta zehir atilmisti).
            if (f == null || mf == null || f == eu.darkbot.api.game.other.EntityInfo.Faction.NONE
                    || mf == eu.darkbot.api.game.other.EntityInfo.Faction.NONE) return null;
            if (i.getClanDiplomacy() == eu.darkbot.api.game.other.EntityInfo.Diplomacy.WAR) return false;
            boolean own = f == mf;
            // Bir kez kendi sirketi goruldu: oturum boyunca oyle hatirlanir (sonra yanlis okunsa da).
            if (own) {
                if (ownIds.size() > 500) ownIds.clear();
                ownIds.add(p.getId());
            }
            return own;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Kendi sirketi oldugu bir kez dogrulanan oyuncular (id). */
    private final java.util.Set<Integer> ownIds = new java.util.HashSet<>();
    /** Son bilinen kendi sirketimiz (gemi bilgisi bir an NONE okunursa). */
    private eu.darkbot.api.game.other.EntityInfo.Faction heroFaction;

    /** Kendi Sirketi Saldirinca = Yok Say ve bu oyuncu kendi sirketimizden: kacis / takip / tehdit yok. */
    public boolean ignoredOwn(Player p) {
        // Sadece kendi sirketi oldugu BILINENLER yok sayilir (bilinmeyen dusman olabilir: ondan yine kacilir).
        // 2.6.0: Sadece Savunma ve Karsilik Ver'de de kendi sirketinden kacilmaz / takip edilmez.
        return p != null && ownNoFlee() && Boolean.TRUE.equals(firmKnown(p));
    }

    /** Kendi Sirketi Saldirinca: kendi sirketinden kacilmayan modlar (Yok Say, Sadece Savunma, Karsilik Ver). */
    public boolean ownNoFlee() {
        BotPlusConfig.OwnMode m = config.ON_ENEMY.OWN_MODE;
        return m == BotPlusConfig.OwnMode.IGNORE || m == BotPlusConfig.OwnMode.DEFEND || m == BotPlusConfig.OwnMode.COUNTER;
    }

    /** Yok Say: kendi sirketinden saldirana Defense de kullanilmaz (2.6.0; bilinen kendi sirketi). */
    public boolean ownSilent(Player p) {
        return p != null && config.ON_ENEMY.OWN_MODE == BotPlusConfig.OwnMode.IGNORE && Boolean.TRUE.equals(firmKnown(p));
    }

    /** Sirket bilgisi (log icin): "kendi sirketi (MMO)" / "dusman sirket (EIC)". */
    public String firmInfo(Player p) {
        String f;
        try {
            f = String.valueOf(p.getEntityInfo().getFaction());
        } catch (RuntimeException e) {
            f = "?";
        }
        Boolean k = firmKnown(p);
        return (k == null ? "sirketi bilinmiyor (kendi sirketi sayildi)" : k ? "kendi sirketi" : "dusman sirket")
                + " (" + f + "/" + heroFaction + ")";
    }

    /**
     * Kimlere: bu oyuncuya ates edilebilir mi (PvP saldirisi, zehir, roket, mayin, yetenek). Dusman sirket: secildiyse.
     * Kendi sirketi: secildiyse VE son 5 sn icinde bize ates ettiyse (bayragi dusunce ates edilmez).
     */
    public boolean mayFire(Player p, java.util.Set<BotPlusConfig.Firm> firms) {
        if (p == null || firms == null) return false;
        if (!ownFirm(p)) return firms.contains(BotPlusConfig.Firm.ENEMY_FIRM);
        // Kendi Sirketi Saldirinca: Karsilik Ver'de son 5 sn icinde bize ates edene (Kimlere seciminden bagimsiz) ates
        // edilir; Yok Say / Sadece Kac / Sadece Savunma'da kendi sirketine hic ates edilmez.
        if (config.ON_ENEMY.OWN_MODE == BotPlusConfig.OwnMode.COUNTER) return attackedBy(p, OWN_FIRM_FIRE_MS);
        if (config.ON_ENEMY.OWN_MODE != BotPlusConfig.OwnMode.SAME) return false;
        return firms.contains(BotPlusConfig.Firm.OWN_FIRM) && attackedBy(p, OWN_FIRM_FIRE_MS);
    }

    /** Ada gore kendi sirketi mi (gorunuyorsa; gorunmuyorsa dusman sirket sayilir: dusman suresi uygulanir). */
    public Boolean ownFirmByName(String name) {
        if (name == null) return null;
        try {
            for (Player p : ents.getPlayers())
                if (p.getEntityInfo() != null && name.equals(p.getEntityInfo().getUsername())) {
                    Boolean k = firmKnown(p);
                    if (k != null) return k;
                }
        } catch (RuntimeException ignored) {
        }
        // Gorus disinda: ates ederken (gorunurken) kendi sirketi oldugu dogrulandiysa o bilgi kullanilir. Eskiden
        // gorunmeyen kendi sirketi saldiran "sirketi bilinmiyor" sayilip dusman olarak 10 dk hatirlaniyordu
        // (17:36 TommyShelby, Kendi Sirketi = Yok Say iken).
        for (java.util.Map.Entry<Integer, String> e : attackerName.entrySet())
            if (name.equals(e.getValue()) && ownIds.contains(e.getKey())) return true;
        for (java.util.Map.Entry<Integer, String> e : trackName.entrySet())
            if (name.equals(e.getValue()) && ownIds.contains(e.getKey())) return true;
        return null;
    }

    /** PET (oyuncu gibi gorunur, adi "Ad [seviye]"). */
    public boolean petPlayer(Player p) {
        return p != null && p.getEntityInfo() != null && petName(p.getEntityInfo().getUsername());
    }

    /** DarkBot ayarlari (oyuncu listesi / etiketler icin). */
    public ConfigAPI configApiRef;
    /** Oyunun son "Menzil disi" mesaji (RSB / ates bekcisi bu sureyi RSB hatasi saymaz). */
    public volatile long lastOutOfRangeAt;
    /** Bos Harita Modu (BotPlus kurar); null olabilir. */
    public EmptyMap emptyMap;
    /** Bulucunun son buldugu NPC (dovus ilk aday olarak alir) ve ne zaman. */
    public int locatorFoundId = -1;
    /** Combat'in su an sectigi NPC (yoksa -1); CombatLogic kurar. */
    public java.util.function.IntSupplier combatTargetId = () -> -1;
    public long locatorFoundAt;
    /** RSB icin: hedefe kararli ates ediliyor mu (yayda, menzilde, ilk isabet alinmis) - CombatPlus kurar. */
    public java.util.function.Predicate<Npc> rsbStable = n -> false;
    /** RSB icin: ates sorunu RSB disi bir nedenden mi (yaklasma, menzilden cikis, kacis...), nedeni ya da null - CombatPlus kurar. */
    public java.util.function.Function<Npc, String> rsbExcuse = n -> null;

    public Context(PluginAPI api) {
        this.api = api;
        this.bot = api.requireAPI(BotAPI.class);
        mainModule = bot::getNonTemporalModule;
        this.pluginApi = api;
        this.hero = api.requireAPI(HeroAPI.class);
        this.move = api.requireAPI(MovementAPI.class);
        this.attack = api.requireAPI(AttackAPI.class);
        this.ents = api.requireAPI(EntitiesAPI.class);
        this.pet = api.requireAPI(PetAPI.class);
        this.items = api.requireAPI(HeroItemsAPI.class);
        this.group = api.requireAPI(GroupAPI.class);
        this.star = api.requireAPI(StarSystemAPI.class);
        this.repair = api.requireAPI(RepairAPI.class);
        this.stats = api.requireAPI(StatsAPI.class);
        SafetyFinder sf = null;
        try {
            // BotPlus'in kacis sistemi: DarkBot'un SafetyFinder'i, dusmandan kacma karari BotPlus'ta (PlusSafety).
            PlusSafety ps = api.requireInstance(PlusSafety.class);
            ps.escapeWanted = () -> escapeWanted.getAsBoolean();
            ps.threat = s -> s instanceof Player && attackedBy((Player) s, ESCAPE_MEMORY_MS) && !ignoredOwn((Player) s);
            ps.inCharge = () -> now() - plusInChargeAt < 1000;
            ps.options = () -> this.config.ON_ENEMY.ESCAPE_OPTS;
            ps.plus = () -> this.config.ON_ENEMY.PLUS;
            ps.critical = () -> this.config.ON_ENEMY.HP > 0 && heroHp() <= this.config.ON_ENEMY.HP / 100.0;
            ps.following = () -> threatFollowing.getAsBoolean();
            // Harita degisim olayi icin acik kayit (DarkBot zaten kaydediyorsa yeniden kaydedilir, cift olmaz).
            try {
                api.requireAPI(eu.darkbot.api.managers.EventBrokerAPI.class).registerListener(ps);
            } catch (Throwable t) {
                System.out.println("[BotPlus] GUVENLIK | kacis sistemi olay kaydi yapilamadi: " + t);
            }
            sf = ps;
        } catch (Throwable t) {
            System.out.println("[BotPlus] GUVENLIK | BotPlus kacis sistemi yuklenemedi, DarkBot'un kacis sistemi kullanilacak: " + t);
            try {
                sf = api.requireInstance(SafetyFinder.class);
            } catch (Throwable t2) {
                System.out.println("[BotPlus] GUVENLIK | DarkBot kacis sistemi de yuklenemedi, BotPlus isleri sirasinda kacis yok: " + t2);
            }
        }
        this.safety = sf;

        ConfigAPI configApi = api.requireAPI(ConfigAPI.class);
        this.configApiRef = configApi;
        this.abilityKey = configApi.requireConfig("loot.ship_ability");
        this.repairRange = configApi.requireConfig("general.safety.repair_hp_range");
        this.runMode = configApi.requireConfig("general.run");
        this.repairMode = configApi.requireConfig("general.safety.repair");
        this.roamMode = configApi.requireConfig("general.roam");
        this.workingMap = configApi.requireConfig("general.working_map");
        this.collectRadius = configApi.requireConfig("collect.radius");
        this.npcDistanceIgnore = configApi.requireConfig("loot.npc_distance_ignore");
        this.onlyKillPreferred = configApi.requireConfig("general.roaming.only_kill_preferred");
        this.runConfigInCircle = optional(configApi, "loot.run_config_in_circle");
        this.ignoreContested = optional(configApi, "collect.ignore_contested_boxes");
        this.petModule = optional(configApi, "pet.module_id");
        this.repairNoNpc = optional(configApi, "general.safety.repair_hp_no_npc");
    }

    /** DarkBot surumunde olmayan ayar eklentiyi durdurmasin. */
    private static <T> ConfigSetting<T> optional(ConfigAPI api, String key) {
        try {
            return api.requireConfig(key);
        } catch (Throwable t) {
            System.out.println("[BotPlus] AYAR | DarkBot ayari bulunamadi: " + key);
            return null;
        }
    }

    public static <T> T value(ConfigSetting<T> s, T def) {
        try {
            T v = s == null ? null : s.getValue();
            return v == null ? def : v;
        } catch (RuntimeException e) {
            return def;
        }
    }


    // ---------------------------------------------------------------- tik onbellegi

    /** Tik sayaci (beginTick ya da 10 ms'den eski onbellek yeni tik sayilir). */
    private long tickNo;
    /** Onbellegin kuruldugu an (System.nanoTime, monoton); cacheReady false: hic kurulmadi. */
    private long cacheAt;
    private boolean cacheReady;
    private static final long TICK_NS = 10_000_000L;
    private final java.util.Set<Integer> attackedIds = new java.util.HashSet<>();
    private boolean attackedReady;
    private Ship attackerCache;
    private boolean attackerReady;
    private Player threatCache;
    private boolean threatReady;
    private java.util.Optional<Location> pingCache;
    private final java.util.Map<Integer, Double> closestNpc = new java.util.HashMap<>();
    private final java.util.Map<Integer, Double> closestBox = new java.util.HashMap<>();
    private final java.util.Map<Integer, Boolean> wouldKill = new java.util.HashMap<>();
    private final java.util.Map<Integer, Boolean> contestedBox = new java.util.HashMap<>();

    /** Yeni tik: tik basina hesaplanan degerler sifirlanir (BotPlus / PlusModule tik basinda cagirir). */
    public void beginTick() {
        newTick(System.nanoTime());
        Log.flushIfDue();
    }

    public long tickNo() {
        fresh();
        return tickNo;
    }

    /**
     * beginTick cagrilmadiysa da onbellek 10 ms'den eskiyse yeni tik sayilir (System.nanoTime: saat geri / ileri
     * alininca bozulmaz). BotPlus'in kendi modulu calismiyorsa tik basinda beginTick cagrilmali.
     */
    private void fresh() {
        long now = System.nanoTime();
        if (!cacheReady || now - cacheAt >= TICK_NS) newTick(now);
    }

    private void newTick(long nanoNow) {
        cacheAt = nanoNow;
        cacheReady = true;
        tickNo++;
        attackedReady = false;
        attackerReady = false;
        threatReady = false;
        attackerCache = null;
        threatCache = null;
        pingCache = null;
        if (!closestNpc.isEmpty()) closestNpc.clear();
        if (!closestBox.isEmpty()) closestBox.clear();
        if (!wouldKill.isEmpty()) wouldKill.clear();
        if (!contestedBox.isEmpty()) contestedBox.clear();
        if (Log.account == null) {
            try {
                EntityInfo me = hero.getEntityInfo();
                String u = me == null ? null : me.getUsername();
                if (u != null && !u.trim().isEmpty()) Log.account = u.trim();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Baska oyuncularin / PET'lerin ates ettigi NPC id'leri (bizim gemi, PET ve grup uyeleri haric), tik basina bir kez. */
    private java.util.Set<Integer> attackedIds() {
        fresh();
        if (attackedReady) return attackedIds;
        attackedIds.clear();
        int heroId = hero.getId();
        for (Player p : ents.getPlayers()) {
            if (p.getId() == heroId || !p.isValid() || !p.isAttacking()) continue;
            eu.darkbot.api.game.entities.Entity t = p.getTarget();
            if (!(t instanceof Npc) || isGroupMember(p)) continue;
            attackedIds.add(t.getId());
        }
        for (Pet p : ents.getPets()) {
            if (!p.isValid() || !p.isAttacking() || isOurPet(p)) continue;
            // Grup arkadasinin PET'i "baskasi vuruyor" sayilmaz (oyuncusu da sayilmiyor).
            if (petOfGroupMember(p)) continue;
            eu.darkbot.api.game.entities.Entity t = p.getTarget();
            if (t instanceof Npc) attackedIds.add(t.getId());
        }
        attackedReady = true;
        return attackedIds;
    }

    /** Gidilebilir alana en yakin uzaklik (MovementAPI.getClosestDistance), NPC basina tik basina bir kez. */
    public double closestDistance(Npc n) {
        fresh();
        Double d = closestNpc.get(n.getId());
        if (d == null) {
            d = move.getClosestDistance(n);
            closestNpc.put(n.getId(), d);
        }
        return d;
    }

    private double closestDistance(eu.darkbot.api.game.entities.Box b) {
        fresh();
        Double d = closestBox.get(b.getId());
        if (d == null) {
            d = move.getClosestDistance(b);
            closestBox.put(b.getId(), d);
        }
        return d;
    }

    // ---------------------------------------------------------------- DarkBot kacisi

    private long lastSafetyTick;
    private int safetyErrors;
    /** DarkBot yenileme / mola istedi: BotPlus'in kacis sistemi portala gidip bekler (Yenileme ayari kapaliyken). */
    public long refreshingUntil;
    /** DarkBot yenileme / mola istedi ama gemi guvende degildi (Yenileme "Once Uzaklas"). */
    public long refreshWantedAt;
    /** DarkBot yenileme / mola zamani geldi (DarkBot canRefresh sordu). */
    public long refreshDueAt;
    /** Son BotPlus isinin bittigi an. */
    public long lastEndAt;
    /** DarkBot'un BotPlus'i en son durdurdugu / calistirmadigi an (onStoppedBehavior). */
    public long lastStoppedAt;
    /** Suren duraklamanin (DarkBot modulu tiklemiyor) basladigi an (2.6.0). */
    public long stopStartedAt;
    /** Bot gercekten durduruldu (DarkBot "durdur"; olum / harita yukleme degil). Ayar dokumu icin. */
    public long lastHaltedAt;
    /** BotPlus gemiyi en son yonettigi an (PlusSafety: 1 sn icindeyse BotPlus'in kacis karari gecerli). */
    public volatile long plusInChargeAt;
    /** Kacis sisteminin WAITING oncesi son durumu (salt tamir kacisi portalda beklemeye donmesin). */
    private SafetyFinder.Escaping lastKind = SafetyFinder.Escaping.NONE;

    /**
     * BotPlus'in kacis sistemini (PlusSafety) bir tik calistirir. true: guvenli, is devam edebilir.
     * Dusmandan kacis sadece BotPlus "Dusman Saldirinca" karariyla (escapeWanted) olur; DarkBot'un "Dusmandan Kac"
     * ayarlari BotPlus aktifken kullanilmaz. "Haritada Tamir" aciksa (repairOnMap) tamir icin portala kacis
     * (REPAIR) hic baslatilmaz: kacis sistemi o durumda tiklenmez (eskiden tiklenip sonucu yok sayiliyordu, ama
     * SafetyFinder tiklenince gemiyi portala ceviriyor, saldiriyi kesiyor ve kacis konfigine geciyordu).
     */
    public boolean safetyTick(boolean repairOnMap) {
        if (safety == null) return true;
        long now = now();
        boolean refreshing = now <= refreshingUntil;
        try {
            boolean want = escapeWanted.getAsBoolean();
            SafetyFinder.Escaping st = safetyEscaping() ? safety.state() : SafetyFinder.Escaping.NONE;
            if (repairOnMap && !refreshing && !want) {
                // Salt tamir kacisi (portal tamiri): BotPlus haritada tamir ediyor. Suren bir dusman kacisi (ENEMY /
                // portalda bekleme) yarida birakilmaz.
                if (st == SafetyFinder.Escaping.NONE || st == SafetyFinder.Escaping.REPAIR
                        || (st == SafetyFinder.Escaping.WAITING && lastKind == SafetyFinder.Escaping.REPAIR)) {
                    if (st != SafetyFinder.Escaping.NONE)
                        Log.dEvery("safetyrepair", 10_000, "GUVENLIK", "DarkBot portal tamiri istiyor, BotPlus haritada tamir ettigi icin yok sayildi");
                    return true;
                }
            }
            safety.setRefreshing(refreshing);
            boolean ok = safety.tick();
            lastSafetyTick = now;
            safetyErrors = 0;
            SafetyFinder.Escaping after = safety.state();
            if (after != SafetyFinder.Escaping.WAITING) lastKind = after;
            return ok;
        } catch (RuntimeException e) {
            Log.every("safetyerr", 60_000, "GUVENLIK", "kacis sistemi hatasi: " + e);
            // Hata tekrarlarsa is sonsuza kadar beklemesin.
            return ++safetyErrors > 5;
        }
    }

    public boolean safetyTick() {
        // Sadece haritada tamir BotPlus'taysa portal tamiri yok sayilir (Portalda / DarkBot'a birak: DarkBot tamir eder).
        // Haritada tamir ise yaramiyorsa (can artmiyor / kovalaniyor) bir sure DarkBot'un portal tamiri calisir.
        return safetyTick(config.REPAIR.onMap() && now() >= portalRepairUntil);
    }

    /** Combat Plus tam kontrolde kutular modulden bagimsiz toplanir (kutu tablosuna gore). */
    public boolean collecting() {
        if (config.COMBAT.ENABLED) return botMode() != BotPlusConfig.BotMode.NPC;
        return collectModule();
    }

    /**
     * Calisma bicimi. Ne yapilacagi DarkBot'ta secili modulden gelir: NPC Killer -> sadece NPC, Collector -> sadece
     * kutu, Loot & Collect (NPC + kutu) -> sira "Siralama" ayarindan (Bot Modulu / once NPC / once kutu / tablo).
     */
    public BotPlusConfig.BotMode botMode() {
        boolean collect, loot;
        if (selfMode.getAsBoolean()) {
            // BotPlus ana modul: calisma sekli BotPlus menusunden.
            BotPlusConfig.WorkMode w = config.WORK == null ? BotPlusConfig.WorkMode.NPC_BOX : config.WORK;
            collect = w != BotPlusConfig.WorkMode.NPC;
            loot = w != BotPlusConfig.WorkMode.BOX;
        } else {
            eu.darkbot.api.extensions.Module mod = bot.getNonTemporalModule();
            String n = mod == null ? "" : mod.getClass().getSimpleName();
            collect = n.contains("Collect");
            loot = n.contains("Loot") || n.contains("Npc") || n.contains("Kill");
            // BotPlus "Modul Secimi" burada da gecerli: BotPlus'in kendi isleri (kutu bekleme, oncelik, Combat, Steal...)
            // secilmeyen isi yapmaz. DarkBot'un kendi modulu yine kendi isini yapar: uyumsuzluk loga yazilir.
            BotPlusConfig.WorkMode w = config.WORK == null ? BotPlusConfig.WorkMode.NPC_BOX : config.WORK;
            boolean c2 = collect && w != BotPlusConfig.WorkMode.NPC, l2 = loot && w != BotPlusConfig.WorkMode.BOX;
            if (!c2 && !l2 && (collect || loot)) {
                // Tamamen uyusmuyor (ornek: Collector secili, Modul Secimi "Sadece NPC"): DarkBot'un modulu gecerli
                // (eskiden ikisi de kapanip "Sadece Toplama"ya dusuyordu).
                Log.every("workmismatch", 60_000, "AYARLAR", "DarkBot'ta secili modul (" + n + ") BotPlus Modul Secimi ("
                        + w + ") ile hic uyusmuyor: DarkBot'un modulune gore calisiliyor. Tam uyum icin modul olarak BotPlus'i secin.");
            } else if ((c2 != collect || l2 != loot) && (c2 || l2)) {
                Log.every("workmismatch", 60_000, "AYARLAR", "DarkBot'ta secili modul (" + n + ") BotPlus Modul Secimi ("
                        + w + ") ile uyusmuyor: BotPlus sadece " + (c2 ? "kutu" : "NPC") + " isi yapar, ama DarkBot'un modulu"
                        + " kendi isini yapmaya devam eder. Tam uyum icin modul olarak BotPlus'i secin.");
                collect = c2;
                loot = l2;
            }
        }
        if (!collect) return BotPlusConfig.BotMode.NPC;
        if (!loot) return BotPlusConfig.BotMode.BOX;
        BotPlusConfig.Sort s = config.SORT;
        if (s == BotPlusConfig.Sort.BOX_FIRST) return BotPlusConfig.BotMode.BOX_NPC;
        if (s == BotPlusConfig.Sort.TABLE) return tableBoxFirst() ? BotPlusConfig.BotMode.BOX_NPC : BotPlusConfig.BotMode.NPC_BOX;
        return BotPlusConfig.BotMode.NPC_BOX; // Bot Modulu (Loot & Collect: once NPC) / once NPC
    }

    private long tableAt;
    private boolean tableBox;

    /** Tablo onceligi: gorunen kutularin en kucuk onceligi NPC'lerinkinden kucukse once kutu (500 ms'de bir hesaplanir). */
    private boolean tableBoxFirst() {
        long now = now();
        if (now - tableAt < 500) return tableBox;
        tableAt = now;
        int npcPri = Integer.MAX_VALUE, boxPri = Integer.MAX_VALUE;
        for (Npc n : ents.getNpcs())
            if (darkBotWouldKill(n) && hero.distanceTo(n) <= npcDistanceIgnore()) npcPri = Math.min(npcPri, n.getInfo().getPriority());
        for (eu.darkbot.api.game.entities.Box b : ents.getBoxes())
            if (b.isValid() && b.getInfo() != null && b.getInfo().shouldCollect() && hero.distanceTo(b) <= config.BOX_MAX_DIST)
                boxPri = Math.min(boxPri, b.getInfo().getPriority());
        tableBox = boxPri < npcPri;
        return tableBox;
    }

    /** BotPlus'in kacis sistemi son 2,5 sn icinde tiklendi ve kacis suruyor. */
    public boolean safetyEscaping() {
        try {
            return safety != null && now() - lastSafetyTick < 2500 && safety.state() != SafetyFinder.Escaping.NONE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Kacis sistemi portalda bekliyor (yenileme icin uygun an). */
    public boolean safetyWaiting() {
        try {
            return safetyEscaping() && safety.state() == SafetyFinder.Escaping.WAITING;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** DarkBot'un secili modulu kendi kacisini yapiyor mu (durum yazisinda "Escaping"). */
    public boolean mainEscaping() {
        try {
            eu.darkbot.api.extensions.Module m = mainModule.get();
            String st = m == null ? null : m.getStatus();
            if (st == null) return false;
            String l = st.toLowerCase(java.util.Locale.ROOT);
            if (!l.contains("escaping")) return false;
            // Haritada tamir aciksa DarkBot'un tamir icin portala kacisi sayilmaz: tamiri BotPlus haritada yapar.
            return !(l.contains("repair") && config.REPAIR.onMap());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Modul durumunun ilk satiri (verim satiri loga yazilmaz). */
    public static String status(eu.darkbot.api.extensions.Module m) {
        String s = m == null ? null : m.getStatus();
        if (s == null) return null;
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    public String mainStatus() {
        try {
            eu.darkbot.api.extensions.Module m = mainModule.get();
            return m == null ? "-" : m.getClass().getSimpleName() + " (" + status(m) + ")";
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Secili DarkBot modulu kutu topluyor mu (adinda "Collect" gecen). */
    public boolean collectModule() {
        if (selfMode.getAsBoolean()) return config.WORK != BotPlusConfig.WorkMode.NPC;
        eu.darkbot.api.extensions.Module m = bot.getNonTemporalModule();
        return m != null && m.getClass().getSimpleName().contains("Collect");
    }

    /** BotPlus DarkBot'ta ana modul olarak secili mi (BotPlus kurar). */
    public java.util.function.BooleanSupplier selfMode = () -> false;
    /**
     * Isin disinda gemiyi yoneten modul: BotPlus ana modulken onun icindeki DarkBot yedegi (Loot / Collector),
     * degilse DarkBot'ta secili modul (BotPlus kurar).
     */
    public java.util.function.Supplier<eu.darkbot.api.extensions.Module> mainModule;
    /** Calisan BotPlus isi (yoksa null; BotPlus kurar). */
    public java.util.function.Supplier<PlusModule> job = () -> null;

    public String safetyStatus() {
        if (safety == null) return "yok";
        try {
            if (!safetyEscaping()) return "NONE";
            return safety.state() + " - " + safety.status();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** DarkBot tamir araliginin alt siniri (0-1), bilinmiyorsa -1. */
    public double darkBotRepairMin() {
        try {
            PercentRange r = repairRange.getValue();
            return r == null ? -1 : r.getMin();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- kargo / kutu

    public boolean cargoFull() {
        try {
            int max = stats.getMaxCargo();
            return max > 0 && stats.getCargo() >= max;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String cargo() {
        try {
            return stats.getCargo() + "/" + stats.getMaxCargo();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    public int collectRadius() {
        try {
            Integer r = collectRadius.getValue();
            return r == null ? 1000 : r;
        } catch (RuntimeException e) {
            return 1000;
        }
    }

    /** Toplanabilir kutu: tabloda toplama acik, toplanmamis, gidilebilir. */
    public boolean collectable(eu.darkbot.api.game.entities.Box b) {
        return b.isValid() && !b.isCollected() && b.getInfo() != null && b.getInfo().shouldCollect()
                && closestDistance(b) < 200
                && (!isResourceBox(b) || !cargoFull())
                && !contested(b);
    }

    /** DarkBot kurali (Collect: IGNORE_CONTESTED_BOXES): baska gemi kutuya bizden once varacaksa alinmaz. */
    public boolean contested(eu.darkbot.api.game.entities.Box b) {
        if (!value(ignoreContested, Boolean.TRUE)) return false;
        fresh();
        Boolean c = contestedBox.get(b.getId());
        if (c == null) {
            c = contested0(b);
            contestedBox.put(b.getId(), c);
        }
        return c;
    }

    private boolean contested0(eu.darkbot.api.game.entities.Box b) {
        try {
            double heroTime = hero.timeTo(b) * 2;
            for (Player p : ents.getPlayers()) {
                if (p.getId() == hero.getId() || !p.isValid()) continue;
                java.util.Optional<Location> d = p.getDestination();
                if (d.isPresent() && d.get().distanceTo(b) < 1 && heroTime > p.timeTo(b)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Son kutu toplamanin bitecegi an: bu sure dolmadan hicbir yerden yeni toplama tiklamasi yapilmaz. */
    private long collectBusyUntil;
    /** Ard arda toplama denemesi (ayni kutu): bekleme suresine deneme basina 100 ms eklenir (DarkBot gibi). */
    private int collectTries;
    private int collectBoxId = Integer.MIN_VALUE;

    /** Bir kutu toplaniyor mu (oyunda toplama animasyonu suruyor): gemi yerinde kalir, baska kutuya tiklanmaz. */
    public boolean collectBusy() {
        if (now() >= collectBusyUntil) return false;
        // Tiklanan kutu kaybolduysa (toplandi) beklenmez.
        eu.darkbot.api.game.entities.Box b = collectBox;
        if (b != null && !b.isValid()) {
            collectBusyUntil = 0;
            collectBox = null;
            return false;
        }
        return true;
    }

    private eu.darkbot.api.game.entities.Box collectBox;

    /** Bu ana kadar DarkBot'un portal tamiri (REPAIR kacisi) engellenmez (haritada tamir basarisiz oldu). */
    public long portalRepairUntil;

    /**
     * Tek yerden kutu toplama. DarkBot'un kutusu ilk denemeden sonra sadece 50 ms "toplaniyor" gorunur; bu yuzden
     * bekleme burada tutulur: kutu bekleme suresi + deneme basina 100 ms (en fazla 1 sn) + varis suresi + 30 ms
     * (DarkBot'un toplayicisiyla ayni). Eskiden her yer kendi suresini tutuyordu ve oyun "Paket icerigi toplaniyor
     * zaten" diyordu (kutu basina 2-3 fazla tiklama).
     */
    public boolean collect(eu.darkbot.api.game.entities.Box b) {
        if (b == null || collectBusy()) return false;
        // Kutuya az once tiklandiysa (DarkBot'un kendi toplayicisi dahil: kutunun ortak "toplaniyor" suresi) tiklanmaz.
        if (b.isCollected()) return false;
        if (!b.tryCollect()) return false;
        Stats.tried(b);
        collectBox = b;
        if (b.getId() != collectBoxId) {
            collectBoxId = b.getId();
            collectTries = 0;
        }
        collectTries++;
        long wait = b.getInfo() == null ? 500 : b.getInfo().getWaitTime();
        long travel;
        try {
            travel = hero.timeTo(b);
        } catch (RuntimeException e) {
            travel = 0;
        }
        collectBusyUntil = now() + wait + Math.min(1000, collectTries * 100L) + Math.max(0, travel) + 30;
        return true;
    }

    /** Toplama icin kalan bekleme (ms, yoksa 0). */
    public long collectWaitLeft() {
        return Math.max(0, collectBusyUntil - now());
    }

    public static boolean isResourceBox(eu.darkbot.api.game.entities.Box b) {
        String t = b.getTypeName();
        return "FROM_SHIP".equals(t) || "PROSPEROUS_CARGO".equals(t);
    }

    /** Gemiye en yakin toplanabilir kutu (max uzaklik icinde). */
    public eu.darkbot.api.game.entities.Box nearestBox(double max) {
        eu.darkbot.api.game.entities.Box best = null;
        double bd = max;
        for (eu.darkbot.api.game.entities.Box b : ents.getBoxes()) {
            if (!collectable(b)) continue;
            double d = hero.distanceTo(b);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- tehlike / mesafeler

    /**
     * DarkBot'un LootModule.shouldKill kurallari (bayraklar): pasif, ikinci vurucu, sadece leech, tercih edilen bolge.
     * BotPlus kendi hedef sectiginde (Combat Plus siradaki hedef) DarkBot'un sececegi NPC'lerle ayni kalir.
     */
    public boolean darkBotWouldKill(Npc n) {
        // Ucuz kontroller once; yok sayma (isBlacklisted) onbelleklenmez, gerisi NPC basina tik basina bir kez.
        if (!killable(n) || n.isBlacklisted()) return false;
        fresh();
        Boolean c = wouldKill.get(n.getId());
        if (c == null) {
            c = wouldKillRules(n);
            wouldKill.put(n.getId(), c);
        }
        return c;
    }

    private boolean wouldKillRules(Npc n) {
        eu.darkbot.api.config.types.NpcInfo i = n.getInfo();
        try {
            if (Boolean.TRUE.equals(onlyKillPreferred.getValue()) && !move.isInPreferredZone(n)) return false;
        } catch (RuntimeException ignored) {
        }
        if (i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.LEECH_ONLY)
                && !hero.hasEffect(eu.darkbot.api.game.enums.EntityEffect.ENERGY_LEECH)) return false;
        boolean attacked = attackedByOthers(n);
        if (i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.ATTACK_SECOND) && !attacked) return false;
        // DarkBot kurali: baskasinin vurdugu NPC'ye (Vurulani Yok Say isareti yoksa) gidilmez. Kendi kilitledigimiz /
        // vurdugumuz NPC haric (DarkBot da sahip oldugu hedefi birakmaz).
        if (attacked && !ignoresAttacked(n) && !i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.ATTACK_SECOND)
                && n.getLockType() != eu.darkbot.api.game.other.Lockable.Lock.OWNED && !shooting(n)) return false;
        String user = n.getEntityInfo() == null ? "" : n.getEntityInfo().getUsername();
        return user.contains("Invoke") || !i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.PASSIVE) || n.isAttacking(hero);
    }

    public int npcDistanceIgnore() {
        try {
            Integer v = npcDistanceIgnore.getValue();
            return v == null ? 3000 : v;
        } catch (RuntimeException e) {
            return 3000;
        }
    }

    public static boolean killable(Npc n) {
        return n != null && n.isValid() && !isFake(n) && n.getInfo() != null && n.getInfo().getShouldKill();
    }

    /** Seni patlatan, hatirlanan oyuncular (Dusmani Hatirla): dost gorunse de dusman sayilir. */
    public final java.util.Set<String> killers = new java.util.HashSet<>();

    public boolean isKiller(Player p) {
        return p != null && p.getEntityInfo() != null && killers.contains(p.getEntityInfo().getUsername());
    }

    /** PET adi: sonunda seviye etiketi ("Ad [20]"); oyuncu degil. */
    private static final java.util.regex.Pattern PET_NAME = java.util.regex.Pattern.compile(".*\\s\\[\\d{1,2}\\]$");

    public static boolean petName(String name) {
        return name != null && name.endsWith("]") && PET_NAME.matcher(name).matches();
    }

    public boolean isEnemyPlayer(Player p) {
        return p != null && p.getId() != hero.getId() && p.isValid() && p.getEntityInfo() != null
                && !petName(p.getEntityInfo().getUsername())
                && (p.getEntityInfo().isEnemy() || isKiller(p)
                || ((p.isAttacking(hero) || attackedBy(p, THREAT_MEMORY_MS)) && !isFriend(p)));
    }

    /** Hatirlanan olduren bu noktaya yakin mi (null: yok). Saldir'da kacilmayanlar ve Yok Say'daki kendi sirketi sayilmaz. */
    public Player killerNear(Locatable l, double dist) {
        if (killers.isEmpty()) return null;
        for (Player p : ents.getPlayers())
            if (p.isValid() && isKiller(p) && p.distanceTo(l) < dist && !attackStays(Log.name(p)) && !ignoredOwn(p)) return p;
        return null;
    }

    /**
     * Saldir secili, PvP Plus acik ve "Saldir'da Hatirlanandan Kacma" aciksa hatirlanan oyuncudan kacilmaz, yanindaki
     * NPC / sinyal yasaklanmaz (karsilik PvP'de). Push Korumasi olme sinirindaki oyuncu haric (ondan yine kacilir).
     */
    public boolean attackStays(String name) {
        try {
            BotPlusConfig.OnEnemy oe = config.ON_ENEMY;
            if (oe == null || !oe.ATTACK_STAY || oe.MODE != BotPlusConfig.OnEnemyMode.ATTACK || !oe.PVP_ON) return false;
            return name == null || !push.feared(name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hizli Can Kaybi suruyor mu (Escape kurar; PvP bu durumda saldiriyi birakir). */
    public java.util.function.BooleanSupplier rapidLossNow = () -> false;
    /** Takip Edilirse Atla: kacilan dusman pesimizde mi (Escape kurar). */
    public java.util.function.BooleanSupplier threatFollowing = () -> true;

    /** Kacis modu Kac / Hizla Atlat ve "Yetenekleri Kacisa Sakla" aciksa Defense EMP / hiz yetenegini ilk atista harcamaz. */
    public boolean saveForEscape() {
        try {
            BotPlusConfig.OnEnemy oe = config.ON_ENEMY;
            return oe.PLUS.SAVE_SKILLS && selfMode.getAsBoolean()
                    && (oe.MODE == BotPlusConfig.OnEnemyMode.OUTRUN || oe.MODE == BotPlusConfig.OnEnemyMode.ESCAPE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Locator'in kendi hatirlama listesinden silme (Locator kurar). */
    public java.util.function.Consumer<String> forgetKiller = n -> {
    };

    /**
     * Oyuncu unutulur (PvP'de oldurduk): saldiran / takip / hatirlanan kayitlari silinir. Eskiden olen oyuncu "ates
     * kesti" sayilip hatirlaniyor, olum yerindeki NPC'ler "hatirlanan dusman yakininda" diye secilmiyordu.
     */
    public void forgetPlayer(int id, String name) {
        lastAttackBy.remove(id);
        attackerAt.remove(id);
        attackerMap.remove(id);
        attackerName.remove(id);
        trackUntil.remove(id);
        trackOwn.remove(id);
        trackSeen.remove(id);
        trackSeenAt.remove(id);
        trackSeenMap.remove(id);
        trackName.remove(id);
        if (name != null) {
            killers.remove(name);
            killerSeen.remove(name);
            killerSeenAt.remove(name);
            killerSeenMap.remove(name);
            try {
                forgetKiller.accept(name);
            } catch (RuntimeException ignored) {
            }
        }
        threatReady = false;
    }


    /** Hatirlanan oldurenlerin son goruldugu yer: gorus disina cikinca da o bolgeye bir sure gidilmez. */
    private final java.util.Map<String, Location> killerSeen = new java.util.HashMap<>();
    private final java.util.Map<String, Long> killerSeenAt = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> killerSeenMap = new java.util.HashMap<>();

    /** Olduren hatirlaninca olunen yer de hatirlanir (o bolgeye gidilmez). */
    public void killerAt(String name, Locatable at, int mapId) {
        if (name == null || at == null) return;
        killerSeen.put(name, Location.of(at.getX(), at.getY()));
        killerSeenAt.put(name, now());
        killerSeenMap.put(name, mapId);
    }

    private int mapIdNow() {
        try {
            return star.getCurrentMap() == null ? -1 : star.getCurrentMap().getId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public void trackKillers() {
        long now = now();
        if (killers.isEmpty()) {
            killerSeen.clear();
            killerSeenAt.clear();
            killerSeenMap.clear();
            return;
        }
        int map = mapIdNow();
        for (Player p : ents.getPlayers()) {
            if (!p.isValid() || !isKiller(p)) continue;
            String name = p.getEntityInfo().getUsername();
            if (!killerSeen.containsKey(name)) Log.i("DUSMAN", name + " goruldu " + Log.loc(p));
            killerAt(name, p, map);
        }
        // Hatirlama suresi boyunca (Dusmani Hatirla: dakika) son goruldugu yer tutulur.
        killerSeenAt.entrySet().removeIf(e -> {
            if (killers.contains(e.getKey())) return false;
            killerSeen.remove(e.getKey());
            killerSeenMap.remove(e.getKey());
            return true;
        });
    }

    /** Gorunen ya da son 3 dk icinde goruldugu yer bu noktaya yakin hatirlanan olduren (adi), yoksa null. */
    public String killerZone(Locatable l, double dist) {
        Player p = killerNear(l, dist);
        if (p != null) return Log.name(p) + " " + (int) p.distanceTo(l);
        int map = mapIdNow();
        for (java.util.Map.Entry<String, Location> e : killerSeen.entrySet()) {
            if (e.getValue().distanceTo(l) >= dist || killerSeenMap.getOrDefault(e.getKey(), map) != map) continue;
            if (attackStays(e.getKey())) continue;
            long ago = (now() - killerSeenAt.getOrDefault(e.getKey(), 0L)) / 1000;
            return e.getKey() + " (son gorulme " + ago + " sn once, " + (int) e.getValue().distanceTo(l) + ")";
        }
        return null;
    }

    /** Dovus kararlari icin tehdit: "Oyuncu Saldirinca: Devam Et" seciliyse dusman dovusu engellemez (null). */
    public Player fightThreat() {
        return config.PROTECT.ON_PLAYER == BotPlusConfig.OnPlayer.CONTINUE ? null : enemyThreat();
    }

    /**
     * Dusman tehdidi: bize ates eden dusman oyuncu (uzaklik farketmez), son 30 sn icinde ates etmis ve 3000 icinde
     * olan dusman ya da hatirlanan olduren. Varken bulucuya gidilmez; kacis "Dusman Saldirinca" ayarina gore yapilir.
     */
    public Player enemyThreat() {
        fresh();
        if (!threatReady) {
            threatCache = findEnemyThreat();
            threatReady = true;
        }
        return threatCache;
    }

    private Player findEnemyThreat() {
        Player near = null;
        double nd = Double.MAX_VALUE;
        for (Player p : ents.getPlayers()) {
            if (!isEnemyPlayer(p) || ignoredOwn(p)) continue;
            double d = p.distanceTo(hero);
            // Bize ates eden dusman, uzakligi ne olursa olsun (DarkBot'ta "saldiriyor" mesafeden bagimsiz bir bayrak;
            // eskiden 2000 siniri vardi: dusman 2000'in hemen disina cikinca bulucu ayni sinyale geri donuyordu).
            if (p.isAttacking(hero)) return p;
            // Son 30 sn icinde bize ates etmis dusman 3000 icindeyse hala tehdit (bir an ates kesince donulmez).
            if ((attackedBy(p, THREAT_MEMORY_MS) && d < 3000) || (isKiller(p) && d < 2500 && !attackStays(Log.name(p)))) {
                if (d < nd) {
                    nd = d;
                    near = p;
                }
            }
        }
        return near;
    }

    // ---------------------------------------------------------------- saldiran oyuncu hafizasi / tehlikeli bolgeler

    /** Saldiran oyuncu ne kadar hatirlanir (tehdit sayilir). */
    public static final long THREAT_MEMORY_MS = 30_000;
    /** Kacis (Dusman Saldirinca) icin: saldiri bu kadar suredir kesikse kacis biter. */
    public static final long ESCAPE_MEMORY_MS = 8_000;
    /** Oyuncu id -> bize son ates ettigi an. */
    private final java.util.Map<Integer, Long> lastAttackBy = new java.util.HashMap<>();
    /** Oyuncu id -> son goruldugu yer (kacis / tehlikeli bolge icin). */
    private final java.util.Map<Integer, Location> attackerAt = new java.util.HashMap<>();
    /** Oyuncu id -> son goruldugu harita (bolge dogru haritaya yazilsin). */
    private final java.util.Map<Integer, Integer> attackerMap = new java.util.HashMap<>();
    /** Oyuncu id -> ad (Dusmani Hatirla: saldiranlar). */
    private final java.util.Map<Integer, String> attackerName = new java.util.HashMap<>();
    private long attackPruneAt;

    /** Her tik: bize ates eden dusman oyuncular hatirlanir (PET'ler haric). */
    public void trackAttackers() {
        long now = now();
        try {
            for (Player p : ents.getPlayers()) {
                if (!isEnemyPlayer(p) || !p.isAttacking(hero)) continue;
                lastAttackBy.put(p.getId(), now);
                // Takip: son ates zamani tutulur, sure her kontrolde ayardan hesaplanir (ayar degisince hemen gecerli).
                if (!ignoredOwn(p)) {
                    trackUntil.put(p.getId(), now);
                    Boolean own = firmKnown(p);
                    if (own != null) trackOwn.put(p.getId(), own); // bilinmiyorsa kaydedilmez, gorulunce yazilir
                }
                attackerAt.put(p.getId(), Location.of(p.getX(), p.getY()));
                attackerMap.put(p.getId(), mapIdNow());
                if (p.getEntityInfo() != null && p.getEntityInfo().getUsername() != null)
                    attackerName.put(p.getId(), p.getEntityInfo().getUsername());
            }
            // Saldirani Takip: takip edilen (hatirlama suresindeki) saldiranin gorundugu her an konumu yazilir.
            if (!trackUntil.isEmpty()) {
                int map = mapIdNow();
                for (Player p : ents.getPlayers()) {
                    if (!p.isValid() || !tracked(p)) continue;
                    if (!trackOwn.containsKey(p.getId())) {
                        Boolean own = firmKnown(p);
                        if (own != null) trackOwn.put(p.getId(), own);
                    }
                    trackSeen.put(p.getId(), Location.of(p.getX(), p.getY()));
                    trackSeenAt.put(p.getId(), now);
                    trackSeenMap.put(p.getId(), map);
                    if (p.getEntityInfo() != null && p.getEntityInfo().getUsername() != null)
                        trackName.put(p.getId(), p.getEntityInfo().getUsername());
                }
            }
        } catch (RuntimeException ignored) {
        }
        // Eski saldiranlar dakikada bir (ya da liste buyuyunce) unutulur.
        if (lastAttackBy.size() > 50 || now - attackPruneAt > 60_000) {
            attackPruneAt = now;
            lastAttackBy.values().removeIf(t -> now - t > THREAT_MEMORY_MS * 4);
            attackerAt.keySet().retainAll(lastAttackBy.keySet());
            attackerMap.keySet().retainAll(lastAttackBy.keySet());
            attackerName.keySet().retainAll(lastAttackBy.keySet());
            trackUntil.entrySet().removeIf(e -> now - e.getValue() > trackMs(trackOwn.getOrDefault(e.getKey(), false)));
            trackOwn.keySet().retainAll(trackUntil.keySet());
            trackSeen.keySet().retainAll(trackUntil.keySet());
            trackSeenAt.keySet().retainAll(trackUntil.keySet());
            trackSeenMap.keySet().retainAll(trackUntil.keySet());
            trackName.keySet().retainAll(trackUntil.keySet());
        }
        dangerZones.removeIf(z -> z.until < now);
    }

    // ---------------------------------------------------------------- Saldirani Takip

    /** Saldiran gorunurken ona bu kadar yakin NPC / sinyal / kacis noktasi secilmez. */
    public static final double TRACK_AVOID_R = 2500;
    /** Saldiran gorus disina cikinca son goruldugu yer bu kadar sure yasak. */
    public static final long TRACK_HIDDEN_MS = 60_000;
    /** Oyuncu id -> bize son ates ettigi an (takip bu andan itibaren Dusmani Hatirla suresi kadar). */
    private final java.util.Map<Integer, Long> trackUntil = new java.util.HashMap<>();
    /** Oyuncu id -> kendi sirketi mi (ayri hatirlama suresi). */
    private final java.util.Map<Integer, Boolean> trackOwn = new java.util.HashMap<>();
    private final java.util.Map<Integer, Location> trackSeen = new java.util.HashMap<>();
    private final java.util.Map<Integer, Long> trackSeenAt = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> trackSeenMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> trackName = new java.util.HashMap<>();

    /**
     * Hatirlama suresi: dusman sirket "Dusmani Hatirla" dakikasi, kendi sirketi "Kendi Sirketini Hatirla" dakikasi.
     * Dusmani Hatirla kapaliysa kisa takip (2 dk / kendi sirketi icin ayari).
     */
    public long trackMs(boolean own) {
        try {
            BotPlusConfig.Killer k = config.PROTECT.KILLER;
            // Dusmani Hatirla kapali (hicbiri secili degil): kimse takip edilmez / hatirlanmaz (eskiden 2 dk / en az
            // 1 dk takip suruyordu, ayar kapatilamiyordu). 0 dakika: o grup hic hatirlanmaz.
            if (k == null || !k.ENABLED) return 0;
            if (own) return Math.max(0, k.OWN_MINUTES) * 60_000L;
            return Math.max(0, k.MINUTES) * 60_000L;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Bu oyuncu bize saldirdi ve hala takip ediliyor mu (sure ayardan anlik). */
    public boolean tracked(Player p) {
        return p != null && tracked(p.getId());
    }

    private boolean tracked(int id) {
        Long t = trackUntil.get(id);
        return t != null && now() - t < trackMs(trackOwn.getOrDefault(id, false));
    }

    /**
     * Takip edilen saldiran bu noktaya r'den yakin mi (nedeni, degilse null): gorunurse canli konumu, gorunmuyorsa
     * son goruldugu yer (TRACK_HIDDEN_MS boyunca). exceptId: bu oyuncu sayilmaz (-1: hepsi).
     */
    public String trackedZone(Locatable l, double r, int exceptId) {
        if (l == null || trackUntil.isEmpty()) return null;
        long now = now();
        java.util.Set<Integer> visible = new java.util.HashSet<>();
        for (Player p : ents.getPlayers()) {
            if (p.getId() == exceptId || !p.isValid() || !tracked(p) || ignoredOwn(p)) continue;
            visible.add(p.getId());
            double d = p.distanceTo(l);
            if (d < r) return "saldiran " + Log.name(p) + " yakininda (" + (int) d + ")";
        }
        int map = mapIdNow();
        for (java.util.Map.Entry<Integer, Location> e : trackSeen.entrySet()) {
            int id = e.getKey();
            if (id == exceptId || visible.contains(id) || !tracked(id)) continue;
            if (trackOwn.getOrDefault(id, false) && ownNoFlee()) continue;
            long ago = now - trackSeenAt.getOrDefault(id, 0L);
            if (ago > TRACK_HIDDEN_MS || trackSeenMap.getOrDefault(id, -2) != map) continue;
            if (e.getValue().distanceTo(l) < r)
                return "saldiran " + trackName.getOrDefault(id, "#" + id) + " son goruldugu yer (" + ago / 1000 + " sn once)";
        }
        return null;
    }

    /** Bu oyuncu son ms icinde bize ates etti mi. */
    public boolean attackedBy(Player p, long ms) {
        return p != null && attackedBy(p.getId(), ms);
    }

    /** Bu id'li oyuncu (gorunmese de) son ms icinde bize ates etti mi. */
    public boolean attackedBy(int playerId, long ms) {
        Long t = lastAttackBy.get(playerId);
        return t != null && now() - t < ms;
    }

    /** Son ms icinde bize ates eden (gorunen) dusman oyuncu: en yakini, yoksa null. */
    public Player recentAttacker(long ms) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : ents.getPlayers()) {
            if (!p.isValid() || !attackedBy(p, ms)) continue;
            double d = p.distanceTo(hero);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** BotPlus portala kacmak istiyor mu (Escape isi kurar; PlusSafety okur). */
    public java.util.function.BooleanSupplier escapeWanted = () -> false;

    /** Tehlikeli bolge: dusman yuzunden birakilan / kacilan yer, bir sure bulucu ve dovus icin yasak. */
    private static final class Zone {
        final Location at;
        final double r;
        final long until;
        final int map;
        final String why;
        /** Bolgeyi koyan dusman (-1: yok): bolgeden uzaklasinca yasak erken kalkar (2.6.0). */
        final int threatId;

        Zone(Location at, double r, long until, int map, String why, int threatId) {
            this.at = at;
            this.r = r;
            this.until = until;
            this.map = map;
            this.why = why;
            this.threatId = threatId;
        }
    }

    /** Dusman yuzunden konan tehlikeli bolge suresi (2.6.0: 60 sn -> 20 sn; dusman uzaklasinca erken kalkar). */
    public static final long THREAT_ZONE_MS = 20_000;
    /** Bolgeyi koyan dusman bolgenin merkezinden bu kadar uzaklasinca yasak kalkar. */
    public static final double THREAT_ZONE_LEAVE = 3000;

    /**
     * Dusman yuzunden tehlikeli bolge: 20 sn, dusman bolgeden 3000+ uzaklasinca erken kalkar. O dusman hatirlanmiyorsa
     * (hatirlama suresi 0 dk / Dusmani Hatirla kapali) bolge hic konmaz.
     */
    public void threatZone(Locatable at, double r, Player threat, String why) {
        if (threat == null) return;
        Boolean own = firmKnown(threat);
        if (trackMs(own != null && own) <= 0) {
            Log.dEvery("zoneoff", 30_000, "GUVENLIK", () -> "tehlikeli bolge konmadi: " + Log.name(threat) + " hatirlanmiyor (sure 0 dk)");
            return;
        }
        dangerZone(at, r, THREAT_ZONE_MS, why, mapIdNow(), threat.getId());
    }

    private final java.util.List<Zone> dangerZones = new java.util.ArrayList<>();

    /** Bolge yasaklanir (ayni yerde zaten varsa suresi uzatilir). */
    public void dangerZone(Locatable at, double r, long ms, String why) {
        dangerZone(at, r, ms, why, mapIdNow(), -1);
    }

    public void dangerZone(Locatable at, double r, long ms, String why, int map) {
        dangerZone(at, r, ms, why, map, -1);
    }

    /** Bolge belirli haritada yasaklanir (kacis portaldan atladiktan sonra biterse eski harita). */
    public void dangerZone(Locatable at, double r, long ms, String why, int map, int threatId) {
        if (at == null || map < 0) return;
        long until = now() + ms;
        // Ayni yerdeki eski bolge birlesir: daha uzun sure ve daha buyuk yaricap korunur (kisalmaz).
        double[] keep = {r, until};
        final double merge = r / 2;
        dangerZones.removeIf(z -> {
            if (z.map != map || z.at.distanceTo(at) >= merge) return false;
            keep[0] = Math.max(keep[0], z.r);
            keep[1] = Math.max(keep[1], z.until);
            return true;
        });
        r = keep[0];
        until = (long) keep[1];
        dangerZones.add(new Zone(Location.of(at.getX(), at.getY()), r, until, map, why, threatId));
        Log.i("GUVENLIK", "tehlikeli bolge " + Log.loc(at) + " (" + (int) r + ") " + ms / 1000 + " sn yasak: " + why);
    }

    /** Atlatma Sonrasi Mesafe: atlatilan dusman (id), son yeri, yaricap ve bitis. */
    private int keepId = -1;
    private String keepName;
    private Location keepAt;
    private double keepR;
    private long keepUntil;
    private int keepMap = -1;

    /** Atlatilan dusmanin (gorunurken canli yeri, gorunmezse son yeri) r icindeki NPC / sinyal ms boyunca secilmez. */
    public void keepAwayFrom(int id, String name, Location at, double r, long ms) {
        keepId = id;
        keepName = name;
        keepAt = at;
        keepR = r;
        keepUntil = now() + ms;
        keepMap = mapIdNow();
        Log.i("DUSMAN", "atlatma sonrasi: " + name + " yanindaki NPC'ler " + ms / 1000 + " sn secilmeyecek (" + (int) r + ")");
    }

    private String keepZone(Locatable l) {
        if (keepId < 0 || now() >= keepUntil || mapIdNow() != keepMap) return null;
        Location at = keepAt;
        for (Player p : ents.getPlayers())
            if (p.getId() == keepId && p.isValid()) {
                at = Location.of(p.getX(), p.getY());
                keepAt = at;
            }
        if (at == null || at.distanceTo(l) >= keepR) return null;
        return "atlatilan dusman " + keepName + " yakininda (" + (keepUntil - now()) / 1000 + " sn)";
    }

    /** Bolgeyi koyan dusman gorunuyor ve bolgeden uzaklastiysa yasak kalkar (2.6.0). */
    private void liftLeftZones() {
        if (dangerZones.isEmpty()) return;
        dangerZones.removeIf(z -> {
            if (z.threatId < 0) return false;
            for (Player p : ents.getPlayers()) {
                if (p.getId() != z.threatId || !p.isValid()) continue;
                if (z.at.distanceTo(p) > THREAT_ZONE_LEAVE + z.r) {
                    Log.i("GUVENLIK", "tehlikeli bolge " + Log.loc(z.at) + " kalkti: " + Log.name(p) + " uzaklasti ("
                            + (int) z.at.distanceTo(p) + ")");
                    return true;
                }
            }
            return false;
        });
    }

    /** Nokta tehlikeli bolgede mi (nedeni), degilse null. */
    public String inDangerZone(Locatable l) {
        if (l == null) return null;
        long now = now();
        int map = mapIdNow();
        liftLeftZones();
        for (Zone z : dangerZones)
            if (z.until > now && z.map == map && z.at.distanceTo(l) < z.r)
                return z.why + " (" + (z.until - now) / 1000 + " sn)";
        String keep = keepZone(l);
        if (keep != null) return keep;
        // Saldirani Takip: sabit bolge yerine saldiranin kendisi (gorunurken canli, gorunmezse 60 sn son yeri).
        // "Devam Et" seciliyse saldirandan uzak durulmaz (dovus engellenmez).
        if (config.ON_ENEMY.MODE == BotPlusConfig.OnEnemyMode.CONTINUE) return null;
        return trackedZone(l, TRACK_AVOID_R, -1);
    }

    /** Oyuncunun son goruldugu yer (bize ates ederken), bilinmiyorsa null. */
    public Location attackerSeenAt(Player p) {
        return p == null ? null : attackerAt.get(p.getId());
    }

    /** Oyuncunun (id) bize ates ederken son goruldugu yer, bilinmiyorsa null. */
    public Location attackerSeenAt(int playerId) {
        return attackerAt.get(playerId);
    }

    /** Bize ates edip quietMs'dir ates etmeyen (en fazla withinMs once) oyuncularin adlari (saldiri bitti). */
    public java.util.List<String> quietAttackers(long quietMs, long withinMs) {
        long now = now();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, Long> e : lastAttackBy.entrySet()) {
            long ago = now - e.getValue();
            String n = attackerName.get(e.getKey());
            if (ago >= quietMs && ago <= withinMs && n != null && !n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** Oyuncunun (id) bize ates ederken son goruldugu harita, bilinmiyorsa -1. */
    public int attackerMapOf(int playerId) {
        Integer m = attackerMap.get(playerId);
        return m == null ? -1 : m;
    }


    // ---------------------------------------------------------------- hareket takibi

    /** Combat Plus "Bitir, sonra tamir" karari (Repair sorar). */
    public java.util.function.BooleanSupplier finishFirst = () -> false;

    /** BotPlus'in son verdigi hareket hedefi: baska eklenti / DarkBot degistirirse loglanir. */
    private Location lastMove;

    private static final double AVOID_R = 700;
    /** "Hedef Yakinsa NPC'den Kacinma": hedefe bu kadar yakinken dolanilmaz. */
    public static final double AVOID_NEAR = 1500;
    /** Birbirine bu kadar yakin liste disi NPC'ler tek engel sayilir (aralarindan gecilmez). */
    private static final double CLUSTER_R = 1500;
    /** Gemiye bu kadar yakin NPC engel sayilmaz (zaten yaninda, geri donulmez). */
    private static final double AVOID_MIN_AHEAD = 300;
    private static final double[] AVOID_ANGLES = {15, 30, 45, 60, 75, 90};
    private int avoidNpc = -1;
    private int avoidSide;
    private long avoidSideAt;
    /** Son dolanma hesabi (300 ms ayni hedef / yakin konum icin tekrar kullanilir: yol bulma pahali). */
    private Locatable avoidResult;
    private Location avoidDest, avoidFrom;
    private long avoidCalcAt;
    private double avoidCalcR;

    /** Kesilmeyen (tabloda olmayan) gercek NPC mi: yolda kacinilacaklar. */
    private static boolean unlisted(Npc n) {
        return n.isValid() && !isFake(n) && n.getInfo() != null && !n.getInfo().getShouldKill();
    }

    /**
     * Saldirmayan NPC'ler (sabit liste): yanindan gecince saldirmayanlar. Loglarda 700 icinde onlarca kez goruldu, hic
     * ates etmedi (Boss Kristallon 99, Boss Lordakium 95, UberLordakium 49, Uber Saboteur 37, Uber Barracuda 36 ...).
     * Talon ailesinin hepsi. Streuner ve Battleray listede yok (vuruyorlar). Ad sadece harf / rakam, kucuk harf.
     */
    private static final String[] PASSIVE_NPCS = {"bosskristallon", "bosslordakium", "uberlordakium", "ubersaboteur",
            "uberbarracuda", "uberannihilator"};

    /** Saldirmayan NPC'lerden Kacinma: tik acik, NPC listede ve bize ates etmiyor / nisan almiyorsa true. */
    private boolean passivePass(Npc n) {
        if (!config.PASS_PASSIVE || n.getInfo() == null) return false;
        String key = passiveKey(n.getInfo().getName());
        boolean listed = key.contains("talon");
        for (String s : PASSIVE_NPCS) if (key.equals(s)) listed = true;
        if (!listed) return false;
        try {
            if (n.isAttacking(hero) || n.isAiming(hero)) return false;
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    private static String passiveKey(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : name.toLowerCase(java.util.Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.toString();
    }

    /** Saldirgan sayilir mi: bize ates ediyor / bize donuk ya da tabloda "Pasif" degil (bilgisi yoksa saldirgan). */
    private boolean aggressive(Npc n) {
        if (n.isAttacking(hero)) return true;
        try {
            if (n.isAiming(hero)) return true;
        } catch (RuntimeException ignored) {
        }
        eu.darkbot.api.config.types.NpcInfo i = n.getInfo();
        return i == null || !i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.PASSIVE);
    }

    public Locatable avoidOnPath(Locatable dest) {
        return avoidOnPath(dest, config.AVOID_UNLISTED, AVOID_R);
    }

    /**
     * Yolda Listede Olmayan NPC'lerden Kacin: hedefe giden duz yolda, gemiden 300 ile "Ileri Bakis" (AVOID_AHEAD)
     * arasinda ve yola avoidR'den yakin kesilmeyen NPC varsa yanindan dolasan ara nokta doner; yoksa hedefin kendisi.
     * - "Sadece Saldirganlar" aciksa sadece bize ates eden / donuk ya da Pasif olmayan NPC'ler engel sayilir.
     * - Engele yakin (1500) ve yola yakin NPC'ler tek kume: aralarindan degil disindan dolanilir.
     * - Adaylar gemi->hedef yonunden +-15/30/45 derece (en fazla "Kacis Acisi"); gidilemeyen, kenara 1000'den yakin ya da
     *   hedefe gemiden yakin olmayan nokta secilmez. Puan: yol uzunlugu - NPC'lere uzaklik; ayni taraf tercih edilir.
     * - Secilen taraf en az 2 sn ve engel geride kalana kadar korunur. Uygun nokta yoksa hedefe devam.
     */
    public Locatable avoidOnPath(Locatable dest, boolean enabled, double avoidR) {
        if (dest == null || !enabled) return dest;
        double hx = hero.getX(), hy = hero.getY();
        double dx = dest.getX() - hx, dy = dest.getY() - hy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 600 || (config.AVOID_NEAR_OFF && len < AVOID_NEAR)) return dest;
        long now = now();
        // Yol bulma pahali: ayni hedef, ayni yaricap ve gemi pek kimildamadiysa son sonuc 500 ms kullanilir.
        if (avoidDest != null && now - avoidCalcAt < 500 && avoidCalcR == avoidR && avoidDest.distanceTo(dest) < 100
                && avoidFrom.distanceTo(hero) < 200) return avoidResult == avoidDest ? dest : avoidResult;
        Locatable res = computeAvoid(dest, avoidR, hx, hy, dx, dy, len, now);
        avoidDest = Location.of(dest.getX(), dest.getY());
        avoidFrom = Location.of(hx, hy);
        avoidCalcAt = now;
        avoidCalcR = avoidR;
        avoidResult = res == dest ? avoidDest : res;
        return res;
    }

    private Locatable computeAvoid(Locatable dest, double avoidR, double hx, double hy, double dx, double dy, double len, long now) {
        double ux = dx / len, uy = dy / len;
        double ahead = Math.min(len, Math.max(AVOID_MIN_AHEAD + 100, config.AVOID_AHEAD));
        boolean onlyAggro = config.AVOID_ONLY_AGGRESSIVE;
        java.util.Collection<? extends Npc> npcs = ents.getNpcs();
        Npc block = null;
        double blockAlong = Double.MAX_VALUE;
        boolean prevAhead = false;
        for (Npc n : npcs) {
            // Ucuz geometri once: yolun onunde ve yola yakin olmayan NPC hic incelenmez.
            double rx = n.getX() - hx, ry = n.getY() - hy;
            double along = rx * ux + ry * uy;
            if (n.getId() == avoidNpc && along > 0 && n.isValid()) prevAhead = true;
            if (along < AVOID_MIN_AHEAD || along > ahead) continue;
            if (Math.abs(rx * uy - ry * ux) >= avoidR) continue;
            // Sadece hedef noktanin tam ustundeki NPC atlanir (eskiden 900: hedef yanindaki NPC'den hic kacinilmiyordu).
            if (!unlisted(n) || n.distanceTo(dest) < 300) continue;
            if (onlyAggro && !aggressive(n)) continue;
            if (passivePass(n)) continue; // Saldirmayan NPC'lerden Kacinma
            if (along < blockAlong) {
                blockAlong = along;
                block = n;
            }
        }
        boolean sideLocked = avoidSide != 0 && (now - avoidSideAt < 2000 || prevAhead);
        if (block == null) {
            if (!sideLocked) {
                avoidNpc = -1;
                avoidSide = 0;
            }
            return dest;
        }
        // Engel kumesi: ilk engele yakin ve yola yakin diger liste disi NPC'ler (kume disindan dolanilir).
        List<Npc> cluster = new ArrayList<>();
        cluster.add(block);
        for (Npc n : npcs) {
            if (n == block || n.distanceTo(block) > CLUSTER_R) continue;
            if (segDist(hx, hy, dest, n) > avoidR + 500 || !unlisted(n)) continue;
            if (onlyAggro && !aggressive(n)) continue;
            if (passivePass(n)) continue; // Saldirmayan NPC'lerden Kacinma
            cluster.add(n);
        }
        double farAlong = blockAlong;
        for (Npc n : cluster) farAlong = Math.max(farAlong, (n.getX() - hx) * ux + (n.getY() - hy) * uy);
        double dist = Math.min(len, Math.max(1500, farAlong + 500));
        double maxAngle = Math.max(15, config.AVOID_ANGLE);
        // Location.of(merkez, aci, r) = merkez - (cos, sin) * r: hedefe dogru nokta icin aci hedefin tersi (hero.angleTo(dest)).
        // (Eskiden atan2(dy, dx) verilip butun adaylar geminin arkasina dusuyor, kacinma hic calismiyordu.)
        double base = Math.atan2(-dy, -dx);
        Location heroLoc = Location.of(hx, hy);
        Location bestWp = null;
        int bestSide = 0;
        double bestScore = Double.MAX_VALUE;
        // Adaylar once duz cizgi uzunluguyla puanlanir; pahali yol bulma (A*) sadece her tarafin en iyisi icin yapilir.
        List<Location> cw = new ArrayList<>(4);
        List<double[]> cs = new ArrayList<>(4); // {kaba puan, taraf, engel payi}
        for (int pass = 0; pass < 2 && cw.isEmpty(); pass++) {
            // Ilk geciste taraf kilitliyse sadece o taraf; uygun nokta yoksa ikinci geciste iki taraf.
            boolean lockedPass = pass == 0 && sideLocked;
            if (pass == 1 && !sideLocked) break;
            for (double deg : AVOID_ANGLES) {
                if (deg > maxAngle) break;
                for (int side = -1; side <= 1; side += 2) {
                    if (lockedPass && side != avoidSide) continue;
                    Location wp = Location.of(heroLoc, base + side * Math.toRadians(deg), dist);
                    // Hedefe gemiden yakin olmayan, kenara (radyasyon) yakin ya da gidilemeyen nokta secilmez.
                    if (wp.distanceTo(dest) >= len || edgeDist(wp) < 1000 || !move.canMove(wp)) continue;
                    double clear = Double.MAX_VALUE;
                    for (Npc n : cluster)
                        clear = Math.min(clear, Math.min(segDist(hx, hy, wp, n), segDist(wp.getX(), wp.getY(), dest, n)));
                    double bonus = -Math.min(clear, 1.5 * avoidR) * 2 - (side == avoidSide ? 800 : 0); // ayni taraf korunur
                    double rough = heroLoc.distanceTo(wp) + wp.distanceTo(dest) + bonus;
                    // Her taraf icin en iyi kaba aday tutulur (iki taraf da yol bulmayla karsilastirilsin: ayni tarafin
                    // yolu engel yuzunden cok uzunsa obur tarafa gecilebilsin).
                    int slot = -1;
                    for (int k = 0; k < cs.size(); k++) if ((int) cs.get(k)[1] == side) slot = k;
                    if (slot < 0) {
                        cw.add(wp);
                        cs.add(new double[]{rough, side, bonus});
                    } else if (rough < cs.get(slot)[0]) {
                        cw.set(slot, wp);
                        cs.set(slot, new double[]{rough, side, bonus});
                    }
                }
            }
        }
        for (int i = 0; i < cw.size(); i++) {
            Location wp = cw.get(i);
            double score = move.getDistanceBetween(heroLoc, wp) + move.getDistanceBetween(wp, dest) + cs.get(i)[2];
            if (score < bestScore) {
                bestScore = score;
                bestWp = wp;
                bestSide = (int) cs.get(i)[1];
            }
        }
        if (bestWp == null) return dest; // uygun dolanma noktasi yok: hedefe devam
        if (bestSide != avoidSide) {
            avoidSideAt = now;
            avoidSide = bestSide;
        }
        if (block.getId() != avoidNpc) {
            avoidNpc = block.getId();
            String name = Log.name(block);
            Log.every("avoid" + name, 5000, "YOL", "yolda listede olmayan " + name + " " + (int) blockAlong + " onde"
                    + (cluster.size() > 1 ? " (+" + (cluster.size() - 1) + " NPC, disindan)" : "")
                    + ", yanindan dolasiliyor " + Log.loc(bestWp));
        }
        return bestWp;
    }

    /** Noktanin (ax,ay)-b dogru parcasina uzakligi. */
    private static double segDist(double ax, double ay, Locatable b, Locatable p) {
        double dx = b.getX() - ax, dy = b.getY() - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.getX() - ax) * dx + (p.getY() - ay) * dy) / l2));
        return Math.hypot(ax + t * dx - p.getX(), ay + t * dy - p.getY());
    }

    /** Uzak hedefe gidis (dolasma, bulucu, NPC'ye yaklasma): yoldaki listede olmayan NPC'lerden kacinarak. */
    public void travelTo(Locatable l) {
        // Bosta tamirde liste disi NPC'lerin etrafindan (daha genis) dolanilir; gidilen yer degismez.
        boolean idle = idleRepair.getAsBoolean() && config.REPAIR.npcAway();
        moveTo(idle ? avoidOnPath(l, true, Math.max(AVOID_R, 1000)) : avoidOnPath(l));
    }

    public void moveTo(Locatable l) {
        if (l == null) return;
        // Gereksiz rota hesabi olmasin: hedef 60'tan az degistiyse ve gemi zaten oraya gidiyorsa gonderilmez.
        Location dest = move.getDestination();
        if (lastMove != null && lastMove.distanceTo(l) < 60 && dest != null && dest.distanceTo(lastMove) < 20) return;
        lastMove = Location.of(l.getX(), l.getY());
        move.moveTo(l);
    }

    public void clearMove() {
        lastMove = null;
    }

    /** Bot durdu / takildi (tikler arasi 2 sn'den uzun): eski hedef "hareket degistirildi" sanilmasin. */
    public void clearMoveAfterPause() {
        lastMove = null;
    }

    /** Durdurma: son hareket hedefi de silinir (yanlis "hareket degistirildi" uyarisi olmasin). */
    public void stop() {
        move.stop(false);
        lastMove = null;
    }

    /** Onceki tik BotPlus'in verdigi hedef baskasi tarafindan degistirildiyse aciklama doner. */
    public String moveOverridden() {
        if (lastMove == null) return null;
        Location dest = move.getDestination();
        if (dest == null || dest.distanceTo(lastMove) < 100) return null;
        return "BotPlus " + Log.loc(lastMove) + " dedi, gemi " + Log.loc(dest) + " hedefine gidiyor";
    }

    /** En yakin gercek NPC (bulucu sahte NPC'si haric). */
    public Npc nearestNpc(Locatable p) {
        // Gemiye en yakin: tik basina bir kez hesaplanir (birden cok is ayni tikte soruyor).
        if (p == hero) {
            fresh();
            if (nearNpcTick == tickNo) return nearNpcCache;
            nearNpcTick = tickNo;
            return nearNpcCache = nearestNpc0(p);
        }
        return nearestNpc0(p);
    }

    private long nearNpcTick = -1, nearEnemyTick = -1;
    private Npc nearNpcCache;
    private Player nearEnemyCache;

    private Npc nearestNpc0(Locatable p) {
        Npc best = null;
        double bd = Double.MAX_VALUE;
        for (Npc n : ents.getNpcs()) {
            if (!n.isValid() || isFake(n)) continue;
            double d = n.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    /** En yakin dusman oyuncu. */
    public Player nearestEnemy(Locatable p) {
        if (p == hero) {
            fresh();
            if (nearEnemyTick == tickNo) return nearEnemyCache;
            nearEnemyTick = tickNo;
            return nearEnemyCache = nearestEnemy0(p);
        }
        return nearestEnemy0(p);
    }

    private Player nearestEnemy0(Locatable p) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player pl : ents.getPlayers()) {
            if (!isEnemyPlayer(pl)) continue;
            double d = pl.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = pl;
            }
        }
        return best;
    }

    /** Bize ates eden oyuncu ya da NPC (once oyuncu), yoksa null. */
    public Ship attackerOfHero() {
        fresh();
        if (!attackerReady) {
            attackerCache = findAttackerOfHero();
            attackerReady = true;
        }
        return attackerCache;
    }

    private Ship findAttackerOfHero() {
        // Once gercek oyuncular, sonra NPC'ler. Dusman PET'i ("Ad [20]") saldiran sayilmaz: tamir / kacis PET'ten
        // degil sahibinden yapilir (eskiden tamir dusman PET'inden 1800 kaciyordu). PET hasari can takibiyle gorulur.
        for (Player pl : ents.getPlayers()) {
            if (pl.getId() == hero.getId() || !pl.isValid() || !pl.isAttacking(hero)) continue;
            if (pl.getEntityInfo() != null && petName(pl.getEntityInfo().getUsername())) continue;
            return pl;
        }
        for (Npc n : ents.getNpcs())
            if (n.isValid() && !isFake(n) && n.isAttacking(hero)) return n;
        return null;
    }

    /** Log icin: en yakin NPC ve dusman, bize ates eden. */
    /** Kesilecek listedeki (tablo) en yakin gercek NPC. */
    public Npc nearestKillable(Locatable p) {
        Npc best = null;
        double bd = Double.MAX_VALUE;
        for (Npc n : ents.getNpcs()) {
            if (!killable(n)) continue;
            double d = n.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    public String dangerInfo(Locatable p) {
        Npc n = nearestNpc(p);
        Npc k = nearestKillable(p);
        Player e = nearestEnemy(p);
        Ship a = attackerOfHero();
        return "en yakin NPC: " + (n == null ? "yok" : Log.name(n) + " " + (int) n.distanceTo(p)
                + (n.isAttacking(hero) ? " (ates ediyor)" : ""))
                + (k == n ? "" : " | en yakin kesilecek: " + (k == null ? "yok" : Log.name(k) + " " + (int) k.distanceTo(p)
                + (k.isBlacklisted() ? " (yok sayilmis)" : "")))
                + " | en yakin dusman: " + (e == null ? "yok" : Log.name(e) + " " + (int) e.distanceTo(p)
                + (e.isAttacking(hero) ? " (ates ediyor)" : ""))
                + (a == null ? "" : " | ates eden: " + Log.name(a) + " " + (int) a.distanceTo(hero));
    }

    /** DarkBot'a ayarlarin degistigini bildirir; dosyaya birkac saniye icinde yazilir. */
    public static void saveConfig() {
        try {
            com.github.manolo8.darkbot.config.ConfigEntity.changed();
        } catch (Throwable t) {
            Log.every("save", 60_000, "AYAR", "Ayar kaydi bildirilemedi: " + t);
        }
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    /** Gemi DarkBot'ta secili calisma haritasinda mi (olunce usse dogulunca BotPlus bir sey yapmaz). */
    public boolean onWorkingMap() {
        try {
            Integer id = workingMap.getValue();
            return id != null && star.getCurrentMap() != null && star.getCurrentMap().getId() == id;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Calisma haritasi id (bilinmiyorsa -1). */
    public int workingMapId() {
        Integer id = value(workingMap, -1);
        return id;
    }

    /** Son "Seref Puani" oyun mesaji: PvP oldurmesini dogrulamak icin (2.6.0). */
    public volatile long honorMsgAt;

    /** Son EMP basilisi (Defense ya da kacis): EMP Donusu icin. */
    public volatile long empUsedAt;
    /** Yaklasinca Hiz Yetenegi: kacis hiz yetenegini tutuyor mu (Defense'in Gemi Yetenegi o ana kadar basilmaz). */
    public volatile java.util.function.BooleanSupplier holdSpeed = () -> false;

    /** Hiz yetenegi mi (Gemi Yetenegi secimi de olabilir: Pusat Speed Sap hizlandirir). */
    public static boolean speedType(eu.darkbot.api.game.items.SelectableItem.Ability a) {
        if (a == null) return false;
        if (a == eu.darkbot.api.game.items.SelectableItem.Ability.PUSAT_PLUS_SPEED_SAP) return true;
        for (eu.darkbot.api.game.items.SelectableItem.Ability s : SPEED) if (s == a) return true;
        return false;
    }

    /**
     * Yaklasinca Hiz Yetenegi: once Defense'teki Gemi Yetenegi (hiz turuyse), yoksa gemideki hiz yetenekleri;
     * "Uzun Yolda Hiz Yetenegi" ayarindan bagimsiz. Basildiysa true.
     */
    public boolean useSpeedNow(String why) {
        java.util.List<eu.darkbot.api.game.items.SelectableItem.Ability> list = new java.util.ArrayList<>();
        try {
            if (config.PROTECT.ENABLED && config.PROTECT.COUNTER != null
                    && config.PROTECT.COUNTER.contains(BotPlusConfig.Counter.ABILITY) && speedType(config.PROTECT.ABILITY))
                list.add(config.PROTECT.ABILITY);
        } catch (RuntimeException ignored) {
        }
        java.util.Collections.addAll(list, SPEED);
        // 2.6.0: Pusat Speed Sap Defense'te secili olmasa da kacista kullanilir.
        if (!list.contains(eu.darkbot.api.game.items.SelectableItem.Ability.PUSAT_PLUS_SPEED_SAP))
            list.add(eu.darkbot.api.game.items.SelectableItem.Ability.PUSAT_PLUS_SPEED_SAP);
        for (eu.darkbot.api.game.items.SelectableItem.Ability a : list) {
            try {
                if (!items.getItem(a, eu.darkbot.api.game.items.ItemFlag.AVAILABLE).isPresent()) continue;
                eu.darkbot.api.game.items.ItemUseResult r = items.useItem(a, eu.darkbot.api.game.items.ItemFlag.USABLE,
                        eu.darkbot.api.game.items.ItemFlag.READY);
                if (r != null && r.isSuccessful()) {
                    Stats.count("hiz yetenegi");
                    Log.i("HIZ", a + " kullanildi (" + why + ")");
                    return true;
                }
                Log.every("speednow" + a, 10_000, "HIZ", a + " basilamadi: " + r + " (" + why + ")");
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private static final eu.darkbot.api.game.items.SelectableItem.Ability[] SPEED = {
            eu.darkbot.api.game.items.SelectableItem.Ability.LIGHTNING,
            eu.darkbot.api.game.items.SelectableItem.Ability.CITADEL_TRAVEL,
            eu.darkbot.api.game.items.SelectableItem.Ability.TARTARUS_SPEED_BOOST,
            eu.darkbot.api.game.items.SelectableItem.Ability.TARTARUS_PLUS_SPEED_BOOST};
    private long speedTryAt;
    private boolean abilitiesLogged, noSpeedLogged;

    /** Uzun yol (Plus: Uzun Yolda Hiz Yetenegi): gemide hiz yetenegi varsa ve hazirsa kullanilir. */
    public void speedAbility(double dist, String why) {
        if (!config.PLUS.SPEED_ABILITY() || dist < 3000) return;
        long now = now();
        if (now - speedTryAt < 2000) return;
        speedTryAt = now;
        if (!abilitiesLogged) {
            abilitiesLogged = true;
            logAbilities();
        }
        boolean any = false;
        // 2.6.0: Pusat Speed Sap de hiz yetenegi (eskiden listede yoktu: "hiz yetenegi yok" yaziyordu). Yetenekleri
        // Kacisa Sakla aciksa yolda harcanmaz (kacista hazir kalsin).
        java.util.List<eu.darkbot.api.game.items.SelectableItem.Ability> travel = new java.util.ArrayList<>();
        java.util.Collections.addAll(travel, SPEED);
        if (!saveForEscape()) travel.add(eu.darkbot.api.game.items.SelectableItem.Ability.PUSAT_PLUS_SPEED_SAP);
        for (eu.darkbot.api.game.items.SelectableItem.Ability a : travel) {
            try {
                if (!items.getItem(a, eu.darkbot.api.game.items.ItemFlag.AVAILABLE).isPresent()) continue;
                any = true;
                eu.darkbot.api.game.items.ItemUseResult r = items.useItem(a, eu.darkbot.api.game.items.ItemFlag.USABLE,
                        eu.darkbot.api.game.items.ItemFlag.READY);
                if (r != null && r.isSuccessful()) {
                    Stats.count("hiz yetenegi");
                    Log.i("HIZ", a + " kullanildi (" + why + ", " + (int) dist + " yol)");
                } else {
                    Log.every("speedfail", 30_000, "HIZ", a + " kullanilamadi: " + r + " (" + why + ")");
                }
                return;
            } catch (RuntimeException e) {
                Log.every("speedfail", 30_000, "HIZ", a + " kullanilamadi: " + e);
            }
        }
        if (!any && !noSpeedLogged) {
            noSpeedLogged = true;
            Log.i("HIZ", "hiz yetenegi yok (gemide " + SPEED.length + " hiz yeteneginden hicbiri bulunmadi)");
        }
    }

    /** Oturumda bir kez: gemideki yetenekler loga yazilir. */
    private void logAbilities() {
        try {
            StringBuilder sb = new StringBuilder();
            for (eu.darkbot.api.game.items.Item it : items.getItems(eu.darkbot.api.game.items.ItemCategory.SHIP_ABILITIES)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(it.getId()).append(it.isAvailable() ? "" : " (yok)");
            }
            Log.i("HIZ", "gemi yetenekleri: " + (sb.length() == 0 ? "yok" : sb));
        } catch (RuntimeException e) {
            Log.i("HIZ", "gemi yetenekleri okunamadi: " + e);
        }
    }

    /** Gemi bilgisi yuklendi mi (canlaninca ilk anlarda can 0 gorunur). */
    public boolean heroLoaded() {
        return hero.getHealth() != null && hero.getHealth().getMaxHp() > 0;
    }

    /**
     * Bulucunun sahte NPC'si (DarkBot FakeNpc / Npc.Fake). Can bilgisine bakilmaz: gercek NPC'nin cani oyun
     * yukleyene kadar 0 gorunebiliyor (1.3.0'da bu yuzden gercek NPC'ler sahte sanilip secilmiyordu).
     */
    public static boolean isFake(Npc n) {
        return n == null || n.getClass().getSimpleName().contains("Fake");
    }

    public static double hp(Ship s) {
        return s.getHealth() == null || s.getHealth().getMaxHp() <= 0 ? 1 : s.getHealth().hpPercent();
    }

    public double heroHp() {
        return hp(hero);
    }

    public boolean isOurPet(Ship s) {
        return s instanceof Pet && pet.isValid() && s.getId() == pet.getId();
    }

    private boolean petOfGroupMember(Pet p) {
        try {
            return group.hasGroup() && group.getMember(p.getOwnerId()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isGroupMember(Ship s) {
        try {
            return group.hasGroup() && group.getMember(s) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Dost: grup uyesi, ayni klan, muttefik ya da paktli klan. */
    public boolean isFriend(Ship s) {
        if (isGroupMember(s)) return true;
        EntityInfo info = s.getEntityInfo();
        if (info == null) return false;
        EntityInfo me = hero.getEntityInfo();
        if (me != null && info.getClanId() != 0 && info.getClanId() == me.getClanId()) return true;
        EntityInfo.Diplomacy d = info.getClanDiplomacy();
        return d == EntityInfo.Diplomacy.ALLIED || d == EntityInfo.Diplomacy.NOT_ATTACK_PACT;
    }

    /** NPC'ye ates eden baska bir oyuncu (bizim gemi, PET ve grup uyeleri haric). PET ateş ediyorsa sahibi aranmaz. */
    public Player otherAttacker(Npc n) {
        for (Player p : ents.getPlayers()) {
            if (p.getId() == hero.getId() || !p.isValid()) continue;
            if (p.isAttacking(n) && !isGroupMember(p)) return p;
        }
        return null;
    }

    /** Bulucu sinyaline dogru dolasilabilir mi (Locator kurar). */
    public java.util.function.Predicate<Location> pingOk = l -> true;

    /** Bulucunun su an aradigi NPC turu (PET bulucu secimi). */
    public volatile String locatorPick;
    /** Bulucunun aradigi turun tablo bilgisi (kesilecek mi): sahte NPC beklenmeden tur bilinir. */
    public volatile eu.darkbot.api.config.types.NpcInfo locatorPickInfo;
    /** Koruma: PET HP Link istendi (bu zamana kadar). */
    public long hpLinkUntil;
    /** HP Link yeteneginin kullanildigi (bekleme suresinin basladigi) an. */
    public long hpLinkUsedAt;
    /** Gemiye son hasar gelen an (can + kalkan dustu; Defense izler). */
    public long heroDamageAt;
    /** Bosta tamir suruyor mu (Repair kurar): dolasirken tamir konfigurasyonu / PET tamiri / liste disi NPC'den kacis. */
    public java.util.function.BooleanSupplier idleRepair = () -> false;
    /** NPC oldu (yer): bulucunun o yerde kalan eski isaretine gidilmesin (Locator kurar). */
    public java.util.function.Consumer<Location> npcDied = l -> { };

    /** Gemi saldiri altinda mi: biri ates ediyor ya da can + kalkan son 2 sn icinde dustu. */
    public boolean underAttack() {
        return attackerOfHero() != null || now() - heroDamageAt < 2000;
    }

    /** Savasta sayilan son an (bize ates eden var / gemi hasar aldi / biz ates ediyoruz). */
    private long combatAt = now();
    private String combatWhy = "";

    /**
     * Savas durumu (her tik): oyunda "savasta" bayragi okunamiyor; bize ates eden, gemi hasari ve kendi atesimizden
     * cikarilir. PET modulu degisimi (kombo tamir, bulucu tazeleme) savas bittikten bir sure sonra yapilir:
     * savasta oyun Kombo Gemi Tamiri'ni baslatmiyor (PET Kombo Koruma'da kaliyor, menu tekrar tekrar aciliyor).
     */
    public void trackCombat() {
        long now = now();
        String why = null;
        try {
            Ship a = attackerOfHero();
            if (a != null) why = "ates eden: " + Log.name(a);
            else if (now - heroDamageAt < 300) why = "hasar aliniyor";
            else if (attack.isAttacking()) why = "ates ediliyor";
        } catch (RuntimeException ignored) {
        }
        if (why != null) {
            combatAt = now;
            combatWhy = why;
        }
    }

    /** Savas bittikten beri gecen sure (ms); savastaysa 0. */
    public long quietMs() {
        return Math.max(0, now() - combatAt);
    }

    /** En az bu kadar suredir savasta degil mi. */
    public boolean outOfCombat(long ms) {
        return quietMs() >= ms;
    }

    /** Son savas nedeni (log). */
    public String combatWhy() {
        return combatWhy;
    }

    /**
     * Gemi bu modda mi: DarkBot'un konfig sinifinda (Config.ShipConfig) isInMode son basilan formasyon tusuna bakar
     * (gercek formasyona degil); tus formasyona cevrilip konfig + formasyon dogrudan karsilastirilir.
     */
    public boolean inMode(ShipMode m) {
        if (m == null) return false;
        try {
            if (m instanceof com.github.manolo8.darkbot.config.Config.ShipConfig) {
                Character key = ((com.github.manolo8.darkbot.config.Config.ShipConfig) m).FORMATION;
                eu.darkbot.api.game.items.SelectableItem.Formation f = key == null ? null : items.getItem(key,
                        eu.darkbot.api.game.items.ItemCategory.DRONE_FORMATIONS,
                        eu.darkbot.api.game.items.SelectableItem.Formation.class).orElse(null);
                if (f != null) return hero.getConfiguration() == m.getConfiguration() && hero.getFormation() == f;
            }
        } catch (Throwable ignored) {
        }
        return hero.isInMode(m);
    }

    /** PET modulu degisimi icin gereken savassiz sure. */
    public static final long PET_QUIET_MS = 4_000;

    /** Kombo Gemi Tamiri yeteneginin kullanildigi an. */
    public long comboRepairUsedAt;
    /** HP Link etkisi (oyunda 20 sn, bekleme 240 sn): bu surede PET HP Link'te kalir. */
    public static final long HP_LINK_ACTIVE_MS = 20_000;
    /** Kombo Gemi Tamiri etkisi (oyunda 5 sn). */
    public static final long COMBO_REPAIR_ACTIVE_MS = 5_000;
    private Boolean hpLinkCdWas, comboCdWas;

    /** HP Link etkisi suruyor mu (kullanildiktan sonra 20 sn). */
    public boolean hpLinkActive(long now) {
        return hpLinkUsedAt > 0 && now - hpLinkUsedAt < HP_LINK_ACTIVE_MS;
    }

    /**
     * PET yeteneklerinin kullanildigi an: API'de etkinin surup surmedigi yok, sadece bekleme suresi var.
     * Bekleme suresinin basladigi an = yetenegin kullanildigi an (her tik cagrilir).
     */
    public void trackPetCooldowns() {
        long now = now();
        boolean hl, cr;
        try {
            hl = pet.hasCooldown(eu.darkbot.api.game.enums.PetGear.HP_LINK);
            cr = pet.hasCooldown(eu.darkbot.api.game.enums.PetGear.COMBO_REPAIR);
        } catch (RuntimeException e) {
            return;
        }
        // Ilk okumada baslangic durumu alinir (acilista zaten beklemedeki yetenek "simdi kullanildi" sayilmaz).
        PetGear cur = null;
        try {
            cur = pet.isActive() ? pet.getGear() : null;
        } catch (RuntimeException ignored) {
        }
        if (hpLinkCdWas != null && hl && !hpLinkCdWas && (now < hpLinkUntil + 2000 || cur == PetGear.HP_LINK)) {
            hpLinkUsedAt = now;
            hpLinkUntil = 0;
            Log.i("KORUMA", "PET HP Link kullanildi, " + HP_LINK_ACTIVE_MS / 1000 + " sn PET HP Link'te kalacak");
        }
        if (comboCdWas != null && cr && !comboCdWas) {
            comboRepairUsedAt = now;
            Log.d("PET", "Kombo Gemi Tamiri kullanildi (" + COMBO_REPAIR_ACTIVE_MS / 1000 + " sn)");
        }
        hpLinkCdWas = hl;
        comboCdWas = cr;
    }
    /** Dovus bu NPC'yi su an secebilir mi (CombatPlus kurar). */
    public java.util.function.Predicate<Npc> combatCanTake = n -> false;
    /** Cal bu NPC'yi alabilir mi (Steal kurar); dovus birakmak yerine devreder. */
    public java.util.function.Predicate<Npc> stealCan = n -> false;

    private Location pingStable, pingCand;
    private long pingSeen, pingCandSince;
    /** Isaretin son dondugu an (tazeleme tur degisimi); sonrasinda kisa sure atlama korumasi kosulsuz. */
    private long pingFrozenAt;
    private static final long FREEZE_GUARD_MS = 2000;
    /** Isaretin okundugu harita (degisince tutulan isaret silinir). */
    private int pingMap = Integer.MIN_VALUE;

    /**
     * Bulucu isareti (haritadaki kirmizi isaret). DarkBot isareti 1 sn gormezse siler: son konum 3 sn tutulur
     * (isaret yanip sonerken gemi durmaz). Isaret 1500'den uzak bir yere atlarsa 1 sn ayni yerde kalmadan
     * yeni yere gecilmez (anlik atlama); tutulan yer olu NPC'nin yeriyse yeni yere hemen gecilir.
     */
    public java.util.Optional<Location> ping() {
        fresh();
        // Donma ayni tikte onbellekten sonra baslamis olabilir: onbellek kullanilmaz.
        if (pingFrozen.getAsBoolean()) {
            freezePing();
            pingCache = null;
            return java.util.Optional.empty();
        }
        if (pingCache == null) pingCache = readPing();
        return pingCache;
    }

    /**
     * Tazeleme tur degistiriyor (LocatorRefresh her tik cagirir): tutulan isaret silinmez ama suresi doldurulur;
     * donus sonrasi kisa sure uzak yere atlama yine 1 sn beklenir (baska turun isareti hemen alinmasin).
     */
    public void freezePing() {
        pingFrozenAt = now();
        pingSeen = 0;
        pingCand = null;
        pingCache = null;
    }

    /** Olu NPC yeri mi (Locator kurar; yan etkisiz, ping/tickDead cagirmaz: readPing icinden kullanilir). */
    public java.util.function.Predicate<Location> deadSpot = l -> false;

    private int mapIdSeen = Integer.MIN_VALUE;
    private int mapEpoch;

    /** Harita degisim sayaci (bilinmeyen harita -1 sayilmaz); bulucu isleri degisince kendini sifirlar. */
    public int mapEpoch() {
        int id = mapIdNow();
        if (id != -1 && id != mapIdSeen) {
            if (mapIdSeen != Integer.MIN_VALUE) mapEpoch++;
            mapIdSeen = id;
        }
        return mapEpoch;
    }

    /** Bulucu tazelemesi tur degistiriyor: isaret baska turun, hic kullanilmaz (LocatorRefresh kurar). */
    public java.util.function.BooleanSupplier pingFrozen = () -> false;
    /** Bu isaret yok sayilir (tazelemede gorulen baska turun isareti; LocatorRefresh kurar). */
    public java.util.function.Predicate<Location> pingBlocked = l -> false;

    /** Ham bulucu isareti; tazeleme sirasindaki baska tur isareti yok sayilir. */
    public Location livePing() {
        if (pingFrozen.getAsBoolean()) return null;
        Location live = null;
        try {
            live = pet.getLocatorNpcLoc().orElse(null);
        } catch (RuntimeException ignored) {
        }
        return live != null && pingBlocked.test(live) ? null : live;
    }

    private java.util.Optional<Location> readPing() {
        long now = now();
        int map = mapEpoch();
        if (map != pingMap) {
            // Harita degisti: onceki haritanin isareti tutulmaz.
            if (pingMap != Integer.MIN_VALUE && pingStable != null) Log.d("BULUCU", "harita degisti, tutulan isaret silindi");
            pingMap = map;
            pingStable = pingCand = null;
            pingSeen = 0;
        }
        Location live = livePing();
        if (live != null) {
            live = Location.of(live.getX(), live.getY());
            // Tazeleme donusunden hemen sonra uzak atlama kosulsuz 1 sn beklenir (baska turun gec kalan isareti).
            boolean guard = now - pingFrozenAt < FREEZE_GUARD_MS;
            boolean near = pingStable != null && pingStable.distanceTo(live) < 1500;
            // Tutulan yer olu NPC'nin eski isareti: yeni canli yere hemen gecilir (1 sn beklenmez).
            boolean free = !guard && (pingStable == null || now - pingSeen > 3000 || deadSpot.test(pingStable));
            if (near || free) {
                pingStable = live;
                pingCand = null;
            } else {
                if (pingCand == null || pingCand.distanceTo(live) > 500) {
                    pingCand = live;
                    pingCandSince = now;
                }
                if (now - pingCandSince >= 1000) {
                    pingStable = live;
                    pingCand = null;
                }
            }
            pingSeen = now;
        }
        return pingStable != null && now - pingSeen <= 3000 ? java.util.Optional.of(pingStable) : java.util.Optional.empty();
    }

    /** Baskasi vursa da saldirilir: "Saldirilani yok say" ya da "Ikinci saldir" (zaten baskasi vurunca saldirir). */
    public static boolean ignoresAttacked(Npc n) {
        eu.darkbot.api.config.types.NpcInfo i = n == null ? null : n.getInfo();
        return i != null && (i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.IGNORE_ATTACKED)
                || i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.ATTACK_SECOND));
    }

    /** Baskasinin kilidindeki (sahipligindeki) NPC birakilmaz: "Sahipligi yok say" ya da "Ikinci saldir". */
    public static boolean ignoresOwnership(Npc n) {
        eu.darkbot.api.config.types.NpcInfo i = n == null ? null : n.getInfo();
        return i != null && (i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.IGNORE_OWNERSHIP)
                || i.hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.ATTACK_SECOND));
    }

    /** Baska oyuncu / PET bu NPC'ye ates ediyor mu (tik basina bir tarama, sonra O(1)). */
    public boolean attackedByOthers(Npc n) {
        return n != null && attackedIds().contains(n.getId());
    }

    public Npc findNpc(int id) {
        for (Npc n : ents.getNpcs()) if (n.getId() == id && n.isValid()) return n;
        return null;
    }

    public Npc targetNpc() {
        Npc n = attack.getTargetAs(Npc.class);
        return n == null || !n.isValid() || isFake(n) ? null : n;
    }

    /** Hedef NPC'ye gercekten ates ediliyor mu. */
    public boolean shooting(Npc n) {
        return n != null && Objects.equals(hero.getTarget(), n) && hero.isAttacking(n);
    }

    /** Harita kenarina uzaklik (kenar disi radyasyonlu bolge). Harita bilinmiyorsa cok buyuk doner. */
    /** Harita ortasi (sinirlar bilinmiyorsa null). */
    public Location mapCenter() {
        try {
            eu.darkbot.api.game.other.Area.Rectangle r = star.getCurrentMapBounds();
            if (r == null || r.getWidth() <= 0) return null;
            return Location.of((r.getX() + r.getX2()) / 2, (r.getY() + r.getY2()) / 2);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Koseye uzaklik: noktanin en yakin iki kenara uzakliklarindan buyugu (ikisi de kucukse kose). */
    public double cornerDist(Locatable p) {
        try {
            eu.darkbot.api.game.other.Area.Rectangle r = star.getCurrentMapBounds();
            if (r == null || r.getWidth() <= 0) return 1e9;
            double dx = Math.min(p.getX() - r.getX(), r.getX2() - p.getX());
            double dy = Math.min(p.getY() - r.getY(), r.getY2() - p.getY());
            return Math.max(dx, dy);
        } catch (RuntimeException e) {
            return 1e9;
        }
    }

    public double edgeDist(Locatable p) {
        try {
            eu.darkbot.api.game.other.Area.Rectangle r = star.getCurrentMapBounds();
            if (r == null || r.getWidth() <= 0) return 1e9;
            return Math.min(Math.min(p.getX() - r.getX(), r.getX2() - p.getX()),
                    Math.min(p.getY() - r.getY(), r.getY2() - p.getY()));
        } catch (RuntimeException e) {
            return 1e9;
        }
    }

    /** En yakin NPC ya da dusman oyuncu. */
    public Locatable nearestDanger(Locatable p) {
        Locatable best = null;
        double bd = Double.MAX_VALUE;
        for (Npc n : ents.getNpcs()) {
            if (!n.isValid() || isFake(n)) continue;
            double d = n.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        for (Player pl : ents.getPlayers()) {
            if (!isEnemyPlayer(pl)) continue;
            double d = pl.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = pl;
            }
        }
        return best;
    }

    /**
     * Guvenli nokta: kenardan (radyasyondan) en az 1500 icerde, tehlikeden uzak. Cok uzak tehlike (3000+) esit sayilir,
     * boylece gemi haritanin kosesine kacmaz, ortaya yakin kalir. away verilirse o yonun tersine bakilir.
     */
    public Location safePoint(double step, Locatable away) {
        Location best = null;
        double bestScore = -Double.MAX_VALUE;
        // Tehlikeler (gercek NPC'ler + dusman oyuncular) bir kez toplanir, 16 aday bu listeyle olculur.
        List<Location> dangers = new ArrayList<>();
        for (Npc n : ents.getNpcs()) if (n.isValid() && !isFake(n)) dangers.add(Location.of(n.getX(), n.getY()));
        for (Player pl : ents.getPlayers()) if (isEnemyPlayer(pl)) dangers.add(Location.of(pl.getX(), pl.getY()));
        for (int k = 0; k < 16; k++) {
            double base = away != null ? hero.angleTo(away) + Math.PI : Math.random() * 2 * Math.PI;
            double spread = away != null ? Math.PI / 2 : Math.PI;
            Location p = ringPoint(hero, step, base, spread);
            double edge = edgeDist(p);
            double danger = 3000;
            for (Location d : dangers) danger = Math.min(danger, d.distanceTo(p));
            double score = danger + Math.min(edge, 3000) * 0.5 - (edge < 1500 ? 5000 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** Merkezin etrafinda, gidilebilir rastgele nokta. */
    public Location ringPoint(Locatable center, double radius, double baseAngle, double spread) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) {
            double a = baseAngle + (r.nextDouble() * 2 - 1) * spread;
            Location l = Location.of(center, a, radius);
            if (move.canMove(l)) return l;
        }
        return Location.of(center, baseAngle, radius);
    }
}