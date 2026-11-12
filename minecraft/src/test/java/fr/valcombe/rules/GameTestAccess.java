package fr.valcombe.rules;

/** Accès aux parties à rôles imposés de GameTest, pour les tests des autres paquets. */
public final class GameTestAccess {
	private GameTestAccess() {
	}

	public static Game fixed(Object... roles) {
		return GameTest.fixed(roles);
	}
}
