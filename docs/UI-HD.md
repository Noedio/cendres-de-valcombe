# Interface haute résolution : construire un écran

Les écrans de Valcombe étaient dessinés à l'échelle de l'interface de Minecraft : à l'échelle 2, chaque pixel de l'interface fait 2 × 2 pixels à l'écran, d'où les gros pixels et la police en escalier. La boîte à outils `fr.valcombe.client.hd` dessine à la **résolution réelle de l'écran**, avec une **police lisse**. Ce document explique comment construire un écran avec elle et comment y passer un écran existant.

Premier écran migré : `SettingsScreen` (« Réglages de la partie »). Vitrine de tous les composants : `src/gametest/java/fr/valcombe/gametest/HdShowcaseScreen.java`.

## Le principe

- **On place en unités d'interface, on dessine en pixels réels.** Un écran garde ses coordonnées habituelles (`px`, `py`, `pw`, `ph`, `width`, `height` : 427 × 240 à l'échelle 2 dans une fenêtre de 854 × 480). Au moment de dessiner, on ouvre un contexte `Hd` : la pose est mise à l'échelle 1/échelle et chaque coordonnée devient un pixel de la fenêtre.
- **Deux conversions** : `hd.px(x)` convertit une coordonnée d'interface (entière) en pixels réels, `hd.u(n)` convertit une longueur de dessin (marge, épaisseur, peut être fractionnaire : `hd.u(0.5)`) en pixels réels.
- **Les composants sont des widgets Minecraft ordinaires** (`AbstractWidget`) : leurs bornes sont en unités d'interface, le focus du clavier, Tab, Échap, la narration et les clics marchent comme avant. Seul leur dessin est en haute résolution.
- **Les textures** sont dessinées par `tools/make_ui.py` à 8 pixels par unité, puis réduites par le jeu (chaîne de mipmaps, filtrage lisse) à l'échelle du joueur : elles restent nettes de l'échelle 1 à l'échelle 8 et au-delà.
- **Les polices** sont des TTF libres (licence OFL) déclarées à une série de tailles en pixels réels ; le jeu prend la plus proche de la taille voulue.

```java
try (Hd hd = Hd.begin(g)) {
	hd.panel(hd.px(px), hd.px(py), hd.px(pw), hd.px(ph));
	hd.text(HdStyle.TEXTE, "Bonsoir", hd.px(px + 12), hd.px(py + 12), Hd.INK);
}
```

Toujours ouvrir `Hd` dans un `try` : il remet la pose en place en sortant. Les contextes s'emboîtent sans se cumuler (un widget qui ouvre le sien dans un écran qui a déjà le sien ne change rien).

## Un écran complet

```java
public class ExempleScreen extends HdScreen {
	public ExempleScreen() {
		super("Exemple");                       // titre du bandeau
	}

	@Override
	protected int[] panelSize() {
		return new int[] {380, 216};            // réduit si la fenêtre est plus petite
	}

	@Override
	protected void build() {                   // rappelé à chaque nouvelle vue du serveur
		JsonObject st = ClientState.obj(view(), "settings");
		toggle(px + 10, py + 12, 175, "Écran noir la nuit", ClientState.bool(st, "nightDark"),
			() -> send("vc reglage nuit_sombre " + (ClientState.bool(st, "nightDark") ? "non" : "oui")))
			.tooltip("Oui : l'écran devient noir pour les joueurs endormis.");
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawHd(Hd hd) {             // contenu dessiné à la main, sous les widgets
		hd.text(HdStyle.SECTION, "La nuit", hd.px(px + 10), hd.px(py + 40), Hd.WINE);
	}
}
```

`HdScreen` hérite de `BaseScreen` : `view()`, `send()`, `objects()`, `strings()`, `circle()`, `ellipse()`, `phaseLabel()` et la reconstruction à chaque vue du serveur sont les mêmes. Il ajoute :

| Outil | Rôle |
| --- | --- |
| `drawHd(Hd hd)` | Contenu dessiné à la main (remplace `drawContent`, qui reste appelé après pour une migration progressive) |
| `button(x, y, w, libellé, action)` | Rend un `HdButton` de 18 unités de haut |
| `toggle(x, y, w, libellé, état, action)` | Rend un `HdToggle` (interrupteur) |
| `token(HdToken)` | Ajoute un jeton |
| `rebuildSoon()` | Reconstruit l'écran au prochain tick (depuis l'action d'un onglet ou d'un interrupteur local, jamais `rebuildWidgets()` pendant un clic) |
| Infobulles | Tout widget `HdTooltipped` (boutons, icônes, jetons) montre son infobulle après 350 ms de survol, ou au focus du clavier |
| Mémoire | Le focus, le texte tapé et le défilement survivent aux reconstructions pour les widgets qui ont une clé (`HdKeyed`) |

## Les composants

| Composant | Usage | Notes |
| --- | --- | --- |
| `HdButton` | `button(...)`, ou `new HdButton(x, y, w, h, libellé, action)` | `.disabled()`, `.tooltip(texte)`, `.style(HdStyle)`, `.key(clé)`. Hérite de `WaxButton` : `press()` et les tests en jeu qui cherchent les boutons par libellé marchent sans changement. |
| `HdToggle` | `toggle(...)` | Libellé à gauche, interrupteur à droite. Son message reste « libellé : oui » ou « libellé : non » (narration, tests). |
| `HdLabel` | `addRenderableOnly(new HdLabel(x, y, w, texte))` | `.style()`, `.color()`, `.align(LEFT, CENTER, RIGHT)`, `.paragraph(lignesMax)` (coupé à la largeur, « … » au bout). |
| `HdList<T>` | `addRenderableWidget(new HdList<T>(x, y, w, h, hauteurLigne, T -> libellé))` | `.items(liste)`, `.select(i)`, `.onSelect()`, `.onActivate()` (double-clic, Entrée), `.row(dessin)`, `.empty(texte)`, `.key(clé)`. Molette, glisser du curseur, clic sur le rail, flèches, Début, Fin, Page préc. et suiv. |
| `HdTextField` | `addRenderableWidget(new HdTextField(x, y, w, 18, narration))` | `.value()`, `.hint()`, `.maxLength()`, `.filter()`, `.responder()`, `.onEnter()`, `.key(clé)`. Sélection souris et clavier, Ctrl+A, C, X, V, mots avec Ctrl. Poser `.key()` après `.value()` : la saisie en cours l'emporte sur la valeur du serveur. `HdTextField.forget(clé)` après un envoi. |
| `HdTabs` | `addRenderableWidget(new HdTabs(x, y, w, 16, libellés, choisi))` | `.onChange(i -> { onglet = i; rebuildSoon(); })`. Posé juste au-dessus d'une liste ou d'un encart. |
| `HdIcon` | `new HdIcon(x, y, taille, HdIcon.gui("roles/e_lune"), narration, actionOuNull)` | `.tooltip()`, `.tint()`. Sans action : décorative (infobulle seulement). |
| `HdToken` | `token(new HdToken(cx, cy, taille, nom, rôle, camp, vivant, choisi, action))` | Même API que `TokenWidget` : `.marker()`, `.labelOutward(angle)`, `.caption()`, plus `.tooltip()`. Mode daltonien compris. |

Dans `drawHd` ou un dessin de ligne de liste, le contexte `Hd` offre :

| Méthode | Dessin |
| --- | --- |
| `panel`, `inset`, `banner`, `separator`, `tooltip`, `shade` | Panneau de parchemin encadré, creux de parchemin, bandeau de titre, séparateur orné, infobulle, voile |
| `text`, `textCentered`, `textRight`, `textMiddle`, `textShadow` | Une ligne ; `textMiddle` la centre dans une hauteur (bouton, ligne de liste) |
| `paragraph(style, texte, x, y, largeur, couleur[, hauteurMax])` | Paragraphe coupé à la largeur ; renvoie la hauteur |
| `width`, `lineHeight`, `ellipsize`, `wrap`, `paragraphHeight` | Mesures, en pixels réels |
| `sprite(HdSprites.X, x, y, w, h[, teinte])` | Texture « 9 tranches » de la boîte à outils |
| `image(HdSprites.X, ...)`, `image(Identifier, x, y, w, h, teinte)` | Image entière ; une ressource du jeu (pictogramme, jeton) est réduite avec ses mipmaps, et le pixel art de 32 px reste net agrandi |
| `fill`, `gradient`, `frame`, `disc`, `triangle` | Formes simples |
| `clip`, `unclip` | Découpe (alignée sur les unités d'interface, c'est la précision de Minecraft) |
| `mouseX`, `mouseY`, `hovered` | Souris en pixels réels, précise au pixel |

### Symboles, texte serré, jetons dessinés

Ajoutés avec le passage des écrans des joueurs et de l'affichage en jeu :

| Outil | Rôle |
| --- | --- |
| `HdGlyphs` | Dessine net les symboles absents des polices (`✔ ✘ ✋ ⚠ ☠ ⏸ • ×`) : `HdGlyphs.text`, `width`, `textCentered`, `textShadow`, `ellipsize` écrivent une ligne où ils sont remplacés par leur forme, à la taille du texte. Le libellé reste le même (narration, tests). |
| `HdGlyphButton` | `HdButton` dont le libellé contient un de ces symboles (« ✔ Oui », « × ») : `addRenderableWidget(new HdGlyphButton(x, y, w, 18, libellé, action))`. |
| `HdText` | Paragraphe serré (`HdText.SERRE`, une ligne toutes les `HdText.PAS` = 11 unités) pour les écrans chargés : `paragraph(hd, style, texte, x, y, largeur, couleur, interligne, hauteurMax)`, `line(...)` raccourcie par « … ». Symboles dessinés compris. |
| `HdTokenArt` | Jeton dessiné (pas un widget), même dessin que `HdToken` : `HdTokenArt.draw(hd, x, y, taille, rôle, camp, vivant, choisi, survolé)` en pixels réels, et `drawWithAlpha` pour un jeton qui apparaît ou s'efface. Remplace `Theme.token` dans `drawHd`. |

### Cercle des sièges

Tout écran qui montre la table (menu J, pupitre et grimoire, désigner un joueur, vote, choix de nuit, grimoire du Mouchard) la pose avec `RingLayout` (calcul pur) par `SeatRing` (mesure avec la vraie police des noms, à l'échelle en cours, et garde les dernières mises en page) :

```java
RingLayout.Result ring = SeatRing.layout(noms, SeatRing.spec(x, y, w, h).tokens(26, 14));
for (int i = 0; i < noms.size(); i++) {
	RingLayout.Seat s = ring.seats().get(i);
	token(new HdToken(s.cx(), s.cy(), ring.size(), noms.get(i), rôle, camp, vivant, choisi, action).seat(s));
}
```

- **Garanties** : aucun jeton ne touche un autre ; aucune plaque de nom ne sort de la zone, ne recouvre une autre plaque ni ne mord sur un autre jeton (marqueurs compris : ils sont posés sur le bord du disque, qu'ils ne dépassent que d'une unité et demie). Vrai de 5 à 20 joueurs, avec des noms de 16 caractères, aux échelles 1 à 4 (`RingLayoutTest`, avec la vraie police).
- **Comment** : les sièges sont espacés également le long du bord de l'ellipse ; chaque plaque est posée le long de la normale à l'ellipse, collée à son jeton. L'ellipse se rétrécit et se recentre jusqu'à ce que tout tienne. Deux plaques qui se touchent : la plus large passe d'abord de l'autre côté de son jeton (dedans, ou un rang plus loin dehors) si elle y tient, sinon perd un caractère (« … »). Ensuite, les noms raccourcis reprennent des lettres là où la place s'est libérée. En dernier recours seulement, un nom est caché. On garde la mise en page qui montre le plus de lettres, puis les plus grands jetons.
- **Réglages** : `.tokens(max, min)` (tailles essayées), `.outsideOnly()` quand le centre porte un texte ou l'aiguille du vote, `.aspect(large, haut)` (rapport des rayons permis : 2,6 pour la bande basse du menu J).
- **Survol** : la plaque fait partie du jeton (survol, clic, liseré doré) ; un nom raccourci ou caché se lit en entier dans l'infobulle.
- **Menu J en partie** : colonne de gauche (carte de rôle, dont le jeton donne le texte du rôle en infobulle, dernière annonce), cercle à droite, actions en bas sur trois colonnes, ou quatre (libellés en 10,5) si cela fait gagner une rangée au cercle ; un libellé qui ne tient pas dans sa case en prend deux.
- **Test en jeu** : `SeatRingGameTest` (captures `valcombe-cercle-*.png`) ouvre ces écrans avec 5, 13 et 20 joueurs aux noms longs, à 427 x 240, à l'échelle 3 et 4, en 640 x 360 et à l'échelle 1, et vérifie plaques, jetons et widgets.

### Affichage en jeu

- Sous un écran de Valcombe ouvert, l'affichage en jeu ne dessine aucun texte (il se mêlerait au bandeau et au panneau sous leur voile) ; le chat et les écrans du jeu le gardent.
- Chaque message au centre de l'écran a sa plaque sombre : il se lit sur n'importe quel décor et recouvre proprement les textes posés dans le monde (accueil du hub). Les messages du haut s'empilent sous le vote ; ceux du bas s'empilent vers le haut au-dessus de la barre d'objets.
- La carte de rôle qui tombe au début de la partie passe par-dessus tout le reste.
- Le bandeau « Le serveur a une autre version du mod Valcombe » ne lit que la version annoncée par le serveur de la connexion en cours, jamais en partie solo (`VersionCheck`, vérifié par `VersionCheckTest`).

Écrans des joueurs passés en haute résolution : `MainScreen`, `SheetsScreen`, `NotebookScreen`, `NotebookGuessScreen`, `PromptScreen`, `NightScreen`, `GuessScreen`, `ClaimScreen`, `SignScreen`, `TokenScreen`, `ChronicleScreen`, `FiguresScreen`, `PrefsScreen`, `PickerScreen`, `RolePickerScreen`, ainsi que l'affichage en jeu (`Hud` : bandeau de phase, vote et horloge, tableau du jour, voix, carte de rôle, messages). Test en jeu : `PlayerScreensHdGameTest` (captures `valcombe-hd-joueurs-*.png`).

### Textes

Les tailles sont en unités d'interface (la taille réelle vaut taille × échelle). Styles nommés, à préférer :

| Style | Face | Taille | Pour |
| --- | --- | --- | --- |
| `HdStyle.TITRE` | Cinzel | 13 | Bandeau de titre |
| `HdStyle.GRAND_TITRE` | Cinzel | 20 | Accueil, annonce |
| `HdStyle.SECTION` | Cinzel | 10 | Intertitre dans un panneau |
| `HdStyle.TEXTE` | Alegreya Sans | 11 | Texte courant, lignes de liste |
| `HdStyle.FORT`, `HdStyle.BOUTON` | Alegreya Sans Medium | 11 | Libellés, boutons, onglets |
| `HdStyle.PETIT` | Alegreya Sans | 9 | Noms sous les jetons, légendes |

`style.size(n)` donne le même style à une autre taille. Les coordonnées d'un texte sont le **haut de sa ligne** ; la hauteur de ligne vaut 1,3 fois la taille et les capitales y sont centrées : centrer la ligne dans un bouton centre le texte.

Couleurs (`Hd.*`) : `INK` (encre, texte sur parchemin), `INK_SOFT` (texte secondaire), `WINE`, `BRONZE` (cadres, filets ; pas de texte sur parchemin, trop pâle), `BRONZE_INK` (texte bronze sur parchemin, contraste de 5), `GOLD`, `PARCH`, `ASH`, `LIGHT` (texte sur fond sombre), `MUTED` (inactif, indication), `SELECTION`, `HOVER`, `TEXT_SELECTION`.

Les textes affichés restent en français, sans tiret cadratin ni demi-cadratin.

## Passer un écran de BaseScreen à HdScreen

1. `extends BaseScreen` devient `extends HdScreen` (paquet `fr.valcombe.client.hd`).
2. `drawContent(GuiGraphicsExtractor g, ...)` devient `drawHd(Hd hd)` : `Theme.centered(g, font, s, x, y, c)` devient `hd.textCentered(HdStyle.TEXTE, s, hd.px(x), hd.px(y), c)`, `Theme.paragraph` devient `hd.paragraph`, `Theme.panel` devient `hd.panel`, `Theme.token` un `HdToken`.
3. `button(...)` rend déjà un `HdButton`. Les boutons « libellé : oui/non » deviennent des `toggle(...)` (le message reste le même). `setTooltip(Tooltip.create(...))` devient `.tooltip("...")`.
4. Les `TokenWidget` deviennent des `HdToken` (mêmes paramètres), les `EditBox` des `HdTextField` (avec une clé), les listes faites à la main des `HdList`.
5. Garder les mêmes commandes et les mêmes libellés de boutons : le test en jeu clique les boutons par leur libellé.
6. Une action qui reconstruit l'écran sur place (onglet, filtre local) appelle `rebuildSoon()`.
7. Vérifier : `./gradlew build --offline -q`, puis une capture dans le test en jeu.

## Écran « Parties » (tableau des parties)

`PartiesScreen` (paquet `screen`) : menu J au hub, « Parties », ou une fiche cliquée sur le tableau des parties de la salle d'accueil (le serveur envoie `parties:<id>`, l'écran s'ouvre sur cette partie). À gauche, un `HdList` à vignettes ; à droite, la fiche de la partie choisie, sa vignette en bandeau, et ses boutons.

- **Vignette peinte** (`PartiesScreen.vignette(hd, partie, x, y, w, h)`, en pixels réels, sans texture) : le ciel de la phase en dégradé (crépuscule et soleil couchant au salon, soleil du jour, lune en croissant et étoiles de la nuit, heure violette de la mise en place), la silhouette du village tirée de l'identifiant de la partie (toujours la même pour une partie, clocher au milieu, fenêtres allumées la nuit), une pastille par place (dorée si elle est prise), un voile et le signe pause en pause ou en suspens, un liseré d'or pour la partie principale, « ESSAI » dans un coin pour une partie d'essai.
- **Boutons** : « Rejoindre » (ou « Y retourner ») et « Regarder », grisés avec la raison en infobulle quand le serveur les refuse ; « Suspendre » ou « Reprendre » pour qui mène la partie ; « Supprimer » (partie d'essai, opérateurs) qui demande « Confirmer » ou « Annuler » ; en bas, pour un opérateur, un `HdTextField` et « Créer une partie d'essai » (la nouvelle partie est choisie dès qu'elle paraît).
- **Données** : le champ `parties` de la vue (`liste`, `operateur`, `regarde`), envoyé seulement hors des mondes de partie : dans une partie, on ne voit qu'elle. Chaque bouton est une commande `/vc partie rejoindre|regarder|suspendre|reprendre|supprimer|creer`.
- **Test en jeu** : `PartiesGameTest` (mise en page à 427 × 240 et à l'échelle 3, captures `valcombe-parties-*.png`, vignettes de tous les états sur une vue fabriquée).

## Atelier en jeu

Paquet `fr.valcombe.client.screen.atelier` (Sprint 16). Menu J › « Atelier », `/vc atelier jeu` (le serveur envoie `atelier`) : `AtelierPage.open()` repart de l'accueil.

| Classe | Rôle |
| --- | --- |
| `AtelierPage` | Cadre commun : panneau de 720 × 420 (réduit à la fenêtre), fil d'Ariane en haut (`trail()` : une `Crumb(libellé, page)` par étape, la dernière est la page en cours), avancement de la pose de carte en cours en haut à droite (`atelier.chantiers`), « Retour » et retour du serveur en bas. Une page donne `trail()`, `buildPage()`, `drawPage(hd)`, au besoin `footerReserve()` (unités prises à droite du pied par ses boutons). Zone libre : `left()`, `right()`, `top()`, `bottom()`. Sans la vue `atelier` (pas opérateur), la page dit pourquoi et mène aux « Mondes ». |
| `AtelierHome` | Accueil : quatre `Tile` (illustration peinte, nom, contenu en une ligne, infobulle). `AtelierHome.structures()`, `parties()`, `roles()` disent où mène chaque entrée. |
| `MapsPage` | Cartes : volets Bibliothèque (galerie `HdGrid`, recherche, tri), Ateliers ouverts, Corbeille ; « Nouvelle carte ». |
| `MapSheetPage` | Fiche d'une carte : grande vignette, détails, actions ; confirmations sur place (renommer, envoyer, supprimer). |
| `NewMapPage` | Parcours « Nouvelle carte » en trois étapes. |
| `StructuresPage` | Structures : volet Catalogue (galerie de vignettes vues de trois quarts, rayons Tout, Maisons, Place, Décor, À vous, recherche ; barre de la structure en main : Tourner, Retourner, À mes pieds, Ranger), volet Construire (les gestes de WorldEdit dans les ateliers, le chemin vers eux ailleurs) ; « Nouvelle structure ». `StructuresPage.take(id)` prend une structure en main (mode construction ouvert d'abord dans une partie). |
| `StructureSheetPage` | Fiche d'une structure : Prendre en main, Voir en 3D (`StructureViewerScreen`, dont « Retour » ramène à la fiche), Aller sur sa parcelle, Revenir à l'originale (fournie modifiée), Renommer…, Supprimer (à vous) ; confirmations sur place. |
| `NewStructurePage` | Parcours « Nouvelle structure » : depuis la sélection WorldEdit (ce qui manque est dit sur la case) ou une parcelle vide ; nom ; rayon ou taille ; vérification. |
| `PartiesPage` | Parties : galerie (carte en place vue de dessus et ciel de la phase, `PartiesPage.picture`), « Créer une partie d'essai » (nom écrit sur place, la nouvelle mise en avant), « Rejoindre ou regarder… » vers `PartiesScreen`. |
| `PartySheetPage` | Fiche d'une partie : Y aller, Envoyer une carte…, Suspendre / Reprendre, Supprimer ; « Carte en place… » (Enregistrer, Enregistrer sous…, Carte vide…, Vider le monde…) ; « Retoucher sur place… » (mode construction, repères, main, liste de contrôle), dans son monde. |
| `RolesPage` | Rôles et scénarios : volets Scénarios (jetons en éventail), Rôles créés (un jeton chacun), Packs (fichiers `.valcombe` déposés, « Importer ») ; « Page web ». |
| `ScenarioSheetPage` | Fiche d'un scénario : tous ses jetons, description, carte ; Choisir pour une partie… (prévient si sa carte sera posée), Partie d'essai… (nombre de joueurs), Page web, Supprimer. |
| `RoleSheetPage` | Fiche d'un rôle créé : grand jeton, camp, pouvoir, réveils, scénarios qui le citent ; Modifier sur la page web, Supprimer. |
| `AtelierArt` | Pastilles (`chip`, `ribbon`), étapes d'un parcours (`steps`), illustrations peintes (`mapScroll`, `structures`, `roles`, `compass`, `frameArt`). |
| `AtelierKit` | Fond d'une ligne ou d'une case, détails « libellé valeur », avertissements et conseils, velours et jetons en éventail (`fan`), identifiant tiré d'un nom (`slug`). |
| `ChoiceTile` | Case d'un point de départ de parcours : illustration, nom, texte, état en vert ou en rouge, cochée quand elle est choisie. |

Conventions :

- **Chaque action** passe par `run(commande, "ce qu'on fait")` : la commande `/vc` part au serveur, « ce qu'on fait… » s'affiche (trois points) jusqu'à sa réponse, qui la remplace au pied de la page (coche verte, ou croix rouge et le refus ; le texte entier au survol). Le serveur capte la réponse des commandes `vc carte`, `vc structure(s)`, `vc atelier`, `vc scenario`, `vc partie`, `vc annuler`, `vc retablir`, `vc baguette` (voir `Workshop.shows`) et l'envoie dans `atelier.reponse` ; `AtelierPage.reply()` ne montre que celles reçues depuis l'ouverture de l'Atelier.
- **L'action suivante** est mise en avant : `HdButton.primary()` (halo doré qui respire, une seule par écran). Ce qui manque pour la faire est dit en rouge à côté, et le bouton reste grisé avec la raison en infobulle.
- **Ce qui détruit** se confirme sur place : le bouton laisse la place à « Confirmer… » et « Annuler », avec la conséquence écrite en rouge.
- **Échap** défait d'abord ce que la page a en cours (`cancelPending()` : une confirmation, une saisie, une étape de parcours), puis ferme l'Atelier.
- **États vides** : une galerie vide dit quoi faire (« Aucune structure à vous : « Nouvelle structure » pour en créer une… »), et le bouton qui y mène est mis en avant.
- L'état d'une page (volet, choix, étape) est gardé en champs statiques : il survit aux reconstructions (une par nouvelle vue du serveur) et à un aller-retour.

Composants ajoutés à la boîte à outils :

| Outil | Rôle |
| --- | --- |
| `HdGrid<T>` | Galerie de cases égales (autant de colonnes qu'il en tient, élargies pour remplir), défilement comme `HdList`, flèches dans les quatre sens. `.cell(dessin)`, `.openOnClick()`, `.onActivate()`, `.key()`, `.empty()`, `.open(nom)` (tests), `.labels()`. |
| `HdButton.primary()` | L'action principale de l'écran. `isPrimary()` pour les tests. |
| `HdList.choose(i)` | Choisit une ligne comme un clic (prévient `onSelect`), pour les tests. |
| `fr.valcombe.client.Vignettes` | Images vues de dessus faites par le serveur, gardées en textures nettes avec leurs réductions : `Vignettes.draw(hd, clé, empreinte, x, y, w, h)` dessine l'image (ou un fond qui miroite en attendant, « Pas d'aperçu » si le serveur n'a pas pu). La demande part toute seule, une fois par empreinte. |

Vignettes, côté serveur (`fr.valcombe.mc.Vignettes`) : paquets `valcombe:vignette_demande` (client → serveur, la clé) et `valcombe:vignette` (serveur → client : clé, empreinte, PNG de 512 × 512). Clés : `carte:<id>` (vue de dessus enregistrée avec la carte, couleurs de carte des blocs, ou tirée de ses blocs ; repères par-dessus), `village:<thème>:<trônes>:<taille>:<oui|non>` (plan d'un village généré avant de le bâtir), `partie:<monde>` (la carte en place dans un monde de partie, refaite au plus une fois par minute), `structure:<id>` (une structure du catalogue vue de trois quarts : un petit cube par bloc aux couleurs de carte, dessus, face sud et face est éclairés différemment, seules les faces qui donnent sur le vide peintes, ombre au sol sur une scène sombre ; `Vignettes.isometric`, empreinte tirée des fichiers de la structure). L'empreinte est dans la vue (`cartes[].vignette`, `parties[].vignette`, `structures[].vignette`) : quand ce qu'elle montre change, elle change, et le client redemande l'image. Réservé aux opérateurs. Pour une autre sorte de vignette, ajouter un cas à `Vignettes.stamp` et à `handle`.

L'Atelier web reçoit les mêmes images par son API (`Atelier.api`, lien de l'Atelier exigé, donc opérateurs seulement) : `GET /api/atelier/vignette/carte/<id>.png` et `GET /api/atelier/vignette/structure/<id>.png` (PNG de 512 × 512 ; `404` si la carte ou la structure n'existe pas, `500` si l'image n'a pas pu se faire). La lecture se fait sur le fil du serveur, le dessin hors de lui. Les fiches de la page portent l'empreinte de chaque vignette : la page redemande l'image quand elle change. L'API d'administration (`docs/ADMIN-API.md`) ne sert pas de vignettes.

Vue `atelier` (opérateurs, `Workshop.gameView`) : `cartes` (par carte : `id`, `nom`, `sieges`, `maisons`, `salle`, `cote`, `hauteur`, `jouable`, `verdict`, `manques`, `modifieeLe`, `modifieeTexte`, `vignette`, `versions`, `octets`, `atelier`, `atelierNom`, `genere`, `theme`), `parties` (mondes de partie : `id`, `nom`, `principale`, `carte`, `carteNom`, `envoi`, `enCours`, `joueurs`, `vignette`, `fiche` (l'état du tableau des parties), `actuelle` (la carte en place : `id`, `nom`, `modifiee`, `sieges`, `maisons`, `controle`...), `chantier`, `scenario`, `scenarioId`, `visee` (la partie que visent les commandes de carte de l'opérateur), `ici`), `partie` et `partieId` (celle que vise l'opérateur), `fichiers` et `dossierImport` (import), `corbeille`, `chantiers` (`texte`, `monde`, `avance` de 0 à 1), `structures` (`id`, `nom`, `categorie`, `taille`, `blocs`, `repere`, `fournie`, `modifiee`, `modifieeLe`, `parcelle`, `vignette`), `atelierStructures`, `dansAtelierStructures`, `tailles`, `roles` (`id`, `nom`, `camp`, `campTexte`, `mal`, `embleme`, `texte`, `premiereNuit`, `autresNuits`, `scenarios`), `scenarios` (`id`, `nom`, `description`, `roles` en jetons : `id`, `nom`, `embleme`, `mal`, `cree`, `inconnu` ; `carte`, `carteNom`, `carteManque`, `joueurs`, `manquants`, `choisiDans`), `packs` (`fichier`, `dossier`, `octets`, `date`, `dateTexte`), `editeur`, `themes`, `tronesMin`, `tronesMax`, `reponse`. Les ateliers carte ouverts sont dans `ateliersCarte.ateliers` (avec `pose`).

Commandes ajoutées pour l'Atelier en jeu (aussi utilisables à la main, opérateurs) : `/vc structure originale <id>` (une structure fournie revient à l'originale, d'où que l'on soit), `/vc structure nouvelle-selection <rayon> <nom>` (la sélection WorldEdit au catalogue, l'identifiant tiré du nom), `/vc carte enregistrer-partie <monde> <id> [nom]` (la carte en place dans le monde d'une partie nommée), `/vc scenario <id|aucun> <monde>` (le scénario d'une autre partie que la sienne), `/vc atelier pack importer <fichier>` (un pack `.valcombe` du dossier d'import ou des partages). `/vc structures aller <id>` et `/vc structures nouvelle <taille> <nom>` conduisent d'abord dans l'atelier structures quand on est ailleurs. L'ancien aperçu `/vc carte apercu` (aperçu en cases de l'ancien écran) est retiré : les vignettes le remplacent.

Test en jeu : `AtelierHdGameTest` (captures `valcombe-atelier-hd-*.png`, mise en page vérifiée à 427 × 240, à l'échelle 3, à l'échelle 1 et en 2560 × 1440).

## Écrans du Conteur

Pupitre (`StorytellerScreen`), grimoire, fiche d'un siège (`SeatScreen`), éditeur de script, scénarios, durées et minuteurs, ordre de réveil et distribution des rôles sont passés à `HdScreen`, avec les mêmes boutons et les mêmes commandes. Ils tiennent tous dans une interface de 427 × 240.

- **Grimoire** : `Grimoire.hdTokens(x, y, w, h, max, min, clic)` pose les jetons du grimoire (rôles, linceuls, marqueurs, infobulle détaillée) sur le cercle des sièges (voir plus bas) dans la zone libre du pupitre ; le grimoire montré au Mouchard passe par la même méthode.
- **Colonne d'actions du pupitre** : boutons de 18 unités avec des écarts entre les groupes si tout tient ; sinon resserrés (16, puis 15) ; sinon la colonne défile à la molette (ascenseur à droite), les actions hors de la fenêtre restant dans l'écran, cachées. Les onglets Partie, Nuit, Jour, Parole et Fin sont un `HdTabs`.
- **`StorytellerKit`** (paquet `screen`) : mesures de texte en unités pour placer les widgets dans `build()`, bouton fléché dessiné (`ArrowButton`, libellé « ▲ » ou « ▼ » gardé pour la narration), ascenseur des listes de boutons qui défilent ligne à ligne, fond alterné des lignes d'un tableau.
- **Test en jeu** : `StorytellerHdGameTest` ouvre chaque écran sur des vues fabriquées (salon, lancement, journée chargée, nuit, 20 joueurs) à 427 × 240, vérifie qu'aucun widget visible ne sort du panneau ni n'en chevauche un autre, clique un jeton, change d'onglet, fait défiler la colonne, et capture le tout (`valcombe-conteur-hd-*.png`), aussi à l'échelle 3 et dans une interface de 640 × 360.

## Textures et polices

- **Textures** (`assets/valcombe/textures/gui/hd/`) : produites par `python tools/make_ui.py`, jamais dessinées à la main. Pour en ajouter une : une fonction dans le script (formes par distance signée, voir `button()` ou `tooltip()`), une constante dans `HdSprites` (taille et bords en unités), relancer le script ; `HdSpritesTest` vérifie l'accord entre les deux. Dimensions multiples de 8 pixels (chaîne de mipmaps).
- **Polices** (`assets/valcombe/font/hd/`) : Cinzel (titres) et Alegreya Sans, normal et Medium (texte), téléchargées depuis google/fonts à un commit figé, empreintes SHA-256 vérifiées (`python tools/make_ui.py --polices`). Licences : `licence_cinzel_ofl.txt`, `licence_alegreya_sans_ofl.txt`. Chaque face est déclarée à une échelle de tailles (`<face>_<taille>.json`, 8 à 142 pixels réels, environ 15 % d'écart) ; `FontLadder` choisit la plus proche (écart corrigé par une mise à l'échelle de moins de 8 %). `FontLadderTest` vérifie l'accord entre le script et le code, et le décalage vertical de chaque taille.
- **Lissage** : `GlyphRenderStateMixin` filtre linéairement les lettres de nos polices (et seulement elles), pour que la petite mise à l'échelle reste lisse. Les jetons et pictogrammes du jeu (`textures/gui/*.png`, redessinés en 128 px par ailleurs) sont dessinés tels quels à n'importe quelle taille par `hd.image` et `HdToken`.

## Tests

- JUnit : `FontLadderTest`, `HdSpritesTest`, `ScrollTest`, `TextEditorTest`, `RingLayoutTest`, `VersionCheckTest` (logique pure, sans Minecraft).
- En jeu : `HdGameTest` (polices chargées, réglages en captures aux échelles 1, 2 et 3, interrupteur qui change le réglage sur le serveur, infobulle, vitrine : saisie, défilement, onglets, mémoire après reconstruction). Captures `valcombe-hd-*.png` dans `build/run/clientGameTest/screenshots/`.

## Limites connues

- La découpe (`clip`, listes) est alignée sur les unités d'interface : c'est la précision du découpage de Minecraft.
- Un caractère absent des polices (symboles rares) vient de la police de Minecraft, en petit : préférer les formes dessinées (`disc`, `triangle`) aux symboles.
- Les infobulles vanilla (`setTooltip`) restent en police Minecraft : utiliser `.tooltip()` des composants HD.
