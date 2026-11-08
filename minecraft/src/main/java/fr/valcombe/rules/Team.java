package fr.valcombe.rules;

public enum Team {
	GOOD("le bien"),
	EVIL("le mal");

	public final String displayName;

	Team(String displayName) {
		this.displayName = displayName;
	}

	public static Team of(Role r) {
		return r.evil() ? EVIL : GOOD;
	}
}
