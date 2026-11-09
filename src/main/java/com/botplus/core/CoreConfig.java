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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * BotPlus (ana modul) menusu. Ust sinifin (BotPlusConfig) alanlari eski surumun kayitli ayarlaridir: menude gorunmez
 * (DarkBot sadece bu sinifta tanimli alanlari listeler), sadece ilk acilista yeni menulere / alt modullere aktarmak
 * icin okunur. Kodun kullandigi gorunum (Context.config) her tik bu menu ve alt modullerin ayarlarindan kurulur.
 */
@Configuration("botplus")
public class CoreConfig extends BotPlusConfig {

    @Option("botplus.general")
    public General GENEL = new General();

    /** 2.7.0: Hareket + Dolasma tek liste. */
    @Option("botplus.move")
    @Dropdown(multi = true)
    public Set<MoveOpt> HAREKET_SET = EnumSet.of(MoveOpt.PASS_PASSIVE);

    /** 2.8.0 Dusman Radari: dusman / tren / kalabalik ates etmeden; Onceden Kac ve Dusmani Hatirla buraya tasindi. */
    @Option("botplus.radar")
    public RadarSet RADAR = new RadarSet();

    /** 2.8.0: Onceden Kac / Dusmani Hatirla Dusman Saldirinca'dan Radar'a bir kez aktarildi mi. */
    @Option.Ignore
    public boolean MIGRATED_RADAR = false;

    /** 2.7.0: Kutu tek liste. */
    @Option("botplus.box")
    @Dropdown(multi = true)
    public Set<BoxOpt> KUTU_SET = EnumSet.of(BoxOpt.FAR_IGNORE);

    @Option("botplus.on_enemy")
    public OnEnemySet DUSMAN = new OnEnemySet();

    @Option("botplus.pet")
    public PetSet PET_AYAR = new PetSet();

    @Option("botplus.log_set")
    public LogSet KAYIT = new LogSet();

    // 2.7.0 oncesi menu gruplari: menude yok, sadece bir kez yeni alanlara aktarilmak icin okunur.
    @Option.Ignore
    public Move HAREKET = new Move();

    @Option.Ignore
    public RoamSet DOLASMA = new RoamSet();

    @Option.Ignore
    public BoxSet KUTU = new BoxSet();

    /** 2.7.0: Tamir (Repair) eklentisine "Can Bekle" olarak tasindi. */
    @Option.Ignore
    public HpWaitSet HP_BEKLE = new HpWaitSet();

    /** 2.7.0: Genel -> Yenileme secimine tasindi. */
    @Option.Ignore
    public RenewSet YENILEME = new RenewSet();

    /** 2.7.0 sade menu (Hareket / Kutu / Yenileme / Dusman Saldirinca listeleri) bir kez aktarildi mi. */
    @Option.Ignore
    public boolean MIGRATED_27 = false;

    /** Eski ayarlar bu menuye aktarildi mi. */
    @Option.Ignore
    public boolean MIGRATED = false;

    /** Ayarlari aktarilmis alt moduller (ozellik sinifi adi). */
    @Option.Ignore
    public Set<String> MIGRATED_SUBS = new HashSet<>();

    /** NPC adi -> asil tablo menzili (Otomatik Menzil / Menzili Dusur tabloyu degistirince geri yuklemek icin). */
    @Option.Ignore
    public java.util.Map<String, Integer> ASIL_MENZIL = new java.util.HashMap<>();

    /** NPC adi -> BotPlus'in tabloya en son yazdigi menzil (tablo buna esitse deger BotPlus'indir, degilse elle girilmis). */
    @Option.Ignore
    public java.util.Map<String, Integer> YAZILAN_MENZIL = new java.util.HashMap<>();

    /** Alt modullerin acik / kapali durumu eski ayarlardan bir kez kuruldu mu. */
    @Option.Ignore
    public boolean MIGRATED_ENABLED = false;

    public static class General {
        @Option("botplus.enabled")
        public boolean ENABLED = true;

        @Option("botplus.work")
        @Dropdown
        public WorkMode WORK = WorkMode.NPC_BOX;

        @Option("botplus.sort")
        @Dropdown
        public Order SORT = Order.NPC_FIRST;

        /** 2.7.0: DarkBot ayarlari (BotPlus yenilemeye karismaz) ya da BotPlus'in yenileme sekli. */
        @Option("botplus.renew_choice")
        @Dropdown
        public RenewChoice YENILE = RenewChoice.DARKBOT;
    }

    public static class Move {
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

        /** Saldirmayan NPC'lerden Kacinma: Boss Kristallon, Boss Lordakium, Talon'lar vb. yaninda dolasilmaz. */
        @Option("botplus.avoid_passive")
        public boolean PASS_PASSIVE = true;

        @Option("botplus.plus.speed_ability")
        public boolean SPEED_ABILITY = false;

        @Option("botplus.plus.unstuck")
        public boolean UNSTUCK = false;
    }

    public static class RoamSet {
        @Option("botplus.plus.short_roam")
        public boolean SHORT = false;

        @Option("botplus.plus.smart_roam")
        public boolean SMART = false;

        @Option("botplus.plus.radar")
        @Number(min = 1000, max = 6000, step = 250)
        public int RADAR = 3000;
    }

    public static class BoxSet {
        @Option("botplus.box_max_dist")
        @Number(min = 500, max = 20000, step = 250)
        public int MAX_DIST = 3000;

        @Option("botplus.box_way")
        @Dropdown
        public BoxWay WAY = BoxWay.IGNORE;

        @Option("botplus.plus.box_wait")
        public boolean WAIT = false;

        @Option("botplus.plus.rival")
        public boolean RIVAL = false;
    }

    /**
     * Dusman Saldirinca: dusman oyuncu bize ates edince ne yapilacagi. BotPlus aktifken DarkBot'un "Dusmandan Kac"
     * ayarlari yerine bu ayar gecerlidir (portal secimi / atlama / portalda bekleme DarkBot'un kacis sistemiyle).
     */
    public static class OnEnemySet {
        @Option("botplus.on_enemy.mode")
        @Dropdown
        public BotPlusConfig.OnEnemyMode MODE = BotPlusConfig.OnEnemyMode.CONTINUE;

        /** Kendi Sirketi Saldirinca: ayni sirketten oyuncu ates edince (ustteki ayar baska sirket icin). */
        @Option("botplus.on_enemy.own_mode")
        @Dropdown
        public BotPlusConfig.OwnMode OWN = BotPlusConfig.OwnMode.SAME;

        /** 2.7.0: dusman saldirirken can 2 sn'de %50 duserse (sabit) ne yapilir. Suren portal kacisi disinda her seyden once. */
        @Option("botplus.on_enemy.rapid_mode")
        @Dropdown
        public RapidMode HIZLI = RapidMode.PORTAL;

        /** Dusman bu kadar sure ates etmezse kacis biter, ise donulur. */
        @Option("botplus.on_enemy.outrun.calm")
        @Number(min = 2, max = 30, step = 1)
        public int SAKIN = 5;

        /** 2.7.0: Hizinla Atlat Secenekleri (yon secimi + Dusman Hizliysa Portala Kac). */
        @Option("botplus.on_enemy.outrun_opts")
        @Dropdown(multi = true)
        public Set<AtlatOpt> ATLAT_SET = EnumSet.of(AtlatOpt.REVERSE, AtlatOpt.ANTI_CUT, AtlatOpt.EDGE_SLIDE,
                AtlatOpt.SIDE_PASS, AtlatOpt.EMP_TURN, AtlatOpt.PORTAL_HOP, AtlatOpt.FASTER);

        /** 2.7.0: Portala Kacis Ayarlari (porta kacilirken uygulanir). */
        @Option("botplus.on_enemy.portal_opts")
        @Dropdown(multi = true)
        public Set<PortalOpt> PORTAL_SET = EnumSet.allOf(PortalOpt.class);

        /** 2.7.0: Gelismis Kacis (kacisin baslangici / bitisi). */
        @Option("botplus.on_enemy.adv_opts")
        @Dropdown(multi = true)
        public Set<AdvOpt> GELISMIS_SET = EnumSet.allOf(AdvOpt.class);

        /** 2.8.0 (gelistirme surumu) Onceden Kac: menude yok, Dusman Radari'na aktarilir. */
        @Option.Ignore
        public Set<PreWhen> ONCEDEN = EnumSet.of(PreWhen.APPROACH);

        // 2.7.0 oncesi alanlar: menude yok, bir kez yeni listelere aktarilir.
        @Option.Ignore
        public int HP = 30;

        @Option.Ignore
        public Set<EscapeOpt> KAC = EnumSet.allOf(EscapeOpt.class);

        @Option.Ignore
        public OutrunSet ATLAT = new OutrunSet();

        @Option.Ignore
        public EscapePlusSet GELISMIS = new EscapePlusSet();

        @Option.Ignore
        public Set<StartOpt> BASLANGIC = EnumSet.of(StartOpt.INSTANT_TURN);

        /** Dusmani Hatirla (2.1.0'da Defense'ten tasindi): hatirlanan oyuncu gorulunce uzaklasilir, yaninda NPC secilmez. */
        @Option.Ignore // 2.8.0: Dusman Radari'na tasindi
        public Set<BotPlusConfig.RememberWho> HATIRLA = EnumSet.of(BotPlusConfig.RememberWho.KILLER);

        /** 2.4.0 oncesi tek secim (Kapali / Seni Patlatan / Patlatan + Saldiran): bir kez HATIRLA'ya tasinir. */
        @Option.Ignore
        public BotPlusConfig.RememberMode REMEMBER = BotPlusConfig.RememberMode.KILLER;

        /** 0: dusman sirketten kimse hatirlanmaz (Hatirla secili olsa da). */
        @Option.Ignore // 2.8.0: Dusman Radari'na tasindi
        public int REMEMBER_MIN = 10;

        /** Kendi sirketinden saldiran ayri sure hatirlanir. */
        /** 0: kendi sirketinden kimse hatirlanmaz. */
        @Option.Ignore // 2.8.0: Dusman Radari'na tasindi
        public int REMEMBER_OWN_MIN = 1;

        /**
         * Saldir seciliyken (PvP Plus acik) hatirlanan oyuncudan kacilmaz, yanindaki NPC / bulucu sinyali yasaklanmaz:
         * karsilik PvP'ye birakilir. Push Korumasi'nin olme sinirindaki oyuncudan yine kacilir.
         */
        @Option.Ignore // 2.8.0: Dusman Radari'na tasindi
        public boolean REMEMBER_ATTACK_STAY = true;
    }

    /** 2.8.0 Dusman Radari. */
    public static class RadarSet {
        /** Kac / Kacin secenekleri (hicbiri secili degilse radar kapali; Dusmani Hatirla yine calisir). */
        @Option("botplus.radar.opts")
        @Dropdown(multi = true)
        public Set<RadarOpt> SECENEK = EnumSet.noneOf(RadarOpt.class);

        @Option("botplus.radar.enemy_avoid")
        @Number(min = 500, max = 8000, step = 100)
        public int KACINMA = 2000;

        @Option("botplus.radar.enemy_flee")
        @Number(min = 500, max = 5000, step = 100)
        public int KACMA = 1500;

        @Option("botplus.radar.low_hp")
        @Number(min = 10, max = 90, step = 5)
        public int HP = 40;

        @Option("botplus.radar.train")
        public TrainSet TREN = new TrainSet();

        @Option("botplus.on_enemy.remember")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.RememberWho> HATIRLA = EnumSet.of(BotPlusConfig.RememberWho.KILLER);

        @Option("botplus.on_enemy.remember_min")
        @Number(min = 0, max = 60, step = 1)
        public int REMEMBER_MIN = 10;

        @Option("botplus.on_enemy.remember_own_min")
        @Number(min = 0, max = 60, step = 1)
        public int REMEMBER_OWN_MIN = 1;

        @Option("botplus.on_enemy.remember_attack_stay")
        public boolean REMEMBER_ATTACK_STAY = true;
    }

    /** 2.8.0 Tren Ayarlari (Dusman Radari altinda, acilir grup). */
    public static class TrainSet {
        @Option("botplus.radar.train.size")
        @Number(min = 3, max = 8, step = 1)
        public int KISI = 4;

        @Option("botplus.radar.train.near")
        @Number(min = 300, max = 1500, step = 50)
        public int YAKIN = 600;

        @Option("botplus.radar.train.avoid")
        @Number(min = 1000, max = 8000, step = 100)
        public int KACINMA = 3000;

        @Option("botplus.radar.train.flee")
        @Number(min = 1000, max = 6000, step = 100)
        public int KACMA = 2500;
    }

    /**
     * 2.8.0: Onceden Kac ve Dusmani Hatirla (Dusman Saldirinca) -> Dusman Radari (bir kez). Onceden Kac eskiden sadece
     * Kac / Hizinla Atlat secilikken calisiyordu; radar moddan bagimsiz oldugu icin secimler sadece o modlarda aktarilir.
     */
    public boolean migrateRadar() {
        if (MIGRATED_RADAR) return false;
        fixCore();
        RadarSet r = RADAR;
        OnEnemySet d = DUSMAN;
        r.HATIRLA = EnumSet.noneOf(BotPlusConfig.RememberWho.class);
        if (d.HATIRLA != null) for (BotPlusConfig.RememberWho x : d.HATIRLA) if (x != null) r.HATIRLA.add(x);
        r.REMEMBER_MIN = d.REMEMBER_MIN;
        r.REMEMBER_OWN_MIN = d.REMEMBER_OWN_MIN;
        r.REMEMBER_ATTACK_STAY = d.REMEMBER_ATTACK_STAY;
        Set<RadarOpt> o = EnumSet.noneOf(RadarOpt.class);
        boolean modeOk = d.MODE == BotPlusConfig.OnEnemyMode.OUTRUN || d.MODE == BotPlusConfig.OnEnemyMode.ESCAPE;
        if (modeOk && d.ONCEDEN != null)
            for (PreWhen w : d.ONCEDEN) {
                if (w == PreWhen.APPROACH) o.add(RadarOpt.ENEMY_FLEE);
                else if (w == PreWhen.LOW_HP_NEAR) o.add(RadarOpt.LOW_HP_FLEE);
                else if (w == PreWhen.KILLER) o.add(RadarOpt.REMEMBERED_FLEE);
                else if (w == PreWhen.MULTI) o.add(RadarOpt.CROWD_FLEE);
            }
        r.SECENEK = o;
        MIGRATED_RADAR = true;
        Log.i("AYARLAR", "2.8.0: Onceden Kac / Dusmani Hatirla -> Dusman Radari " + o + " hatirla " + r.HATIRLA
                + " " + r.REMEMBER_MIN + "/" + r.REMEMBER_OWN_MIN + " dk");
        return true;
    }

    /** Gelismis Kacis: hepsi varsayilan acik. */
    public static class EscapePlusSet {
        /** Oyuncu ateşiyle can 2 sn icinde bu kadar duserse (her modda, saldirmadan) portala kacilir. 0: kapali. */
        @Option("botplus.on_enemy.plus.rapid")
        @Number(min = 0, max = 60, step = 5)
        public int RAPID_LOSS = 25;

        /**
         * 2.6.0 Akilli Kacis: guclu dusmandan erken kacilir. Can 1 sn'de %15 duserse, olume kalan sure 4 sn'nin altina
         * inerse ya da can, o oyuncunun 1 sn'de verdigi en buyuk hasarin 1,2 katinin altina inerse (Saldir'da da).
         */
        @Option("botplus.on_enemy.plus.smart")
        public boolean SMART = true;

        /** Can %50 altinda ve dusman 3000 icindeyse kacis bitmez (en fazla 60 sn). */
        @Option("botplus.on_enemy.plus.end_hold")
        public boolean END_HOLD = true;

        /** Can Kacis Cani'nin altindaysa koni / yaris kurali olmadan en yakin portal. */
        @Option("botplus.on_enemy.plus.crit_portal")
        public boolean CRIT_PORTAL = true;

        /** Uygun portal yoksa koni 60 -> 90 -> 120 derece genisletilir. */
        @Option("botplus.on_enemy.plus.widen_cone")
        public boolean WIDEN_CONE = true;

        /** Portala varinca sadece dusman takip ediyorsa atlanir; etmiyorsa portalda beklenir. */
        @Option("botplus.on_enemy.plus.follow_jump")
        public boolean FOLLOW_JUMP = true;

        /** Atladiktan sonra dusman da gecerse hemen geri atlanir; gecmezse 10 sn beklenip donulur. */
        @Option("botplus.on_enemy.plus.chain")
        public boolean PORTAL_CHAIN = true;

        /** Hizla Atlat: koseye / kenara giden yon yerine harita ortasina acik yon. */
        @Option("botplus.on_enemy.plus.corner")
        public boolean CORNER_GUARD = true;

        /** Hizla Atlat: dusman pesimizden yaklasiyorsa arkaya yavaslatici mayin (SLM-01). */
        @Option("botplus.on_enemy.plus.mine")
        public boolean MINE_BACK = true;

        /** Kacis modu Kac / Hizla Atlat iken Defense EMP'yi ve hiz yetenegini ilk atista harcamaz, kacis kullanir. */
        @Option("botplus.on_enemy.plus.save")
        public boolean SAVE_SKILLS = true;

        /** Atlatilan dusmanin yanindaki NPC'ler bu kadar sn secilmez (0: kapali). */
        @Option("botplus.on_enemy.plus.after")
        @Number(min = 0, max = 60, step = 5)
        public int AFTER_SEC = 10;

        /** Tamirde can %50 altinda ve dusman 3000 icindeyse haritada degil portalda tamir. */
        @Option("botplus.on_enemy.plus.repair_portal")
        public boolean REPAIR_PORTAL = true;
    }

    /** Hizla Atlat: haritada dusmandan hizla uzaklasma. */
    public static class OutrunSet {
        /** Hizla Atlat Secenekleri: haritada dusmandan uzaklasirken yon secimi. */
        @Option("botplus.on_enemy.outrun_opts")
        @Dropdown(multi = true)
        public Set<OutrunOpt> SECENEK = EnumSet.allOf(OutrunOpt.class);

        /** Dusmana bu kadar uzaklasinca atlatildi sayilir. */
        @Option("botplus.on_enemy.outrun.dist")
        @Number(min = 1500, max = 6000, step = 250)
        public int DIST = 2500;

        /** Bu kadar sure ates gelmezse ise donulur. */
        @Option("botplus.on_enemy.outrun.calm")
        @Number(min = 2, max = 30, step = 1)
        public int CALM_SEC = 5;

        /** Dusman bizden hizliysa (ya da yaklasiyorsa) portala kacilir. */
        @Option("botplus.on_enemy.outrun.faster")
        public boolean FASTER_PORTAL = true;

        /** Kacarken dusman hala ates ediyorsa EMP basilir (kilidi kirilir), dusmanin tersine kacmaya devam edilir. */
        @Option("botplus.on_enemy.outrun.emp")
        public boolean EMP = true;

        /** EMP, kacmaya baslayinca bu kadar yol gidildikten sonra dusman hala ates ediyorsa basilir. */
        @Option("botplus.on_enemy.outrun.emp_dist")
        @Number(min = 0, max = 5000, step = 250)
        public int EMP_DIST = 2000;
    }

    /** HP Bekle: "Yeni Dovus Icin En Az Can" ile birlesik. */
    public static class HpWaitSet {
        @Option("botplus.plus.hp_wait.enabled")
        public boolean ENABLED = false;

        @Option("botplus.plus.hp_wait.which")
        @Dropdown
        public HpWaitWhich WHICH = HpWaitWhich.FLAGGED;

        @Option("botplus.plus.hp_wait.hp")
        @Number(min = 10, max = 100, step = 5)
        public int HP = 70;

        @Option("botplus.plus.hp_wait.mode")
        @Dropdown
        public HpWaitMode MODE = HpWaitMode.NEAR;
    }

    /** Oncelik (sadece NPC + Toplama'da): NPC ile kutu bir aradayken once hangisi. Eski "Bot Modulu" kaldirildi. */
    @Configuration("botplus.order")
    public enum Order {
        NPC_FIRST(Sort.NPC_FIRST), BOX_FIRST(Sort.BOX_FIRST), TABLE(Sort.TABLE);

        public final Sort sort;

        Order(Sort sort) {
            this.sort = sort;
        }

        static Order of(Sort s) {
            return s == Sort.BOX_FIRST ? BOX_FIRST : s == Sort.TABLE ? TABLE : NPC_FIRST;
        }
    }

    /** HP Bekle: beklerken NPC'nin yaninda can doldur / dolasmaya devam et (yeni NPC'ye girme). */
    @Configuration("botplus.plus.hp_wait.mode")
    public enum HpWaitMode { NEAR, ROAM }

    /** PET: genel PET ayarlari. Saldiridaki PET Combat'ta, cal arken PET NPC Steal'de, yoldaki bulucu Enemy Locator'da. */
    public static class PetSet {
        @Option("botplus.pet.repair")
        public boolean REPAIR = true;

        @Option("botplus.pet.low_repair")
        public boolean LOW_REPAIR = true;

        @Option("botplus.pet.low_hp")
        @Number(min = 10, max = 90, step = 5)
        public int LOW_HP = 40;

        /** 2.6.0: PET bu kadar dakika acilamazsa oyun yenilenir (0: kapali). */
        @Option("botplus.pet.refresh_min")
        @Number(min = 0, max = 30, step = 1)
        public int REFRESH_MIN = 5;

        // Eski (2.0 ilk surum) ayarlar: menude yok, sadece Combat'a bir kez aktarmak icin okunur.
        @Option.Ignore
        public boolean ENABLED = true;

        @Option.Ignore
        public boolean COMBAT_CHANGE = true;

        @Option.Ignore
        public CombatPet COMBAT = CombatPet.LOOTER;
    }

    public static class RenewSet {
        @Option("botplus.renew.enabled")
        public boolean ENABLED = false;

        @Option("botplus.renew.mode")
        @Dropdown
        public RefreshMode MODE = RefreshMode.MAP;
    }

    public static class LogSet {
        @Option("botplus.log")
        public boolean ENABLED = true;

        @Option("botplus.log_level")
        @Dropdown
        public LogLevel LEVEL = LogLevel.DETAILED;

        @Option("botplus.log_file")
        public boolean FILE = true;

        @Option("botplus.plus.stats")
        public boolean STATS = false;
    }

    /** Alt sinifta null gelebilecek gruplar (eski kayit / elle duzenleme). */
    public void fixCore() {
        if (GENEL == null) GENEL = new General();
        if (GENEL.WORK == null) GENEL.WORK = WorkMode.NPC_BOX;
        if (GENEL.SORT == null) GENEL.SORT = Order.NPC_FIRST; // eski "Bot Modulu" kaydi da buraya duser
        if (HAREKET == null) HAREKET = new Move();
        if (DOLASMA == null) DOLASMA = new RoamSet();
        if (KUTU == null) KUTU = new BoxSet();
        if (KUTU.WAY == null) KUTU.WAY = BoxWay.IGNORE;
        if (DUSMAN == null) DUSMAN = new OnEnemySet();
        if (DUSMAN.MODE == null) DUSMAN.MODE = BotPlusConfig.OnEnemyMode.CONTINUE;
        if (DUSMAN.ATLAT == null) DUSMAN.ATLAT = new OutrunSet();
        if (DUSMAN.GELISMIS == null) DUSMAN.GELISMIS = new EscapePlusSet();
        if (DUSMAN.OWN == null) DUSMAN.OWN = BotPlusConfig.OwnMode.SAME;
        DUSMAN.REMEMBER_OWN_MIN = Math.max(0, Math.min(60, DUSMAN.REMEMBER_OWN_MIN));
        DUSMAN.REMEMBER_MIN = Math.max(0, Math.min(60, DUSMAN.REMEMBER_MIN));
        if (DUSMAN.KAC == null) DUSMAN.KAC = EnumSet.allOf(EscapeOpt.class);
        if (DUSMAN.BASLANGIC == null) DUSMAN.BASLANGIC = EnumSet.of(StartOpt.INSTANT_TURN);
        if (DUSMAN.ATLAT.SECENEK == null) DUSMAN.ATLAT.SECENEK = EnumSet.allOf(OutrunOpt.class);
        DUSMAN.KAC.remove(null);
        DUSMAN.BASLANGIC.remove(null);
        DUSMAN.ATLAT.SECENEK.remove(null);
        if (DUSMAN.REMEMBER == null) DUSMAN.REMEMBER = BotPlusConfig.RememberMode.KILLER;
        if (DUSMAN.HATIRLA == null) DUSMAN.HATIRLA = EnumSet.of(BotPlusConfig.RememberWho.KILLER);
        DUSMAN.HATIRLA.remove(null);
        if (RADAR == null) RADAR = new RadarSet();
        if (RADAR.TREN == null) RADAR.TREN = new TrainSet();
        if (RADAR.SECENEK == null) RADAR.SECENEK = EnumSet.noneOf(RadarOpt.class);
        RADAR.SECENEK.remove(null);
        if (RADAR.HATIRLA == null) RADAR.HATIRLA = EnumSet.of(BotPlusConfig.RememberWho.KILLER);
        RADAR.HATIRLA.remove(null);
        RADAR.KACINMA = Math.max(500, Math.min(8000, RADAR.KACINMA));
        RADAR.KACMA = Math.max(500, Math.min(5000, RADAR.KACMA));
        RADAR.HP = Math.max(10, Math.min(90, RADAR.HP));
        RADAR.REMEMBER_MIN = Math.max(0, Math.min(60, RADAR.REMEMBER_MIN));
        RADAR.REMEMBER_OWN_MIN = Math.max(0, Math.min(60, RADAR.REMEMBER_OWN_MIN));
        RADAR.TREN.KISI = Math.max(3, Math.min(8, RADAR.TREN.KISI));
        RADAR.TREN.YAKIN = Math.max(300, Math.min(1500, RADAR.TREN.YAKIN));
        RADAR.TREN.KACINMA = Math.max(1000, Math.min(8000, RADAR.TREN.KACINMA));
        RADAR.TREN.KACMA = Math.max(1000, Math.min(6000, RADAR.TREN.KACMA));
        if (HP_BEKLE == null) HP_BEKLE = new HpWaitSet();
        if (HP_BEKLE.WHICH == null) HP_BEKLE.WHICH = HpWaitWhich.FLAGGED;
        if (HP_BEKLE.MODE == null) HP_BEKLE.MODE = HpWaitMode.NEAR;
        if (PET_AYAR == null) PET_AYAR = new PetSet();
        if (PET_AYAR.COMBAT == null) PET_AYAR.COMBAT = CombatPet.LOOTER;
        PET_AYAR.REFRESH_MIN = Math.max(0, Math.min(30, PET_AYAR.REFRESH_MIN));
        if (YENILEME == null) YENILEME = new RenewSet();
        if (YENILEME.MODE == null) YENILEME.MODE = RefreshMode.MAP;
        if (KAYIT == null) KAYIT = new LogSet();
        if (KAYIT.LEVEL == null) KAYIT.LEVEL = LogLevel.DETAILED;
        if (MIGRATED_SUBS == null) MIGRATED_SUBS = new HashSet<>();
        if (ASIL_MENZIL == null) ASIL_MENZIL = new java.util.HashMap<>();
        if (YAZILAN_MENZIL == null) YAZILAN_MENZIL = new java.util.HashMap<>();
        // 2.7.0 listeleri
        if (GENEL.YENILE == null) GENEL.YENILE = RenewChoice.DARKBOT;
        if (HAREKET_SET == null) HAREKET_SET = EnumSet.of(MoveOpt.PASS_PASSIVE);
        if (KUTU_SET == null) KUTU_SET = EnumSet.of(BoxOpt.FAR_IGNORE);
        if (DUSMAN.HIZLI == null) DUSMAN.HIZLI = RapidMode.PORTAL;
        DUSMAN.SAKIN = Math.max(2, Math.min(30, DUSMAN.SAKIN));
        if (DUSMAN.ATLAT_SET == null) DUSMAN.ATLAT_SET = EnumSet.allOf(AtlatOpt.class);
        if (DUSMAN.PORTAL_SET == null) DUSMAN.PORTAL_SET = EnumSet.allOf(PortalOpt.class);
        if (DUSMAN.GELISMIS_SET == null) DUSMAN.GELISMIS_SET = EnumSet.allOf(AdvOpt.class);
        // Bilinmeyen (eski / elle bozulmus) kayitlar Gson'da null eleman olur.
        HAREKET_SET.remove(null);
        KUTU_SET.remove(null);
        DUSMAN.ATLAT_SET.remove(null);
        DUSMAN.PORTAL_SET.remove(null);
        DUSMAN.GELISMIS_SET.remove(null);
    }

    /** 2.7.0 aktarma (Settings.setCore, 1.x aktarmasindan sonra cagirir). */
    public void migrate27IfNeeded() {
        if (MIGRATED_27) return;
        fixCore(); // eski kayitta eksik gruplar (null) olmasin
        migrate27();
        MIGRATED_27 = true;
        Context.saveConfig();
    }

    /**
     * 2.7.0: eski menu gruplarindan (Hareket, Dolasma, Kutu, Yenileme, Dusman Saldirinca) yeni tek satirlik listelere
     * bir kez aktarma. Yeni kurulumda eski alanlar varsayilanda oldugu icin sonuc da varsayilanlarla ayni.
     */
    private void migrate27() {
        Set<MoveOpt> mv = EnumSet.noneOf(MoveOpt.class);
        if (HAREKET.AVOID_UNLISTED) mv.add(MoveOpt.AVOID_UNLISTED);
        if (HAREKET.AVOID_NEAR_OFF) mv.add(MoveOpt.AVOID_NEAR_OFF);
        if (HAREKET.PASS_PASSIVE) mv.add(MoveOpt.PASS_PASSIVE);
        if (HAREKET.SPEED_ABILITY) mv.add(MoveOpt.SPEED_ABILITY);
        if (HAREKET.UNSTUCK) mv.add(MoveOpt.UNSTUCK);
        if (DOLASMA.SHORT) mv.add(MoveOpt.SHORT_ROAM);
        if (DOLASMA.SMART) mv.add(MoveOpt.SMART_ROAM);
        HAREKET_SET = mv;

        Set<BoxOpt> bx = EnumSet.noneOf(BoxOpt.class);
        if (KUTU.WAIT) bx.add(BoxOpt.WAIT);
        if (KUTU.RIVAL) bx.add(BoxOpt.RIVAL);
        // Eski sinir 3000 ya da altindaysa "Uzaktaki Kutulari Yok Say" (3000) acik; daha buyukse sinir kalkar.
        if (KUTU.MAX_DIST <= BOX_FAR) bx.add(BoxOpt.FAR_IGNORE);
        if (KUTU.WAY == BoxWay.ON_ROUTE) bx.add(BoxOpt.WAY_ROUTE);
        if (KUTU.WAY == BoxWay.NEARBY) bx.add(BoxOpt.WAY_NEAR);
        KUTU_SET = bx;

        GENEL.YENILE = !YENILEME.ENABLED ? RenewChoice.DARKBOT
                : YENILEME.MODE == RefreshMode.LOGOUT ? RenewChoice.LOGOUT
                : YENILEME.MODE == RefreshMode.PORTAL ? RenewChoice.PORTAL : RenewChoice.MAP;

        OnEnemySet d = DUSMAN;
        EscapePlusSet g = d.GELISMIS == null ? new EscapePlusSet() : d.GELISMIS;
        OutrunSet a = d.ATLAT == null ? new OutrunSet() : d.ATLAT;
        d.HIZLI = g.RAPID_LOSS > 0 ? RapidMode.PORTAL : RapidMode.OFF;
        d.SAKIN = Math.max(2, Math.min(30, a.CALM_SEC));
        Set<AtlatOpt> at = EnumSet.noneOf(AtlatOpt.class);
        Set<OutrunOpt> os = a.SECENEK == null ? EnumSet.allOf(OutrunOpt.class) : a.SECENEK;
        if (os.contains(OutrunOpt.REVERSE)) at.add(AtlatOpt.REVERSE);
        if (os.contains(OutrunOpt.ANTI_CUT)) at.add(AtlatOpt.ANTI_CUT);
        if (os.contains(OutrunOpt.EDGE_SLIDE)) at.add(AtlatOpt.EDGE_SLIDE);
        if (os.contains(OutrunOpt.SIDE_PASS)) at.add(AtlatOpt.SIDE_PASS);
        if (os.contains(OutrunOpt.EMP_TURN)) at.add(AtlatOpt.EMP_TURN);
        if (os.contains(OutrunOpt.PORTAL_HOP)) at.add(AtlatOpt.PORTAL_HOP);
        if (a.FASTER_PORTAL) at.add(AtlatOpt.FASTER);
        d.ATLAT_SET = at;
        Set<PortalOpt> po = EnumSet.noneOf(PortalOpt.class);
        Set<EscapeOpt> ko = d.KAC == null ? EnumSet.allOf(EscapeOpt.class) : d.KAC;
        if (ko.contains(EscapeOpt.CONE)) po.add(PortalOpt.CONE);
        if (ko.contains(EscapeOpt.RACE)) po.add(PortalOpt.RACE);
        if (ko.contains(EscapeOpt.ELSE_OUTRUN)) po.add(PortalOpt.ELSE_OUTRUN);
        if (ko.contains(EscapeOpt.SWITCH_PORTAL)) po.add(PortalOpt.SWITCH_PORTAL);
        if (ko.contains(EscapeOpt.PASS_JUMP)) po.add(PortalOpt.PASS_JUMP);
        if (g.WIDEN_CONE) po.add(PortalOpt.WIDEN_CONE);
        if (g.FOLLOW_JUMP) po.add(PortalOpt.FOLLOW_JUMP);
        if (g.PORTAL_CHAIN) po.add(PortalOpt.PORTAL_CHAIN);
        if (g.REPAIR_PORTAL) po.add(PortalOpt.REPAIR_PORTAL);
        d.PORTAL_SET = po;
        Set<AdvOpt> ad = EnumSet.noneOf(AdvOpt.class);
        if (d.BASLANGIC == null || d.BASLANGIC.contains(StartOpt.INSTANT_TURN)) ad.add(AdvOpt.INSTANT_TURN);
        if (g.END_HOLD) ad.add(AdvOpt.END_HOLD);
        ad.add(AdvOpt.CHASE_HOLD); // eskiden hep acikti (Atlatma Uzakligi x 1,6)
        if (g.AFTER_SEC > 0) ad.add(AdvOpt.AFTER_STAY);
        if (g.CORNER_GUARD) ad.add(AdvOpt.CORNER_GUARD);
        d.GELISMIS_SET = ad;
    }

    /** "Uzaktaki Kutulari Yok Say" siniri. */
    public static final int BOX_FAR = 3000;
}