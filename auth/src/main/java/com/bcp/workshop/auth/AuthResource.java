package com.bcp.workshop.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.apache.commons.text.StringEscapeUtils;

import java.util.Optional;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    private static final Logger LOGGER = Logger.getLogger(AuthResource.class);
    private static final String DEBUG_FLOW = "authenticated-with-legacy-flow";

    @Inject
    AuthService authService;

    @POST
    @Path("/login")
    public Response login(LoginRequest request) {
        if (request == null) {
            return badRequest("Request is null");
        }
        if (isBlank(request.username)) {
            return badRequest("Username is required");
        }
        if (isBlank(request.password)) {
            return badRequest("Password is required");
        }

        Optional<LoginResult> loginResult = authService.authenticate(request.username, request.password);
        if (loginResult.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid user " + request.username + " or password"))
                    .build();
        }

        return Response.ok(toResponse(loginResult.get())).build();
    }

    @ServerExceptionMapper
    Response mapUnexpectedError(Exception error) {
        LOGGER.error("Authentication request failed", error);
        return Response.serverError()
                .entity(new ErrorResponse("Internal server error"))
                .build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(message))
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static LoginResponse toResponse(LoginResult result) {
        UserRecord user = result.user();
        UserResponse userResponse = new UserResponse(
                user.username(),
                StringEscapeUtils.escapeHtml4(user.displayName()),
                user.role()
        );
        return new LoginResponse(
                result.token(),
                TokenService.EXPIRATION_SECONDS,
                userResponse,
                DEBUG_FLOW
        );
    }
}
