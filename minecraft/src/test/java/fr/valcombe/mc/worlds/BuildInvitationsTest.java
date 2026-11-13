package fr.valcombe.mc.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.rules.GameException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Invitations à construire : inviter, retirer, « tous les ateliers », comptes reconnus, fichier ateliers.json. */
class BuildInvitationsTest {
	@TempDir
	Path tmp;

	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
	private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
	private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

	private BuildInvitations fresh() {
		BuildInvitations i = new BuildInvitations(BuildInvitations.fileIn(tmp));
		i.load();
		return i;
	}

	@Test
	void uneInvitationValantPourUnAtelierEtPasPourLesAutres() {
		BuildInvitations i = fresh();
		assertTrue(i.invite("Alice", ALICE, "atelier-carte-1", "Yoann", NOW));
		assertTrue(i.invited(ALICE, "Alice", "atelier-carte-1"));
		assertFalse(i.invited(ALICE, "Alice", "atelier-carte-2"));
		assertFalse(i.invited(BOB, "Bob", "atelier-carte-1"));
		assertTrue(i.invitedSomewhere(ALICE, "Alice"));
		assertFalse(i.invitedSomewhere(BOB, "Bob"));
		assertEquals(List.of("Alice"), i.guests("atelier-carte-1"));
		assertEquals(List.of(), i.guests("atelier-carte-2"));
		assertFalse(i.invite("Alice", ALICE, "atelier-carte-1", "Yoann", NOW), "déjà invitée : rien de nouveau");
		assertEquals(1, i.list().size());
	}

	@Test
	void tousLesAteliersEtRetraitDeLInvitation() {
		BuildInvitations i = fresh();
		i.invite("Bob", BOB, BuildInvitations.ALL, "Yoann", NOW);
		i.invite("Bob", BOB, "atelier-structures", "Yoann", NOW);
		assertTrue(i.invited(BOB, "Bob", "atelier-carte-7"), "tous les ateliers, même un nouveau");
		assertEquals(List.of("Bob"), i.guests("atelier-structures"), "sans doublon");
		// Retirer « tous » laisse l'invitation à l'atelier structures.
		assertEquals(1, i.remove("Bob", BOB, BuildInvitations.ALL));
		assertFalse(i.invited(BOB, "Bob", "atelier-carte-7"));
		assertTrue(i.invited(BOB, "Bob", "atelier-structures"));
		// Sans atelier : toutes ses invitations.
		i.invite("Bob", BOB, "atelier-carte-1", "Yoann", NOW);
		assertEquals(2, i.remove("Bob", null, null));
		assertFalse(i.invitedSomewhere(BOB, "Bob"));
		assertEquals(0, i.remove("Bob", BOB, null), "plus rien à retirer");
	}

	@Test
	void unJoueurInviteHorsLigneEstReconnuASaVenuePuisMemeRenomme() {
		BuildInvitations i = fresh();
		i.invite("alice", null, "atelier-carte-1", "Yoann", NOW);
		assertTrue(i.invited(ALICE, "Alice", "atelier-carte-1"), "par son nom, sans souci des majuscules");
		assertNull(i.list().get(0).uuid());
		assertTrue(i.noteJoin(ALICE, "Alice"));
		assertEquals(ALICE.toString(), i.list().get(0).uuid());
		assertEquals("Alice", i.list().get(0).joueur(), "nom tel qu'il est vraiment écrit");
		// Elle change de nom : son compte la fait reconnaître, son nouveau nom est noté.
		assertTrue(i.invited(ALICE, "Alicia", "atelier-carte-1"));
		assertFalse(i.invited(BOB, "Alice", "atelier-carte-1"), "un autre compte au même nom n'est pas elle");
		assertTrue(i.noteJoin(ALICE, "Alicia"));
		assertEquals(List.of("Alicia"), i.guests("atelier-carte-1"));
		assertFalse(i.noteJoin(ALICE, "Alicia"), "rien de changé");
	}

	@Test
	void unAtelierSupprimeEmporteSesInvitations() {
		BuildInvitations i = fresh();
		i.invite("Alice", ALICE, "atelier-carte-1", "Yoann", NOW);
		i.invite("Bob", BOB, BuildInvitations.ALL, "Yoann", NOW);
		i.forgetWorld("atelier-carte-1");
		assertFalse(i.invited(ALICE, "Alice", "atelier-carte-1"));
		assertTrue(i.invited(BOB, "Bob", "atelier-carte-1"), "l'invitation à tous les ateliers reste");
	}

	@Test
	void leFichierEstRelu() throws Exception {
		BuildInvitations i = fresh();
		i.invite("Alice", ALICE, "atelier-carte-1", "Yoann", NOW);
		i.invite("Bob", null, BuildInvitations.ALL, "la console", NOW);
		i.setWorldEditInTrials(true);
		Path f = BuildInvitations.fileIn(tmp);
		assertEquals(tmp.resolve("config").resolve("valcombe").resolve("ateliers.json"), f);
		JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		assertTrue(o.get("worldEditEssais").getAsBoolean());
		assertEquals(2, o.getAsJsonArray("invitations").size());
		assertEquals("*", o.getAsJsonArray("invitations").get(1).getAsJsonObject().get("monde").getAsString());

		BuildInvitations again = fresh();
		assertTrue(again.worldEditInTrials());
		assertEquals(2, again.list().size());
		assertTrue(again.invited(ALICE, "Alice", "atelier-carte-1"));
		assertTrue(again.invited(BOB, "Bob", "atelier-structures"));
		assertEquals(NOW, again.list().get(0).le());
		assertEquals("Yoann", again.list().get(0).par());
	}

	@Test
	void unFichierAbimeNeCassePasLeServeur() throws Exception {
		Path f = BuildInvitations.fileIn(tmp);
		Files.createDirectories(f.getParent());
		Files.writeString(f, "{\"worldEditEssais\": true, \"invitations\": [{\"joueur\": \"Al ice\", \"monde\": \"x\"}, "
			+ "{\"joueur\": \"Bob\", \"monde\": \"atelier-carte-1\", \"le\": \"hier\"}, {\"monde\": \"y\"}]}", StandardCharsets.UTF_8);
		BuildInvitations i = fresh();
		assertTrue(i.worldEditInTrials());
		assertEquals(1, i.list().size(), "les lignes abîmées sont ignorées");
		assertEquals(Instant.EPOCH, i.list().get(0).le(), "date illisible");
		Files.writeString(f, "pas du json", StandardCharsets.UTF_8);
		assertEquals(0, fresh().list().size());
	}

	@Test
	void seulsDesNomsDeJoueursSontInvites() {
		BuildInvitations i = fresh();
		for (String bad : new String[] {"", "Al ice", "trop-long-pour-minecraft", "../x", "Élodie"}) {
			assertThrows(GameException.class, () -> i.invite(bad, null, "atelier-carte-1", "Yoann", NOW), bad);
		}
		assertEquals("personne", BuildInvitations.names(List.of()));
		assertEquals("Alice", BuildInvitations.names(List.of("Alice")));
		assertEquals("Alice, Bob et Chloé", BuildInvitations.names(List.of("Alice", "Bob", "Chloé")));
	}
}
