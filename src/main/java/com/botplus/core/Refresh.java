package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.extensions.Module;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.other.Location;

/**
 * Yenileme: DarkBot'un yenileme suresi dolunca NPC modulu portala gitmeye baslar ("Escaping refresh").
 * BotPlus bunu gorur gormez isi devralir; portala gidilmez:
 * Direkt: oldugu yerde hemen yenilenir.
 * Once Uzaklas: saldiri birakilir, NPC'lerden ve dusmanlardan uzaklasilir, 1500 icinde kimse yokken
 * ve 3 sn vurulmadan yenilenir (60 sn'de olmazsa yine de yenilenir).
 * Sure DarkBot'un kendi yenileme ayarindan gelir; "mola ver" ayari da calisir.
 */
public final class Refresh {

    private static final int SAFE_DIST = 1500;
    private static final long CALM_MS = 3000;
    private static final long ESCAPE_TIMEOUT_MS = 60_000;
    private static final int STEP = 1500;

    private final Context context;

    public Refresh(Context context) {
        this.context = context;
    }

    /** NPC modulu yenileme icin portala gitmeye basladiysa isi doner. */
    public PlusModule.Job check() {
        if (!context.config.RENEW.own()) return null; // Portalda Yenile: DarkBot portala gider
        BotPlusConfig.RefreshMode mode = context.config.RENEW.MODE;
        Module m = context.mainModule.get();
        String st = m == null ? null : m.getStatus();
        // DarkBot'un modulu portala yenilemeye gidiyor ya da (Combat Plus tam kontrolde) DarkBot yenileme istedi.
        boolean wanted = Context.now() - context.refreshWantedAt < 2000;
        // Durum yazisi sadece Combat Plus kapaliyken ve son isten 3 sn sonra okunur: is surerken DarkBot'un modulu
        // tiklenmez, eski "Escaping refresh" yazisi kalir; hemen okunursa yenileme isi sonsuza kadar tekrar baslar.
        boolean status = !context.config.COMBAT.ENABLED && Context.now() - context.lastEndAt > 3000
                && st != null && st.toLowerCase(java.util.Locale.ROOT).contains("escaping refresh");
        if (!wanted && !status) return null;
        context.refreshWantedAt = 0;
        boolean forced = context.refreshForced;
        context.refreshForced = false;
        Log.i("YENILEME", "yenileme zamani geldi, portala gidilmiyor (" + (mode == BotPlusConfig.RefreshMode.LOGOUT
                ? "uzaklasilip cikis yapilacak" : "uzaklasilip haritada yenilenecek") + ")");
        return new Job(mode, forced);
    }

    private double danger(Location p) {
        double d = Double.MAX_VALUE;
        for (Npc n : context.ents.getNpcs()) if (n.isValid() && !Context.isFake(n)) d = Math.min(d, n.distanceTo(p));
        for (Player pl : context.ents.getPlayers())
            if (context.isEnemyPlayer(pl))
                d = Math.min(d, pl.distanceTo(p));
        return d;
    }

    public final class Job implements PlusModule.Job {
        private final BotPlusConfig.RefreshMode mode;
        private final long start = Context.now();
        private long calmSince = Context.now(), lastDrop;
        private double lastTotal = -1;
        private Location target;
        private long targetAt;
        private boolean allowed;
        private long allowedAt, logoutAt;
        private boolean reloadSeen, noWindow, refreshLogged;
        private long lastTickAt;
        /** 2.8.0: PET icin BotPlus istedi; DarkBot kendi yenilemez, izin gelince BotPlus bir kez yeniler. */
        private final boolean forced;
        private boolean forcedDone;

        private void forceRefresh() {
            if (!forced || forcedDone) return;
            forcedDone = true;
            Log.i("YENILEME", "PET yenilemesi: BotPlus yeniliyor");
            try {
                context.bot.handleRefresh();
            } catch (RuntimeException e) {
                Log.i("YENILEME", "yenilenemedi: " + e);
            }
        }

        /** Cikis yapiliyor (pencere acildi): bu sirada hicbir komut gonderilmez (PET dahil). */
        public boolean loggingOut() {
            return logoutAt > 0;
        }

        /**
         * Cikis Yapip Yenile: oyunun cikis penceresi acilir (geri sayim), sonra DarkBot yeniden yukler.
         * Geri sayim sirasinda gemiye / PET'e / saldiriya hicbir komut gitmez: oyun her eylemde cikisi iptal ediyor.
         */
        private boolean logout(long now) {
            if (logoutAt == 0) {
                context.stop();
                logoutAt = now;
                try {
                    eu.darkbot.api.managers.GameScreenAPI scr = context.pluginApi.requireAPI(eu.darkbot.api.managers.GameScreenAPI.class);
                    eu.darkbot.api.game.other.Gui g = scr.getGui("logout");
                    if (g != null) g.setVisible(true);
                    else noWindow = true;
                    Log.i("YENILEME", "cikis yapiliyor" + (g == null ? " (cikis penceresi bulunamadi, dogrudan yenilenecek)" : ""));
                } catch (Throwable e) {
                    noWindow = true;
                    Log.i("YENILEME", "cikis penceresi acilamadi (" + e + "), dogrudan yenilenecek");
                }
            }
            // Yeniden yukleme goruldu (gemi bilgisi gitti, sonra geldi): is biter, ikinci kez yenilenmez.
            // Oyun yuklenirken BotPlus tiklenmez: tikler arasi 3 sn'den uzun bosluk da yeniden yukleme sayilir.
            boolean loaded = context.heroLoaded();
            if (!loaded || (lastTickAt > 0 && now - lastTickAt > 3000)) reloadSeen = true;
            lastTickAt = now;
            if (!reloadSeen && (noWindow || now - logoutAt >= 22_000)) forceRefresh();
            if (reloadSeen && loaded) {
                Log.i("YENILEME", "yenilendi (" + (now - logoutAt) / 1000 + " sn)");
                return false;
            }
            // Yedek: 35 sn'de yenilenmediyse BotPlus yeniler (bir kez). DarkBot'a 22. sn'de izin verilir.
            if (!reloadSeen && now - logoutAt > 35_000) {
                Log.i("YENILEME", "35 sn'de yenilenmedi, BotPlus yeniliyor");
                try {
                    context.bot.handleRefresh();
                } catch (RuntimeException e) {
                    Log.i("YENILEME", "yenilenemedi: " + e);
                }
                return false;
            }
            return true;
        }

        public Job(BotPlusConfig.RefreshMode mode, boolean forced) {
            this.mode = mode;
            this.forced = forced;
        }

        @Override
        public String name() {
            return "YENILEME";
        }

        @Override
        public int priority() {
            return PlusModule.P_REFRESH;
        }

        /** Dusman ates ediyorsa kacis sistemi calisir (yenileme ateş altinda yapilmaz). */
        @Override
        public boolean safety() {
            if (loggingOut()) return false; // cikis geri sayiminda kacis da komut gonderir, cikis iptal olur
            Ship a = context.attackerOfHero();
            return context.safetyEscaping() || a instanceof Player; // basladiysa kacis yarida birakilmaz
        }

        @Override
        public String status() {
            if (allowed) return mode == BotPlusConfig.RefreshMode.LOGOUT ? "çıkış yapılıyor" : "yenileniyor";
            return "yenileme için uzaklaşılıyor";
        }

        @Override
        public boolean canRefresh() {
            // Cikis Yapip Yenile: cikis geri sayimi (premiumsuz 20 sn) bitince, pencere acildiktan 22 sn sonra
            // DarkBot'un kendi yenilemesine izin verilir (DarkBot yenileme sayacini sifirlar; kendimiz yenileseydik
            // DarkBot 1 dk sonra tekrar yenileme isterdi). Pencere bulunamadiysa hemen.
            boolean ok;
            if (mode == BotPlusConfig.RefreshMode.LOGOUT)
                ok = logoutAt > 0 && (noWindow || Context.now() - logoutAt >= 22_000);
            else ok = allowed;
            if (ok && !refreshLogged) {
                refreshLogged = true;
                Log.i("YENILEME", (mode == BotPlusConfig.RefreshMode.LOGOUT ? "cikis yapildi" : "guvenli nokta")
                        + ", DarkBot yeniliyor, " + Log.loc(context.hero) + " (" + (Context.now() - start) / 1000 + " sn)");
            }
            return ok;
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            if (allowed && mode != BotPlusConfig.RefreshMode.LOGOUT && now - start > 15_000 && now - allowedAt > 15_000)
                return false; // DarkBot yenilemedi, ana module don
            if (allowed && mode == BotPlusConfig.RefreshMode.LOGOUT) return logout(now); // once: cikista komut yok
            context.attack.stopAttack();
            if (context.attack.hasTarget()) context.attack.setTarget(null);
            double total = context.hero.getHealth().getHp() + context.hero.getHealth().getShield();
            if (lastTotal >= 0 && total < lastTotal - 1) {
                calmSince = now;
                lastDrop = now;
            }
            lastTotal = total;

            double here = danger(Location.of(context.hero));
            if (here < SAFE_DIST) calmSince = now;
            // 60 sn dolsa da can dusuyorsa (ates altinda) yenilenmez.
            if (!allowed && (now - calmSince >= CALM_MS || (now - start > ESCAPE_TIMEOUT_MS && now - lastDrop > CALM_MS))) {
                allowed = true;
                allowedAt = now;
                Log.i("YENILEME", (now - start > ESCAPE_TIMEOUT_MS ? "60 sn doldu" : "etraf temiz")
                        + ", en yakin tehlike " + (here == Double.MAX_VALUE ? "yok" : String.valueOf((int) here))
                        + ", yenileme izni: " + Log.loc(context.hero) + " (" + (now - start) / 1000 + " sn)");
            }
            if (allowed) {
                context.stop();
                forceRefresh();
                return true;
            }
            // Tehlikeli nokta en erken 1,5 sn'de bir degisir (her tik yeni nokta secilmez).
            if (target == null || context.hero.distanceTo(target) < 100 || (danger(target) < SAFE_DIST && now - targetAt >= 1500)) {
                Location best = context.safePoint(STEP, context.nearestDanger(context.hero));
                target = best;
                targetAt = now;
                Log.every("refreshmove", 3000, "YENILEME", "en yakin tehlike " + (int) Math.min(here, 99999) + ", yeni nokta " + Log.loc(target));
            }
            context.hero.setRoamMode();
            context.moveTo(target);
            return true;
        }
    }
}