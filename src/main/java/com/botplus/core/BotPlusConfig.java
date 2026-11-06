package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.game.enums.PetGear;
import eu.darkbot.api.game.items.SelectableItem;

import java.util.EnumSet;
import java.util.Set;


@SuppressWarnings("unused")
@Configuration("botplus")
public class BotPlusConfig {


    @Option("botplus.enabled")
    public boolean ENABLED = true;

    @Option("botplus.sort")
    @Dropdown
    public Sort SORT = Sort.MODULE;

    @Option("botplus.box_way")
    @Dropdown
    public BoxWay BOX_WAY = BoxWay.IGNORE;

    @Option("botplus.avoid_unlisted")
    public boolean AVOID_UNLISTED = false;

    @Option("botplus.avoid_near")
    public boolean AVOID_NEAR_OFF = false;

    @Option("botplus.avoid_ahead")
    @Number(min = 500, max = 5000, step = 250)
    public int AVOID_AHEAD = 1500;

    @Option("botplus.avoid_angle")
    @Number(min = 15, max = 90, step = 5)
    public int AVOID_ANGLE = 45;

    @Option("botplus.avoid_aggressive")
    public boolean AVOID_ONLY_AGGRESSIVE = true;

    @Option("botplus.box_max_dist")
    @Number(min = 500, max = 20000, step = 250)
    public int BOX_MAX_DIST = 3000;

    @Option("botplus.plus")
    public Plus PLUS = new Plus();

    @Option("botplus.combat")
    public Combat COMBAT = new Combat();

    /** Calisma sekli (kod gorunumu): BotPlus ana modulken menuden, degilse DarkBot'ta secili modulden. */
    public transient WorkMode WORK = WorkMode.NPC_BOX;

    /** Eski surumden kalan ya da okunamayan (bos) ayarlari varsayilana cevirir. */
    public void fix() {
        if (BOX_WAY == null) BOX_WAY = BoxWay.IGNORE;
        if (PROTECT == null) PROTECT = new Protect();
        PROTECT.fix();
        if (ON_ENEMY == null) ON_ENEMY = new OnEnemy();
        if (ON_ENEMY.MODE == null) ON_ENEMY.MODE = OnEnemyMode.CONTINUE;
        if (SORT == null) SORT = Sort.MODULE;
        if (LOCATOR == null) LOCATOR = new Locator();
        if (PET == null) PET = new Pet();
        if (PET.COMBAT == null) PET.COMBAT = CombatPet.LOOTER;
        if (STEAL != null && STEAL.PET == null) STEAL.PET = StealPet.GUARD;
        if (STEAL == null) STEAL = new Steal();
        if (STEAL.FROM == null) STEAL.FROM = StealFrom.ENEMY;
        if (STEAL.ACTION == null) STEAL.ACTION = StealAction.KILL;
        if (STEAL.AMMO == null) STEAL.AMMO = Ammo.X2;
        if (CHASE == null) CHASE = new Chase();
        if (REPAIR == null) REPAIR = new Repair();
        if (RENEW == null) RENEW = new Refresh();
        if (RENEW.MODE == null) RENEW.MODE = RefreshMode.MAP;
        if (REPAIR.MODE == null || REPAIR.MODE.getConfiguration() == null
                || REPAIR.MODE.getConfiguration() == HeroAPI.Configuration.UNKNOWN) REPAIR.MODE = repairDefault();
        if (PLUS == null) PLUS = new Plus();
        PLUS.fix();
        if (COMBAT == null) COMBAT = new Combat();
        if (COMBAT.STYLE == null) COMBAT.STYLE = CombatStyle.ARC;
        if (COMBAT.ARC == null) COMBAT.ARC = new ArcSettings();
        if (COMBAT.SAFE == null) COMBAT.SAFE = new SafeSettings();
        if (COMBAT.SAFE.SIDE == null) COMBAT.SAFE.SIDE = SafeSide.AUTO;
        if (COMBAT.OUT == null) COMBAT.OUT = new OutrangeSettings();

        if (LOG_LEVEL == null) LOG_LEVEL = LogLevel.DETAILED;
        // Sayi sinirlari (elle duzenlenmis dosyada 0 / asiri deger titreme yapmasin)
        COMBAT.ARC.ANGLE = clamp(COMBAT.ARC.ANGLE, 30, 360);
        COMBAT.OUT.MARGIN = clamp(COMBAT.OUT.MARGIN, 0, 300);
        COMBAT.CHASE_DROP = clamp(COMBAT.CHASE_DROP, 5, 50);
        if (COMBAT.OPTIONS == null) COMBAT.OPTIONS = EnumSet.of(CombatOption.PRE_LOCK);
        COMBAT.OPTIONS.remove(null);
        if (REPAIR.AVOID != null) REPAIR.AVOID.remove(null);
        COMBAT.INWARD = COMBAT.OPTIONS.contains(CombatOption.INWARD);
        COMBAT.OUT_ON_DAMAGE = COMBAT.OPTIONS.contains(CombatOption.OUT_ON_DAMAGE);
        COMBAT.PRE_LOCK = COMBAT.OPTIONS.contains(CombatOption.PRE_LOCK);
        // Kacan NPC ayarlari Combat Plus altinda; eski CHASE alani kodun okudugu gorunum (menude yok, kaydedilmez).
        CHASE.ENABLED = COMBAT.CHASE;
        CHASE.DROP = COMBAT.CHASE_DROP;
        CHASE.MAX_SEC = CHASE_MAX_SEC;
        REPAIR.START_HP = clamp(REPAIR.START_HP, 5, 95);
        REPAIR.END_HP = clamp(REPAIR.END_HP, 10, 100);
        CHASE.DROP = clamp(CHASE.DROP, 5, 50);
        CHASE.MAX_SEC = clamp(CHASE.MAX_SEC, 10, 120);
        if (PLUS.TARGET.HP_WAIT == null) PLUS.TARGET.HP_WAIT = new HpWait();
        if (PLUS.TARGET.HP_WAIT.WHICH == null) PLUS.TARGET.HP_WAIT.WHICH = HpWaitWhich.FLAGGED;
        PLUS.TARGET.HP_WAIT.HP = clamp(PLUS.TARGET.HP_WAIT.HP, 10, 100);
        REPAIR.IDLE_HP = clamp(REPAIR.IDLE_HP, 10, 100);
        if (REPAIR.PLACE == null) REPAIR.PLACE = RepairPlace.MAP;
        if (REPAIR.AVOID == null) REPAIR.AVOID = EnumSet.of(RepairAvoid.NPC, RepairAvoid.ENEMY);
        if (STEAL.WHICH == null) STEAL.WHICH = StealWhich.ALL;
        PROTECT.EMP_HP = clamp(PROTECT.EMP_HP, 5, 100);
        PROTECT.ISH_HP = clamp(PROTECT.ISH_HP, 5, 100);
        PLUS.OTHER.RADAR = clamp(PLUS.OTHER.RADAR, 1000, 6000);
        PROTECT.HP_LINK.HP = clamp(PROTECT.HP_LINK.HP, 5, 100);
        BOX_MAX_DIST = clamp(BOX_MAX_DIST, 500, 20000);
        AVOID_AHEAD = clamp(AVOID_AHEAD, 500, 5000);
        AVOID_ANGLE = clamp(AVOID_ANGLE, 15, 90);
        LOCATOR.DEAD_R = clamp(LOCATOR.DEAD_R, 200, 2000);
        LOCATOR.DEAD_SEC = clamp(LOCATOR.DEAD_SEC, 3, 60);
        if (EMPTY == null) EMPTY = new EmptyMapSettings();
        EMPTY.SEC = clamp(EMPTY.SEC, 1, 60);
        if (RSB == null) RSB = new Rsb();
        if (RSB.AMMO == null) RSB.AMMO = Ammo.RSB;
        if (RSB.WHICH == null) RSB.WHICH = RsbWhich.ALL;
        RSB.PING = clamp(RSB.PING, 50, 1000);
        COMBAT.FIGHT_MIN_HP = clamp(COMBAT.FIGHT_MIN_HP, 0, 95);
        LOCATOR.DROP_HP = clamp(LOCATOR.DROP_HP, 0, 100);
        STEAL.NPC_HP = clamp(STEAL.NPC_HP, 0, 100);
        STEAL.HERO_HP = clamp(STEAL.HERO_HP, 0, 100);
        STEAL.CHASE_SEC = clamp(STEAL.CHASE_SEC, 1, 120);
        if (PROTECT.KILLER != null) PROTECT.KILLER.MINUTES = clamp(PROTECT.KILLER.MINUTES, 1, 60);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** DarkBot'un kendi konfig sinifi: kaydedip acinca formasyon kaybolmaz. */
    public static ShipMode repairDefault() {
        try {
            return new com.github.manolo8.darkbot.config.Config.ShipConfig(1, '9');
        } catch (Throwable t) {
            return ShipMode.of(HeroAPI.Configuration.FIRST, SelectableItem.Formation.STANDARD);
        }
    }

    @Option("botplus.pet")
    public Pet PET = new Pet();

    @Option("botplus.protect")
    public Protect PROTECT = new Protect();

    /** Dusman Saldirinca (kod gorunumu, kaydedilmez). */
    public transient OnEnemy ON_ENEMY = new OnEnemy();
    /** Dusmani Hatirla: saldiranlar da hatirlansin mi (gorunum). */
    public transient boolean REMEMBER_ATTACKERS;

    @Option("botplus.locator")
    public Locator LOCATOR = new Locator();

    @Option("botplus.empty")
    public EmptyMapSettings EMPTY = new EmptyMapSettings();

    /** Bos Harita Modu: bulucu sinyal vermezken (avlanan tur haritada yok) harita ortasinda beklenir / aranir. */
    public static class EmptyMapSettings {
        @Option("botplus.empty.enabled")
        public boolean ENABLED = false;

        @Option("botplus.empty.sec")
        @Number(min = 1, max = 60, step = 1)
        public int SEC = 4;

    }

    @Option("botplus.steal")
    public Steal STEAL = new Steal();

    @Option("botplus.rsb")
    public Rsb RSB = new Rsb();

    /** Kod icin gorunum: degerler COMBAT.CHASE / CHASE_DROP ve CHASE_MAX_SEC'ten (fix) gelir. */
    public transient Chase CHASE = new Chase();

    @Option("botplus.repair")
    public Repair REPAIR = new Repair();

    @Option("botplus.renew")
    // Eski surumde REFRESH bir secenekti (metin); kayitli ayarla cakismasin diye alan adi farkli.
    public Refresh RENEW = new Refresh();

    @Option("botplus.log")
    public boolean LOG = true;

    @Option("botplus.log_level")
    @Dropdown
    public LogLevel LOG_LEVEL = LogLevel.DETAILED;

    @Option("botplus.log_file")
    public boolean LOG_FILE = true;

    public static class Locator {
        @Option("botplus.locator.go")
        public boolean GO = false;

        @Option("botplus.locator.drop")
        public boolean DROP = false;

        @Option("botplus.locator.drop_hp")
        @Number(min = 0, max = 100, step = 5)
        public int DROP_HP = 50;

        @Option("botplus.locator.ignore")
        public boolean IGNORE = false;

        @Option("botplus.locator.evade_guard")
        public boolean EVADE_GUARD = true;

        @Option("botplus.locator.rotate")
        public boolean ROTATE = false;

        /** Olen NPC'nin yeri: bu yaricaptaki bulucu isareti DEAD_SEC sn reddedilir (eski isaret). */
        @Option("botplus.locator.dead_r")
        @Number(min = 200, max = 2000, step = 50)
        public int DEAD_R = 600;

        @Option("botplus.locator.dead_sec")
        @Number(min = 3, max = 60, step = 1)
        public int DEAD_SEC = 10;

        /** Kesim sonrasi PET kisa sure bulucudan cikarilip geri alinir (yeni isaret erken gelsin mi denemesi). */
        public boolean REFRESH = true;
    }

    /** PET yonetimi: kapaliyken BotPlus PET modulune hic dokunmaz (DarkBot'un PET ayari gecerli). */
    public static class Pet {
        @Option("botplus.pet.enabled")
        public boolean ENABLED = true;

        @Option("botplus.pet.combat_change")
        public boolean COMBAT_CHANGE = true;

        @Option("botplus.pet.combat")
        @Dropdown
        public CombatPet COMBAT = CombatPet.LOOTER;

        @Option("botplus.pet.travel_locator")
        public boolean TRAVEL_LOCATOR = true;

        @Option("botplus.pet.repair")
        public boolean REPAIR = true;

        /** PET canı dusunce (dovus disinda) PET kendi tamirine gecer; sinir %. Gorunum alanlari (menude CoreConfig). */
        public boolean LOW_REPAIR = false;
        public int LOW_HP = 40;
    }

    /** Koruma: EMP, Insta-Shield, PET HP Link (her biri kendi kosullari ve can siniri ile). */
    public static class Protect {
        @Option("botplus.protect.enabled")
        public boolean ENABLED = false;

        @Option("botplus.protect.emp.enabled")
        public boolean EMP_ON = false;

        @Option("botplus.protect.emp.when")
        @Dropdown(multi = true)
        public Set<When> EMP_WHEN = EnumSet.of(When.HP_LOW);

        @Option("botplus.protect.emp.hp")
        @Number(min = 5, max = 100, step = 5)
        public int EMP_HP = 50;

        @Option("botplus.protect.ish.enabled")
        public boolean ISH_ON = false;

        @Option("botplus.protect.ish.when")
        @Dropdown(multi = true)
        public Set<When> ISH_WHEN = EnumSet.of(When.HP_LOW);

        @Option("botplus.protect.ish.hp")
        @Number(min = 5, max = 100, step = 5)
        public int ISH_HP = 40;

        @Option("botplus.protect.hp_link")
        public HpLinkSet HP_LINK = new HpLinkSet();

        @Option("botplus.killer")
        public Killer KILLER = new Killer();

        /** Kod gorunumu (Defense alt modulu): dusman oyuncu ates edince dovus ne olur. */
        public transient OnPlayer ON_PLAYER = OnPlayer.DROP_AWAY;
        /** Kod gorunumu (Defense alt modulu): oyuncuya ates ederken RSB (RSB alt modulu aciksa). */
        public transient boolean RSB_PLAYERS = false;

        public void fix() {
            if (HP_LINK == null) HP_LINK = new HpLinkSet();
            if (KILLER == null) KILLER = new Killer();
            if (EMP_WHEN == null) EMP_WHEN = EnumSet.of(When.HP_LOW);
            if (ISH_WHEN == null) ISH_WHEN = EnumSet.of(When.HP_LOW);
            if (HP_LINK.WHEN == null) HP_LINK.WHEN = EnumSet.of(When.HP_LOW);
            // Eski kayitta kalkan secenek (ornegin "Dusman ilk saldirinca") okunamaz, bos deger olarak gelir: atilir.
            EMP_WHEN.remove(null);
            ISH_WHEN.remove(null);
            HP_LINK.WHEN.remove(null);
        }
    }

    public static class HpLinkSet {
        @Option("botplus.protect.hp_link.enabled")
        public boolean ENABLED = false;

        @Option("botplus.protect.hp_link.when")
        @Dropdown(multi = true)
        public Set<When> WHEN = EnumSet.of(When.HP_LOW);

        @Option("botplus.protect.hp_link.hp")
        @Number(min = 5, max = 100, step = 5)
        public int HP = 40;
    }

    public static class Killer {
        @Option("botplus.killer.enabled")
        public boolean ENABLED = false;

        @Option("botplus.killer.minutes")
        @Number(min = 1, max = 60, step = 1)
        public int MINUTES = 10;
    }

    public static class Steal {
        @Option("botplus.steal.enabled")
        public boolean ENABLED = false;

        @Option("botplus.steal.which")
        @Dropdown
        public StealWhich WHICH = StealWhich.ALL;

        @Option("botplus.steal.from")
        @Dropdown
        public StealFrom FROM = StealFrom.ENEMY;

        @Option("botplus.steal.npc_hp")
        @Number(min = 0, max = 100, step = 5)
        public int NPC_HP = 60;

        @Option("botplus.steal.action")
        @Dropdown
        public StealAction ACTION = StealAction.KILL;

        @Option("botplus.steal.enemy_hp")
        @Number(min = 5, max = 95, step = 5)
        public int ENEMY_HP = 30;

        @Option("botplus.steal.chase_sec")
        @Number(min = 5, max = 120, step = 5)
        public int CHASE_SEC = 20;

        @Option("botplus.steal.hero_hp")
        @Number(min = 0, max = 100, step = 5)
        public int HERO_HP = 50;

        @Option("botplus.steal.ammo")
        @Dropdown
        public Ammo AMMO = Ammo.X2;

        @Option("botplus.steal.pet")
        @Dropdown
        public StealPet PET = StealPet.GUARD;
    }

    /** Kacan NPC: kod icin gorunum (menude Combat Plus altinda: Kacan NPC'yi Kovala + Menzil Dususu). */
    public static class Chase {
        public boolean ENABLED = false;
        public int DROP = 20;
        public int MAX_SEC = CHASE_MAX_SEC;
    }

    public static class Repair {
        /** Tamirde uzak durulan mesafeler (menude yok). */
        public static final int NPC_DIST = 1500;
        public static final int ENEMY_DIST = 3000;

        @Option("botplus.repair.enabled")
        public boolean ENABLED = false;

        @Option("botplus.repair.place")
        @Dropdown
        public RepairPlace PLACE = RepairPlace.MAP;

        @Option("botplus.repair.mode")
        public ShipMode MODE = repairDefault();

        @Option("botplus.repair.start_hp")
        @Number(min = 5, max = 95, step = 5)
        public int START_HP = 40;

        @Option("botplus.repair.end_hp")
        @Number(min = 10, max = 100, step = 5)
        public int END_HP = 95;

        @Option("botplus.repair.avoid")
        @Dropdown(multi = true)
        public Set<RepairAvoid> AVOID = EnumSet.of(RepairAvoid.NPC, RepairAvoid.ENEMY);

        @Option("botplus.repair.enemy_run_mode")
        public boolean ENEMY_RUN_MODE = true;

        @Option("botplus.repair.near_npc")
        public boolean NEAR_NPC = false;

        @Option("botplus.repair.pet_ship")
        public boolean PET_SHIP = false;

        @Option("botplus.repair.idle")
        public boolean IDLE = false;

        @Option("botplus.repair.idle_hp")
        @Number(min = 10, max = 100, step = 5)
        public int IDLE_HP = 70;

        /** Bosta tamirde uzun yolda (3000+) dolasma konfigurasyonu (hizli formasyon); durunca tamir konfigurasyonu. */
        @Option("botplus.repair.travel_roam")
        public boolean TRAVEL_ROAM = true;

        public boolean npcAway() {
            return AVOID != null && AVOID.contains(RepairAvoid.NPC);
        }

        public boolean enemyAway() {
            return AVOID != null && AVOID.contains(RepairAvoid.ENEMY);
        }

        /** Haritada tamir acik mi (Portalda: tamir DarkBot'un). */
        public boolean onMap() {
            return ENABLED && PLACE != RepairPlace.PORTAL;
        }
    }

    public static class Refresh {
        @Option("botplus.renew.enabled")
        public boolean ENABLED = false;

        @Option("botplus.renew.mode")
        @Dropdown
        public RefreshMode MODE = RefreshMode.MAP;

        /** Yenilemeyi BotPlus mu yapar (Portalda: DarkBot gibi portala gidilir). */
        public boolean own() {
            return ENABLED && MODE != RefreshMode.PORTAL;
        }
    }

    /** Combat Plus (taktik NPC kesimi): dovusu BotPlus yonetir (yaklas, ilk vurus, yay, konum tahmini, hizli gecis). */
    public static class Combat {
        @Option("botplus.combat.enabled")
        public boolean ENABLED = false;

        @Option("botplus.combat.style")
        @Dropdown
        public CombatStyle STYLE = CombatStyle.ARC;

        @Option("botplus.chase.enabled")
        public boolean CHASE = false;

        @Option("botplus.chase.drop")
        @Number(min = 5, max = 50, step = 5)
        public int CHASE_DROP = 20;

        @Option("botplus.combat.options")
        @Dropdown(multi = true)
        public Set<CombatOption> OPTIONS = EnumSet.of(CombatOption.PRE_LOCK);

        /** Kod icin gorunum (Secenekler listesinden, fix ile). */
        public transient boolean INWARD = false, OUT_ON_DAMAGE = false, PRE_LOCK = true;

        @Option("botplus.combat.finish_first")
        public boolean FINISH_FIRST = true;

        /** Yeni dovuse baslamak icin en az gemi cani (0: kapali). Baslamis dovus bu yuzden birakilmaz. */
        @Option("botplus.combat.fight_min_hp")
        @Number(min = 0, max = 95, step = 5)
        public int FIGHT_MIN_HP = 40;

        @Option("botplus.combat.arc_set")
        public ArcSettings ARC = new ArcSettings();

        @Option("botplus.combat.safe_set")
        public SafeSettings SAFE = new SafeSettings();

        @Option("botplus.combat.out_set")
        public OutrangeSettings OUT = new OutrangeSettings();
    }

    /** Once Bitir Sonra Tamir: gemi cani bunun altina inerse NPC birakilip hemen tamire gidilir (menude yok). */
    public static final int FINISH_MIN_HP = 10;
    /** Kacan NPC en fazla kovalama suresi (sn, menude yok). */
    public static final int CHASE_MAX_SEC = 40;

    /**
     * Plus Ozellikleri: ust satirda "Aktif" tiki, altinda acilir-kapanir iki grup (DarkBot 5'ten fazla secenegi
     * olan alt gruplari kapali gosterir). Kod ozelliklere metotlarla bakar: Aktif kapaliysa hepsi kapali sayilir.
     */
    /** Yay ayarlari (Dovus Tarzi: Yay). */
    public static class ArcSettings {
        @Option("botplus.combat.arc_angle")
        @Number(min = 30, max = 360, step = 15)
        public int ANGLE = 90;
    }

    /** Guvenli Aci ayarlari (Dovus Tarzi: Guvenli Aci). */
    public static class SafeSettings {
        @Option("botplus.combat.safe_side")
        @Dropdown
        public SafeSide SIDE = SafeSide.AUTO;

        @Option("botplus.combat.safe_learn")
        public boolean LEARN = true;

        @Option("botplus.combat.safe_lock")
        public boolean LOCK = false;

        @Option("botplus.combat.safe_approach")
        public boolean APPROACH = true;

        @Option("botplus.combat.safe_exit")
        public boolean EXIT = true;
    }

    /** RSB: BotPlus'in kendi RSB secimi (tus gerekmez). DarkBot'un RSB'si engellenir. */
    public static class Rsb {
        @Option("botplus.rsb.enabled")
        public boolean ENABLED = false;

        @Option("botplus.rsb.which")
        @Dropdown
        public RsbWhich WHICH = RsbWhich.ALL;

        @Option("botplus.rsb.ammo")
        @Dropdown
        public Ammo AMMO = Ammo.RSB;

        @Option("botplus.rsb.ping")
        @Number(min = 50, max = 1000, step = 25)
        public int PING = 250;

        /** Akilli RSB: NPC cani %10'un altindayken RSB atilmaz (kod gorunumu). */
        public transient boolean SMART = true;

        @Option("botplus.rsb.lag_guard")
        public boolean LAG_GUARD = true;
    }

    @Configuration("botplus.rsb.which")
    public enum RsbWhich { FLAGGED, ALL }

    /** Menzil Disindan Vur ayarlari. */
    public static class OutrangeSettings {
        @Option("botplus.combat.out_margin")
        @Number(min = 0, max = 300, step = 10)
        public int MARGIN = 50;
    }

    public static class Plus {
        /** Sadece eski (1.x) ayarlardan aktarim icin okunur; calisirken hep acik (2.1.0'da anahtar kaldirildi). */
        @Option.Ignore
        public boolean ENABLED = true;

        @Option("botplus.plus.target")
        public PlusTarget TARGET = new PlusTarget();

        @Option("botplus.plus.other")
        public PlusOther OTHER = new PlusOther();

        public void fix() {
            if (TARGET == null) TARGET = new PlusTarget();
            if (OTHER == null) OTHER = new PlusOther();
        }

        public boolean NEAREST() { return ENABLED && TARGET.NEAREST; }
        public boolean NEXT_TARGET() { return ENABLED && TARGET.NEXT_TARGET; }
        public boolean FAST_LOCK() { return ENABLED && TARGET.FAST_LOCK; }
        public boolean WEAK_FIRST() { return ENABLED && TARGET.WEAK_FIRST; }
        public boolean AUTO_RANGE() { return ENABLED && TARGET.AUTO_RANGE; }
        public boolean RANGE_DOWN() { return ENABLED && TARGET.RANGE_DOWN; }
        public boolean NO_LEARN_FLEE() { return ENABLED && TARGET.NO_LEARN_FLEE; }
        public boolean BOX_WAIT() { return ENABLED && OTHER.BOX_WAIT; }
        public boolean RIVAL() { return ENABLED && OTHER.RIVAL; }
        public boolean SHORT_ROAM() { return ENABLED && OTHER.SHORT_ROAM; }
        public boolean SMART_ROAM() { return ENABLED && OTHER.SMART_ROAM; }
        /** PET radar (bulucu) menzili: dolasma noktalari kenara bundan yakin secilmez. */
        public int RADAR() { return OTHER.RADAR; }
        public boolean SPEED_ABILITY() { return ENABLED && OTHER.SPEED_ABILITY; }
        public boolean UNSTUCK() { return ENABLED && OTHER.UNSTUCK; }
        public boolean STATS() { return ENABLED && OTHER.STATS; }
    }

    /** Hedef ve menzil (6 secenek). */
    public static class PlusTarget {
        @Option("botplus.plus.nearest")
        public boolean NEAREST = false;

        @Option("botplus.plus.next_target")
        public boolean NEXT_TARGET = false;

        @Option("botplus.plus.fast_lock")
        public boolean FAST_LOCK = false;

        @Option("botplus.plus.weak_first")
        public boolean WEAK_FIRST = false;

        @Option("botplus.plus.auto_range")
        public boolean AUTO_RANGE = false;

        @Option("botplus.plus.range_down")
        public boolean RANGE_DOWN = true;

        @Option("botplus.plus.no_learn_flee")
        public boolean NO_LEARN_FLEE = true;

        @Option("botplus.plus.hp_wait")
        public HpWait HP_WAIT = new HpWait();
    }

    /** HP Bekle: can sinirin altindayken NPC'ye saldirilmaz (HB isaretli ya da tum NPC'ler). */
    public static class HpWait {
        @Option("botplus.plus.hp_wait.enabled")
        public boolean ENABLED = false;

        @Option("botplus.plus.hp_wait.which")
        @Dropdown
        public HpWaitWhich WHICH = HpWaitWhich.FLAGGED;

        @Option("botplus.plus.hp_wait.hp")
        @Number(min = 10, max = 100, step = 5)
        public int HP = 70;

        /** Bu NPC icin HP beklenir mi (can sinira bakilmadan). */
        public boolean appliesTo(eu.darkbot.api.config.types.NpcInfo i) {
            return ENABLED && i != null && (WHICH == HpWaitWhich.ALL || i.hasExtraFlag(BotPlusFlag.WAIT_HP));
        }
    }

    /** Kutu, dolasma ve diger. */
    public static class PlusOther {
        @Option("botplus.plus.box_wait")
        public boolean BOX_WAIT = false;

        @Option("botplus.plus.rival")
        public boolean RIVAL = false;

        @Option("botplus.plus.short_roam")
        public boolean SHORT_ROAM = false;

        @Option("botplus.plus.smart_roam")
        public boolean SMART_ROAM = false;

        @Option("botplus.plus.radar")
        @Number(min = 1000, max = 6000, step = 250)
        public int RADAR = 3000;

        @Option("botplus.plus.speed_ability")
        public boolean SPEED_ABILITY = false;

        @Option("botplus.plus.unstuck")
        public boolean UNSTUCK = false;

        @Option("botplus.plus.stats")
        public boolean STATS = false;
    }

    @Configuration("botplus.pet.combat")
    public enum CombatPet {
        LOOTER(PetGear.LOOTER), GUARD(PetGear.GUARD), KAMIKAZE(PetGear.KAMIKAZE), COMBO_REPAIR(PetGear.COMBO_REPAIR),
        COMBO_GUARD(PetGear.COMBO_GUARD);

        public final PetGear gear;

        CombatPet(PetGear gear) {
            this.gear = gear;
        }
    }

    /** Combat: NPC'ye saldirirken PET modulu. NONE: degistirilmez (DarkBot'un PET ayari). */
    @Configuration("botplus.combat.pet")
    public enum FightPet {
        NONE(null), LOOTER(PetGear.LOOTER), GUARD(PetGear.GUARD), KAMIKAZE(PetGear.KAMIKAZE),
        COMBO_REPAIR(PetGear.COMBO_REPAIR), COMBO_GUARD(PetGear.COMBO_GUARD);

        public final PetGear gear;

        FightPet(PetGear gear) {
            this.gear = gear;
        }

        public static FightPet of(CombatPet c) {
            if (c == null) return NONE;
            switch (c) {
                case GUARD: return GUARD;
                case KAMIKAZE: return KAMIKAZE;
                case COMBO_REPAIR: return COMBO_REPAIR;
                case COMBO_GUARD: return COMBO_GUARD;
                default: return LOOTER;
            }
        }

        public CombatPet legacy() {
            switch (this) {
                case GUARD: return CombatPet.GUARD;
                case KAMIKAZE: return CombatPet.KAMIKAZE;
                case COMBO_REPAIR: return CombatPet.COMBO_REPAIR;
                case COMBO_GUARD: return CombatPet.COMBO_GUARD;
                default: return CombatPet.LOOTER;
            }
        }
    }

    @Configuration("botplus.steal.pet")
    public enum StealPet {
        NONE(null), GUARD(PetGear.GUARD), KAMIKAZE(PetGear.KAMIKAZE), COMBO_GUARD(PetGear.COMBO_GUARD);

        public final PetGear gear;

        StealPet(PetGear gear) {
            this.gear = gear;
        }
    }

    /** NPC'ye (ya da bulucu sinyaline) giderken kutular. */
    @Configuration("botplus.box_way")
    public enum BoxWay { IGNORE, ON_ROUTE, NEARBY }

    @Configuration("botplus.steal.ammo")
    public enum Ammo {
        X1(SelectableItem.Laser.LCB_10), X2(SelectableItem.Laser.MCB_25), X3(SelectableItem.Laser.MCB_50),
        X4(SelectableItem.Laser.UCB_100), SAB(SelectableItem.Laser.SAB_50), RSB(SelectableItem.Laser.RSB_75),
        CBO(SelectableItem.Laser.CBO_100), A_BL(SelectableItem.Laser.A_BL);

        public final SelectableItem.Laser laser;

        Ammo(SelectableItem.Laser laser) {
            this.laser = laser;
        }
    }

    @Configuration("botplus.repair.place")
    public enum RepairPlace { MAP, PORTAL }

    @Configuration("botplus.repair.avoid")
    public enum RepairAvoid { NPC, ENEMY }

    /**
     * Koruma: ne zaman basilir. Secilenler birlikte aranir: HP sinirin altinda + saldirgan (oyuncu ya da NPC,
     * biri yeter). Hic secim yoksa "HP sinirin altinda" sayilir.
     */
    @Configuration("botplus.protect.when")
    public enum When { HP_LOW, ENEMY, NPC }

    /** Combat Plus secenekleri (coklu secim). */
    @Configuration("botplus.combat.options")
    public enum CombatOption { INWARD, OUT_ON_DAMAGE, PRE_LOCK }

    @Configuration("botplus.plus.hp_wait.which")
    public enum HpWaitWhich { FLAGGED, ALL }

    @Configuration("botplus.steal.which")
    public enum StealWhich { FLAGGED, ALL }

    /** Calisma bicimi (ic): ne yapilacagi DarkBot modulunden, sira Siralama ayarindan gelir. */
    public enum BotMode { NPC, BOX, NPC_BOX, BOX_NPC }

    /** Siralama: NPC ve kutu toplayan modulde once hangisi. */
    @Configuration("botplus.sort")
    public enum Sort { MODULE, NPC_FIRST, BOX_FIRST, TABLE }

    /** Combat Plus: dovus hareketi. */
    @Configuration("botplus.combat.style")
    public enum CombatStyle { ARC, CIRCLE, HOLD, SAFE, OUTRANGE }

    /**
     * Guvenli taraf: NPC'ye gore ekrandaki taraf. sector: 0 = sag, 1 = sag alt ... (ekranda y asagi, saat yonu).
     */
    @Configuration("botplus.combat.safe_side")
    public enum SafeSide {
        AUTO(-1), UP_RIGHT(7), RIGHT(0), DOWN_RIGHT(1), DOWN(2), DOWN_LEFT(3), LEFT(4), UP_LEFT(5), UP(6);

        public final int sector;

        SafeSide(int sector) {
            this.sector = sector;
        }

        public static SafeSide of(int sector) {
            for (SafeSide s : values()) if (s.sector == sector) return s;
            return AUTO;
        }
    }

    /** Dusman oyuncu ates edince: dovuse devam / dovusu birak ve uzaklas / portala kac. */
    public enum OnPlayer { CONTINUE, DROP_AWAY, PORTAL }

    /** Dusman Saldirinca (BotPlus menusu): devam et / kac (portala) / hizla atlat (haritada). */
    @Configuration("botplus.on_enemy.mode")
    public enum OnEnemyMode { CONTINUE, ESCAPE, OUTRUN }

    /** Dusmani Hatirla: kimse / seni patlatan / patlatan + sana saldiran oyuncu. */
    @Configuration("botplus.on_enemy.remember")
    public enum RememberMode { OFF, KILLER, ATTACKER }

    /** Dusman Saldirinca: kod gorunumu (CoreConfig'ten kurulur, kaydedilmez). */
    public static class OnEnemy {
        public OnEnemyMode MODE = OnEnemyMode.CONTINUE;
        public int HP = 30;
        public int DIST = 2500;
        public int CALM_SEC = 5;
        public boolean FASTER_PORTAL = true;
        public boolean EMP = true;
        public int EMP_DIST = 2000;
    }

    /** Calisma sekli: sadece NPC / sadece kutu / NPC + kutu. */
    @Configuration("botplus.work")
    public enum WorkMode { NPC, BOX, NPC_BOX }

    @Configuration("botplus.log_level")
    public enum LogLevel { NORMAL, DETAILED }

    @Configuration("botplus.renew.mode")
    public enum RefreshMode { MAP, PORTAL, LOGOUT }


    @Configuration("botplus.steal.from")
    public enum StealFrom { ENEMY, ALL }

    @Configuration("botplus.steal.action")
    public enum StealAction { KILL, REDUCE }
}