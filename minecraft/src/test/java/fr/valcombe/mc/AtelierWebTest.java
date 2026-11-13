package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * L'Atelier web aligné sur les parcours en jeu : adresses des vignettes, envoi d'une carte possible partie par partie
 * (une partie en cours dans la partie principale ne bloque plus l'envoi à une partie d'essai), vignettes servies
 * par le producteur du jeu, et la page elle-même (onglets dans l'ordre de l'Atelier en jeu, routes connues du serveur,
 * textes sans tiret long).
 */
class AtelierWebTest {
	@Test
	void adresseDesVignettes() {
		Atelier.Thumb c = Atelier.thumb("vignette/carte/port-aux-brumes.png");
		assertNotNull(c);
		assertEquals("carte", c.kind());
		assertEquals("carte:port-aux-brumes", c.key());
		assertEquals("port-aux-brumes", c.id());
		Atelier.Thumb s = Atelier.thumb("vignette/structure/maison_gothique-2.png");
		assertNotNull(s);
		assertEquals("structure", s.kind());
		assertEquals("maison_gothique-2", s.id());
		// Rien d'autre : sorte inconnue, extension, chemin qui remonte, majuscules, vide, autre route de l'API.
		for (String bad : new String[] {"vignette/role/x.png", "vignette/carte/port.jpg", "vignette/carte/../secret.png",
			"vignette/carte/Port.png", "vignette/carte/.png", "vignette/carte.png", "vignette/carte/a/b.png", "donnees", "", null}) {
			assertNull(Atelier.thumb(bad), bad);
		}
	}

	@Test
	void chaquePartieASonPropreEtatDEnvoi() {
		// Le défaut de l'ancienne page : une partie en cours dans la partie principale grisait l'envoi à toutes les parties.
		String main = Atelier.sendBlocker("Partie principale", true, null, false);
		String trial = Atelier.sendBlocker("Essai du soir", false, null, false);
		assertNotNull(main);
		assertTrue(main.contains("« Partie principale »") && main.contains("entre deux parties"), main);
		assertNull(trial, "une partie d'essai au salon reçoit une carte même si la principale joue");
		String sending = Atelier.sendBlocker("Essai du soir", false, "la carte « Port »", true);
		assertTrue(sending.contains("reçoit déjà la carte « Port »"), sending);
		String busy = Atelier.sendBlocker("Essai du soir", false, null, true);
		assertTrue(busy.contains("chantier"), busy);
	}

	@Test
	void sansVignettesInstalleesRienNEstServi() {
		// Hors d'un serveur (Vignettes.install n'a pas eu lieu), la page reçoit un refus, jamais une image inventée.
		assertNull(Vignettes.job("carte:port"));
		assertNull(Vignettes.job("structure:puits"));
		// Seules les cartes et les structures se demandent depuis la page (pas les parties ni les villages).
		assertNull(Vignettes.job("partie:hub"));
		assertNull(Vignettes.job(null));
	}

	private static String page() throws IOException {
		try (InputStream in = AtelierWebTest.class.getResourceAsStream("/valcombe/atelier.html")) {
			assertNotNull(in, "page de l'Atelier dans le mod");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void lesOngletsSuiventLAtelierEnJeu() throws IOException {
		String html = page();
		// Même vocabulaire et même ordre que l'Atelier en jeu : Cartes, Structures, Parties, Rôles et scénarios.
		Matcher m = Pattern.compile("<button data-tab=\"([a-z]+)\"").matcher(html);
		StringBuilder tabs = new StringBuilder();
		while (m.find()) {
			tabs.append(m.group(1)).append(' ');
		}
		assertEquals("cartes structures parties rs ", tabs.toString());
		assertTrue(html.contains("Rôles <span class=\"long\">et scénarios</span>"));
		// Pas d'éditeur de blocs ni de plan dans le navigateur : les cartes et les structures se bâtissent en jeu.
		for (String gone : List.of("planView", "carte/repere", "structure/poser", "\"construction\"", "edition/annuler")) {
			assertFalse(html.contains(gone), "reste de l'ancienne page : " + gone);
		}
		// Ni tiret long ni demi-cadratin dans les textes.
		assertFalse(html.contains("—") || html.contains("–"), "tiret long dans la page");
	}

	@Test
	void chaqueRouteDeLaPageExisteCoteServeur() throws IOException {
		String html = page();
		String server;
		try (InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/java/fr/valcombe/mc/Atelier.java"))) {
			server = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		// Les actions que la page appelle (api("…") et act("…")) sont toutes des cas du serveur.
		Matcher m = Pattern.compile("(?:api|act)\\(\"([a-z/]+)\"").matcher(html);
		int n = 0;
		while (m.find()) {
			String route = m.group(1);
			if (route.equals("vignette/")) {
				continue;
			}
			n++;
			assertTrue(server.contains("case \"" + route + "\""), "route inconnue du serveur : " + route);
		}
		assertTrue(n >= 25, "routes trouvées dans la page : " + n);
	}
}
