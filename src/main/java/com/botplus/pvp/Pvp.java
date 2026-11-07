package com.botplus.pvp;

import com.botplus.core.BotPlusConfig;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.config.annotations.Tag;
import eu.darkbot.api.config.types.PlayerTag;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;

/**
 * BotPlus alt modulu: PvP (Dusmana Saldir). BotPlus menusunde "Dusman Saldirinca -> Ne Yapilsin = Saldir" seciliyken
 * ve bu alt eklenti acikken dusmana karsilik verilir; kapaliyken "Saldir" Kac gibi davranir. Mantik PvpLogic'te.
 */
@Feature(name = "PvP Plus", description = "BotPlus: düşmana saldır (kime, kimlere, kovalama, şartlı saldırı).")
public class Pvp implements Configurable<Pvp.Config>, Behavior {

    public volatile Config config = new Config();

    @Override
    public void setConfig(ConfigSetting<Config> setting) {
        config = setting.getValue();
    }

    /** Davranis olarak gorunur ("B" isareti); mantik BotPlus'in tikinde. */
    @Override
    public void onTickBehavior() {
    }

    @Configuration("botplus.pvp")
    public static class Config {
        /** Varsayilan kapali: kullanici acmadan dusmana saldirilmaz. */
        @Option("botplus.pvp.enabled")
        public boolean ENABLED = false;

        @Option("botplus.on_enemy.pvp.who")
        @Dropdown
        public BotPlusConfig.PvpWho WHO = BotPlusConfig.PvpWho.ATTACKERS;

        /** Kimlere Saldir: varsayilan sadece dusman sirket (kendi sirketine ates seref kaybettirir). */
        @Option("botplus.pvp.firms")
        @Dropdown(multi = true)
        public java.util.Set<BotPlusConfig.Firm> FIRMS = java.util.EnumSet.of(BotPlusConfig.Firm.ENEMY_FIRM);

        @Option("botplus.on_enemy.pvp.tag")
        @Tag(Tag.Default.NONE)
        public PlayerTag TAG = null;

        @Option("botplus.on_enemy.pvp.chase")
        public boolean CHASE = true;

        @Option("botplus.on_enemy.pvp.chase_sec")
        @Number(min = 5, max = 120, step = 5)
        public int CHASE_SEC = 20;

        @Option("botplus.on_enemy.pvp.chase_dist")
        @Number(min = 1000, max = 10000, step = 250)
        public int CHASE_DIST = 3000;

        @Option("botplus.on_enemy.pvp.conditional")
        public boolean CONDITIONAL = true;

        @Option("botplus.on_enemy.pvp.retreat_hp")
        @Number(min = 0, max = 90, step = 5)
        public int RETREAT_HP = 40;

        @Option("botplus.on_enemy.pvp.crowd")
        public boolean CROWD = true;

        @Option("botplus.on_enemy.pvp.off_map")
        public boolean OFF_MAP = false;

        /** RSB Kullan: oyuncuya saldirirken (ve NPC Calma'da oyuncuya ates ederken) RSB alt eklentisi aciksa RSB. */
        @Option("botplus.pvp.rsb")
        public boolean RSB = false;
    }

    /** Kod gorunumune kopya. */
    public static BotPlusConfig.PvpSettings view(Pvp p) {
        BotPlusConfig.PvpSettings v = new BotPlusConfig.PvpSettings();
        Config c = p == null ? null : p.config;
        if (c == null) return v;
        v.WHO = c.WHO == null ? BotPlusConfig.PvpWho.ATTACKERS : c.WHO;
        v.TAG = c.TAG;
        v.CHASE = c.CHASE;
        v.CHASE_SEC = c.CHASE_SEC;
        v.CHASE_DIST = c.CHASE_DIST;
        v.CONDITIONAL = c.CONDITIONAL;
        v.RETREAT_HP = c.RETREAT_HP;
        v.CROWD = c.CROWD;
        v.FIRMS = c.FIRMS == null ? java.util.EnumSet.of(BotPlusConfig.Firm.ENEMY_FIRM)
                : c.FIRMS.isEmpty() ? java.util.EnumSet.noneOf(BotPlusConfig.Firm.class) : java.util.EnumSet.copyOf(c.FIRMS);
        v.OFF_MAP = c.OFF_MAP;
        v.RSB = c.RSB;
        return v;
    }
}