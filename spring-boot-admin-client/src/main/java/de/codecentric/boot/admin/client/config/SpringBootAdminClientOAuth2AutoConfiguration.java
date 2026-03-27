/*
 * Copyright 2014-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.codecentric.boot.admin.client.config;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.client.registration.RestClientRegistrationClient;

/**
 * Auto-configuration that adds an OAuth2 Client Credentials interceptor to the
 * registration {@link RestClient} when:
 * <ul>
 * <li>{@code spring-security-oauth2-client} is on the classpath</li>
 * <li>an {@link OAuth2AuthorizedClientManager} bean is present in the context</li>
 * </ul>
 * <p>
 * Registration ID resolution order (highest priority first):
 * <ol>
 * <li>Instance metadata key {@code "oauth2.registration-id"} (also accepted:
 * {@code "oauth2-registration-id"}) — per-instance override</li>
 * <li>{@code spring.boot.admin.client.oauth2-registration-id} — default for all
 * registrations; allows zero-metadata configuration</li>
 * </ol>
 * <p>
 * Minimal zero-metadata setup:
 *
 * <pre>
 * spring:
 *   boot:
 *     admin:
 *       client:
 *         oauth2-registration-id: my-client
 * </pre>
 *
 * Per-instance override via metadata:
 *
 * <pre>
 * spring:
 *   boot:
 *     admin:
 *       client:
 *         instance:
 *           metadata:
 *             oauth2.registration-id: my-other-client
 * </pre>
 *
 * If neither is configured, no token is injected and registration proceeds
 * unauthenticated.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2AuthorizedClientManager.class)
@ConditionalOnBean(OAuth2AuthorizedClientManager.class)
@AutoConfigureAfter(SpringBootAdminClientAutoConfiguration.class)
public class SpringBootAdminClientOAuth2AutoConfiguration {

	private static final String[] REGISTRATION_ID_KEYS = { "oauth2.registration-id", "oauth2-registration-id" };

	@Bean
	@ConditionalOnMissingBean(RegistrationClient.class)
	public RegistrationClient oauth2RegistrationClient(ClientProperties client, InstanceProperties instance,
			RestClient.Builder restClientBuilder, OAuth2AuthorizedClientManager authorizedClientManager) {
		var interceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
		interceptor.setClientRegistrationIdResolver((request) -> resolveRegistrationId(client, instance));
		restClientBuilder.requestInterceptor(interceptor);
		return new RestClientRegistrationClient(restClientBuilder.build());
	}

	private static String resolveRegistrationId(ClientProperties client, InstanceProperties instance) {
		for (String key : REGISTRATION_ID_KEYS) {
			String value = instance.getMetadata().get(key);
			if (value != null) {
				return value;
			}
		}
		return client.getOauth2RegistrationId();
	}

}
