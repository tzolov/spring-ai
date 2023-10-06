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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * @author Christian Tzolov
 */
public class PdfDocumentReader implements DocumentReader {

	private static final String METADATA_START_PAGE = "startPage";

	private static final String METADATA_END_PAGE = "endPage";

	private static final String METADATA_TITLE = "title";

	private static final String METADATA_LEVEL = "level";

	private final ParagraphTextExtractor paragraphTextExtractor;

	private final PDDocument document;

	private PdfDocumentReaderConfig config;

	public static class PdfDocumentReaderConfig {

		/**
		 * In some PDF documents the PDOutlineItem destination (== paragraph position) is vertically inverted compared
		 * to the page.getMediaBox() coordinates.
		 */
		private final boolean reversedParagraphPosition;

		private final boolean textLeftAlignment;

		private final int pageTopMargin;

		private final int pageBottomMargin;

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
			this.reversedParagraphPosition = builder.reversedParagraphPosition;
			this.textLeftAlignment = builder.textLeftAlignment;
		}

		public static class Builder {

			private boolean reversedParagraphPosition = false;

			private boolean textLeftAlignment = false;

			private int pageTopMargin = 0;

			private int pageBottomMargin = 0;

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
			 * Configures the Pdf reader reverse paragraph position. Defaults to false.
			 * @param reversedParagraphPosition to reverse or not the paragraph position withing a page.
			 * @return this builder
			 */
			public Builder withReversedParagraphPosition(boolean reversedParagraphPosition) {
				this.reversedParagraphPosition = reversedParagraphPosition;
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

	public record Paragraph(Paragraph parent, String title, int level, int startPageNumber, int endPageNumber,
			int position,
			List<Paragraph> children) {

		public Paragraph(Paragraph parent, String title, int level, int startPageNumber, int endPageNumber,
				int position) {
			this(parent, title, level, startPageNumber, endPageNumber, position, new ArrayList<>());
		}

		@Override
		public String toString() {
			String indent = (level < 0) ? "" : new String(new char[level * 2]).replace('\0', ' ');

			return indent + " " + level + ") " + title + " [" + startPageNumber + "," + endPageNumber
					+ "], children = " + children.size() + ", pos = " + position;
		}

	}

	public PdfDocumentReader(String resourceUrl) {
		this(new DefaultResourceLoader().getResource(resourceUrl));
	}

	public PdfDocumentReader(Resource pdfResource) {
		this(pdfResource, PdfDocumentReaderConfig.defaultConfig());
	}

	public PdfDocumentReader(String resourceUrl, PdfDocumentReaderConfig config) {
		this(new DefaultResourceLoader().getResource(resourceUrl), config);
	}

	public PdfDocumentReader(Resource pdfResource, PdfDocumentReaderConfig config) {

		try {
			PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(pdfResource.getInputStream()));
			pdfParser.parse();

			this.config = config;
			this.document = new PDDocument(pdfParser.getDocument());

			this.paragraphTextExtractor = new ParagraphTextExtractor(this.document);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Document> get() {

		var paragraphs = this.paragraphTextExtractor.flattenParagraphTree();

		List<Document> documents = new ArrayList<>(paragraphs.size());

		if (!CollectionUtils.isEmpty(paragraphs)) {
			Iterator<Paragraph> itr = paragraphs.iterator();

			var current = itr.next();

			if (!itr.hasNext()) {
				documents.add(toDocument(current, current));
			}
			else {
				while (itr.hasNext()) {
					var next = itr.next();
					Document document = toDocument(current, next);
					if (StringUtils.hasText(document.getContent())) {
						documents.add(document);
					}
					current = next;
				}
			}
		}

		return documents;
	}

	private Document toDocument(Paragraph from, Paragraph to) {

		String docText = this.getTextBetweenParagraphs(from, to);

		Document document = new Document(docText);
		document.getMetadata().put(METADATA_TITLE, from.title());
		document.getMetadata().put(METADATA_START_PAGE, from.startPageNumber());
		document.getMetadata().put(METADATA_END_PAGE, to.startPageNumber());
		document.getMetadata().put(METADATA_LEVEL, from.level());

		return document;
	}

	public String getTextBetweenParagraphs2(Paragraph fromParagraph, Paragraph toParagraph) {

		// Page started from index 0, while PDFBOx getPage return them from index 1.
		int startPage = fromParagraph.startPageNumber() - 1;
		int endPage = toParagraph.startPageNumber() - 1;

		try {

			StringBuilder sb = new StringBuilder();

			var pdfTextStripper = new PDFLayoutTextStripperByArea();
			pdfTextStripper.setSortByPosition(true);

			for (int pageNumber = startPage; pageNumber <= endPage; pageNumber++) {

				var page = this.document.getPage(pageNumber);

				// int fromPosition = fromParagraph.position();
				// int toPosition = toParagraph.position();

				// if (this.config.reversedParagraphPosition) {
				// 	fromPosition = (int) (page.getMediaBox().getHeight() - fromPosition);
				// 	toPosition = (int) (page.getMediaBox().getHeight() - toPosition);
				// }

				int x0 = (int) page.getMediaBox().getLowerLeftX();
				int xW = (int) page.getMediaBox().getWidth();

				int y0 = (int) page.getMediaBox().getLowerLeftY();
				int yW = (int) page.getMediaBox().getHeight();

				// if (pageNumber == startPage) {
				// 	y0 = fromPosition;
				// 	yW = (int) page.getMediaBox().getHeight() - y0;
				// }
				// if (pageNumber == endPage) {
				// 	yW = toPosition - y0;
				// }

				if ((y0 + yW) == (int) page.getMediaBox().getHeight()) {
					yW = yW - this.config.pageBottomMargin;
				}

				if (y0 == 0) {
					y0 = y0 + this.config.pageTopMargin;
					yW = yW - this.config.pageTopMargin;
				}

				pdfTextStripper.addRegion("pdfPageRegion", new Rectangle(x0, y0, xW, yW));
				pdfTextStripper.extractRegions(page);
				var text = pdfTextStripper.getTextForRegion("pdfPageRegion");
				if (StringUtils.hasText(text)) {
					// System.out.println(text);
					sb.append(text);
				}
				pdfTextStripper.removeRegion("pdfPageRegion");

			}
			// if (StringUtils.hasText(sb.toString())) {
			// System.out.println("----------------------------------------------------------");
			// }

			String text = sb.toString();


			// Replaces multiple empty lines with single newline
			text = text.replaceAll("(?m)(^ *\n)", "\n").replaceAll("(?m)^$([\r\n]+?)(^$[\r\n]+?^)+", "$1");

			var text2 = text.replaceAll("(?m)(^ *| +(?= |$))", "").replaceAll("(?m)^$([\r\n]+?)(^$[\r\n]+?^)+",
						"$1");

			var fromIndex = text2.indexOf(fromParagraph.title());
			var toIndex = text2.indexOf(toParagraph.title());

			if (this.config.textLeftAlignment) {
				text = text.replaceAll("(?m)(^ *| +(?= |$))", "").replaceAll("(?m)^$([\r\n]+?)(^$[\r\n]+?^)+",
						"$1");
			}

			return text;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static int countLines(String str){
		String[] lines = str.split("\r\n|\r|\n");
		return  lines.length;
	 }

	public String getTextBetweenParagraphs(Paragraph fromParagraph, Paragraph toParagraph) {

		// Page started from index 0, while PDFBOx getPage return them from index 1.
		int startPage = fromParagraph.startPageNumber() - 1;
		int endPage = toParagraph.startPageNumber() - 1;

		try {

			StringBuilder sb = new StringBuilder();

			var pdfTextStripper = new PDFLayoutTextStripperByArea();
			pdfTextStripper.setSortByPosition(true);

			for (int pageNumber = startPage; pageNumber <= endPage; pageNumber++) {

				var page = this.document.getPage(pageNumber);

				int fromPosition = fromParagraph.position();
				int toPosition = toParagraph.position();

				if (this.config.reversedParagraphPosition) {
					fromPosition = (int) (page.getMediaBox().getHeight() - fromPosition);
					toPosition = (int) (page.getMediaBox().getHeight() - toPosition);
				}

				int x0 = (int) page.getMediaBox().getLowerLeftX();
				int xW = (int) page.getMediaBox().getWidth();

				int y0 = (int) page.getMediaBox().getLowerLeftY();
				int yW = (int) page.getMediaBox().getHeight();

				if (pageNumber == startPage) {
					y0 = fromPosition;
					yW = (int) page.getMediaBox().getHeight() - y0;
				}
				if (pageNumber == endPage) {
					yW = toPosition - y0;
				}

				if ((y0 + yW) == (int) page.getMediaBox().getHeight()) {
					yW = yW - this.config.pageBottomMargin;
				}

				if (y0 == 0) {
					y0 = y0 + this.config.pageTopMargin;
					yW = yW - this.config.pageTopMargin;
				}

				pdfTextStripper.addRegion("pdfPageRegion", new Rectangle(x0, y0, xW, yW));
				pdfTextStripper.extractRegions(page);
				var text = pdfTextStripper.getTextForRegion("pdfPageRegion");
				if (StringUtils.hasText(text)) {
					// System.out.println(text);
					sb.append(text);
				}
				pdfTextStripper.removeRegion("pdfPageRegion");

			}
			// if (StringUtils.hasText(sb.toString())) {
			// System.out.println("----------------------------------------------------------");
			// }

			String text = sb.toString();

			// Replaces multiple empty lines with single newline
			text = text.replaceAll("(?m)(^ *\n)", "\n").replaceAll("(?m)^$([\r\n]+?)(^$[\r\n]+?^)+", "$1");

			if (this.config.textLeftAlignment) {
				text = text.replaceAll("(?m)(^ *| +(?= |$))", "").replaceAll("(?m)^$([\r\n]+?)(^$[\r\n]+?^)+",
						"$1");
			}

			return text;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static class ParagraphTextExtractor {

		private final Paragraph rootParagraph;

		private final PDDocument document;

		public ParagraphTextExtractor(PDDocument document) {
			try {

				this.document = document;

				this.rootParagraph = generateParagraphs(
						new Paragraph(null, "root", -1, 1, this.document.getNumberOfPages(), 0),
						this.document.getDocumentCatalog().getDocumentOutline(), 0);

				printParagraph(rootParagraph, System.out);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}

		}

		public List<Paragraph> flattenParagraphTree() {
			List<Paragraph> paragraphs = new ArrayList<>();
			for (var child : this.rootParagraph.children()) {
				flatten(child, paragraphs);
			}
			return paragraphs;
		}

		private void flatten(Paragraph current, List<Paragraph> paragraphs) {
			paragraphs.add(current);
			for (var child : current.children()) {
				flatten(child, paragraphs);
			}
		}

		private void printParagraph(Paragraph paragraph, PrintStream printStream) {
			printStream.println(paragraph);
			for (Paragraph childParagraph : paragraph.children()) {
				printParagraph(childParagraph, printStream);
			}
		}

		public Paragraph generateParagraphs(Paragraph parentParagraph, PDOutlineNode bookmark, Integer level)
				throws IOException {

			PDOutlineItem current = bookmark.getFirstChild();
			while (current != null) {

				int pageNumber = getPageNumber(current);
				var nextSiblingNumber = getPageNumber(current.getNextSibling());
				if (nextSiblingNumber < 0) {
					nextSiblingNumber = getPageNumber(current.getLastChild());
				}

				var paragraphPosition = (current.getDestination() instanceof PDPageXYZDestination)
						? ((PDPageXYZDestination) current.getDestination()).getTop()
						: -1;

				var currentParagraph = new Paragraph(parentParagraph, current.getTitle(), level, pageNumber,
						nextSiblingNumber, paragraphPosition);

				parentParagraph.children().add(currentParagraph);

				generateParagraphs(currentParagraph, current, level + 1); // reverse loop
																			// on ToC
				current = current.getNextSibling();
			}
			// fix some page ending numbers (e.g. replace -1 with the start page number
			// for the follow up paragraph)
			// if (parentParagraph.parent() == null) {
			// fixEndPageNumber(parentParagraph, 0);
			// }
			return parentParagraph;
		}

		// private void fixEndPageNumber(Paragraph paragraph, int parentEndPageNumber) {
		// if (paragraph.endPageNumber() < 0) {
		// paragraph.setEndPageNumber(parentEndPageNumber);
		// }
		// for (Paragraph childParagraph : paragraph.children()) {
		// fixEndPageNumber(childParagraph, paragraph.endPageNumber());
		// }
		// }

		private int getPageNumber(PDOutlineItem current) throws IOException {
			if (current == null) {
				return -1;
			}
			PDPage currentPage = current.findDestinationPage(this.document);
			PDPageTree pages = this.document.getDocumentCatalog().getPages();
			for (int i = 0; i < pages.getCount(); i++) {
				var page = pages.get(i);
				if (page.equals(currentPage)) {
					return i + 1;
				}
			}
			return -1;
		}

		public List<Paragraph> getParagraphsByLevel(Paragraph paragraph, int level, boolean interLevelText) {

			List<Paragraph> resultList = new ArrayList<>();

			if (paragraph.level() < level) {
				if (!CollectionUtils.isEmpty(paragraph.children())) {

					if (interLevelText) {
						var interLevelParagraph = new Paragraph(paragraph.parent(), paragraph.title(),
								paragraph.level(), paragraph.startPageNumber(),
								paragraph.children().get(0).startPageNumber(), paragraph.position());
						resultList.add(interLevelParagraph);
					}

					for (Paragraph child : paragraph.children()) {
						resultList.addAll(getParagraphsByLevel(child, level, interLevelText));
					}
				}
			}
			else if (paragraph.level() == level) {
				resultList.add(paragraph);
			}

			return resultList;
		}

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

		// PdfLoader pdfLoader = new PdfLoader(
		// "file:spring-ai-core/src/test/resources/spring-framework.pdf", PdfLoaderConfig.builder()
		// .withPageBottomMargin(15)
		// .withReversedParagraphPosition(true)
		// // .withTextLeftAlignment(true)
		// .build());

		// PdfDocumentReader pdfReader = new PdfDocumentReader("file:spring-ai-core/src/test/resources/mpb.pdf",
		PdfDocumentReader pdfReader = new PdfDocumentReader("file:spring-ai-core/src/test/resources/uber-k-10.pdf",
				PdfDocumentReaderConfig.builder()
						.withPageTopMargin(80)
						.withPageBottomMargin(60)
						.build());

		var documents = pdfReader.get();

		writeToFile("spring-ai-core/target/uber-k-10.txt", documents, true);
		System.out.println(documents.size());

	}

}
