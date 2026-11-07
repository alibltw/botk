package com.botplus.combat;

import com.botplus.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;

import java.util.EnumSet;
import java.util.Set;

/** BotPlus alt modulu: dovus. Acik / kapali durumu DarkBot'un eklentiler sekmesinden; mantik BotPlus'ta. */
@Feature(name = "Combat", description = "BotPlus: dövüş (saldırı tarzı, hedef seçimi, menzil, kaçan NPC).")
public class Combat implements Configurable<Combat.Config>, eu.darkbot.api.extensions.Behavior {

    /**
     * Davranis olarak gorunur (DarkBot'un eklenti listesinde "B" isareti; hangi modul secili olursa olsun acik).
     * Mantik BotPlus'in tikinde calisir; burada ayrica bir sey yapilmaz.
     */
    @Override
    public void onTickBehavior() {
    }


    public volatile Config config = new Config();

    @Override
    public void setConfig(ConfigSetting<Config> setting) {
        config = setting.getValue();
    }

    @Configuration("botplus.combat")
    public static class Config {
        /** Kapaliyken dovusu DarkBot'un mantigi yapar; hedef ve menzil ayarlari ona yardim eder. */
        @Option("botplus.combat.full")
        public boolean FULL = true;

        @Option("botplus.combat.style")
        @Dropdown
        public BotPlusConfig.CombatStyle STYLE = BotPlusConfig.CombatStyle.ORBIT;

        @Option("botplus.combat.first_hit")
        @Dropdown
        public BotPlusConfig.FirstHit FIRST_HIT = BotPlusConfig.FirstHit.NORMAL;

        /** Kartal Dalisi bu canin altinda (ya da yaninda baska saldirgan NPC varken) yapilmaz, normal giris. */
        @Option("botplus.combat.eagle_min_hp")
        @Number(min = 0, max = 100, step = 5)
        public int EAGLE_MIN_HP = 50;

        /** NPC'ye yaklasinca PET bu module gecer; "Degistirme": DarkBot'un PET ayari. */
        @Option("botplus.combat.pet")
        @Dropdown
        public BotPlusConfig.FightPet PET = BotPlusConfig.FightPet.NONE;

        @Option("botplus.combat.orbit")
        public BotPlusConfig.OrbitSettings ORBIT = new BotPlusConfig.OrbitSettings();

        @Option("botplus.combat.pendulum")
        public BotPlusConfig.PendulumSettings PENDULUM = new BotPlusConfig.PendulumSettings();

        @Option("botplus.combat.wave")
        public BotPlusConfig.WaveSettings WAVE = new BotPlusConfig.WaveSettings();

        @Option("botplus.combat.harbor")
        public BotPlusConfig.HarborSettings HARBOR = new BotPlusConfig.HarborSettings();

        @Option("botplus.combat.crescent")
        public BotPlusConfig.CrescentSettings CRESCENT = new BotPlusConfig.CrescentSettings();

        @Option("botplus.combat.lock")
        public BotPlusConfig.LockSettings LOCK = new BotPlusConfig.LockSettings();

        @Option("botplus.combat.claw")
        public BotPlusConfig.ClawSettings CLAW = new BotPlusConfig.ClawSettings();

        @Option("botplus.combat.options")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.CombatOption> OPTIONS = EnumSet.of(BotPlusConfig.CombatOption.PRE_LOCK,
                BotPlusConfig.CombatOption.OPENING_GUARD, BotPlusConfig.CombatOption.RICOCHET);

        @Option("botplus.combat.finish_first")
        public boolean FINISH_FIRST = true;

        @Option("botplus.combat.flee")
        public Flee FLEE = new Flee();

        @Option("botplus.combat.target")
        public Target TARGET = new Target();

        @Option("botplus.combat.range")
        public Range RANGE = new Range();
    }

    public static class Flee {
        @Option("botplus.chase.enabled")
        public boolean CHASE = false;

        @Option("botplus.chase.drop")
        @Number(min = 5, max = 50, step = 5)
        public int DROP = 20;
    }

    public static class Target {
        @Option("botplus.plus.nearest")
        public boolean NEAREST = false;

        @Option("botplus.plus.fast_lock")
        public boolean FAST_LOCK = false;

        @Option("botplus.plus.next_target")
        public boolean NEXT_TARGET = false;

        @Option("botplus.plus.weak_first")
        public boolean WEAK_FIRST = false;
    }

    /** Menzil: sadece NPC listesindeki menzil kullanilir (ogrenme kaldirildi). */
    public static class Range {
        /** Isaretlenince BotPlus'in eskiden degistirdigi tum NPC menzilleri asil tablo degerlerine doner (sonra kendiliginden kapanir). */
        @Option("botplus.plus.range_restore")
        public boolean RESTORE = false;

        /** Vuramazsa Menzili Kis: NPC yok sayilmadan once saldiri mesafesi %10 adimlarla bu orana kadar kisilir. */
        @Option("botplus.combat.range.no_hit_min")
        @Number(min = 40, max = 90, step = 5)
        public int NO_HIT_MIN = 60;
    }
}