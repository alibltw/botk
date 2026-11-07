package com.botplus.rsb;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;

/**
 * BotPlus alt modulu: RSB. Aciksa BotPlus RSB calisir ve DarkBot'un kendi RSB'si susturulur; kapaliysa DarkBot'un
 * RSB ayari (gemi ayarlari) gecerlidir; ikisi de kapaliysa RSB atilmaz.
 */
@Feature(name = "RSB", description = "BotPlus: tuşsuz RSB cephanesi (ilk atış normal, ateş durursa normale dönüş).")
public class Rsb implements Configurable<Rsb.Config>, eu.darkbot.api.extensions.Behavior {

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

    @Configuration("botplus.rsb")
    public static class Config {
        /** Eklenti listesinde acik olsa da bu tik kapaliysa BotPlus RSB atmaz (DarkBot'un kendi RSB ayari gecerli). */
        @Option("botplus.rsb.enabled")
        public boolean ENABLED = false;

        @Option("botplus.rsb.which")
        @Dropdown
        public BotPlusConfig.RsbWhich WHICH = BotPlusConfig.RsbWhich.ALL;

        @Option("botplus.rsb.ammo")
        @Dropdown
        public BotPlusConfig.Ammo AMMO = BotPlusConfig.Ammo.RSB;

        @Option("botplus.rsb.ping")
        @Number(min = 50, max = 1000, step = 25)
        public int PING = 250;

        @Option("botplus.rsb.lag_guard")
        public boolean LAG_GUARD = true;

        /** Akilli RSB: NPC'nin cani %10'un altindayken RSB atilmaz (son atislarda RSB harcanmaz). */
        @Option("botplus.rsb.smart")
        public boolean SMART = true;
    }
}