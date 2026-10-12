package com.sender.backend.auth;

import com.sender.backend.common.security.JwtService;
import com.sender.backend.user.User;
import com.sender.backend.user.UserRepository;
import com.sender.backend.auth.AuthDtos.LoginRequest;
import com.sender.backend.auth.AuthDtos.PublicUser;
import com.sender.backend.auth.AuthDtos.RegisterRequest;
import com.sender.backend.auth.AuthDtos.ChangePasswordRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;

@Service
public class AuthService {
	private static final String INVALID_CREDENTIALS = "Invalid email or password";
	private final UserRepository users;
	private final AuthSessionRepository sessions;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final long refreshTokenDays;
	private final SecureRandom secureRandom = new SecureRandom();
	private final TransactionTemplate tx;

	public AuthService(UserRepository users, AuthSessionRepository sessions, PasswordEncoder passwordEncoder,
			JwtService jwtService, PlatformTransactionManager transactionManager,
			@Value("${app.auth.refresh-token-days}") long refreshTokenDays) {
		this.users = users;
		this.sessions = sessions;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.refreshTokenDays = refreshTokenDays;
		this.tx = new TransactionTemplate(transactionManager);
	}

	public SessionResult register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		String username = request.username().toLowerCase();
		if (users.existsByEmail(email) || users.existsByUsername(username)) {
			throw new BadCredentialsException(INVALID_CREDENTIALS);
		}
		// Encode before opening a transaction so the pool is not held during BCrypt.
		String passwordHash = passwordEncoder.encode(request.password());
		try {
			return Objects.requireNonNull(tx.execute(status -> {
				if (users.existsByEmail(email) || users.existsByUsername(username)) {
					throw new BadCredentialsException(INVALID_CREDENTIALS);
				}
				User user = users.save(new User(username, email, passwordHash));
				return createSession(user);
			}));
		} catch (DataIntegrityViolationException exception) {
			throw new BadCredentialsException(INVALID_CREDENTIALS);
		}
	}

	public SessionResult login(LoginRequest request) {
		User user = users.findByEmail(normalizeEmail(request.email()))
				.orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
		Integer userId = user.getId();
		String passwordHash = user.getPasswordHash();
		// BCrypt outside a DB transaction so Hikari connections are not held during hashing.
		if (!passwordEncoder.matches(request.password(), passwordHash)) {
			throw new BadCredentialsException(INVALID_CREDENTIALS);
		}
		return Objects.requireNonNull(tx.execute(status -> {
			User managed = users.findById(userId)
					.orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
			return createSession(managed);
		}));
	}

	@Transactional
	public SessionResult refresh(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BadCredentialsException("Invalid refresh token");
		}
		AuthSession session = sessions.findByTokenHash(hash(refreshToken))
				.filter(value -> value.isActive(Instant.now()))
				.orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
		session.revoke();
		return createSession(session.getUser());
	}

	@Transactional
	public void logout(String refreshToken, boolean allDevices) {
		if (refreshToken == null || refreshToken.isBlank()) return;
		AuthSession current = sessions.findByTokenHash(hash(refreshToken)).orElse(null);
		if (current == null) return;
		if (allDevices) {
			sessions.findAllByUserId(current.getUser().getId()).forEach(AuthSession::revoke);
		} else {
			current.revoke();
		}
	}

	public void changePassword(Integer userId, ChangePasswordRequest request) {
		User user = users.findById(userId)
				.orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
		if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
			throw new BadCredentialsException(INVALID_CREDENTIALS);
		}
		String passwordHash = passwordEncoder.encode(request.newPassword());
		tx.executeWithoutResult(status -> {
			User managed = users.findById(userId)
					.orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
			managed.setPasswordHash(passwordHash);
			sessions.findAllByUserId(userId).forEach(AuthSession::revoke);
		});
	}

	private SessionResult createSession(User user) {
		String rawRefreshToken = randomToken();
		Instant refreshExpiry = Instant.now().plus(refreshTokenDays, ChronoUnit.DAYS);
		sessions.save(new AuthSession(user, hash(rawRefreshToken), refreshExpiry));
		JwtService.Token access = jwtService.issue(user);
		return new SessionResult(PublicUser.from(user), access, rawRefreshToken);
	}

	private String randomToken() {
		byte[] bytes = new byte[48];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String hash(String value) {
		try {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to hash refresh token", exception);
		}
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase();
	}

	public record SessionResult(PublicUser user, JwtService.Token accessToken, String refreshToken) {}
}
