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
public class Combat implements Configurable<Combat.Config> {

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
        public BotPlusConfig.CombatStyle STYLE = BotPlusConfig.CombatStyle.ARC;

        /** NPC'ye yaklasinca PET bu module gecer; "Degistirme": DarkBot'un PET ayari. */
        @Option("botplus.combat.pet")
        @Dropdown
        public BotPlusConfig.FightPet PET = BotPlusConfig.FightPet.NONE;

        @Option("botplus.combat.arc_set")
        public BotPlusConfig.ArcSettings ARC = new BotPlusConfig.ArcSettings();

        @Option("botplus.combat.safe_set")
        public BotPlusConfig.SafeSettings SAFE = new BotPlusConfig.SafeSettings();

        @Option("botplus.combat.out_set")
        public BotPlusConfig.OutrangeSettings OUT = new BotPlusConfig.OutrangeSettings();

        @Option("botplus.combat.options")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.CombatOption> OPTIONS = EnumSet.of(BotPlusConfig.CombatOption.PRE_LOCK);

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

    public static class Range {
        @Option("botplus.plus.auto_range")
        public boolean AUTO_RANGE = false;

        @Option("botplus.plus.range_down")
        public boolean RANGE_DOWN = true;

        @Option("botplus.plus.no_learn_flee")
        public boolean NO_LEARN_FLEE = true;

        /** Isaretlenince BotPlus'in degistirdigi tum NPC menzilleri asil tablo degerlerine doner (sonra kendiliginden kapanir). */
        @Option("botplus.plus.range_restore")
        public boolean RESTORE = false;
    }
}