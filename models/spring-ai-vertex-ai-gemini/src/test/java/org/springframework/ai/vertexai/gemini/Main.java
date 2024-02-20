/*
 * Copyright 2024-2024 the original author or authors.
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

package org.springframework.ai.vertexai.gemini;

import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Type;
import com.google.protobuf.util.JsonFormat;

/**
 * @author Christian Tzolov
 */
public class Main {

	public static void main(String[] args) {
		Schema schema = Schema.newBuilder()
			.setType(Type.OBJECT)
			.putProperties("location",
					Schema.newBuilder()
						.setType(Type.STRING)
						.setDescription("The city and state e.g. San Francisco, CA")
						.build())
			.putProperties("lat", Schema.newBuilder().setType(Type.NUMBER).setDescription("The city latitude").build())
			.putProperties("lon", Schema.newBuilder().setType(Type.NUMBER).setDescription("The city longitude").build())
			.putProperties("unit", Schema.newBuilder().setType(Type.STRING).setDescription("Temperature unit").build())
			.addRequired("location")
			.addRequired("lat")
			.addRequired("lon")
			.addRequired("unit")
			.build();

		// System.out.println(schema);

		System.out.println(structToJson(schema));
	}

	private static String structToJson(Schema struct) {
		try {
			String json = JsonFormat.printer().print(struct);
			// Map<String, Object> metadata = new ObjectMapper() .readValue(json,
			// Map.class);
			return json;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
