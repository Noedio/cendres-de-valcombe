package fr.valcombe.session;

import java.util.ArrayList;
import java.util.List;

/** Message riche indépendant de Minecraft : texte et boutons cliquables. */
public final class Msg {
	/** command : commande lancée au clic ; suggest : la placer dans le chat sans l'exécuter. */
	public record Part(String text, String color, String command, boolean suggest, String hover) {
	}

	public final List<Part> parts = new ArrayList<>();

	public static Msg of(String text) {
		return new Msg().text(text);
	}

	public Msg text(String text) {
		parts.add(new Part(text, null, null, false, null));
		return this;
	}

	public Msg colored(String text, String color) {
		parts.add(new Part(text, color, null, false, null));
		return this;
	}

	public Msg button(String label, String command, String hover) {
		parts.add(new Part(" [" + label + "]", "gold", command, false, hover));
		return this;
	}

	public Msg suggest(String label, String command, String hover) {
		parts.add(new Part(" [" + label + "]", "aqua", command, true, hover));
		return this;
	}

	public String plain() {
		StringBuilder sb = new StringBuilder();
		for (Part p : parts) {
			sb.append(p.text());
		}
		return sb.toString();
	}
}
