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

    @Option("botplus.move")
    public Move HAREKET = new Move();

    @Option("botplus.roam")
    public RoamSet DOLASMA = new RoamSet();

    @Option("botplus.box")
    public BoxSet KUTU = new BoxSet();

    @Option("botplus.on_enemy")
    public OnEnemySet DUSMAN = new OnEnemySet();

    @Option("botplus.plus.hp_wait")
    public HpWaitSet HP_BEKLE = new HpWaitSet();

    @Option("botplus.pet")
    public PetSet PET_AYAR = new PetSet();

    @Option("botplus.renew")
    public RenewSet YENILEME = new RenewSet();

    @Option("botplus.log_set")
    public LogSet KAYIT = new LogSet();

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

        /** Bu canin altinda dusman ates ediyorsa her secenekte portala kacilir (0: kapali, 100: ilk atista). */
        @Option("botplus.on_enemy.hp")
        @Number(min = 0, max = 100, step = 5)
        public int HP = 30;

        /** Kac Secenekleri: portala kacarken portal secimi. */
        @Option("botplus.on_enemy.escape_opts")
        @Dropdown(multi = true)
        public Set<EscapeOpt> KAC = EnumSet.allOf(EscapeOpt.class);

        @Option("botplus.on_enemy.outrun")
        public OutrunSet ATLAT = new OutrunSet();

        /** Kacis Baslangici: dusman ilk ates ettiginde. */
        @Option("botplus.on_enemy.start_opts")
        @Dropdown(multi = true)
        public Set<StartOpt> BASLANGIC = EnumSet.of(StartOpt.INSTANT_TURN);

        /** Dusmani Hatirla (2.1.0'da Defense'ten tasindi): hatirlanan oyuncu gorulunce uzaklasilir, yaninda NPC secilmez. */
        @Option("botplus.on_enemy.remember")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.RememberWho> HATIRLA = EnumSet.of(BotPlusConfig.RememberWho.KILLER);

        /** 2.4.0 oncesi tek secim (Kapali / Seni Patlatan / Patlatan + Saldiran): bir kez HATIRLA'ya tasinir. */
        @Option.Ignore
        public BotPlusConfig.RememberMode REMEMBER = BotPlusConfig.RememberMode.KILLER;

        @Option("botplus.on_enemy.remember_min")
        @Number(min = 1, max = 60, step = 1)
        public int REMEMBER_MIN = 10;

        /** Kendi sirketinden saldiran ayri sure hatirlanir. */
        @Option("botplus.on_enemy.remember_own_min")
        @Number(min = 1, max = 60, step = 1)
        public int REMEMBER_OWN_MIN = 1;
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
        if (DUSMAN.OWN == null) DUSMAN.OWN = BotPlusConfig.OwnMode.SAME;
        DUSMAN.REMEMBER_OWN_MIN = Math.max(1, Math.min(60, DUSMAN.REMEMBER_OWN_MIN));
        if (DUSMAN.KAC == null) DUSMAN.KAC = EnumSet.allOf(EscapeOpt.class);
        if (DUSMAN.BASLANGIC == null) DUSMAN.BASLANGIC = EnumSet.of(StartOpt.INSTANT_TURN);
        if (DUSMAN.ATLAT.SECENEK == null) DUSMAN.ATLAT.SECENEK = EnumSet.allOf(OutrunOpt.class);
        DUSMAN.KAC.remove(null);
        DUSMAN.BASLANGIC.remove(null);
        DUSMAN.ATLAT.SECENEK.remove(null);
        if (DUSMAN.REMEMBER == null) DUSMAN.REMEMBER = BotPlusConfig.RememberMode.KILLER;
        if (DUSMAN.HATIRLA == null) DUSMAN.HATIRLA = EnumSet.of(BotPlusConfig.RememberWho.KILLER);
        DUSMAN.HATIRLA.remove(null);
        if (HP_BEKLE == null) HP_BEKLE = new HpWaitSet();
        if (HP_BEKLE.WHICH == null) HP_BEKLE.WHICH = HpWaitWhich.FLAGGED;
        if (HP_BEKLE.MODE == null) HP_BEKLE.MODE = HpWaitMode.NEAR;
        if (PET_AYAR == null) PET_AYAR = new PetSet();
        if (PET_AYAR.COMBAT == null) PET_AYAR.COMBAT = CombatPet.LOOTER;
        if (YENILEME == null) YENILEME = new RenewSet();
        if (YENILEME.MODE == null) YENILEME.MODE = RefreshMode.MAP;
        if (KAYIT == null) KAYIT = new LogSet();
        if (KAYIT.LEVEL == null) KAYIT.LEVEL = LogLevel.DETAILED;
        if (MIGRATED_SUBS == null) MIGRATED_SUBS = new HashSet<>();
        if (ASIL_MENZIL == null) ASIL_MENZIL = new java.util.HashMap<>();
        if (YAZILAN_MENZIL == null) YAZILAN_MENZIL = new java.util.HashMap<>();
    }
}