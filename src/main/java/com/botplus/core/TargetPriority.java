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
import eu.darkbot.api.game.other.Location;

import java.util.Objects;

/**
 * Kutu / NPC Onceligi. Hem kutu hem NPC varken BotPlus isi devralir, ustte ne yapildigi yazar:
 * NPC once: NPC bitene kadar kutulara gidilmez, sonra kutular toplanir.
 * Kutu once: yakindaki kutular toplanir, sonra NPC'ye donulur.
 * Tablo onceligi: kutu tablosundaki oncelik ile NPC tablosundaki oncelik karsilastirilir, kucuk sayi once.
 */
public final class TargetPriority {

    private static final int BOX_SCAN = 1500;
    private static final int COLLECT_DIST = 250;
    private static final long NPC_TIMEOUT_MS = 120_000;
    private static final long BOX_TIMEOUT_MS = 60_000;
    private static final long COOLDOWN_MS = 1500;

    private final Context context;
    private long cooldownUntil;

    public TargetPriority(Context context) {
        this.context = context;
    }

    private boolean collectable(Box b) {
        // Ucuz mesafe kontrolu once (collectable yol hesabi yapar).
        return context.hero.distanceTo(b) < Math.min(BOX_SCAN, context.config.BOX_MAX_DIST) && context.collectable(b);
    }

    private int boxCount() {
        int n = 0;
        for (Box b : context.ents.getBoxes()) if (collectable(b)) n++;
        return n;
    }

    /** Hem kutu hem hedef NPC varsa ayara gore isi doner. */
    public PlusModule.Job check(boolean collectModule) {
        BotPlusConfig.Sort order = context.config.SORT;
        if (order == BotPlusConfig.Sort.MODULE || !collectModule || Context.now() < cooldownUntil) return null;
        Npc npc = context.targetNpc();
        if (npc == null) return null;
        // Kutular bir kez taranir (collectable her kutu icin tik basina bir kez).
        int boxes = 0, boxPri = Integer.MAX_VALUE;
        for (Box b : context.ents.getBoxes())
            if (collectable(b)) {
                boxes++;
                boxPri = Math.min(boxPri, b.getInfo().getPriority());
            }
        if (boxes == 0) return null;
        boolean npcFirst = order == BotPlusConfig.Sort.NPC_FIRST;
        if (order == BotPlusConfig.Sort.TABLE) {
            npcFirst = npc.getInfo().getPriority() <= boxPri;
            Log.every("orderpri", 5000, "ONCELIK", "tablo onceligi: NPC " + npc.getInfo().getPriority() + " / kutu " + boxPri
                    + " -> " + (npcFirst ? "NPC once" : "kutu once"));
        }
        if (npcFirst) {
            Log.i("ONCELIK", "NPC once: " + Log.npc(npc) + ", " + boxes + " kutu sonra toplanacak");
            return new NpcFirst(npc);
        }
        Log.i("ONCELIK", "kutu once: " + boxes + " kutu toplanacak, sonra " + Log.npc(npc));
        return new BoxFirst(npc);
    }

    public final class NpcFirst implements PlusModule.Job {
        private final Npc npc;
        private final long start = Context.now();

        public NpcFirst(Npc npc) {
            this.npc = npc;
        }

        @Override
        public String name() {
            return "ONCELIK";
        }

        @Override
        public int priority() {
            return PlusModule.P_ORDER;
        }

        @Override
        public String status() {
            return "NPC'ye gidiliyor, kutular yok sayıldı (" + boxCount() + " kutu sonra)";
        }

        @Override
        public boolean tick() {
            if (!npc.isValid() || context.findNpc(npc.getId()) == null) {
                Log.i("ONCELIK", "NPC bitti, kutulara geciliyor (" + boxCount() + " kutu)");
                return false;
            }
            if (Context.now() - start > NPC_TIMEOUT_MS) {
                Log.i("ONCELIK", "2 dk icinde NPC bitmedi, DarkBot'a birakildi");
                return false;
            }
            if (npc.isBlacklisted() || !npc.getInfo().getShouldKill()) {
                Log.i("ONCELIK", "NPC birakildi (yok sayildi / listeden cikti)");
                return false;
            }
            // DarkBot kurali: NPC'ye uzaklik "NPC uzaklik siniri"ni asarsa birakilir (haritanin ote ucuna kovalanmaz).
            double hd = context.hero.distanceTo(npc);
            if (hd > context.npcDistanceIgnore()) {
                Log.i("ONCELIK", Log.npc(npc) + " cok uzak (" + (int) hd + " > " + context.npcDistanceIgnore() + "), birakildi");
                return false;
            }
            // DarkBot kurallari: baskasi vuruyor, baskasinin sahipligi, engel icinde ulasilamiyor.
            if (!Context.ignoresAttacked(npc) && context.attackedByOthers(npc)) {
                Log.i("ONCELIK", Log.npc(npc) + " baskasi vuruyor, birakildi");
                return false;
            }
            if (context.move.getClosestDistance(npc) > 600) {
                Log.i("ONCELIK", Log.npc(npc) + " engel icinde, ulasilamiyor, birakildi");
                context.bans.ban(npc, Bans.Owner.ORDER, 5000);
                return false;
            }
            if (Objects.equals(context.hero.getTarget(), npc) && !(Context.ignoresOwnership(npc) || npc.isOwned())) {
                Log.i("ONCELIK", Log.npc(npc) + " baskasinin sahipliginde, birakildi");
                context.bans.ban(npc, Bans.Owner.ORDER, 5000);
                return false;
            }
            if (!Objects.equals(context.attack.getTarget(), npc)) context.attack.setTarget(npc);
            context.attack.tryLockAndAttack();
            context.hero.setAttackMode(npc);
            double r = npc.getInfo().getRadius();
            double d = context.hero.distanceTo(npc);
            double angle = npc.angleTo(context.hero) + (d <= r * 1.05 && d >= r * 0.7 ? 0.25 : 0);
            context.moveTo(Location.of(npc, angle, r));
            return true;
        }

        @Override
        public void end(String why) {
            cooldownUntil = Context.now() + COOLDOWN_MS;
        }
    }

    public final class BoxFirst implements PlusModule.Job {
        private final Npc npc;
        private final long start = Context.now();
        private Box current;
        private long waitUntil;
        private int collected;
        private int countedId = Integer.MIN_VALUE;

        public BoxFirst(Npc npc) {
            this.npc = npc;
        }

        @Override
        public String name() {
            return "ONCELIK";
        }

        @Override
        public int priority() {
            return PlusModule.P_ORDER;
        }

        @Override
        public String status() {
            return "kutuya gidiliyor, NPC sıradaki adım (" + boxCount() + " kutu)";
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            if (now < waitUntil) return true;
            if (now - start > BOX_TIMEOUT_MS) {
                Log.i("ONCELIK", "kutu toplama birakildi, NPC'ye donuluyor");
                return false;
            }
            if (current == null || !current.isValid() || current.isCollected()) {
                current = null;
                double best = Double.MAX_VALUE;
                for (Box b : context.ents.getBoxes()) {
                    if (!collectable(b)) continue;
                    double d = context.hero.distanceTo(b);
                    if (d < best) {
                        best = d;
                        current = b;
                    }
                }
                if (current == null) {
                    Log.i("ONCELIK", collected + " kutu toplandi, simdi NPC: " + Log.npc(npc));
                    return false;
                }
            }
            double d = context.hero.distanceTo(current);
            if (d < COLLECT_DIST) {
                context.stop();
                if (context.collect(current)) {
                    if (current.getId() != countedId) { // kutu basina bir kez sayilir (tiklama degil)
                        countedId = current.getId();
                        collected++;
                    }
                    waitUntil = now + context.collectWaitLeft();
                }
            } else if (!context.collectBusy()) {
                context.moveTo(current);
            }
            return true;
        }

        @Override
        public void end(String why) {
            cooldownUntil = Context.now() + COOLDOWN_MS;
        }
    }
}