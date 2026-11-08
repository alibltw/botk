package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.types.PercentRange;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.extensions.FeatureInfo;
import eu.darkbot.api.managers.ExtensionsAPI;

import java.util.EnumSet;

/**
 * Ayarlar: BotPlus menusu (CoreConfig) + acik alt modullerin ayarlari -> kodun okudugu gorunum (BotPlusConfig).
 * Kapali alt modulun ozellikleri gorunumde kapali sayilir. Ilk acilista eski (tek parca) ayarlar yeni menulere
 * ve alt modullere bir kez aktarilir, alt modullerin acik / kapali durumu eski ayara gore kurulur.
 */
public final class Settings {

    /** Defense kapaliyken kullanilan kapali ayarlar (her tik yeni nesne kurulmaz). */
    private static final BotPlusConfig.HpLinkSet OFF_LINK = new BotPlusConfig.HpLinkSet();
    /** Dusmani Hatirla gorunumu (CoreConfig'ten kurulur). */
    private final BotPlusConfig.Killer VIEW_KILLER = new BotPlusConfig.Killer();
    /** 2.7.0: Dusman Saldirinca gelismis ayarlarinin gorunumu (menude listeler; sabitler burada). */
    private final CoreConfig.EscapePlusSet VIEW_PLUS = new CoreConfig.EscapePlusSet();
    /** PvP Plus'in ayari (Aktif tiki kapali olsa da): Klan Uyelerine Saldirma her yerde gecerli. */
    private com.botplus.pvp.Pvp.Config pvpRaw;

    private final Context context;
    private final ExtensionsAPI ext;
    private CoreConfig core;

    /** Su an acik alt moduller (kapaliysa null), her tik yenilenir. */
    public Combat combat;
    public Rsb rsb;
    public NpcSteal steal;
    public Repair repair;
    public EnemyLocator locator;
    public Defense defense;
    public com.botplus.pvp.Pvp pvp;
    public com.botplus.push.Push push;

    public Settings(Context context) {
        this.context = context;
        ExtensionsAPI e = null;
        try {
            e = context.api.requireAPI(ExtensionsAPI.class);
        } catch (Throwable t) {
            Log.i("AYARLAR", "alt moduller okunamiyor (ExtensionsAPI yok): " + t);
        }
        this.ext = e;
    }

    public CoreConfig core() {
        return core;
    }

    /** BotPlus menusu geldi (setConfig): eski ayarlar bir kez aktarilir. */
    public void setCore(CoreConfig c) {
        this.core = c;
        c.fixCore();
        Targeting.useStore(c.ASIL_MENZIL, c.YAZILAN_MENZIL, Context::saveConfig);
        if (!c.MIGRATED) {
            if (hasOldSettings(c)) {
                migrateCore(c);
                Log.i("AYARLAR", "eski ayarlar yeni BotPlus menusune aktarildi");
            } else {
                // Yeni kurulum (eski kayit yok): hicbir sey aktarilmaz, alt modullerin varsayilanlari ve kullanicinin
                // yaptigi ayarlar ezilmez (eskiden ilk acilista Combat'in Tam Kontrol'u kapaniyordu).
                c.MIGRATED_ENABLED = true;
                c.MIGRATED_SUBS.add(Combat.class.getName());
                c.MIGRATED_SUBS.add(Rsb.class.getName());
                c.MIGRATED_SUBS.add(NpcSteal.class.getName());
                c.MIGRATED_SUBS.add(Repair.class.getName());
                c.MIGRATED_SUBS.add(EnemyLocator.class.getName());
                c.MIGRATED_SUBS.add(Defense.class.getName());
                c.MIGRATED_SUBS.add(PET_TO_COMBAT);
                c.MIGRATED_SUBS.add(ONPLAYER_TO_CORE);
                c.MIGRATED_SUBS.add(KILLER_TO_CORE);
                c.MIGRATED_SUBS.add(REMEMBER_TO_MULTI);
                Log.i("AYARLAR", "eski BotPlus ayari yok, aktarma yapilmadi");
            }
            c.MIGRATED = true;
            Context.saveConfig();
        }
        c.migrate27IfNeeded();
    }

    /** CoreConfig'in kendi (yeni menu) alanlari: eski ayar karsilastirmasinda ve ayar dokumunde kullanilir. */
    private static final String[] CORE_KEYS = {"GENEL", "HAREKET_SET", "KUTU_SET", "DUSMAN", "PET_AYAR", "KAYIT"};
    private static final java.util.Set<String> CORE_ONLY = new java.util.HashSet<>(java.util.Arrays.asList(
            "GENEL", "HAREKET", "DOLASMA", "KUTU", "DUSMAN", "HP_BEKLE", "PET_AYAR", "YENILEME", "KAYIT",
            "HAREKET_SET", "KUTU_SET", "MIGRATED_27",
            "MIGRATED", "MIGRATED_SUBS", "MIGRATED_ENABLED", "ASIL_MENZIL", "YAZILAN_MENZIL"));

    /**
     * Eski (1.x) ayar kaydi var mi: eski alanlardan biri varsayilandan farkliysa evet. DarkBot eklentilerde
     * java.lang.reflect'i engelliyor (log: NoClassDefFoundError): karsilastirma Gson ile yapilir.
     */
    private static boolean hasOldSettings(CoreConfig c) {
        try {
            com.google.gson.Gson g = new com.google.gson.Gson();
            com.google.gson.JsonObject cur = g.toJsonTree(c).getAsJsonObject();
            com.google.gson.JsonObject def = g.toJsonTree(new CoreConfig()).getAsJsonObject();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : def.entrySet()) {
                if (CORE_ONLY.contains(e.getKey())) continue;
                if (!java.util.Objects.equals(e.getValue(), cur.get(e.getKey()))) return true;
            }
            return false;
        } catch (Throwable t) {
            Log.i("AYARLAR", "eski ayar kontrolu yapilamadi, aktariliyor: " + t);
            return true;
        }
    }

    // Eski ayar nesnelerinden alan alan kopya (mevcut nesne korunur: DarkBot'un ayar penceresi ayni nesneyi gosterir).
    private static void copyArc(BotPlusConfig.ArcSettings s, BotPlusConfig.ArcSettings d) {
        if (s != null && d != null) d.ANGLE = s.ANGLE;
    }

    private static void copySafe(BotPlusConfig.SafeSettings s, BotPlusConfig.SafeSettings d) {
        if (s == null || d == null) return;
        d.SIDE = s.SIDE;
        d.LEARN = s.LEARN;
        d.LOCK = s.LOCK;
        d.APPROACH = s.APPROACH;
        d.EXIT = s.EXIT;
    }

    private static void copyOut(BotPlusConfig.OutrangeSettings s, BotPlusConfig.OutrangeSettings d) {
        if (s != null && d != null) d.MARGIN = s.MARGIN;
    }

    private static void copyHpLink(BotPlusConfig.HpLinkSet s, BotPlusConfig.HpLinkSet d) {
        if (s == null || d == null) return;
        d.ENABLED = s.ENABLED;
        if (s.WHEN != null) d.WHEN = java.util.EnumSet.copyOf(s.WHEN.isEmpty() ? java.util.EnumSet.noneOf(BotPlusConfig.When.class) : s.WHEN);
        d.HP = s.HP;
    }

    private static void copyKiller(BotPlusConfig.Killer s, BotPlusConfig.Killer d) {
        if (s == null || d == null) return;
        d.ENABLED = s.ENABLED;
        d.MINUTES = s.MINUTES;
    }

    /** Her tik: alt moduller okunur, gorunum kurulur. */
    public void refresh() {
        if (core == null) return;
        // Tam okuma en fazla saniyede bir (her tik alt modul aramasi + gorunum kurulumu gereksiz yuk);
        // bot yeniden baslatildiysa hemen.
        long nowR0 = Context.now();
        // Saat geri alindiysa (nowR0 < lastRefreshAt) da hemen okunur.
        if (lastRefreshAt != 0 && nowR0 >= lastRefreshAt && nowR0 - lastRefreshAt < REFRESH_MS
                && context.lastHaltedAt <= lastRefreshAt) return;
        core.fixCore();
        if (!core.MIGRATED_ENABLED && ext != null) {
            migrateEnabled(core);
            core.MIGRATED_ENABLED = true;
            Context.saveConfig();
        }
        combat = sub(Combat.class);
        rsb = sub(Rsb.class);
        steal = sub(NpcSteal.class);
        repair = sub(Repair.class);
        locator = sub(EnemyLocator.class);
        defense = sub(Defense.class);
        pvp = sub(com.botplus.pvp.Pvp.class);
        push = sub(com.botplus.push.Push.class);
        migrateSubs();
        migrateSubs27();
        restoreRanges();
        pvpRaw = pvp == null ? null : pvp.config;
        // Bot baslatilinca (ilk tik ya da bot durdurulup yeniden baslatilinca) tum ayarlar loga dokulur (test icin).
        // Eskiden her 5 sn'lik aralikta (gecici modul, takilma) tekrar dokuluyordu.
        long nowR = Context.now();
        boolean dump = lastRefreshAt == 0 || context.lastHaltedAt > lastRefreshAt;
        lastRefreshAt = nowR;
        if (dump) dumpAll();
        // Alt modulun kendi "Aktif" tiki kapaliysa eklenti acik olsa da kapali sayilir.
        if (rsb != null && !rsb.config.ENABLED) rsb = null;
        if (steal != null && !steal.config.ENABLED) steal = null;
        if (defense != null && !defense.config.ENABLED) defense = null;
        if (pvp != null && !pvp.config.ENABLED) pvp = null;
        if (push != null && !push.config.ENABLED) push = null;
        build(context.config);
        try {
            context.abilitiesTick();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 2.7.0: "Asil Menzillere Don" kaldirildi; yeni surumun ilk acilisinda menziller bir kez kendiliginden asil
     * degerlerine dondurulur (Combat acikken).
     */
    private void restoreRanges() {
        if (combat == null || core.MIGRATED_SUBS.contains(RESTORE_27)) return;
        int n = 0;
        try {
            eu.darkbot.api.config.ConfigSetting<java.util.Map<String, ?>> infos = context.api
                    .requireAPI(eu.darkbot.api.managers.ConfigAPI.class).requireConfig("loot.npc_infos");
            java.util.Map<String, ?> m = infos.getValue();
            if (m == null) return; // NPC listesi henuz okunamadi: sonra yeniden denenir
            n = Targeting.restoreAll(name -> {
                Object o = m.get(name);
                return o instanceof eu.darkbot.api.config.types.NpcInfo ? (eu.darkbot.api.config.types.NpcInfo) o : null;
            });
        } catch (Throwable t) {
            Log.every("restore27", 60_000, "MENZIL", "asil menziller geri yuklenemedi: " + t);
            return;
        }
        core.MIGRATED_SUBS.add(RESTORE_27);
        Log.i("MENZIL", "2.7.0 ilk acilis: " + n + " NPC'nin menzili asil degerine dondu (kayitli " + core.ASIL_MENZIL.size() + ")");
        Context.saveConfig();
    }

    /** 2.7.0 alt modul menu sadelestirmesi: her alt modul kendi bayragiyla bir kez (Aktif tiki kapali olsa da). */
    private void migrateSubs27() {
        boolean ch = false;
        CoreConfig.OnEnemySet ds = core.DUSMAN;
        if (combat != null && combat.config.migrate27()) ch = true;
        if (defense != null) {
            boolean emp = ds.ATLAT != null && ds.ATLAT.EMP;
            boolean late = ds.ATLAT != null && ds.ATLAT.SECENEK != null && ds.ATLAT.SECENEK.contains(BotPlusConfig.OutrunOpt.LATE_SPEED);
            if (defense.config.migrate27(emp, late)) ch = true;
        }
        if (pvp != null) {
            boolean same = push == null || push.config == null || push.config.SAME_CLAN;
            if (pvp.config.migrate27(same)) ch = true;
        }
        if (repair != null && !repair.config.MIGRATED_27) {
            Repair.Config r = repair.config;
            if (r.CAN_BEKLE == null) r.CAN_BEKLE = new Repair.HpWait();
            CoreConfig.HpWaitSet hb = core.HP_BEKLE;
            if (hb != null && hb.ENABLED) {
                r.CAN_BEKLE.HP = Math.max(1, hb.HP);
                if (hb.WHICH != null) r.CAN_BEKLE.WHICH = hb.WHICH;
                if (hb.MODE != null) r.CAN_BEKLE.MODE = hb.MODE;
            } else r.CAN_BEKLE.HP = 0;
            r.MIGRATED_27 = true;
            Log.i("AYARLAR", "2.7.0: HP Bekle -> Tamir -> Can Bekle (%" + r.CAN_BEKLE.HP + ")");
            ch = true;
        }
        if (ch) Context.saveConfig();
    }

    private long lastRefreshAt;
    private static final long REFRESH_MS = 1000;
    private static final String PET_TO_COMBAT = "pet->combat";
    private static final String RESTORE_27 = "restore-ranges-27";
    /** 2.1.0: Defense -> "Oyuncu Saldirinca" BotPlus menusundeki "Dusman Saldirinca" ayarina tasindi. */
    private static final String ONPLAYER_TO_CORE = "onplayer->core";
    private static final String REMEMBER_TO_MULTI = "remember->multi";
    /** 2.1.0: Defense'teki "Dusmani Hatirla" BotPlus menusune. */
    private static final String KILLER_TO_CORE = "killer->core";

    /** Tum BotPlus ayarlari (menu + alt moduller) loga: hangi ayar acik / kapali tek bakista gorulur. */
    private void dumpAll() {
        try {
            com.google.gson.Gson g = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            Log.i("AYARLAR", "===== TUM AYARLAR (bot baslatildi) =====");
            com.google.gson.JsonObject all = g.toJsonTree(core).getAsJsonObject();
            for (String k : CORE_KEYS) Log.i("AYARLAR", "BotPlus." + k + " = " + all.get(k));
            dumpSub(g, "Combat", Combat.class, combat == null ? null : combat.config, null);
            dumpSub(g, "RSB", Rsb.class, rsb == null ? null : rsb.config, rsb == null ? null : rsb.config.ENABLED);
            dumpSub(g, "NPC Steal", NpcSteal.class, steal == null ? null : steal.config, steal == null ? null : steal.config.ENABLED);
            dumpSub(g, "Repair", Repair.class, repair == null ? null : repair.config, null);
            dumpSub(g, "Enemy Locator", EnemyLocator.class, locator == null ? null : locator.config, null);
            dumpSub(g, "Defense", Defense.class, defense == null ? null : defense.config, defense == null ? null : defense.config.ENABLED);
            dumpSub(g, "PvP Plus", com.botplus.pvp.Pvp.class, pvp == null ? null : pvp.config, pvp == null ? null : pvp.config.ENABLED);
            dumpSub(g, "Push Korumasi", com.botplus.push.Push.class, push == null ? null : push.config, push == null ? null : push.config.ENABLED);
            Log.i("AYARLAR", "===== AYARLAR SONU =====");
        } catch (Throwable t) {
            Log.i("AYARLAR", "ayarlar yazilamadi: " + t);
        }
    }

    private void dumpSub(com.google.gson.Gson g, String name, Class<?> cls, Object cfg, Boolean active) {
        if (cfg == null) {
            Log.i("AYARLAR", name + ": KAPALI (eklenti listesinde kapali)");
            return;
        }
        String state = active == null ? "ACIK" : active ? "ACIK (Aktif tiki acik)" : "KAPALI (eklenti acik ama Aktif tiki kapali)";
        Log.i("AYARLAR", name + ": " + state + " " + g.toJson(cfg));
    }

    /** Gorunum sadece menu ve son okunan alt modullerle kurulur (alt modul sorgulanmaz). */
    public void rebuild() {
        if (core == null) return;
        core.fixCore();
        build(context.config);
    }

    private <T> T sub(Class<T> cls) {
        if (ext == null) return null;
        try {
            return ext.getFeature(cls).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- gorunum

    private void build(BotPlusConfig v) {
        CoreConfig c = core;
        v.ENABLED = c.GENEL.ENABLED;
        v.WORK = c.GENEL.WORK;
        v.SORT = c.GENEL.SORT.sort;

        // 2.7.0 Hareket listesi; Ileri Bakis 1500, Dolanma Acisi 45, PET Radar 3000 sabit, "Sadece Saldirgan" kaldirildi.
        java.util.Set<BotPlusConfig.MoveOpt> mv = c.HAREKET_SET;
        v.AVOID_UNLISTED = mv.contains(BotPlusConfig.MoveOpt.AVOID_UNLISTED);
        v.AVOID_NEAR_OFF = mv.contains(BotPlusConfig.MoveOpt.AVOID_NEAR_OFF);
        v.AVOID_AHEAD = 1500;
        v.AVOID_ANGLE = 45;
        v.AVOID_ONLY_AGGRESSIVE = false;
        v.PASS_PASSIVE = mv.contains(BotPlusConfig.MoveOpt.PASS_PASSIVE);
        // 2.7.0 Kutu listesi: "Uzaktaki Kutulari Yok Say" 3000, kapaliysa sinir yok.
        java.util.Set<BotPlusConfig.BoxOpt> bx = c.KUTU_SET;
        v.BOX_MAX_DIST = bx.contains(BotPlusConfig.BoxOpt.FAR_IGNORE) ? CoreConfig.BOX_FAR : 100_000;
        v.BOX_WAY = bx.contains(BotPlusConfig.BoxOpt.WAY_NEAR) ? BotPlusConfig.BoxWay.NEARBY
                : bx.contains(BotPlusConfig.BoxOpt.WAY_ROUTE) ? BotPlusConfig.BoxWay.ON_ROUTE : BotPlusConfig.BoxWay.IGNORE;

        // Plus ozellikleri: ana anahtar her zaman acik, tek tek ayarlar menulerden.
        v.PLUS.ENABLED = true;
        BotPlusConfig.PlusOther o = v.PLUS.OTHER;
        o.BOX_WAIT = bx.contains(BotPlusConfig.BoxOpt.WAIT);
        o.RIVAL = bx.contains(BotPlusConfig.BoxOpt.RIVAL);
        o.SHORT_ROAM = mv.contains(BotPlusConfig.MoveOpt.SHORT_ROAM);
        o.SMART_ROAM = mv.contains(BotPlusConfig.MoveOpt.SMART_ROAM);
        o.RADAR = 3000;
        o.SPEED_ABILITY = mv.contains(BotPlusConfig.MoveOpt.SPEED_ABILITY);
        o.UNSTUCK = mv.contains(BotPlusConfig.MoveOpt.UNSTUCK);
        o.STATS = c.KAYIT.STATS;

        BotPlusConfig.PlusTarget t = v.PLUS.TARGET;
        Combat.Config cb = combat == null ? null : combat.config;
        t.NEAREST = cb != null && cb.TARGET != null && cb.TARGET.NEAREST;
        t.FAST_LOCK = cb != null && cb.TARGET != null && cb.TARGET.FAST_LOCK;
        t.NEXT_TARGET = cb != null && cb.TARGET != null && cb.TARGET.NEXT_TARGET;
        t.WEAK_FIRST = cb != null && cb.TARGET != null && cb.TARGET.WEAK_FIRST;
        // Menzil ogrenme kaldirildi: sadece NPC listesindeki menzil (kullanici istegi).
        t.AUTO_RANGE = false;
        t.RANGE_DOWN = false;
        t.NO_LEARN_FLEE = true;

        // Can Bekle (2.7.0: Tamir eklentisinde; eski HP Bekle). "NPC'nin yaninda bekle" = eski HP Bekle; "dolasmaya devam"
        // = eski Yeni Dovus Icin En Az Can. Tamir eklentisi kapaliysa calismaz. Can 0: kapali.
        Repair.HpWait hw0 = repair == null || repair.config.CAN_BEKLE == null ? null : repair.config.CAN_BEKLE;
        boolean waitOn = hw0 != null && hw0.HP > 0;
        boolean near = waitOn && hw0.MODE != CoreConfig.HpWaitMode.ROAM;
        boolean roam = waitOn && hw0.MODE == CoreConfig.HpWaitMode.ROAM;
        t.HP_WAIT.ENABLED = near;
        t.HP_WAIT.WHICH = hw0 == null || hw0.WHICH == null ? BotPlusConfig.HpWaitWhich.FLAGGED : hw0.WHICH;
        t.HP_WAIT.HP = hw0 == null ? 70 : Math.max(1, hw0.HP);

        BotPlusConfig.Combat vc = v.COMBAT;
        vc.ENABLED = cb != null && cb.FULL;
        vc.FIGHT_MIN_HP = roam ? hw0.HP : 0;
        if (cb != null) {
            vc.STYLE = cb.STYLE != null ? cb.STYLE : BotPlusConfig.CombatStyle.ORBIT;
            vc.FIRST_HIT = cb.FIRST_HIT != null ? cb.FIRST_HIT : BotPlusConfig.FirstHit.NORMAL;
            vc.EAGLE_MIN_HP = cb.EAGLE_MIN_HP;
            // 2.6.0: tarzlarin ince ayarlari (Yorunge, Sarkac, Dalga, Liman, Yarim Ay, Menzil Kilidi) menude yok: her
            // zaman varsayilan degerler kullanilir (eski kayitli degerler okunmaz).
            if (cb.CLAW != null) vc.CLAW = cb.CLAW;
            // 2.7.0: Secenekler (Kacisi Onle haric) + Menzil listesi. Kovala %70 (dusus %30), vuramazsa en fazla %60'a.
            java.util.Set<BotPlusConfig.RangeOpt> rg = cb.MENZIL == null ? EnumSet.noneOf(BotPlusConfig.RangeOpt.class) : cb.MENZIL;
            java.util.Set<BotPlusConfig.CombatOption> co = EnumSet.noneOf(BotPlusConfig.CombatOption.class);
            if (cb.OPTIONS_SET != null)
                for (BotPlusConfig.CombatMenuOpt x : cb.OPTIONS_SET) if (x != null) co.add(BotPlusConfig.CombatOption.valueOf(x.name()));
            if (rg.contains(BotPlusConfig.RangeOpt.FLEE_CUT)) co.add(BotPlusConfig.CombatOption.FLEE_CUT);
            vc.OPTIONS = co;
            vc.FINISH_FIRST = cb.FINISH_FIRST;
            vc.CHASE = rg.contains(BotPlusConfig.RangeOpt.CHASE);
            vc.CHASE_DROP = 30;
            vc.NO_HIT_MIN = rg.contains(BotPlusConfig.RangeOpt.NO_HIT_SHRINK) ? 60 : 100;
        } else {
            vc.CHASE = false;
        }

        BotPlusConfig.Pet vp = v.PET;
        // Ana anahtar yok: her durum kendi menusundeki PET ayarina bakar. Saldiridaki PET Combat'tan (Combat kapaliysa
        // dovusu DarkBot yapar, PET'e de DarkBot'un ayari bakar).
        vp.ENABLED = true;
        BotPlusConfig.FightPet fp = combat == null || combat.config.PET == null ? BotPlusConfig.FightPet.NONE : combat.config.PET;
        vp.COMBAT_CHANGE = fp.gear != null;
        vp.COMBAT = fp.legacy();
        vp.REPAIR = c.PET_AYAR.REPAIR;
        vp.LOW_REPAIR = c.PET_AYAR.LOW_REPAIR;
        vp.LOW_HP = c.PET_AYAR.LOW_HP;
        vp.REFRESH_MIN = c.PET_AYAR.REFRESH_MIN;
        EnemyLocator.Config el = locator == null ? null : locator.config;
        vp.TRAVEL_LOCATOR = el != null && el.GO != null && el.GO.TRAVEL_LOCATOR;

        BotPlusConfig.Locator vl = v.LOCATOR;
        // Enemy Locator acikken bulucu sinyaline her zaman gidilir (eskiden ayri "Bulucu Bulunca Git" kutusu vardi;
        // ayarlar sifirlaninca gitme sessizce kapaniyordu).
        vl.GO = el != null;
        vl.DIRECT = el != null && el.GO != null && el.GO.DIRECT;
        vl.DROP = el != null && el.GO != null && el.GO.DROP;
        vl.DROP_HP = el == null || el.GO == null ? 50 : el.GO.DROP_HP;
        vl.IGNORE = el != null && el.ATTACKED != null && el.ATTACKED.IGNORE;
        vl.EVADE_GUARD = el == null || el.ATTACKED == null || el.ATTACKED.EVADE_GUARD;
        vl.ROTATE = el != null && el.ROTATE;
        vl.DEAD_R = 600;   // Eski Isaret ayarlari menude yok: sabit
        // Olu NPC'nin eski isareti en fazla 6 sn yok sayilir (eskiden 10 sn); isaret ayrilinca / orada NPC gorulunce
        // zaten erken silinir. Kesimden sonra gereksiz bekleme kisalir.
        vl.DEAD_SEC = 6;
        vl.REFRESH = el != null && el.REFRESH;
        v.EMPTY.ENABLED = el != null && el.EMPTY;
        v.EMPTY.SEC = 6;

        BotPlusConfig.Protect vd = v.PROTECT;
        Defense.Config df = defense == null ? null : defense.config;
        vd.ENABLED = df != null;
        if (df != null) {
            vd.EMP_ON = df.EMP != null && df.EMP.ON;
            if (df.EMP != null) {
                vd.EMP_WHEN = df.EMP.WHEN;
                vd.EMP_HP = df.EMP.HP;
            }
            vd.ISH_ON = df.ISH != null && df.ISH.ON;
            if (df.ISH != null) {
                vd.ISH_WHEN = df.ISH.WHEN;
                vd.ISH_HP = df.ISH.HP;
            }
            if (df.HP_LINK != null) vd.HP_LINK = df.HP_LINK;
            // 2.4.0 tasimalari: Sadece Bulastir -> Karsi Saldiri "Kacarken Zehirle"; Oyunculara RSB -> PvP Plus "RSB Kullan".
            if (df.INFECT) {
                try {
                    if (df.COUNTER_SET == null) df.COUNTER_SET = java.util.EnumSet.noneOf(BotPlusConfig.CounterOpt.class);
                    df.COUNTER_SET.add(BotPlusConfig.CounterOpt.INFECT);
                    df.INFECT = false;
                    Context.saveConfig();
                    Log.i("AYARLAR", "Defense: Sadece Bulastir -> Karsi Saldiri: Kacarken Zehirle olarak tasindi");
                } catch (RuntimeException ignored) {
                }
            }
            if (df.RSB_PLAYERS && pvp != null && pvp.config != null) {
                pvp.config.RSB = true;
                df.RSB_PLAYERS = false;
                Context.saveConfig();
                Log.i("AYARLAR", "Defense: Oyunculara RSB Kullan -> PvP Plus: RSB Kullan olarak tasindi");
            }
            vd.RSB_PLAYERS = df.RSB_PLAYERS || (pvp != null && pvp.config != null && pvp.config.RSB);
            // 2.7.0: Karsi Saldiri listesi (Gemi Yetenegi kendi listesinde: "Kapali" disinda bir sey seciliyse acik).
            java.util.Set<BotPlusConfig.Counter> cn = java.util.EnumSet.noneOf(BotPlusConfig.Counter.class);
            if (df.COUNTER_SET != null)
                for (BotPlusConfig.CounterOpt x : df.COUNTER_SET)
                    if (x != null && x != BotPlusConfig.CounterOpt.LATE_SPEED) cn.add(BotPlusConfig.Counter.valueOf(x.name()));
            vd.ABILITY = df.ability();
            if (vd.ABILITY != null) cn.add(BotPlusConfig.Counter.ABILITY);
            vd.COUNTER = cn;
            vd.INFECT = cn.contains(BotPlusConfig.Counter.INFECT);
            vd.FIRMS = df.FIRMS == null ? java.util.EnumSet.of(BotPlusConfig.Firm.ENEMY_FIRM)
                    : df.FIRMS.isEmpty() ? java.util.EnumSet.noneOf(BotPlusConfig.Firm.class) : java.util.EnumSet.copyOf(df.FIRMS);
        } else {
            vd.COUNTER = java.util.EnumSet.noneOf(BotPlusConfig.Counter.class);
            vd.ABILITY = null;
            vd.INFECT = false;
            vd.EMP_ON = vd.ISH_ON = false;
            vd.HP_LINK = OFF_LINK;
            vd.RSB_PLAYERS = pvp != null && pvp.config != null && pvp.config.RSB;
        }
        // Dusman Saldirinca (BotPlus menusu; Defense'ten bagimsiz). Kac / Hizla Atlat'ta dovus birakilir (yarim NPC
        // hatirlanir), kacisi Escape isi yapar; Devam Et'te dovus surer (can siniri altinda yine kacilir).
        BotPlusConfig.OnEnemy oe = v.ON_ENEMY;
        CoreConfig.OnEnemySet ds = c.DUSMAN;
        oe.MODE = ds.MODE;
        // 2.7.0: Kacis Cani kaldirildi (0: can yuzunden hicbir mod ezilmez; can dusunce Tamir / DarkBot karar verir).
        oe.HP = 0;
        oe.DIST = 2500; // Atlatma Uzakligi menude yok: sabit
        oe.CALM_SEC = ds.SAKIN;
        java.util.Set<BotPlusConfig.AtlatOpt> at = ds.ATLAT_SET;
        oe.FASTER_PORTAL = at.contains(BotPlusConfig.AtlatOpt.FASTER);
        // Atlatirken EMP Bas -> Savunma -> EMP -> "Kacarken kilit suruyorsa" (2000 yol sabit).
        oe.EMP = df != null && df.EMP != null && df.EMP.ON && df.EMP.KACARKEN;
        oe.EMP_DIST = 2000;
        java.util.Set<BotPlusConfig.PortalOpt> po = ds.PORTAL_SET;
        java.util.Set<BotPlusConfig.EscapeOpt> eo = java.util.EnumSet.noneOf(BotPlusConfig.EscapeOpt.class);
        if (po.contains(BotPlusConfig.PortalOpt.CONE)) eo.add(BotPlusConfig.EscapeOpt.CONE);
        if (po.contains(BotPlusConfig.PortalOpt.RACE)) eo.add(BotPlusConfig.EscapeOpt.RACE);
        if (po.contains(BotPlusConfig.PortalOpt.ELSE_OUTRUN)) eo.add(BotPlusConfig.EscapeOpt.ELSE_OUTRUN);
        if (po.contains(BotPlusConfig.PortalOpt.SWITCH_PORTAL)) eo.add(BotPlusConfig.EscapeOpt.SWITCH_PORTAL);
        if (po.contains(BotPlusConfig.PortalOpt.PASS_JUMP)) eo.add(BotPlusConfig.EscapeOpt.PASS_JUMP);
        oe.ESCAPE_OPTS = eo;
        java.util.Set<BotPlusConfig.OutrunOpt> oo = java.util.EnumSet.noneOf(BotPlusConfig.OutrunOpt.class);
        for (BotPlusConfig.AtlatOpt x : at) if (x != BotPlusConfig.AtlatOpt.FASTER) oo.add(BotPlusConfig.OutrunOpt.valueOf(x.name()));
        // Yaklasinca Hiz Yetenegi -> Savunma -> Karsi Saldiri.
        if (df != null && df.COUNTER_SET != null && df.COUNTER_SET.contains(BotPlusConfig.CounterOpt.LATE_SPEED))
            oo.add(BotPlusConfig.OutrunOpt.LATE_SPEED);
        oe.OUTRUN_OPTS = oo;
        java.util.Set<BotPlusConfig.AdvOpt> ad = ds.GELISMIS_SET;
        // Kilit Kir kaldirildi (Savunma'daki "ilk sikinca" EMP yeterli).
        oe.START_OPTS = ad.contains(BotPlusConfig.AdvOpt.INSTANT_TURN) ? java.util.EnumSet.of(BotPlusConfig.StartOpt.INSTANT_TURN)
                : java.util.EnumSet.noneOf(BotPlusConfig.StartOpt.class);
        oe.OWN_MODE = ds.OWN == null ? BotPlusConfig.OwnMode.SAME : ds.OWN;
        oe.ATTACK_STAY = ds.REMEMBER_ATTACK_STAY;
        CoreConfig.EscapePlusSet ep = VIEW_PLUS;
        ep.RAPID_LOSS = ds.HIZLI == BotPlusConfig.RapidMode.OFF ? 0 : 50; // 2 sn'de %50 (sabit)
        ep.SMART = false;          // Akilli Kacis kaldirildi
        ep.CRIT_PORTAL = false;    // Can Kritikse En Yakin Portal kaldirildi
        ep.SAVE_SKILLS = false;    // Yetenekleri Kacisa Sakla kaldirildi
        ep.END_HOLD = ad.contains(BotPlusConfig.AdvOpt.END_HOLD);
        ep.AFTER_SEC = ad.contains(BotPlusConfig.AdvOpt.AFTER_STAY) ? 30 : 0;
        ep.CORNER_GUARD = ad.contains(BotPlusConfig.AdvOpt.CORNER_GUARD);
        ep.WIDEN_CONE = po.contains(BotPlusConfig.PortalOpt.WIDEN_CONE);
        ep.FOLLOW_JUMP = po.contains(BotPlusConfig.PortalOpt.FOLLOW_JUMP);
        ep.PORTAL_CHAIN = po.contains(BotPlusConfig.PortalOpt.PORTAL_CHAIN);
        ep.REPAIR_PORTAL = po.contains(BotPlusConfig.PortalOpt.REPAIR_PORTAL);
        // Arkaya Mayin -> Savunma -> Karsi Saldiri -> Yavaslatici Mayin.
        ep.MINE_BACK = df != null && df.COUNTER_SET != null && df.COUNTER_SET.contains(BotPlusConfig.CounterOpt.SLM_01);
        oe.PLUS = ep;
        oe.RAPID_MODE = ds.HIZLI;
        oe.CHASE_HOLD = ad.contains(BotPlusConfig.AdvOpt.CHASE_HOLD);
        // PvP ve Push Korumasi: kendi alt eklentileri (kapaliysa Saldir Kac gibi, push kontrolu yok).
        oe.PVP = com.botplus.pvp.Pvp.view(pvp);
        oe.PVP_ON = pvp != null;
        oe.PUSH = com.botplus.push.Push.view(push);
        // Klan Uyelerine Saldirma: PvP'nin Kimlere Saldir listesinde; PvP kapaliysa da (varsayilan) acik.
        com.botplus.pvp.Pvp.Config pvc = pvpRaw;
        oe.CLAN_SAFE = pvc == null || pvc.KIMLER == null || pvc.KIMLER.contains(BotPlusConfig.PvpFirm.CLAN_SAFE);
        oe.PUSH.SAME_CLAN = oe.CLAN_SAFE;
        // Dusmani Hatirla: BotPlus menusunden (Defense kapali olsa da calisir).
        // Hicbiri secili degilse hatirlama kapali; "Gemine Saldiran" tek basina da patlatani hatirlar (saldiran zaten).
        java.util.Set<BotPlusConfig.RememberWho> hw = c.DUSMAN.HATIRLA;
        VIEW_KILLER.ENABLED = hw != null && !hw.isEmpty();
        VIEW_KILLER.MINUTES = c.DUSMAN.REMEMBER_MIN;
        VIEW_KILLER.OWN_MINUTES = c.DUSMAN.REMEMBER_OWN_MIN;
        vd.KILLER = VIEW_KILLER;
        v.REMEMBER_ATTACKERS = hw != null && hw.contains(BotPlusConfig.RememberWho.ATTACKER);
        v.REMEMBER_KILLERS = hw != null && hw.contains(BotPlusConfig.RememberWho.KILLER);
        vd.ON_PLAYER = oe.MODE == BotPlusConfig.OnEnemyMode.CONTINUE ? BotPlusConfig.OnPlayer.CONTINUE : BotPlusConfig.OnPlayer.DROP_AWAY;

        BotPlusConfig.Steal vs = v.STEAL;
        NpcSteal.Config st = steal == null ? null : steal.config;
        vs.ENABLED = st != null;
        if (st != null) {
            vs.WHICH = st.WHICH;
            vs.FROM = st.FROM;
            vs.NPC_HP = st.NPC_HP;
            vs.ACTION = st.ACTION;
            vs.ENEMY_HP = st.ENEMY_HP;
            vs.CHASE_SEC = st.CHASE_SEC;
            vs.HERO_HP = st.HERO_HP;
            vs.AMMO = st.AMMO;
            vs.PET = st.PET;
        }

        BotPlusConfig.Rsb vr = v.RSB;
        Rsb.Config rs = rsb == null ? null : rsb.config;
        vr.ENABLED = rs != null;
        if (rs != null) {
            vr.WHICH = rs.WHICH;
            vr.AMMO = rs.AMMO;
            vr.PING = rs.PING;
            vr.LAG_GUARD = rs.LAG_GUARD;
            vr.SMART = rs.SMART;
        }

        BotPlusConfig.Repair vt = v.REPAIR;
        Repair.Config rp = repair == null ? null : repair.config;
        vt.ENABLED = rp != null && rp.PLACE != Repair.Place.DARKBOT;
        vt.PLACE = rp != null && rp.PLACE == Repair.Place.MAP ? BotPlusConfig.RepairPlace.MAP : BotPlusConfig.RepairPlace.PORTAL;
        // Tamir konfigurasyonu ve baslama / bitis cani DarkBot'un kendi tamir ayarlarindan.
        try {
            PercentRange r = context.repairRange.getValue();
            if (r != null) {
                vt.START_HP = (int) Math.round(r.getMin() * 100);
                vt.END_HP = (int) Math.round(r.getMax() * 100);
            }
        } catch (RuntimeException ignored) {
        }
        ShipMode m = Context.value(context.repairMode, null);
        if (m != null) vt.MODE = m;
        if (rp != null) {
            vt.AVOID = rp.AVOID;
            vt.ENEMY_RUN_MODE = rp.ENEMY_RUN_MODE;
            vt.NEAR_NPC = rp.NEAR_NPC;
            vt.PET_SHIP = rp.PET_SHIP;
            vt.IDLE = rp.IDLE != null && rp.IDLE.ENABLED;
            vt.IDLE_HP = rp.IDLE == null ? 70 : rp.IDLE.HP;
        } else {
            vt.PET_SHIP = false;
            vt.IDLE = false;
        }
        vt.TRAVEL_ROAM = true;

        // 2.7.0 Yenileme: "DarkBot ayarlari" = BotPlus yenilemez.
        BotPlusConfig.RenewChoice rc = c.GENEL.YENILE == null ? BotPlusConfig.RenewChoice.DARKBOT : c.GENEL.YENILE;
        v.RENEW.ENABLED = rc != BotPlusConfig.RenewChoice.DARKBOT;
        if (v.RENEW.ENABLED) v.RENEW.MODE = BotPlusConfig.RefreshMode.valueOf(rc.name());

        v.LOG = c.KAYIT.ENABLED;
        v.LOG_LEVEL = c.KAYIT.LEVEL;
        v.LOG_FILE = c.KAYIT.FILE;

        applyWorkMode(v);
        v.fix();
    }

    /**
     * Modul secimi kesin kuraldir (DarkBot modulleri gibi): Sadece Toplama'da NPC'ye hic gidilmez, Sadece NPC'de hic
     * kutu toplanmaz; alt modullerin ayarlari acik olsa bile. (Ana modulde BotPlus menusu, degilse DarkBot'ta secili modul.)
     */
    private void applyWorkMode(BotPlusConfig v) {
        BotPlusConfig.BotMode mode;
        try {
            mode = context.botMode();
        } catch (RuntimeException e) {
            return;
        }
        if (mode == BotPlusConfig.BotMode.BOX) {
            v.STEAL.ENABLED = false;
            v.LOCATOR.GO = false;
            v.LOCATOR.DIRECT = false;
            v.LOCATOR.DROP = false;
            v.LOCATOR.IGNORE = false;
            v.LOCATOR.ROTATE = false;
            v.LOCATOR.REFRESH = false;
            v.EMPTY.ENABLED = false;
            v.PET.TRAVEL_LOCATOR = false;
            v.COMBAT.CHASE = false;
            v.COMBAT.FIGHT_MIN_HP = 0;
            BotPlusConfig.PlusTarget t = v.PLUS.TARGET;
            t.NEAREST = t.FAST_LOCK = t.NEXT_TARGET = t.WEAK_FIRST = false;
            t.HP_WAIT.ENABLED = false;
        } else if (mode == BotPlusConfig.BotMode.NPC) {
            v.BOX_WAY = BotPlusConfig.BoxWay.IGNORE;
            v.PLUS.OTHER.BOX_WAIT = false;
            v.PLUS.OTHER.RIVAL = false;
        }
    }

    // ---------------------------------------------------------------- eski ayarlarin aktarilmasi

    /** Eski tek parca ayarlar (CoreConfig'in ust sinif alanlari) -> BotPlus menusu. */
    private void migrateCore(CoreConfig c) {
        BotPlusConfig old = c;
        old.fix();
        boolean plus = old.PLUS.ENABLED;
        c.GENEL.ENABLED = old.ENABLED;
        c.GENEL.SORT = CoreConfig.Order.of(old.SORT);
        c.GENEL.WORK = workFromDarkBot();
        c.HAREKET.AVOID_UNLISTED = old.AVOID_UNLISTED;
        c.HAREKET.AVOID_NEAR_OFF = old.AVOID_NEAR_OFF;
        c.HAREKET.AVOID_AHEAD = old.AVOID_AHEAD;
        c.HAREKET.AVOID_ANGLE = old.AVOID_ANGLE;
        c.HAREKET.AVOID_ONLY_AGGRESSIVE = old.AVOID_ONLY_AGGRESSIVE;
        c.HAREKET.SPEED_ABILITY = plus && old.PLUS.OTHER.SPEED_ABILITY;
        c.HAREKET.UNSTUCK = plus && old.PLUS.OTHER.UNSTUCK;
        c.DOLASMA.SHORT = plus && old.PLUS.OTHER.SHORT_ROAM;
        c.DOLASMA.SMART = plus && old.PLUS.OTHER.SMART_ROAM;
        c.DOLASMA.RADAR = old.PLUS.OTHER.RADAR;
        c.KUTU.MAX_DIST = old.BOX_MAX_DIST;
        c.KUTU.WAY = old.BOX_WAY;
        c.KUTU.WAIT = plus && old.PLUS.OTHER.BOX_WAIT;
        c.KUTU.RIVAL = plus && old.PLUS.OTHER.RIVAL;
        BotPlusConfig.HpWait hw = old.PLUS.TARGET.HP_WAIT;
        if (plus && hw.ENABLED) {
            c.HP_BEKLE.ENABLED = true;
            c.HP_BEKLE.WHICH = hw.WHICH;
            c.HP_BEKLE.HP = hw.HP;
            c.HP_BEKLE.MODE = CoreConfig.HpWaitMode.NEAR;
        } else if (old.COMBAT.ENABLED && old.COMBAT.FIGHT_MIN_HP > 0) {
            // Eski "Yeni Dovus Icin En Az Can" (Combat Plus acikken calisiyordu).
            c.HP_BEKLE.ENABLED = true;
            c.HP_BEKLE.WHICH = BotPlusConfig.HpWaitWhich.ALL;
            c.HP_BEKLE.HP = Math.max(10, old.COMBAT.FIGHT_MIN_HP);
            c.HP_BEKLE.MODE = CoreConfig.HpWaitMode.ROAM;
        }
        c.PET_AYAR.ENABLED = old.PET.ENABLED;
        c.PET_AYAR.COMBAT_CHANGE = old.PET.COMBAT_CHANGE;
        c.PET_AYAR.COMBAT = old.PET.COMBAT;
        c.PET_AYAR.REPAIR = old.PET.REPAIR;
        c.YENILEME.ENABLED = old.RENEW.ENABLED;
        c.YENILEME.MODE = old.RENEW.MODE;
        c.KAYIT.ENABLED = old.LOG;
        c.KAYIT.LEVEL = old.LOG_LEVEL;
        c.KAYIT.FILE = old.LOG_FILE;
        c.KAYIT.STATS = plus && old.PLUS.OTHER.STATS;
    }

    /** Eski surumde calisma sekli DarkBot'ta secili modulden geliyordu. */
    private BotPlusConfig.WorkMode workFromDarkBot() {
        try {
            eu.darkbot.api.extensions.Module m = context.bot.getNonTemporalModule();
            String n = m == null ? "" : m.getClass().getSimpleName();
            boolean collect = n.contains("Collect");
            boolean loot = n.contains("Loot") || n.contains("Npc") || n.contains("Kill");
            if (collect && loot) return BotPlusConfig.WorkMode.NPC_BOX;
            if (collect) return BotPlusConfig.WorkMode.BOX;
            if (loot) return BotPlusConfig.WorkMode.NPC;
        } catch (RuntimeException ignored) {
        }
        return BotPlusConfig.WorkMode.NPC_BOX;
    }

    /** Alt modullerin acik / kapali durumu eski ayarlara gore (bir kez). */
    private void migrateEnabled(CoreConfig c) {
        BotPlusConfig old = c;
        boolean plus = old.PLUS.ENABLED;
        BotPlusConfig.PlusTarget t = old.PLUS.TARGET;
        boolean combatAny = old.COMBAT.ENABLED || old.COMBAT.CHASE
                || (plus && (t.NEAREST || t.FAST_LOCK || t.NEXT_TARGET || t.WEAK_FIRST || t.AUTO_RANGE));
        enable(Combat.class, combatAny);
        enable(Rsb.class, old.RSB.ENABLED);
        enable(NpcSteal.class, old.STEAL.ENABLED);
        enable(Repair.class, old.REPAIR.ENABLED);
        enable(EnemyLocator.class, old.LOCATOR.GO || old.LOCATOR.IGNORE || old.LOCATOR.ROTATE || old.EMPTY.ENABLED);
        enable(Defense.class, old.PROTECT.ENABLED || old.PROTECT.KILLER.ENABLED);
        Log.i("AYARLAR", "alt modullerin acik / kapali durumu eski ayarlardan kuruldu");
    }

    private void enable(Class<?> cls, boolean on) {
        try {
            FeatureInfo<?> info = ext.getFeatureInfo(cls);
            if (info != null && info.isEnabled() != on) info.setEnabled(on);
        } catch (Throwable t) {
            Log.i("AYARLAR", cls.getSimpleName() + " acik / kapali kurulamadi: " + t);
        }
    }

    /** Acik alt modul ilk kez goruldugunde eski ayarlari aktarilir (bir kez). */
    private void migrateSubs() {
        BotPlusConfig old = core;
        boolean changed = false;
        if (combat != null && core.MIGRATED_SUBS.add(Combat.class.getName())) {
            Combat.Config n = combat.config;
            n.FULL = old.COMBAT.ENABLED;
            n.STYLE = old.COMBAT.STYLE;
            if (old.COMBAT.OPTIONS != null) {
                n.OPTIONS.clear();
                n.OPTIONS.addAll(old.COMBAT.OPTIONS);
            }
            n.FINISH_FIRST = old.COMBAT.FINISH_FIRST;
            n.FLEE.CHASE = old.COMBAT.CHASE;
            n.FLEE.DROP = old.COMBAT.CHASE_DROP;
            boolean plus = old.PLUS.ENABLED;
            BotPlusConfig.PlusTarget t = old.PLUS.TARGET;
            n.TARGET.NEAREST = plus && t.NEAREST;
            n.TARGET.FAST_LOCK = plus && t.FAST_LOCK;
            n.TARGET.NEXT_TARGET = plus && t.NEXT_TARGET;
            n.TARGET.WEAK_FIRST = plus && t.WEAK_FIRST;
            changed = true;
        }
        // BotPlus -> PET'teki saldiri PET'i Combat'a tasindi (bir kez): yonetim ya da "saldirirken degistir" kapaliysa
        // "Degistirme", acikse eski secilen modul.
        if (combat != null && core.MIGRATED_SUBS.add(PET_TO_COMBAT)) {
            CoreConfig.PetSet op = core.PET_AYAR;
            combat.config.PET = op.ENABLED && op.COMBAT_CHANGE ? BotPlusConfig.FightPet.of(op.COMBAT) : BotPlusConfig.FightPet.NONE;
            changed = true;
        }
        if (rsb != null && core.MIGRATED_SUBS.add(Rsb.class.getName())) {
            Rsb.Config n = rsb.config;
            n.ENABLED = old.RSB.ENABLED;
            n.WHICH = old.RSB.WHICH;
            n.AMMO = old.RSB.AMMO;
            n.PING = old.RSB.PING;
            n.LAG_GUARD = old.RSB.LAG_GUARD;
            changed = true;
        }
        if (steal != null && core.MIGRATED_SUBS.add(NpcSteal.class.getName())) {
            NpcSteal.Config n = steal.config;
            n.ENABLED = old.STEAL.ENABLED;
            n.WHICH = old.STEAL.WHICH;
            n.FROM = old.STEAL.FROM;
            n.NPC_HP = old.STEAL.NPC_HP;
            n.ACTION = old.STEAL.ACTION;
            n.ENEMY_HP = old.STEAL.ENEMY_HP;
            n.CHASE_SEC = old.STEAL.CHASE_SEC;
            n.HERO_HP = old.STEAL.HERO_HP;
            n.AMMO = old.STEAL.AMMO;
            n.PET = old.STEAL.PET;
            changed = true;
        }
        if (repair != null && core.MIGRATED_SUBS.add(Repair.class.getName())) {
            Repair.Config n = repair.config;
            n.PLACE = old.REPAIR.PLACE == BotPlusConfig.RepairPlace.PORTAL ? Repair.Place.DARKBOT : Repair.Place.MAP;
            n.AVOID = old.REPAIR.AVOID;
            n.ENEMY_RUN_MODE = old.REPAIR.ENEMY_RUN_MODE;
            n.NEAR_NPC = old.REPAIR.NEAR_NPC;
            n.PET_SHIP = old.REPAIR.PET_SHIP;
            n.IDLE.ENABLED = old.REPAIR.IDLE;
            n.IDLE.HP = old.REPAIR.IDLE_HP;
            changed = true;
        }
        if (locator != null && core.MIGRATED_SUBS.add(EnemyLocator.class.getName())) {
            EnemyLocator.Config n = locator.config;
            n.GO.DROP = old.LOCATOR.DROP;
            n.GO.DROP_HP = old.LOCATOR.DROP_HP;
            n.GO.TRAVEL_LOCATOR = old.PET.TRAVEL_LOCATOR;
            n.ATTACKED.IGNORE = old.LOCATOR.IGNORE;
            n.ATTACKED.EVADE_GUARD = old.LOCATOR.EVADE_GUARD;
            n.ROTATE = old.LOCATOR.ROTATE;
            n.EMPTY = old.EMPTY.ENABLED;
            n.REFRESH = false;
            changed = true;
        }
        if (defense != null && core.MIGRATED_SUBS.add(Defense.class.getName())) {
            Defense.Config n = defense.config;
            n.ENABLED = old.PROTECT.ENABLED || old.PROTECT.KILLER.ENABLED;
            n.EMP.ON = old.PROTECT.ENABLED && old.PROTECT.EMP_ON; // eskiden ana anahtar kapaliysa hicbiri calismiyordu
            n.EMP.WHEN = old.PROTECT.EMP_WHEN;
            n.EMP.HP = old.PROTECT.EMP_HP;
            n.ISH.ON = old.PROTECT.ENABLED && old.PROTECT.ISH_ON;
            n.ISH.WHEN = old.PROTECT.ISH_WHEN;
            n.ISH.HP = old.PROTECT.ISH_HP;
            copyHpLink(old.PROTECT.HP_LINK, n.HP_LINK);
            if (!old.PROTECT.ENABLED && n.HP_LINK != null) n.HP_LINK.ENABLED = false;
            copyKiller(old.PROTECT.KILLER, n.KILLER);
            changed = true;
        }
        // 2.1.0: Defense'teki "Oyuncu Saldirinca" BotPlus menusune (bir kez). Sadece Defense ayari onceden kurulmus
        // (eski) kurulumlarda; yeni kurulumda varsayilan "Devam Et" kalir.
        // Defense eklenti listesinde kapali (ya da kendi Aktif tiki kapali) idiyse 2.0.0'da etkin deger hep "Dovusu Birak
        // ve Uzaklas" idi; o deger tasinir (Defense sonradan acilinca kullanicinin yeni secimi ezilmez: tasima bir kez).
        if ((defense == null || core.MIGRATED_SUBS.contains(Defense.class.getName())) && core.MIGRATED_SUBS.add(ONPLAYER_TO_CORE)) {
            Defense.OnPlayer op = defense == null || !defense.config.ENABLED ? Defense.OnPlayer.DROP_AWAY : defense.config.ON_PLAYER;
            core.DUSMAN.MODE = op == Defense.OnPlayer.PORTAL ? BotPlusConfig.OnEnemyMode.ESCAPE
                    : op == Defense.OnPlayer.DROP_AWAY ? BotPlusConfig.OnEnemyMode.OUTRUN : BotPlusConfig.OnEnemyMode.CONTINUE;
            Log.i("AYARLAR", "Defense -> Oyuncu Saldirinca (" + op + ") BotPlus -> Dusman Saldirinca (" + core.DUSMAN.MODE + ") olarak tasindi");
            changed = true;
        }
        // Dusmani Hatirla: Defense acik ve ozellik aciksa sureyle aynen; Defense acik ama ozellik bilerek kapaliysa kapali;
        // Defense hic kullanilmadiysa (eskiden ozellik fiilen kapaliydi) yeni varsayilan "Seni Patlatan".
        if ((defense == null || core.MIGRATED_SUBS.contains(Defense.class.getName())) && core.MIGRATED_SUBS.add(KILLER_TO_CORE)) {
            BotPlusConfig.Killer k = defense == null ? null : defense.config.KILLER;
            boolean defOn = defense != null && defense.config.ENABLED;
            if (k != null && defOn && k.ENABLED) {
                core.DUSMAN.REMEMBER = BotPlusConfig.RememberMode.KILLER;
                core.DUSMAN.REMEMBER_MIN = k.MINUTES;
            } else if (k != null && defOn) core.DUSMAN.REMEMBER = BotPlusConfig.RememberMode.OFF;
            Log.i("AYARLAR", "Defense -> Dusmani Hatirla BotPlus -> Dusman Saldirinca -> Dusmani Hatirla ("
                    + core.DUSMAN.REMEMBER + ", " + core.DUSMAN.REMEMBER_MIN + " dk) olarak tasindi");
            changed = true;
        }
        // 2.4.0: Dusmani Hatirla tek secimden coklu secime (Kapali -> hicbiri, Seni Patlatan -> Gemini Patlatan,
        // Patlatan + Saldiran -> ikisi). Yukaridaki eski tasimadan sonra, bir kez.
        if (core.MIGRATED_SUBS.contains(KILLER_TO_CORE) && core.MIGRATED_SUBS.add(REMEMBER_TO_MULTI)) {
            java.util.EnumSet<BotPlusConfig.RememberWho> hw = java.util.EnumSet.noneOf(BotPlusConfig.RememberWho.class);
            if (core.DUSMAN.REMEMBER == BotPlusConfig.RememberMode.KILLER) hw.add(BotPlusConfig.RememberWho.KILLER);
            if (core.DUSMAN.REMEMBER == BotPlusConfig.RememberMode.ATTACKER) {
                hw.add(BotPlusConfig.RememberWho.KILLER);
                hw.add(BotPlusConfig.RememberWho.ATTACKER);
            }
            core.DUSMAN.HATIRLA = hw;
            Log.i("AYARLAR", "Dusmani Hatirla coklu secime tasindi: " + core.DUSMAN.REMEMBER + " -> " + hw);
            changed = true;
        }
        if (changed) {
            Context.saveConfig();
            Log.i("AYARLAR", "eski ayarlar alt modullere aktarildi: " + core.MIGRATED_SUBS);
        }
    }

    /** Coklu secimin kod kopyasi (bos / bozuk kayit: hicbiri secili degil). */
    private static <E extends Enum<E>> java.util.Set<E> copy(java.util.Set<E> src, Class<E> type) {
        java.util.EnumSet<E> out = java.util.EnumSet.noneOf(type);
        if (src != null) for (E e : src) if (e != null) out.add(e);
        return out;
    }
}