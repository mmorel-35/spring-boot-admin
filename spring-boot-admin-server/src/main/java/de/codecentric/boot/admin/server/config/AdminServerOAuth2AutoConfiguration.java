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

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.util.StringUtils;

import de.codecentric.boot.admin.server.web.client.OAuth2ReactiveHttpHeadersProvider;
import de.codecentric.boot.admin.server.web.client.OAuth2RegistrationIdResolver;

/**
 * Auto-configuration for server-side OAuth2 Client Credentials support. Registers an
 * {@link OAuth2ReactiveHttpHeadersProvider} bean when
 * {@code spring-security-oauth2-client} is on the classpath and a
 * {@link ReactiveOAuth2AuthorizedClientManager} bean is available.
 *
 * <p>
 * This is a top-level auto-configuration class (listed in
 * {@code AutoConfiguration.imports}) rather than a nested class inside
 * {@link AdminServerInstanceWebClientConfiguration}, so that Spring can safely skip class
 * introspection when {@code spring-security-oauth2-client} is absent from the classpath
 * without risking a {@link NoClassDefFoundError}.
 *
 * <p>
 * Configured <em>before</em> {@link AdminServerAutoConfiguration} so that the
 * {@link OAuth2ReactiveHttpHeadersProvider} bean is already registered when
 * {@link AdminServerInstanceWebClientConfiguration} evaluates the
 * {@code @ConditionalOnBean(ReactiveHttpHeadersProvider.class)} condition on the reactive
 * headers exchange filter bean.
 *
 * <p>
 * Both the {@link OAuth2RegistrationIdResolver} and the
 * {@link OAuth2ReactiveHttpHeadersProvider} are exposed as
 * {@code @ConditionalOnMissingBean} beans, allowing applications to replace either
 * component individually via a custom {@code @Bean} definition. To suppress
 * metadata-based registration ID resolution, for example in multi-tenant environments,
 * provide a custom {@link OAuth2RegistrationIdResolver} that does not inspect instance
 * metadata.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ReactiveOAuth2AuthorizedClientManager.class)
@ConditionalOnBean(ReactiveOAuth2AuthorizedClientManager.class)
@AutoConfigureBefore(AdminServerAutoConfiguration.class)
@EnableConfigurationProperties(AdminServerProperties.class)
public class AdminServerOAuth2AutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(OAuth2RegistrationIdResolver.class)
	public OAuth2RegistrationIdResolver oauth2RegistrationIdResolver(AdminServerProperties properties) {
		AdminServerProperties.InstanceAuthProperties instanceAuth = properties.getInstanceAuth();
		OAuth2RegistrationIdResolver resolver = OAuth2ReactiveHttpHeadersProvider.metadataRegistrationIdResolver();
		if (instanceAuth.isEnabled()) {
			AdminServerProperties.InstanceOAuth2Properties oauth2 = instanceAuth.getOauth2();
			Map<String, String> serviceMap = oauth2.getServiceMap();
			String defaultId = oauth2.getDefaultRegistrationId();
			resolver = resolver.andThen((instance) -> serviceMap.get(instance.getRegistration().getName()))
				.andThen((instance) -> StringUtils.hasText(defaultId) ? defaultId : null);
		}
		return resolver;
	}

	@Bean
	@ConditionalOnMissingBean(OAuth2ReactiveHttpHeadersProvider.class)
	public OAuth2ReactiveHttpHeadersProvider oauth2ReactiveHttpHeadersProvider(
			ReactiveOAuth2AuthorizedClientManager manager, OAuth2RegistrationIdResolver registrationIdResolver) {
		return new OAuth2ReactiveHttpHeadersProvider(manager, registrationIdResolver);
	}

}
