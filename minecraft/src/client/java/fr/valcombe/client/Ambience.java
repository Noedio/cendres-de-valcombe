package fr.valcombe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Ambiances en boucle (jour, nuit) et sons de Valcombe joués côté client. */
public final class Ambience {
	private static SoundInstance loop;
	private static String current = "";

	private Ambience() {
	}

	static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath("valcombe", name);
	}

	/** Un son de l'interface (non situé dans le monde). */
	public static void play(String name, float volume, float pitch) {
		Minecraft.getInstance().getSoundManager().play(
			SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id(name)), pitch, volume));
	}

	/** Change l'ambiance selon la phase de la partie. */
	static void onPhase(String phase) {
		String wanted = switch (phase) {
			case "NIGHT" -> "ambiance_nuit";
			case "DAY", "READY" -> "ambiance_jour";
			default -> "";
		};
		if (wanted.equals(current)) {
			return;
		}
		var sounds = Minecraft.getInstance().getSoundManager();
		if (loop != null) {
			sounds.stop(loop);
			loop = null;
		}
		current = wanted;
		if (!wanted.isEmpty()) {
			loop = new SimpleSoundInstance(id(wanted), SoundSource.AMBIENT, 0.45f, 1f, RandomSource.create(), true, 0,
				SoundInstance.Attenuation.NONE, 0, 0, 0, true);
			sounds.play(loop);
		}
	}
}
