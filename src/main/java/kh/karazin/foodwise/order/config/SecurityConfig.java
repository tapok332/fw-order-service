package kh.karazin.foodwise.order.config;

import kh.karazin.foodwise.common.security.InternalAuthFilter;
import kh.karazin.foodwise.common.security.XUserHeadersAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public XUserHeadersAuthFilter xUserHeadersAuthFilter() {
        return new XUserHeadersAuthFilter();
    }

    @Bean
    public InternalAuthFilter internalAuthFilter(@Value("${internal.service.secret}") String internalSecret) {
        return new InternalAuthFilter(internalSecret);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           InternalAuthFilter internalAuthFilter,
                                           XUserHeadersAuthFilter xUserHeadersAuthFilter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(xUserHeadersAuthFilter, InternalAuthFilter.class)
                .build();
    }
}
