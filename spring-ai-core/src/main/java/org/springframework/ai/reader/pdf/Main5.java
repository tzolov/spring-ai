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

package org.springframework.ai.reader.pdf;

import org.springframework.util.StringUtils;

/**
 *
 * @author Christian Tzolov
 */
public class Main5 {

	static String text = """
			Our platform, and in particular  our Mobility  products,  are  subject  to  differing,  and  sometimes conflicting,  laws, rules,  and  regulatio
			jurisdictions  in  which we  operate. A  large  number  of  proposals  are  before  various national,  regional,  and  local  legislative  bodies  an
			the  United  States  and  in foreign  jurisdictions,  regarding  issues related  to  our  business  model.
				In  the  United  States,  many  state  and  local  laws, rules,  and  regulations  impose legal  restrictions  and  other  requirements  on  oper
			including  licensing,  insurance,  screening,  and  background check  requirements. Outside  of  the  United  States,  certain jurisdictions  have  a
			and regulations  while  other  jurisdictions  have  not adopted  any laws,  rules,  and  regulations  which govern  our  Mobility  business. Further,
			including  Argentina,  Germany,  Italy, Japan,  South  Korea,  and Spain,  six  countries  that  we have  identified  as expansion markets,  have  ad
			regulations  banning  certain  ridesharing  products or  imposing  extensive  operational  restrictions.  This  uncertainty  and  fragmented  regulat
			significant  complexities  for  our business  and  operating  model.""";

	public static void main(String[] args) {
		System.out.println(deleteBottomTextLines(text, 1));
		System.out.println(deleteTopTextLines(text, 1));
	}

	private static String deleteBottomTextLines(String text, int numberOfLines) {
		if (!StringUtils.hasText(text)) {
			return text;
		}

		int lineCount = 0;
		int truncateIndex = text.length();
		int nextTruncateIndex = truncateIndex;
		while (lineCount < numberOfLines && nextTruncateIndex >= 0) {
			nextTruncateIndex = text.lastIndexOf(System.lineSeparator(), truncateIndex - 1);
			truncateIndex = nextTruncateIndex < 0 ? truncateIndex : nextTruncateIndex;
			lineCount++;
		}
		return text.substring(0, truncateIndex);
	}

	private static String deleteTopTextLines(String text, int numberOfLines) {
		if (!StringUtils.hasText(text)) {
			return text;
		}
		int lineCount = 0;

		int truncateIndex = 0;
		int nextTruncateIndex = truncateIndex;
		while (lineCount < numberOfLines && nextTruncateIndex >= 0) {
			nextTruncateIndex = text.indexOf(System.lineSeparator(), truncateIndex + 1);
			truncateIndex = nextTruncateIndex < 0 ? truncateIndex : nextTruncateIndex;
			lineCount++;
		}
		return text.substring(truncateIndex, text.length());
	}

}
