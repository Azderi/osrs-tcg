package com.osrstcg.debug.catalogedit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.inject.Singleton;

/**
 * Resolves the workspace {@code src/main/resources/Card.json} for dev-time edits.
 * Production JAR classpath resources cannot be written at runtime.
 */
@Singleton
final class DebugCardJsonPaths
{
	private static final String CARD_JSON_RELATIVE = "src/main/resources/Card.json";
	private static final String OVERRIDE_PROPERTY = "osrstcg.cardJsonPath";

	volatile Path cachedWorkspaceCardJson;

	Optional<Path> resolveWorkspaceCardJson()
	{
		Path cached = cachedWorkspaceCardJson;
		if (cached != null)
		{
			if (Files.isRegularFile(cached))
			{
				return Optional.of(cached);
			}
			cachedWorkspaceCardJson = null;
		}

		String override = System.getProperty(OVERRIDE_PROPERTY);
		if (override != null && !override.trim().isEmpty())
		{
			Path p = Paths.get(override.trim());
			if (Files.isRegularFile(p))
			{
				cachedWorkspaceCardJson = p.toAbsolutePath().normalize();
				return Optional.of(cachedWorkspaceCardJson);
			}
			return Optional.empty();
		}

		Path start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		for (Path dir = start; dir != null; dir = dir.getParent())
		{
			Path candidate = dir.resolve(CARD_JSON_RELATIVE);
			if (Files.isRegularFile(candidate))
			{
				cachedWorkspaceCardJson = candidate.normalize();
				return Optional.of(cachedWorkspaceCardJson);
			}
			if (Files.isRegularFile(dir.resolve("build.gradle"))
				&& Files.isRegularFile(dir.resolve("settings.gradle")))
			{
				Path fromRoot = dir.resolve(CARD_JSON_RELATIVE);
				if (Files.isRegularFile(fromRoot))
				{
					cachedWorkspaceCardJson = fromRoot.normalize();
					return Optional.of(cachedWorkspaceCardJson);
				}
				break;
			}
		}
		return Optional.empty();
	}
}
