package org.springframework.ai.reader;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

/**
 * @author Craig Walls
 * @author Christian Tzolov
 */
public class TextReader implements DocumentReader<String> {

	public static final String CHARSET_METADATA = "charset";

	public static final String SOURCE_METADATA = "source";

	/**
	 * @return Character set to be used when loading data from the
	 */
	private Charset charset = StandardCharsets.UTF_8;

	private Map<String, Object> customMetadata = new HashMap<>();

	public void setCharset(Charset charset) {
		Objects.requireNonNull(charset, "The charset must not be null");
		this.charset = charset;
	}

	public Charset getCharset() {
		return this.charset;
	}

	/**
	 * Metadata associated with all documents created by the loader.
	 * @return Metadata to be assigned to the output Documents.
	 */
	public Map<String, Object> getCustomMetadata() {
		return this.customMetadata;
	}

	@Override
	public List<Document> apply(String resourceUri) {
		Objects.requireNonNull(resourceUri, "The Spring Resource Uri must not be null");
		var resource = new DefaultResourceLoader().getResource(resourceUri);
		try {

			String document = StreamUtils.copyToString(resource.getInputStream(), this.charset);

			// Inject source information as a metadata.
			this.customMetadata.put(CHARSET_METADATA, this.charset.name());
			this.customMetadata.put(SOURCE_METADATA, resource.getFilename());

			return List.of(new Document(document, this.customMetadata));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}