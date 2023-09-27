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
import java.io.IOException;
import java.util.Random;

import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 *
 * @author Christian Tzolov
 */
public class Main {

	public static void main(String[] args) throws IOException {

		String resourceName = "file:spring-ai-core/src/test/resources/spring-framework.pdf";

		Resource pdfResource = new DefaultResourceLoader().getResource(resourceName);

		PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(pdfResource.getInputStream()));
		pdfParser.parse();
		PDDocument document = new PDDocument(pdfParser.getDocument());

		var pageDimensions = determinePageDimensions(document);

		PDFLayoutTextStripperByArea pdfTextStripper = new PDFLayoutTextStripperByArea();
		pdfTextStripper.setSortByPosition(true);

		// int pageNumber = 3 - 1;
		int pageNumber = 6 - 1;

		int y0 = 0;
		int yW = (int) pageDimensions.getHeight() - 250;

		pdfTextStripper.addRegion("startPageRegionName",
				new Rectangle(0, y0, (int) pageDimensions.getWidth(), yW));

		pdfTextStripper.extractRegions(document.getPage(pageNumber));
		var text = pdfTextStripper.getTextForRegion("startPageRegionName");
		System.out.println(text);

		//
		y0 = (int) pageDimensions.getHeight() - 250;
		yW = 250;

		pdfTextStripper.addRegion("endPageRegionName",
				new Rectangle(0, y0, (int) pageDimensions.getWidth(), yW));

		pdfTextStripper.extractRegions(document.getPage(pageNumber));
		text = pdfTextStripper.getTextForRegion("endPageRegionName");
		System.out.println(text);

		//
		y0 = (int) pageDimensions.getY();
		yW = (int) pageDimensions.getHeight();
		var x0 = (int) pageDimensions.getX();
		var xW = (int) pageDimensions.getWidth();

		pdfTextStripper.addRegion("pageDimensions1",
				new Rectangle(x0, y0, xW, yW));

		pdfTextStripper.extractRegions(document.getPage(pageNumber));
		text = pdfTextStripper.getTextForRegion("pageDimensions1");
		System.out.println(text);

	}

	private static Rectangle determinePageDimensions(PDDocument document) {

		var numberOfPages = document.getNumberOfPages();

		Assert.isTrue(numberOfPages > 0, "At least one page is expected in the document");

		var rect1 = document.getPages().get(new Random().nextInt(numberOfPages)).getMediaBox();
		var rect2 = document.getPages().get(new Random().nextInt(numberOfPages)).getMediaBox();
		Assert.isTrue(rect1.getLowerLeftX() == rect2.getLowerLeftX()
				&& rect1.getLowerLeftY() == rect2.getLowerLeftY()
				&& rect1.getWidth() == rect2.getWidth()
				&& rect1.getHeight() == rect2.getHeight(), "All pages should have same size!");

		return new Rectangle((int) rect1.getLowerLeftX(), (int) rect1.getLowerLeftY(), (int) rect1.getWidth(),
				(int) rect1.getHeight());
	}

	// 1.3. Design Philosophy - p. 3 topPos: 250
}
