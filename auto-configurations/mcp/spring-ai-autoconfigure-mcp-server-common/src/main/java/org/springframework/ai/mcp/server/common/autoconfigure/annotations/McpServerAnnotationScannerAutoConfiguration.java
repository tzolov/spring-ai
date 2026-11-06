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

package org.springframework.ai.mcp.server.common.autoconfigure.annotations;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.scan.AbstractAnnotatedMethodBeanFactoryInitializationAotProcessor;
import org.springframework.ai.mcp.annotation.spring.scan.AbstractMcpAnnotatedBeans;
import org.springframework.ai.mcp.annotation.spring.scan.AnnotatedMethodDiscovery;
import org.springframework.aop.support.AopUtils;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;

/**
 * @author Christian Tzolov
 * @author Josh Long
 * @author Daniel Garnier-Moiroux
 */
@AutoConfiguration
@ConditionalOnClass(McpTool.class)
@ConditionalOnProperty(prefix = McpServerAnnotationScannerProperties.CONFIG_PREFIX, name = "enabled",
		havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpServerAnnotationScannerProperties.class)
@ImportRuntimeHints(McpServerAnnotationScannerAutoConfiguration.AnnotationHints.class)
public class McpServerAnnotationScannerAutoConfiguration {

	private static final Set<Class<? extends Annotation>> SERVER_MCP_ANNOTATIONS = Set.of(McpTool.class,
			McpResource.class, McpPrompt.class, McpComplete.class);

	@Bean
	@ConditionalOnMissingBean
	public static ServerMcpAnnotatedBeans serverAnnotatedBeanRegistry() {
		return new ServerMcpAnnotatedBeans(SERVER_MCP_ANNOTATIONS);
	}

	@Bean
	public static ServerAnnotatedBeanFactoryInitializationAotProcessor serverAnnotatedBeanFactoryInitializationAotProcessor() {
		return new ServerAnnotatedBeanFactoryInitializationAotProcessor(SERVER_MCP_ANNOTATIONS);
	}

	/**
	 * Finds the beans with MCP server annotated methods (tools, resources, prompts,
	 * completions).
	 * <p>
	 * The bean definitions are scanned once they are all available and before any bean is
	 * created, so that the beans with handlers are known before the MCP-specific beans
	 * (e.g. {@code McpSyncToolRepository}) that are built from them are created, whatever
	 * the bean order. The scan uses the type declared by each bean definition; a bean
	 * whose declared type does not show its annotated methods (for instance a
	 * {@code @Bean} method returning an interface) is found once it is created, and a
	 * warning is logged when that happens after its handlers have already been collected.
	 * <p>
	 * A bean with annotated methods must not depend eagerly on a repository or server
	 * bean, since those are created from its handlers: use
	 * {@code ObjectProvider<McpSyncToolRepository>} to inject such a dependency.
	 * <p>
	 * This is a {@link BeanFactoryPostProcessor}: a custom instance must be declared in a
	 * {@code static @Bean} method.
	 */
	public static class ServerMcpAnnotatedBeans extends AbstractMcpAnnotatedBeans
			implements BeanFactoryPostProcessor, BeanPostProcessor {

		private static final Logger logger = LoggerFactory.getLogger(ServerMcpAnnotatedBeans.class);

		private final Set<Class<? extends Annotation>> targetAnnotations;

		private final Map<Class<? extends Annotation>, Set<String>> beanNamesByAnnotation = new LinkedHashMap<>();

		private final Set<String> annotatedBeanNames = new LinkedHashSet<>();

		private final Map<String, Class<?>> scannedTypes = new HashMap<>();

		private final Set<Class<? extends Annotation>> collectedAnnotations = new LinkedHashSet<>();

		private @Nullable ConfigurableListableBeanFactory beanFactory;

		/**
		 * Create an instance looking for methods annotated with the MCP server
		 * annotations.
		 */
		public ServerMcpAnnotatedBeans() {
			this(SERVER_MCP_ANNOTATIONS);
		}

		/**
		 * Create an instance looking for methods annotated with any of the given
		 * annotations.
		 * @param targetAnnotations the method annotations to look for
		 * @since 2.1.0
		 */
		public ServerMcpAnnotatedBeans(Set<Class<? extends Annotation>> targetAnnotations) {
			Assert.notEmpty(targetAnnotations, "Target annotations must not be empty");
			this.targetAnnotations = targetAnnotations;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			this.beanFactory = beanFactory;
			for (String beanName : beanFactory.getBeanDefinitionNames()) {
				if (!isCandidate(beanFactory, beanName)) {
					continue;
				}
				// FactoryBeans are not initialized to find their product type: a bean
				// whose type is unknown here is scanned in
				// postProcessAfterInitialization.
				Class<?> beanClass = beanFactory.getType(beanName, false);
				if (beanClass == null) {
					continue;
				}
				this.scannedTypes.put(beanName, beanClass);
				record(beanName, findAnnotations(beanName, beanClass), false);
			}
		}

		private static boolean isCandidate(ConfigurableListableBeanFactory beanFactory, String beanName) {
			if (!beanFactory.containsBeanDefinition(beanName)) {
				return false;
			}
			BeanDefinition beanDefinition = beanFactory.getMergedBeanDefinition(beanName);
			return beanDefinition.isSingleton() && !beanDefinition.isAbstract()
					&& beanDefinition.getRole() != BeanDefinition.ROLE_INFRASTRUCTURE;
		}

		private Set<Class<? extends Annotation>> findAnnotations(String beanName, Class<?> beanClass) {
			if (!AnnotationUtils.isCandidateClass(beanClass, this.targetAnnotations)) {
				return Set.of();
			}
			try {
				return AnnotatedMethodDiscovery.findMethodAnnotations(beanClass, this.targetAnnotations);
			}
			catch (Throwable ex) {
				logger.debug("Could not introspect bean '{}' of type {} for MCP annotations", beanName,
						beanClass.getName(), ex);
				return Set.of();
			}
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			ConfigurableListableBeanFactory beanFactory = this.beanFactory;
			if (beanFactory == null || this.annotatedBeanNames.contains(beanName)
					|| !isCandidate(beanFactory, beanName)) {
				return bean;
			}
			Class<?> targetClass = AopUtils.getTargetClass(bean);
			if (targetClass.equals(this.scannedTypes.get(beanName))) {
				return bean;
			}
			record(beanName, findAnnotations(beanName, targetClass), true);
			return bean;
		}

		private void record(String beanName, Set<Class<? extends Annotation>> annotationTypes,
				boolean afterInitialization) {
			if (annotationTypes.isEmpty()) {
				return;
			}
			this.annotatedBeanNames.add(beanName);
			for (Class<? extends Annotation> annotationType : annotationTypes) {
				this.beanNamesByAnnotation.computeIfAbsent(annotationType, k -> new LinkedHashSet<>()).add(beanName);
				if (afterInitialization && this.collectedAnnotations.contains(annotationType)) {
					logger.warn(
							"Bean '{}' has @{} methods but was only found once created, after the @{} handlers "
									+ "had been collected: its handlers are not registered. Declare the bean with the "
									+ "concrete type that declares the annotated methods.",
							beanName, annotationType.getSimpleName(), annotationType.getSimpleName());
				}
			}
		}

		/**
		 * Return the beans that have at least one method annotated with the given
		 * annotation, creating them if they do not exist yet. Beans added with
		 * {@link #addMcpAnnotatedBean} come first, then the scanned beans in bean
		 * definition order.
		 * @param annotationType the method annotation
		 * @return the annotated beans
		 * @since 2.1.0
		 */
		@Override
		public List<Object> getBeansByAnnotation(Class<? extends Annotation> annotationType) {
			List<Object> beans = new ArrayList<>(super.getBeansByAnnotation(annotationType));
			Set<String> beanNames = this.beanNamesByAnnotation.get(annotationType);
			if (beanNames != null) {
				resolve(beanNames, beans);
			}
			this.collectedAnnotations.add(annotationType);
			return beans;
		}

		/**
		 * Return all the beans with annotated methods, creating them if they do not exist
		 * yet. Beans added with {@link #addMcpAnnotatedBean} come first, then the scanned
		 * beans in bean definition order.
		 * @return the annotated beans
		 * @since 2.1.0
		 */
		@Override
		public List<Object> getAllAnnotatedBeans() {
			List<Object> beans = super.getAllAnnotatedBeans();
			resolve(this.annotatedBeanNames, beans);
			this.collectedAnnotations.addAll(this.targetAnnotations);
			return beans;
		}

		/**
		 * Resolve the given beans. Creating a bean can discover further annotated beans
		 * (see {@link #postProcessAfterInitialization}), which grows the given set: the
		 * set is copied before each pass and passes are repeated until it is stable.
		 */
		private void resolve(Set<String> beanNames, List<Object> beans) {
			Set<String> resolved = new LinkedHashSet<>();
			while (resolved.size() < beanNames.size()) {
				for (String beanName : List.copyOf(beanNames)) {
					if (resolved.add(beanName)) {
						beans.add(getBean(beanName));
					}
				}
			}
		}

		@Override
		public int getCount() {
			return super.getCount() + this.annotatedBeanNames.size();
		}

		private Object getBean(String beanName) {
			ConfigurableListableBeanFactory beanFactory = this.beanFactory;
			Assert.state(beanFactory != null, "The bean factory has not been post-processed yet");
			try {
				return beanFactory.getBean(beanName);
			}
			catch (BeansException ex) {
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					throw new BeanCreationException(beanName, "Bean '" + beanName
							+ "' has MCP handler methods and is currently in creation: it probably depends on an MCP "
							+ "server or repository bean that is being built from its handlers. Inject that "
							+ "dependency lazily, for instance as an ObjectProvider", ex);
				}
				throw ex;
			}
		}

	}

	public static class ServerAnnotatedBeanFactoryInitializationAotProcessor
			extends AbstractAnnotatedMethodBeanFactoryInitializationAotProcessor {

		public ServerAnnotatedBeanFactoryInitializationAotProcessor(
				Set<Class<? extends Annotation>> targetAnnotations) {
			super(targetAnnotations);
		}

	}

	static class AnnotationHints implements RuntimeHintsRegistrar {

		@Override
		public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
			SERVER_MCP_ANNOTATIONS.forEach(an -> hints.reflection().registerType(an, MemberCategory.values()));
		}

	}

}
