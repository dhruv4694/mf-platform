package com.mfplatform.mfplatform.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenApiConfig customises the auto-generated Swagger UI.
 *
 * Springdoc already generates documentation from @RestController classes
 * automatically — this config adds:
 *   1. API title, description, version metadata
 *   2. JWT "Authorize" button — paste your token once, all requests include it
 *
 * WITHOUT the security scheme:
 *   Every "Try it out" request gets 401 because no Authorization header is sent.
 *
 * WITH the security scheme:
 *   Click "Authorize" → paste JWT access token → all requests auto-include
 *   "Authorization: Bearer <token>" header.
 *
 * HOW TO USE:
 *   1. Open http://localhost:8080/swagger-ui.html
 *   2. Call POST /api/v1/auth/login via "Try it out" — copy the accessToken
 *   3. Click "Authorize" (top right, padlock icon)
 *   4. Paste the token → Authorize → Close
 *   5. All subsequent "Try it out" requests are authenticated
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mfPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MF Platform API")
                        .description("""
                                Mutual Fund Management System — REST API documentation.
                                                                
                                **Roles:**
                                - `INVESTOR` — self-service: purchase, redeem, SIP, portfolio
                                - `DISTRIBUTOR` — manage client book: add investors, place transactions
                                - `ADMIN` — manage schemes, NAV import, user management
                                                                
                                **Authentication:**
                                Call `POST /api/v1/auth/login` to get a JWT access token,
                                then click **Authorize** (padlock icon) and paste the token.
                                All authenticated endpoints will include the Bearer header automatically.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("MF Platform")
                                .email("admin@mfplatform.com")))

                // Tells Swagger UI to include "Authorization: Bearer <token>"
                // on every request once the user clicks Authorize and pastes a token
                .addSecurityItem(new SecurityRequirement().addList("Bearer Auth"))

                .components(new Components()
                        .addSecuritySchemes("Bearer Auth", new SecurityScheme()
                                .name("Bearer Auth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Get a token from POST /api/v1/auth/login, then paste it here.
                                        Default admin credentials: username=admin, password=ChangeMe123!
                                        """)));
    }
}
