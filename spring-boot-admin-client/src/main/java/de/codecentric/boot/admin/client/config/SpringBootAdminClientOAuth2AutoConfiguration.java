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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

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
 * This configuration runs <em>before</em> {@link SpringBootAdminClientAutoConfiguration}
 * so that the {@link RegistrationClient} it creates (with the OAuth2 interceptor)
 * satisfies the {@code @ConditionalOnMissingBean(RegistrationClient.class)} condition in
 * {@link SpringBootAdminClientAutoConfiguration.RestClientRegistrationClientConfig},
 * preventing a duplicate bean without the interceptor.
 * <p>
 * All {@link RestClient.Builder} setup applied by
 * {@link SpringBootAdminClientAutoConfiguration.RestClientRegistrationClientConfig} —
 * connect/read timeouts, Jackson message-converter customization — is replicated here.
 * Basic Auth is intentionally omitted because OAuth2 takes precedence.
 * <p>
 * Registration ID resolution order (highest priority first):
 * <ol>
 * <li>Instance metadata key {@code "oauth2.registration-id"} (also accepted:
 * {@code "oauth2-registration-id"}) — per-instance override, same key the server reads
 * when polling actuator endpoints</li>
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
@AutoConfigureBefore(SpringBootAdminClientAutoConfiguration.class)
public class SpringBootAdminClientOAuth2AutoConfiguration {

	private static final String[] REGISTRATION_ID_KEYS = { "oauth2.registration-id", "oauth2-registration-id" };

	@Bean
	@ConditionalOnMissingBean(RegistrationClient.class)
	public RegistrationClient oauth2RegistrationClient(ClientProperties client, InstanceProperties instance,
			RestClient.Builder restClientBuilder, OAuth2AuthorizedClientManager authorizedClientManager,
			ObjectProvider<JsonMapper> objectMapper) {
		var factorySettings = HttpClientSettings.defaults()
			.withConnectTimeout(client.getConnectTimeout())
			.withReadTimeout(client.getReadTimeout());
		restClientBuilder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(factorySettings));

		objectMapper.ifAvailable((mapper) -> restClientBuilder.messageConverters((converters) -> {
			converters.removeIf(JacksonJsonHttpMessageConverter.class::isInstance);
			converters.add(new JacksonJsonHttpMessageConverter(mapper));
		}));

		var interceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
		interceptor.setClientRegistrationIdResolver((request) -> resolveRegistrationId(client, instance));
		restClientBuilder.requestInterceptor(interceptor);

		return new RestClientRegistrationClient(restClientBuilder.build());
	}

	static String resolveRegistrationId(ClientProperties client, InstanceProperties instance) {
		for (String key : REGISTRATION_ID_KEYS) {
			String value = instance.getMetadata().get(key);
			if (value != null) {
				return value;
			}
		}
		return client.getOauth2RegistrationId();
	}

}
