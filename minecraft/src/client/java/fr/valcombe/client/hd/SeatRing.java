package fr.valcombe.client.hd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le cercle des sièges dans un écran : RingLayout mesuré avec la vraie police des noms, à l'échelle d'interface en
 * cours. Les dernières mises en page sont gardées : un écran se reconstruit à chaque vue du serveur (souvent, pendant
 * un vote), mais les noms et la zone, eux, changent rarement.
 *
 * <pre>
 * RingLayout.Result ring = SeatRing.layout(noms, SeatRing.spec(x, y, w, h).tokens(26, 14));
 * for (int i = 0; i &lt; noms.size(); i++) {
 *     RingLayout.Seat s = ring.seats().get(i);
 *     token(new HdToken(s.cx(), s.cy(), ring.size(), noms.get(i), ...).seat(s));
 * }
 * </pre>
 */
public final class SeatRing {
	/** Style des noms sur leurs plaques. */
	public static final HdStyle LABEL = HdStyle.PETIT;
	private static final int KEEP = 12;

	private record Key(List<String> names, RingLayout.Box zone, int max, int min, double wide, double tall,
			int scale) {
	}

	private static final Map<Key, RingLayout.Result> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, RingLayout.Result> eldest) {
			return size() > KEEP;
		}
	};

	private SeatRing() {
	}

	/** Ce que l'on demande au cercle dans la zone (x, y, w, h en unités), noms mesurés à l'échelle en cours. */
	public static RingLayout.Spec spec(double x, double y, double w, double h) {
		int s = Hd.guiScale();
		return new RingLayout.Spec(x, y, w, h, HdFonts.lineHeight(LABEL, s) / (double) s,
			text -> HdFonts.width(LABEL, s, text) / (double) s);
	}

	/** La mise en page de ces noms (dans l'ordre des sièges), reprise telle quelle si rien n'a changé. */
	public static RingLayout.Result layout(List<String> names, RingLayout.Spec spec) {
		Key key = new Key(List.copyOf(names), spec.zone, spec.sizeMax, spec.sizeMin, spec.maxWide, spec.maxTall,
			Hd.guiScale());
		return CACHE.computeIfAbsent(key, k -> RingLayout.layout(k.names(), spec));
	}
}
