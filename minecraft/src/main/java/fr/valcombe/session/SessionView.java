package fr.valcombe.session;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vue envoyée au client (écrans sur mesure, tablette) : ce que chacun a le droit de voir. */
final class SessionView {
	private SessionView() {
	}

	// ------------------------------------------------------------------
	// Vue envoyée au client (écrans sur mesure)
	// ------------------------------------------------------------------
	/** Rôle vu pour un siège : le camp d'un voyageur est le sien, pas celui du rôle. */
	static Map<String, Object> seatRole(Game.Seat p, Role r) {
		Map<String, Object> m = new LinkedHashMap<>(roleView(r));
		if (p.traveller()) {
			m.put("team", p.team().name());
		}
		return m;
	}

	static Map<String, Object> roleView(Role r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.name());
		m.put("name", r.displayName);
		m.put("type", r.type.name());
		m.put("team", Team.of(r).name());
		m.put("text", r.text);
		return m;
	}

	/**
	 * Ce qu'un joueur a le droit de voir, sous forme d'arbre simple (sérialisé en JSON).
	 * leader : opérateur ou Conteur (accès au panneau). Le grimoire n'est inclus que pour
	 * le Conteur, ou pour le Mouchard pendant son réveil.
	 */
	static Map<String, Object> build(Session ses, String me, boolean leader) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("me", me);
		v.put("leader", leader);
		v.put("storyteller", ses.storyteller);
		v.put("isStoryteller", me.equals(ses.storyteller));
		v.put("mode", ses.mode.name());
		v.put("lobby", List.copyOf(ses.lobby));
		v.put("bots", List.copyOf(ses.bots));
		v.put("inLobby", ses.lobby.contains(me));
		v.put("paused", ses.paused);
		if (leader && ses.game == null) {
			String[] ready = ses.lobbyStatus();
			v.put("lobbyReady", ready[0].equals("oui"));
			v.put("lobbyStatus", ready[1]);
		}
		v.put("spectator", ses.spectators.contains(me));
		v.put("trainee", me.equals(ses.trainee));
		Map<String, Object> sc = new LinkedHashMap<>();
		sc.put("name", ses.scriptName);
		sc.put("roles", ses.script.stream().map(Role::name).toList());
		sc.put("saved", ses.savedScripts);
		sc.put("problem", fr.valcombe.rules.Setup.check(ses.script, Math.max(5, ses.lobby.size())));
		v.put("script", sc);
		// Rôles créés dans l'Atelier : le client remplit ses emplacements avec ces fiches.
		v.put("modVersion", ses.modVersion);
		v.put("customRoles", Role.customDefs());
		List<Map<String, Object>> figs = new ArrayList<>();
		for (var e : Session.FIGURES.entrySet()) {
			figs.add(Map.of("id", e.getKey(), "nom", e.getValue()[0], "texte", e.getValue()[1],
				"active", ses.figures.contains(e.getKey())));
		}
		v.put("figures", figs);
		if (ses.carnetSource != null && ses.game == null) {
			Map<String, Object> carnet = ses.carnetSource.apply(me);
			if (carnet != null) {
				v.put("carnet", carnet);
			}
		}
		if (ses.scenario != null) {
			v.put("scenario", Map.of("id", ses.scenario.id, "nom", ses.scenario.nom,
				"description", ses.scenario.description == null ? "" : ses.scenario.description));
		}
		List<Map<String, Object>> scs = new ArrayList<>();
		for (ScenarioDef d : ses.scenarios()) {
			List<Integer> n = d.playable();
			scs.add(Map.of("id", d.id, "nom", d.nom, "joueurs", n.isEmpty() ? "" : n.get(0) + "–" + n.get(n.size() - 1),
				"carte", d.carte == null ? "" : d.carte));
		}
		v.put("scenarios", scs);
		// Pour prévenir avant de choisir un scénario qui chargerait une autre carte que celle en place.
		v.put("mapInPlace", ses.hostMapInPlace());
		v.put("feed", ses.feed.subList(Math.max(0, ses.feed.size() - 8), ses.feed.size()));
		Map<String, Object> st = new LinkedHashMap<>();
		st.put("nightFreeze", ses.settings.nightFreeze);
		st.put("nightDark", ses.settings.nightDark);
		st.put("nightHouses", ses.settings.nightHouses);
		st.put("nightMute", ses.settings.nightMute);
		st.put("restoreMap", ses.settings.restoreMap);
		st.put("defenseSeconds", ses.settings.defenseSeconds);
		st.put("clockSeconds", ses.settings.clockTicks / 20.0);
		st.put("fullEffects", ses.settings.fullEffects);
		st.put("deadHat", ses.settings.deadHat);
		st.put("roleRoom", ses.settings.roleRoom);
		st.put("council", ses.settings.nominationsFirst);
		st.put("councilSeconds", ses.settings.councilSeconds);
		st.put("absoluteMajority", ses.settings.absoluteMajority);
		st.put("evilMajorityWins", ses.settings.evilMajorityWins);
		v.put("settings", st);
		Game g = ses.game;
		if (g == null) {
			v.put("phase", "LOBBY");
			v.put("lastWinner", ses.lastGame != null && ses.lastGame.winner() != null ? ses.lastGame.winner().name() : null);
			if (ses.lastGame != null) {
				v.put("chronicle", ses.lastGame.log());
			}
		} else {
			v.put("phase", g.phase().name());
			v.put("night", g.night());
			v.put("day", g.day());
			v.put("dayOver", g.dayOver());
			int mySeat = g.seatOf(me);
			v.put("mySeat", mySeat);
			v.put("traveller", mySeat >= 0 && g.player(mySeat).traveller());
			v.put("canTravel", mySeat < 0 && !me.equals(ses.storyteller) && g.winner() == null);
			List<Map<String, Object>> seats = new ArrayList<>();
			for (int i = 0; i < g.size(); i++) {
				Game.Seat p = g.player(i);
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("seat", i);
				s.put("name", p.name);
				s.put("alive", p.alive());
				s.put("ghostVote", p.ghostVote());
				s.put("floor", ses.hasFloor(p.name));
				s.put("bot", ses.bots.contains(p.name));
				s.put("standIn", ses.standIns.contains(p.name));
				s.put("traveller", p.traveller());
				Role claimed = ses.social.claims.get(p.name);
				s.put("claim", claimed == null ? null : claimed.name());
				seats.add(s);
			}
			v.put("seats", seats);
			if (mySeat >= 0 && ses.roleKnown(me)) {
				v.put("role", seatRole(g.player(mySeat), g.player(mySeat).believed()));
			}
			v.put("roleWaiting", mySeat >= 0 && !ses.roleKnown(me));
			v.put("summoned", ses.summoned);
			if (me.equals(ses.storyteller)) {
				v.put("unbriefed", List.copyOf(ses.unbriefed));
			}
			if (ses.voteNom != null) {
				Map<String, Object> vote = new LinkedHashMap<>();
				vote.put("nominator", g.player(ses.voteNom.nominator()).name);
				vote.put("nominee", g.player(ses.voteNom.nominee()).name);
				vote.put("stage", ses.defense ? "DEFENSE" : "CLOCK");
				vote.put("defenseLeft", ses.defenseLeft);
				vote.put("clockSeat", ses.clockPos >= 0 && ses.clockPos < ses.clockOrder.size() ? ses.clockOrder.get(ses.clockPos) : -1);
				vote.put("locked", List.copyOf(ses.lockedHands));
				vote.put("count", ses.lockedHands.size());
				vote.put("threshold", g.voteThreshold());
				vote.put("myHand", Boolean.TRUE.equals(ses.answers.get(me)));
				vote.put("canVote", ses.canVote(me));
				vote.put("order", ses.clockOrder.stream().map(i -> g.player(i).name).toList());
				vote.put("clockPos", ses.clockPos);
				vote.put("lockedNames", ses.lockedHands.stream().map(i -> g.player(i).name).toList());
				vote.put("hands", ses.answers.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
				v.put("vote", vote);
			}
			v.put("nominationsOpen", ses.nominationsOpen);
			v.put("council", ses.settings.nominationsFirst);
			v.put("councilVoting", ses.councilVoting);
			v.put("inCouncil", ses.inCouncil());
			v.put("voice", ses.voiceLevel(me));
			st.put("nameTags", ses.settings.nameTags);
			st.put("whisperRange", ses.settings.whisperRange);
			st.put("shoutRange", ses.settings.shoutRange);
			Map<String, Object> speech = ses.desk.speechView();
			if (speech != null) {
				speech.put("mine", ses.desk.queue.contains(me) || me.equals(ses.desk.speaker));
				v.put("speech", speech);
			}
			v.put("broadcast", ses.desk.broadcast);
			v.put("aside", ses.social.aside);
			if (me.equals(ses.storyteller)) {
				// Joueurs déconnectés, pour le Conteur (de nuit, leur réveil est joué au hasard).
				v.put("absents", g.players().stream().map(p -> p.name)
					.filter(n -> !ses.bots.contains(n) && !ses.host.online(n)).toList());
			}
			v.put("talkingWith", ses.social.talks.get(me));
			v.put("talkInvite", ses.social.talkInvites.get(me));
			v.put("hasStoryteller", ses.storyteller != null);
			if (me.equals(ses.storyteller) && g.phase() == Game.Phase.NIGHT && ses.pendingStep != null) {
				// Bandeau du Conteur, la nuit : qui est réveillé, et ce qu'on attend.
				Map<String, Object> ns = new LinkedHashMap<>();
				ns.put("title", ses.pendingStep.title());
				ns.put("player", ses.pendingStep.seat >= 0 ? g.player(ses.pendingStep.seat).name : "");
				ns.put("waiting", ses.pendingDecisionOpen() ? "ta décision (menu J)"
					: ses.pendingChooser != null ? ses.pendingChooser + " choisit"
					: ses.waitingAckName() != null ? ses.waitingAckName() + " lit son information" : "");
				// Carte de l'étape : ce qu'il faut faire et dire, et les mises en garde sur le joueur réveillé.
				ns.put("todo", ses.desk.todo(ses.pendingStep));
				ns.put("alerts", ses.desk.alerts(g, ses.pendingStep));
				v.put("nightStep", ns);
			}
			if (me.equals(ses.storyteller) && g.phase() == Game.Phase.NIGHT) {
				List<Map<String, Object>> order = new ArrayList<>();
				for (Game.NightEntry e : g.nightOrder()) {
					order.add(Map.of("title", e.title(), "player", e.player(), "state", e.state()));
				}
				v.put("nightOrder", order);
				v.put("awake", List.copyOf(ses.desk.awake));
			}
			if (g.phase() == Game.Phase.DAY) {
				// Tableau du jour : qui a accusé qui, avec combien de voix, et qui est sur le billot.
				Map<String, Object> day = new LinkedHashMap<>();
				List<Map<String, Object>> noms = new ArrayList<>();
				for (int[] n : g.nominationsToday()) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("by", g.player(n[0]).name);
					m.put("target", g.player(n[1]).name);
					m.put("votes", n[2]);
					m.put("voters", ses.votersOf.getOrDefault(noms.size(), List.of()));
					noms.add(m);
				}
				day.put("nominations", noms);
				day.put("block", g.onTheBlock() >= 0 ? g.player(g.onTheBlock()).name : null);
				day.put("blockVotes", g.blockVotes());
				day.put("tie", g.blockTie());
				day.put("threshold", g.voteThreshold());
				boolean forbidden = ses.scenario != null && ses.scenario.regles.pasAccusationPremierJour && g.day() == 1;
				day.put("forbidden", forbidden);
				day.put("canNominate", !forbidden && mySeat >= 0 && g.player(mySeat).alive() && !g.hasNominatedToday(mySeat));
				v.put("today", day);
			}
			boolean spyAwake = ses.prompts.get(me) instanceof Prompt.Grimoire;
			boolean watcher = ses.spectators.contains(me) && ses.settings.spectatorGrimoire;
			if (me.equals(ses.storyteller) || spyAwake || watcher) {
				List<Map<String, Object>> grim = new ArrayList<>();
				List<String> lines = g.grimoireLines();
				for (int i = 0; i < g.size(); i++) {
					Game.Seat p = g.player(i);
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("seat", i);
					row.put("role", seatRole(p, p.role()));
					double[] pos = ses.host.seatPosition(p.name);
					if (pos != null) {
						row.put("pos", List.of(pos[0], pos[1], pos[2]));
					}
					row.put("name", p.name);
					row.put("alive", p.alive());
					row.put("believed", p.believed() != p.role() ? roleView(p.believed()) : null);
					row.put("line", lines.get(i));
					row.put("note", ses.notes.get(p.name));
					row.put("reminders", ses.reminders.getOrDefault(p.name, List.of()));
					row.put("poisoned", g.isPoisoned(i));
					row.put("protected", g.protectedSeat() == i);
					row.put("redHerring", g.redHerring() == i);
					row.put("master", g.isMaster(i));
					grim.add(row);
				}
				v.put("grimoire", grim);
				if (me.equals(ses.storyteller)) {
					// Pupitre du Conteur : jetons de rappel par rôle, journal, note de partie, réponses des joueurs.
					v.put("tokens", ses.desk.tokenTray(g));
					List<String> log = g.log();
					v.put("journal", log.subList(Math.max(0, log.size() - 200), log.size()));
					v.put("gameNote", ses.notes.get(Session.GAME_NOTE));
					v.put("answers", ses.desk.answers);
				}
				v.put("bluffs", g.bluffs().stream().map(Role::name).toList());
				v.put("distWarning", g.distributionWarning());
				Map<String, Object> pol = new LinkedHashMap<>();
				pol.put("misregister", ses.policies.misregister.name());
				pol.put("mayorTarget", ses.policies.mayorTarget);
				pol.put("successor", ses.policies.successor);
				v.put("policies", pol);
			}
		}
		Prompt pr = ses.prompts.get(me);
		if (pr != null) {
			Map<String, Object> m = new LinkedHashMap<>();
			switch (pr) {
				case Prompt.Choose c -> {
					m.put("type", "choose");
					m.put("title", c.title());
					m.put("count", c.count());
					m.put("optional", c.optional());
					List<Map<String, Object>> opts = new ArrayList<>();
					for (Prompt.Option o : c.options()) {
						opts.add(Map.of("seat", o.seat(), "name", o.name(), "alive", o.alive(), "selected", o.selected()));
					}
					m.put("options", opts);
				}
				case Prompt.Info i -> {
					m.put("type", "info");
					m.put("title", i.title());
					m.put("text", i.text());
				}
				case Prompt.Grimoire gr -> m.put("type", "grimoire");
				case Prompt.Vote vo -> {
					m.put("type", "vote");
					m.put("nominee", vo.nominee());
					m.put("ghost", vo.ghost());
				}
				case Prompt.Decide dc -> {
					m.put("type", "decide");
					m.put("title", dc.title());
					m.put("question", dc.question());
					m.put("options", dc.options());
				}
				case Prompt.Confirm cf -> {
					m.put("type", "confirm");
					m.put("title", cf.title());
					m.put("to", cf.to());
					m.put("text", cf.text());
					m.put("truth", cf.truth());
				}
			}
			v.put("prompt", m);
		}
		return v;
	}
}
