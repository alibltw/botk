package com.botplus.pvp;

import com.botplus.core.BotPlusConfig;
import com.botplus.core.Context;
import com.botplus.core.Log;
import com.botplus.core.PlusModule;
import com.botplus.core.Stats;

import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Location;

/**
 * PvP alt eklentisinin mantigi: "Dusman Saldirinca -> Ne Yapilsin = Saldir" iken uygun dusman secilir ve saldiri isi
 * (PvpJob) baslatilir. Push Korumasi (push alt eklentisi) her hedef icin once kontrol edilir.
 */
public final class PvpLogic {

    /** Oyuncuya bu mesafeden ates edilir, etrafinda bu yaricapta donulur. */
    private static final double PVP_RANGE = 550;
    /** Kalabalik: bu mesafedeki dusman sayisi. */
    private static final double CROWD_DIST = 2000;

    private final Context context;
    private PvpJob pvp;
    /** Geri cekildiyse bu zamana kadar yeni saldiri baslatilmaz (kacis isler). */
    private long pvpBlockedUntil;

    public PvpLogic(Context context) {
        this.context = context;
        context.pvpTarget = () -> pvp != null && !pvp.done ? pvp.target : null;
    }

    /** Saldiri isi suruyor mu. */
    public boolean busy() {
        return pvp != null && !pvp.done;
    }

    /** Saldiri disaridan birakilir (Hizli Can Kaybi): 30 sn yeni saldiri yok. */
    public void abort(String why) {
        if (pvp == null || pvp.done) return;
        pvp.done = true;
        pvpBlockedUntil = Context.now() + 30_000;
        Log.i("SALDIR", pvp.name + " birakildi: " + why);
    }

    /** Uygun dusman varsa saldiri isi, yoksa null. */
    public PlusModule.Job check(BotPlusConfig.PvpSettings s) {
        Player target = pvpCandidate(s);
        if (target == null) return null;
        Stats.count("pvp");
        Log.i("SALDIR", Log.ship(target) + " hedef alindi (" + (int) target.distanceTo(context.hero) + " uzakta) | kime: "
                + s.WHO + " | gemi can " + Log.pct(context.heroHp()));
        pvp = new PvpJob(target);
        return pvp;
    }

    /** Saldir (PvP): "Kime Saldirilsin"a, push korumasina ve kosullara uyan en yakin oyuncu (yoksa null). */
    Player pvpCandidate(BotPlusConfig.PvpSettings s) {
        long now = Context.now();
        if (s == null || now < pvpBlockedUntil) return null;
        if (context.heroHp() < s.RETREAT_HP / 100.0) {
            Log.every("pvphp", 15_000, "SALDIR", "saldirilmiyor: can " + Log.pct(context.heroHp()) + " < %" + s.RETREAT_HP);
            return null;
        }
        if (s.CROWD && enemiesNear() >= 2) {
            Log.every("pvpcrowd", 15_000, "SALDIR", "saldirilmiyor: " + enemiesNear() + " dusman yakinda (Kalabalikta Kac)");
            return null;
        }
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : context.ents.getPlayers()) {
            if (!context.isEnemyPlayer(p) || context.petPlayer(p)) continue;
            // Kimlere Saldir: secili olmayan sirkete saldirilmaz; kendi sirketine sadece son 5 sn icinde bize ates
            // ettiyse (bayragi dusunce ates seref kaybettirir).
            if (!context.mayFire(p, s.FIRMS)) {
                Log.every("pvpfirm" + p.getId(), 30_000, "SALDIR", Log.ship(p) + " saldirilmiyor: "
                        + context.firmInfo(p) + " (Kimlere Saldir)");
                continue;
            }
            boolean attacking = p.isAttacking(context.hero);
            boolean tagged = tagged(p, s.TAG);
            boolean ok;
            switch (s.WHO) {
                case LIST_ATTACKERS: ok = attacking && tagged; break;
                case LIST: ok = tagged; break;
                case ALL: ok = true; break;
                default: ok = attacking;
            }
            if (!ok) continue;
            double d = p.distanceTo(context.hero);
            if (!attacking && d > s.CHASE_DIST) continue;
            String block = context.push.blockReason(p);
            if (block != null) {
                Log.every("pvppush" + p.getId(), 60_000, "PUSH", Log.ship(p) + " saldirilmiyor: " + block);
                continue;
            }
            if (s.CONDITIONAL) {
                double theirs;
                try {
                    theirs = p.getHealth().hpPercent();
                } catch (RuntimeException e) {
                    theirs = -1;
                }
                if (theirs > context.heroHp()) {
                    Log.every("pvpcond" + p.getId(), 15_000, "SALDIR", Log.ship(p) + " saldirilmiyor: cani " + Log.pct(theirs)
                            + " > bizim " + Log.pct(context.heroHp()) + " (Sartli Saldiri)");
                    continue;
                }
            }
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private int enemiesNear() {
        int n = 0;
        for (Player p : context.ents.getPlayers())
            if (context.isEnemyPlayer(p) && p.distanceTo(context.hero) < CROWD_DIST) n++;
        return n;
    }

    /** DarkBot oyuncu listesinde bu etiket var mi. */
    private boolean tagged(Player p, eu.darkbot.api.config.types.PlayerTag tag) {
        if (tag == null || context.configApiRef == null) return false;
        try {
            return tag.hasTag(context.configApiRef.getLegacy().getPlayerInfo(p.getId()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Saldir (PvP): oyuncuya kilitlenip PVP_RANGE'de etrafinda donerek ates edilir. Can "Geri Cekilme Cani"nin
     * altina inerse ya da kalabalikta birakilir (30 sn yeni saldiri yok, kacis isler). Kovalama sure / mesafe sinirli.
     * Oyuncu dusuk canla gozden kaybolursa oldurulmus sayilir (Push Korumasi'na yazilir).
     */
    public final class PvpJob implements PlusModule.Job {
        private final int id;
        private final String name;
        volatile Player target;
        private final long start = Context.now();
        private long lastSeenAt = Context.now(), lastInRangeAt;
        private double lastHp = 1;
        private Location lastLoc;
        /** Kendi sirketinden: kovalanmaz, 5 sn ates etmezse birakilir. */
        private final boolean own;
        private int dir = 1;
        boolean done;

        PvpJob(Player p) {
            this.id = p.getId();
            this.name = Log.name(p);
            this.target = p;
            this.own = context.ownFirm(p);
        }

        @Override
        public String name() {
            return "SALDIR";
        }

        @Override
        public int priority() {
            return PlusModule.P_ESCAPE;
        }

        @Override
        public boolean safety() {
            return false;
        }

        @Override
        public String status() {
            return "düşmana saldırılıyor (" + name + ")";
        }

        private Player find() {
            for (Player p : context.ents.getPlayers()) if (p.getId() == id && p.isValid()) return p;
            return null;
        }

        private void retreat(String why) {
            pvpBlockedUntil = Context.now() + 30_000;
            Log.i("SALDIR", name + " birakildi, geri cekiliniyor: " + why + " | gemi can " + Log.pct(context.heroHp()));
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            BotPlusConfig.PvpSettings s = context.config.ON_ENEMY.PVP;
            Player p = find();
            target = p;
            if (p != null) {
                lastSeenAt = now;
                lastLoc = Location.of(p.getX(), p.getY());
                try {
                    lastHp = p.getHealth().hpPercent();
                } catch (RuntimeException ignored) {
                }
            } else {
                if (lastHp <= 0.15 && now - lastSeenAt < 3000) {
                    Stats.count("pvp oldurme");
                    Log.i("SALDIR", name + " olduruldu (son can " + Log.pct(lastHp) + ") | " + (now - start) / 1000 + " sn");
                    context.push.killed(name);
                    // Oldurulen oyuncu unutulur: olum yerindeki NPC'ler "hatirlanan dusman yakininda" diye yok sayilmasin.
                    context.forgetPlayer(id, name);
                    return false;
                }
                if (!own && s.CHASE && lastLoc != null && now - lastSeenAt < s.CHASE_SEC * 1000L) {
                    context.moveTo(lastLoc);
                    return true;
                }
                Log.i("SALDIR", name + " gorus disina cikti, saldiri bitti");
                return false;
            }
            if (!context.mayFire(p, s.FIRMS)) {
                Log.i("SALDIR", name + " birakildi: " + (own ? "kendi sirketi " + Context.OWN_FIRM_FIRE_MS / 1000
                        + " sn'dir ates etmiyor (seref kaybi olmasin)" : "Kimlere Saldir'da secili degil"));
                return false;
            }
            if (context.heroHp() < s.RETREAT_HP / 100.0) {
                retreat("can < %" + s.RETREAT_HP);
                return false;
            }
            if (context.rapidLossNow.getAsBoolean()) {
                retreat("hizli can kaybi (2 sn'de can sert dustu)");
                return false;
            }
            if (s.CROWD && enemiesNear() >= 2) {
                retreat(enemiesNear() + " dusman yakinda");
                return false;
            }
            if (!s.OFF_MAP && !context.onWorkingMap()) {
                Log.i("SALDIR", name + " calisma haritasi disinda kovalanmiyor");
                return false;
            }
            double d = p.distanceTo(context.hero);
            if (d <= PVP_RANGE + 50) lastInRangeAt = now;
            long since = lastInRangeAt > 0 ? now - lastInRangeAt : now - start;
            if (d > s.CHASE_DIST || ((!s.CHASE || own) && d > PVP_RANGE * 1.5) || since > s.CHASE_SEC * 1000L) {
                pvpBlockedUntil = now + 10_000;
                Log.i("SALDIR", name + " kovalama birakildi (" + (int) d + " uzakta, " + since / 1000 + " sn menzil disi)");
                return false;
            }
            if (!java.util.Objects.equals(context.attack.getTarget(), p)) context.attack.setTarget(p);
            context.attack.tryLockAndAttack();
            Location c = Location.of(p.getX(), p.getY());
            double a = c.angleTo(context.hero) + dir * 0.35;
            Location m = Location.of(c, a, d > PVP_RANGE * 1.2 ? PVP_RANGE * 0.8 : PVP_RANGE * 0.9);
            if (!context.move.canMove(m)) {
                dir = -dir;
                m = Location.of(c, c.angleTo(context.hero) + dir * 0.35, PVP_RANGE * 0.9);
            }
            context.moveTo(m);
            if (Log.due("pvpstat", 3000))
                Log.i("SALDIR", Log.ship(p) + " | uzaklik " + (int) d + " | dusman can " + Log.pct(lastHp) + " | gemi can "
                        + Log.pct(context.heroHp()));
            return true;
        }

        @Override
        public void end(String why) {
            done = true;
            target = null;
            // Kilit Birakma: ates CTRL ile kontrollu kesilir, kilit yakindaki NPC'ye tasinir (cephaneye dokunulmaz).
            context.unlock.request("saldiri bitti: " + name);
        }
    }

}