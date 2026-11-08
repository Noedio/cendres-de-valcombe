package fr.valcombe.rules;

public enum RoleType {
	CITADIN("Citadin"),
	ETRANGER("Étranger"),
	SBIRE("Sbire"),
	DEMON("Démon");

	public final String displayName;

	RoleType(String displayName) {
		this.displayName = displayName;
	}

	public boolean evil() {
		return this == SBIRE || this == DEMON;
	}
}
