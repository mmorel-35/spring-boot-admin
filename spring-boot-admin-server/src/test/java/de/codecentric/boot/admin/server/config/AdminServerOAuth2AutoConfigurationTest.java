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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;

import de.codecentric.boot.admin.server.web.client.OAuth2ReactiveHttpHeadersProvider;

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
	void withReactiveOAuth2AuthorizedClientManager_createsProvider() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.run((context) -> assertThat(context).hasSingleBean(OAuth2ReactiveHttpHeadersProvider.class));
	}

	@Test
	void withReactiveOAuth2AuthorizedClientManager_registersReactiveHeadersExchangeFilter() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.run((context) -> assertThat(context).hasBean("addReactiveHeadersInstanceExchangeFilter"));
	}

	@Test
	void withInstanceAuthDisabled_createsProviderWithoutServerSideConfig() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withPropertyValues("spring.boot.admin.instance-auth.enabled=false",
					"spring.boot.admin.instance-auth.oauth2.default-registration-id=my-client")
			.run((context) -> {
				assertThat(context).hasSingleBean(OAuth2ReactiveHttpHeadersProvider.class);
				OAuth2ReactiveHttpHeadersProvider provider = context.getBean(OAuth2ReactiveHttpHeadersProvider.class);
				assertThat(provider).extracting("defaultRegistrationId").isNull();
			});
	}

	@Test
	void withInstanceAuthEnabled_createsProviderWithServerSideConfig() {
		this.contextRunner
			.withBean(ReactiveOAuth2AuthorizedClientManager.class,
					() -> mock(ReactiveOAuth2AuthorizedClientManager.class))
			.withPropertyValues("spring.boot.admin.instance-auth.enabled=true",
					"spring.boot.admin.instance-auth.oauth2.default-registration-id=my-client",
					"spring.boot.admin.instance-auth.oauth2.service-map.payment-service=payment-client")
			.run((context) -> {
				assertThat(context).hasSingleBean(OAuth2ReactiveHttpHeadersProvider.class);
				OAuth2ReactiveHttpHeadersProvider provider = context.getBean(OAuth2ReactiveHttpHeadersProvider.class);
				assertThat(provider).extracting("defaultRegistrationId").isEqualTo("my-client");
				assertThat(provider).extracting("serviceRegistrationMap")
					.isEqualTo(Map.of("payment-service", "payment-client"));
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

}
