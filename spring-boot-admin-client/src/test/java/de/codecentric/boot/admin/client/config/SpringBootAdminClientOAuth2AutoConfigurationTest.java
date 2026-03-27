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

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLoggingListener;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.client.registration.RestClientRegistrationClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringBootAdminClientOAuth2AutoConfigurationTest {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class,
				DispatcherServletAutoConfiguration.class, SpringBootAdminClientAutoConfiguration.class,
				SpringBootAdminClientOAuth2AutoConfiguration.class))
		.withBean(RestClient.Builder.class, RestClient::builder)
		.withPropertyValues("spring.boot.admin.client.url:http://localhost:8081")
		.withInitializer(new ConditionEvaluationReportLoggingListener());

	@Test
	void withoutOAuth2AuthorizedClientManager_usesDefaultRegistrationClient() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(RegistrationClient.class);
			assertThat(context.getBean(RegistrationClient.class)).isInstanceOf(RestClientRegistrationClient.class);
		});
	}

	@Test
	void withOAuth2AuthorizedClientManager_usesOAuth2RegistrationClient() {
		this.contextRunner
			.withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
			.run((context) -> {
				assertThat(context).hasSingleBean(RegistrationClient.class);
				assertThat(context.getBean(RegistrationClient.class)).isInstanceOf(RestClientRegistrationClient.class);
			});
	}

	@Test
	void withDefaultOAuth2RegistrationId_autoConfiguresWithoutMetadata() {
		this.contextRunner.withPropertyValues("spring.boot.admin.client.oauth2-registration-id:default-client")
			.withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
			.run((context) -> {
				assertThat(context).hasSingleBean(RegistrationClient.class);
				assertThat(context.getBean(RegistrationClient.class)).isInstanceOf(RestClientRegistrationClient.class);
			});
	}

	@Test
	void metadataRegistrationIdOverridesDefaultProperty() {
		this.contextRunner
			.withPropertyValues("spring.boot.admin.client.oauth2-registration-id:default-client",
					"spring.boot.admin.client.instance.metadata.oauth2.registration-id:override-client")
			.withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
			.run((context) -> {
				assertThat(context).hasSingleBean(RegistrationClient.class);
				assertThat(context.getBean(RegistrationClient.class)).isInstanceOf(RestClientRegistrationClient.class);
			});
	}

	@Test
	void withCustomRegistrationClientBean_conditionalOnMissingBeanRespected() {
		RegistrationClient customClient = mock(RegistrationClient.class);
		this.contextRunner
			.withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
			.withBean(RegistrationClient.class, () -> customClient)
			.run((context) -> {
				assertThat(context).hasSingleBean(RegistrationClient.class);
				assertThat(context.getBean(RegistrationClient.class)).isSameAs(customClient);
			});
	}

}
