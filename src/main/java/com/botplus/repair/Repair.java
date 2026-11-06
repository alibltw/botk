package com.botplus.repair;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
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

/**
 * BotPlus alt modulu: tamir. Tamir konfigurasyonu ile baslama / bitis cani DarkBot'un kendi ayarlarindan
 * (Genel -> Guvenlik -> Tamir) okunur, burada tekrar sorulmaz.
 */
@Feature(name = "Repair", description = "BotPlus: haritada tamir ve boşta tamir (konfigürasyon ve can sınırları DarkBot ayarlarından).")
public class Repair implements Configurable<Repair.Config> {

    public volatile Config config = new Config();

    @Override
    public void setConfig(ConfigSetting<Config> setting) {
        config = setting.getValue();
    }

    @Configuration("botplus.repair.place")
    public enum Place { MAP, PORTAL, DARKBOT }

    @Configuration("botplus.repair")
    public static class Config {
        @Option("botplus.repair.place")
        @Dropdown
        public Place PLACE = Place.MAP;

        @Option("botplus.repair.avoid")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.RepairAvoid> AVOID = EnumSet.of(BotPlusConfig.RepairAvoid.NPC, BotPlusConfig.RepairAvoid.ENEMY);

        @Option("botplus.repair.enemy_run_mode")
        public boolean ENEMY_RUN_MODE = true;

        @Option("botplus.repair.near_npc")
        public boolean NEAR_NPC = false;

        @Option("botplus.repair.pet_ship")
        public boolean PET_SHIP = false;

        @Option("botplus.repair.idle_set")
        public Idle IDLE = new Idle();
    }

    public static class Idle {
        @Option("botplus.repair.idle")
        public boolean ENABLED = false;

        @Option("botplus.repair.idle_hp")
        @Number(min = 10, max = 100, step = 5)
        public int HP = 70;
    }
}