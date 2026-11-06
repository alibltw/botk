package com.botplus.locator;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.enums.PetGear;
import eu.darkbot.api.game.other.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kesim Sonrasi Bulucuyu Tazele (deneme): NPC olunce oyunun mini haritasindaki bulucu isareti bir sure olu NPC'nin
 * yerinde kaliyor (DarkBot isareti mini haritadan okur, kendisi tutmaz). Sunucuyu yeni konum gondermeye zorlamak icin
 * PET kisa sure pasif moda alinip bulucuya geri doner.
 * Savasta PET'e dokunulmaz: tazeleme ancak gemi 4 sn savas disinda kalinca yapilir; o ana kadar yeni isaret geldiyse
 * hic yapilmaz. Olumden gecerli yeni isarete kadar gecen sure olculur (ayrintili log).
 * 2.1.0: kullanilmayan "tur degistir" yontemi ve A/B olcumu kaldirildi (ayarlar hep pasif / A/B kapali veriyordu).
 */
public final class LocatorRefresh {

    /** Pasif yontemde PET en fazla bu kadar pasifte kalir. */
    private static final long OFF_MAX_MS = 1500;
    /** Savas bu kadar surede bitmezse tazeleme yapilmaz. */
    private static final long WAIT_MAX_MS = 15_000;
    /** Olumden sonra bu surede yeni isaret gelmezse olcum atilir (harita bos). */
    private static final long MEASURE_MAX_MS = 30_000;

    private enum Phase { NONE, WAIT, OFF }

    private final Context context;
    private Phase phase = Phase.NONE;
    private long phaseAt;
    /** Bu kesimde tazeleme yapildi mi (savas surdugu / gerek kalmadigi icin yapilmamis olabilir). */
    private boolean done;
    private boolean measuring;
    private long killAt;
    private Location deadAt;
    /** Olcumu bozan PET modulu (kombo tamir, HP Link vb.): kesim sayilmaz. */
    private PetGear dirty;
    /** Kesim anindaki PET modulu (savas modulu vb.): olcumu bozmus sayilmaz. */
    private PetGear killGear;
    private int mapEpoch = Integer.MIN_VALUE;
    /** PET bulucudan cikti mi. */
    private boolean offSeen;
    private final List<Long> times = new ArrayList<>();
    private int skipped, measured;

    public LocatorRefresh(Context context) {
        this.context = context;
    }

    private boolean enabled() {
        // Enemy Locator -> "NPC Patlayinca Dusman Bulucuyu Yenile" (deneme, varsayilan kapali).
        return context.config.LOCATOR.REFRESH;
    }

    /** NPC oldu (Combat Plus / BotPlus kesim yeri). */
    public void onKill(Location at) {
        phase = Phase.NONE;
        if (!enabled() || at == null) {
            measuring = false;
            return;
        }
        killAt = Context.now();
        deadAt = Location.of(at.getX(), at.getY());
        measuring = true;
        dirty = null;
        killGear = gearNow();
        done = false;
        offSeen = false;
        phase = Phase.WAIT;
        phaseAt = killAt;
        Log.d("BULUCU", "kesim sonrasi bulucu tazeleme planlandi, savas disinda " + Context.PET_QUIET_MS / 1000 + " sn bekleniyor");
    }

    /** PET modulu istegi (BotPlus PET secicisi): tazelemede PASSIVE, aksi halde null. */
    public PetGear gear() {
        long now = Context.now();
        switch (phase) {
            case WAIT: {
                if (!measuring || now - killAt < Context.PET_QUIET_MS || !context.outOfCombat(Context.PET_QUIET_MS)) return null;
                // Gidilebilir isaret zaten var: tazelemeye gerek yok.
                Location usable = context.ping().filter(context.pingOk::test).orElse(null);
                if (usable != null) {
                    phase = Phase.NONE;
                    Log.d("BULUCU", "tazeleme yapilmadi: gecerli isaret var " + Log.loc(usable));
                    return null;
                }
                PetGear cur = gearNow();
                if (cur != PetGear.ENEMY_LOCATOR) {
                    // Savas modulunden bulucuya donus bekleniyor (en fazla WAIT_MAX_MS, advance bitirir).
                    Log.dEvery("refreshwaitpet", 2000, "BULUCU", "tazeleme bekliyor: PET bulucuda degil (" + cur + ")");
                    return null;
                }
                done = true;
                phaseAt = now;
                phase = Phase.OFF;
                offSeen = false;
                Log.d("BULUCU", "savas disinda " + context.quietMs() / 1000 + " sn: PET pasife aliniyor");
                return PetGear.PASSIVE;
            }
            case OFF:
                return offSeen ? null : PetGear.PASSIVE; // pasife gectiyse normal secim bulucuya dondurur
            default:
                return null;
        }
    }

    /** Asama gecisleri (her tik; PET secici cagrilmasa da pasif takili kalmaz). */
    private void advance(long now) {
        switch (phase) {
            case WAIT:
                if (!measuring) phase = Phase.NONE; // yeni isaret geldi: tazelemeye gerek yok
                else if (now - killAt > WAIT_MAX_MS) {
                    phase = Phase.NONE;
                    Log.d("BULUCU", "tazeleme yapilmadi: " + WAIT_MAX_MS / 1000 + " sn savas bitmedi (" + context.combatWhy() + ")");
                }
                break;
            case OFF: {
                PetGear cur = gearNow();
                if (!offSeen && cur != null && cur != PetGear.ENEMY_LOCATOR) {
                    offSeen = true;
                    Log.d("BULUCU", "PET pasife gecti (" + cur + ", " + (now - phaseAt) + " ms), bulucuya donuluyor");
                }
                if (offSeen && cur == PetGear.ENEMY_LOCATOR) {
                    phase = Phase.NONE;
                    Log.d("BULUCU", "PET bulucuya dondu (" + (now - phaseAt) + " ms)");
                } else if (now - phaseAt > (offSeen ? 4000 : OFF_MAX_MS)) {
                    if (!offSeen) Log.d("BULUCU", "PET " + OFF_MAX_MS + " ms'de pasife gecmedi, bulucuya donuluyor");
                    phase = Phase.NONE;
                }
                break;
            }
            default:
        }
    }

    /** Her tik: olcum. */
    public void tick() {
        long now = Context.now();
        int e = context.mapEpoch();
        if (e != mapEpoch) {
            if (mapEpoch != Integer.MIN_VALUE) {
                if (measuring || phase != Phase.NONE)
                    Log.d("BULUCU", "harita degisti: bulucu tazeleme / olcum birakildi (" + phase + ")");
                measuring = false;
                phase = Phase.NONE;
            }
            mapEpoch = e;
        }
        advance(now);
        if (!measuring) return;
        if (now - killAt > MEASURE_MAX_MS) {
            measuring = false;
            skipped++;
            Log.d("BULUCU", "kesimden " + MEASURE_MAX_MS / 1000 + " sn yeni isaret yok, olcum sayilmadi");
            return;
        }
        PetGear g = gearNow();
        if (g != null && g != PetGear.ENEMY_LOCATOR && g != killGear
                && !(g == PetGear.PASSIVE && (phase == Phase.OFF || offSeen)) && dirty == null)
            dirty = g;
        Location live = context.livePing();
        if (live == null || deadAt == null || live.distanceTo(deadAt) < context.config.LOCATOR.DEAD_R) return;
        long ms = now - killAt;
        measuring = false;
        if (phase == Phase.WAIT) phase = Phase.NONE;
        String how = done ? "tazelemeli" : "savas surdu / gerek kalmadi";
        if (dirty != null) {
            skipped++;
            Log.d("BULUCU", "kesimden yeni isarete " + ms + " ms (" + how + ") - sayilmadi: PET baska iste (" + dirty + ")");
            return;
        }
        times.add(ms);
        if (times.size() > 200) times.remove(0);
        Log.d("BULUCU", "kesimden yeni isarete " + ms + " ms (" + how + ")");
        if (++measured % 10 == 0) Log.i("BULUCU TAZELEME", report());
    }

    private PetGear gearNow() {
        try {
            return context.pet.isActive() ? context.pet.getGear() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ozet. */
    public String report() {
        if (times.isEmpty()) return "kesimden yeni isarete: veri yok | sayilmayan " + skipped;
        List<Long> s = new ArrayList<>(times);
        Collections.sort(s);
        long sum = 0;
        for (long v : s) sum += v;
        return "kesimden yeni isarete: " + s.size() + " kesim, ortanca " + s.get(s.size() / 2) / 100 / 10.0 + " sn, ortalama "
                + sum / s.size() / 100 / 10.0 + " sn | sayilmayan " + skipped;
    }
}