package com.bcp.workshop.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class AuthService {
    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    TokenService tokenService;

    public Optional<LoginResult> authenticate(String username, String password) {
        return userRepository.findByUsername(username)
                .filter(user -> passwordHasher.matches(password, user.passwordHash()))
                .map(user -> new LoginResult(user, tokenService.create(user.username(), user.role())));
    }
}
