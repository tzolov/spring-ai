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
package org.springframework.ai.chat.model.observation;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class ChatModelObservationDocumentation implements ObservationDocumentation {

	public enum LowCardinalityKeyNames implements KeyName {

		/**
		 * Name of ChatModel request Model or {@value KeyValue#NONE_VALUE} if the request
		 * could not be created.
		 */
		MODEL {
			@Override
			public String asString() {
				return "model";
			}

		},

		/**
		 * URI template used for ChatModel request, or {@value KeyValue#NONE_VALUE} if
		 * none was provided. Only the path part of the URI is considered.
		 */
		URI {
			@Override
			public String asString() {
				return "uri";
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
			// TODO Auto-generated method stub
			throw new UnsupportedOperationException("Unimplemented method 'asString'");
		}

	}

}
