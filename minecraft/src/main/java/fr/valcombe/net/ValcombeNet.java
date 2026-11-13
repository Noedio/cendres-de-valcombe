package fr.valcombe.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Paquets échangés entre le serveur et le mod client. */
public final class ValcombeNet {
	private static final int MAX = 1 << 20;

	/** Serveur → client : la vue de la partie, en JSON. */
	public record ViewPayload(String json) implements CustomPacketPayload {
		public static final Type<ViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("valcombe", "view"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ViewPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX), ViewPayload::json, ViewPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → serveur : une commande /vc (sans la barre oblique), ex. « vc vote oui ». */
	public record ActionPayload(String command) implements CustomPacketPayload {
		public static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("valcombe", "action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.stringUtf8(512), ActionPayload::command, ActionPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Serveur → client : ouvrir un écran (« main », « storyteller », « sheets »…). */
	public record OpenPayload(String screen) implements CustomPacketPayload {
		public static final Type<OpenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("valcombe", "open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.stringUtf8(64), OpenPayload::screen, OpenPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Serveur → client : l'aperçu vu de dessus d'une carte (couleurs et repères, en JSON), pour l'écran de l'Atelier. */
	public record PreviewPayload(String json) implements CustomPacketPayload {
		public static final Type<PreviewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("valcombe", "apercu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PreviewPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.stringUtf8(1 << 16), PreviewPayload::json, PreviewPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Serveur → client : la sélection WorldEdit du joueur, dans le monde où il est, pour la dessiner en 3D avec ses
	 * dimensions. Envoyée quand elle change (et vide quand il n'en a plus ici).
	 *
	 * @param shape  forme ({@code cuboide}, {@code polygone}, {@code ellipsoide}, {@code cylindre}, {@code sphere},
	 *               {@code enveloppe}) ; vide : pas de sélection à montrer
	 * @param box    boîte englobante : x, y, z du coin le plus bas puis du plus haut ; vide tant qu'elle est incomplète
	 * @param first  premier point (x, y, z), ou vide
	 * @param second second point (x, y, z), ou vide
	 * @param volume nombre de blocs
	 */
	public record SelectionPayload(String shape, int[] box, int[] first, int[] second, long volume) implements CustomPacketPayload {
		public static final Type<SelectionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("valcombe", "selection"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SelectionPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.shape(), 32);
				buf.writeVarIntArray(p.box());
				buf.writeVarIntArray(p.first());
				buf.writeVarIntArray(p.second());
				buf.writeVarLong(p.volume());
			},
			buf -> new SelectionPayload(buf.readUtf(32), buf.readVarIntArray(6), buf.readVarIntArray(3), buf.readVarIntArray(3),
				buf.readVarLong()));

		/** Rien à montrer. */
		public static final SelectionPayload NONE = new SelectionPayload("", new int[0], new int[0], new int[0], 0);

		public boolean empty() {
			return shape.isEmpty();
		}

		/** Ce qui la distingue d'une autre (pour n'envoyer que les changements). */
		public String signature() {
			return shape + java.util.Arrays.toString(box) + java.util.Arrays.toString(first) + java.util.Arrays.toString(second) + volume;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private ValcombeNet() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().registerLarge(ViewPayload.TYPE, ViewPayload.CODEC, MAX * 4);
		PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreviewPayload.TYPE, PreviewPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SelectionPayload.TYPE, SelectionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
	}
}
