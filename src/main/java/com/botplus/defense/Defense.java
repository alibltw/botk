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

        /** 2.7.0 Karsi Saldiri: roket / mayin / Kacarken Zehirle / Yaklasinca Hiz Yetenegi (Gemi Yetenegi kendi listesinde). */
        @Option("botplus.protect.counter")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.CounterOpt> COUNTER_SET = EnumSet.noneOf(BotPlusConfig.CounterOpt.class);

        @Option("botplus.protect.emp")
        public EmpItem EMP = new EmpItem();

        @Option("botplus.protect.ish")
        public Item ISH = new Item(40);

        @Option("botplus.protect.hp_link")
        public BotPlusConfig.HpLinkSet HP_LINK = new BotPlusConfig.HpLinkSet();

        /** 2.7.0 Gemi Yetenegi: sadece gemideki yetenekler, okunur adla; "Kapali" (OFF) secilirse kullanilmaz. */
        @Option("botplus.protect.ability_pick")
        @Dropdown(options = AbilityOptions.class)
        public String GEMI_YETENEK = AbilityOptions.OFF;

        // 2.7.0 oncesi alanlar: menude yok, bir kez yeni alanlara aktarilir.
        @Option.Ignore
        public Set<BotPlusConfig.Counter> COUNTER = EnumSet.noneOf(BotPlusConfig.Counter.class);

        @Option.Ignore
        public eu.darkbot.api.game.items.SelectableItem.Ability ABILITY = eu.darkbot.api.game.items.SelectableItem.Ability.PUSAT_PLUS_SPEED_SAP;

        @Option.Ignore
        public boolean MIGRATED_27 = false;

        @Option.Ignore
        public boolean MIGRATED_28 = false;

        /**
         * 2.8.0 aktarma (bir kez): eski "Kacarken EMP" tiki EMP listesine (yakalayacakken + portala varmadan), "Portala
         * varmadan" herkese varsayilan; eski listelerdeki "NPC saldirirken" (EMP / ISH'de artik yok) atilir.
         */
        public boolean migrate28() {
            if (MIGRATED_28) return false;
            if (EMP == null) EMP = new EmpItem();
            if (ISH == null) ISH = new Item(40);
            java.util.Set<BotPlusConfig.EmpWhen> ew = EnumSet.noneOf(BotPlusConfig.EmpWhen.class);
            if (EMP.WHEN != null) for (BotPlusConfig.EmpWhen x : EMP.WHEN) if (x != null) ew.add(x);
            if (ew.isEmpty()) ew.add(BotPlusConfig.EmpWhen.HP_LOW);
            if (EMP.KACARKEN) {
                ew.add(BotPlusConfig.EmpWhen.ESC_CATCH);
                ew.add(BotPlusConfig.EmpWhen.ESC_PORTAL);
            }
            ew.add(BotPlusConfig.EmpWhen.ESC_PORTAL);
            EMP.WHEN = ew;
            java.util.Set<BotPlusConfig.IshWhen> iw = EnumSet.noneOf(BotPlusConfig.IshWhen.class);
            if (ISH.WHEN != null) for (BotPlusConfig.IshWhen x : ISH.WHEN) if (x != null) iw.add(x);
            if (iw.isEmpty()) iw.add(BotPlusConfig.IshWhen.HP_LOW);
            ISH.WHEN = iw;
            MIGRATED_28 = true;
            return true;
        }

        /** 2.7.0 aktarma. kacarkenEmp / lateSpeed: BotPlus menusundeki eski "Atlatirken EMP Bas" ve "Yaklasinca Hiz Yetenegi". */
        public boolean migrate27(boolean kacarkenEmp, boolean lateSpeed) {
            if (MIGRATED_27) return false;
            Set<BotPlusConfig.CounterOpt> s = EnumSet.noneOf(BotPlusConfig.CounterOpt.class);
            if (COUNTER != null)
                for (BotPlusConfig.Counter c : COUNTER) {
                    if (c == null || c == BotPlusConfig.Counter.ABILITY) continue;
                    s.add(BotPlusConfig.CounterOpt.valueOf(c.name()));
                }
            if (lateSpeed) s.add(BotPlusConfig.CounterOpt.LATE_SPEED);
            COUNTER_SET = s;
            boolean abilityOn = COUNTER != null && COUNTER.contains(BotPlusConfig.Counter.ABILITY) && ABILITY != null;
            GEMI_YETENEK = abilityOn ? ABILITY.name() : AbilityOptions.OFF;
            if (EMP == null) EMP = new EmpItem();
            EMP.KACARKEN = kacarkenEmp;
            MIGRATED_27 = true;
            return true;
        }

        /** Secili gemi yetenegi (Kapali / bilinmiyorsa null). */
        public eu.darkbot.api.game.items.SelectableItem.Ability ability() {
            AbilityOptions.selected = GEMI_YETENEK == null ? AbilityOptions.OFF : GEMI_YETENEK;
            if (GEMI_YETENEK == null || GEMI_YETENEK.isEmpty() || AbilityOptions.OFF.equals(GEMI_YETENEK)) return null;
            try {
                return eu.darkbot.api.game.items.SelectableItem.Ability.valueOf(GEMI_YETENEK);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

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

    /** Insta-Shield: kullan, ne zaman, can siniri. 2.8.0: Ne Zaman listesi Insta-Shield'e ozel (IshWhen). */
    public static class Item {
        @Option("botplus.protect.item.on")
        public boolean ON = false;

        @Option("botplus.protect.ish_when")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.IshWhen> WHEN = EnumSet.of(BotPlusConfig.IshWhen.HP_LOW);

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

    /**
     * EMP: kullan, ne zaman (2.8.0: kacis secenekleri dahil, EmpWhen), can siniri, yakalama mesafesi. Eski ayri
     * "Kacarken kilit suruyorsa" (KACARKEN) tiki kalkti; bir kez listeye aktarilir (migrate28).
     */
    public static class EmpItem {
        @Option("botplus.protect.item.on")
        public boolean ON = false;

        @Option("botplus.protect.emp_when")
        @Dropdown(multi = true)
        public Set<BotPlusConfig.EmpWhen> WHEN = EnumSet.of(BotPlusConfig.EmpWhen.HP_LOW, BotPlusConfig.EmpWhen.ESC_PORTAL);

        @Option("botplus.protect.item.hp")
        @Number(min = 5, max = 100, step = 5)
        public int HP = 50;

        /** 2.8.0 Kacarken: dusman yakalayacakken (bu mesafeye girip yaklasiyorsa) EMP basilir. */
        @Option("botplus.protect.emp.catch_dist")
        @Number(min = 200, max = 2000, step = 50)
        public int CATCH_DIST = 600;

        /** 2.7.0 "Kacarken kilit suruyorsa" (menude yok; 2.8.0'da listeye aktarilir). */
        @Option.Ignore
        public boolean KACARKEN = true;
    }

    /**
     * Gemi Yetenegi listesi: sadece gemide olan yetenekler, okunur adla ("PUSAT_PLUS_SPEED_SAP" -> "Pusat+ Speed Sap").
     * Gemi bilgisi yokken (oyun yuklenmeden) bilinen son liste, o da yoksa secili deger gosterilir.
     */
    public static class AbilityOptions implements Dropdown.Options<String> {
        public static final String OFF = "OFF";
        /** Context gemideki yetenekleri buraya yazar (oyun yuklenince). */
        public static volatile java.util.List<String> known = java.util.Collections.emptyList();
        /** Secili deger (gemi degisse de listede kalsin, kaybolmasin). */
        public static volatile String selected = OFF;

        @Override
        public java.util.Collection<String> options() {
            java.util.List<String> out = new java.util.ArrayList<>();
            out.add(OFF);
            java.util.List<String> k = known;
            if (!k.isEmpty()) {
                out.addAll(k);
                String sel = selected;
                if (sel != null && !out.contains(sel)) out.add(sel);
            } else // oyun yuklenmeden gemi bilinmiyor: secili deger kaybolmasin diye tum yetenekler
                for (eu.darkbot.api.game.items.SelectableItem.Ability a : eu.darkbot.api.game.items.SelectableItem.Ability.values())
                    out.add(a.name());
            return out;
        }

        @Override
        public String getText(String v) {
            if (v == null || v.isEmpty() || OFF.equals(v)) return "Kapalı";
            return pretty(v);
        }

        public static String pretty(String v) {
            StringBuilder b = new StringBuilder();
            for (String w : v.replace("_PLUS", "+").split("_")) {
                if (w.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                b.append(w.charAt(0)).append(w.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
            return b.toString();
        }
    }
}