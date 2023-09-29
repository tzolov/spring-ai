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

import java.io.IOException;

import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 *
 * @author Christian Tzolov
 */
public class Main4 {

	public static void main(String[] args) throws IOException {
		String resourceName = "file:spring-ai-core/src/test/resources/mpb.pdf";

		Resource pdfResource = new DefaultResourceLoader().getResource(resourceName);

		PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(pdfResource.getInputStream()));
		pdfParser.parse();
		PDDocument document = new PDDocument(pdfParser.getDocument());

		PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();

		PDOutlineItem item = outline.getFirstChild();

		PDOutlineItem nextItem = item.getNextSibling();

		var pageNumber = getPageNumber(document, nextItem);

	}

	private static int getPageNumber(PDDocument document, PDOutlineItem current) throws IOException {
		if (current == null) {
			return -1;
		}
		PDPage currentPage = current.findDestinationPage(document);
		PDPageTree pages = document.getDocumentCatalog().getPages();
		for (int i = 0; i < pages.getCount(); i++) {
			var page = pages.get(i);
			if (page.equals(currentPage)) {
				return i + 1;
			}
		}
		return -1;
	}

}
