package com.botplus.steal;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;

import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Lockable;
import eu.darkbot.api.game.other.Location;

import java.util.Objects;

/**
 * NPC Cal (tablodaki NC sutunu): NPC'yi vuran oyuncuya saldirilir (patlat ya da canini azalt),
 * sonra NPC'nin yaninda, vurma menzilinin sinirinda (yaklasik 800) beklenir; NPC bosa dusunce vurulur.
 */
public final class Steal {

    private static final int SCAN = 2000;
    private static final int WAIT_DIST = Context.ATTACK_RANGE;
    private static final int ENEMY_FOLLOW = 550;
    private static final int CHASE_FROM_NPC = 1000;
    private static final long FREE_MS = 2000;
    private static final long WAIT_TIMEOUT_MS = 30_000;
    private static final long LOST_MS = 5000;
    private static final long NEAR_LOST_MS = 3000;
    private static final long KILL_TIMEOUT_MS = 90_000;
    private static final long REPICK_MS = 3000;

    private static final long CONFIRM_MS = 2500;

    private final Context context;
    private long cooldownUntil;
    /** NPC'yi baskasinin vurdugu ilk an: calma 2,5 sn surekli vurulunca baslar. */
    private final java.util.Map<Integer, Long> seenAttacked = new java.util.HashMap<>();

    public Steal(Context context) {
        this.context = context;
    }

    /** Calinacak NPC varsa isi doner. */
    public PlusModule.Job check() {
        BotPlusConfig.Steal c = context.config.STEAL;
        if (!c.ENABLED || Context.now() < cooldownUntil) return null;
        if (seenAttacked.size() > 50) seenAttacked.clear();
        boolean lowHp = context.heroHp() < c.HERO_HP / 100.0;
        // Kendi kesimimiz yarida birakilmaz: vurdugumuz NPC varken sadece o NPC calinabilir.
        Npc own = context.targetNpc();
        if (own != null && !context.shooting(own)) own = null;
        for (Npc n : context.ents.getNpcs()) {
            if (!n.isValid() || Context.isFake(n) || context.hero.distanceTo(n) > SCAN) continue;
            if (own != null && n.getId() != own.getId()) continue;
            NpcInfo i = n.getInfo();
            if (i == null || !i.getShouldKill()) continue;
            if (n.getLockType() == Lockable.Lock.OWNED && context.shooting(n)) continue;
            Player p = context.otherAttacker(n);
            if (p == null) {
                seenAttacked.remove(n.getId());
                continue;
            }
            // Sessiz kalinmaz: baskasinin vurdugu kesilecek NPC neden calinmiyor, 10 sn'de bir loga.
            if (c.WHICH == BotPlusConfig.StealWhich.FLAGGED && !i.hasExtraFlag(BotPlusFlag.STEAL)) {
                Log.every("stealno" + n.getId(), 10_000, "CAL", Log.npc(n) + " calinmiyor: NC isareti yok (Hangi NPC'ler: NC isaretli)");
                continue;
            }
            if (lowHp) {
                Log.every("stealno" + n.getId(), 10_000, "CAL", Log.npc(n) + " calinmiyor: gemi cani " + Log.pct(context.heroHp())
                        + " < %" + c.HERO_HP);
                continue;
            }
            long first = seenAttacked.computeIfAbsent(n.getId(), id -> Context.now());
            if (Context.now() - first < CONFIRM_MS) continue;
            String no = whyNot(n, p);
            if (no != null) {
                Log.every("stealno" + n.getId(), 10_000, "CAL", Log.npc(n) + " calinmiyor: " + no);
                continue;
            }
            seenAttacked.clear();
            Stats.count("calma");
            Log.i("CAL", "BASLADI " + Log.npc(n) + " | vuran " + Log.ship(p) + " | yontem " + c.ACTION
                    + " | gemi cani " + Log.pct(context.heroHp()));
            return new Job(n, p);
        }
        return null;
    }

    /** Dovus bu NPC'yi birakmak yerine Cal'a devredebilir mi (baskasi vuruyor / sahiplik). */
    public boolean canSteal(Npc n) {
        BotPlusConfig.Steal c = context.config.STEAL;
        if (!c.ENABLED || n == null || Context.now() < cooldownUntil || context.heroHp() < c.HERO_HP / 100.0) return false;
        // check() ile ayni kosullar: 2000'den uzak NPC Cal'a devredilmez (Combat birakir, Cal hic almazdi).
        if (!n.isValid() || Context.isFake(n) || context.hero.distanceTo(n) > SCAN) return false;
        NpcInfo i = n.getInfo();
        if (i == null || !i.getShouldKill()) return false;
        if (c.WHICH == BotPlusConfig.StealWhich.FLAGGED && !i.hasExtraFlag(BotPlusFlag.STEAL)) return false;
        Player p = context.otherAttacker(n);
        if (p == null || whyNot(n, p) != null) return false;
        seenAttacked.put(n.getId(), Context.now() - CONFIRM_MS); // dogrulama beklenmez, hemen calinir
        return true;
    }

    private String whyNot(Npc n, Player p) {
        BotPlusConfig.Steal c = context.config.STEAL;
        if (Context.hp(n) < c.NPC_HP / 100.0) return "NPC cani " + Log.pct(Context.hp(n)) + " < %" + c.NPC_HP;
        if (context.isFriend(p)) return Log.ship(p) + " dost";
        if (context.isKiller(p)) return Log.ship(p) + " seni patlatan hatirlanan dusman, bulasilmiyor";
        if (context.config.PROTECT.KILLER.ENABLED) {
            String k = context.killerZone(n, 2500);
            if (k != null) return "hatirlanan dusman " + k + " yakininda";
        }
        if (c.FROM == BotPlusConfig.StealFrom.ENEMY && (p.getEntityInfo() == null || !p.getEntityInfo().isEnemy())) return Log.ship(p) + " dusman degil";
        // "Herkes" seciliyken de kendi sirketinden oyuncuya saldirilmaz (onur kaybi).
        // Sirket bilgisi okunamazsa da (NONE) kendi sirketi sayilir (Context.ownFirm).
        if (context.ownFirm(p)) return Log.ship(p) + " ayni sirket (" + context.firmInfo(p) + ")";
        return null;
    }

    private enum State { ENEMY, WAIT, KILL }

    public final class Job implements PlusModule.Job {
        private final int npcId;
        private final String npcName;
        private Location npcLoc;
        private Player enemy;
        private State state = State.ENEMY;
        private long stateAt = Context.now();
        private long chaseMs, lastTick = Context.now();
        private long freeSince, lastSeen = Context.now(), repickAt;
        private Location waitPoint;

        public Job(Npc n, Player p) {
            this.npcId = n.getId();
            this.npcName = n.getInfo().getName();
            this.npcLoc = Location.of(n);
            this.enemy = p;
        }

        @Override
        public String name() {
            return "CAL";
        }

        @Override
        public int priority() {
            return PlusModule.P_STEAL;
        }

        /** Calmada dusmana bilerek saldiriliyor: DarkBot'un dusmandan kacisi bu iste calismaz (gemi cani siniri korur). */
        @Override
        public boolean safety() {
            // Sadece dusmana saldirirken DarkBot'un kacisi kapali; beklerken / NPC'yi keserken baska dusman gelirse kacilir.
            return state != State.ENEMY;
        }

        @Override
        public String status() {
            switch (state) {
                case ENEMY: return "NPC çalma: düşmana saldırılıyor";
                case WAIT: return "NPC çalma: NPC'nin boşa düşmesi bekleniyor";
                default: return "NPC çalma: NPC vuruluyor";
            }
        }

        private void set(State s, String why) {
            Log.i("CAL", state + " -> " + s + ": " + why);
            state = s;
            stateAt = Context.now();
            freeSince = 0;
            waitPoint = null;
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            long dt = now - lastTick;
            lastTick = now;
            BotPlusConfig.Steal c = context.config.STEAL;
            if (context.config.PET.ENABLED) context.pet.setEnabled(true);

            if (context.heroHp() < c.HERO_HP / 100.0) {
                Log.i("CAL", "gemi cani " + Log.pct(context.heroHp()) + " < %" + c.HERO_HP + ", birakildi");
                return stop(60_000);
            }

            Npc npc = context.findNpc(npcId);
            if (npc != null) {
                npcLoc = Location.of(npc);
                lastSeen = now;
            } else if (state != State.ENEMY
                    && ((context.hero.distanceTo(npcLoc) < 1500 && now - lastSeen > NEAR_LOST_MS) || now - lastSeen > LOST_MS)) {
                // Dusman kovalanirken NPC gorus disina cikabilir: kovalama suresi (ayar) gecerli, NPC kayip sayilmaz.
                // NPC gorunmuyor: yakinindaysak 3 sn, degilsek 5 sn sonra birakilir (bos yere beklenmez).
                Log.i("CAL", npcName + " #" + npcId + " son yerinde " + Log.loc(npcLoc) + " yok (oldu ya da gitti)");
                return stop(10_000);
            }
            if (npc != null && state != State.KILL && Context.hp(npc) < c.NPC_HP / 100.0) {
                Log.i("CAL", Log.npc(npc) + " cani esigin altina indi, birakildi");
                return stop(10_000);
            }

            // Harita degisimi / yeniden yukleme: eski Player nesnesi tutulmaz, listede ayni id yoksa gitmis sayilir.
            if (enemy != null && !present(enemy)) {
                Log.i("CAL", "dusman " + Log.ship(enemy) + " artik haritada yok");
                enemy = null;
            }

            switch (state) {
                case ENEMY: return enemy(npc, now, dt, c);
                case WAIT: return waitFree(npc, now, c);
                default: return kill(npc, now);
            }
        }

        private boolean present(Player p) {
            int id = p.getId();
            for (Player q : context.ents.getPlayers()) if (q.getId() == id) {
                enemy = q; // guncel nesne
                return true;
            }
            return false;
        }

        private boolean enemy(Npc npc, long now, long dt, BotPlusConfig.Steal c) {
            if (enemy == null || !enemy.isValid()) {
                set(State.WAIT, "dusman yok oldu / patladi");
                context.attack.stopAttack();
                return true;
            }
            if (c.ACTION == BotPlusConfig.StealAction.REDUCE && Context.hp(enemy) <= c.ENEMY_HP / 100.0) {
                set(State.WAIT, "dusman cani " + Log.pct(Context.hp(enemy)) + " <= %" + c.ENEMY_HP);
                context.attack.stopAttack();
                return true;
            }
            if (enemy.distanceTo(npcLoc) > CHASE_FROM_NPC) chaseMs += dt;
            if (chaseMs > c.CHASE_SEC * 1000L) {
                set(State.WAIT, "kovalama suresi doldu (" + c.CHASE_SEC + " sn), NPC'ye donuluyor");
                context.attack.stopAttack();
                return true;
            }
            if (now - stateAt > KILL_TIMEOUT_MS) {
                Log.i("CAL", KILL_TIMEOUT_MS / 1000 + " sn icinde dusman birakmadi, birakildi");
                return stop(20_000);
            }
            if (!Objects.equals(context.attack.getTarget(), enemy)) context.attack.setTarget(enemy);
            context.attack.tryLockAndAttack();
            // Oyuncuyla dovuste saldiri konfigurasyonu (dolasma konfigurasyonu degil).
            context.hero.setAttackMode(null);
            if (context.hero.distanceTo(enemy) > ENEMY_FOLLOW)
                context.moveTo(Location.of(enemy, enemy.angleTo(context.hero), ENEMY_FOLLOW - 100));
            Log.every("calenemy", 3000, "CAL", "dusman " + Log.ship(enemy) + " | gemi " + Log.pct(context.heroHp())
                    + " | kovalama " + chaseMs / 1000 + " sn");
            return true;
        }

        private boolean waitFree(Npc npc, long now, BotPlusConfig.Steal c) {
            if (now - stateAt > WAIT_TIMEOUT_MS) {
                Log.i("CAL", "30 sn beklendi, NPC bosa dusmedi");
                return stop(20_000);
            }
            if (npc != null) {
                Player p = context.otherAttacker(npc);
                if (p != null) {
                    freeSince = 0;
                    if (p.getId() != (enemy == null ? -1 : enemy.getId()) && whyNot(npc, p) == null) {
                        enemy = p;
                        chaseMs = 0;
                        set(State.ENEMY, "yeni vuran " + Log.ship(p));
                        return true;
                    }
                    // Ayni dusman NPC'yi vurmaya devam ediyor: 30 sn bos beklenmez. Patlat seciliyse tekrar saldirilir,
                    // Canini Azalt'ta 5 sn sonra birakilir.
                    if (now - stateAt > 5000) {
                        if (c.ACTION == BotPlusConfig.StealAction.KILL && whyNot(npc, p) == null && chaseMs <= c.CHASE_SEC * 1000L) {
                            enemy = p;
                            set(State.ENEMY, "ayni dusman NPC'yi vurmaya devam ediyor");
                            return true;
                        }
                        Log.i("CAL", Log.ship(p) + " NPC'yi vurmaya devam ediyor, birakildi");
                        return stop(20_000);
                    }
                } else {
                    if (freeSince == 0) freeSince = now;
                    if (now - freeSince >= FREE_MS) {
                        set(State.KILL, Log.npc(npc) + " bosa dustu");
                        return true;
                    }
                }
            }
            // Vurma menzilinin sinirinda (yaklasik 800) bekle, durmadan kucuk hareketlerle.
            if (waitPoint == null || now > repickAt || context.hero.distanceTo(waitPoint) < 60) {
                // Bekleme: NPC'nin tablodaki menzilinin hemen sinirinda (bosa dusunce hemen vurulur).
                double wait = npc != null ? npc.getInfo().getRadius() : WAIT_DIST;
                waitPoint = context.ringPoint(npcLoc, wait, npcLoc.angleTo(context.hero), Math.PI / 6);
                repickAt = now + REPICK_MS;
            }
            context.hero.setRoamMode();
            context.moveTo(waitPoint);
            return true;
        }

        private boolean kill(Npc npc, long now) {
            if (npc == null) {
                if (now - lastSeen > LOST_MS) {
                    Log.i("CAL", "NPC kayboldu / oldu, bitti");
                    return stop(0);
                }
                context.moveTo(npcLoc);
                return true;
            }
            if (now - stateAt > KILL_TIMEOUT_MS) {
                Log.i("CAL", "90 sn icinde NPC bitirilemedi");
                return stop(20_000);
            }
            if (context.otherAttacker(npc) != null && npc.getLockType() != Lockable.Lock.OWNED) {
                set(State.WAIT, "NPC tekrar baskasina gecti");
                return true;
            }
            if (!Objects.equals(context.attack.getTarget(), npc)) context.attack.setTarget(npc);
            context.attack.tryLockAndAttack();
            context.hero.setAttackMode(npc);
            double r = npc.getInfo().getRadius();
            if (context.hero.distanceTo(npc) > r + 50)
                context.moveTo(Location.of(npc, npc.angleTo(context.hero), r));
            Log.every("calkill", 3000, "CAL", "vuruluyor " + Log.npc(npc) + " kilit " + npc.getLockType());
            return true;
        }

        private boolean stop(long cooldown) {
            cooldownUntil = Context.now() + cooldown;
            return false;
        }

        @Override
        public void end(String why) {
            context.attack.stopAttack();
            context.attack.setTarget(null);
            // Her bitiste kisa ara: kesilince / bitince ayni NPC'ye hemen tekrar baslanmasin.
            cooldownUntil = Math.max(cooldownUntil, Context.now() + 5000);
            Log.i("CAL", "bitti (" + why + ")");
        }
    }

    /** Dusmana ates edilirken kullanilacak cephane (LaserSelector). */
    public boolean firingAtEnemy(PlusModule m) {
        return m != null && m.job() instanceof Job && ((Job) m.job()).state == State.ENEMY;
    }
}