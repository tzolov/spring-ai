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

import java.awt.Rectangle;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * @author Christian Tzolov
 */
public class PdfDocumentReader2 implements DocumentReader {

	private final PDDocument document;

	private PdfDocumentReaderConfig config;

	public static class PdfDocumentReaderConfig {

		private final boolean textLeftAlignment;

		private final int pageTopMargin;

		private final int pageBottomMargin;

		private final int deletePageTopTextLines;

		private final int deletePageBottomTextLines;

		private final int deleteTopPagesToSkip;

		/**
		 * Start building a new configuration.
		 * @return The entry point for creating a new configuration.
		 */
		public static Builder builder() {

			return new Builder();
		}

		/**
		 * {@return the default config}
		 */
		public static PdfDocumentReaderConfig defaultConfig() {

			return builder().build();
		}

		private PdfDocumentReaderConfig(Builder builder) {
			this.pageBottomMargin = builder.pageBottomMargin;
			this.pageTopMargin = builder.pageTopMargin;
			this.textLeftAlignment = builder.textLeftAlignment;
			this.deletePageBottomTextLines = builder.deletePageBottomTextLines;
			this.deletePageTopTextLines = builder.deletePageTopTextLines;
			this.deleteTopPagesToSkip = builder.deleteTopPagesToSkip;
		}

		public static class Builder {

			private boolean textLeftAlignment = false;

			private int pageTopMargin = 0;

			private int pageBottomMargin = 0;

			private int deletePageTopTextLines;

			private int deletePageBottomTextLines;

			private int deleteTopPagesToSkip;

			private Builder() {
			}

			/**
			 * Configures the Pdf reader page top margin. Defaults to 0.
			 * @param topMargin page top margin to use
			 * @return this builder
			 */
			public Builder withPageTopMargin(int topMargin) {
				Assert.isTrue(topMargin >= 0, "Page margins must be a positive value.");
				this.pageTopMargin = topMargin;
				return this;
			}

			/**
			 * Configures the Pdf reader page bottom margin. Defaults to 0.
			 * @param bottomMargin page top margin to use
			 * @return this builder
			 */
			public Builder withPageBottomMargin(int bottomMargin) {
				Assert.isTrue(bottomMargin >= 0, "Page margins must be a positive value.");
				this.pageBottomMargin = bottomMargin;
				return this;
			}

			/**
			 * From every extracted page text remove the top N lines. Defaults to 0.
			 * @param textLinesToDelete Number of top text lines to delete.
			 * @return this builder
			 */
			public Builder withDeletePageTopTextLines(int textLinesToDelete) {
				Assert.isTrue(textLinesToDelete >= 0, "Line number must be a positive value.");
				this.deletePageTopTextLines = textLinesToDelete;
				return this;
			}

			/**
			 * From every extracted page text remove the bottom N lines. Defaults to 0.
			 * @param textLinesToDelete Number of bottom text lines to delete.
			 * @return this builder
			 */
			public Builder withDeletePageBottomTextLines(int textLinesToDelete) {
				Assert.isTrue(textLinesToDelete >= 0, "Line number must be a positive value.");
				this.deletePageBottomTextLines = textLinesToDelete;
				return this;
			}

			/**
			 * Withdraw the top N pages from the text top/bottom line deletion. Defaults to 0.
			 * @param textLinesToDelete Number of pages to skip from top/bottom line deletion policy.
			 * @return this builder
			 */
			public Builder withDeleteTopPagesToSkip(int topPagesToSkip) {
				Assert.isTrue(topPagesToSkip >= 0, "Page number must be a positive value.");
				this.deleteTopPagesToSkip = topPagesToSkip;
				return this;
			}

			/**
			 * Configures the Pdf reader to align the document text to the left. Defaults to false.
			 * @param textLeftAlignment flag to align the text to the left.
			 * @return this builder
			 */
			public Builder withTextLeftAlignment(boolean textLeftAlignment) {
				this.textLeftAlignment = textLeftAlignment;
				return this;
			}

			/**
			 * {@return the immutable configuration}
			 */
			public PdfDocumentReaderConfig build() {
				return new PdfDocumentReaderConfig(this);
			}
		}

	}

	public PdfDocumentReader2(String resourceUrl) {
		this(new DefaultResourceLoader().getResource(resourceUrl));
	}

	public PdfDocumentReader2(Resource pdfResource) {
		this(pdfResource, PdfDocumentReaderConfig.defaultConfig());
	}

	public PdfDocumentReader2(String resourceUrl, PdfDocumentReaderConfig config) {
		this(new DefaultResourceLoader().getResource(resourceUrl), config);
	}

	public PdfDocumentReader2(Resource pdfResource, PdfDocumentReaderConfig config) {

		try {
			PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(pdfResource.getInputStream()));
			pdfParser.parse();

			this.config = config;
			this.document = new PDDocument(pdfParser.getDocument());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Document> get() {

		PDDocumentCatalog catalog = this.document.getDocumentCatalog();

		try {
			var pdfTextStripper = new PDFLayoutTextStripperByArea();
			pdfTextStripper.setSortByPosition(true);

			StringBuilder sb = new StringBuilder();

			int pageNumber = 0;

			for (PDPage page : catalog.getPages()) {

				int x0 = (int) page.getMediaBox().getLowerLeftX();
				int xW = (int) page.getMediaBox().getWidth();

				int y0 = (int) page.getMediaBox().getLowerLeftY() + this.config.pageTopMargin;
				int yW = (int) page.getMediaBox().getHeight()
						- (this.config.pageTopMargin + this.config.pageBottomMargin);

				pdfTextStripper.addRegion("pdfPageRegion", new Rectangle(x0, y0, xW, yW));

				pdfTextStripper.extractRegions(page);
				var text = pdfTextStripper.getTextForRegion("pdfPageRegion");
				if (StringUtils.hasText(text)) {

					// Replaces multiple empty lines with single newline
					text = text.replaceAll("(?m)(^ *\n)", "\n").replaceAll("(?m)^$([\r\n]+?)(^$[\r\n]+?^)+", "$1");

					if (pageNumber >= this.config.deleteTopPagesToSkip) {
						text = deletePageTopTextLines(text, this.config.deletePageTopTextLines);
						text = deletePageBottomTextLines(text, this.config.deletePageBottomTextLines);
					}

					if (this.config.textLeftAlignment) {
						text = text.replaceAll("(?m)(^ *| +(?= |$))", "").replaceAll("(?m)^$(	?)(^$[\r\n]+?^)+",
								"$1");
					}

					System.out.println(text);
					sb.append(text);
				}
				pageNumber++;
				pdfTextStripper.removeRegion("pdfPageRegion");
			}

			return List.of(new Document(sb.toString()));

		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String deletePageBottomTextLines(String text, int numberOfLines) {
		if (!StringUtils.hasText(text)) {
			return text;
		}

		int lineCount = 0;
		int truncateIndex = text.length();
		int nextTruncateIndex = truncateIndex;
		while (lineCount < numberOfLines && nextTruncateIndex >= 0) {
			nextTruncateIndex = text.lastIndexOf(System.lineSeparator(), truncateIndex - 1);
			truncateIndex = nextTruncateIndex < 0 ? truncateIndex : nextTruncateIndex;
			lineCount++;
		}
		return text.substring(0, truncateIndex);
	}

	private static String deletePageTopTextLines(String text, int numberOfLines) {
		if (!StringUtils.hasText(text)) {
			return text;
		}
		int lineCount = 0;

		int truncateIndex = 0;
		int nextTruncateIndex = truncateIndex;
		while (lineCount < numberOfLines && nextTruncateIndex >= 0) {
			nextTruncateIndex = text.indexOf(System.lineSeparator(), truncateIndex + 1);
			truncateIndex = nextTruncateIndex < 0 ? truncateIndex : nextTruncateIndex;
			lineCount++;
		}
		return text.substring(truncateIndex, text.length());
	}

	public static void writeToFile(String fileName, List<Document> docs, boolean withDocumentMarkers)
			throws IOException {
		var writer = new FileWriter(fileName, false);

		int i = 0;
		for (Document doc : docs) {
			if (withDocumentMarkers) {
				writer.write("\n### [" + i + "] ###\n");
			}
			writer.write(doc.getContent());
			i++;
		}

		writer.close();
	}

	public static void main(String[] args) throws IOException {

		PdfDocumentReader2 pdfReader = new PdfDocumentReader2("file:spring-ai-core/src/test/resources/uber-k-10.pdf",
				PdfDocumentReaderConfig.builder()
						// .withPageTopMargin(0)
						// .withPageBottomMargin(0)
						.withDeletePageBottomTextLines(3)
						.withDeleteTopPagesToSkip(1)
						.build());

		var documents = pdfReader.get();

		writeToFile("spring-ai-core/target/uber-k-10.txt", documents, true);
		System.out.println(documents.size());

	}

}
