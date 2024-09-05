/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.chat.client;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.RequestAdvisor;
import org.springframework.ai.chat.client.advisor.api.ResponseAdvisor;
import org.springframework.ai.chat.client.advisor.api.ResponseAdvisor.StreamResponseMode;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservableHelper;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationDocumentation;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.model.Media;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The default implementation of {@link ChatClient} as created by the
 * {@link Builder#build()} } method.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Josh Long
 * @author Arjen Poutsma
 * @author Soby Chacko
 * @since 1.0.0
 */
public class DefaultChatClient implements ChatClient {

	private static final ChatClientObservationConvention DEFAULT_CHAT_CLIENT_OBSERVATION_CONVENTION = new DefaultChatClientObservationConvention();

	private final ChatModel chatModel;

	private final DefaultChatClientRequestSpec defaultChatClientRequest;

	public DefaultChatClient(ChatModel chatModel, DefaultChatClientRequestSpec defaultChatClientRequest) {
		this.chatModel = chatModel;
		this.defaultChatClientRequest = defaultChatClientRequest;
	}

	@Override
	public ChatClientRequestSpec prompt() {
		return new DefaultChatClientRequestSpec(this.defaultChatClientRequest);
	}

	@Override
	public ChatClientPromptRequestSpec prompt(Prompt prompt) {
		return new DefaultChatClientPromptRequestSpec(this.chatModel, prompt);
	}

	/**
	 * Return a {@code ChatClient2Builder} to create a new {@code ChatClient} whose
	 * settings are replicated from this {@code ChatClientRequest}.
	 */
	@Override
	public Builder mutate() {
		return this.defaultChatClientRequest.mutate();
	}

	public static class DefaultPromptUserSpec implements PromptUserSpec {

		private String text = "";

		private final Map<String, Object> params = new HashMap<>();

		private final List<Media> media = new ArrayList<>();

		@Override
		public PromptUserSpec media(Media... media) {
			this.media.addAll(Arrays.asList(media));
			return this;
		}

		@Override
		public PromptUserSpec media(MimeType mimeType, URL url) {
			this.media.add(new Media(mimeType, url));
			return this;
		}

		@Override
		public PromptUserSpec media(MimeType mimeType, Resource resource) {
			this.media.add(new Media(mimeType, resource));
			return this;
		}

		@Override
		public PromptUserSpec text(String text) {
			this.text = text;
			return this;
		}

		@Override
		public PromptUserSpec text(Resource text, Charset charset) {
			try {
				this.text(text.getContentAsString(charset));
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		@Override
		public PromptUserSpec text(Resource text) {
			this.text(text, Charset.defaultCharset());
			return this;
		}

		@Override
		public PromptUserSpec param(String k, Object v) {
			this.params.put(k, v);
			return this;
		}

		@Override
		public PromptUserSpec params(Map<String, Object> p) {
			this.params.putAll(p);
			return this;
		}

		protected String text() {
			return this.text;
		}

		protected Map<String, Object> params() {
			return this.params;
		}

		protected List<Media> media() {
			return this.media;
		}

	}

	public static class DefaultPromptSystemSpec implements PromptSystemSpec {

		private String text = "";

		private final Map<String, Object> params = new HashMap<>();

		@Override
		public PromptSystemSpec text(String text) {
			this.text = text;
			return this;
		}

		@Override
		public PromptSystemSpec text(Resource text, Charset charset) {
			try {
				this.text(text.getContentAsString(charset));
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		@Override
		public PromptSystemSpec text(Resource text) {
			this.text(text, Charset.defaultCharset());
			return this;
		}

		@Override
		public PromptSystemSpec param(String k, Object v) {
			this.params.put(k, v);
			return this;
		}

		@Override
		public PromptSystemSpec params(Map<String, Object> p) {
			this.params.putAll(p);
			return this;
		}

		protected String text() {
			return this.text;
		}

		protected Map<String, Object> params() {
			return this.params;
		}

	}

	public static class DefaultAdvisorSpec implements AdvisorSpec {

		private final List<Advisor> advisors = new ArrayList<>();

		private final Map<String, Object> params = new HashMap<>();

		public AdvisorSpec param(String k, Object v) {
			this.params.put(k, v);
			return this;
		}

		public AdvisorSpec params(Map<String, Object> p) {
			this.params.putAll(p);
			return this;
		}

		public AdvisorSpec advisors(Advisor... advisors) {
			this.advisors.addAll(List.of(advisors));
			return this;
		}

		public AdvisorSpec advisors(List<Advisor> advisors) {
			this.advisors.addAll(advisors);
			return this;
		}

		public List<Advisor> getAdvisors() {
			return advisors;
		}

		public Map<String, Object> getParams() {
			return params;
		}

	}

	public static class DefaultCallResponseSpec implements CallResponseSpec {

		private final DefaultChatClientRequestSpec request;

		public DefaultCallResponseSpec(DefaultChatClientRequestSpec request) {
			this.request = request;
		}

		public <T> ResponseEntity<ChatResponse, T> responseEntity(Class<T> type) {
			Assert.notNull(type, "the class must be non-null");
			return doResponseEntity(new BeanOutputConverter<T>(type));
		}

		public <T> ResponseEntity<ChatResponse, T> responseEntity(ParameterizedTypeReference<T> type) {
			return doResponseEntity(new BeanOutputConverter<T>(type));
		}

		public <T> ResponseEntity<ChatResponse, T> responseEntity(
				StructuredOutputConverter<T> structuredOutputConverter) {
			return doResponseEntity(structuredOutputConverter);
		}

		protected <T> ResponseEntity<ChatResponse, T> doResponseEntity(StructuredOutputConverter<T> boc) {
			var chatResponse = doGetObservableChatResponse(this.request, boc.getFormat());
			var responseContent = chatResponse.getResult().getOutput().getContent();
			T entity = boc.convert(responseContent);

			return new ResponseEntity<>(chatResponse, entity);
		}

		public <T> T entity(ParameterizedTypeReference<T> type) {
			return doSingleWithBeanOutputConverter(new BeanOutputConverter<T>(type));
		}

		public <T> T entity(StructuredOutputConverter<T> structuredOutputConverter) {
			return doSingleWithBeanOutputConverter(structuredOutputConverter);
		}

		private <T> T doSingleWithBeanOutputConverter(StructuredOutputConverter<T> boc) {
			var chatResponse = doGetObservableChatResponse(this.request, boc.getFormat());
			var stringResponse = chatResponse.getResult().getOutput().getContent();
			return boc.convert(stringResponse);
		}

		public <T> T entity(Class<T> type) {
			Assert.notNull(type, "the class must be non-null");
			var boc = new BeanOutputConverter<T>(type);
			return doSingleWithBeanOutputConverter(boc);
		}

		private ChatResponse doGetChatResponse() {
			return this.doGetObservableChatResponse(this.request, "");
		}

		private ChatResponse doGetObservableChatResponse(DefaultChatClientRequestSpec inputRequest,
				String formatParam) {

			ChatClientObservationContext observationContext = new ChatClientObservationContext(inputRequest,
					formatParam, false);

			var observation = ChatClientObservationDocumentation.AI_CHAT_CLIENT.observation(
					inputRequest.getCustomObservationConvention(), DEFAULT_CHAT_CLIENT_OBSERVATION_CONVENTION,
					() -> observationContext, inputRequest.getObservationRegistry());
			return observation.observe(() -> {
				ChatResponse chatResponse = doGetChatResponse(inputRequest, formatParam, observation);
				return chatResponse;
			});

		}

		private ChatResponse doGetChatResponse(DefaultChatClientRequestSpec inputRequestSpec, String formatParam,
				Observation parentObservation) {

			AdvisedRequest advisedRequest = toAdvisedRequest(inputRequestSpec, formatParam);

			if (!CollectionUtils.isEmpty(inputRequestSpec.advisors)) {

				// Apply the Request advisors
				var currentAdvisors = new ArrayList<>(
						AdvisorObservableHelper.extractRequestAdvisors(inputRequestSpec.advisors));
				for (RequestAdvisor advisor : currentAdvisors) {
					advisedRequest = AdvisorObservableHelper.adviseRequest(parentObservation, advisor, advisedRequest);
				}
			}

			// Apply the around advisor chain that terminates with the, last, model call
			// advisor.
			AdvisedResponse advisedResponse = inputRequestSpec.aroundAdvisorChain.nextAroundCall(advisedRequest);

			// Apply the Response advisors.
			if (!CollectionUtils.isEmpty(inputRequestSpec.getAdvisors())) {
				var currentAdvisors = new ArrayList<>(
						AdvisorObservableHelper.extractResponseAdvisors(inputRequestSpec.getAdvisors()));
				for (ResponseAdvisor advisor : currentAdvisors) {
					advisedResponse = AdvisorObservableHelper.adviseResponse(parentObservation, advisor,
							advisedResponse);
				}
			}

			return advisedResponse.response();
		}

		public ChatResponse chatResponse() {
			return doGetChatResponse();
		}

		public String content() {
			return doGetChatResponse().getResult().getOutput().getContent();
		}

	}

	private static Prompt toPrompt(AdvisedRequest advisedRequest) {

		var messages = new ArrayList<Message>(advisedRequest.messages());

		String processedSystemText = advisedRequest.systemText();
		if (StringUtils.hasText(processedSystemText)) {
			if (!CollectionUtils.isEmpty(advisedRequest.systemParams())) {
				processedSystemText = new PromptTemplate(processedSystemText, advisedRequest.systemParams()).render();
			}
			messages.add(new SystemMessage(processedSystemText));
		}

		String formatParam = (String) advisedRequest.adviseContext().get("formatParam");

		var processedUserText = StringUtils.hasText(formatParam)
				? advisedRequest.userText() + System.lineSeparator() + "{spring_ai_soc_format}"
				: advisedRequest.userText();

		if (StringUtils.hasText(processedUserText)) {

			Map<String, Object> userParams = new HashMap<>(advisedRequest.userParams());
			if (StringUtils.hasText(formatParam)) {
				userParams.put("spring_ai_soc_format", formatParam);
			}
			if (!CollectionUtils.isEmpty(userParams)) {
				processedUserText = new PromptTemplate(processedUserText, userParams).render();
			}
			messages.add(new UserMessage(processedUserText, advisedRequest.media()));
		}

		if (advisedRequest.chatOptions() instanceof FunctionCallingOptions functionCallingOptions) {
			if (!advisedRequest.functionNames().isEmpty()) {
				functionCallingOptions.setFunctions(new HashSet<>(advisedRequest.functionNames()));
			}
			if (!advisedRequest.functionCallbacks().isEmpty()) {
				functionCallingOptions.setFunctionCallbacks(advisedRequest.functionCallbacks());
			}
		}

		return new Prompt(messages, advisedRequest.chatOptions());
	}

	public static class DefaultStreamResponseSpec implements StreamResponseSpec {

		private final DefaultChatClientRequestSpec request;

		public DefaultStreamResponseSpec(DefaultChatClientRequestSpec request) {
			this.request = request;
		}

		private Flux<ChatResponse> doGetObservableFluxChatResponse(DefaultChatClientRequestSpec inputRequest) {
			return Flux.deferContextual(contextView -> {

				ChatClientObservationContext observationContext = new ChatClientObservationContext(inputRequest, "",
						true);

				Observation observation = ChatClientObservationDocumentation.AI_CHAT_CLIENT.observation(
						inputRequest.getCustomObservationConvention(), DEFAULT_CHAT_CLIENT_OBSERVATION_CONVENTION,
						() -> observationContext, inputRequest.getObservationRegistry());

				observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null))
					.start();

				// @formatter:off				
				 return doGetFluxChatResponse(inputRequest, observation)
					 .doOnError(observation::error)
					 .doFinally(s -> {
						 observation.stop();
					 })
					 .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
				 // @formatter:on
			});
		}

		private Flux<ChatResponse> doGetFluxChatResponse(DefaultChatClientRequestSpec inputRequest,
				Observation parentObservation) {

			var advisedRequest0 = toAdvisedRequest(inputRequest, "");

			return Flux.fromIterable(AdvisorObservableHelper.extractRequestAdvisors(inputRequest.advisors))
				.transformDeferredContextual((f, ctx) -> f
					// This allows us to call blocking code in reduce
					.publishOn(Schedulers.boundedElastic())
					.reduce(advisedRequest0, (ar, advisor) -> {
						// Apply the Request advisors
						return AdvisorObservableHelper.adviseRequest(parentObservation, advisor, ar);
					}))
				.single()
				.flatMapMany(advisedRequest -> {

					// Apply the around advisor chain that terminates with the, last,
					// model call advisor.
					Flux<ChatResponse> advisedResponseFlux = inputRequest.aroundAdvisorChain
						.nextAroundStream(advisedRequest);

					// Apply the Response advisors
					if (!CollectionUtils.isEmpty(inputRequest.getAdvisors())) {

						Map<String, Object> advisorContext = new ConcurrentHashMap<>(advisedRequest.adviseContext());

						var responseAdvisors = new ArrayList<>(
								AdvisorObservableHelper.extractResponseAdvisors(inputRequest.getAdvisors()));

						List<ResponseAdvisor> perElementResponseAdvisors = responseAdvisors.stream()
							.filter(a -> a.getStreamResponseMode() == StreamResponseMode.PER_ELEMENT)
							.toList();

						List<ResponseAdvisor> onFinishElementResponseAdvisors = responseAdvisors.stream()
							.filter(a -> a.getStreamResponseMode() == StreamResponseMode.ON_FINISH_ELEMENT)
							.toList();

						// PER_ELEMENT and ON_FINISH_ELEMENT
						advisedResponseFlux = advisedResponseFlux.map(chatResponse -> {

							AdvisedResponse advisedResponse = AdvisedResponse.builder()
								.withResponse(chatResponse)
								.withAdviseContext(advisedRequest.adviseContext())
								.build();

							// PER_ELEMENT
							if (!CollectionUtils.isEmpty(perElementResponseAdvisors)) {
								for (ResponseAdvisor advisor : perElementResponseAdvisors) {
									advisedResponse = AdvisorObservableHelper.adviseResponse(parentObservation, advisor,
											advisedResponse);
								}
							}
							// ON_FINISH_ELEMENT
							if (!CollectionUtils.isEmpty(onFinishElementResponseAdvisors)) {
								for (ResponseAdvisor advisor : onFinishElementResponseAdvisors) {
									boolean withFinishReason = chatResponse.getResults()
										.stream()
										.filter(result -> result != null && result.getMetadata() != null
												&& StringUtils.hasText(result.getMetadata().getFinishReason()))
										.findFirst()
										.isPresent();

									if (withFinishReason) {
										advisedResponse = AdvisorObservableHelper.adviseResponse(parentObservation,
												advisor, advisedResponse);
									}
								}
							}

							advisorContext.putAll(advisedResponse.adviseContext());

							return advisedResponse.response();
						});

						// CUSTOM
						// TODO: how to pass the parentObservation to the custom response
						// advisor?
						List<ResponseAdvisor> customResponseAdvisors = responseAdvisors.stream()
							.filter(a -> a.getStreamResponseMode() == StreamResponseMode.CUSTOM)
							.toList();
						if (!CollectionUtils.isEmpty(customResponseAdvisors)) {
							for (ResponseAdvisor advisor : customResponseAdvisors) {
								advisedResponseFlux = advisor.adviseResponse(advisedResponseFlux,
										advisedRequest.adviseContext());
							}
						}

						// AGGREGATE
						List<ResponseAdvisor> aggregateResponseAdvisors = responseAdvisors.stream()
							.filter(a -> a.getStreamResponseMode() == StreamResponseMode.AGGREGATE)
							.toList();

						if (!CollectionUtils.isEmpty(aggregateResponseAdvisors)) {
							advisedResponseFlux = new MessageAggregator().aggregate(advisedResponseFlux,
									chatResponse -> {
										AdvisedResponse advisedResponse = AdvisedResponse.builder()
											.withResponse(chatResponse)
											.withAdviseContext(advisorContext)
											.build();
										for (ResponseAdvisor advisor : aggregateResponseAdvisors) {
											AdvisorObservableHelper.adviseResponse(parentObservation, advisor,
													advisedResponse);
										}
									});
						}
					}

					return advisedResponseFlux;

				});
		}

		public Flux<ChatResponse> chatResponse() {
			return doGetObservableFluxChatResponse(this.request);
		}

		public Flux<String> content() {
			return doGetObservableFluxChatResponse(this.request).map(r -> {
				if (r.getResult() == null || r.getResult().getOutput() == null
						|| r.getResult().getOutput().getContent() == null) {
					return "";
				}
				return r.getResult().getOutput().getContent();
			}).filter(StringUtils::hasLength);
		}

	}

	public static class DefaultChatClientRequestSpec implements ChatClientRequestSpec {

		private final ObservationRegistry observationRegistry;

		private final ChatClientObservationConvention customObservationConvention;

		private final ChatModel chatModel;

		private String userText = "";

		private String systemText = "";

		private ChatOptions chatOptions;

		private final List<Media> media = new ArrayList<>();

		private final List<String> functionNames = new ArrayList<>();

		private final List<FunctionCallback> functionCallbacks = new ArrayList<>();

		private final List<Message> messages = new ArrayList<>();

		private final Map<String, Object> userParams = new HashMap<>();

		private final Map<String, Object> systemParams = new HashMap<>();

		private final List<Advisor> advisors = new ArrayList<>();

		private final Map<String, Object> advisorParams = new HashMap<>();

		private final DefaultAroundAdvisorChain aroundAdvisorChain;

		public AroundAdvisorChain getAroundAdvisorChain() {
			return this.aroundAdvisorChain;
		}

		private ObservationRegistry getObservationRegistry() {
			return this.observationRegistry;
		}

		private ChatClientObservationConvention getCustomObservationConvention() {
			return this.customObservationConvention;
		}

		public String getUserText() {
			return this.userText;
		}

		public Map<String, Object> getUserParams() {
			return this.userParams;
		}

		public String getSystemText() {
			return this.systemText;
		}

		public Map<String, Object> getSystemParams() {
			return this.systemParams;
		}

		public ChatOptions getChatOptions() {
			return this.chatOptions;
		}

		public List<Advisor> getAdvisors() {
			return this.advisors;
		}

		public Map<String, Object> getAdvisorParams() {
			return this.advisorParams;
		}

		public List<Message> getMessages() {
			return this.messages;
		}

		public List<Media> getMedia() {
			return this.media;
		}

		public List<String> getFunctionNames() {
			return this.functionNames;
		}

		public List<FunctionCallback> getFunctionCallbacks() {
			return this.functionCallbacks;
		}

		/* copy constructor */
		DefaultChatClientRequestSpec(DefaultChatClientRequestSpec ccr) {
			this(ccr.chatModel, ccr.userText, ccr.userParams, ccr.systemText, ccr.systemParams, ccr.functionCallbacks,
					ccr.messages, ccr.functionNames, ccr.media, ccr.chatOptions, ccr.advisors, ccr.advisorParams,
					ccr.observationRegistry, ccr.customObservationConvention);
		}

		public DefaultChatClientRequestSpec(ChatModel chatModel, String userText, Map<String, Object> userParams,
				String systemText, Map<String, Object> systemParams, List<FunctionCallback> functionCallbacks,
				List<Message> messages, List<String> functionNames, List<Media> media, ChatOptions chatOptions,
				List<Advisor> advisors, Map<String, Object> advisorParams, ObservationRegistry observationRegistry,
				ChatClientObservationConvention customObservationConvention) {

			this.chatModel = chatModel;
			this.chatOptions = chatOptions != null ? chatOptions.copy()
					: (chatModel.getDefaultOptions() != null) ? chatModel.getDefaultOptions().copy() : null;

			this.userText = userText;
			this.userParams.putAll(userParams);
			this.systemText = systemText;
			this.systemParams.putAll(systemParams);

			this.functionNames.addAll(functionNames);
			this.functionCallbacks.addAll(functionCallbacks);
			this.messages.addAll(messages);
			this.media.addAll(media);
			this.advisors.addAll(advisors);
			this.advisorParams.putAll(advisorParams);
			this.observationRegistry = observationRegistry;
			this.customObservationConvention = customObservationConvention;

			// @formatter:off
			 this.aroundAdvisorChain = DefaultAroundAdvisorChain.builder(observationRegistry)
				 // At the stack bottom add the non-streaming and streaming model call advisors.
				 // They play the role of the last advisor in the around advisor chain.
				 .push(new CallAroundAdvisor() {

					 @Override
					 public String getName() {						
						 return CallAroundAdvisor.class.getSimpleName();
					 }

					 @Override					
					 public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, AroundAdvisorChain chain) {						
						 return new AdvisedResponse(chatModel.call(toPrompt(advisedRequest)), Collections.unmodifiableMap(advisedRequest.adviseContext()));
					 }
				 })
				 .push(new StreamAroundAdvisor() {

					 @Override
					 public String getName() {
						 return StreamAroundAdvisor.class.getSimpleName();
					 }
					 @Override
					 public Flux<ChatResponse> aroundStream(AdvisedRequest advisedRequest, AroundAdvisorChain chain) {
						 return chatModel.stream(toPrompt(advisedRequest));
					 }
				 })
				 .pushAll(this.advisors)
				 .build();
			 // @formatter:on
		}

		/**
		 * Return a {@code ChatClient2Builder} to create a new {@code ChatClient2} whose
		 * settings are replicated from this {@code ChatClientRequest}.
		 */
		public Builder mutate() {
			DefaultChatClientBuilder builder = (DefaultChatClientBuilder) ChatClient
				.builder(chatModel, this.observationRegistry, this.customObservationConvention)
				.defaultSystem(s -> s.text(this.systemText).params(this.systemParams))
				.defaultUser(u -> u.text(this.userText)
					.params(this.userParams)
					.media(this.media.toArray(new Media[this.media.size()])))
				.defaultOptions(this.chatOptions)
				.defaultFunctions(StringUtils.toStringArray(this.functionNames));

			// workaround to set the missing fields.
			builder.defaultRequest.getMessages().addAll(this.messages);
			builder.defaultRequest.getFunctionCallbacks().addAll(this.functionCallbacks);

			return builder;
		}

		public ChatClientRequestSpec advisors(Consumer<ChatClient.AdvisorSpec> consumer) {
			Assert.notNull(consumer, "the consumer must be non-null");
			var as = new DefaultAdvisorSpec();
			consumer.accept(as);
			this.advisorParams.putAll(as.getParams());
			this.advisors.addAll(as.getAdvisors());
			this.aroundAdvisorChain.pushAll(as.getAdvisors());
			return this;
		}

		public ChatClientRequestSpec advisors(Advisor... advisors) {
			Assert.notNull(advisors, "the advisors must be non-null");
			this.advisors.addAll(Arrays.asList(advisors));
			this.aroundAdvisorChain.pushAll(Arrays.asList(advisors));
			return this;
		}

		public ChatClientRequestSpec advisors(List<Advisor> advisors) {
			Assert.notNull(advisors, "the advisors must be non-null");
			this.advisors.addAll(advisors);
			this.aroundAdvisorChain.pushAll(advisors);
			return this;
		}

		public ChatClientRequestSpec messages(Message... messages) {
			Assert.notNull(messages, "the messages must be non-null");
			this.messages.addAll(List.of(messages));
			return this;
		}

		public ChatClientRequestSpec messages(List<Message> messages) {
			Assert.notNull(messages, "the messages must be non-null");
			this.messages.addAll(messages);
			return this;
		}

		public <T extends ChatOptions> ChatClientRequestSpec options(T options) {
			Assert.notNull(options, "the options must be non-null");
			this.chatOptions = options;
			return this;
		}

		public <I, O> ChatClientRequestSpec function(String name, String description,
				java.util.function.Function<I, O> function) {
			return this.function(name, description, null, function);
		}

		public <I, O> ChatClientRequestSpec function(String name, String description, Class<I> inputType,
				java.util.function.Function<I, O> function) {

			Assert.hasText(name, "the name must be non-null and non-empty");
			Assert.hasText(description, "the description must be non-null and non-empty");
			Assert.notNull(function, "the function must be non-null");

			var fcw = FunctionCallbackWrapper.builder(function)
				.withDescription(description)
				.withName(name)
				.withInputType(inputType)
				.withResponseConverter(Object::toString)
				.build();
			this.functionCallbacks.add(fcw);
			return this;
		}

		public ChatClientRequestSpec functions(String... functionBeanNames) {
			Assert.notNull(functionBeanNames, "the functionBeanNames must be non-null");
			this.functionNames.addAll(List.of(functionBeanNames));
			return this;
		}

		public ChatClientRequestSpec system(String text) {
			Assert.notNull(text, "the text must be non-null");
			this.systemText = text;
			return this;
		}

		public ChatClientRequestSpec system(Resource textResource, Charset charset) {

			Assert.notNull(textResource, "the text resource must be non-null");
			Assert.notNull(charset, "the charset must be non-null");

			try {
				this.systemText = textResource.getContentAsString(charset);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		public ChatClientRequestSpec system(Resource text) {
			Assert.notNull(text, "the text resource must be non-null");
			return this.system(text, Charset.defaultCharset());
		}

		public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) {

			Assert.notNull(consumer, "the consumer must be non-null");

			var ss = new DefaultPromptSystemSpec();
			consumer.accept(ss);
			this.systemText = StringUtils.hasText(ss.text()) ? ss.text() : this.systemText;
			this.systemParams.putAll(ss.params());

			return this;
		}

		public ChatClientRequestSpec user(String text) {
			Assert.notNull(text, "the text must be non-null");
			this.userText = text;
			return this;
		}

		public ChatClientRequestSpec user(Resource text, Charset charset) {

			Assert.notNull(text, "the text resource must be non-null");
			Assert.notNull(charset, "the charset must be non-null");

			try {
				this.userText = text.getContentAsString(charset);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		public ChatClientRequestSpec user(Resource text) {
			Assert.notNull(text, "the text resource must be non-null");
			return this.user(text, Charset.defaultCharset());
		}

		public ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer) {
			Assert.notNull(consumer, "the consumer must be non-null");

			var us = new DefaultPromptUserSpec();
			consumer.accept(us);
			this.userText = StringUtils.hasText(us.text()) ? us.text() : this.userText;
			this.userParams.putAll(us.params());
			this.media.addAll(us.media());
			return this;
		}

		public CallResponseSpec call() {
			return new DefaultCallResponseSpec(this);
		}

		public StreamResponseSpec stream() {
			return new DefaultStreamResponseSpec(this);
		}

	}

	private static AdvisedRequest toAdvisedRequest(DefaultChatClientRequestSpec inputRequest, String formatParam) {
		Map<String, Object> advisorContext = new ConcurrentHashMap<>(inputRequest.getAdvisorParams());
		if (StringUtils.hasText(formatParam)) {
			advisorContext.put("formatParam", formatParam);
		}

		return new AdvisedRequest(inputRequest.chatModel, inputRequest.userText, inputRequest.systemText,
				inputRequest.chatOptions, inputRequest.media, inputRequest.functionNames,
				inputRequest.functionCallbacks, inputRequest.messages, inputRequest.userParams,
				inputRequest.systemParams, inputRequest.advisors, inputRequest.advisorParams, advisorContext);
	}

	public static DefaultChatClientRequestSpec toDefaultChatClientRequestSpec(AdvisedRequest advisedRequest,
			ObservationRegistry observationRegistry, ChatClientObservationConvention customObservationConvention) {

		return new DefaultChatClientRequestSpec(advisedRequest.chatModel(), advisedRequest.userText(),
				advisedRequest.userParams(), advisedRequest.systemText(), advisedRequest.systemParams(),
				advisedRequest.functionCallbacks(), advisedRequest.messages(), advisedRequest.functionNames(),
				advisedRequest.media(), advisedRequest.chatOptions(), advisedRequest.advisors(),
				advisedRequest.advisorParams(), observationRegistry, customObservationConvention);
	}

	// Prompt

	public static class DefaultCallPromptResponseSpec implements CallPromptResponseSpec {

		private final ChatModel chatModel;

		private final Prompt prompt;

		public DefaultCallPromptResponseSpec(ChatModel chatModel, Prompt prompt) {
			this.chatModel = chatModel;
			this.prompt = prompt;
		}

		public String content() {
			return doGetChatResponse(this.prompt).getResult().getOutput().getContent();
		}

		public List<String> contents() {
			return doGetChatResponse(this.prompt).getResults().stream().map(r -> r.getOutput().getContent()).toList();
		}

		public ChatResponse chatResponse() {
			return doGetChatResponse(this.prompt);
		}

		private ChatResponse doGetChatResponse(Prompt prompt) {
			return chatModel.call(prompt);
		}

	}

	public static class DefaultStreamPromptResponseSpec implements StreamPromptResponseSpec {

		private final Prompt prompt;

		private final StreamingChatModel chatModel;

		public DefaultStreamPromptResponseSpec(StreamingChatModel streamingChatModel, Prompt prompt) {
			this.chatModel = streamingChatModel;
			this.prompt = prompt;
		}

		public Flux<ChatResponse> chatResponse() {
			return doGetFluxChatResponse(this.prompt);
		}

		private Flux<ChatResponse> doGetFluxChatResponse(Prompt prompt) {
			return this.chatModel.stream(prompt);
		}

		public Flux<String> content() {
			return doGetFluxChatResponse(this.prompt).map(r -> {
				if (r.getResult() == null || r.getResult().getOutput() == null
						|| r.getResult().getOutput().getContent() == null) {
					return "";
				}
				return r.getResult().getOutput().getContent();
			}).filter(StringUtils::hasLength);
		}

	}

	public static class DefaultChatClientPromptRequestSpec implements ChatClientPromptRequestSpec {

		private final ChatModel chatModel;

		private final Prompt prompt;

		public DefaultChatClientPromptRequestSpec(ChatModel chatModel, Prompt prompt) {
			this.chatModel = chatModel;
			this.prompt = prompt;
		}

		public CallPromptResponseSpec call() {
			return new DefaultCallPromptResponseSpec(this.chatModel, this.prompt);
		}

		public StreamPromptResponseSpec stream() {
			return new DefaultStreamPromptResponseSpec(this.chatModel, this.prompt);
		}

	}

}