package eu.stefanbraun612.smartplayerlogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class FileUtil {
	private FileUtil() {
	}

	/**
	 * Writes via a temp file + move so a crash never leaves a half-written file. If the move is
	 * refused (on Windows when another program, e.g. a tail viewer, has the target open) it falls
	 * back to overwriting the file directly.
	 */
	static void writeAtomic(Path target, String content) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = dir.resolve(target.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				Files.writeString(target, content, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}
}
