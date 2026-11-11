package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Les rôles intégrés décrits dans le format des rôles créés, pour en faire des variantes dans
 * l'Atelier (« partir d'un rôle intégré »). Seuls ceux que les modèles de pouvoir savent décrire
 * y figurent : le Fauconnier, le Châtelain et le Somnambule restent propres au moteur.
 */
public final class Builtins {
	private Builtins() {
	}

	private static Ability power(String type) {
		Ability a = new Ability();
		a.type = type;
		return a;
	}

	private static Ability power(String type, String cible) {
		Ability a = power(type);
		a.cible = cible;
		return a;
	}

	private static Ability power(String type, int nombre) {
		Ability a = power(type);
		a.nombre = nombre;
		return a;
	}

	/** La fiche modèle d'un rôle intégré, ou null s'il ne se décrit pas avec les modèles. */
	public static RoleDef template(Role r) {
		Ability a;
		List<String> tokens = new ArrayList<>();
		switch (r) {
			case COMMERE -> a = power("paire", "citadin");
			case GREFFIER -> a = power("paire", "etranger");
			case INQUISITEUR -> a = power("paire", "sbire");
			case AUBERGISTE -> a = power("adjacents");
			case VEILLEUSE -> a = power("voisins");
			case ASTROLOGUE -> {
				a = power("detecteur", "demon");
				a.nombre = 2;
				a.faussePiste = true;
				tokens.add("faux présage");
			}
			case EMBAUMEUR -> a = power("execute");
			case CHAPELAIN -> {
				a = power("protection");
				tokens.add("protégé");
			}
			case PELERINE -> {
				a = power("represailles", "citadin");
				tokens.add("pouvoir utilisé");
			}
			case ARBALETRIER -> {
				a = power("tir", "demon");
				tokens.add("a tiré");
			}
			case TEMPLIER -> a = power("immunite");
			case SONNEUR -> a = power("compte", 2);
			case CARTOGRAPHE -> a = power("distance");
			case ECUYER -> {
				a = power("maitre");
				tokens.add("maître");
			}
			case EXCOMMUNIE -> a = power("semblant");
			case MARTYR -> a = power("martyr");
			case ALCHIMISTE -> {
				a = power("poison");
				tokens.add("empoisonné");
			}
			case MOUCHARD -> {
				a = power("grimoire");
				a.aussi.add(power("semblant"));
			}
			case HERITIERE -> {
				a = power("heritier");
				tokens.add("est le Démon");
			}
			case MARGRAVE -> {
				a = power("etrangers");
				a.etrangers = 2;
			}
			case FAUSSAIRE -> {
				a = power("fausse_info");
				tokens.add("trompé cette nuit");
			}
			case GOULE -> {
				a = power("mise_a_mort");
				tokens.add("mort cette nuit");
			}
			default -> {
				return null;
			}
		}
		RoleDef d = new RoleDef();
		String key = r.name().toLowerCase(java.util.Locale.ROOT);
		d.id = key + "-bis";
		d.nom = r.displayName + " (variante)";
		d.camp = r.type.name();
		d.texte = r.text;
		d.embleme = r.icon;
		d.premiereNuit = r.firstNight > 0 ? "apres:" + key : "jamais";
		d.autresNuits = r.otherNights > 0 ? "apres:" + key : "jamais";
		d.pouvoir = a;
		d.rappels = tokens;
		return d;
	}

	/** Les fiches modèles de tous les rôles intégrés qui se décrivent avec les modèles de pouvoir. */
	public static List<RoleDef> templates() {
		List<RoleDef> out = new ArrayList<>();
		for (Role r : Role.values()) {
			if (!r.slot && r != Role.VOYAGEUR) {
				RoleDef d = template(r);
				if (d != null) {
					out.add(d);
				}
			}
		}
		return out;
	}
}
