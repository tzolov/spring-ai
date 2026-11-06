/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.mcp.annotation.spring.scan;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

/**
 * Finds the MCP annotations present on the methods of a class.
 *
 * @author Christian Tzolov
 * @since 2.1.0
 */
public class AnnotatedMethodDiscovery {

	protected final Set<Class<? extends Annotation>> targetAnnotations;

	public AnnotatedMethodDiscovery(Set<Class<? extends Annotation>> targetAnnotations) {
		Assert.notNull(targetAnnotations, "Target annotations must not be null");
		this.targetAnnotations = targetAnnotations;
	}

	/**
	 * Return the target annotation types present on at least one method of the given
	 * class, including inherited and interface methods.
	 * @param beanClass the class to scan
	 * @return the annotation types found, in encounter order
	 */
	protected Set<Class<? extends Annotation>> scan(Class<?> beanClass) {
		return findMethodAnnotations(beanClass, this.targetAnnotations);
	}

	/**
	 * Return the given annotation types that are present on at least one method of the
	 * given class, including inherited and interface methods.
	 * @param beanClass the class to scan
	 * @param targetAnnotations the method annotations to look for
	 * @return the annotation types found, in encounter order
	 * @since 2.1.0
	 */
	public static Set<Class<? extends Annotation>> findMethodAnnotations(Class<?> beanClass,
			Set<Class<? extends Annotation>> targetAnnotations) {
		Set<Class<? extends Annotation>> foundAnnotations = new LinkedHashSet<>();
		ReflectionUtils.doWithMethods(beanClass, method -> {
			for (Class<? extends Annotation> annotationType : targetAnnotations) {
				if (AnnotationUtils.findAnnotation(method, annotationType) != null) {
					foundAnnotations.add(annotationType);
				}
			}
		});
		return foundAnnotations;
	}

}
