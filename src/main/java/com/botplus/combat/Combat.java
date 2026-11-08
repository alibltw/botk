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

        @Option.Ignore // 2.6.0: tarz ince ayarlari menuden kaldirildi (varsayilanlar kodda)
        public BotPlusConfig.OrbitSettings ORBIT = new BotPlusConfig.OrbitSettings();

        @Option.Ignore // 2.6.0: tarz ince ayarlari menuden kaldirildi (varsayilanlar kodda)
        public BotPlusConfig.PendulumSettings PENDULUM = new BotPlusConfig.PendulumSettings();

        @Option.Ignore // 2.6.0: tarz ince ayarlari menuden kaldirildi (varsayilanlar kodda)
        public BotPlusConfig.WaveSettings WAVE = new BotPlusConfig.WaveSettings();

        @Option.Ignore // 2.6.0: tarz ince ayarlari menuden kaldirildi (varsayilanlar kodda)
        public BotPlusConfig.HarborSettings HARBOR = new BotPlusConfig.HarborSettings();

        @Option.Ignore // 2.6.0: tarz ince ayarlari menuden kaldirildi (varsayilanlar kodda)
        public BotPlusConfig.CrescentSettings CRESCENT = new BotPlusConfig.CrescentSettings();

        @Option.Ignore // 2.6.0: tarz ince ayarlari menuden kaldirildi (varsayilanlar kodda)
        public BotPlusConfig.LockSettings LOCK = new BotPlusConfig.LockSettings();

        @Option("botplus.combat.claw")
        public BotPlusConfig.ClawSettings CLAW = new BotPlusConfig.ClawSettings();

        /** 2.7.0: Kacisi Onle Menzil listesine tasindi. */
        @Option("botplus.combat.options")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.CombatMenuOpt> OPTIONS_SET = EnumSet.of(BotPlusConfig.CombatMenuOpt.PRE_LOCK,
                BotPlusConfig.CombatMenuOpt.OPENING_GUARD, BotPlusConfig.CombatMenuOpt.RICOCHET);

        @Option("botplus.combat.finish_first")
        public boolean FINISH_FIRST = true;

        /** 2.7.0: Kacan NPC + Menzil tek liste (kovala %70, vuramazsa en fazla %60'a kis: sabit). */
        @Option("botplus.combat.range_opts")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.RangeOpt> MENZIL = EnumSet.of(BotPlusConfig.RangeOpt.NO_HIT_SHRINK);

        @Option("botplus.combat.target")
        public Target TARGET = new Target();

        // 2.7.0 oncesi alanlar: menude yok, bir kez yeni listelere aktarilir.
        @Option.Ignore
        public Set<BotPlusConfig.CombatOption> OPTIONS = EnumSet.of(BotPlusConfig.CombatOption.PRE_LOCK,
                BotPlusConfig.CombatOption.OPENING_GUARD, BotPlusConfig.CombatOption.RICOCHET);

        @Option.Ignore
        public Flee FLEE = new Flee();

        @Option.Ignore
        public Range RANGE = new Range();

        @Option.Ignore
        public boolean MIGRATED_27 = false;

        /** 2.7.0 aktarma (setConfig'te bir kez). true: aktarildi, kaydedilmeli. */
        public boolean migrate27() {
            if (MIGRATED_27) return false;
            Set<BotPlusConfig.CombatMenuOpt> o = EnumSet.noneOf(BotPlusConfig.CombatMenuOpt.class);
            if (OPTIONS != null)
                for (BotPlusConfig.CombatOption c : OPTIONS) {
                    if (c == null || c == BotPlusConfig.CombatOption.FLEE_CUT) continue;
                    o.add(BotPlusConfig.CombatMenuOpt.valueOf(c.name()));
                }
            OPTIONS_SET = o;
            Set<BotPlusConfig.RangeOpt> m = EnumSet.noneOf(BotPlusConfig.RangeOpt.class);
            if (FLEE != null && FLEE.CHASE) m.add(BotPlusConfig.RangeOpt.CHASE);
            if (OPTIONS != null && OPTIONS.contains(BotPlusConfig.CombatOption.FLEE_CUT)) m.add(BotPlusConfig.RangeOpt.FLEE_CUT);
            if (RANGE == null || RANGE.NO_HIT_MIN < 100) m.add(BotPlusConfig.RangeOpt.NO_HIT_SHRINK);
            MENZIL = m;
            MIGRATED_27 = true;
            return true;
        }
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