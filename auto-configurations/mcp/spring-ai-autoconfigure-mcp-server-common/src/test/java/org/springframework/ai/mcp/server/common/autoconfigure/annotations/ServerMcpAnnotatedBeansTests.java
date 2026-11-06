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

import java.util.List;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.aop.framework.autoproxy.BeanNameAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServerMcpAnnotatedBeans}.
 *
 * @author Christian Tzolov
 */
@ExtendWith(OutputCaptureExtension.class)
class ServerMcpAnnotatedBeansTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(McpServerAnnotationScannerAutoConfiguration.class));

	@Test
	void annotatedBeansAreKnownBeforeTheyAreCreated() {
		// The consumer is defined first, so it is created before the annotated bean.
		this.contextRunner.withUserConfiguration(ConsumerConfiguration.class, AnnotatedConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				Consumer consumer = context.getBean(Consumer.class);
				assertThat(consumer.tools).containsExactly(context.getBean(Tools.class));
				assertThat(consumer.resources).containsExactly(context.getBean(Resources.class));
				assertThat(consumer.prompts).isEmpty();
			});
	}

	@Test
	void beansAreReturnedInDefinitionOrder() {
		this.contextRunner.withUserConfiguration(AnnotatedConfiguration.class, MoreToolsConfiguration.class)
			.run(context -> {
				ServerMcpAnnotatedBeans registry = context.getBean(ServerMcpAnnotatedBeans.class);
				assertThat(registry.getBeansByAnnotation(McpTool.class)).containsExactly(context.getBean(Tools.class),
						context.getBean(MoreTools.class));
				assertThat(registry.getAllAnnotatedBeans()).containsExactly(context.getBean(Tools.class),
						context.getBean(Resources.class), context.getBean(MoreTools.class));
				assertThat(registry.getCount()).isEqualTo(3);
				assertThat(registry.getBeansByAnnotation(McpPrompt.class)).isEmpty();
			});
	}

	@Test
	void beanDeclaredWithInterfaceTypeIsFoundOnceCreated(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(InterfaceTypedConfiguration.class, ConsumerConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Consumer.class).tools).containsExactly(context.getBean(ToolsApi.class));
				assertThat(output).doesNotContain("its handlers are not registered");
			});
	}

	@Test
	void beanDeclaredWithInterfaceTypeFoundAfterCollectionIsLogged(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(ConsumerConfiguration.class, InterfaceTypedConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Consumer.class).tools).isEmpty();
				assertThat(output).contains("Bean 'toolsApi' has @McpTool methods but was only found once created");
				// Later requests do see the bean.
				assertThat(context.getBean(ServerMcpAnnotatedBeans.class).getBeansByAnnotation(McpTool.class))
					.containsExactly(context.getBean(ToolsApi.class));
			});
	}

	@Test
	void prototypeBeansAreIgnored() {
		this.contextRunner.withUserConfiguration(PrototypeConfiguration.class).run(context -> {
			ServerMcpAnnotatedBeans registry = context.getBean(ServerMcpAnnotatedBeans.class);
			context.getBean(Tools.class);
			assertThat(registry.getBeansByAnnotation(McpTool.class)).isEmpty();
			assertThat(registry.getCount()).isZero();
		});
	}

	@Test
	void manuallyAddedBeansComeFirst() {
		this.contextRunner.withUserConfiguration(AnnotatedConfiguration.class).run(context -> {
			ServerMcpAnnotatedBeans registry = context.getBean(ServerMcpAnnotatedBeans.class);
			Tools extra = new Tools();
			registry.addMcpAnnotatedBean(extra, Set.of(McpTool.class));
			assertThat(registry.getBeansByAnnotation(McpTool.class)).containsExactly(extra,
					context.getBean(Tools.class));
			assertThat(registry.getAllAnnotatedBeans()).startsWith(extra);
			assertThat(registry.getCount()).isEqualTo(3);
		});
	}

	@Test
	void annotatedBeanDependingOnItsConsumerFailsWithHint() {
		this.contextRunner.withUserConfiguration(CyclicConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("Bean 'cyclicTools' has MCP handler methods and is currently in creation")
				.hasStackTraceContaining("ObjectProvider");
		});
	}

	@Test
	void annotatedBeanFoundWhileResolvingIsIncluded(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(ConsumerConfiguration.class, HelperDependentConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Consumer.class).tools).containsExactly(context.getBean(Tools.class),
						context.getBean(HelperApi.class));
				assertThat(output).doesNotContain("its handlers are not registered");
			});
	}

	@Test
	void cycleThroughIntermediateBeanFailsWithHint() {
		this.contextRunner.withUserConfiguration(IntermediateCyclicConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("Bean 'tools' has MCP handler methods and is currently in creation")
				.hasStackTraceContaining("ObjectProvider");
		});
	}

	@Test
	void beanFoundAfterAllAnnotatedBeansWereCollectedIsLogged(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(AllConsumerConfiguration.class, InterfaceTypedConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AllConsumer.class).beans).isEmpty();
				assertThat(output).contains("Bean 'toolsApi' has @McpTool methods but was only found once created");
			});
	}

	@Test
	void autoProxiedBeansAreFoundOnce() {
		this.contextRunner
			.withUserConfiguration(AutoProxyConfiguration.class, AnnotatedConfiguration.class,
					InterfaceTypedConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				Object tools = context.getBean("tools");
				Object toolsApi = context.getBean("toolsApi");
				assertThat(AopUtils.isCglibProxy(tools)).isTrue();
				assertThat(AopUtils.isJdkDynamicProxy(toolsApi)).isTrue();
				ServerMcpAnnotatedBeans registry = context.getBean(ServerMcpAnnotatedBeans.class);
				assertThat(registry.getBeansByAnnotation(McpTool.class)).containsExactly(tools, toolsApi);
				assertThat(registry.getCount()).isEqualTo(3);
			});
	}

	@Test
	void customRegistryBeanIsUsed() {
		this.contextRunner.withUserConfiguration(CustomRegistryConfiguration.class, AnnotatedConfiguration.class)
			.run(context -> {
				ServerMcpAnnotatedBeans registry = context.getBean(ServerMcpAnnotatedBeans.class);
				assertThat(registry).isInstanceOf(CustomRegistry.class);
				assertThat(registry.getBeansByAnnotation(McpTool.class)).containsExactly(context.getBean(Tools.class));
			});
	}

	static class Tools {

		@McpTool(name = "t", description = "t")
		public String tool() {
			return "t";
		}

	}

	static class MoreTools {

		@McpTool(name = "m", description = "m")
		public String more() {
			return "m";
		}

	}

	static class Resources {

		@McpResource(uri = "test://r", name = "r")
		public String resource() {
			return "r";
		}

	}

	interface ToolsApi {

		String tool();

	}

	static class ToolsImpl implements ToolsApi {

		@Override
		@McpTool(name = "api", description = "api")
		public String tool() {
			return "api";
		}

	}

	static class Consumer {

		final List<Object> tools;

		final List<Object> resources;

		final List<Object> prompts;

		Consumer(ServerMcpAnnotatedBeans registry) {
			this.tools = registry.getBeansByAnnotation(McpTool.class);
			this.resources = registry.getBeansByAnnotation(McpResource.class);
			this.prompts = registry.getBeansByAnnotation(McpPrompt.class);
		}

	}

	static class CyclicTools {

		CyclicTools(Consumer consumer) {
		}

		@McpTool(name = "c", description = "c")
		public String tool() {
			return "c";
		}

	}

	static class CustomRegistry extends ServerMcpAnnotatedBeans {

	}

	interface HelperApi {

		String help();

	}

	static class HelperImpl implements HelperApi {

		@Override
		@McpTool(name = "help", description = "help")
		public String help() {
			return "help";
		}

	}

	static class Intermediate {

		Intermediate(Consumer consumer) {
		}

	}

	static class AllConsumer {

		final List<Object> beans;

		AllConsumer(ServerMcpAnnotatedBeans registry) {
			this.beans = registry.getAllAnnotatedBeans();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class HelperDependentConfiguration {

		@Bean
		Tools tools(HelperApi helper) {
			return new Tools();
		}

		@Bean
		HelperApi helper() {
			return new HelperImpl();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class IntermediateCyclicConfiguration {

		@Bean
		Intermediate intermediate(Consumer consumer) {
			return new Intermediate(consumer);
		}

		@Bean
		Tools tools(Intermediate intermediate) {
			return new Tools();
		}

		@Bean
		Consumer consumer(ServerMcpAnnotatedBeans registry) {
			return new Consumer(registry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AllConsumerConfiguration {

		@Bean
		AllConsumer allConsumer(ServerMcpAnnotatedBeans registry) {
			return new AllConsumer(registry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AutoProxyConfiguration {

		@Bean
		static BeanNameAutoProxyCreator autoProxyCreator() {
			BeanNameAutoProxyCreator creator = new BeanNameAutoProxyCreator();
			creator.setBeanNames("tools", "toolsApi");
			creator.setInterceptorNames("noopInterceptor");
			return creator;
		}

		@Bean
		MethodInterceptor noopInterceptor() {
			return MethodInvocation::proceed;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AnnotatedConfiguration {

		@Bean
		Tools tools() {
			return new Tools();
		}

		@Bean
		Resources resources() {
			return new Resources();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class MoreToolsConfiguration {

		@Bean
		MoreTools moreTools() {
			return new MoreTools();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ConsumerConfiguration {

		@Bean
		Consumer consumer(ServerMcpAnnotatedBeans registry) {
			return new Consumer(registry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class InterfaceTypedConfiguration {

		@Bean
		ToolsApi toolsApi() {
			return new ToolsImpl();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PrototypeConfiguration {

		@Bean
		@Scope("prototype")
		Tools tools() {
			return new Tools();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CyclicConfiguration {

		@Bean
		CyclicTools cyclicTools(Consumer consumer) {
			return new CyclicTools(consumer);
		}

		@Bean
		Consumer consumer(ServerMcpAnnotatedBeans registry) {
			return new Consumer(registry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomRegistryConfiguration {

		@Bean
		static ServerMcpAnnotatedBeans serverAnnotatedBeanRegistry() {
			return new CustomRegistry();
		}

	}

}
