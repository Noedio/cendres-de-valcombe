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
		v.put("spectator", ses.spectators.contains(me));
		v.put("trainee", me.equals(ses.trainee));
		Map<String, Object> sc = new LinkedHashMap<>();
		sc.put("name", ses.scriptName);
		sc.put("roles", ses.script.stream().map(Role::name).toList());
		sc.put("saved", ses.savedScripts);
		sc.put("problem", fr.valcombe.rules.Setup.check(ses.script, Math.max(5, ses.lobby.size())));
		v.put("script", sc);
		v.put("feed", ses.feed.subList(Math.max(0, ses.feed.size() - 8), ses.feed.size()));
		Map<String, Object> st = new LinkedHashMap<>();
		st.put("nightFreeze", ses.settings.nightFreeze);
		st.put("nightDark", ses.settings.nightDark);
		st.put("nightHouses", ses.settings.nightHouses);
		st.put("nightMute", ses.settings.nightMute);
		st.put("defenseSeconds", ses.settings.defenseSeconds);
		st.put("clockSeconds", ses.settings.clockTicks / 20.0);
		st.put("fullEffects", ses.settings.fullEffects);
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
			List<Map<String, Object>> seats = new ArrayList<>();
			for (int i = 0; i < g.size(); i++) {
				Game.Seat p = g.player(i);
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("seat", i);
				s.put("name", p.name);
				s.put("alive", p.alive());
				s.put("ghostVote", p.ghostVote());
				s.put("bot", ses.bots.contains(p.name));
				seats.add(s);
			}
			v.put("seats", seats);
			if (mySeat >= 0) {
				v.put("role", roleView(g.player(mySeat).believed()));
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
				vote.put("threshold", (g.aliveCount() + 1) / 2);
				vote.put("myHand", Boolean.TRUE.equals(ses.answers.get(me)));
				vote.put("hands", ses.answers.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
				v.put("vote", vote);
			}
			v.put("nominationsOpen", ses.nominationsOpen);
			boolean spyAwake = ses.prompts.get(me) instanceof Prompt.Grimoire;
			boolean watcher = ses.spectators.contains(me) && ses.settings.spectatorGrimoire;
			if (me.equals(ses.storyteller) || spyAwake || watcher) {
				List<Map<String, Object>> grim = new ArrayList<>();
				List<String> lines = g.grimoireLines();
				for (int i = 0; i < g.size(); i++) {
					Game.Seat p = g.player(i);
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("seat", i);
					row.put("role", roleView(p.role()));
					row.put("believed", p.believed() != p.role() ? roleView(p.believed()) : null);
					row.put("line", lines.get(i));
					row.put("note", ses.notes.get(p.name));
					row.put("reminders", ses.reminders.getOrDefault(p.name, List.of()));
					row.put("poisoned", g.poisonedSeat() == i);
					row.put("protected", g.protectedSeat() == i);
					row.put("redHerring", g.redHerring() == i);
					row.put("master", g.masterSeat() == i);
					grim.add(row);
				}
				v.put("grimoire", grim);
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
