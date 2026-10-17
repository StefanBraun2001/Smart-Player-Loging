package eu.stefanbraun612.smartplayerlogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class LoggerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** How many lines PA_Logger keeps; the oldest are dropped first. */
	public int maxLines = 100;

	/** Turn a leave + quick return into a single "reconnected" line. */
	public boolean mergeReconnects = true;
	/** A return within this many minutes after a leave counts as a reconnect. */
	public int reconnectThresholdMinutes = 5;

	/** Also write a line (with the reason, if known) when a connection is refused. */
	public boolean logRefusedConnections = true;
	/** Write refusals to their own file (PA_Refusals.txt) instead of PA_Logger.txt. */
	public boolean separateRefusalLog = true;
	/** Identical refusals (same name + reason) within this many seconds are merged into one line that says how many attempts there were. 0 = log all separately. */
	public int refusalDedupeSeconds = 30;

	/** PA_History order: true = alphabetical by name, false = most recently online first (online players on top). */
	public boolean History_alphabetic = true;

	/**
	 * Loads the config, creating it with defaults if missing. Missing keys are filled in and
	 * invalid values are clamped. A file that cannot be parsed is left untouched and defaults are
	 * used for this run.
	 */
	public static LoggerConfig load(Path file, Consumer<String> warn) {
		LoggerConfig cfg = new LoggerConfig();
		boolean parsed = true;
		String existing = null;
		if (Files.exists(file)) {
			try {
				existing = Files.readString(file, StandardCharsets.UTF_8);
				LoggerConfig read = GSON.fromJson(existing, LoggerConfig.class);
				if (read != null) {
					cfg = read;
				}
			} catch (IOException | JsonParseException e) {
				warn.accept("Could not read " + file.getFileName() + " (" + e.getMessage()
						+ "); using defaults for this run and leaving the file untouched.");
				parsed = false;
			}
		}
		cfg.sanitize();
		if (parsed) {
			String out = GSON.toJson(cfg) + System.lineSeparator();
			if (!out.equals(existing)) {
				try {
					FileUtil.writeAtomic(file, out);
				} catch (IOException e) {
					warn.accept("Could not write " + file.getFileName() + ": " + e.getMessage());
				}
			}
		}
		return cfg;
	}

	void sanitize() {
		if (maxLines < 1) {
			maxLines = 1;
		}
		if (reconnectThresholdMinutes < 0) {
			reconnectThresholdMinutes = 0;
		}
		if (refusalDedupeSeconds < 0) {
			refusalDedupeSeconds = 0;
		}
	}
}
