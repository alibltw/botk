package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Box;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.other.Location;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Kisa Dolasma: hedef yokken bot haritanin uzak bir yerine tiklarsa nokta en fazla 2000 uzaga cekilir.
 * Yon korunur, yani gemi yine ayni tarafa gider ama kisa adimlarla.
 */
public final class Roam {

    private static final int MAX_STEP = 2000;
    private static final int MIN_STEP = 1000;
    private static final double SPREAD = Math.PI / 4;

    private static final long STUCK_MS = 15_000;
    private static final int STUCK_MOVE = 50;
    private static final int STUCK_ENEMY = 2000;
    private static final int STUCK_STEP = 1500;

    private final Context context;
    private Location lastSet;
    /** Kisaltilan asil uzak nokta: adima varinca DarkBot yeni rastgele nokta secse de buraya devam edilir. */
    private Location farDest;
    private Location stillAt;
    private long stillSince, lastCallAt;
    /** Can tamir baslama sinirinin altina indi, bitis canina kadar takilma cozulmez. */
    private boolean repairWait;

    public Roam(Context context) {
        this.context = context;
    }

    public void reset() {
        lastSet = null;
        farDest = null;
        stillAt = null;
    }

    /**
     * Takilmayi Coz: gemi 15 sn yerinden kipirdamiyorsa (ornegin portta bekliyor, ustte "NPC araniyor" yaziyor)
     * hareket etmeye zorlanir. Tamir icin bekleme (can tamir sinirinin altinda) ve yakinda dusman varken dokunulmaz.
     */
    public PlusModule.Job unstuck() {
        if (!context.config.PLUS.UNSTUCK()) {
            stillAt = null;
            return null;
        }
        updateRepairWait();
        long now = Context.now();
        // Arada cagrilmadiysa (tam kontrolde dovus surerken) olcum bastan baslar.
        boolean gap = now - lastCallAt > 2000;
        lastCallAt = now;
        if (gap || stillAt == null || context.hero.distanceTo(stillAt) > STUCK_MOVE || context.shooting(context.targetNpc())) {
            stillAt = Location.of(context.hero);
            stillSince = now;
            return null;
        }
        if (now - stillSince < STUCK_MS) return null;

        String why = null;
        if (context.mainEscaping() || context.safetyEscaping()) why = "kacis suruyor (portalda bekleme olabilir)";
        if (why == null && repairWait) why = "tamir bekleniyor (can " + Log.pct(context.heroHp()) + ")";
        for (Player p : context.ents.getPlayers())
            if (why == null && context.isEnemyPlayer(p)
                    && context.hero.distanceTo(p) < STUCK_ENEMY) why = "yakinda dusman " + Log.ship(p);
        for (Box b : context.ents.getBoxes())
            if (why == null && b.isValid() && context.hero.distanceTo(b) < 300) why = "kutu toplaniyor";
        if (why != null) {
            Log.every("stuckok", 30_000, "TAKILMA", "gemi " + (now - stillSince) / 1000 + " sn duruyor ama " + why);
            return null;
        }

        String status = context.bot.getModule() == null ? "-" : context.bot.getModule().getStatus();
        Location p = context.ringPoint(context.hero, STUCK_STEP, ThreadLocalRandom.current().nextDouble() * 2 * Math.PI, Math.PI);
        context.attack.setTarget(null);
        Log.i("TAKILMA", "gemi " + (now - stillSince) / 1000 + " sn " + Log.loc(context.hero) + " yerinde kaldi (modul: "
                + status + "), " + Log.loc(p) + " noktasina gitmeye zorlaniyor");
        stillAt = null;
        final long start = now;
        return new PlusModule.Job() {
            @Override
            public String name() {
                return "TAKILMA";
            }

            @Override
            public int priority() {
                return PlusModule.P_UNSTUCK;
            }

            @Override
            public String status() {
                return "takılma çözülüyor";
            }

            @Override
            public boolean tick() {
                if (Context.now() - start > 6000 || context.hero.distanceTo(p) < 100) return false;
                context.hero.setRoamMode();
                context.moveTo(p);
                return true;
            }

            @Override
            public void end(String why) {
                Log.i("TAKILMA", "gemi " + Log.loc(context.hero) + " noktasinda, ana modul devam ediyor");
            }
        };
    }

    /**
     * Tamir bekleniyor: BotPlus haritada tamir aciksa onun baslama / bitis cani; degilse DarkBot'un tamir alt sinirinin
     * altina inildiyse (DarkBot tamir ediyor) onun ust sinirina kadar. Normal oyunda takilma cozulebilir.
     */
    private void updateRepairWait() {
        try {
            double hp = context.heroHp();
            BotPlusConfig.Repair rc = context.config.REPAIR;
            if (context.config.ENABLED && rc.onMap()) {
                if (repairWait && hp >= Math.max(rc.START_HP, rc.END_HP) / 100.0) repairWait = false;
                if (hp < rc.START_HP / 100.0) repairWait = true;
            } else {
                double min = context.darkBotRepairMin();
                double max = context.repairRange.getValue().getMax();
                if (repairWait && hp >= max) repairWait = false;
                if (min >= 0 && hp < min) repairWait = true;
            }
        } catch (RuntimeException ignored) {
        }
    }

    public void tick() {
        if (!context.config.PLUS.SHORT_ROAM() || context.attack.hasTarget() || context.mainEscaping()) {
            farDest = null;
            return;
        }
        Location dest = context.move.getDestination();
        if (dest == null || context.move.isOutOfMap()) return;
        if (lastSet != null && dest.distanceTo(lastSet) < 5) return;

        // Adimimiza vardik, DarkBot yeni rastgele nokta secti: asil uzak nokta hala uzaksa ona devam edilir
        // (yoksa gemi her adimda baska yone donup ayni bolgede dolasir).
        boolean resume = farDest != null && lastSet != null && context.hero.distanceTo(lastSet) < 300
                && context.hero.distanceTo(farDest) > MAX_STEP + 200;
        double d = context.hero.distanceTo(dest);
        if (!resume && d <= MAX_STEP + 200) {
            farDest = null;
            return;
        }

        // Kacis (kacis ayari acik), portal, kutu ya da bulucu hedefi ise dokunulmaz.
        try {
            if (context.runMode.getValue() != null && context.inMode(context.runMode.getValue())) {
                farDest = null;
                return;
            }
        } catch (RuntimeException ignored) {
        }
        for (Portal p : context.ents.getPortals()) if (p.distanceTo(dest) < 300) {
            farDest = null;
            return;
        }
        for (Box b : context.ents.getBoxes()) if (b.distanceTo(dest) < 50) {
            farDest = null;
            return;
        }

        if (!resume) farDest = dest;
        Location goal = resume ? farDest : dest;
        double gd = context.hero.distanceTo(goal);
        double step = MIN_STEP + ThreadLocalRandom.current().nextDouble() * (MAX_STEP - MIN_STEP);
        Location p = context.ringPoint(context.hero, step, context.hero.angleTo(goal), SPREAD);
        context.moveTo(p);
        lastSet = p;
        Log.every("roam", 2000, "DOLASMA", (resume ? "adima varildi, asil uzak nokta " : "uzak nokta ") + Log.loc(goal)
                + " (" + (int) gd + ") yerine " + Log.loc(p) + " (" + (int) context.hero.distanceTo(p) + ")");
    }
}