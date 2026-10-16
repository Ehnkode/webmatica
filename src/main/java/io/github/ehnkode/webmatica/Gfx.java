package io.github.ehnkode.webmatica;

import net.minecraft.client.gui.Font;
import net.minecraft.world.item.ItemStack;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Drawing for the in-game menu and HUD. Minecraft 26.1 renamed GuiGraphics and its methods,
 * so screens and the HUD draw through this instead.
 */
final class Gfx {
    //? if >=26.1 {
    /*private final GuiGraphicsExtractor g;

    Gfx(GuiGraphicsExtractor g) {
        this.g = g;
    }
    *///?} else {
    private final GuiGraphics g;

    Gfx(GuiGraphics g) {
        this.g = g;
    }
    //?}

    /** Colors are ARGB: without alpha the text is invisible. */
    void text(Font f, String s, int x, int y, int argb) {
        //? if >=26.1 {
        /*g.text(f, s, x, y, argb);
        *///?} else {
        g.drawString(f, s, x, y, argb);
        //?}
    }

    void centered(Font f, String s, int x, int y, int argb) {
        //? if >=26.1 {
        /*g.centeredText(f, s, x, y, argb);
        *///?} else {
        g.drawCenteredString(f, s, x, y, argb);
        //?}
    }

    void fill(int x0, int y0, int x1, int y1, int argb) {
        g.fill(x0, y0, x1, y1, argb);
    }

    void item(ItemStack stack, int x, int y) {
        //? if >=26.1 {
        /*g.item(stack, x, y);
        *///?} else {
        g.renderItem(stack, x, y);
        //?}
    }
}
