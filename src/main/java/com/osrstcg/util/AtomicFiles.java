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
	private AtomicFiles()
	{
	}

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

	public static void writeString(Path target, String content, Charset charset) throws IOException
	{
		writeBytes(target, content.getBytes(charset));
	}

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
