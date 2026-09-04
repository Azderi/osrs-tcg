package com.osrstcg.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
/** Temp-file write then atomic replace. */
public final class AtomicFiles
{
/** No instances. */
	private AtomicFiles()
	{
	}
/**
	 * Writes {@code bytes} to a sibling {@code .tmp} file, then atomically moves it onto {@code target}.
	 * Cleans up the temp file on failure. Creates parent directories as needed.
	 */
	public static void writeBytes(Path target, byte[] bytes) throws IOException
	{
		Path dir = target.getParent();
		if (dir == null)
		{
			throw new IOException("No parent directory for " + target);
		}
		Files.createDirectories(dir);
		Path tmp = dir.resolve(target.getFileName().toString() + ".tmp");
		try
		{
			Files.write(tmp, bytes);
			moveReplace(tmp, target);
		}
		catch (IOException ex)
		{
			try
			{
				Files.deleteIfExists(tmp);
			}
			catch (IOException ignored)
			{
				// ignore
			}
			throw ex;
		}
	}
/** Encodes {@code content} with {@code charset} and writes it via {@link #writeBytes}. */
	public static void writeString(Path target, String content, Charset charset) throws IOException
	{
		writeBytes(target, content.getBytes(charset));
	}
/** Moves {@code source} onto {@code target}, replacing any existing file, falling back to a non-atomic move if the filesystem doesn't support atomic rename. */
	public static void moveReplace(Path source, Path target) throws IOException
	{
		try
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
