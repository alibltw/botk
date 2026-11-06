package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Box;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Rakipten Uzak Topla ve Kutuyu Bekle.
 * Rakip: 3,5 sn boyunca seninle ayni yone giden ya da kutulara yonelen gemi.
 * Sadece herkesin alabilecegi kutular icin; gemiye ozel kutular (kargo, prizmatium) haric.
 */
public final class Boxes {

    private static final long RIVAL_ON_MS = 3500;
    private static final long RIVAL_OFF_MS = 3500;
    private static final int RIVAL_SCAN = 4000;
    private static final int SAME_DIR_DIST = 1500;
    private static final double SAME_DIR_ANGLE = Math.toRadians(30);
    private static final int RIVAL_AREA = 800;
    private static final double RIVAL_CONE = Math.toRadians(35);
    private static final int RIVAL_CONE_DIST = 2500;
    private static final int RACE_DEST = 50;
    private static final int AWAY_STEP = 1500;
    private static final double DIED_HP = 0.25;
    /** Ters yon noktasi bu sure tutulur (her tik yeni rastgele nokta secilmez). */
    private static final long AWAY_KEEP_MS = 2000;

    private static final class Rival {
        public long since, last;
        public boolean on;
    }

    private final Context context;
    private final Map<Integer, Rival> rivals = new HashMap<>();

    // Kutuyu Bekle: son vurulan NPC
    private int lastNpc = -1;
    private double lastNpcHp = 1;
    private Location lastNpcLoc;
    private long lastShotAt;

    // Rakipten uzaklasma noktasi (2 sn tutulur)
    private Location awayPt;
    private long awayAt;

    public Boxes(Context context) {
        this.context = context;
    }

    public void reset() {
        rivals.clear();
        lastNpc = -1;
        committed = null;
        awayPt = null;
    }

    /** Herkesin alabilecegi kutu mu (kargo ve gemiye ozel kutular haric). */
    public static boolean isPublic(Box b) {
        String t = b.getTypeName();
        if (t == null) return true;
        String u = t.toUpperCase(java.util.Locale.ROOT);
        return !u.equals("FROM_SHIP") && !u.contains("CARGO") && !u.contains("PRISM");
    }

    // ---------------------------------------------------------------- Rakip

    // Kutu-NPC arasinda gidip gelme: modul kutuya yonelip toplamadan geri donerse kutuya devam edilir.
    private static final long COMMIT_MS = 4000;
    private static final int COMMIT_DIST = 1500;
    private Box committed;
    private long committedAt;

    private void antiFlip(long now) {
        // DarkBot NPC'ye saldiriyorken gemi kutuya geri cekilmez.
        if (context.attack.hasTarget()) {
            committed = null;
            return;
        }
        Location dest = context.move.getDestination();
        if (dest == null) return;
        Box going = null;
        for (Box b : context.ents.getBoxes())
            if (b.isValid() && !b.isCollected() && b.distanceTo(dest) < 30) going = b;
        if (going != null) {
            if (going != committed) {
                committed = going;
                committedAt = now;
            }
            return;
        }
        if (committed == null) return;
        if (!committed.isValid() || committed.isCollected() || now - committedAt > COMMIT_MS
                || context.hero.distanceTo(committed) > COMMIT_DIST) {
            committed = null;
            return;
        }
        context.moveTo(committed);
        Log.every("flip", 2000, "KUTU", "modul " + committed.getTypeName() + " " + Log.loc(committed)
                + " kutusundan geri donuyordu, kutuya devam ediliyor (" + (int) context.hero.distanceTo(committed) + ")");
    }

    public void tick() {
        long t0 = Context.now();
        // DarkBot kaciyorken (portala gidiyor) kutuya geri cekilmez.
        if (context.config.SORT == BotPlusConfig.Sort.MODULE && !context.mainEscaping()) antiFlip(t0);
        else committed = null;
        if (!context.config.PLUS.RIVAL()) {
            rivals.clear();
            return;
        }
        long now = Context.now();
        trackRivals(now);
        if (context.attack.hasTarget() || context.mainEscaping()) return;

        Location dest = context.move.getDestination();
        if (dest == null) return;
        Box going = null;
        for (Box b : context.ents.getBoxes())
            if (b.isValid() && b.distanceTo(dest) < 30) going = b;
        if (going == null || !isPublic(going) || context.hero.distanceTo(going) < 250) return;

        String why = skipReason(going);
        if (why == null) return;

        Box alt = null;
        double best = Double.MAX_VALUE;
        for (Box b : context.ents.getBoxes()) {
            if (b == going || !b.isValid() || b.isCollected() || b.getInfo() == null || !b.getInfo().shouldCollect())
                continue;
            if (context.move.getClosestDistance(b) > 200) continue;
            if (isPublic(b) && skipReason(b) != null) continue;
            double d = context.hero.distanceTo(b);
            if (d < best) {
                best = d;
                alt = b;
            }
        }
        if (alt != null) {
            context.moveTo(alt);
            Log.every("rivalbox", 2000, "RAKIP", going.getTypeName() + " " + Log.loc(going) + " atlandi (" + why
                    + "), " + alt.getTypeName() + " " + Log.loc(alt) + " kutusuna gidiliyor");
            return;
        }
        Player away = closestRival();
        Locatable from = away != null ? away : going;
        if (awayPt == null || now - awayAt > AWAY_KEEP_MS || context.hero.distanceTo(awayPt) < 150) {
            awayPt = context.ringPoint(context.hero, AWAY_STEP, context.hero.angleTo(from) + Math.PI, Math.PI / 4);
            awayAt = now;
        }
        Location p = awayPt;
        context.moveTo(p);
        Log.every("rivalaway", 2000, "RAKIP", going.getTypeName() + " " + Log.loc(going) + " atlandi (" + why
                + "), baska kutu yok, ters yone " + Log.loc(p));
    }

    private void trackRivals(long now) {
        Optional<Location> heroDest = context.hero.getDestination();
        for (Player p : context.ents.getPlayers()) {
            if (p.getId() == context.hero.getId() || !p.isValid() || context.hero.distanceTo(p) > RIVAL_SCAN) continue;
            boolean competing = competing(p, heroDest);
            Rival r = rivals.computeIfAbsent(p.getId(), id -> new Rival());
            if (competing) {
                if (r.since == 0) r.since = now;
                r.last = now;
                if (!r.on && now - r.since >= RIVAL_ON_MS) {
                    r.on = true;
                    Log.i("RAKIP", "RAKIP: " + Log.ship(p) + " (" + (now - r.since) / 1000.0 + " sn ayni yone / kutulara)");
                }
            } else if (now - r.last >= RIVAL_OFF_MS) {
                r.since = 0;
                if (r.on) {
                    r.on = false;
                    Log.i("RAKIP", "rakiplikten cikti: " + Log.ship(p));
                }
            }
        }
        Iterator<Map.Entry<Integer, Rival>> it = rivals.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue().last > 30_000) it.remove();
    }

    private boolean competing(Player p, Optional<Location> heroDest) {
        Optional<Location> pd = p.getDestination();
        if (!pd.isPresent()) return false;
        Location d = pd.get();
        for (Box b : context.ents.getBoxes())
            if (b.isValid() && isPublic(b) && b.distanceTo(d) < 150) return true;
        if (!heroDest.isPresent() || context.hero.distanceTo(p) > SAME_DIR_DIST) return false;
        double a1 = Math.atan2(heroDest.get().getY() - context.hero.getY(), heroDest.get().getX() - context.hero.getX());
        double a2 = Math.atan2(d.getY() - p.getY(), d.getX() - p.getX());
        return angleDiff(a1, a2) < SAME_DIR_ANGLE;
    }

    /** @return kutu atlanacaksa nedeni, yoksa null */
    private String skipReason(Box b) {
        long heroTime = context.hero.timeTo(b);
        for (Player p : context.ents.getPlayers()) {
            if (p.getId() == context.hero.getId() || !p.isValid()) continue;
            Optional<Location> pd = p.getDestination();
            if (!pd.isPresent()) continue;
            if (pd.get().distanceTo(b) < RACE_DEST && p.timeTo(b) < heroTime)
                return Log.ship(p) + " once varacak";
            Rival r = rivals.get(p.getId());
            if (r == null || !r.on) continue;
            if (pd.get().distanceTo(b) < RIVAL_AREA) return "rakip " + Log.name(p) + " hedefine yakin";
            double heading = Math.atan2(pd.get().getY() - p.getY(), pd.get().getX() - p.getX());
            double toBox = Math.atan2(b.getY() - p.getY(), b.getX() - p.getX());
            if (p.distanceTo(b) < RIVAL_CONE_DIST && angleDiff(heading, toBox) < RIVAL_CONE)
                return "rakip " + Log.name(p) + " yonunde";
        }
        return null;
    }

    private Player closestRival() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : context.ents.getPlayers()) {
            Rival r = rivals.get(p.getId());
            if (r == null || !r.on || !p.isValid()) continue;
            double d = context.hero.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return d > Math.PI ? 2 * Math.PI - d : d;
    }

    // ---------------------------------------------------------------- Kutuyu Bekle

    /** Vurdugumuz NPC olduyse olum yeri doner. */
    public Location died() {
        Npc cur = context.targetNpc();
        long now = Context.now();
        if (cur != null && context.shooting(cur)) {
            // Hedef ayni tikte yenisine gectiyse (oyun / DarkBot kilidi aninda degistirdi) eskisinin olumu kacmasin.
            Location prevDied = null;
            if (lastNpc >= 0 && lastNpc != cur.getId() && context.findNpc(lastNpc) == null) prevDied = deathOf();
            lastNpc = cur.getId();
            lastNpcHp = Context.hp(cur);
            lastNpcLoc = Location.of(cur);
            lastShotAt = now;
            return prevDied;
        }
        if (lastNpc < 0) return null;
        Npc last = context.findNpc(lastNpc);
        if (last != null) {
            // Hala yasiyor: ates anlik kesildi (kilit yenilendi). 3 sn ates edilmezse takip birakilir.
            lastNpcHp = Context.hp(last);
            lastNpcLoc = Location.of(last);
            if (now - lastShotAt > 3000) lastNpc = -1;
            return null;
        }
        lastNpc = -1;
        return deathOf();
    }

    /** Takip edilen NPC listeden dustu: son cani dusuk ve gemiye yakinsa olum yeri, degilse null. */
    private Location deathOf() {
        // Gorusten cikan (uzaklasan) NPC olu sayilmaz: son cani dusuk ve gemiye yakin olmali.
        if (lastNpcHp > DIED_HP || lastNpcLoc == null || context.hero.distanceTo(lastNpcLoc) > 1500) return null;
        // Combat Plus acikken "npc" sayaci CombatPlus'in "kesim" sayacindan gelir (BotPlus), burada sayilmaz.
        if (!context.config.COMBAT.ENABLED) Stats.count("npc");
        return lastNpcLoc;
    }

    /** Toplama yapan modul mu (adinda "Collect" gecen). */
    public boolean collectModule() {
        return context.collectModule();
    }
}