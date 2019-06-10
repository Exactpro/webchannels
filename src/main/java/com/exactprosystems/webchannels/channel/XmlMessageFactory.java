/*
 * *****************************************************************************
 *  Copyright 2009-2018 Exactpro (Exactpro Systems Limited)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ****************************************************************************
 */

package com.exactprosystems.webchannels.channel;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.List;

import com.exactprosystems.webchannels.exceptions.DecodingException;
import com.exactprosystems.webchannels.exceptions.EncodingException;

public class XmlMessageFactory extends AbstractMessageFactory {

	@Override
	public List<WithSeqnumWrapper> decodeMessage(InputStream stream) throws DecodingException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OutputStream encodeMessage(List<WithSeqnumWrapper> message, OutputStream stream) throws EncodingException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getContentType() {
		// TODO Auto-generated method stub
		return "";
	}

	@Override
	public Writer encodeMessage(List<WithSeqnumWrapper> message, Writer writer) throws EncodingException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<WithSeqnumWrapper> decodeMessage(Reader reader) throws DecodingException {
		// TODO Auto-generated method stub
		return null;
	}

}
