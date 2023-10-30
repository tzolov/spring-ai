/*
 * Copyright 2023-2023 the original author or authors.
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

package org.springframework.ai.reader.pdf.scf;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.pdf.layout.PageExtractedTextFormatter;
import org.springframework.ai.writer.FileDocumentWriter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.integration.dsl.FunctionFlowBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.LoggingHandler;

/**
 * https://docs.spring.io/spring-cloud-function/reference/spring-integration.html
 *
 * @author Christian Tzolov
 */
// @SpringBootTest(properties = "spring.cloud.function.definition=someFunctionFlow")
public class SpringCloudFunctionTests {

	@Test
	public void testContinuousPdfReaderPipeline() throws IOException, InterruptedException {
		Thread.sleep(15000);
	}

	@SpringBootConfiguration
	@EnableIntegration
	public static class Config {

		@Bean
		public DocumentReader<String> reader(FunctionCatalog functionCatalog) {
			return new PagePdfDocumentReader(PdfDocumentReaderConfig.builder()
				.withPageTopMargin(0)
				.withPageBottomMargin(0)
				.withPageExtractedTextFormatter(PageExtractedTextFormatter.builder()
					.withNumberOfTopTextLinesToDelete(0)
					.withNumberOfBottomTextLinesToDelete(3)
					.withNumberOfTopPagesToSkipBeforeDelete(0)
					.build())
				.withPagesPerDocument(1)
				.build());
		}

		@Bean
		QueueChannel wireTapChannel() {
			return new QueueChannel();
		}

		@Bean
		DocumentTransformer transformer(FunctionCatalog functionCatalog) {
			return docs -> {
				docs.forEach(doc -> System.out.println(doc.getId()));
				return docs;
			};
		}

		@Bean
		Consumer<List<Document>> fileDocumentWriter() {
			return new FileDocumentWriter("extractedDocs.txt", true, MetadataMode.ALL, true);
		}

		@Bean
		IntegrationFlow someFunctionFlow(FunctionFlowBuilder functionFlowBuilder) {
			return functionFlowBuilder.fromSupplier(() -> "classpath:/sample1.pdf")
				.wireTap("wireTapChannel")
				.apply("reader")
				.apply("transformer")
				.log(LoggingHandler.Level.WARN)
				.accept("fileDocumentWriter");
		}

	}

}
