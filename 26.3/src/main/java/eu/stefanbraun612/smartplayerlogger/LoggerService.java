package eu.stefanbraun612.smartplayerlogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns P_Logger/ and a single background thread that does all file IO, so the server thread
 * (and Netty threads) only ever enqueue a small task and never wait on disk.
 */
public final class LoggerService {
	private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, r -> {
		Thread t = new Thread(r, "SmartPlayerLogger-IO");
		t.setDaemon(true);
		return t;
	});
	private final Consumer<String> info;
	private final Consumer<String> warn;
	private final ActivityTracker tracker;
	private final PlayerHistory history;
	private final boolean firstRun;
	private boolean historyFlushQueued; // IO thread only
	private boolean refusalFlushQueued; // IO thread only

	private LoggerService(Path dir, Consumer<String> info, Consumer<String> warn) throws IOException {
		this.info = info;
		this.warn = warn;
		io.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		Files.createDirectories(dir);

		LoggerConfig config = LoggerConfig.load(dir.resolve("config.json"), warn);
		ActivityLog log = new ActivityLog(dir.resolve("PA_Logger.txt"), config.maxLines);
		try {
			log.load();
		} catch (IOException e) {
			warn.accept("Could not read PA_Logger.txt, starting empty: " + e.getMessage());
		}
		try {
			log.flush(); // creates the file and applies a lowered maxLines right away
		} catch (IOException e) {
			warn.accept("Could not write PA_Logger.txt: " + e.getMessage());
		}

		ActivityLog refusalLog = log;
		if (config.logRefusedConnections && config.separateRefusalLog) {
			refusalLog = new ActivityLog(dir.resolve("PA_Refusals.txt"), config.maxLines);
			try {
				refusalLog.load();
			} catch (IOException e) {
				warn.accept("Could not read PA_Refusals.txt, starting empty: " + e.getMessage());
			}
			try {
				refusalLog.flush();
			} catch (IOException e) {
				warn.accept("Could not write PA_Refusals.txt: " + e.getMessage());
			}
		}

		history = new PlayerHistory(dir.resolve("PA_History.txt"), config.History_alphabetic);
		firstRun = !history.fileExists();
		try {
			history.load();
		} catch (IOException e) {
			warn.accept("Could not read PA_History.txt, starting empty: " + e.getMessage());
		}
		tracker = new ActivityTracker(config, log, refusalLog, history, warn, this::queueHistoryFlush, this::queueRefusalFlush);
	}

	public static LoggerService start(Path dir, Consumer<String> info, Consumer<String> warn) throws IOException {
		return new LoggerService(dir, info, warn);
	}

	public void playerJoined(UUID uuid, String name) {
		Instant now = Instant.now();
		submit(() -> tracker.onJoin(uuid, name, now));
	}

	public void playerLeft(UUID uuid, String name) {
		Instant now = Instant.now();
		submit(() -> tracker.onLeave(uuid, name, now));
	}

	public void connectionRefused(String name, String reason) {
		Instant now = Instant.now();
		submit(() -> tracker.onRefused(name, reason, now));
	}

	public void serverStopping() {
		Instant now = Instant.now();
		submit(() -> tracker.onShutdown(now));
	}

	/**
	 * On the very first run (no PA_History.txt yet) fills the history from the world's save
	 * files. Always creates the file so it exists after the first start.
	 */
	public void seedHistoryIfFirstRun(Path worldRoot, Path serverDir) {
		submit(() -> {
			if (firstRun) {
				Map<UUID, Instant> seen = WorldScanner.scanLastSeen(worldRoot);
				Map<UUID, String> names = WorldScanner.readNames(serverDir);
				int added = history.seed(seen, names::get);
				info.accept("First run: imported " + added + " known player(s) into PA_History.txt (last-seen times are estimated from world files).");
			}
			flushHistory();
		});
	}

	public void stop() {
		submit(() -> {
			flushHistory();
			tracker.flushRefusals();
		});
		io.shutdown();
		try {
			if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
				warn.accept("Timed out while finishing log writes.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void queueHistoryFlush() {
		if (historyFlushQueued) {
			return;
		}
		historyFlushQueued = true;
		try {
			io.schedule(() -> {
				historyFlushQueued = false;
				flushHistory();
			}, 2, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			historyFlushQueued = false;
			flushHistory();
		}
	}

	private void queueRefusalFlush() {
		if (refusalFlushQueued) {
			return;
		}
		refusalFlushQueued = true;
		try {
			io.schedule(() -> {
				refusalFlushQueued = false;
				tracker.flushRefusals();
			}, 2, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			refusalFlushQueued = false;
			tracker.flushRefusals();
		}
	}

	private void flushHistory() {
		try {
			history.flush();
		} catch (IOException e) {
			warn.accept("Could not write PA_History.txt: " + e.getMessage());
		}
	}

	private void submit(Runnable task) {
		try {
			io.execute(() -> {
				try {
					task.run();
				} catch (Throwable t) {
					warn.accept("Logger task failed: " + t);
				}
			});
		} catch (RejectedExecutionException ignored) {
			// already shut down
		}
	}
}
