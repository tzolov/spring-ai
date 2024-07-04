/*
* Copyright 2024 - 2024 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springframework.ai.vectorstore.observation;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */
public enum VectorStoreObservationDocumentation implements ObservationDocumentation {

	/**
	 * AI Chat Model observations for clients.
	 */
	AI_VECTOR_STORE {
		@Override
		public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
			return DefaultVectorStoreObservationConvention.class;
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return LowCardinalityKeyNames.values();
		}

		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return new KeyName[] {};
		}

	};

	public enum LowCardinalityKeyNames implements KeyName {

		/**
		 * Dimensions used for EmbeddingModel request, or {@value KeyValue#NONE_VALUE} if
		 * none was provided.
		 */
		DIMENSIONS {
			@Override
			public String asString() {
				return "dimensions";
			}
		},

		/**
		 * ChatModel response raw status code, or {@code "IO_ERROR"} in case of
		 * {@code IOException}, or {@code "CLIENT_ERROR"} if no response was received.
		 */
		STATUS {
			@Override
			public String asString() {
				return "status";
			}
		},

		/**
		 * Client name derived from the request URI host.
		 */
		CLIENT_NAME {
			@Override
			public String asString() {
				return "client.name";
			}
		},

		/**
		 * Name of the exception thrown during the chat model request, or
		 * {@value KeyValue#NONE_VALUE} if no exception happened.
		 */
		EXCEPTION {
			@Override
			public String asString() {
				return "exception";
			}
		},

	}

	public enum HighCardinalityKeyNames implements KeyName {

		;

		@Override
		public String asString() {
			throw new UnsupportedOperationException("Unimplemented method 'asString'");
		}

	}

}
