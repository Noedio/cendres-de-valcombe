package fr.valcombe.admin;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * Appender log4j qui recopie chaque ligne du journal (celle que la console du serveur affiche) dans un
 * {@link ConsoleBuffer}. Il se branche sur le journal racine, au niveau que la configuration du serveur
 * a déjà choisi : on voit exactement ce que voit la console.
 *
 * <p>Un appender ne doit jamais écrire lui-même dans le journal (il s'appellerait en boucle) ni lever
 * d'exception : tout est rattrapé en silence.
 */
public final class ConsoleCapture extends AbstractAppender {
	public static final String NAME = "ValcombeConsole";
	/** Lignes de trace gardées par erreur : assez pour situer le problème, pas de quoi noyer le tampon. */
	private static final int STACK_LINES = 40;

	private final ConsoleBuffer buffer;

	private ConsoleCapture(ConsoleBuffer buffer) {
		super(NAME, null, null, true, Property.EMPTY_ARRAY);
		this.buffer = buffer;
	}

	@Override
	public void append(LogEvent event) {
		try {
			String text = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
			Throwable t = event.getThrown();
			if (t != null) {
				text = text + "\n" + trace(t);
			}
			buffer.append(event.getTimeMillis(), event.getLevel() == null ? "" : event.getLevel().name(),
				event.getThreadName(), event.getLoggerName(), text);
		} catch (RuntimeException e) {
			// rien : la console ne doit jamais être gênée par sa copie
		}
	}

	private static String trace(Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		String[] lines = w.toString().split("\\R");
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < lines.length && i < STACK_LINES; i++) {
			out.append(i == 0 ? "" : "\n").append(lines[i]);
		}
		if (lines.length > STACK_LINES) {
			out.append("\n\t… ").append(lines.length - STACK_LINES).append(" lignes de plus");
		}
		return out.toString();
	}

	/**
	 * Branche la copie sur le journal racine. Renvoie faux si le journal n'est pas celui de log4j (rien n'est
	 * alors copié, l'API répond avec une console vide). Une seconde installation ne fait rien.
	 */
	public static boolean install(ConsoleBuffer buffer) {
		try {
			if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
				return false;
			}
			Configuration config = context.getConfiguration();
			if (config.getAppender(NAME) != null) {
				return true;
			}
			ConsoleCapture appender = new ConsoleCapture(buffer);
			appender.start();
			config.addAppender(appender);
			config.getRootLogger().addAppender(appender, null, null);
			context.updateLoggers();
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}
}
