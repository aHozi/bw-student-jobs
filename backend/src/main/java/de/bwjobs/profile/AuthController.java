package de.bwjobs.profile;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;

    public AuthController(UserRepository users, ProfileRepository profiles, PasswordEncoder encoder,
                          JwtEncoder jwtEncoder, AppProperties props) {
        this.users = users;
        this.profiles = profiles;
        this.encoder = encoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
    }

    public record RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 200) String password,
                                  @Size(max = 200) String displayName) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record UserView(long id, String email, String displayName, String role) {
        static UserView of(AppUser u) {
            return new UserView(u.id(), u.email(), u.displayName(), u.role());
        }
    }

    public record AuthResponse(String token, UserView user) {}

    @PostMapping("/auth/register")
    @Transactional
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        if (users.findByEmail(req.email()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        boolean admin = users.countAdmins() == 0
                || (props.adminEmails() != null && props.adminEmails().stream().anyMatch(e -> e.equalsIgnoreCase(req.email().trim())));
        AppUser u = users.insert(req.email(), encoder.encode(req.password()), req.displayName(), admin ? "ADMIN" : "USER");
        profiles.createEmpty(u.id(), u.email(), req.displayName());
        return new AuthResponse(token(u), UserView.of(u));
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        AppUser u = users.findByEmail(req.email())
                .filter(x -> encoder.matches(req.password(), x.passwordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        return new AuthResponse(token(u), UserView.of(u));
    }

    @GetMapping("/me")
    public UserView me() {
        return users.findById(CurrentUser.id()).map(UserView::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    private String token(AppUser u) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(u.id()))
                .issuedAt(now)
                .expiresAt(now.plus(props.jwtTtlHours(), ChronoUnit.HOURS))
                .claim("role", u.role())
                .claim("email", u.email())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
