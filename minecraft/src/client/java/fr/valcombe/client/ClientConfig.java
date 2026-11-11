package fr.valcombe.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/** Réglages du mod côté joueur (config/valcombe-client.json), gardés par le profil du launcher. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance;

	/** Mode daltonien : des formes en plus des couleurs (camps, votes). */
	public boolean daltonien;

	/** Conteur : rôle et camp de chaque joueur au-dessus de lui (visible à travers les murs). */
	public boolean rolesAuDessus = true;

	/** Fond sonore de la nuit (discret). Désactivable : les cloches et signaux restent. */
	public boolean ambiance = true;

	private ClientConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("valcombe-client.json");
	}

	public static ClientConfig get() {
		if (instance == null) {
			try {
				instance = Files.exists(file()) ? GSON.fromJson(Files.readString(file()), ClientConfig.class) : new ClientConfig();
			} catch (IOException | RuntimeException e) {
				instance = new ClientConfig();
			}
			if (instance == null) {
				instance = new ClientConfig();
			}
		}
		return instance;
	}

	public void save() {
		try {
			Files.writeString(file(), GSON.toJson(this));
		} catch (IOException e) {
			// réglage gardé pour la session seulement
		}
	}
}
