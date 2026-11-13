package fr.valcombe.client.hd;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Rangée d'onglets posée au-dessus d'un contenu : l'onglet choisi est clair et ouvert vers le bas, les autres
 * sombres et un peu plus bas. Chaque onglet prend la largeur de son libellé ; si la rangée déborde, ils se
 * partagent la place et leurs libellés se raccourcissent. Clic ou flèches gauche et droite (avec le focus).
 */
public class HdTabs extends AbstractWidget implements HdKeyed {
	private final List<String> labels = new ArrayList<>();
	private int selected;
	private IntConsumer onChange = i -> { };

	/** Une rangée d'onglets de h unités de haut (16 conseillé). */
	public HdTabs(int x, int y, int w, int h, List<String> labels, int selected) {
		super(x, y, w, h, Component.literal("Onglets"));
		this.labels.addAll(labels);
		this.selected = Math.max(0, Math.min(labels.size() - 1, selected));
	}

	/** Appelé avec l'indice de l'onglet choisi par le joueur. */
	public HdTabs onChange(IntConsumer c) {
		this.onChange = c;
		return this;
	}

	@Override
	public String key() {
		return "onglets:" + String.join("|", labels);
	}

	public int selected() {
		return selected;
	}

	public List<String> labels() {
		return labels;
	}

	/** Choisit un onglet comme le ferait un clic (prévient onChange). */
	public void choose(int index) {
		if (index >= 0 && index < labels.size() && index != selected) {
			selected = index;
			onChange.accept(index);
		}
	}

	/** Bords des onglets en pixels réels depuis la gauche de la rangée (n + 1 valeurs). */
	private int[] edges(int scale) {
		int n = labels.size();
		int[] widths = new int[n];
		int pad = 10 * scale;
		int total = 0;
		for (int i = 0; i < n; i++) {
			widths[i] = Math.max(36 * scale, HdFonts.width(HdStyle.FORT, scale, labels.get(i)) + 2 * pad);
			total += widths[i];
		}
		int gap = scale;
		int room = getWidth() * scale - gap * Math.max(0, n - 1);
		int[] out = new int[n + 1];
		int x = 0;
		for (int i = 0; i < n; i++) {
			out[i] = x;
			int w = total > room ? room / n : widths[i];
			x += w + gap;
		}
		out[n] = x - gap;
		return out;
	}

	private int tabAt(double mouseX) {
		int scale = Hd.guiScale();
		int[] e = edges(scale);
		double rel = (mouseX - getX()) * scale;
		for (int i = 0; i < labels.size(); i++) {
			if (rel >= e[i] && rel < e[i + 1]) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!active || !visible || !isValidClickButton(event.buttonInfo()) || !isMouseOver(event.x(), event.y())) {
			return false;
		}
		int i = tabAt(event.x());
		if (i >= 0) {
			playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
			choose(i);
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active || !isFocused()) {
			return false;
		}
		if (event.isLeft()) {
			choose(Math.max(0, selected - 1));
			return true;
		}
		if (event.isRight()) {
			choose(Math.min(labels.size() - 1, selected + 1));
			return true;
		}
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		try (Hd hd = Hd.begin(g)) {
			int x = hd.px(getX());
			int y = hd.px(getY());
			int h = hd.px(getHeight());
			int[] e = edges(hd.scale());
			int hovered = isHovered() ? tabAt(hd.mouseX() / hd.scale()) : -1;
			int drop = hd.u(2);
			// Filet de bronze sous la rangée : l'onglet choisi, dessiné après, le recouvre et s'ouvre sur le contenu.
			hd.fill(x, y + h - hd.u(1), x + hd.px(getWidth()), y + h, 0xFF6E5432);
			for (int pass = 0; pass < 2; pass++) {
				for (int i = 0; i < labels.size(); i++) {
					boolean sel = i == selected;
					if (sel != (pass == 1)) {
						continue;
					}
					int tx = x + e[i];
					int tw = e[i + 1] - e[i];
					int ty = sel ? y : y + drop;
					int th = sel ? h : h - drop - hd.u(1);
					HdSprites.Sprite sp = sel ? HdSprites.ONGLET_ACTIF : i == hovered ? HdSprites.ONGLET_SURVOL : HdSprites.ONGLET;
					hd.sprite(sp, tx, ty, tw, th);
					String text = hd.ellipsize(HdStyle.FORT, labels.get(i), tw - hd.u(8));
					int lw = hd.width(HdStyle.FORT, text);
					int textY = ty + (th - hd.lineHeight(HdStyle.FORT)) / 2 + hd.u(0.5);
					if (sel) {
						hd.text(HdStyle.FORT, text, tx + (tw - lw) / 2, textY, Hd.INK);
						if (isFocused()) {
							hd.fill(tx + (tw - lw) / 2, textY + hd.lineHeight(HdStyle.FORT) - hd.u(1.5),
								tx + (tw + lw) / 2, textY + hd.lineHeight(HdStyle.FORT) - hd.u(1), Hd.WINE);
						}
					} else {
						hd.textShadow(HdStyle.FORT, text, tx + (tw - lw) / 2, textY, Hd.LIGHT, 0x90000000);
					}
				}
			}
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		if (labels.isEmpty()) {
			return;
		}
		output.add(NarratedElementType.TITLE, Component.literal("Onglet " + labels.get(selected) + ", "
			+ (selected + 1) + " sur " + labels.size()));
	}
}
