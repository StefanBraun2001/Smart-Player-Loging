package eu.stefanbraun612.smartplayerlogger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The rules: what goes into PA_Logger and PA_History for each event. No Minecraft types and no
 * threading of its own - {@link LoggerService} calls it from a single IO thread.
 */
final class ActivityTracker {
	private record Pending(ActivityLog.Entry leaveEntry, Instant leftAt) {
	}

	/** One refusal line that later identical attempts get folded into. */
	private static final class Refusal {
		ActivityLog.Entry entry;
		final String who;
		final String why;
		final Instant first;
		Instant last;
		int count = 1;

		Refusal(ActivityLog.Entry entry, String who, String why, Instant first) {
			this.entry = entry;
			this.who = who;
			this.why = why;
			this.first = first;
			this.last = first;
		}

		String text() {
			String reason = why == null ? " (no reason given)" : ": " + why;
			if (count == 1) {
				return who + " was refused at " + TimeFmt.format(first) + reason;
			}
			return who + " was refused " + count + " times between " + TimeFmt.format(first)
					+ " and " + TimeFmt.format(last) + reason;
		}
	}

	private final LoggerConfig config;
	private final ActivityLog log;
	private final ActivityLog refusalLog; // same object as log when refusals are not separated
	private final PlayerHistory history;
	private final Consumer<String> warn;
	private final Runnable historyDirty;
	private final Runnable refusalDirty;

	private final Map<UUID, String> online = new HashMap<>();
	private final Map<UUID, Pending> pendingLeaves = new HashMap<>();
	private final Map<String, Refusal> recentRefusals = new HashMap<>();

	ActivityTracker(LoggerConfig config, ActivityLog log, ActivityLog refusalLog, PlayerHistory history,
			Consumer<String> warn, Runnable historyDirty, Runnable refusalDirty) {
		this.config = config;
		this.log = log;
		this.refusalLog = refusalLog;
		this.history = history;
		this.warn = warn;
		this.historyDirty = historyDirty;
		this.refusalDirty = refusalDirty;
	}

	void onJoin(UUID uuid, String name, Instant now) {
		prunePending(now);
		online.put(uuid, name);
		Pending p = pendingLeaves.remove(uuid);
		boolean merged = false;
		if (p != null && config.mergeReconnects
				&& Duration.between(p.leftAt(), now).compareTo(Duration.ofMinutes(config.reconnectThresholdMinutes)) <= 0
				&& log.remove(p.leaveEntry())) {
			log.add(clean(name) + " reconnected (Left: " + TimeFmt.format(p.leftAt()) + ", Return: " + TimeFmt.format(now) + ")");
			merged = true;
		}
		if (!merged) {
			log.add(clean(name) + " joined the game at " + TimeFmt.format(now));
		}
		flushLog();
		history.markOnline(uuid, name);
		historyDirty.run();
	}

	void onLeave(UUID uuid, String name, Instant now) {
		if (online.remove(uuid) == null) {
			return; // never seen joining (or already handled at shutdown)
		}
		prunePending(now);
		ActivityLog.Entry entry = log.add(clean(name) + " left the game at " + TimeFmt.format(now));
		pendingLeaves.put(uuid, new Pending(entry, now));
		flushLog();
		history.markOffline(uuid, name, now);
		historyDirty.run();
	}

	void onRefused(String name, String reason, Instant now) {
		if (!config.logRefusedConnections) {
			return;
		}
		String who = name == null || name.isBlank() ? "Unknown player" : clean(name);
		String why = reason == null || reason.isBlank() ? null : clean(reason);
		String key = who + "\n" + why;
		if (config.refusalDedupeSeconds > 0) {
			Refusal r = recentRefusals.get(key);
			if (r != null && Duration.between(r.first, now).getSeconds() < config.refusalDedupeSeconds) {
				r.count++;
				r.last = now;
				refusalLog.setText(r.entry, r.text());
				refusalDirty.run(); // bursts are coalesced into one write
				return;
			}
			if (recentRefusals.size() > 500) {
				recentRefusals.values().removeIf(x -> Duration.between(x.first, now).getSeconds() >= config.refusalDedupeSeconds);
			}
		}
		Refusal r = new Refusal(null, who, why, now);
		r.entry = refusalLog.add(r.text());
		if (config.refusalDedupeSeconds > 0) {
			recentRefusals.put(key, r);
		}
		flushRefusals();
	}

	/** Server is stopping: record everyone still online as having left. */
	void onShutdown(Instant now) {
		for (Map.Entry<UUID, String> e : Map.copyOf(online).entrySet()) {
			onLeave(e.getKey(), e.getValue(), now);
		}
	}

	private void prunePending(Instant now) {
		Duration limit = Duration.ofMinutes(config.reconnectThresholdMinutes);
		for (Iterator<Pending> it = pendingLeaves.values().iterator(); it.hasNext(); ) {
			if (Duration.between(it.next().leftAt(), now).compareTo(limit) > 0) {
				it.remove();
			}
		}
	}

	void flushRefusals() {
		try {
			refusalLog.flush();
		} catch (IOException e) {
			warn.accept("Could not write the refusal log: " + e.getMessage());
		}
	}

	private void flushLog() {
		try {
			log.flush();
		} catch (IOException e) {
			warn.accept("Could not write PA_Logger.txt: " + e.getMessage());
		}
	}

	/** One entry = one line: collapse line breaks (e.g. multi-line ban messages). */
	static String clean(String s) {
		return s.strip().replaceAll("\\s*\\R\\s*", " | ");
	}
}
