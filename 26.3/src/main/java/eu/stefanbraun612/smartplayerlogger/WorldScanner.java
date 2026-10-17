package eu.stefanbraun612.smartplayerlogger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reconstructs "last seen" for the first-run import. The game never stored this, so the best
 * proxy is the modification time of each player's save files (written on leave and on autosave).
 * That is a guess, which is why entries built from it are flagged "(estimated)". The layout of
 * those folders differs between versions, so every known location is checked.
 */
final class WorldScanner {
	private static final String[] PLAYER_DIRS = {
			"playerdata", "players/data",
			"stats", "players/stats",
			"advancements", "players/advancements"
	};
	private static final String[] NAME_FILES = {
			"usercache.json", "whitelist.json", "ops.json", "banned-players.json"
	};

	private WorldScanner() {
	}

	/** UUID -> newest modification time of any per-player file in the world folder. */
	static Map<UUID, Instant> scanLastSeen(Path worldRoot) {
		Map<UUID, Instant> out = new HashMap<>();
		for (String rel : PLAYER_DIRS) {
			Path dir = worldRoot.resolve(rel);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
				for (Path f : files) {
					String fileName = f.getFileName().toString();
					int dot = fileName.indexOf('.');
					if (dot < 0 || !(fileName.endsWith(".dat") || fileName.endsWith(".json"))) {
						continue;
					}
					UUID id = parseUuid(fileName.substring(0, dot));
					if (id == null) {
						continue;
					}
					Instant t = Files.getLastModifiedTime(f).toInstant();
					out.merge(id, t, (a, b) -> a.isAfter(b) ? a : b);
				}
			} catch (IOException ignored) {
				// unreadable folder: skip it, other sources may still work
			}
		}
		return out;
	}

	/** UUID -> name from the server's own JSON lists. Missing or broken files are skipped. */
	static Map<UUID, String> readNames(Path serverDir) {
		Map<UUID, String> out = new HashMap<>();
		for (String name : NAME_FILES) {
			Path f = serverDir.resolve(name);
			if (!Files.isRegularFile(f)) {
				continue;
			}
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(r);
				if (!root.isJsonArray()) {
					continue;
				}
				JsonArray arr = root.getAsJsonArray();
				for (JsonElement el : arr) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject o = el.getAsJsonObject();
					if (!o.has("uuid") || !o.has("name") || !o.get("uuid").isJsonPrimitive() || !o.get("name").isJsonPrimitive()) {
						continue;
					}
					UUID id = parseUuid(o.get("uuid").getAsString());
					if (id != null) {
						out.putIfAbsent(id, o.get("name").getAsString());
					}
				}
			} catch (IOException | RuntimeException ignored) {
				// malformed file: skip
			}
		}
		return out;
	}

	private static UUID parseUuid(String s) {
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
