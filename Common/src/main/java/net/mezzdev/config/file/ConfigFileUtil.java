package net.mezzdev.config.file;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.WriteOptions;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

public final class ConfigFileUtil {
	public static final int MAX_BACKUPS = 5;
	static final WriteOptions WRITE_OPTIONS = WriteOptions.DEFAULT
		.withAtomicMovePolicy(AtomicMovePolicy.ALLOW_NON_ATOMIC);

	private ConfigFileUtil() {
	}

	public static void writeUsingTempFile(Path path, List<? extends CharSequence> lines) throws IOException {
		writeUsingTempFileAndGetFingerprint(path, lines);
	}

	static String writeUsingTempFileAndGetFingerprint(Path path, List<? extends CharSequence> lines) throws IOException {
		return writeUsingTempFileAndGetFingerprint(path, lines, null);
	}

	static String writeUsingTempFileAndGetFingerprint(
		Path path,
		List<? extends CharSequence> lines,
		@Nullable String loadedFingerprint
	) throws IOException {
		byte[] bytes = serializeLines(lines);
		MessageDigest digest = ConfigFileReader.newFingerprintDigest();
		digest.update(bytes);
		String fingerprint = ConfigFileReader.finishFingerprint(digest);
		// A startup refresh can compare with the bytes already read while loading the config.
		boolean unchanged;
		if (loadedFingerprint == null) {
			unchanged = hasSameContents(path, bytes);
		} else {
			unchanged = fingerprint.equals(loadedFingerprint);
		}
		if (unchanged) {
			return fingerprint;
		}
		AtomicFiles.write(path, bytes, WRITE_OPTIONS);
		return fingerprint;
	}

	static byte[] serializeLines(List<? extends CharSequence> lines) throws IOException {
		validateReadableContents(lines);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
			for (CharSequence line : lines) {
				writer.append(line);
				writer.newLine();
			}
		}
		return output.toByteArray();
	}

	private static boolean hasSameContents(Path path, byte[] bytes) {
		try (InputStream input = Files.newInputStream(path)) {
			return Arrays.equals(bytes, input.readNBytes(bytes.length + 1));
		} catch (IOException ignored) {
			// Comparing is optional: missing or unreadable files still get a normal save attempt.
			return false;
		}
	}

	public static void validateReadableContents(List<? extends CharSequence> lines) {
		if (lines.size() > ConfigFileReader.MAX_FILE_LINES) {
			throw excessiveLineCount();
		}
		int lineSeparatorBytes = System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
		long totalLines = lines.size();
		long totalBytes = 0;
		for (CharSequence line : lines) {
			totalLines += countEmbeddedLineSeparators(line);
			if (totalLines > ConfigFileReader.MAX_FILE_LINES) {
				throw excessiveLineCount();
			}
			totalBytes += line.toString().getBytes(StandardCharsets.UTF_8).length + lineSeparatorBytes;
			if (totalBytes > ConfigFileReader.MAX_FILE_BYTES) {
				throw new IllegalArgumentException(
					"Serialized config file exceeds the maximum supported size of " + ConfigFileReader.MAX_FILE_BYTES + " bytes."
				);
			}
		}
	}

	private static long countEmbeddedLineSeparators(CharSequence line) {
		long count = 0;
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (character == '\r') {
				count++;
				if (index + 1 < line.length() && line.charAt(index + 1) == '\n') {
					index++;
				}
			} else if (character == '\n') {
				count++;
			}
		}
		return count;
	}

	private static IllegalArgumentException excessiveLineCount() {
		return new IllegalArgumentException(
			"Serialized config file exceeds the maximum supported line count of " + ConfigFileReader.MAX_FILE_LINES + "."
		);
	}

	/**
	 * Replaces the target atomically when supported by this move's filesystem/provider.
	 * Falls back to a non-atomic replacement only when atomic moving or replacing is unsupported.
	 * Windows sharing/access failures use a limited number of retries with increasing delays.
	 * They must not trigger the non-atomic fallback, which can delete the target before the rename.
	 * Atomic visibility does not guarantee that the directory entry survives power loss.
	 */
	public static void moveAtomicReplace(Path source, Path target) throws IOException {
		AtomicFiles.moveReplace(source, target, WRITE_OPTIONS.atomicMovePolicy(), WRITE_OPTIONS.retryTimeout());
	}

	public static Path backUpFile(Path path, int maxBackups) throws IOException {
		if (maxBackups < 1) {
			throw new IllegalArgumentException("maxBackups must be positive.");
		}
		Path newestBackup = getBackupPath(path, 1);
		if (Files.exists(newestBackup) && AtomicFiles.mismatch(path, newestBackup) == -1) {
			return newestBackup;
		}
		try (var stagedBackup = AtomicFiles.stageCopy(path, newestBackup, WRITE_OPTIONS, StandardCopyOption.COPY_ATTRIBUTES)) {
			for (int index = maxBackups; index > 1; index--) {
				Path previous = getBackupPath(path, index - 1);
				if (Files.exists(previous)) {
					moveAtomicReplace(previous, getBackupPath(path, index));
				}
			}
			stagedBackup.commit();
			return newestBackup;
		}
	}

	public static Path backUpFile(Path path) throws IOException {
		return backUpFile(path, MAX_BACKUPS);
	}

	public static Path getBackupPath(Path path, int index) {
		return path.resolveSibling(path.getFileName() + ".bak." + index);
	}
}
