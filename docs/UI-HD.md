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

Couleurs (`Hd.*`) : `INK` (encre, texte sur parchemin), `INK_SOFT` (texte secondaire), `WINE`, `BRONZE`, `GOLD`, `PARCH`, `ASH`, `LIGHT` (texte sur fond sombre), `MUTED` (inactif, indication), `SELECTION`, `HOVER`, `TEXT_SELECTION`.

Les textes affichés restent en français, sans tiret cadratin ni demi-cadratin.

## Passer un écran de BaseScreen à HdScreen

1. `extends BaseScreen` devient `extends HdScreen` (paquet `fr.valcombe.client.hd`).
2. `drawContent(GuiGraphicsExtractor g, ...)` devient `drawHd(Hd hd)` : `Theme.centered(g, font, s, x, y, c)` devient `hd.textCentered(HdStyle.TEXTE, s, hd.px(x), hd.px(y), c)`, `Theme.paragraph` devient `hd.paragraph`, `Theme.panel` devient `hd.panel`, `Theme.token` un `HdToken`.
3. `button(...)` rend déjà un `HdButton`. Les boutons « libellé : oui/non » deviennent des `toggle(...)` (le message reste le même). `setTooltip(Tooltip.create(...))` devient `.tooltip("...")`.
4. Les `TokenWidget` deviennent des `HdToken` (mêmes paramètres), les `EditBox` des `HdTextField` (avec une clé), les listes faites à la main des `HdList`.
5. Garder les mêmes commandes et les mêmes libellés de boutons : le test en jeu clique les boutons par leur libellé.
6. Une action qui reconstruit l'écran sur place (onglet, filtre local) appelle `rebuildSoon()`.
7. Vérifier : `./gradlew build --offline -q`, puis une capture dans le test en jeu.

## Textures et polices

- **Textures** (`assets/valcombe/textures/gui/hd/`) : produites par `python tools/make_ui.py`, jamais dessinées à la main. Pour en ajouter une : une fonction dans le script (formes par distance signée, voir `button()` ou `tooltip()`), une constante dans `HdSprites` (taille et bords en unités), relancer le script ; `HdSpritesTest` vérifie l'accord entre les deux. Dimensions multiples de 8 pixels (chaîne de mipmaps).
- **Polices** (`assets/valcombe/font/hd/`) : Cinzel (titres) et Alegreya Sans, normal et Medium (texte), téléchargées depuis google/fonts à un commit figé, empreintes SHA-256 vérifiées (`python tools/make_ui.py --polices`). Licences : `licence_cinzel_ofl.txt`, `licence_alegreya_sans_ofl.txt`. Chaque face est déclarée à une échelle de tailles (`<face>_<taille>.json`, 8 à 142 pixels réels, environ 15 % d'écart) ; `FontLadder` choisit la plus proche (écart corrigé par une mise à l'échelle de moins de 8 %). `FontLadderTest` vérifie l'accord entre le script et le code, et le décalage vertical de chaque taille.
- **Lissage** : `GlyphRenderStateMixin` filtre linéairement les lettres de nos polices (et seulement elles), pour que la petite mise à l'échelle reste lisse. Les jetons et pictogrammes du jeu (`textures/gui/*.png`, redessinés en 128 px par ailleurs) sont dessinés tels quels à n'importe quelle taille par `hd.image` et `HdToken`.

## Tests

- JUnit : `FontLadderTest`, `HdSpritesTest`, `ScrollTest`, `TextEditorTest` (logique pure, sans Minecraft).
- En jeu : `HdGameTest` (polices chargées, réglages en captures aux échelles 1, 2 et 3, interrupteur qui change le réglage sur le serveur, infobulle, vitrine : saisie, défilement, onglets, mémoire après reconstruction). Captures `valcombe-hd-*.png` dans `build/run/clientGameTest/screenshots/`.

## Limites connues

- La découpe (`clip`, listes) est alignée sur les unités d'interface : c'est la précision du découpage de Minecraft.
- Un caractère absent des polices (symboles rares) vient de la police de Minecraft, en petit : préférer les formes dessinées (`disc`, `triangle`) aux symboles.
- Les infobulles vanilla (`setTooltip`) restent en police Minecraft : utiliser `.tooltip()` des composants HD.
