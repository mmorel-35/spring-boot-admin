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

import java.time.Instant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLoggingListener;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import de.codecentric.boot.admin.client.registration.Application;
import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.client.registration.RestClientRegistrationClient;

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringBootAdminClientOAuth2AutoConfigurationTest {

	@Nested
	class AutoConfigurationConditionsTest {

		private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(EndpointAutoConfiguration.class,
					WebEndpointAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
					SpringBootAdminClientAutoConfiguration.class, SpringBootAdminClientOAuth2AutoConfiguration.class))
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
		void withOAuth2AuthorizedClientManager_butNoRegistrationId_usesUnauthenticatedRegistrationClient() {
			this.contextRunner
				.withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
				.run((context) -> {
					assertThat(context).hasSingleBean(RegistrationClient.class);
					assertThat(context.getBean(RegistrationClient.class))
						.isInstanceOf(RestClientRegistrationClient.class);
				});
		}

		@Test
		void withDefaultOAuth2RegistrationId_autoConfiguresWithoutMetadata() {
			this.contextRunner.withPropertyValues("spring.boot.admin.client.oauth2-registration-id:default-client")
				.withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
				.run((context) -> {
					assertThat(context).hasSingleBean(RegistrationClient.class);
					assertThat(context.getBean(RegistrationClient.class))
						.isInstanceOf(RestClientRegistrationClient.class);
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

	@Nested
	class RegistrationIdResolutionTest {

		@Test
		void defaultPropertyIsUsed_whenNoMetadata() {
			ClientProperties client = new ClientProperties();
			client.setOauth2RegistrationId("default-client");
			InstanceProperties instance = new InstanceProperties();

			assertThat(SpringBootAdminClientOAuth2AutoConfiguration.resolveRegistrationId(client, instance))
				.isEqualTo("default-client");
		}

		@Test
		void metadataDotKeyTakesPrecedenceOverDefaultProperty() {
			ClientProperties client = new ClientProperties();
			client.setOauth2RegistrationId("default-client");
			InstanceProperties instance = new InstanceProperties();
			instance.getMetadata().put("oauth2.registration-id", "meta-client");

			assertThat(SpringBootAdminClientOAuth2AutoConfiguration.resolveRegistrationId(client, instance))
				.isEqualTo("meta-client");
		}

		@Test
		void metadataDashKeyTakesPrecedenceOverDefaultProperty() {
			ClientProperties client = new ClientProperties();
			client.setOauth2RegistrationId("default-client");
			InstanceProperties instance = new InstanceProperties();
			instance.getMetadata().put("oauth2-registration-id", "dash-client");

			assertThat(SpringBootAdminClientOAuth2AutoConfiguration.resolveRegistrationId(client, instance))
				.isEqualTo("dash-client");
		}

		@Test
		void nullReturnedWhenNeitherMetadataNorDefaultSet() {
			assertThat(SpringBootAdminClientOAuth2AutoConfiguration.resolveRegistrationId(new ClientProperties(),
					new InstanceProperties()))
				.isNull();
		}

		@Test
		void nullReturnedWhenMetadataValueIsBlank() {
			ClientProperties client = new ClientProperties();
			InstanceProperties instance = new InstanceProperties();
			instance.getMetadata().put("oauth2.registration-id", "  ");

			assertThat(SpringBootAdminClientOAuth2AutoConfiguration.resolveRegistrationId(client, instance)).isNull();
		}

		@Test
		void nullReturnedWhenPropertyValueIsBlank() {
			ClientProperties client = new ClientProperties();
			client.setOauth2RegistrationId("  ");
			InstanceProperties instance = new InstanceProperties();

			assertThat(SpringBootAdminClientOAuth2AutoConfiguration.resolveRegistrationId(client, instance)).isNull();
		}

	}

	@Nested
	class BearerTokenSentTest {

		private final WireMockServer wireMock = new WireMockServer(
				options().dynamicPort().notifier(new ConsoleNotifier(false)));

		@BeforeEach
		void startWireMock() {
			this.wireMock.start();
		}

		@AfterEach
		void stopWireMock() {
			this.wireMock.stop();
		}

		@Test
		void registrationRequestIncludesBearerToken() {
			this.wireMock.stubFor(
					post(urlEqualTo("/instances")).willReturn(created().withHeader("Content-Type", "application/json")
						.withHeader("Location", this.wireMock.url("/instances/abc"))
						.withBody("{ \"id\": \"abc\" }")));

			OAuth2AuthorizedClientManager manager = buildMockManager("test-client", "my-token");

			Application application = Application.create("test-app")
				.managementUrl("http://localhost:8080/mgmt")
				.healthUrl("http://localhost:8080/health")
				.serviceUrl("http://localhost:8080")
				.build();

			new WebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class,
						EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, SpringBootAdminClientAutoConfiguration.class,
						SpringBootAdminClientOAuth2AutoConfiguration.class))
				.withPropertyValues("spring.boot.admin.client.url=" + this.wireMock.url("/"),
						"spring.boot.admin.client.oauth2-registration-id=test-client")
				.withBean(OAuth2AuthorizedClientManager.class, () -> manager)
				.run((context) -> {
					assertThat(context).hasSingleBean(RegistrationClient.class);
					RegistrationClient registrationClient = context.getBean(RegistrationClient.class);
					registrationClient.register(this.wireMock.url("/instances"), application);
				});

			this.wireMock.verify(
					postRequestedFor(urlEqualTo("/instances")).withHeader("Authorization", equalTo("Bearer my-token")));
		}

		@Test
		void withOAuth2Manager_andBasicAuthConfigured_butNoOAuth2RegistrationId_usesBasicAuth() {
			this.wireMock.stubFor(
					post(urlEqualTo("/instances")).willReturn(created().withHeader("Content-Type", "application/json")
						.withHeader("Location", this.wireMock.url("/instances/abc"))
						.withBody("{ \"id\": \"abc\" }")));

			// No oauth2RegistrationId set → production code falls back to Basic Auth
			ClientProperties client = new ClientProperties();
			client.setUsername("admin");
			client.setPassword("secret");
			InstanceProperties instance = new InstanceProperties();

			@SuppressWarnings("unchecked")
			ObjectProvider<JsonMapper> mapperProvider = mock(ObjectProvider.class);
			OAuth2AuthorizedClientManager oAuth2Manager = mock(OAuth2AuthorizedClientManager.class);
			RegistrationClient registrationClient = new SpringBootAdminClientOAuth2AutoConfiguration()
				.oauth2RegistrationClient(client, instance, RestClient.builder(), oAuth2Manager, mapperProvider);

			Application application = Application.create("test-app")
				.managementUrl("http://localhost:8080/mgmt")
				.healthUrl("http://localhost:8080/health")
				.serviceUrl("http://localhost:8080")
				.build();

			registrationClient.register(this.wireMock.url("/instances"), application);

			// Base64("admin:secret") = YWRtaW46c2VjcmV0
			this.wireMock.verify(postRequestedFor(urlEqualTo("/instances")).withHeader("Authorization",
					equalTo("Basic YWRtaW46c2VjcmV0")));
			// OAuth2 manager must not be invoked when falling back to Basic Auth
			verifyNoInteractions(oAuth2Manager);
		}

		private static OAuth2AuthorizedClientManager buildMockManager(String registrationId, String tokenValue) {
			ClientRegistration reg = ClientRegistration.withRegistrationId(registrationId)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.clientId("cid")
				.tokenUri("https://token-uri")
				.build();
			OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue,
					Instant.now(), Instant.now().plusSeconds(300));
			OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(reg, "sba-server", token);

			OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
			when(manager.authorize(any())).thenReturn(authorizedClient);
			return manager;
		}

	}

}
