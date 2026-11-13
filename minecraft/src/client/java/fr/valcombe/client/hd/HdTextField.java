package fr.valcombe.client.hd;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Champ de saisie d'une ligne : curseur clignotant, sélection à la souris et au clavier (Maj, Ctrl pour les mots),
 * Ctrl+A, C, X, V, Début, Fin, Entrée. Le texte défile quand il dépasse. Avec une clé (key), le texte tapé et le
 * curseur survivent aux reconstructions de l'écran (une nouvelle vue du serveur n'efface pas la saisie).
 */
public class HdTextField extends AbstractWidget implements HdKeyed {
	private record Memory(String text, int cursor, int anchor) {
	}

	private static final Map<String, Memory> MEMORY = new HashMap<>();
	/** Marge intérieure, en unités. */
	private static final int PAD = 5;

	private final TextEditor editor = new TextEditor();
	private HdStyle style = HdStyle.TEXTE;
	private String hint = "";
	private String key;
	private Consumer<String> responder = s -> { };
	private Runnable onEnter = () -> { };
	/** Décalage horizontal du texte, en pixels réels (pour garder le curseur visible). */
	private int scrollPx;
	private int scrollScale;
	private boolean selecting;
	private long blinkFrom;

	public HdTextField(int x, int y, int w, int h, String narration) {
		super(x, y, w, h, Component.literal(narration));
	}

	public String value() {
		return editor.text();
	}

	/** Remplace le texte (sans prévenir le répondant). */
	public HdTextField value(String text) {
		editor.setText(text);
		remember();
		return this;
	}

	public HdTextField maxLength(int max) {
		editor.maxLength(max);
		return this;
	}

	/** N'accepte que les textes qui passent ce filtre. */
	public HdTextField filter(Predicate<String> accept) {
		editor.filter(accept);
		return this;
	}

	/** Indication grisée quand le champ est vide. */
	public HdTextField hint(String text) {
		this.hint = text == null ? "" : text;
		return this;
	}

	public HdTextField style(HdStyle s) {
		this.style = s;
		return this;
	}

	/** Appelé à chaque changement du texte. */
	public HdTextField responder(Consumer<String> c) {
		this.responder = c;
		return this;
	}

	/** Appelé sur Entrée. */
	public HdTextField onEnter(Runnable r) {
		this.onEnter = r;
		return this;
	}

	/** Garde le texte et le curseur sous cette clé (à poser après value() pour reprendre la saisie en cours). */
	public HdTextField key(String k) {
		this.key = k;
		Memory m = k == null ? null : MEMORY.get(k);
		if (m != null) {
			editor.setText(m.text());
			editor.moveTo(m.anchor(), false);
			editor.moveTo(m.cursor(), true);
		}
		return this;
	}

	@Override
	public String key() {
		return key;
	}

	/** Oublie la saisie gardée sous cette clé (après l'avoir envoyée, par exemple). */
	public static void forget(String key) {
		MEMORY.remove(key);
	}

	public TextEditor editor() {
		return editor;
	}

	private void remember() {
		if (key != null) {
			MEMORY.put(key, new Memory(editor.text(), editor.cursor(), editor.anchor()));
		}
	}

	private void changed() {
		remember();
		blinkFrom = Util.getMillis();
		responder.accept(editor.text());
	}

	private boolean editable() {
		return active && visible && isFocused();
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		blinkFrom = Util.getMillis();
	}

	@Override
	public boolean capturesInput() {
		return editable();
	}

	// ------------------------------------------------------------------
	// Clavier
	// ------------------------------------------------------------------
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!editable()) {
			return false;
		}
		boolean shift = event.hasShiftDown();
		boolean word = event.hasControlDownWithQuirk();
		var keyboard = Minecraft.getInstance().keyboardHandler;
		int code = event.input();
		String before = editor.text();
		if (event.isSelectAll()) {
			editor.selectAll();
		} else if (event.isCopy()) {
			if (editor.hasSelection()) {
				keyboard.setClipboard(editor.selected());
			}
		} else if (event.isCut()) {
			if (editor.hasSelection()) {
				keyboard.setClipboard(editor.selected());
				editor.insert("");
			}
		} else if (event.isPaste()) {
			editor.insert(keyboard.getClipboard());
		} else if (code == InputConstants.KEY_BACKSPACE) {
			editor.delete(-1, word);
		} else if (code == InputConstants.KEY_DELETE) {
			editor.delete(1, word);
		} else if (event.isLeft()) {
			editor.move(-1, word, shift);
		} else if (event.isRight()) {
			editor.move(1, word, shift);
		} else if (code == InputConstants.KEY_HOME) {
			editor.home(shift);
		} else if (code == InputConstants.KEY_END) {
			editor.end(shift);
		} else if (event.isConfirmation()) {
			onEnter.run();
		} else {
			return false;
		}
		if (!editor.text().equals(before)) {
			changed();
		} else {
			remember();
			blinkFrom = Util.getMillis();
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!editable() || !event.isAllowedChatCharacter()) {
			return false;
		}
		if (editor.insert(event.codepointAsString())) {
			changed();
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Souris
	// ------------------------------------------------------------------
	/** Indice du caractère sous l'abscisse d'interface mx. */
	private int indexAtMouse(double mx) {
		int scale = Hd.guiScale();
		double rel = (mx - getX() - PAD) * scale + scrollPx;
		return HdFonts.indexAt(style, scale, editor.text(), rel);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!active || !visible || !isValidClickButton(event.buttonInfo()) || !isMouseOver(event.x(), event.y())) {
			return false;
		}
		if (doubleClick) {
			editor.selectAll();
		} else {
			editor.moveTo(indexAtMouse(event.x()), event.hasShiftDown());
			selecting = true;
		}
		remember();
		blinkFrom = Util.getMillis();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (selecting) {
			editor.moveTo(indexAtMouse(event.x()), true);
			remember();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean was = selecting;
		selecting = false;
		return was;
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		try (Hd hd = Hd.begin(g)) {
			if (hd.scale() != scrollScale) {
				scrollScale = hd.scale();
				scrollPx = 0;
			}
			int x = hd.px(getX());
			int y = hd.px(getY());
			int w = hd.px(getWidth());
			int h = hd.px(getHeight());
			hd.sprite(isFocused() ? HdSprites.CHAMP_ACTIF : HdSprites.CHAMP, x, y, w, h, active ? -1 : 0xFFA09890);
			int x0 = hd.px(getX() + PAD);
			int x1 = hd.px(getX() + getWidth() - PAD);
			int lh = hd.lineHeight(style);
			int ty = y + (h - lh) / 2;
			String text = editor.text();
			if (text.isEmpty() && !isFocused()) {
				hd.text(style, hd.ellipsize(style, hint, x1 - x0), x0, ty, Hd.MUTED);
				return;
			}
			// Garde le curseur dans la fenêtre, avec un peu d'air.
			int cursorPx = hd.width(style, text.substring(0, editor.cursor()));
			int room = x1 - x0;
			int margin = Math.min(hd.u(8), room / 4);
			if (cursorPx - scrollPx > room - margin) {
				scrollPx = cursorPx - room + margin;
			} else if (cursorPx - scrollPx < margin) {
				scrollPx = Math.max(0, cursorPx - margin);
			}
			int total = hd.width(style, text);
			scrollPx = Math.max(0, Math.min(scrollPx, Math.max(0, total - room + hd.u(1))));
			hd.clip(hd.px(getX() + 2), y, hd.px(getX() + getWidth() - 2), y + h);
			int tx = x0 - scrollPx;
			if (editor.hasSelection()) {
				int a = hd.width(style, text.substring(0, editor.selectionStart()));
				int b = hd.width(style, text.substring(0, editor.selectionEnd()));
				hd.fill(tx + a, ty, tx + b, ty + lh, Hd.TEXT_SELECTION);
			}
			hd.text(style, text, tx, ty, active ? Hd.LIGHT : Hd.MUTED);
			if (isFocused() && ((Util.getMillis() - blinkFrom) / 530) % 2 == 0) {
				int cx = tx + cursorPx;
				int cw = Math.max(1, hd.u(0.6));
				hd.fill(cx, ty + hd.u(1), cx + cw, ty + lh - hd.u(1), Hd.GOLD);
			}
			hd.unclip();
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal(getMessage().getString() + " : "
			+ (editor.text().isEmpty() ? hint : editor.text())));
	}
}
