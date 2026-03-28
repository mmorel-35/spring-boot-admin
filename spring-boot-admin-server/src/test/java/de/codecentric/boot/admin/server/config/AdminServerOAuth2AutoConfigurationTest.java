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

package de.codecentric.boot.admin.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.web.client.OAuth2ReactiveHttpHeadersProvider;
import de.codecentric.boot.admin.server.web.client.OAuth2RegistrationIdResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdminServerOAuth2AutoConfigurationTest {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(ReactiveHttpClientAutoConfiguration.class, WebClientAutoConfiguration.class,
						WebMvcAutoConfiguration.class, AdminServerOAuth2AutoConfiguration.class,
						AdminServerAutoConfiguration.class, AdminServerInstanceWebClientConfiguration.class))
		.withUserConfiguration(AdminServerMarkerConfiguration.class);

	@Test
	void withoutReactiveOAuth2AuthorizedClientManager_doesNotCreateProvider() {
		this.contextRunner
			.run((context) -> assertThat(context).doesNotHaveBean(OAuth2ReactiveHttpHeadersProvider.class));
	}

	@Test
	void withReactiveOAuth2AuthorizedClientManager_createsResolverAndProvider() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.run((context) -> {
				assertThat(context).hasSingleBean(OAuth2RegistrationIdResolver.class);
				assertThat(context).hasSingleBean(OAuth2ReactiveHttpHeadersProvider.class);
			});
	}

	@Test
	void withReactiveOAuth2AuthorizedClientManager_registersReactiveHeadersExchangeFilter() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.run((context) -> assertThat(context).hasBean("addReactiveHeadersInstanceExchangeFilter"));
	}

	@Test
	void withInstanceAuthDisabled_resolverIgnoresServiceMapAndDefault() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withPropertyValues("spring.boot.admin.instance-auth.enabled=false",
					"spring.boot.admin.instance-auth.oauth2.default-registration-id=my-client",
					"spring.boot.admin.instance-auth.oauth2.service-map.payment-service=payment-client")
			.run((context) -> {
				OAuth2RegistrationIdResolver resolver = context.getBean(OAuth2RegistrationIdResolver.class);
				// service-map entry must be ignored
				assertThat(resolver.resolve(buildInstance("payment-service"))).isNull();
				// default-registration-id must be ignored
				assertThat(resolver.resolve(buildInstance("unknown-service"))).isNull();
			});
	}

	@Test
	void withInstanceAuthEnabled_resolverAppliesServiceMapAndDefault() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withPropertyValues("spring.boot.admin.instance-auth.enabled=true",
					"spring.boot.admin.instance-auth.oauth2.default-registration-id=my-client",
					"spring.boot.admin.instance-auth.oauth2.service-map.payment-service=payment-client")
			.run((context) -> {
				OAuth2RegistrationIdResolver resolver = context.getBean(OAuth2RegistrationIdResolver.class);
				// service-map must take precedence over default
				assertThat(resolver.resolve(buildInstance("payment-service"))).isEqualTo("payment-client");
				// default must be used for other services
				assertThat(resolver.resolve(buildInstance("other-service"))).isEqualTo("my-client");
			});
	}

	@Test
	void metadataKeyTakesPrecedenceOverServiceMapAndDefault() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withPropertyValues("spring.boot.admin.instance-auth.enabled=true",
					"spring.boot.admin.instance-auth.oauth2.default-registration-id=default-client",
					"spring.boot.admin.instance-auth.oauth2.service-map.payment-service=service-client")
			.run((context) -> {
				OAuth2RegistrationIdResolver resolver = context.getBean(OAuth2RegistrationIdResolver.class);
				Instance instance = buildInstanceWithMetadata("payment-service", "oauth2.registration-id",
						"metadata-client");
				assertThat(resolver.resolve(instance)).isEqualTo("metadata-client");
			});
	}

	@Test
	void customResolverBeanSuppressesDefaultResolver() {
		OAuth2RegistrationIdResolver customResolver = (instance) -> "custom-id";
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withBean(OAuth2RegistrationIdResolver.class, () -> customResolver)
			.run((context) -> {
				assertThat(context).hasSingleBean(OAuth2RegistrationIdResolver.class);
				assertThat(context.getBean(OAuth2RegistrationIdResolver.class)).isSameAs(customResolver);
			});
	}

	@Test
	void withCustomProvider_doesNotCreateDefaultProvider() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withBean(OAuth2ReactiveHttpHeadersProvider.class,
					() -> new OAuth2ReactiveHttpHeadersProvider(mock(ReactiveOAuth2AuthorizedClientManager.class)))
			.run((context) -> assertThat(context).hasSingleBean(OAuth2ReactiveHttpHeadersProvider.class));
	}

	private static Instance buildInstance(String serviceName) {
		Registration registration = Registration.create(serviceName, "https://health").name(serviceName).build();
		return Instance.create(InstanceId.of("id")).register(registration);
	}

	private static Instance buildInstanceWithMetadata(String serviceName, String metadataKey, String metadataValue) {
		Registration registration = Registration.create(serviceName, "https://health")
			.name(serviceName)
			.metadata(metadataKey, metadataValue)
			.build();
		return Instance.create(InstanceId.of("id")).register(registration);
	}

}
