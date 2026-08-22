package ai.devpath.sandbox.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecretKey jwtSecretKey(
      @Value("${devpath.auth.jwt-secret:test-secret-please-change-min-32-bytes-long-0123456789}") String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("JWT_SECRET must be >= 32 bytes (HS256), got " + bytes.length);
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  public JwtDecoder jwtDecoder(SecretKey key) {
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  public InternalApiAuthenticationFilter internalApiAuthenticationFilter(
      @Value("${devpath.auth.internal-token:}") String internalToken) {
    return new InternalApiAuthenticationFilter(internalToken);
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      InternalApiAuthenticationFilter internalApiAuthenticationFilter) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers("/internal/**").hasRole("INTERNAL")
            .anyRequest().authenticated())
        .addFilterBefore(internalApiAuthenticationFilter, BearerTokenAuthenticationFilter.class)
        .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
