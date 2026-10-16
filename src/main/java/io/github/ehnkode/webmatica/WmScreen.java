package io.github.ehnkode.webmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/** Base of the in-game menu screens: version-independent drawing, German texts translated. */
abstract class WmScreen extends Screen {
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFFA0A0A0;
    static final int GOLD = 0xFFFFAA00;
    static final int GREEN = 0xFF55FF55;
    static final int RED = 0xFFFF5555;

    protected final Screen parent;

    WmScreen(String title, Screen parent) {
        super(Component.literal(Lang.tr(title)));
        this.parent = parent;
    }

    static String t(String german) {
        return Lang.tr(german);
    }

    static Component c(String german) {
        return Component.literal(Lang.tr(german));
    }

    protected Button button(String label, int x, int y, int w, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(c(label), onPress).bounds(x, y, w, 20).build());
    }

    static void tip(Button b, String german) {
        b.setTooltip(german == null ? null : Tooltip.create(c(german)));
    }

    /** Text and icons on top of the widgets. */
    protected abstract void draw(Gfx g, int mouseX, int mouseY);

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        draw(new Gfx(g), mouseX, mouseY);
    }
    *///?} else {
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        draw(new Gfx(g), mouseX, mouseY);
    }
    //?}

    @Override
    public boolean isPauseScreen() {
        return false;       // the bot keeps working while the menu is open
    }

    @Override
    public void onClose() {
        Mc.setScreen(Minecraft.getInstance(), parent);
    }
}
