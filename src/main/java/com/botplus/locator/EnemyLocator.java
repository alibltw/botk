package com.botplus.locator;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;

/** BotPlus alt modulu: PET Dusman Bulucu (sinyale gitme, vurulan NPC, tur dondurme, bos harita, yenileme). */
@Feature(name = "Enemy Locator", description = "BotPlus: PET düşman bulucu sinyaline gitme ve ilgili ayarlar.")
public class EnemyLocator implements Configurable<EnemyLocator.Config> {

    public volatile Config config = new Config();

    @Override
    public void setConfig(ConfigSetting<Config> setting) {
        config = setting.getValue();
    }

    @Configuration("botplus.locator")
    public static class Config {
        @Option("botplus.locator.go_set")
        public Go GO = new Go();

        @Option("botplus.locator.attacked")
        public Attacked ATTACKED = new Attacked();

        @Option("botplus.locator.rotate")
        public boolean ROTATE = false;

        /** Bulucu 6 sn sinyal vermezse harita ortasinda dolasilir. */
        @Option("botplus.locator.empty")
        public boolean EMPTY = false;

        /** Deneme: NPC olunce PET kisa sure pasife alinip bulucuya geri doner. */
        @Option("botplus.locator.refresh")
        public boolean REFRESH = false;
    }

    public static class Go {
        @Option("botplus.locator.go")
        public boolean GO = false;

        @Option("botplus.locator.drop")
        public boolean DROP = false;

        @Option("botplus.locator.drop_hp")
        @Number(min = 0, max = 100, step = 5)
        public int DROP_HP = 50;

        @Option("botplus.pet.travel_locator")
        public boolean TRAVEL_LOCATOR = true;
    }

    public static class Attacked {
        @Option("botplus.locator.ignore")
        public boolean IGNORE = false;

        @Option("botplus.locator.evade_guard")
        public boolean EVADE_GUARD = true;
    }
}