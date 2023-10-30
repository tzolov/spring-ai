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

package org.springframework.ai.reader.pdf;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.pdf.layout.PageExtractedTextFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Christian Tzolov
 */
public class PagePdfDocumentReaderTests {

	@Test
	public void test1() {

		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(PdfDocumentReaderConfig.builder()
			.withPageTopMargin(0)
			.withPageBottomMargin(0)
			.withPageExtractedTextFormatter(PageExtractedTextFormatter.builder()
				.withNumberOfTopTextLinesToDelete(0)
				.withNumberOfBottomTextLinesToDelete(3)
				.withNumberOfTopPagesToSkipBeforeDelete(0)
				.build())
			.withPagesPerDocument(1)
			.build());

		List<Document> docs = pdfReader.apply("classpath:/sample1.pdf");

		assertThat(docs).hasSize(4);

		String allText = docs.stream().map(d -> d.getContent()).collect(Collectors.joining("\n"));

		assertThat(allText).doesNotContain(
				List.of("Page  1 of 4", "Page  2 of 4", "Page  3 of 4", "Page  4 of 4", "PDF  Bookmark   Sample"));
	}

}
