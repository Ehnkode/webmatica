package io.github.ehnkode.webmatica;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

/** Search all remembered chests of this server; mark, walk to or fetch from the chest. */
final class ChestSearchScreen extends WmScreen {
    private static final int ROW = 22;
    private static final int BTN = 54;

    private String query;
    private EditBox box;
    private final List<Button[]> rows = new ArrayList<>();
    private List<ChestIndex.Hit> hits = List.of();
    private int scroll;
    private int left;
    private int listW;
    private int listTop;
    private boolean wasLoaded;

    ChestSearchScreen(Screen parent, String query) {
        super("Kisten durchsuchen", parent);
        this.query = query == null ? "" : query;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        ChestIndex.clearNames();         // game language may have changed
        ChestIndex.refresh(mc, false);
        listW = Math.min(width - 20, 440);
        left = (width - listW) / 2;
        box = new EditBox(font, left, 24, listW, 20, c("Suche"));
        box.setMaxLength(64);
        box.setHint(c("Item suchen, z. B. Diamant"));
        box.setValue(query);
        box.setResponder(s -> {
            query = s;
            scroll = 0;
            update();
        });
        addRenderableWidget(box);
        setInitialFocus(box);

        listTop = 52;
        int visible = Math.max(1, (height - listTop - 40) / ROW);
        rows.clear();
        for (int i = 0; i < visible; i++) {
            int y = listTop + i * ROW;
            int bx = left + listW - 3 * BTN - 8;
            final int row = i;
            Button mark = button("Markieren", bx, y, BTN, b -> act(row, 0));
            Button walk = button("Hinlaufen", bx + BTN + 4, y, BTN, b -> act(row, 1));
            Button fetch = button("Holen", bx + 2 * (BTN + 4), y, BTN, b -> act(row, 2));
            rows.add(new Button[] {mark, walk, fetch});
        }
        button("Zurück", (width - 150) / 2, height - 28, 150, b -> onClose());
        update();
    }

    private void act(int row, int what) {
        int i = scroll + row;
        if (i >= hits.size()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ChestIndex.Hit h = hits.get(i);
        Mc.setScreen(mc, null);
        switch (what) {
            case 0 -> IngameActions.highlight(mc, h);
            case 1 -> IngameActions.walkTo(mc, h);
            default -> IngameActions.fetch(mc, h);
        }
    }

    private void update() {
        hits = ChestIndex.search(box.getValue(), 200);
        scroll = Math.max(0, Math.min(scroll, hits.size() - rows.size()));
        String here = IngameActions.dim(Minecraft.getInstance());
        for (int r = 0; r < rows.size(); r++) {
            int i = scroll + r;
            Button[] b = rows.get(r);
            boolean show = i < hits.size();
            for (Button x : b) {
                x.visible = show;
            }
            if (!show) {
                continue;
            }
            ChestIndex.Hit h = hits.get(i);
            boolean sameDim = h.ender() || h.dim.equals(here);
            b[0].active = sameDim;
            b[1].active = sameDim && !ServerSafe.on();
            b[2].active = sameDim && !ServerSafe.on();
            String why = !sameDim ? "Kiste ist in einer anderen Dimension"
                    : ServerSafe.on() ? "Server-safe ist an: Bot-Funktionen sind aus" : null;
            tip(b[1], why);
            tip(b[2], why != null ? why : h.shulkerSlot ? "Holt die ganze Shulker-Kiste" : null);
        }
    }

    @Override
    public void tick() {
        boolean loaded = ChestIndex.loaded(Minecraft.getInstance());
        if (loaded != wasLoaded) {
            wasLoaded = loaded;
            update();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, hits.size() - rows.size());
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        if (next != scroll) {
            scroll = next;
            update();
        }
        return true;
    }

    @Override
    protected void draw(Gfx g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        g.centered(font, title.getString(), width / 2, 10, WHITE);
        String q = box.getValue().trim();
        if (!ChestIndex.loaded(mc)) {
            g.centered(font, t("Kisten werden geladen …"), width / 2, listTop + 6, GRAY);
            return;
        }
        if (q.isEmpty()) {
            g.centered(font, t(ChestIndex.chestCount() + " Kisten bekannt. Öffne Kisten im Spiel, damit Webmatica sie sich merkt."),
                    width / 2, listTop + 6, GRAY);
            return;
        }
        if (hits.isEmpty()) {
            g.centered(font, t("Nichts gefunden"), width / 2, listTop + 6, GRAY);
            return;
        }
        LocalPlayer p = mc.player;
        int textW = listW - 3 * BTN - 8 - 26;
        for (int r = 0; r < rows.size() && scroll + r < hits.size(); r++) {
            ChestIndex.Hit h = hits.get(scroll + r);
            int y = listTop + r * ROW;
            g.fill(left, y - 1, left + listW, y + ROW - 3, 0x40000000);
            g.item(ChestIndex.stack(h.id), left + 2, y + 1);
            String name = (h.name != null ? h.name : ChestIndex.displayName(h.id)) + "  ×" + h.count;
            g.text(font, font.plainSubstrByWidth(name, textW), left + 22, y + 1, WHITE);
            StringBuilder where = new StringBuilder(h.x + " " + h.y + " " + h.z);
            if (p != null && h.dim.equals(IngameActions.dim(mc))) {
                where.append(" · ").append((int) Math.sqrt(p.distanceToSqr(h.x + 0.5, h.y + 0.5, h.z + 0.5))).append(" m");
            } else if (!h.ender()) {
                where.append(" · ").append(h.dim);
            }
            if (h.ender()) {
                where.append(" · ").append(t("Endertruhe"));
            }
            if (h.inShulker) {
                where.append(" · ").append(t("in Shulker"));
            }
            g.text(font, font.plainSubstrByWidth(where.toString(), textW), left + 22, y + 11, GRAY);
        }
        if (hits.size() > rows.size()) {
            g.centered(font, t((scroll + 1) + "–" + Math.min(hits.size(), scroll + rows.size()) + " von " + hits.size()
                    + " · Mausrad blättert"), width / 2, height - 40, GRAY);
        }
    }
}
