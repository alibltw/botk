package com.botplus.defense;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.enums.PetGear;
import eu.darkbot.api.game.items.ItemUseResult;
import eu.darkbot.api.game.items.SelectableItem;

import java.util.Set;

/**
 * Koruma: EMP, Insta-Shield ve PET HP Link. Her birinin "Ne Zaman" secimi ve can siniri var:
 * - "HP sinirin altinda" + saldirgan (oyuncu / NPC) secili: can sinirin altinda VE secili turden biri saldiriyorsa.
 * - Sadece "HP sinirin altinda" (ya da hic secim yok): can sinirin altina dusmekteyken (hasar aliniyorken).
 *   Gemi yol giderken / tamirdeyken can zaten dusukse basilmaz.
 * - Sadece saldirgan secili: can sinirina bakilmaz, o turden biri gemiye ates edince.
 * Oyuncu ve NPC birlikte secildiyse ikisinden biri yeter. Elde olmayan ya da hazir olmayan ozellige dokunulmaz.
 */
public final class DefenseLogic {

    /** HP Link en fazla bu kadar istenir (PET hazir degilse istek duser). */
    private static final long HP_LINK_ASK_MS = 6000;
    /** Can + kalkan bu sure icinde dustuyse hasar aliniyor sayilir. */
    private static final long DAMAGE_MS = 2000;
    /** Saldirgan sayilan uzaklik. */
    private static final int ATTACKER_DIST = 1000;

    private final Context context;
    private double lastTotal = -1;
    private long damageAt;

    public DefenseLogic(Context context) {
        this.context = context;
    }

    public void tick(boolean active) {
        BotPlusConfig.Protect c = context.config.PROTECT;
        trackDamage();
        if (!active || !context.config.ENABLED || !c.ENABLED || loggingOut()) return;
        try {
            if (context.repair.isDestroyed() || !context.heroLoaded()) return;
        } catch (RuntimeException e) {
            return;
        }
        long now = Context.now();
        double hp = context.heroHp();
        Player enemy = enemyAttacker();
        Npc npc = npcAttacker();
        boolean damaged = now - damageAt < DAMAGE_MS;

        if (c.EMP_ON) {
            String why = reason(c.EMP_WHEN, c.EMP_HP, hp, damaged, enemy, npc);
            // EMP oyuncu kilitlerini kirar; NPC kilidi hemen geri gelir. Sadece bize ates eden oyuncu varken basilir
            // (eskiden NPC'ye de basiliyordu: 54 EMP'nin 39'u NPC'ye gitti, oyuncu gelince EMP beklemede kaliyordu).
            if (why != null && (enemy != null || context.attackerOfHero() instanceof Player))
                use(SelectableItem.Special.EMP_01, "EMP", why, hp);
            else if (why != null)
                Log.dEvery("empnpc", 30_000, "KORUMA", "EMP basilmadi: bize ates eden oyuncu yok (" + why + ")");
        }
        if (c.ISH_ON) {
            String why = reason(c.ISH_WHEN, c.ISH_HP, hp, damaged, enemy, npc);
            if (why != null) use(SelectableItem.Special.ISH_01, "INSTA-SHIELD", why, hp);
        }

        BotPlusConfig.HpLinkSet link = c.HP_LINK;
        // PET'i BotPlus yonetmiyorsa HP Link istenmez (istek PET'i degistiremez, bosuna sayilir).
        if (link.ENABLED && context.config.PET.ENABLED && now >= context.hpLinkUntil && !context.hpLinkActive(now)) {
            String why = reason(link.WHEN, link.HP, hp, damaged, enemy, npc);
            boolean ready;
            try {
                ready = why != null && context.pet.isActive() && !context.pet.hasCooldown(PetGear.HP_LINK)
                        && context.pet.hasGear(PetGear.HP_LINK);
            } catch (RuntimeException e) {
                ready = false;
            }
            if (ready) {
                context.hpLinkUntil = now + HP_LINK_ASK_MS;
                context.hpLinkUsedAt = 0;
                Log.i("KORUMA", "PET HP Link istendi, " + why + ", can " + Log.pct(hp));
                Stats.count("hp link");
            }
        }
    }

    /**
     * Basilma nedeni, basilmayacaksa null. Secimler birlikte aranir (bkz. sinif aciklamasi).
     */
    private String reason(Set<BotPlusConfig.When> w, int limit, double hp, boolean damaged, Player enemy, Npc npc) {
        boolean hpSel = w == null || w.isEmpty() || w.contains(BotPlusConfig.When.HP_LOW);
        boolean enemySel = w != null && w.contains(BotPlusConfig.When.ENEMY);
        boolean npcSel = w != null && w.contains(BotPlusConfig.When.NPC);
        boolean hpLow = hp <= limit / 100.0;
        String attacker = enemySel && enemy != null ? "oyuncu saldiriyor: " + Log.ship(enemy)
                : npcSel && npc != null ? "NPC saldiriyor: " + Log.name(npc) : null;
        if (enemySel || npcSel) {
            if (attacker == null) return null;
            if (!hpSel) return attacker;
            return hpLow ? attacker + ", can <= %" + limit : null;
        }
        // Sadece HP: can sinirin altina dusmekteyken (hasar aliniyor), kim vurursa vursun.
        return hpLow && damaged ? "can <= %" + limit + " ve dusuyor" : null;
    }

    /** Can + kalkan dususu: hasar aliniyor mu. */
    private void trackDamage() {
        try {
            double total = context.hero.getHealth().getHp() + context.hero.getHealth().getShield();
            if (lastTotal >= 0 && total < lastTotal - 1) {
                damageAt = Context.now();
                context.heroDamageAt = damageAt;
            }
            lastTotal = total;
        } catch (RuntimeException ignored) {
        }
    }

    /** Cikis yapip yenileme geri sayimi suruyor mu (her eylem cikisi iptal eder). */
    private boolean loggingOut() {
        try {
            PlusModule m = context.job.get();
            if (m == null || m.ended()) return false;
            PlusModule.Job j = m.job();
            return j instanceof Refresh.Job && ((Refresh.Job) j).loggingOut();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Ayni ozellik son basistan 1 sn gecmeden tekrar basilmaz. */
    private final java.util.Map<SelectableItem, Long> lastUse = new java.util.HashMap<>();

    private boolean use(SelectableItem item, String name, String why, double hp) {
        long now = Context.now();
        Long last = lastUse.get(item);
        if (last != null && now - last < 1000) return false;
        try {
            lastUse.put(item, now);
            ItemUseResult r = context.items.useItem(item, 1000);
            if (r == ItemUseResult.SUCCESS) {
                Log.i("KORUMA", name + " basildi, " + why + ", can " + Log.pct(hp));
                Stats.count(name.toLowerCase(java.util.Locale.ROOT));
                return true;
            }
            // Basilamadi: bekleme suresi (NOT_READY / RECENTLY_USED) olagan, sadece ayrintili logda; digerleri (yok,
            // kullanilamaz) her zaman loga (eskiden sessizdi).
            if (r == ItemUseResult.NOT_READY || r == ItemUseResult.RECENTLY_USED)
                Log.dEvery("defnr" + name, 10_000, "KORUMA", name + " hazir degil (" + r + "), " + why);
            else Log.every("defuse" + name, 10_000, "KORUMA", name + " basilamadi (" + r + "), " + why + ", can " + Log.pct(hp));
        } catch (RuntimeException e) {
            Log.every("defuse" + name, 10_000, "KORUMA", name + " basilamadi (" + e + ")");
        }
        return false;
    }

    private Player enemyAttacker() {
        for (Player p : context.ents.getPlayers())
            if (context.isEnemyPlayer(p) && p.isAttacking(context.hero) && p.distanceTo(context.hero) < ATTACKER_DIST + 500)
                return p;
        return null;
    }

    private Npc npcAttacker() {
        for (Npc n : context.ents.getNpcs())
            // Olmek uzere olan NPC (can <= %10) saldirgan sayilmaz: HP Link gereksiz basilip PET 20 sn bulucudan cikiyordu.
            if (n.isValid() && !Context.isFake(n) && Context.hp(n) > 0.10 && n.isAttacking(context.hero)
                    && n.distanceTo(context.hero) < ATTACKER_DIST)
                return n;
        return null;
    }
}