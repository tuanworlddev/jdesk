package dev.jdesk.updater;

import java.nio.file.Path;

public record VerifiedUpdate(Path packagePath, long size, String sha256) { }
