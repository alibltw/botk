package com.botplus.core;

import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2.8.0 Dusman Radari: haritadaki oyunculari izler (dusman, tren, kalabalik). Kacin: bolgedeki NPC / kutu / sinyal
 * secilmez, yol bolgenin sagindan ya da solundan dolanilir (tersine donulmez). Kac: yon degistirilir (portal / kacis
 * yok); dusman ates edince "Dusman Saldirinca" devreye girer.
 * <ul>
 * <li>Dusman: dusman sirketten oyuncu ya da hatirlanan olduren (kendi sirketi olsa da). PET, grup ve klan sayilmaz.</li>
 * <li>Tren: birbirine "Tren Yakinlik Mesafesi" kadar yakin, 3 sn boyunca ayni yone (45 derece) giden en az "Tren En Az
 * Kisi" gemi; icinde en az 1 kendi sirketi ve en az 1 dusman sirket oyuncusu. Grup ve klan sayilmaz.</li>
 * <li>Kalabalik: birbirine 2000'den yakin en az 2 dusman.</li>
 * </ul>
 */
public final class Radar {

    /** Kalabalik: dusmanlar birbirine bu kadar yakin; kacinma da bu mesafeden. */
    public static final double CROWD_DIST = 2000;
    private static final long SAMPLE_MS = 250, KEEP_MS = 4000, HEADING_MS = 2500, SCAN_MS = 500;
    /** 3 sn'de en az bu kadar yol: hareket ediyor. */
    private static final double MOVING_MIN = 150;
    private static final double SAME_DIR = Math.toRadians(45);
    /** Hedef secimi: NPC / sinyal bolgenin bu kadar disinda olmali (gemi NPC'nin etrafinda donerken bolgeye girmesin). */
    private static final double NPC_MARGIN = 700;

    private final Context c;
    private final Map<Integer, ArrayDeque<double[]>> samples = new HashMap<>();
    private long sampleAt, scanAt;
    private final List<Player> enemies = new ArrayList<>();
    private final List<Train> trains = new ArrayList<>();
    private final List<List<Player>> crowds = new ArrayList<>();

    public Radar(Context c) {
        this.c = c;
    }

    /** Tren: uyeler, merkez, ortak yon (birim vektor). */
    public static final class Train {
        public final List<Player> members;
        public final double cx, cy, hx, hy;

        Train(List<Player> m, double hx, double hy) {
            this.members = m;
            double sx = 0, sy = 0;
            for (Player p : m) {
                sx += p.getX();
                sy += p.getY();
            }
            this.cx = sx / m.size();
            this.cy = sy / m.size();
            double l = Math.max(1e-6, Math.hypot(hx, hy));
            this.hx = hx / l;
            this.hy = hy / l;
        }

        /** Trenin l'ye en yakin uyesine uzaklik. */
        public double distanceTo(Locatable l) {
            double d = Double.MAX_VALUE;
            for (Player p : members) d = Math.min(d, p.distanceTo(l));
            return d;
        }

        public Player nearest(Locatable l) {
            Player best = null;
            double bd = Double.MAX_VALUE;
            for (Player p : members) {
                double d = p.distanceTo(l);
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
            return best;
        }

        /** Tren l'ye dogru gidiyor mu (ortak yon l'ye 60 dereceden yakin). */
        public boolean headingTo(Locatable l) {
            double dx = l.getX() - cx, dy = l.getY() - cy, d = Math.hypot(dx, dy);
            if (d < 1) return true;
            return (dx * hx + dy * hy) / d > 0.5;
        }

        public String info() {
            StringBuilder b = new StringBuilder();
            for (Player p : members) {
                if (b.length() > 0) b.append(", ");
                b.append(Log.name(p));
            }
            return members.size() + " gemi (" + b + ")";
        }
    }

    private BotPlusConfig.OnEnemy oe() {
        return c.config.ON_ENEMY;
    }

    private boolean on(BotPlusConfig.RadarOpt o) {
        BotPlusConfig.OnEnemy oe = oe();
        return oe != null && oe.radar(o);
    }

    /** Radarda bir sey acik mi. */
    private boolean any() {
        BotPlusConfig.OnEnemy oe = oe();
        return oe != null && oe.RADAR != null && !oe.RADAR.isEmpty();
    }

    /** Her tik: konum ornekleri ve (500 ms'de bir) dusman / tren / kalabalik listeleri. */
    public void tick() {
        if (!any()) {
            if (!samples.isEmpty()) samples.clear();
            enemies.clear();
            trains.clear();
            crowds.clear();
            return;
        }
        long now = Context.now();
        try {
            if (now - sampleAt >= SAMPLE_MS) {
                sampleAt = now;
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (Player p : c.ents.getPlayers()) {
                    if (!p.isValid()) continue;
                    seen.add(p.getId());
                    ArrayDeque<double[]> q = samples.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                    q.addLast(new double[]{now, p.getX(), p.getY()});
                    while (!q.isEmpty() && now - (long) q.peekFirst()[0] > KEEP_MS) q.pollFirst();
                }
                samples.keySet().retainAll(seen);
            }
            if (now - scanAt >= SCAN_MS) {
                scanAt = now;
                scan();
            }
        } catch (RuntimeException e) {
            Log.every("radarerr", 60_000, "RADAR", "radar okunamadi: " + e);
        }
    }

    /** 3 sn'deki yer degisimi (hareket etmiyorsa null). */
    private double[] heading(Player p) {
        ArrayDeque<double[]> q = samples.get(p.getId());
        if (q == null || q.size() < 2) return null;
        double[] last = q.peekLast(), first = null;
        for (double[] s : q)
            if (last[0] - s[0] >= HEADING_MS) first = s;
        if (first == null) return null;
        double dx = last[1] - first[1], dy = last[2] - first[2];
        return Math.hypot(dx, dy) < MOVING_MIN ? null : new double[]{dx, dy};
    }

    /** Sayilmayanlar: biz, PET, grup, klan. */
    private boolean skip(Player p) {
        return p == null || !p.isValid() || p.getId() == c.hero.getId() || c.petPlayer(p) || c.isGroupMember(p) || c.sameClan(p);
    }

    /** Dusman sirket oyuncusu mu (bilinmiyorsa oyunun "dusman" bilgisi). */
    private boolean enemyFirm(Player p) {
        Boolean f = c.firmKnown(p);
        if (f != null) return !f;
        return p.getEntityInfo() != null && p.getEntityInfo().isEnemy();
    }

    private boolean ownFirm(Player p) {
        return Boolean.TRUE.equals(c.firmKnown(p));
    }

    private void scan() {
        enemies.clear();
        trains.clear();
        crowds.clear();
        List<Player> movers = new ArrayList<>();
        List<double[]> heads = new ArrayList<>();
        for (Player p : c.ents.getPlayers()) {
            if (skip(p)) continue;
            boolean killer = c.isKiller(p) && !c.attackStays(Log.name(p));
            if (killer || enemyFirm(p)) enemies.add(p);
            double[] h = heading(p);
            if (h != null) {
                movers.add(p);
                heads.add(h);
            }
        }
        // Tren: birbirine yakin ve ayni yone giden gemiler (birlesim kumesi).
        BotPlusConfig.OnEnemy oe = oe();
        if ((on(BotPlusConfig.RadarOpt.TRAIN_FLEE) || on(BotPlusConfig.RadarOpt.TRAIN_AVOID)) && movers.size() >= oe.TRAIN_SIZE) {
            int n = movers.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    if (movers.get(i).distanceTo(movers.get(j)) > oe.TRAIN_NEAR) continue;
                    if (angle(heads.get(i), heads.get(j)) > SAME_DIR) continue;
                    parent[find(parent, i)] = find(parent, j);
                }
            Map<Integer, List<Integer>> groups = new HashMap<>();
            for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
            for (List<Integer> g : groups.values()) {
                if (g.size() < oe.TRAIN_SIZE) continue;
                int own = 0, enemy = 0;
                double hx = 0, hy = 0;
                List<Player> m = new ArrayList<>();
                for (int i : g) {
                    Player p = movers.get(i);
                    m.add(p);
                    if (ownFirm(p)) own++;
                    else if (enemyFirm(p)) enemy++;
                    double[] h = heads.get(i);
                    double l = Math.max(1e-6, Math.hypot(h[0], h[1]));
                    hx += h[0] / l;
                    hy += h[1] / l;
                }
                if (own < 1 || enemy < 1) continue;
                Train t = new Train(m, hx, hy);
                trains.add(t);
                Log.every("radartrain", 20_000, "RADAR", "tren goruldu: " + t.info() + " " + (int) t.distanceTo(c.hero) + " uzakta");
            }
        }
        // Kalabalik: birbirine 2000'den yakin en az 2 dusman.
        if (on(BotPlusConfig.RadarOpt.CROWD_AVOID) && enemies.size() >= 2) {
            int n = enemies.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++)
                    if (enemies.get(i).distanceTo(enemies.get(j)) <= CROWD_DIST) parent[find(parent, i)] = find(parent, j);
            Map<Integer, List<Player>> groups = new HashMap<>();
            for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(enemies.get(i));
            for (List<Player> g : groups.values()) if (g.size() >= 2) crowds.add(g);
        }
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static double angle(double[] a, double[] b) {
        double la = Math.hypot(a[0], a[1]), lb = Math.hypot(b[0], b[1]);
        if (la < 1e-6 || lb < 1e-6) return Math.PI;
        return Math.acos(Math.max(-1, Math.min(1, (a[0] * b[0] + a[1] * b[1]) / (la * lb))));
    }

    /** Tren Kac: kacma mesafesi icinde ve bize dogru gelen tren (yoksa null). */
    public Train approachingTrain() {
        if (!on(BotPlusConfig.RadarOpt.TRAIN_FLEE)) return null;
        double flee = oe().TRAIN_FLEE;
        for (Train t : trains) {
            double d = t.distanceTo(c.hero);
            if (d <= flee && (t.headingTo(c.hero) || d <= flee * 0.5)) return t;
        }
        return null;
    }

    // ---------------------------------------------------------------- Kacin: kacinma bolgeleri

    /** Bir kacinma bolgesi: merkez oyuncu, yaricap, neden; nearOff: "Hedef Yakinsa Kacinma" uygulanir mi. */
    private static final class Zone {
        final Player at;
        final double r;
        final String why;
        final boolean nearOff;

        Zone(Player at, double r, String why, boolean nearOff) {
            this.at = at;
            this.r = r;
            this.why = why;
            this.nearOff = nearOff;
        }
    }

    /** Kac: kacilan oyuncu id -> {bitis zamani, yaricap}: bu sure yanindaki NPC / kutu / sinyal secilmez. */
    private final Map<Integer, double[]> fled = new HashMap<>();

    private List<Zone> zones() {
        List<Zone> z = new ArrayList<>();
        BotPlusConfig.OnEnemy oe = oe();
        if (oe == null) return z;
        if (on(BotPlusConfig.RadarOpt.ENEMY_AVOID))
            for (Player p : enemies) if (p.isValid()) z.add(new Zone(p, oe.RADAR_ENEMY_AVOID, "radar: dusman " + Log.name(p), true));
        if (on(BotPlusConfig.RadarOpt.TRAIN_AVOID))
            for (Train t : trains)
                for (Player p : t.members)
                    if (p.isValid()) z.add(new Zone(p, oe.TRAIN_AVOID, "radar: tren " + t.members.size() + " gemi", false));
        if (on(BotPlusConfig.RadarOpt.CROWD_AVOID))
            for (List<Player> g : crowds)
                for (Player p : g) if (p.isValid()) z.add(new Zone(p, CROWD_DIST, "radar: kalabalik " + g.size() + " dusman", false));
        return z;
    }

    /** Kacilan oyuncularin yaninda hedef secilmez (yol dolanmasina katilmaz). */
    private String fledReason(Locatable l) {
        if (fled.isEmpty()) return null;
        long now = Context.now();
        fled.values().removeIf(v -> v[0] < now);
        for (Player p : c.ents.getPlayers()) {
            double[] v = fled.get(p.getId());
            if (v != null && p.isValid() && p.distanceTo(l) < v[1]) return "radar: kacilan " + Log.name(p);
        }
        return null;
    }

    /**
     * Nokta bir kacinma bolgesinde mi (nedeni), degilse null: oraya gidilmez, oradaki NPC / kutu / sinyal secilmez.
     * NPC'nin etrafinda donerken gemi bolgeye girmesin diye mesafeye NPC_MARGIN eklenir.
     */
    public String avoidReason(Locatable l) {
        if (l == null || !any()) return null;
        for (Zone z : zones()) {
            double d = z.at.distanceTo(l);
            if (d < z.r + NPC_MARGIN) return z.why + " " + (int) d + " < " + (int) (z.r + NPC_MARGIN);
        }
        return fledReason(l);
    }

    /**
     * Gidilen hedef (NPC) sonradan bolgeye girdi mi: secimden daha dar (NPC_MARGIN yok) ki sinirda birak / al olmasin.
     */
    public String targetReason(Locatable l) {
        if (l == null || !any()) return null;
        for (Zone z : zones()) {
            double d = z.at.distanceTo(l);
            if (d < z.r) return z.why + " " + (int) d + " < " + (int) z.r;
        }
        return null;
    }

    /** Bolge id -> secilen dolanma tarafi {yon, bitis}: taraf engel geride kalana kadar korunur (sag-sol yapilmaz). */
    private final Map<Integer, double[]> side = new HashMap<>();

    /**
     * Kacin: gemiden hedefe yol bir kacinma bolgesinden geciyorsa bolgenin sagindan ya da solundan, yaricap + 300
     * disindan dolanan ara nokta; yoksa hedefin kendisi. Bolge yaricap + 1500 ileriden gorulur. Gemi bolgenin icindeyse
     * tersine donulmez: kenara dogru kayarak hedef yonunde devam edilir. Dusman bolgelerinde "Hedef Yakinsa Kacinma"
     * (hedef 1500'den yakin) dolanmayi kapatir; tren ve kalabalikta kapatmaz.
     */
    public Locatable detour(Locatable dest) {
        if (dest == null || !any()) return dest;
        List<Zone> zs = zones();
        if (zs.isEmpty()) return dest;
        long now = Context.now();
        side.values().removeIf(v -> v[1] < now);
        double hx = c.hero.getX(), hy = c.hero.getY();
        double toDest = c.hero.distanceTo(dest);
        boolean nearOff = c.config.AVOID_NEAR_OFF && toDest < Context.AVOID_NEAR;
        Zone block = null;
        double bd = Double.MAX_VALUE;
        for (Zone z : zs) {
            if (z.nearOff && nearOff) continue;
            double d = z.at.distanceTo(c.hero);
            if (d > z.r + 1500) continue; // ileri bakis
            boolean inside = d < z.r;
            double sd = segDist(hx, hy, dest.getX(), dest.getY(), z.at.getX(), z.at.getY());
            if (!inside && sd >= z.r) continue;
            if (d < bd) {
                bd = d;
                block = z;
            }
        }
        if (block == null) return dest;
        double tx = block.at.getX(), ty = block.at.getY(), r = block.r + 300;
        double a0 = Math.atan2(hy - ty, hx - tx), a1 = Math.atan2(dest.getY() - ty, dest.getX() - tx);
        double[] kept = side.get(block.at.getId());
        double dir = kept != null ? kept[0] : (norm(a1 - a0) >= 0 ? 1 : -1);
        for (int k = 0; k < 2; k++, dir = -dir) {
            // Hedef yonune 60 derece ilerideki kenar noktasi (gemi icerideyse de disari ve ileri).
            double step = Math.min(Math.toRadians(60), Math.abs(norm(a1 - a0)) + Math.toRadians(10));
            double a = a0 + dir * step;
            Location w = Location.of(tx + Math.cos(a) * r, ty + Math.sin(a) * r);
            if (c.move.canMove(w)) {
                side.put(block.at.getId(), new double[]{dir, now + 5000});
                Log.dEvery("radardetour", 5000, "RADAR", block.why + " yolda: " + (dir > 0 ? "bir yanindan" : "diger yanindan")
                        + " dolaniliyor " + Log.loc(w));
                return w;
            }
        }
        return dest;
    }

    private static double segDist(double ax, double ay, double bx, double by, double px, double py) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(ax + t * dx - px, ay + t * dy - py);
    }

    private static double norm(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ---------------------------------------------------------------- Kac: yon degistirme

    /** Oyuncu 1 sn once gemiye bundan uzaktaydi (yaklasiyor mu); bilinmiyorsa -1. */
    private double distAgo(Player p, long ms) {
        ArrayDeque<double[]> q = samples.get(p.getId());
        if (q == null) return -1;
        double[] last = q.peekLast(), old = null;
        if (last == null) return -1;
        for (double[] s : q) if (last[0] - s[0] >= ms) old = s;
        if (old == null) return -1;
        return Math.hypot(old[1] - c.hero.getX(), old[2] - c.hero.getY());
    }

    private boolean closing(Player p) {
        double before = distAgo(p, 1000);
        return before >= 0 && p.distanceTo(c.hero) < before - 30;
    }

    /** Kac kosulu tutan oyuncu ve neden ({oyuncu, neden, yaricap}); yoksa null. */
    private Object[] fleeThreat() {
        BotPlusConfig.OnEnemy oe = oe();
        Train tr = approachingTrain();
        if (tr != null) {
            Player n = tr.nearest(c.hero);
            if (n != null) return new Object[]{n, "trenden kac: " + tr.info() + " " + (int) tr.distanceTo(c.hero) + " uzakta ve geliyor",
                    (double) oe.TRAIN_FLEE};
        }
        Player best = null;
        String why = null;
        double r = 0, bd = Double.MAX_VALUE;
        int crowd = 0;
        Player crowdNear = null;
        double hp = c.heroHp();
        for (Player p : enemies) {
            if (!p.isValid()) continue;
            double d = p.distanceTo(c.hero);
            String w = null;
            double wr = 0;
            boolean remembered = (c.isKiller(p) && !c.attackStays(Log.name(p))) || c.push.feared(Log.name(p));
            if (on(BotPlusConfig.RadarOpt.ENEMY_FLEE) && d <= oe.RADAR_ENEMY_FLEE && (closing(p) || d <= oe.RADAR_ENEMY_FLEE * 0.5)) {
                w = "dusmandan kac: " + (int) d + " uzakta ve yaklasiyor";
                wr = oe.RADAR_ENEMY_FLEE;
            } else if (on(BotPlusConfig.RadarOpt.LOW_HP_FLEE) && d <= 2000 && hp < oe.RADAR_LOW_HP / 100.0) {
                w = "HP dusukken kac: " + (int) d + " uzakta, can " + Log.pct(hp) + " < %" + oe.RADAR_LOW_HP;
                wr = 2000;
            } else if (on(BotPlusConfig.RadarOpt.REMEMBERED_FLEE) && remembered && d <= 3000) {
                w = "hatirlanan dusmandan kac: " + (int) d + " uzakta";
                wr = 3000;
            }
            if (d <= CROWD_DIST) {
                crowd++;
                if (crowdNear == null || d < crowdNear.distanceTo(c.hero)) crowdNear = p;
            }
            if (w != null && d < bd) {
                bd = d;
                best = p;
                why = w;
                r = wr;
            }
        }
        if (best == null && on(BotPlusConfig.RadarOpt.CROWD_FLEE) && crowd >= 2 && crowdNear != null)
            return new Object[]{crowdNear, "kalabaliktan kac: " + (int) CROWD_DIST + " icinde " + crowd + " dusman", CROWD_DIST};
        return best == null ? null : new Object[]{best, why, r};
    }

    private long turnCooldown;

    /**
     * Her tik: Kac kosulu tutarsa yon degistirme isi (portal / kacis yok: hedef birakilir, tehlikenin tersine gidilir;
     * kacilanin yanindaki NPC / kutu / sinyal 20 sn secilmez, ters taraftaki hedefe gecilir).
     */
    public PlusModule.Job check() {
        if (!any() || Context.now() < turnCooldown) return null;
        Object[] t = fleeThreat();
        if (t == null) return null;
        Player p = (Player) t[0];
        String why = (String) t[1];
        double r = (Double) t[2];
        fled.put(p.getId(), new double[]{Context.now() + 20_000, r + NPC_MARGIN});
        c.attack.stopAttack();
        c.attack.setTarget(null);
        c.hero.setLocalTarget(null);
        Stats.count("radar kacis");
        Log.i("RADAR", Log.ship(p) + " | " + why + ": hedef birakildi, yon degistiriliyor | " + c.firmInfo(p));
        return new Turn(p, r, why);
    }

    /** Yon degistirme: tehlikenin tersine; tehlike mesafe + 1000 disina cikinca ya da 20 sn sonra biter. */
    public final class Turn implements PlusModule.Job {
        private final int id;
        private final double r;
        private final String why;
        private final long start = Context.now();
        private Location target;
        private long targetAt;

        Turn(Player p, double r, String why) {
            this.id = p.getId();
            this.r = r;
            this.why = why;
        }

        @Override
        public String name() {
            return "RADAR";
        }

        @Override
        public int priority() {
            return PlusModule.P_KILLER;
        }

        @Override
        public String status() {
            return "radar: yön değiştiriliyor (" + why + ")";
        }

        private Player threat() {
            for (Player p : c.ents.getPlayers()) if (p.getId() == id && p.isValid()) return p;
            return null;
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            Player p = threat();
            if (p == null || now - start > 20_000) return false;
            double d = p.distanceTo(c.hero);
            if (d > r + 1000) return false;
            c.pet.setEnabled(true);
            c.hero.setRoamMode();
            if (target == null || c.hero.distanceTo(target) < 400 || now - targetAt > 1000) {
                target = awayPoint(p);
                targetAt = now;
            }
            c.moveTo(target);
            Log.dEvery("radarturn", 3000, "RADAR", "yon degistirildi, " + Log.name(p) + " " + (int) d + " uzakta, gidilen "
                    + Log.loc(target));
            return true;
        }

        /** Tehlikenin tersi (2500 ileri); gidilemiyorsa 30 / 60 / 90 derece yana. */
        private Location awayPoint(Player p) {
            double a0 = Math.atan2(c.hero.getY() - p.getY(), c.hero.getX() - p.getX());
            Location best = null;
            for (double off : new double[]{0, 30, -30, 60, -60, 90, -90}) {
                double a = a0 + Math.toRadians(off);
                Location w = Location.of(c.hero.getX() + Math.cos(a) * 2500, c.hero.getY() + Math.sin(a) * 2500);
                if (c.move.canMove(w) && c.edgeDist(w) > 500) return w;
                if (best == null && c.move.canMove(w)) best = w;
            }
            return best != null ? best : Location.of(c.hero.getX() + Math.cos(a0) * 2500, c.hero.getY() + Math.sin(a0) * 2500);
        }

        @Override
        public void end(String why) {
            turnCooldown = Context.now() + 3000;
            Log.i("RADAR", "yon degistirme bitti (" + why + ") " + Log.loc(c.hero) + " | " + c.dangerInfo(c.hero));
        }
    }
}