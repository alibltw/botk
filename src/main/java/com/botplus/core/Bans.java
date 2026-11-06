package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Npc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Merkezi yok sayma (blacklist) yoneticisi. BotPlus'in butun ozellikleri NPC'yi buradan yok sayar:
 * - Hicbir ozellik baskasinin (DarkBot, baska eklenti ya da BotPlus'in baska ozelligi) yok saymasini kisaltmaz.
 * - Kaldirma sadece o sahibin kaydini siler; NPC ancak BotPlus'in baska kaydi yoksa ve yok sayma bizimse acilir.
 * Oyun API'si yok sayma bitis zamanini vermez: bizim koydugumuz son bitis tutulur, NPC bundan sonra da yok
 * sayiliyorsa bu baskasinin yok saymasidir ve dokunulmaz.
 */
public final class Bans {

    /** Yok sayan ozellik. */
    public enum Owner { HOLD, LOCATOR_BLOCK, IGNORE, COMBAT, DROP, ORDER, WAIT, STEAL }

    private static final class Entry {
        public final Map<Owner, Long> until = new HashMap<>();
        /** NPC'ye en son bizim yazdigimiz bitis. */
        public long applied;
        /** Bizden once / bizim surenin disinda baskasi (DarkBot, baska eklenti) da yok saydi: kaldirirken acilmaz. */
        public boolean foreign;
    }

    private final Map<Integer, Entry> entries = new HashMap<>();
    private long lastClean;

    /** NPC'yi ms kadar yok sayar; mevcut (bizim ya da baskasinin) daha uzun yok saymayi kisaltmaz. */
    public void ban(Npc n, Owner owner, long ms) {
        if (n == null || ms <= 0) return;
        long now = Context.now();
        clean(now);
        Entry e = entries.computeIfAbsent(n.getId(), k -> new Entry());
        long until = now + ms;
        Long old = e.until.get(owner);
        if (old == null || old < until) e.until.put(owner, until);
        // Bizim kaydimiz olmadan zaten yok sayiliyor: baskasinin yok saymasi (suresi bilinmiyor). Ustune yazilmaz
        // (kisaltabilir); sadece bizim bitisimiz tutulur, baskasininki bitince has() / ban() bizimkini uygular.
        boolean blacklisted = n.isBlacklisted();
        if (blacklisted && e.applied < now) {
            e.foreign = true;
            return;
        }
        // Baskasinin yok saymasi bittiyse oyundaki yok sayma artik bizim (birakinca acilsin).
        if (!blacklisted) e.foreign = false;
        // Bizimki uzadiysa ya da oyunda yok sayma kalkmissa (bizim kaydimiz suruyor) yeniden uygulanir.
        if (until > e.applied || !blacklisted) apply(n, e, now);
    }

    /** Bizim kayitlarimizin en gec bitisini oyuna yazar. */
    private static void apply(Npc n, Entry e, long now) {
        long rest = rest(e);
        if (rest <= now) return;
        n.setBlacklisted(rest - now);
        e.applied = rest;
    }

    /** Bizim kayitlarimizin en gec bitisi (kayit yoksa 0). */
    private static long rest(Entry e) {
        long rest = 0;
        for (long u : e.until.values()) rest = Math.max(rest, u);
        return rest;
    }

    /** Bu sahibin yok saymasini kaldirir. Baska BotPlus kaydi varsa o surer; baskasinin yok saymasina dokunulmaz. */
    public void release(Npc n, Owner owner) {
        if (n == null) return;
        Entry e = entries.get(n.getId());
        if (e == null || e.until.remove(owner) == null) return;
        long now = Context.now();
        long rest = rest(e);
        if (rest > now) {
            // Baska ozelligimiz hala yok sayiyor: oyundaki bizim sure onun kalan suresine kisaltilir
            // (e.applied > rest > now: oyundaki yok sayma bizim, baskasininkine dokunulmaz).
            if (e.applied > rest && n.isBlacklisted()) {
                n.setBlacklisted(rest - now);
                e.applied = rest;
            }
            return;
        }
        entries.remove(n.getId());
        // Sadece yok sayma bizimse (bizim bitisimiz henuz gecmemis ve baskasi da yok saymamis) acilir. Baskasinin
        // (DarkBot "baskasi vuruyor" vb.) yok saymasi bizimkiyle ust uste bindiyse acilmaz, kendi suresi dolar.
        if (!e.foreign && e.applied > now && n.isBlacklisted()) n.setBlacklisted(0);
    }

    /** Bu sahip bu NPC'yi su an yok sayiyor mu. */
    public boolean has(Npc n, Owner owner) {
        if (n == null) return false;
        Entry e = entries.get(n.getId());
        Long u = e == null ? null : e.until.get(owner);
        long now = Context.now();
        if (u == null || u <= now) return false;
        // Kaydimiz suruyor ama oyunda yok sayma yok (baskasininki bitti / kaldirildi): bizimki uygulanir.
        if (!n.isBlacklisted()) {
            e.foreign = false;
            apply(n, e, now);
        }
        return true;
    }

    /** Yok sayma sadece BotPlus'in verilen sahibinden mi (baska sahip / baskasinin yok saymasi yok). */
    public boolean onlyBy(Npc n, Owner owner) {
        if (n == null || !n.isBlacklisted()) return false;
        Entry e = entries.get(n.getId());
        if (e == null) return false;
        long now = Context.now();
        for (Map.Entry<Owner, Long> o : e.until.entrySet())
            if (o.getValue() > now && o.getKey() != owner) return false;
        Long mine = e.until.get(owner);
        // e.applied > now: oyundaki yok sayma bizim (baskasininkinin altinda sadece kaydimiz varsa degil).
        return mine != null && mine > now && e.applied > now && e.applied <= mine + 50;
    }

    private void clean(long now) {
        if (now - lastClean < 10_000) return;
        lastClean = now;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            e.until.values().removeIf(u -> u < now);
            if (e.until.isEmpty() && e.applied < now) it.remove();
        }
    }
}