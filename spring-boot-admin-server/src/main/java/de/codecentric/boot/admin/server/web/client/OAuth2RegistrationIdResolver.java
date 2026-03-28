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

package de.codecentric.boot.admin.server.web.client;

import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

import de.codecentric.boot.admin.server.domain.entities.Instance;

/**
 * Strategy for resolving the OAuth2 client registration ID that should be used when
 * obtaining a Bearer token for a given {@link Instance}.
 *
 * <p>
 * Implementations are typically chained using
 * {@link #andThen(OAuth2RegistrationIdResolver)} so that the first resolver that returns
 * a non-blank value wins. For example:
 *
 * <pre>{@code
 * OAuth2RegistrationIdResolver resolver =
 *     OAuth2ReactiveHttpHeadersProvider.metadataRegistrationIdResolver()
 *         .andThen(instance -> serviceMap.get(instance.getRegistration().getName()))
 *         .andThen(instance -> defaultRegistrationId);
 * }</pre>
 *
 * <p>
 * Auto-configuration exposes the default resolver chain as a
 * {@code @ConditionalOnMissingBean} bean so that applications can replace or extend it
 * with a custom {@code @Bean} of this type.
 */
@FunctionalInterface
public interface OAuth2RegistrationIdResolver {

	/**
	 * Resolve the OAuth2 client registration ID for the given instance.
	 * @param instance the instance for which headers are about to be fetched
	 * @return the registration ID, or {@code null} / blank string if this resolver does
	 * not apply
	 */
	@Nullable String resolve(Instance instance);

	/**
	 * Returns a composed resolver that first tries {@code this} resolver and, if it
	 * returns a blank or {@code null} value, delegates to {@code fallback}.
	 * @param fallback the resolver to try when this one returns no result
	 * @return a composed resolver
	 */
	default OAuth2RegistrationIdResolver andThen(OAuth2RegistrationIdResolver fallback) {
		return (instance) -> {
			String id = this.resolve(instance);
			return StringUtils.hasText(id) ? id : fallback.resolve(instance);
		};
	}

}
