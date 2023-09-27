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

package org.springframework.ai.loader.impl.pdf;

import java.awt.Rectangle;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import io.github.jonathanlink.PDFLayoutTextStripper;
import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import org.springframework.ai.document.Document;
import org.springframework.ai.loader.Loader;
import org.springframework.ai.splitter.TextSplitter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 *
 * @author Christian Tzolov
 */
public class PdfLoader implements Loader {

	private final ParagraphTextExtractor paragraphTextExtractor;

	private final int tocLevel;

	private boolean interLevelText = true;

	public static class Paragraph {

		private Paragraph parent;

		private String title;

		private int level;

		private int startPageNumber;

		private int endPageNumber;

		private int position = 0;

		private List<Paragraph> children = new ArrayList<>();

		public Paragraph(Paragraph parent, String title, int level, int startPageNumber, int endPageNumber) {
			this.parent = parent;
			this.title = title;
			this.level = level;
			this.startPageNumber = startPageNumber;
			this.endPageNumber = endPageNumber;
		}

		public int getPosition() {
			return position;
		}

		public void setPosition(int topPosition) {
			this.position = topPosition;
		}

		public Paragraph getParent() {
			return parent;
		}

		public void setParent(Paragraph parent) {
			this.parent = parent;
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public int getLevel() {
			return level;
		}

		public void setLevel(int level) {
			this.level = level;
		}

		public int getStartPageNumber() {
			return startPageNumber;
		}

		public void setStartPageNumber(int startPageNumber) {
			this.startPageNumber = startPageNumber;
		}

		public int getEndPageNumber() {
			return endPageNumber;
		}

		public void setEndPageNumber(int endPageNumber) {
			this.endPageNumber = endPageNumber;
		}

		public List<Paragraph> getChildren() {
			return children;
		}

		public void setChildren(List<Paragraph> children) {
			this.children = children;
		}

		@Override
		public String toString() {
			String indent = (level < 0) ? "" : new String(new char[level * 2]).replace('\0', ' ');

			return indent + " " + level + ") " + title + " [" + startPageNumber
					+ "," + endPageNumber + "], children = " + children.size();
		}
	}

	public PdfLoader(Resource pdfResource, int tocLevel) {
		this(pdfResource, tocLevel, null);
	}

	public PdfLoader(Resource pdfResource, int tocLevel, Rectangle pageDimensions) {
		this.paragraphTextExtractor = new ParagraphTextExtractor(pdfResource, pageDimensions);
		this.tocLevel = tocLevel;
	}

	public void setInterLevelText(boolean generateInterLevelText) {
		this.interLevelText = generateInterLevelText;
	}

	public boolean isInterLevelText() {
		return interLevelText;
	}

	public ParagraphTextExtractor getParagraphTextExtractor() {
		return paragraphTextExtractor;
	}

	// @Override
	public List<Document> load2() {

		var paragraphs = this.paragraphTextExtractor.getParagraphsByLevel(
				this.paragraphTextExtractor.getRootParagraph(), this.tocLevel,
				this.interLevelText);

		List<Document> documents = new ArrayList<>(paragraphs.size());

		for (Paragraph paragraph : paragraphs) {
			String docText = (this.paragraphTextExtractor.getPageDimensions() != null)
					? this.paragraphTextExtractor.extractTextByRegion(this.paragraphTextExtractor.getPageDimensions(),
							paragraph.getStartPageNumber(),
							paragraph.getEndPageNumber())
					: this.paragraphTextExtractor.extractText(paragraph.getStartPageNumber(),
							paragraph.getEndPageNumber());
			Document document = new Document(docText);
			document.getMetadata().put("title", paragraph.getTitle());
			document.getMetadata().put("level", paragraph.getLevel());
			document.getMetadata().put("startPage", paragraph.getStartPageNumber());
			document.getMetadata().put("endPage", paragraph.getEndPageNumber());
			documents.add(document);
		}

		return documents;
	}

	public List<Document> load() {

		var paragraphs = this.paragraphTextExtractor.flatten();

		List<Document> documents = new ArrayList<>(paragraphs.size());

		if (!CollectionUtils.isEmpty(paragraphs)) {
			Iterator<Paragraph> itr = paragraphs.iterator();

			var current = itr.next();

			if (!itr.hasNext()) {
				documents.add(toDocument(current, current, this.paragraphTextExtractor.getPageDimensions()));
			}
			else {
				while (itr.hasNext()) {
					var next = itr.next();
					documents.add(toDocument(current, next, this.paragraphTextExtractor.getPageDimensions()));
					current = next;
				}
			}
		}

		return documents;
	}

	private Document toDocument(Paragraph from, Paragraph to, Rectangle pageDimensions) {
		String docText = this.paragraphTextExtractor.extractText2(from, to, 40, 15);
		Document document = new Document(docText);
		document.getMetadata().put("title", from.getTitle());
		document.getMetadata().put("level", from.getLevel());
		document.getMetadata().put("startPage", from.getStartPageNumber());
		document.getMetadata().put("endPage", to.getStartPageNumber());
		return document;
	}

	@Override
	public List<Document> load(TextSplitter textSplitter) {
		return load();
	}

	public static class ParagraphTextExtractor {

		private final Paragraph rootParagraph;

		private final PDDocument document;

		private final Rectangle pageDimensions;

		public Paragraph getRootParagraph() {
			return rootParagraph;
		}

		public PDDocument getDocument() {
			return document;
		}

		public Rectangle getPageDimensions() {
			return this.pageDimensions;
		}

		public ParagraphTextExtractor(Resource pdfResource, Rectangle pageDimensions) {
			try {
				PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(pdfResource.getInputStream()));
				pdfParser.parse();
				this.document = new PDDocument(pdfParser.getDocument());

				this.pageDimensions = (pageDimensions != null) ? pageDimensions : determinePageDimensions();

				this.rootParagraph = generateParagraphs(
						new Paragraph(null, "root", -1, 1, this.document.getNumberOfPages()),
						this.document.getDocumentCatalog().getDocumentOutline(),
						0);

				printParagraph(rootParagraph);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}

		}

		public List<Paragraph> flatten() {
			List<Paragraph> paragraphs = new ArrayList<>();
			for (var child : this.rootParagraph.getChildren()) {
				flatten(child, paragraphs);
			}
			return paragraphs;
		}

		private void flatten(Paragraph current, List<Paragraph> paragraphs) {
			paragraphs.add(current);
			for (var child : current.getChildren()) {
				flatten(child, paragraphs);
			}
		}

		private Rectangle determinePageDimensions() {

			var numberOfPages = this.document.getNumberOfPages();

			Assert.isTrue(numberOfPages > 0, "At least one page is expected in the document");

			var rect1 = this.document.getPages().get(new Random().nextInt(numberOfPages)).getMediaBox();
			var rect2 = this.document.getPages().get(new Random().nextInt(numberOfPages)).getMediaBox();
			Assert.isTrue(rect1.getLowerLeftX() == rect2.getLowerLeftX()
					&& rect1.getLowerLeftY() == rect2.getLowerLeftY()
					&& rect1.getWidth() == rect2.getWidth()
					&& rect1.getHeight() == rect2.getHeight(), "All pages should have same size!");

			return new Rectangle((int) rect1.getLowerLeftX(), (int) rect1.getLowerLeftY(), (int) rect1.getWidth(),
					(int) rect1.getHeight());
		}

		public void printParagraph(Paragraph paragraph) {
			System.out.println(paragraph);
			for (Paragraph childParagraph : paragraph.getChildren()) {
				printParagraph(childParagraph);
			}
		}

		void print(String title, PDPageXYZDestination dest) {
			System.out.println(
					"DEST:" + title + " - " + dest.getLeft() + ":" + dest.getTop() + ":"
							+ dest.getZoom());
		}

		public Paragraph generateParagraphs(Paragraph parentParagraph, PDOutlineNode bookmark,
				Integer level) throws IOException {

			PDOutlineItem current = bookmark.getFirstChild();
			while (current != null) {

				int pageNumber = getPageNumber(current);
				var nextSiblingNumber = getPageNumber(current.getNextSibling());
				if (nextSiblingNumber < 0) {
					nextSiblingNumber = getPageNumber(current.getLastChild());
				}

				var currentParagraph = new Paragraph(parentParagraph, current.getTitle(), level, pageNumber,
						nextSiblingNumber);
				parentParagraph.getChildren().add(currentParagraph);

				if (current.getDestination() instanceof PDPageXYZDestination) {
					currentParagraph.setPosition(((PDPageXYZDestination) current.getDestination()).getTop());
				}
				else {
					currentParagraph.setPosition((int) this.pageDimensions.getHeight());
				}
				// print(current.getTitle(), (PDPageXYZDestination) dest);

				generateParagraphs(currentParagraph, current, level + 1); // reverse loop on ToC
				current = current.getNextSibling();
			}
			// fix some page ending numbers (e.g. replace -1 with the start page number for the follow up paragraph)
			if (parentParagraph.getParent() == null) {
				fixEndPageNumber(parentParagraph, 0);
			}
			return parentParagraph;
		}

		private void fixEndPageNumber(Paragraph paragraph, int parentEndPageNumber) {
			if (paragraph.getEndPageNumber() < 0) {
				paragraph.setEndPageNumber(parentEndPageNumber);
			}
			for (Paragraph childParagraph : paragraph.getChildren()) {
				fixEndPageNumber(childParagraph, paragraph.getEndPageNumber());
			}
		}

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

			if (paragraph.getLevel() < level) {
				if (!CollectionUtils.isEmpty(paragraph.getChildren())) {

					if (interLevelText) {
						var interLevelParagraph = new Paragraph(paragraph.getParent(), paragraph.getTitle(),
								paragraph.getLevel(),
								paragraph.getStartPageNumber(), paragraph.getChildren().get(0).getStartPageNumber());
						resultList.add(interLevelParagraph);
					}

					for (Paragraph child : paragraph.getChildren()) {
						resultList.addAll(getParagraphsByLevel(child, level, interLevelText));
					}
				}
			}
			else if (paragraph.getLevel() == level) {
				resultList.add(paragraph);
			}

			return resultList;
		}

		public String extractTextByRegion(Rectangle inPageRectangle, int startPage, int endPage) {

			try {
				PDFLayoutTextStripperByArea pdfTextStripper = new PDFLayoutTextStripperByArea();
				pdfTextStripper.setSortByPosition(true);
				pdfTextStripper.addRegion("regionName", inPageRectangle);

				StringBuilder sb = new StringBuilder();
				for (int pageNumber = startPage - 1; pageNumber < endPage; pageNumber++) {
					var page = this.document.getPage(pageNumber);
					pdfTextStripper.extractRegions(page);
					sb.append(pdfTextStripper.getTextForRegion("regionName"));
				}

				return sb.toString();
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public String extractText(int startPage, int endPage) {

			try {
				PDFLayoutTextStripper pdfTextStripper = new PDFLayoutTextStripper();
				pdfTextStripper.setStartPage(startPage);
				pdfTextStripper.setEndPage(endPage);

				return pdfTextStripper.getText(this.document);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}


		public String extractText2(Paragraph fromParagraph, Paragraph toParagraph, int topMargin, int bottomMargin) {

			int startPage = fromParagraph.getStartPageNumber() - 1;
			int endPage = toParagraph.getStartPageNumber() - 1;

			try {

				StringBuilder sb = new StringBuilder();

				for (int pageNumber = startPage; pageNumber <= endPage; pageNumber++) {

					var page = this.document.getPage(pageNumber);
					var pdfTextStripper = new PDFLayoutTextStripperByArea();
					pdfTextStripper.setSortByPosition(true);

					int x0 = (int) page.getMediaBox().getLowerLeftX();
					int xW = (int) page.getMediaBox().getWidth();
					int y0 = (int) page.getMediaBox().getLowerLeftY() + topMargin;
					int yW = (int) page.getMediaBox().getHeight() - (topMargin + bottomMargin);

					if (pageNumber == startPage) {
						y0 = (int) page.getMediaBox().getHeight() - fromParagraph.getPosition();
						yW = fromParagraph.getPosition() - bottomMargin;
						// yW = fromParagraph.getTopPosition();
					}
					if (pageNumber == endPage) {
						yW = Math.abs(yW - toParagraph.getPosition());
					}

					pdfTextStripper.addRegion("pdfPageRegion", new Rectangle(x0, y0, xW, yW));
					pdfTextStripper.extractRegions(page);
					var text = pdfTextStripper.getTextForRegion("pdfPageRegion");
					if (StringUtils.hasText(text)) {
						System.out.println(text);
						sb.append(text);
					}
					pdfTextStripper.removeRegion("pdfPageRegion");

				}
				if (StringUtils.hasText(sb.toString())) {
					System.out.println("----------------------------------------------------------");
				}
				return sb.toString();
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public void pageDimensions() throws IOException {

			for (int i = 0; i < this.document.getNumberOfPages(); i++) {
				System.out.println(this.document.getPage(i).getMediaBox());
			}
		}

	}

	private static void writeToFile(String fileName, List<Document> docs) throws IOException {
		var writer = new FileWriter(fileName, false);

		for (Document doc : docs) {
			writer.write(doc.getText());
		}

		writer.close();
	}

	public static void main(String[] args) throws IOException {
		Resource pdfResource = new DefaultResourceLoader().getResource(
				"file:spring-ai-core/src/test/resources/spring-framework.pdf");

		PdfLoader pdfLoader = new PdfLoader(pdfResource, 1);
		// pdfLoader.getMyExtractor().pageDimensions();//[0.0,0.0,595.28,841.89]

		// pdfLoader.setRectangle(new Rectangle(20, 25, 595 - 20, 841 - 44));
		var docs = pdfLoader.load();

		writeToFile("spring-ai-core/target/docs1.txt", docs);
		System.out.println(docs.size());

	}
}
