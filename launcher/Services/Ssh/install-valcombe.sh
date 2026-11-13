#!/usr/bin/env bash
# ----------------------------------------------------------------------------------------------------
# Les Cendres de Valcombe : installe ou met à jour le serveur sur un VPS Linux (Debian, Ubuntu...).
#
# Le launcher l'envoie par SFTP dans un dossier temporaire, avec un plan qu'il a préparé
# (valcombe-plan.tsv : Java, lanceur Fabric, mods de la dernière version publiée, fichiers de réglages),
# puis le lance en root. Le script est idempotent : relancé, il ne refait que ce qui manque ou a changé.
# Tout fichier téléchargé est vérifié par son empreinte (SHA-1 de Mojang, SHA-256 d'Adoptium, SHA-512
# du manifeste de Valcombe) ; seuls des hôtes connus sont acceptés.
#
# Ce qu'il met en place (voir HEBERGEMENT.md) :
#   - un compte système dédié, sans connexion possible (valcombe par défaut) ;
#   - le dossier du serveur (/opt/valcombe par défaut), avec Java, fabric-server-launch.jar, mods/,
#     demarrer.sh (relance après POST /admin/redemarrer), sauvegarder.sh et les réglages préparés ;
#   - le service systemd (valcombe.service), activé au démarrage de la machine mais pas démarré ;
#   - les ports du jeu (TCP) et du chat vocal (UDP) dans ufw ou firewalld s'ils sont actifs.
#     Le port de l'API d'administration n'est jamais ouvert : le launcher y passe par un tunnel SSH.
#
# Utilisation (en root) :
#   bash install-valcombe.sh --plan valcombe-plan.tsv [--envoi DOSSIER] [--a-blanc --racine DOSSIER]
#                            [--sans-telechargement]
#   --envoi                dossier des fichiers préparés (par défaut : celui du plan)
#   --a-blanc              installation à blanc : tout est écrit sous --racine, les commandes du système
#                          (compte, droits, systemd, pare-feu) sont seulement affichées ; sert à vérifier
#                          le script depuis le PC (ValcombeLauncher.exe --verifier-vps)
#   --sans-telechargement  n'affiche que ce qui serait téléchargé
#
# Messages pour le launcher, un par ligne (le reste est le journal de l'installation) :
#   @@etape <pourcentage> <texte>   avancement
#   @@change oui|non                le serveur a-t-il changé (le launcher le redémarre alors s'il tournait)
#   @@fini <texte>                  succès
#   @@erreur <texte>                échec (le code de sortie n'est pas nul)
# ----------------------------------------------------------------------------------------------------
set -Eeuo pipefail
umask 022
export LC_ALL=C
exec 2>&1

etape() { printf '@@etape %s %s\n' "$1" "$2"; }
info() { printf '%s\n' "$*"; }
erreur() {
	printf '@@erreur %s\n' "$*"
	exit 1
}
trap 'erreur "Échec inattendu à la ligne $LINENO du script ($BASH_COMMAND)."' ERR

# ----------------------------------------------------------------------------------------------------
# Arguments
# ----------------------------------------------------------------------------------------------------
PLAN=""
ENVOI=""
A_BLANC=0
RACINE=""
TELECHARGER=1
while [ $# -gt 0 ]; do
	case "$1" in
		--plan) PLAN=${2:-}; shift 2 ;;
		--envoi) ENVOI=${2:-}; shift 2 ;;
		--a-blanc) A_BLANC=1; shift ;;
		--racine) RACINE=${2:-}; shift 2 ;;
		--sans-telechargement) TELECHARGER=0; shift ;;
		*) erreur "Option inconnue : $1" ;;
	esac
done
[ -n "$PLAN" ] && [ -f "$PLAN" ] || erreur "Plan d'installation introuvable (--plan)."
[ -n "$ENVOI" ] || ENVOI=$(dirname "$PLAN")
if [ "$A_BLANC" = 1 ]; then
	[ -n "$RACINE" ] && [ -d "$RACINE" ] || erreur "L'installation à blanc demande un dossier --racine existant."
	RACINE=${RACINE%/}
	info "Installation à blanc dans $RACINE : les commandes du système sont seulement affichées."
elif [ -n "$RACINE" ]; then
	erreur "--racine ne sert qu'avec --a-blanc."
elif [ "$(id -u)" != 0 ]; then
	erreur "Le script doit être lancé en root (sudo)."
fi

# Commande qui change le système (compte, droits, services, pare-feu) : affichée seulement à blanc.
systeme() {
	if [ "$A_BLANC" = 1 ]; then
		info "[à blanc] $*"
	else
		"$@"
	fi
}

# ----------------------------------------------------------------------------------------------------
# Contrôles des valeurs du plan (le launcher les vérifie déjà ; le script ne fait confiance à personne)
# ----------------------------------------------------------------------------------------------------
NOM_SUR='^[A-Za-z0-9._+@-]+$'
chemin_relatif_sur() {
	local c=$1 morceau
	case "$c" in '' | /* | */ | *//*) return 1 ;; esac
	local IFS=/
	for morceau in $c; do
		[[ "$morceau" =~ $NOM_SUR ]] || return 1
		[ "$morceau" != . ] && [ "$morceau" != .. ] || return 1
	done
	return 0
}
hote_connu() {
	case "$1" in
		piston-data.mojang.com | meta.fabricmc.net | github.com | objects.githubusercontent.com | cdn.modrinth.com) return 0 ;;
		*) return 1 ;;
	esac
}
ADRESSE_SURE='^https://([A-Za-z0-9.-]+)/[A-Za-z0-9._~%/+=:@?&,-]*$'
adresse_sure() {
	[[ "$1" =~ $ADRESSE_SURE ]] || return 1
	hote_connu "${BASH_REMATCH[1]}"
}
hexa() { [[ "$2" =~ ^[0-9a-fA-F]+$ ]] && [ "${#2}" = "$1" ]; }
entier() { [[ "$1" =~ ^[0-9]{1,6}$ ]]; }

# ----------------------------------------------------------------------------------------------------
# Lecture du plan
# ----------------------------------------------------------------------------------------------------
DOSSIER=""
COMPTE=""
SERVICE=""
PORT_JEU=""
PORT_VOIX=""
MEMOIRE_MO=""
PARE_FEU="non"
VERSION=""
MINECRAFT=""
CHARGEUR=""
JAVA_NOM=""
FABRIC_URL=""
JAVA_ARCHIVE_SHA=""
JAVA_ARCHIVE_URL=""
JR=()
JF_SHA=()
JF_X=()
JF_CHEMIN=()
JF_URL=()
JL_CHEMIN=()
JL_CIBLE=()
MOD_SHA=()
MOD_NOM=()
MOD_URL=()
FI_CHEMIN=()
FI_SOURCE=()
FI_MODE=()
FORMAT=""
n=0
while IFS= read -r ligne || [ -n "$ligne" ]; do
	n=$((n + 1))
	ligne=${ligne%$'\r'}
	case "$ligne" in '' | '#'*) continue ;; esac
	IFS=$'\t' read -r -a f <<<"$ligne"
	case "${f[0]}" in
		valcombe-plan) FORMAT=${f[1]:-} ;;
		reglage)
			v=${f[2]:-}
			case "${f[1]:-}" in
				DOSSIER) DOSSIER=$v ;;
				COMPTE) COMPTE=$v ;;
				SERVICE) SERVICE=$v ;;
				PORT_JEU) PORT_JEU=$v ;;
				PORT_VOIX) PORT_VOIX=$v ;;
				MEMOIRE_MO) MEMOIRE_MO=$v ;;
				PARE_FEU) PARE_FEU=$v ;;
				VERSION) VERSION=$v ;;
				MINECRAFT) MINECRAFT=$v ;;
				CHARGEUR) CHARGEUR=$v ;;
				JAVA) JAVA_NOM=$v ;;
				*) erreur "Plan, ligne $n : réglage inconnu (${f[1]:-}). Mettez le launcher à jour." ;;
			esac
			;;
		java-rep)
			chemin_relatif_sur "${f[1]:-}" || erreur "Plan, ligne $n : dossier de Java refusé."
			JR+=("${f[1]}")
			;;
		java-fichier)
			hexa 40 "${f[1]:-}" || erreur "Plan, ligne $n : empreinte SHA-1 invalide."
			[ "${f[2]:-}" = x ] || [ "${f[2]:-}" = - ] || erreur "Plan, ligne $n : drapeau d'exécution invalide."
			chemin_relatif_sur "${f[3]:-}" || erreur "Plan, ligne $n : chemin de fichier de Java refusé."
			adresse_sure "${f[4]:-}" || erreur "Plan, ligne $n : adresse de téléchargement refusée."
			JF_SHA+=("${f[1],,}")
			JF_X+=("${f[2]}")
			JF_CHEMIN+=("${f[3]}")
			JF_URL+=("${f[4]}")
			;;
		java-lien)
			chemin_relatif_sur "${f[1]:-}" || erreur "Plan, ligne $n : lien de Java refusé."
			[[ "${f[2]:-}" =~ ^[A-Za-z0-9._+@/-]+$ ]] && [[ "${f[2]}" != /* ]] || erreur "Plan, ligne $n : cible de lien refusée."
			JL_CHEMIN+=("${f[1]}")
			JL_CIBLE+=("${f[2]}")
			;;
		java-archive)
			hexa 64 "${f[1]:-}" || erreur "Plan, ligne $n : empreinte SHA-256 invalide."
			adresse_sure "${f[2]:-}" || erreur "Plan, ligne $n : adresse de téléchargement refusée."
			JAVA_ARCHIVE_SHA=${f[1],,}
			JAVA_ARCHIVE_URL=${f[2]}
			;;
		fabric)
			adresse_sure "${f[1]:-}" || erreur "Plan, ligne $n : adresse de Fabric refusée."
			FABRIC_URL=${f[1]}
			;;
		mod)
			hexa 128 "${f[1]:-}" || erreur "Plan, ligne $n : empreinte SHA-512 invalide."
			[[ "${f[2]:-}" =~ ^[A-Za-z0-9._+-]+\.jar$ ]] || erreur "Plan, ligne $n : nom de mod refusé."
			adresse_sure "${f[3]:-}" || erreur "Plan, ligne $n : adresse de mod refusée."
			MOD_SHA+=("${f[1],,}")
			MOD_NOM+=("${f[2]}")
			MOD_URL+=("${f[3]}")
			;;
		fichier)
			chemin_relatif_sur "${f[1]:-}" || erreur "Plan, ligne $n : chemin de réglage refusé."
			[[ "${f[2]:-}" =~ $NOM_SUR ]] || erreur "Plan, ligne $n : fichier préparé refusé."
			[[ "${f[3]:-}" =~ ^[0-7]{3}$ ]] || erreur "Plan, ligne $n : droits invalides."
			[ -f "$ENVOI/${f[2]}" ] || erreur "Fichier préparé absent de l'envoi : ${f[2]}"
			FI_CHEMIN+=("${f[1]}")
			FI_SOURCE+=("${f[2]}")
			FI_MODE+=("${f[3]}")
			;;
		*) erreur "Plan, ligne $n : entrée inconnue (${f[0]}). Mettez le launcher à jour." ;;
	esac
done <"$PLAN"

[ "$FORMAT" = 1 ] || erreur "Format de plan inconnu ($FORMAT) : mettez le launcher à jour."
[[ "$DOSSIER" =~ ^/[A-Za-z0-9._/-]+$ ]] && chemin_relatif_sur "${DOSSIER#/}" || erreur "Dossier du serveur refusé : $DOSSIER"
case "$DOSSIER" in
	/bin | /boot | /dev | /etc | /home | /lib | /lib64 | /opt | /proc | /root | /run | /sbin | /srv | /sys | /tmp | /usr | /var)
		erreur "Dossier du serveur refusé (dossier du système) : $DOSSIER" ;;
	/bin/* | /boot/* | /dev/* | /etc/* | /lib/* | /lib64/* | /proc/* | /run/* | /sbin/* | /sys/* | /usr/* | /tmp/*)
		erreur "Dossier du serveur refusé (dossier du système) : $DOSSIER" ;;
esac
[[ "$COMPTE" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] && [ "$COMPTE" != root ] || erreur "Compte du serveur refusé : $COMPTE"
[[ "$SERVICE" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || erreur "Nom de service refusé : $SERVICE"
for p in "$PORT_JEU" "$PORT_VOIX"; do
	entier "$p" && [ "$p" -ge 1024 ] && [ "$p" -le 65535 ] || erreur "Port refusé : $p"
done
entier "$MEMOIRE_MO" && [ "$MEMOIRE_MO" -ge 1024 ] || erreur "Mémoire refusée : $MEMOIRE_MO"
[[ "$JAVA_NOM" =~ $NOM_SUR ]] || erreur "Nom du Java refusé : $JAVA_NOM"
[[ "$VERSION" =~ ^[A-Za-z0-9._+-]{1,40}$ ]] || erreur "Version refusée : $VERSION"
[[ "$MINECRAFT" =~ ^[A-Za-z0-9._+-]{1,40}$ ]] && [[ "$CHARGEUR" =~ ^[A-Za-z0-9._+-]{1,40}$ ]] || erreur "Versions de Minecraft ou de Fabric refusées."
[ -n "$FABRIC_URL" ] || erreur "Le plan ne dit pas où prendre le lanceur Fabric."
[ "${#MOD_NOM[@]}" -gt 0 ] || erreur "Le plan ne contient aucun mod."
if [ -n "$JAVA_ARCHIVE_URL" ] && [ "${#JF_CHEMIN[@]}" -gt 0 ]; then
	erreur "Le plan donne deux Java à la fois."
fi
[ -n "$JAVA_ARCHIVE_URL" ] || [ "${#JF_CHEMIN[@]}" -gt 0 ] || erreur "Le plan ne dit pas quel Java installer."

D="$RACINE$DOSSIER"
JDIR="$D/java/$JAVA_NOM"
JAVA_BIN="$DOSSIER/java/$JAVA_NOM/bin/java"
UNITE="$RACINE/etc/systemd/system/$SERVICE.service"
CHANGE=0
info "Valcombe $VERSION (Minecraft $MINECRAFT, Fabric $CHARGEUR) dans $DOSSIER, compte $COMPTE, service $SERVICE."

# ----------------------------------------------------------------------------------------------------
# Verrou : deux installations en même temps se marcheraient dessus
# ----------------------------------------------------------------------------------------------------
if command -v flock >/dev/null 2>&1; then
	mkdir -p "$RACINE/run/lock"
	exec 9>"$RACINE/run/lock/valcombe-installation.lock"
	flock -n 9 || erreur "Une autre installation de Valcombe est en cours sur ce serveur."
fi

# ----------------------------------------------------------------------------------------------------
# Outils : curl (ou wget), empreintes, tar
# ----------------------------------------------------------------------------------------------------
etape 2 "Vérification des outils du serveur"
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
	if [ "$A_BLANC" = 1 ]; then
		erreur "Ni curl ni wget sur ce poste."
	fi
	info "Installation de curl..."
	if command -v apt-get >/dev/null 2>&1; then
		DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl ca-certificates
	elif command -v dnf >/dev/null 2>&1; then
		dnf install -y -q curl ca-certificates
	elif command -v yum >/dev/null 2>&1; then
		yum install -y -q curl ca-certificates
	else
		erreur "Installez curl sur le serveur (ni curl ni wget, et aucun gestionnaire de paquets connu)."
	fi
fi
for outil in sha1sum sha256sum sha512sum tar cmp install; do
	command -v "$outil" >/dev/null 2>&1 || erreur "Outil absent du serveur : $outil (paquets coreutils, diffutils ou tar)."
done

empreinte() { # algo fichier
	local e
	e=$("${1}sum" "$2")
	printf '%s' "${e%% *}" | tr 'A-F' 'a-f'
}

telecharger() { # adresse cible [algo empreinte]
	local url=$1 cible=$2 algo=${3:-} attendu=${4:-} tmp="$2.part"
	if [ "$TELECHARGER" = 0 ]; then
		info "[sans téléchargement] $url"
		return 0
	fi
	mkdir -p "$(dirname "$cible")"
	rm -f "$tmp"
	local ok=0
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 --retry-delay 2 --connect-timeout 20 \
			-o "$tmp" "$url" && ok=1
	else
		wget -q --https-only --tries=3 --timeout=20 -O "$tmp" "$url" && ok=1
	fi
	if [ "$ok" != 1 ]; then
		rm -f "$tmp"
		erreur "Téléchargement impossible : $url"
	fi
	if [ -n "$algo" ] && [ "$(empreinte "$algo" "$tmp")" != "$attendu" ]; then
		rm -f "$tmp"
		erreur "Fichier corrompu (empreinte incorrecte) : $(basename "$cible")"
	fi
	mv -f "$tmp" "$cible"
}

# Place un fichier (copie depuis l'envoi ou fichier généré) s'il diffère, avec ses droits.
# Rend 0 s'il a changé, 1 sinon.
placer() { # source cible mode propriétaire
	local source=$1 cible=$2 mode=$3 proprio=$4
	if [ -f "$cible" ] && cmp -s "$source" "$cible"; then
		return 1
	fi
	mkdir -p "$(dirname "$cible")"
	cp -f "$source" "$cible.nouveau"
	chmod "$mode" "$cible.nouveau"
	mv -f "$cible.nouveau" "$cible"
	systeme chown "$proprio" "$cible"
	return 0
}

# ----------------------------------------------------------------------------------------------------
# Compte et dossier
# ----------------------------------------------------------------------------------------------------
etape 4 "Compte $COMPTE et dossier $DOSSIER"
if id -u "$COMPTE" >/dev/null 2>&1 && [ "$A_BLANC" = 0 ]; then
	info "Le compte $COMPTE existe déjà."
else
	NOLOGIN=/usr/sbin/nologin
	[ -x "$NOLOGIN" ] || NOLOGIN=/sbin/nologin
	systeme useradd --system --user-group --home-dir "$DOSSIER" --no-create-home --shell "$NOLOGIN" "$COMPTE"
fi
if [ "$A_BLANC" = 0 ] && [ -d "$(dirname "$DOSSIER")" ]; then
	libre=$(df -Pk "$(dirname "$DOSSIER")" | awk 'NR==2 {print $4}')
	if entier "${libre:-x}" && [ "$libre" -lt 1048576 ]; then
		erreur "Moins de 1 Go libre sur le disque du serveur : faites de la place avant d'installer."
	fi
fi
mkdir -p "$D" "$D/mods" "$D/config" "$D/java"
chmod 750 "$D"
systeme chown "$COMPTE:$COMPTE" "$D" "$D/mods" "$D/config"
TMPD=$(mktemp -d "$D/.installation.XXXXXX")
nettoyer() { rm -rf "$TMPD"; }
trap nettoyer EXIT

# ----------------------------------------------------------------------------------------------------
# Java
# ----------------------------------------------------------------------------------------------------
etape 8 "Java ($JAVA_NOM)"
if [ -n "$JAVA_ARCHIVE_URL" ]; then
	# Java d'Adoptium (machines ARM, pour lesquelles Mojang ne publie pas de Java) : une archive vérifiée.
	if [ -f "$JDIR/.valcombe-archive" ] && [ "$(cat "$JDIR/.valcombe-archive")" = "$JAVA_ARCHIVE_SHA" ] && [ -x "$JDIR/bin/java" ]; then
		info "Java déjà en place."
	else
		telecharger "$JAVA_ARCHIVE_URL" "$TMPD/java.tar.gz" sha256 "$JAVA_ARCHIVE_SHA"
		if [ "$TELECHARGER" = 1 ]; then
			rm -rf "$JDIR.nouveau"
			mkdir -p "$JDIR.nouveau"
			tar -xzf "$TMPD/java.tar.gz" -C "$JDIR.nouveau" --strip-components=1 --no-same-owner
			printf '%s' "$JAVA_ARCHIVE_SHA" >"$JDIR.nouveau/.valcombe-archive"
			rm -rf "$JDIR.ancien"
			[ ! -e "$JDIR" ] || mv "$JDIR" "$JDIR.ancien"
			mv "$JDIR.nouveau" "$JDIR"
			rm -rf "$JDIR.ancien"
		fi
		CHANGE=1
	fi
else
	# Java officiel de Mojang, fichier par fichier (le même que celui du jeu sur le PC des joueurs).
	# (« ${t[@]+...} » : un tableau vide passe aussi avec set -u sur les bash anciens.)
	for r in ${JR[@]+"${JR[@]}"}; do
		mkdir -p "$JDIR/$r"
	done
	total=${#JF_CHEMIN[@]}
	refaits=0
	for i in ${JF_CHEMIN[@]+"${!JF_CHEMIN[@]}"}; do
		cible="$JDIR/${JF_CHEMIN[$i]}"
		if [ ! -f "$cible" ] || [ "$(empreinte sha1 "$cible")" != "${JF_SHA[$i]}" ]; then
			telecharger "${JF_URL[$i]}" "$cible" sha1 "${JF_SHA[$i]}"
			refaits=$((refaits + 1))
			CHANGE=1
		fi
		if [ -f "$cible" ]; then
			if [ "${JF_X[$i]}" = x ]; then chmod 755 "$cible"; else chmod 644 "$cible"; fi
		fi
		if [ $((i % 20)) = 0 ]; then
			etape $((8 + 40 * i / total)) "Java : $i / $total fichiers vérifiés"
		fi
	done
	for i in ${JL_CHEMIN[@]+"${!JL_CHEMIN[@]}"}; do
		lien="$JDIR/${JL_CHEMIN[$i]}"
		cible=${JL_CIBLE[$i]}
		# La cible d'un lien doit rester dans le dossier de Java.
		resolu=$(realpath -m "$(dirname "$lien")/$cible")
		case "$resolu" in "$(realpath -m "$JDIR")"/*) ;; *) erreur "Lien de Java qui sort de son dossier : ${JL_CHEMIN[$i]}" ;; esac
		if [ -L "$lien" ] && [ "$(readlink "$lien")" = "$cible" ]; then
			continue
		fi
		if [ "$A_BLANC" = 1 ] && [ -e "$lien" ]; then
			continue # sous Windows, un lien est une copie : déjà faite
		fi
		mkdir -p "$(dirname "$lien")"
		if [ "$A_BLANC" = 1 ]; then
			# Sous Windows, ln copie la cible : impossible si elle n'a pas été téléchargée.
			ln -sfn "$cible" "$lien" 2>/dev/null || info "[à blanc] ln -s $cible $lien"
		else
			ln -sfn "$cible" "$lien"
		fi
		CHANGE=1
	done
	info "Java : $total fichiers, $refaits téléchargés."
fi
if [ "$A_BLANC" = 0 ]; then
	version_java=$("$JDIR/bin/java" -version 2>&1) || erreur "Java ne démarre pas sur ce serveur ($JAVA_NOM)."
	info "${version_java%%$'\n'*}"
fi
systeme chown -R root:root "$JDIR"

# ----------------------------------------------------------------------------------------------------
# Lanceur du serveur Fabric
# ----------------------------------------------------------------------------------------------------
etape 50 "Lanceur du serveur Fabric"
TAMPON_FABRIC="$MINECRAFT $CHARGEUR $FABRIC_URL"
if [ -f "$D/fabric-server-launch.jar" ] && [ -f "$D/.valcombe-fabric" ] && [ "$(cat "$D/.valcombe-fabric")" = "$TAMPON_FABRIC" ]; then
	info "Lanceur Fabric déjà en place."
else
	telecharger "$FABRIC_URL" "$TMPD/fabric-server-launch.jar"
	if [ "$TELECHARGER" = 1 ]; then
		[ "$(head -c 2 "$TMPD/fabric-server-launch.jar")" = PK ] || erreur "Le lanceur Fabric reçu n'est pas une archive Java."
		placer "$TMPD/fabric-server-launch.jar" "$D/fabric-server-launch.jar" 644 "$COMPTE:$COMPTE" || true
		printf '%s' "$TAMPON_FABRIC" >"$D/.valcombe-fabric"
	fi
	CHANGE=1
fi

# ----------------------------------------------------------------------------------------------------
# Mods : ceux du plan, et aucun autre
# ----------------------------------------------------------------------------------------------------
etape 58 "Mods du serveur"
for vieux in "$D"/mods/*.jar; do
	[ -e "$vieux" ] || continue
	garder=0
	for nom in "${MOD_NOM[@]}"; do
		if [ "$(basename "$vieux")" = "$nom" ]; then garder=1; fi
	done
	if [ "$garder" = 0 ]; then
		info "Retrait de l'ancien mod $(basename "$vieux")"
		rm -f "$vieux"
		CHANGE=1
	fi
done
total=${#MOD_NOM[@]}
for i in "${!MOD_NOM[@]}"; do
	cible="$D/mods/${MOD_NOM[$i]}"
	if [ -f "$cible" ] && [ "$(empreinte sha512 "$cible")" = "${MOD_SHA[$i]}" ]; then
		continue
	fi
	etape $((60 + 25 * i / total)) "Mod ${MOD_NOM[$i]}"
	telecharger "${MOD_URL[$i]}" "$cible" sha512 "${MOD_SHA[$i]}"
	[ ! -f "$cible" ] || chmod 644 "$cible"
	systeme chown "$COMPTE:$COMPTE" "$cible"
	CHANGE=1
done

# ----------------------------------------------------------------------------------------------------
# Réglages préparés par le launcher (server.properties, contrat, ports...)
# ----------------------------------------------------------------------------------------------------
etape 86 "Réglages du serveur"
for i in ${FI_CHEMIN[@]+"${!FI_CHEMIN[@]}"}; do
	if placer "$ENVOI/${FI_SOURCE[$i]}" "$D/${FI_CHEMIN[$i]}" "${FI_MODE[$i]}" "$COMPTE:$COMPTE"; then
		info "Réglages : ${FI_CHEMIN[$i]} mis à jour."
		CHANGE=1
	fi
done
for r in "$D/config" "$D/config/voicechat"; do
	[ ! -d "$r" ] || systeme chown "$COMPTE:$COMPTE" "$r"
done

# Scripts de lancement et de sauvegarde (les mêmes que ceux de tools/make_modpack.py, avec le Java installé ici).
cat >"$TMPD/demarrer.sh" <<'FIN'
#!/bin/sh
# Lance le serveur des Cendres de Valcombe (écrit par install-valcombe.sh, réécrit à chaque mise à jour).
# Après un arrêt, le relance si l'API d'administration l'a demandé (drapeau config/valcombe/redemarrer).
cd "$(dirname "$0")"
while :; do
  rm -f config/valcombe/redemarrer
  "${VALCOMBE_JAVA:-java}" -Xms1G -Xmx${VALCOMBE_RAM:-4G} -jar fabric-server-launch.jar nogui
  code=$?
  [ -f config/valcombe/redemarrer ] || exit $code
  echo "Redémarrage demandé par l'API d'administration : relance du serveur."
  sleep 2
done
FIN
cat >"$TMPD/sauvegarder.sh" <<'FIN'
#!/bin/sh
# Sauvegarde le monde et les réglages de Valcombe ; garde les 14 dernières archives.
set -e
cd "$(dirname "$0")"
mkdir -p sauvegardes
tar -czf "sauvegardes/valcombe-$(date +%Y-%m-%d_%H%M).tar.gz" world config server.properties whitelist.json ops.json 2>/dev/null || true
ls -1t sauvegardes/valcombe-*.tar.gz | tail -n +15 | xargs -r rm --
FIN
for s in demarrer.sh sauvegarder.sh; do
	if placer "$TMPD/$s" "$D/$s" 755 "$COMPTE:$COMPTE"; then
		CHANGE=1
	fi
done

# ----------------------------------------------------------------------------------------------------
# Service systemd
# ----------------------------------------------------------------------------------------------------
etape 90 "Service systemd $SERVICE"
PROTEGER_HOME="ProtectHome=true"
case "$DOSSIER" in /home/* | /root/*) PROTEGER_HOME="# ProtectHome : le serveur est dans un dossier personnel" ;; esac
cat >"$TMPD/unite" <<FIN
# Écrit par install-valcombe.sh (launcher Valcombe) : réécrit à chaque mise à jour.
[Unit]
Description=Serveur Minecraft Les Cendres de Valcombe
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$COMPTE
Group=$COMPTE
WorkingDirectory=$DOSSIER
Environment=VALCOMBE_JAVA=$JAVA_BIN
Environment=VALCOMBE_RAM=${MEMOIRE_MO}M
ExecStart=$DOSSIER/demarrer.sh
Restart=on-failure
RestartSec=10
# Le temps d'enregistrer les mondes et la partie en cours à l'arrêt (Java les enregistre sur SIGTERM).
TimeoutStopSec=120
SuccessExitStatus=0 143
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
$PROTEGER_HOME

[Install]
WantedBy=multi-user.target
FIN
UNITE_CHANGEE=0
if placer "$TMPD/unite" "$UNITE" 644 root:root; then
	UNITE_CHANGEE=1
	CHANGE=1
fi
if [ "$UNITE_CHANGEE" = 1 ]; then
	systeme systemctl daemon-reload
fi
if [ "$A_BLANC" = 1 ]; then
	systeme systemctl enable "$SERVICE"
elif ! systemctl is-enabled --quiet "$SERVICE" 2>/dev/null; then
	systemctl enable --quiet "$SERVICE"
	info "Service $SERVICE activé au démarrage de la machine."
fi

# ----------------------------------------------------------------------------------------------------
# Pare-feu : seulement s'il est déjà actif (l'activer ici pourrait couper SSH)
# ----------------------------------------------------------------------------------------------------
etape 95 "Pare-feu"
if [ "$PARE_FEU" = oui ]; then
	etat_ufw=""
	if command -v ufw >/dev/null 2>&1; then etat_ufw=$(ufw status 2>/dev/null || true); fi
	etat_firewalld=""
	if command -v firewall-cmd >/dev/null 2>&1; then etat_firewalld=$(firewall-cmd --state 2>/dev/null || true); fi
	case "$etat_ufw" in
		*"Status: active"*)
			systeme ufw allow "$PORT_JEU/tcp" comment "Valcombe : jeu"
			systeme ufw allow "$PORT_VOIX/udp" comment "Valcombe : chat vocal"
			;;
		*)
			if [ "$etat_firewalld" = running ]; then
				systeme firewall-cmd --quiet --permanent --add-port="$PORT_JEU/tcp"
				systeme firewall-cmd --quiet --permanent --add-port="$PORT_VOIX/udp"
				systeme firewall-cmd --quiet --reload
			else
				info "Aucun pare-feu actif sur le serveur (ufw, firewalld) : rien à ouvrir ici. Chez certains hébergeurs, ouvrez $PORT_JEU/tcp et $PORT_VOIX/udp dans leur interface."
			fi
			;;
	esac
else
	info "Pare-feu laissé tel quel (réglage du launcher)."
fi

# ----------------------------------------------------------------------------------------------------
# Version installée (lue par le launcher)
# ----------------------------------------------------------------------------------------------------
printf 'version=%s\nminecraft=%s\nchargeur=%s\njava=%s\nport=%s\n' "$VERSION" "$MINECRAFT" "$CHARGEUR" "$JAVA_NOM" "$PORT_JEU" >"$TMPD/installe"
placer "$TMPD/installe" "$D/valcombe-installe.txt" 644 "$COMPTE:$COMPTE" || true

if [ "$CHANGE" = 1 ]; then echo "@@change oui"; else echo "@@change non"; fi
etape 100 "Serveur installé"
echo "@@fini Valcombe $VERSION installé dans $DOSSIER (service $SERVICE)."
