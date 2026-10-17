package eu.stefanbraun612.smartplayerlogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The line buffer behind PA_Logger.txt. Not thread-safe; owned by the IO thread. */
final class ActivityLog {
	static final class Entry {
		String text;

		Entry(String text) {
			this.text = text;
		}
	}

	private final Path file;
	private final List<Entry> lines = new ArrayList<>();
	private int maxLines;

	ActivityLog(Path file, int maxLines) {
		this.file = file;
		this.maxLines = maxLines;
	}

	void load() throws IOException {
		lines.clear();
		if (Files.exists(file)) {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (!line.isBlank()) {
					lines.add(new Entry(line));
				}
			}
		}
		trim();
	}

	Entry add(String text) {
		Entry e = new Entry(text);
		lines.add(e);
		trim();
		return e;
	}

	void setText(Entry entry, String text) {
		entry.text = text;
	}

	/** Removes this exact entry; false if it was already trimmed away. */
	boolean remove(Entry entry) {
		for (int i = lines.size() - 1; i >= 0; i--) {
			if (lines.get(i) == entry) {
				lines.remove(i);
				return true;
			}
		}
		return false;
	}

	List<String> snapshot() {
		List<String> out = new ArrayList<>(lines.size());
		for (Entry e : lines) {
			out.add(e.text);
		}
		return out;
	}

	void flush() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (Entry e : lines) {
			sb.append(e.text).append(System.lineSeparator());
		}
		FileUtil.writeAtomic(file, sb.toString());
	}

	private void trim() {
		int excess = lines.size() - maxLines;
		if (excess > 0) {
			lines.subList(0, excess).clear();
		}
	}
}
