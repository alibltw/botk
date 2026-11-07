package com.botplus.push;

import com.botplus.core.BotPlusConfig;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;

/**
 * BotPlus alt modulu: Push Korumasi. Ayni oyuncuyu tekrar tekrar oldurme (push) ve ayni oyuncudan tekrar tekrar olme
 * onlenir. Mantik PushGuard'da (BotPlus'in tikinde); bu sinif DarkBot'un eklenti listesindeki ac / kapa ve menu.
 */
@Feature(name = "Push Koruması", description = "BotPlus: aynı oyuncuyu tekrar tekrar öldürme / ondan ölme sınırı (PvP).")
public class Push implements Configurable<Push.Config>, Behavior {

    public volatile Config config = new Config();

    @Override
    public void setConfig(ConfigSetting<Config> setting) {
        config = setting.getValue();
    }

    /** Davranis olarak gorunur ("B" isareti); mantik BotPlus'in tikinde. */
    @Override
    public void onTickBehavior() {
    }

    @Configuration("botplus.push")
    public static class Config {
        @Option("botplus.push.enabled")
        public boolean ENABLED = true;

        @Option("botplus.push.kill_limit")
        @Number(min = 1, max = 20, step = 1)
        public int KILL_LIMIT = 2;

        @Option("botplus.push.death_limit")
        @Number(min = 1, max = 20, step = 1)
        public int DEATH_LIMIT = 2;

        @Option("botplus.push.window_h")
        @Number(min = 1, max = 72, step = 1)
        public int WINDOW_H = 24;

        @Option("botplus.push.same_clan")
        public boolean SAME_CLAN = true;
    }

    /** Kod gorunumune kopya (eklenti kapaliysa kapali). */
    public static BotPlusConfig.PushSettings view(Push p) {
        BotPlusConfig.PushSettings v = new BotPlusConfig.PushSettings();
        Config c = p == null ? null : p.config;
        v.ENABLED = c != null && c.ENABLED;
        if (c != null) {
            v.KILL_LIMIT = c.KILL_LIMIT;
            v.DEATH_LIMIT = c.DEATH_LIMIT;
            v.WINDOW_H = c.WINDOW_H;
            v.SAME_CLAN = c.SAME_CLAN;
        }
        return v;
    }
}