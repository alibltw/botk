package com.botplus.steal;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;

/** BotPlus alt modulu: NPC Cal (baskasinin vurdugu NPC). */
@Feature(name = "NPC Steal", description = "BotPlus: başkasının vurduğu NPC'yi çalma.")
public class NpcSteal implements Configurable<NpcSteal.Config> {

    public volatile Config config = new Config();

    @Override
    public void setConfig(ConfigSetting<Config> setting) {
        config = setting.getValue();
    }

    @Configuration("botplus.steal")
    public static class Config {
        /** Eklenti listesinde acik olsa da bu tik kapaliysa NPC calinmaz. */
        @Option("botplus.steal.enabled")
        public boolean ENABLED = false;

        @Option("botplus.steal.which")
        @Dropdown
        public BotPlusConfig.StealWhich WHICH = BotPlusConfig.StealWhich.ALL;

        @Option("botplus.steal.from")
        @Dropdown
        public BotPlusConfig.StealFrom FROM = BotPlusConfig.StealFrom.ENEMY;

        @Option("botplus.steal.npc_hp")
        @Number(min = 0, max = 100, step = 5)
        public int NPC_HP = 60;

        @Option("botplus.steal.action")
        @Dropdown
        public BotPlusConfig.StealAction ACTION = BotPlusConfig.StealAction.KILL;

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
        public BotPlusConfig.Ammo AMMO = BotPlusConfig.Ammo.X2;

        @Option("botplus.steal.pet")
        @Dropdown
        public BotPlusConfig.StealPet PET = BotPlusConfig.StealPet.GUARD;
    }
}