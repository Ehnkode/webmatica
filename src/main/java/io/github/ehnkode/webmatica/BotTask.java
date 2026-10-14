package io.github.ehnkode.webmatica;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * What the multi-step bot jobs (FetchTask, UnpackTask, FarmTask) share: one
 * command from the viewer at a time, run in phases on the client tick, frozen
 * while the viewer has paused for a nearby player or while eating (time limits
 * don't run on then), and one result reported back to the viewer at the end.
 *
 * Client thread.
 *
 * @param <P> the job's phases; {@code idle} means nothing is running
 */
abstract class BotTask<P extends Enum<P>> {

    private final P idle;
    protected P phase;
    protected String id;
    protected long phaseStart;
    protected int tick;
    private long frozenAt;
    /** The chest or shulker window the job opened, if any. */
    protected AbstractContainerMenu opened;

    protected BotTask(P idle) {
        this.idle = idle;
        this.phase = idle;
    }

    public boolean busy() {
        return phase != idle;
    }

    public void cancel() {
        if (busy()) {
            finish(false, "gestoppt");
        }
    }

    /** Start of a new command: a running one is reported as cancelled first. */
    protected void begin(String cmdId) {
        if (busy()) {
            finish(false, "abgebrochen, neuer Auftrag");
        }
        id = cmdId;
    }

    protected void setPhase(P p) {
        phase = p;
        phaseStart = System.currentTimeMillis();
    }

    public void tick(Minecraft mc) {
        if (!busy()) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            finish(false, "nicht mehr im Spiel");
            return;
        }
        if (AutoGuard.paused() || AutoGuard.eating()) {
            // player nearby or eating: wait without the time limits running on
            if (frozenAt == 0L) {
                frozenAt = System.currentTimeMillis();
            }
            return;
        }
        if (frozenAt != 0L) {
            phaseStart += System.currentTimeMillis() - frozenAt;
            frozenAt = 0L;
        }
        long age = System.currentTimeMillis() - phaseStart;
        tick++;
        step(mc, p, age);
    }

    /** One tick of the current phase; age = ms since the phase began (without frozen time). */
    protected abstract void step(Minecraft mc, LocalPlayer p, long age);

    /** End the job: build the result message and call {@link #report}. */
    protected abstract void finish(boolean ok, String why);

    /** Show the result in game, send it to the viewer, back to idle. */
    protected void report(boolean ok, String msg) {
        actionbar(Minecraft.getInstance(), msg, ok ? ChatFormatting.GREEN : ChatFormatting.RED);
        BaritoneBridge.report(id, ok, msg);
        phase = idle;
        opened = null;
    }

    protected static void actionbar(Minecraft mc, String s, ChatFormatting f) {
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(s)).withStyle(f));
        }
    }
}
