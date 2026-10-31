package de.bwjobs.profile;

import java.time.Instant;

public record AppUser(long id, String email, String passwordHash, String displayName, String role, Instant createdAt) {}
