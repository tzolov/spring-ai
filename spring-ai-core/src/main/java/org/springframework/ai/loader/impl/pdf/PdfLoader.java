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
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import io.github.jonathanlink.PDFLayoutTextStripper;
import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import org.springframework.ai.document.Document;
import org.springframework.ai.loader.Loader;
import org.springframework.ai.splitter.TextSplitter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;

/**
 *
 * @author Christian Tzolov
 */
public class PdfLoader implements Loader {

	private MyExtractor myExtractor;

	private Rectangle rectangle;

	private final int tocLevel;

	private boolean interLevelText = true;

	public static class Paragraph {

		private Paragraph parent;

		private String title;

		private int level;

		private int startPageNumber;

		private int endPageNumber;

		private List<Paragraph> children = new ArrayList<>();

		public Paragraph(Paragraph parent, String title, int level, int startPageNumber, int endPageNumber) {
			this.parent = parent;
			this.title = title;
			this.level = level;
			this.startPageNumber = startPageNumber;
			this.endPageNumber = endPageNumber;
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
		this.myExtractor = new MyExtractor(pdfResource);
		this.tocLevel = tocLevel;
	}

	public void setRectangle(Rectangle rectangle) {
		this.rectangle = rectangle;
	}

	public Rectangle getRectangle() {
		return rectangle;
	}

	public void setInterLevelText(boolean generateInterLevelText) {
		this.interLevelText = generateInterLevelText;
	}

	public boolean isInterLevelText() {
		return interLevelText;
	}

	@Override
	public List<Document> load() {

		var paragraphs = this.myExtractor.getParagraphsByLevel(this.myExtractor.getRootParagraph(), this.tocLevel,
				this.interLevelText);

		List<Document> documents = new ArrayList<>(paragraphs.size());

		for (Paragraph paragraph : paragraphs) {
			String docText = (this.rectangle != null)
					? this.myExtractor.extractTextByRegion(this.rectangle, paragraph.getStartPageNumber(),
							paragraph.getEndPageNumber())
					: this.myExtractor.extractText(paragraph.getStartPageNumber(), paragraph.getEndPageNumber());
			Document document = new Document(docText);
			document.getMetadata().put("title", paragraph.getTitle());
			document.getMetadata().put("level", paragraph.getLevel());
			document.getMetadata().put("startPage", paragraph.getStartPageNumber());
			document.getMetadata().put("endPage", paragraph.getEndPageNumber());
			documents.add(document);
		}

		return documents;
	}

	@Override
	public List<Document> load(TextSplitter textSplitter) {
		return load();
	}

	public static class MyExtractor {

		private final Paragraph rootParagraph;

		private final PDDocument document;

		public Paragraph getRootParagraph() {
			return rootParagraph;
		}

		public PDDocument getDocument() {
			return document;
		}

		public MyExtractor(Resource pdfResource) {
			try {
				PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(pdfResource.getInputStream()));
				pdfParser.parse();
				this.document = new PDDocument(pdfParser.getDocument());

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

		public void printParagraph(Paragraph paragraph) {
			System.out.println(paragraph);
			for (Paragraph childParagraph : paragraph.getChildren()) {
				printParagraph(childParagraph);
			}
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
					pdfTextStripper.extractRegions(this.document.getPage(pageNumber));
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

	}

	public static void main(String[] args) throws IOException {
		Resource pdfResource = new DefaultResourceLoader().getResource(
				"file:spring-ai-core/src/test/resources/spring-framework.pdf");

		PdfLoader pdfLoader = new PdfLoader(pdfResource, 1);

		// pdfLoader.setRectangle(new Rectangle(51, 51, 531, 71));
		var docs = pdfLoader.load();

		System.out.println(docs.size());

	}
}
