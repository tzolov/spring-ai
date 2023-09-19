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

package org.springframework.ai.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.jonathanlink.PDFLayoutTextStripper;
import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.Resource;

/**
 *
 * @author Christian Tzolov
 */
public class PdfLoader implements DocumentReader {

	private Resource pdfResource;

	public PdfLoader(Resource pdfResource) {
		this.pdfResource = pdfResource;
	}

	@Override
	public List<Document> get() {

		List<Document> documents = new ArrayList<>();



		throw new UnsupportedOperationException("Unimplemented method 'load'");
	}

	private String extractPDFContent() throws IOException {
		PDFParser pdfParser = new PDFParser(new RandomAccessBuffer(this.pdfResource.getInputStream()));
		pdfParser.parse();
		PDDocument pdDocument = new PDDocument(pdfParser.getDocument());
		PDFTextStripper pdfTextStripper = new PDFLayoutTextStripper();
		return pdfTextStripper.getText(pdDocument);
	}

}
