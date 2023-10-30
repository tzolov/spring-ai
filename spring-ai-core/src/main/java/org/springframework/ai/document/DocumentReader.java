package org.springframework.ai.document;

import java.util.List;
import java.util.function.Function;

public interface DocumentReader<T> extends Function<T, List<Document>> {

}
