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
        // Yeni saldiri tespiti: bize ates eden her oyuncu (uzaklik farketmez) her tik yazilir.
        for (Player p : context.ents.getPlayers())
            if (context.isEnemyPlayer(p) && p.isAttacking(context.hero)) newEpisode(p, now);
        Npc npc = npcAttacker();
        boolean damaged = now - damageAt < DAMAGE_MS;

        if (c.EMP_ON) {
            String why = reason("EMP", c.EMP_WHEN, c.EMP_HP, hp, damaged, enemy, npc);
            // EMP oyuncu kilitlerini kirar; NPC kilidi hemen geri gelir. Sadece bize ates eden oyuncu varken basilir
            // (eskiden NPC'ye de basiliyordu: 54 EMP'nin 39'u NPC'ye gitti, oyuncu gelince EMP beklemede kaliyordu).
            if (why != null && (enemy != null || context.attackerOfHero() instanceof Player || context.pvpTarget.get() != null)) {
                if (use(SelectableItem.Special.EMP_01, "EMP", why, hp)) {
                    markFirst("EMP", enemy);
                    context.empUsedAt = now;
                }
            } else if (why != null)
                Log.dEvery("empnpc", 30_000, "KORUMA", "EMP basilmadi: bize ates eden oyuncu yok (" + why + ")");
        }
        if (c.ISH_ON) {
            String why = reason("ISH", c.ISH_WHEN, c.ISH_HP, hp, damaged, enemy, npc);
            if (why != null && use(SelectableItem.Special.ISH_01, "INSTA-SHIELD", why, hp)) markFirst("ISH", enemy);
        }

        counter(c, hp, enemy, now);
        restoreRocket(now);

        BotPlusConfig.HpLinkSet link = c.HP_LINK;
        // PET'i BotPlus yonetmiyorsa HP Link istenmez (istek PET'i degistiremez, bosuna sayilir).
        if (link.ENABLED && context.config.PET.ENABLED && now >= context.hpLinkUntil && !context.hpLinkActive(now)) {
            String why = reason("LINK", link.WHEN, link.HP, hp, damaged, enemy, npc);
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
    /** "Dusman saldirinca" bu saldirgan icin bu saldirida bir kez basildi (ozellik:oyuncu id). */
    private final java.util.Set<String> firstDone = new java.util.HashSet<>();
    /** Oyuncu id -> bize son ates ettigi an (yeni saldiri tespiti). */
    private final java.util.Map<Integer, Long> lastSeenAttack = new java.util.HashMap<>();
    /** Bu kadar sure ates etmeyen oyuncunun sonraki atesi yeni saldiri sayilir ("Dusman Saldirinca" yeniden basar). */
    private static final long NEW_ATTACK_GAP_MS = 30_000;

    /**
     * Yeni saldiri: oyuncu NEW_ATTACK_GAP_MS ates etmeyip yeniden ates ederse onun "Dusman Saldirinca" kayitlari
     * silinir (eskiden oyuncu basina bir kez basiliyordu: ayni oyuncunun ikinci saldirisinda EMP basilmadi).
     */
    private void newEpisode(Player enemy, long now) {
        if (enemy == null) return;
        Long prev = lastSeenAttack.put(enemy.getId(), now);
        if (prev != null && now - prev > NEW_ATTACK_GAP_MS) {
            String suffix = ":" + enemy.getId();
            firstDone.removeIf(k -> k.endsWith(suffix));
            Log.d("KORUMA", Log.ship(enemy) + " yeniden saldiriyor (" + (now - prev) / 1000 + " sn sonra): Dusman Saldirinca yeniden basilabilir");
        }
        if (lastSeenAttack.size() > 200) lastSeenAttack.clear();
    }

    private void markFirst(String item, Player enemy) {
        if (enemy == null) return;
        if (firstDone.size() > 200) firstDone.clear();
        firstDone.add(item + ":" + enemy.getId());
    }

    private String reason(String item, Set<BotPlusConfig.When> w, int limit, double hp, boolean damaged, Player enemy, Npc npc) {
        // Dusman Saldirinca: can sinirina bakilmadan, dusman ilk ates ettigi an.
        if (w != null && w.contains(BotPlusConfig.When.ENEMY_FIRST) && enemy != null && !firstDone.contains(item + ":" + enemy.getId()))
            return "dusman saldirdi: " + Log.ship(enemy);
        // Dusmana Saldirinca: Saldir (PvP) isi suruyor.
        if (w != null && w.contains(BotPlusConfig.When.ATTACKING)) {
            Player t = context.pvpTarget.get();
            if (t != null) return "dusmana saldiriliyor: " + Log.ship(t);
        }
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

    /** Roket secimi en sik bu kadar surede bir yenilenir. */
    private static final long ROCKET_MS = 3000;
    /** Mayin icin dusman en fazla bu uzaklikta olmali. */
    private static final int MINE_DIST = 1500;

    /**
     * Dusmana Karsi: dusman bize ates ederken ya da Saldir (PvP) isi surerken secili roket / mayin / gemi yetenegi.
     * Mayinlar (SLM-01, IM-01) gemi hareket ederken (kacis) arkaya birakilir. Roketler (DCR-250, R-IC3, PLD-8) sadece
     * gemi o dusmana kilitliyken secilir (PvP ya da Sadece Bulastir atisi); atis oyunun otomatik roketiyle olur.
     */
    private void counter(BotPlusConfig.Protect c, double hp, Player enemy, long now) {
        Set<BotPlusConfig.Counter> cs = c.COUNTER;
        if (cs == null || cs.isEmpty()) return;
        Player pvp = context.pvpTarget.get();
        Player foe = pvp != null ? pvp : enemy;
        if (foe == null) return;
        // Kimlere Karsilik: secili olmayan sirkete (varsayilan: kendi sirketine) zehir / roket / mayin / yetenek yok;
        // kendi sirketi secildiyse de sadece son 5 sn icinde bize ates ettiyse.
        if (!context.mayFire(foe, c.FIRMS)) {
            Log.dEvery("defirm" + foe.getId(), 30_000, "KORUMA", "dusmana karsi kullanilmiyor: " + Log.ship(foe) + " "
                    + context.firmInfo(foe) + " (Kime Karsi)");
            return;
        }
        String why = (pvp != null ? "dusmana saldiriliyor: " : "dusman saldiriyor: ") + Log.ship(foe);
        boolean moving;
        try {
            moving = context.move.isMoving();
        } catch (RuntimeException e) {
            moving = false;
        }
        double dist = foe.distanceTo(context.hero);
        if (moving && dist < MINE_DIST) {
            if (cs.contains(BotPlusConfig.Counter.SLM_01)) use(SelectableItem.Mine.SLM_01, "YAVASLATICI MAYIN", why, hp);
            if (cs.contains(BotPlusConfig.Counter.IM_01)) use(SelectableItem.Mine.IM_01, "HASTALIK MAYINI", why, hp);
        }
        // Yaklasinca Hiz Yetenegi: hiz turundeki yetenek kacis istedigi ana kadar tutulur (kacis kendisi basar).
        boolean hold = Context.speedType(c.ABILITY) && context.holdSpeed.getAsBoolean();
        if (cs.contains(BotPlusConfig.Counter.ABILITY) && c.ABILITY != null && !hold) use(c.ABILITY, "GEMI YETENEGI " + c.ABILITY, why, hp);
        boolean lockedOnFoe = java.util.Objects.equals(context.hero.getTarget(), foe);
        if (lockedOnFoe) {
            SelectableItem.Rocket r = cs.contains(BotPlusConfig.Counter.DCR_250) ? SelectableItem.Rocket.DCR_250
                    : cs.contains(BotPlusConfig.Counter.R_IC3) ? SelectableItem.Rocket.R_IC3
                    : cs.contains(BotPlusConfig.Counter.PLD_8) ? SelectableItem.Rocket.PLD_8 : null;
            Long last = lastUse.get(r);
            if (r != null && (last == null || now - last >= ROCKET_MS)) {
                // Eski roket bir kez kaydedilir; oyuncuyla is bitince geri secilir (eskiden hep yavaslatici kaliyordu).
                if (prevRocket == null) {
                    try {
                        SelectableItem.Rocket cur = context.hero.getRocket();
                        if (cur != null && cur != r) prevRocket = cur;
                    } catch (RuntimeException ignored) {
                    }
                }
                if (use(r, "ROKET " + r, why, hp)) counterRocketAt = now;
            }
        }
    }

    /** Karsi Saldiri'dan once secili roket (geri secilecek), yoksa null. */
    private SelectableItem.Rocket prevRocket;
    private long counterRocketAt;
    /** Oyuncuyla is bu kadar suredir bittiyse eski roket geri secilir. */
    private static final long ROCKET_RESTORE_MS = 5000;

    /**
     * Eski roketi geri sec: PvP hedefi yok, bize 5 sn'dir ates eden oyuncu yok ve kilit bir oyuncuda degilken
     * (kendi sirketine yanlislikla roket gitmesin).
     */
    private void restoreRocket(long now) {
        if (prevRocket == null || now - counterRocketAt < ROCKET_RESTORE_MS) return;
        if (context.pvpTarget.get() != null || context.recentAttacker(ROCKET_RESTORE_MS) != null) return;
        try {
            if (context.hero.getTarget() instanceof Player) return;
            if (context.hero.getRocket() == prevRocket) {
                prevRocket = null;
                return;
            }
            ItemUseResult r = context.items.useItem(prevRocket, 1000);
            if (r == ItemUseResult.SUCCESS) {
                Log.i("KORUMA", "eski roket geri secildi: " + prevRocket);
                prevRocket = null;
            } else Log.every("rocketback", 10_000, "KORUMA", "eski roket secilemedi (" + r + "): " + prevRocket);
        } catch (RuntimeException ignored) {
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