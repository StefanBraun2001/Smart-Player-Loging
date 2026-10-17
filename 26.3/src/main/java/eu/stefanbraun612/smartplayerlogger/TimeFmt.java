package eu.stefanbraun612.smartplayerlogger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** European date (dd.MM.yyyy) with a 24 hour clock, in the server's local time zone. */
public final class TimeFmt {
	public static final String PATTERN_REGEX = "\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2}";

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

	private TimeFmt() {
	}

	public static String format(Instant instant) {
		return FORMAT.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
	}

	/** Returns null if the text is not a timestamp in our format. */
	public static Instant parse(String text) {
		try {
			return LocalDateTime.parse(text, FORMAT).atZone(ZoneId.systemDefault()).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
