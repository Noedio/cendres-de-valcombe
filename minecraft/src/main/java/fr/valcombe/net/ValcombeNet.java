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

	private ValcombeNet() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().registerLarge(ViewPayload.TYPE, ViewPayload.CODEC, MAX * 4);
		PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreviewPayload.TYPE, PreviewPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
	}
}
