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

import com.exactprosystems.webchannels.messages.PollingRequest;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.InputStream;
import java.io.Reader;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.zip.InflaterInputStream;

public class HttpChannelProcessor extends AbstractChannelProcessor{
	
	public HttpChannelProcessor(AbstractHandlerFactory handlerFactory,
								AbstractMessageFactory messageFactory,
								ChannelSettings settings, Executor executorService) {
		
		super(handlerFactory, messageFactory, settings,
				new HttpChannelFactory(messageFactory, handlerFactory), executorService);
		
	}
	
	public void processAsyncContext(AsyncContext context) {
		
		String channelId = context.getRequest().getParameter("channelId");
		
		if (channelId == null) {
			throw new RuntimeException("ChannelId is not defined");
		}
		
		AbstractChannel channel = channels.get(channelId);

		if (channel == null) {
			HttpSession httpSession = ((HttpServletRequest) context.getRequest()).getSession(true);
			ChannelSettings settings = getSettings(httpSession, context.getRequest());
			channel = channelFactory.createChannel(channelId, settings, executor, httpSession);
			AbstractChannel prev = channels.putIfAbsent(channelId, channel);
			if (prev != null) {
				channel = prev;
			} else {
				channel.initHandler();
				SessionContrtoller.getInstance().registerChannel(channel, httpSession);
			}
		}
		
		List<WithSeqnumWrapper> list = null;
		
		try {
			if (channel.getChannelSettings().isCompressionEnabled()) {
				try (InputStream input = context.getRequest().getInputStream();
						InputStream gzipInput = new InflaterInputStream(input)) {
					list = messageFactory.decodeMessage(gzipInput);
				}
			} else {
				try (Reader input = context.getRequest().getReader()) {
					list = messageFactory.decodeMessage(input);
				}
			}
		} catch (Exception e) {
			logger.error("Exception while decoding input messages", e);
			Throwable[] suppressed = e.getSuppressed();
			for (Throwable throwable : suppressed) {
				logger.error("Suppressed exception during decoding in channel " + channel, throwable);
			}
			context.complete();
		}
		
		
		logger.trace("Process AsyncContext {} for {}", context, channel);
		
		if (list != null) {
			if (list.size() == 1 && list.get(0).getMessage() instanceof PollingRequest) {
				channel.bind(context);
			} else {
				for (WithSeqnumWrapper wrapper : list) {
					logger.trace("Processor {} onMessage() {} for {}", this, wrapper, channel);
					channel.handleRequest(wrapper);
				}
				context.complete();
			}
		
		}
		
	}
	
	public void processAsyncContextClose(AsyncEvent event) {
		
		try {
			
			String channelId = event.getSuppliedRequest().getParameter("channelId");
			
			if (channelId == null) {
				throw new RuntimeException("ChannelId is not defined");
			}
			
			AbstractChannel channel = channels.get(channelId);
			
			if (channel != null) {
				logger.trace("Close AsyncContext {} for {}", event.getAsyncContext(), channel);
				channel.unbind(event.getAsyncContext());
			}
			
		} catch (Exception e) {
			
			logger.error("Exception while processing asyncContext", e);
			
		}
		
	}
	
	@Override
	public String toString() {
		return "HttpChannelProcessor[]";
	}

}
