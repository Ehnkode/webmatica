package io.github.ehnkode.webmatica;

/** Public entry for the mixins (the selection tool itself stays package-private). */
public final class SelectionHooks {
    private SelectionHooks() {
    }

    public static boolean scroll(double amount) {
        return SelectionTool.scroll(amount);
    }
}
