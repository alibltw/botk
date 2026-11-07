package com.botplus.defense;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
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

/** BotPlus alt modulu: EMP, Insta-Shield, PET HP Link, Dusmani Hatirla. */
@Feature(name = "Defense", description = "BotPlus: EMP, Insta-Shield, PET HP Link, düşmanı hatırla.")
public class Defense implements Configurable<Defense.Config>, eu.darkbot.api.extensions.Behavior {

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

    @Configuration("botplus.defense.on_player")
    public enum OnPlayer { CONTINUE, DROP_AWAY, PORTAL }

    @Configuration("botplus.protect")
    public static class Config {
        /** Eklenti listesinde acik olsa da bu tik kapaliysa Defense hicbir sey yapmaz. */
        @Option("botplus.defense.enabled")
        public boolean ENABLED = false;

        /** Kime Karsi: Karsi Saldiri hangi sirketten saldirana (varsayilan dusman sirket). */
        @Option("botplus.protect.firms")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.Firm> FIRMS = EnumSet.of(BotPlusConfig.Firm.ENEMY_FIRM);

        /** Karsi Saldiri: dusman saldirinca (ya da biz saldirirken) roket / mayin / gemi yetenegi / Kacarken Zehirle. */
        @Option("botplus.protect.counter")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.Counter> COUNTER = EnumSet.noneOf(BotPlusConfig.Counter.class);

        @Option("botplus.protect.emp")
        public Item EMP = new Item(50);

        @Option("botplus.protect.ish")
        public Item ISH = new Item(40);

        @Option("botplus.protect.hp_link")
        public BotPlusConfig.HpLinkSet HP_LINK = new BotPlusConfig.HpLinkSet();

        /** "Gemi Yetenegi" secildiyse kullanilacak yetenek. */
        @Option("botplus.protect.ability")
        @Dropdown
        public eu.darkbot.api.game.items.SelectableItem.Ability ABILITY = eu.darkbot.api.game.items.SelectableItem.Ability.PUSAT_PLUS_SPEED_SAP;

        /** 2.1.0: BotPlus menusu -> Dusman Saldirinca -> Dusmani Hatirla'ya tasindi; eski deger bir kez aktarilir. */
        @Option.Ignore
        public BotPlusConfig.Killer KILLER = new BotPlusConfig.Killer();

        /** 2.1.0: BotPlus menusundeki "Dusman Saldirinca"ya tasindi; eski deger bir kez aktarilmak icin okunur. */
        @Option.Ignore
        public OnPlayer ON_PLAYER = OnPlayer.DROP_AWAY;

        /** 2.4.0: Karsi Saldiri listesindeki "Kacarken Zehirle"ye tasindi (aciksa bir kez aktarilir). */
        @Option.Ignore
        public boolean INFECT = false;

        /** 2.4.0: PvP Plus -> RSB Kullan'a tasindi (aciksa bir kez aktarilir). */
        @Option.Ignore
        public boolean RSB_PLAYERS = false;
    }

    /** EMP / Insta-Shield: kullan, ne zaman, can siniri. */
    public static class Item {
        @Option("botplus.protect.item.on")
        public boolean ON = false;

        @Option("botplus.protect.when")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.When> WHEN = EnumSet.of(BotPlusConfig.When.HP_LOW);

        @Option("botplus.protect.item.hp")
        @Number(min = 5, max = 100, step = 5)
        public int HP;

        public Item() {
            this(50);
        }

        public Item(int hp) {
            this.HP = hp;
        }
    }
}