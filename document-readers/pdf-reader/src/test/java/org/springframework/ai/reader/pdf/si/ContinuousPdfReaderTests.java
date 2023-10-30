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

package org.springframework.ai.reader.pdf.si;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.integration.channel.FixedSubscriberChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.dsl.Files;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.util.FileSystemUtils;

/**
 * @author Christian Tzolov
 */
@SpringBootTest
public class ContinuousPdfReaderTests {

	@TempDir
	static File inboundDirectory;

	@Test
	public void testContinuousPdfReaderPipeline() throws IOException, InterruptedException {

		var samplePdfFile1 = new DefaultResourceLoader().getResource("classpath:/sample1.pdf").getFile();
		var samplePdfFile2 = new DefaultResourceLoader().getResource("classpath:/qx80-abbridged.pdf").getFile();

		FileSystemUtils.copyRecursively(samplePdfFile1, new File(inboundDirectory, samplePdfFile1.getName()));
		FileSystemUtils.copyRecursively(samplePdfFile2, new File(inboundDirectory, samplePdfFile2.getName()));

		Thread.sleep(15000);
	}

	@SpringBootConfiguration
	@EnableIntegration
	public static class Config {

		@Bean
		public DocumentReader<String> reader() {
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
		public MessageHandler messageHandler(DocumentReader<String> pdfReader) {

			return new MessageHandler() {

				@Override
				public void handleMessage(Message<?> message) throws MessagingException {
					String pdfFileUri = "file:" + ((File) message.getPayload()).getAbsolutePath();

					DocumentTransformer transformer1 = docs -> {
						System.out.println(docs.size());
						return docs;
					};

					DocumentTransformer transformer2 = docs -> {
						docs.forEach(doc -> System.out.println(doc.getId()));
						return docs;
					};

					// Document processing pipeline.
					Function<String, List<Document>> pipeline = pdfReader.andThen(transformer1).andThen(transformer2);

					// Note: the Vector Stores are document writers as well.
					FileDocumentWriter docFileWriter = new FileDocumentWriter("./target/extractedDocs.txt", true,
							MetadataMode.ALL, true);

					docFileWriter.accept(pipeline.apply(pdfFileUri));
				}
			};
		}

		@Bean
		public IntegrationFlow fileReadingFlow(MessageHandler messageHandler) {

			// Continuously listen for new, pdf files dropped in the inbound directory.
			// For each new pdf file calls the messageHandler.
			return IntegrationFlow
				.from(Files.inboundAdapter(inboundDirectory).autoCreateDirectory(true).patternFilter("*.pdf"),
						e -> e.poller(Pollers.fixedDelay(1000)))
				.channel(new FixedSubscriberChannel(messageHandler))
				.get();
		}

	}

}
