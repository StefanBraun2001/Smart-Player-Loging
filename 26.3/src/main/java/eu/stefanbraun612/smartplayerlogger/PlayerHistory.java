package eu.stefanbraun612.smartplayerlogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The data behind PA_History.txt: every player that ever joined, keyed by UUID (names change),
 * rendered alphabetically (or newest-first, per config). Not thread-safe; owned by the IO thread.
 *
 * Line format: {@code Name (uuid) - Last seen: 14.10.2026 21:05:33}, or {@code Online}, or
 * a timestamp followed by {@code (estimated)} when it was reconstructed from world files.
 */
final class PlayerHistory {
	static final class Entry {
		UUID uuid;
		String name; // null = unknown
		Instant lastSeen; // null while online
		boolean online;
		boolean estimated;
	}

	private static final Pattern LINE = Pattern.compile(
			"^(.*) \\(([0-9a-fA-F-]{36})\\) - Last seen: (Online|" + TimeFmt.PATTERN_REGEX + ")( \\(estimated\\))?\\s*$");
	private static final String UNKNOWN_NAME = "Unknown";

	private final Path file;
	private final boolean alphabetic;
	private final Map<UUID, Entry> entries = new HashMap<>();

	PlayerHistory(Path file, boolean alphabetic) {
		this.file = file;
		this.alphabetic = alphabetic;
	}

	boolean fileExists() {
		return Files.exists(file);
	}

	/**
	 * A line saying "Online" in the file means the server stopped without a clean shutdown, so
	 * the real leave time is unknown; the best available guess is when the file was last written.
	 */
	void load() throws IOException {
		entries.clear();
		if (!Files.exists(file)) {
			return;
		}
		Instant fileTime = Files.getLastModifiedTime(file).toInstant();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			Matcher m = LINE.matcher(line);
			if (!m.matches()) {
				continue;
			}
			Entry e = new Entry();
			e.uuid = UUID.fromString(m.group(2));
			e.name = UNKNOWN_NAME.equals(m.group(1)) ? null : m.group(1);
			if (m.group(3).equals("Online")) {
				e.lastSeen = fileTime;
				e.estimated = true;
			} else {
				e.lastSeen = TimeFmt.parse(m.group(3));
				e.estimated = m.group(4) != null;
				if (e.lastSeen == null) {
					continue;
				}
			}
			entries.put(e.uuid, e);
		}
	}

	void markOnline(UUID uuid, String name) {
		Entry e = entries.computeIfAbsent(uuid, k -> {
			Entry n = new Entry();
			n.uuid = k;
			return n;
		});
		e.name = name;
		e.online = true;
		e.lastSeen = null;
		e.estimated = false;
	}

	void markOffline(UUID uuid, String name, Instant when) {
		Entry e = entries.computeIfAbsent(uuid, k -> {
			Entry n = new Entry();
			n.uuid = k;
			return n;
		});
		e.name = name;
		e.online = false;
		e.lastSeen = when;
		e.estimated = false;
	}

	/** Adds players we have no record of, flagged as estimated. Returns how many were added. */
	int seed(Map<UUID, Instant> lastSeen, Function<UUID, String> nameLookup) {
		int added = 0;
		for (Map.Entry<UUID, Instant> src : lastSeen.entrySet()) {
			if (entries.containsKey(src.getKey())) {
				continue;
			}
			Entry e = new Entry();
			e.uuid = src.getKey();
			e.name = nameLookup.apply(e.uuid);
			e.lastSeen = src.getValue();
			e.estimated = true;
			entries.put(e.uuid, e);
			added++;
		}
		return added;
	}

	Entry get(UUID uuid) {
		return entries.get(uuid);
	}

	int size() {
		return entries.size();
	}

	List<String> render() {
		List<Entry> sorted = new ArrayList<>(entries.values());
		Comparator<Entry> byName = Comparator
				.comparing((Entry e) -> e.name == null ? UNKNOWN_NAME : e.name, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(e -> e.uuid);
		// Online players count as "seen right now", so they come first, then newest last-seen.
		Comparator<Entry> byRecency = Comparator
				.comparing((Entry e) -> !e.online)
				.thenComparing(Comparator.comparing((Entry e) -> e.lastSeen == null ? Instant.MIN : e.lastSeen).reversed())
				.thenComparing(byName);
		sorted.sort(alphabetic ? byName : byRecency);
		List<String> out = new ArrayList<>(sorted.size());
		for (Entry e : sorted) {
			StringBuilder sb = new StringBuilder();
			sb.append(e.name == null ? UNKNOWN_NAME : e.name).append(" (").append(e.uuid).append(") - Last seen: ");
			if (e.online) {
				sb.append("Online");
			} else {
				sb.append(TimeFmt.format(e.lastSeen));
				if (e.estimated) {
					sb.append(" (estimated)");
				}
			}
			out.add(sb.toString());
		}
		return out;
	}

	void flush() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String line : render()) {
			sb.append(line).append(System.lineSeparator());
		}
		FileUtil.writeAtomic(file, sb.toString());
	}
}
