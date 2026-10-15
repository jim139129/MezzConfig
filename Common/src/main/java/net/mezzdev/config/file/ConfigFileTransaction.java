package net.mezzdev.config.file;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.StagedFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigFileTransaction {
	private ConfigFileTransaction() {}

	public static void write(
		Map<Path, ? extends List<? extends CharSequence>> outputs,
		Set<Path> requiredAbsentTargets
	) throws IOException {
		Deque<PreparedFile> stagedFiles = new ArrayDeque<>();
		List<PreparedFile> committedFiles = new ArrayList<>();
		Set<Path> normalizedTargets = new HashSet<>();
		Set<Path> normalizedRequiredAbsentTargets = new HashSet<>();
		for (Path path : requiredAbsentTargets) {
			normalizedRequiredAbsentTargets.add(normalize(path));
		}

		try {
			for (Map.Entry<Path, ? extends List<? extends CharSequence>> entry : outputs.entrySet()) {
				Path target = normalize(entry.getKey());
				if (!normalizedTargets.add(target)) {
					throw new IllegalArgumentException("A file transaction cannot write the same target more than once: " + target);
				}
				boolean requiredAbsent = normalizedRequiredAbsentTargets.contains(target);
				PreparedFile stagedFile = stage(target, entry.getValue(), requiredAbsent);
				if (stagedFile != null) {
					stagedFiles.add(stagedFile);
				}
			}

			while (!stagedFiles.isEmpty()) {
				PreparedFile stagedFile = stagedFiles.getFirst();
				stagedFile.commit();
				committedFiles.add(stagedFile);
				stagedFiles.removeFirst();
			}
		} catch (IOException | RuntimeException | Error failure) {
			rollback(committedFiles, failure);
			throw failure;
		} finally {
			for (PreparedFile stagedFile : stagedFiles) {
				stagedFile.cleanup();
			}
		}
		for (PreparedFile committedFile : committedFiles) {
			committedFile.cleanup();
		}
	}

	private static @Nullable PreparedFile stage(
		Path target,
		List<? extends CharSequence> contents,
		boolean requiredAbsent
	) throws IOException {
		byte[] bytes = ConfigFileUtil.serializeLines(contents);
		if (requiredAbsent && Files.exists(target)) {
			throw new FileAlreadyExistsException(target.toString());
		}
		StagedFile stagedOutput = AtomicFiles.stage(target, bytes, ConfigFileUtil.WRITE_OPTIONS);
		StagedFile rollbackFile = null;
		try {
			if (!requiredAbsent && stagedOutput.isUnchanged()) {
				stagedOutput.close();
				return null;
			}
			if (Files.exists(target)) {
				rollbackFile = AtomicFiles.snapshot(target, ConfigFileUtil.WRITE_OPTIONS);
			}
			return new PreparedFile(target, stagedOutput, rollbackFile, requiredAbsent);
		} catch (IOException | RuntimeException | Error failure) {
			try {
				stagedOutput.close();
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			if (rollbackFile != null) {
				try {
					rollbackFile.close();
				} catch (IOException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}

	private static void rollback(List<PreparedFile> committedFiles, Throwable failure) {
		for (int index = committedFiles.size() - 1; index >= 0; index--) {
			try {
				committedFiles.get(index).rollback();
			} catch (IOException | RuntimeException | Error rollbackFailure) {
				// Leave a failed rollback's backup available for recovery.
				failure.addSuppressed(rollbackFailure);
			}
		}
	}

	private static void closeQuietly(@Nullable StagedFile stagedFile) {
		if (stagedFile == null) {
			return;
		}
		try {
			stagedFile.close();
		} catch (IOException ignored) {}
	}

	private record PreparedFile(Path target, StagedFile stagedOutput, @Nullable StagedFile rollbackFile, boolean requiredAbsent) {
		private void commit() throws IOException {
			if (requiredAbsent && Files.exists(target)) {
				throw new FileAlreadyExistsException(target.toString());
			}
			stagedOutput.commit();
		}

		private void rollback() throws IOException {
			if (rollbackFile != null) {
				rollbackFile.commit();
			} else {
				AtomicFiles.deleteIfExists(target);
			}
		}

		private void cleanup() {
			closeQuietly(stagedOutput);
			closeQuietly(rollbackFile);
		}
	}
}
