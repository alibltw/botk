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

    /**
     * Kendi Sirketi = Karsilik Ver (2.6.0): bize ates eden kendi sirketinden oyuncuya karsilik. PvP Plus kapali olsa da
     * calisir; kovalanmaz, oyuncu 5 sn ates etmezse biter, can Kacis Cani'na inerse birakilir.
     */
    public PlusModule.Job counter(Player p) {
        long now = Context.now();
        if (p == null || now < pvpBlockedUntil) return null;
        String block = context.push.blockReason(p);
        if (block != null) {
            Log.every("pvppush" + p.getId(), 60_000, "PUSH", Log.ship(p) + " karsilik verilmiyor: " + block);
            return null;
        }
        Stats.count("karsilik");
        Log.i("SALDIR", Log.ship(p) + " (kendi sirketi) ates ediyor: karsilik veriliyor | gemi can " + Log.pct(context.heroHp()));
        pvp = new PvpJob(p);
        pvp.counter = true;
        return pvp;
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
        /** Kendi Sirketi = Karsilik Ver isi (PvP Plus ayarlari olmadan da calisir). */
        boolean counter;
        /** Gucluyse Geri Cekil: {an, bizim can, dusman can} ornekleri (son 4 sn). */
        private final java.util.ArrayDeque<double[]> duel = new java.util.ArrayDeque<>();
        /** Mayin Tuzagi: kosma asamasi bitisi, son mayin. */
        private long trapRunUntil, trapNextAt, mineAt;
        private boolean kiteLogged;
        /** Dusmanin cani okunabiliyor mu (okunamazsa hpPercent 1 doner: Gucluyse Geri Cekil karar vermez). */
        private boolean hpKnown;

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
            if (s == null) s = new BotPlusConfig.PvpSettings(); // Karsilik Ver: PvP Plus kapali olabilir
            Player p = find();
            target = p;
            if (p != null) {
                lastSeenAt = now;
                lastLoc = Location.of(p.getX(), p.getY());
                try {
                    lastHp = p.getHealth().hpPercent();
                    hpKnown = p.getHealth().getMaxHp() > 0;
                } catch (RuntimeException ignored) {
                }
            } else {
                // 2.6.0: dusuk canla gozden kaybolmak tek basina oldurme sayilmaz (portaldan atlayan / gizlenen de
                // kaybolur, eskiden hatirlanan dusman listesinden siliniyordu): 3 sn icinde Seref Puani mesaji gelmeli.
                boolean confirmed = context.honorMsgAt >= lastSeenAt - 300;
                if (lastHp <= 0.15 && now - lastSeenAt < 3000 && !confirmed) {
                    Log.dEvery("pvpconfirm" + id, 3000, "SALDIR", name + " gozden kayboldu (son can " + Log.pct(lastHp)
                            + "), oldurme dogrulamasi bekleniyor");
                    return true;
                }
                if (lastHp <= 0.15 && confirmed) {
                    Stats.count("pvp oldurme");
                    Log.i("SALDIR", name + " olduruldu (son can " + Log.pct(lastHp) + ") | " + (now - start) / 1000 + " sn");
                    context.push.killed(name);
                    // Oldurulen oyuncu unutulur: olum yerindeki NPC'ler "hatirlanan dusman yakininda" diye yok sayilmasin.
                    context.forgetPlayer(id, name);
                    return false;
                }
                if (!own && chaseOk(s) && lastLoc != null && now - lastSeenAt < s.CHASE_SEC * 1000L) {
                    if (s.OPTIONS.contains(BotPlusConfig.PvpOption.CHASE_LOW)) context.useSpeedNow("Cani Azsa Kovala: " + name);
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
            // Karsilik Ver: Kacis Cani'nda birakilir (kacis portala goturur).
            if (counter && context.heroHp() <= context.config.ON_ENEMY.HP / 100.0) {
                retreat("can <= %" + context.config.ON_ENEMY.HP + " (Kacis Cani)");
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
            if (strongerFoe(s, now)) {
                retreat("dusman daha guclu (Gucluyse Geri Cekil)");
                return false;
            }
            if (d > s.CHASE_DIST || ((!chaseOk(s) || own) && d > PVP_RANGE * 1.5) || since > s.CHASE_SEC * 1000L) {
                pvpBlockedUntil = now + 10_000;
                Log.i("SALDIR", name + " kovalama birakildi (" + (int) d + " uzakta, " + since / 1000 + " sn menzil disi)");
                return false;
            }
            if (!java.util.Objects.equals(context.attack.getTarget(), p)) context.attack.setTarget(p);
            context.attack.tryLockAndAttack();
            Location c = Location.of(p.getX(), p.getY());
            if (d > PVP_RANGE * 1.2 && chaseOk(s) && s.OPTIONS.contains(BotPlusConfig.PvpOption.CHASE_LOW))
                context.useSpeedNow("Cani Azsa Kovala: " + name);
            Location m = styleMove(s, p, c, d, now);
            context.moveTo(m);
            if (Log.due("pvpstat", 3000))
                Log.i("SALDIR", Log.ship(p) + " | uzaklik " + (int) d + " | dusman can " + Log.pct(lastHp) + " | gemi can "
                        + Log.pct(context.heroHp()));
            return true;
        }

        /** Kovalama izni: Cani Azsa Kovala aciksa sadece dusmanin cani %25 ve altindayken, degilse Kovala ayari. */
        private boolean chaseOk(BotPlusConfig.PvpSettings s) {
            if (s.OPTIONS.contains(BotPlusConfig.PvpOption.CHASE_LOW)) return lastHp <= 0.25;
            return s.CHASE;
        }

        /**
         * Gucluyse Geri Cekil: en az 3 sn'lik veriyle kim once olur hesaplanir (bizim can / bizim kayip hizi ile
         * dusmanin cani / dusmanin kayip hizi). Biz once oluyorsak ve dusman %15'in ustundeyse true.
         */
        private boolean strongerFoe(BotPlusConfig.PvpSettings s, long now) {
            if (!s.OPTIONS.contains(BotPlusConfig.PvpOption.RETREAT_STRONG) || !hpKnown) {
                duel.clear();
                return false;
            }
            double ours = context.heroHp();
            duel.addLast(new double[]{now, ours, lastHp});
            while (!duel.isEmpty() && now - (long) duel.peekFirst()[0] > 4000) duel.pollFirst();
            double[] f = duel.peekFirst();
            double span = (now - (long) f[0]) / 1000.0;
            if (span < 3 || lastHp <= 0.15) return false;
            double ourRate = (f[1] - ours) / span, theirRate = (f[2] - lastHp) / span;
            if (ourRate <= 0.01) return false;              // biz hasar almiyoruz
            double ourTtd = ours / ourRate;
            double theirTtd = theirRate <= 0.001 ? Double.MAX_VALUE : lastHp / theirRate;
            if (ourTtd >= theirTtd) return false;
            Log.i("SALDIR", name + ": biz " + String.format(java.util.Locale.ROOT, "%.1f", ourTtd) + " sn'de, o "
                    + (theirTtd == Double.MAX_VALUE ? "hic" : String.format(java.util.Locale.ROOT, "%.1f sn'de", theirTtd))
                    + " olur (biz %" + Math.round(ourRate * 100) + "/sn, o %" + Math.round(theirRate * 100) + "/sn kaybediyor)");
            return true;
        }

        /** PvP Tarzi'na gore hareket noktasi. */
        private Location styleMove(BotPlusConfig.PvpSettings s, Player p, Location c, double d, long now) {
            BotPlusConfig.PvpStyle st = s.STYLE == null ? BotPlusConfig.PvpStyle.ORBIT : s.STYLE;
            boolean faster;
            try {
                faster = context.hero.getSpeed() > p.getSpeed() * 1.05;
            } catch (RuntimeException e) {
                faster = false;
            }
            if ((st == BotPlusConfig.PvpStyle.KITE || st == BotPlusConfig.PvpStyle.MINE_TRAP) && !faster) {
                if (!kiteLogged) {
                    kiteLogged = true;
                    Log.i("SALDIR", name + " bizden hizli ya da esit (" + p.getSpeed() + " / " + context.hero.getSpeed()
                            + "): " + st + " yerine yorunge");
                }
                st = BotPlusConfig.PvpStyle.ORBIT;
            }
            if (st == BotPlusConfig.PvpStyle.MINE_TRAP) {
                // Mayin Tuzagi: 2,5 sn duz uzaklasilir (dusman pesimizden gelir), basta arkaya yavaslatici mayin,
                // sonra 3 sn yorunge (dusman mayinin ustunden gecer). Mayin yoksa / beklemedeyse Kacarak Vur gibi.
                if (now >= trapNextAt) {
                    trapRunUntil = now + 2500;
                    trapNextAt = now + 5500;
                }
                if (now < trapRunUntil) {
                    if (now - mineAt > 2500 && d < 900) {
                        try {
                            eu.darkbot.api.game.items.ItemUseResult r = context.items.useItem(
                                    eu.darkbot.api.game.items.SelectableItem.Mine.SLM_01, 1000);
                            if (r == eu.darkbot.api.game.items.ItemUseResult.SUCCESS) {
                                mineAt = now;
                                Stats.count("mayin tuzagi");
                                Log.i("SALDIR", name + ": mayin tuzagi, arkaya yavaslatici mayin birakildi (" + (int) d + ")");
                            }
                        } catch (RuntimeException ignored) {
                        }
                    }
                    Location run = Location.of(c, c.angleTo(context.hero), Math.max(d + 400, PVP_RANGE));
                    if (context.move.canMove(run)) return run;
                }
                st = BotPlusConfig.PvpStyle.ORBIT;
            }
            if (st == BotPlusConfig.PvpStyle.KITE) {
                // Kacarak Vur: menzil sinirinda (550) kalinir; dusman yaklasirsa disari ve yana kayilir, uzaklasirsa
                // yaklasilir. Biz hizli oldugumuz icin dusman kovalarken hasar yer ama yaklasamaz.
                double r = d < PVP_RANGE * 0.9 ? PVP_RANGE * 1.05 : PVP_RANGE * 0.95;
                Location m = Location.of(c, c.angleTo(context.hero) + dir * 0.25, r);
                if (!context.move.canMove(m)) {
                    dir = -dir;
                    m = Location.of(c, c.angleTo(context.hero) + dir * 0.25, r);
                }
                return m;
            }
            // Yorunge (eski davranis).
            double a = c.angleTo(context.hero) + dir * 0.35;
            Location m = Location.of(c, a, d > PVP_RANGE * 1.2 ? PVP_RANGE * 0.8 : PVP_RANGE * 0.9);
            if (!context.move.canMove(m)) {
                dir = -dir;
                m = Location.of(c, c.angleTo(context.hero) + dir * 0.35, PVP_RANGE * 0.9);
            }
            return m;
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