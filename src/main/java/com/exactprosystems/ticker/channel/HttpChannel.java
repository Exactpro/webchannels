package com.exactprosystems.ticker.channel;

import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.zip.DeflaterOutputStream;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;

import com.exactprosystems.ticker.enums.ChannelStatus;
import com.exactprosystems.ticker.exceptions.RecoverException;
import com.exactprosystems.ticker.messages.AbstractMessage;
import com.exactprosystems.ticker.messages.CloseChannel;
import com.exactprosystems.ticker.messages.HeartBeat;
import com.exactprosystems.ticker.messages.ResendRequest;
import com.exactprosystems.ticker.messages.TestRequest;
import com.exactprosystems.ticker.util.DateUtils;

public class HttpChannel extends AbstractChannel {
	
	private OutputMessagesBuffer outputMessageQueue;
	
	private InputMessagesBuffer inputMessageQueue;
	
	private SentMessagesBuffer sentMessageQueue;
	
	private long created;
	
	private long closed;
	
	private long lastSendTime;
	
	private long lastReceiveTime;
	
	private boolean awaitHeartbeat;
	
	private AsyncContext pollingContext;
	
	private long inputSeqnum;
	
	private long outputSeqnum;
	
	public HttpChannel(IChannelHandler handler, String id, ChannelSettings settings, 
			AbstactMessageFactory messageFactory, ExecutorService executor) {
				
		super(handler, id, settings, messageFactory, executor);
		
		outputMessageQueue = new OutputMessagesBuffer();
		inputMessageQueue = new InputMessagesBuffer();
		sentMessageQueue = new SentMessagesBuffer(settings.getResendBufferSize());
		inputSeqnum = 0;
		outputSeqnum = 1;
		pollingContext = null;
		lastSendTime = System.currentTimeMillis();
		lastReceiveTime = lastSendTime;
		awaitHeartbeat = false;
		created = 0L;
		closed = 0L;
		logger.info("Create {}", this);
		
	}
	
	@Override
	protected void onCreate() {
		this.created = System.currentTimeMillis();
		this.getHandler().onCreate(this);
	}
	
	@Override
	protected ChannelStats getChannelStats() {
		return new ChannelStats(getID(), getStatus(), created, closed, outputSeqnum, inputSeqnum, lastSendTime, lastReceiveTime);
	}
	
	@Override
	protected void onBind(Object context) {
		
		if (context == null) {
			throw new RuntimeException("AsyncContext is null");
		}
		
		AsyncContext asyncContext = (AsyncContext) context;

		if (this.getChannelSettings().isCompressionEnabled()) {
			((HttpServletResponse) asyncContext.getResponse()).setHeader("Content-Type", "application/octet-stream");
		} else {
			((HttpServletResponse) asyncContext.getResponse()).setHeader("Content-Type", this.getMessageFactory().getContentType());
		}
		
		if (pollingContext != null) {			
			try {
				completeRequest(pollingContext);
			} catch (Exception e) {
				logger.error("Exception while closing context " + pollingContext, e);
				this.getHandler().onException(e);
			} finally {
				logger.trace("Unbind context {} for {}", pollingContext, this);
				pollingContext = null;
			}
		}
		
		this.pollingContext = asyncContext;
		this.setStatus(ChannelStatus.OPENED);
		logger.trace("Bind context {} for {}", asyncContext, this);
		
	}
	
	@Override
	protected void onUnbind(Object context) {

		if (context == null) {
			throw new RuntimeException("AsyncContext is null");
		}
		
		AsyncContext asyncContext = (AsyncContext) context;
		
		if (pollingContext != null && pollingContext == asyncContext) {
			try {
				completeRequest(pollingContext);
			} catch (Exception e) {
				logger.error("Exception while closing context " + pollingContext, e);
				this.getHandler().onException(e);
			} finally {
				logger.trace("Unbind context {} for {}", pollingContext, this);
				this.setStatus(ChannelStatus.WAITING);
				this.pollingContext = null;
			}
		}
		
	}
	
	@Override
	protected void processInputMessage(WithSeqnumWrapper wrapper) {
		
		AbstractMessage message = wrapper.getMessage();
		long seqnum = wrapper.getSeqnum();
		long expectedSeqnum = inputSeqnum + 1;
		
		if (seqnum == expectedSeqnum) {
		
			if (inputMessageQueue.isRecovered()) {
				handleInputMessage(message, seqnum);
			} else {
				inputMessageQueue.add(wrapper);
			}
			
		} else	if (seqnum > expectedSeqnum) {
				
			logger.error("Missed messages between {} and {} on {}", inputSeqnum, seqnum, this);
			this.sendMessage(new ResendRequest("Resend", inputSeqnum, seqnum));
			
			inputMessageQueue.recover(expectedSeqnum, seqnum);
			inputMessageQueue.add(wrapper);
		
		} else if (seqnum < expectedSeqnum) {
			
			if (inputMessageQueue.isRecovered()) {
				
				logger.error("Unexpected message with seqnum {} (expected seqnum {}) on {}", seqnum, expectedSeqnum, this);
				
			} else {
				
				logger.debug("Message with seqnum {} recovered on {}", seqnum, this);
				inputMessageQueue.add(wrapper);
				
				List<WithSeqnumWrapper> messages = inputMessageQueue.tryRecover();
				for (WithSeqnumWrapper restored : messages) {
					handleInputMessage(restored.getMessage(), restored.getSeqnum());
				}
				
				if (inputMessageQueue.isRecovered()) {
					logger.info("Recovered {}", this);
				}
				
			}
			
		}
		
		if (seqnum > inputSeqnum) {
			inputSeqnum = seqnum;
		}
		
	}
	
	private void handleInputMessage(AbstractMessage message, long seqnum) {
			
		lastReceiveTime = System.currentTimeMillis();
		
		if (message.isAdmin() == false) {
        	// Business messages process by handler
			AbstractMessage response = this.getHandler().onReceive(message, seqnum);
			if (response != null) {
				this.sendMessage(response);
			}
        } else {
        	// Handle admin messages
    		if (message instanceof TestRequest) {
    			logger.warn("TestRequest received on {}", this);
    			this.sendMessage(new HeartBeat());
    		} else if (message instanceof HeartBeat) {
    			logger.debug("HeartBeat received on {}", this);
    			if (awaitHeartbeat) {
    				awaitHeartbeat = false;
    			}
    		} else if (message instanceof ResendRequest) {
    			logger.error("ResendRequest {} received on {}", message, this);
                ResendRequest resendRequest = (ResendRequest) message;
                try {
	                List<WithSeqnumWrapper> messages = sentMessageQueue.get(resendRequest.getFrom(), resendRequest.getTo());
	                for (WithSeqnumWrapper old : messages) {
	                	logger.debug("Resend message {} on {}", old, this);
	                    outputMessageQueue.offer(new WithSeqnumWrapper(old.getSeqnum(), old.getMessage()));
	                }
                } catch (RecoverException e) {
                	logger.error(e.getMessage(), e);
                	onClose();
                }
            } else if (message instanceof CloseChannel) {
            	logger.debug("CloseChannel received on {}", this);
            	onClose();
            } else {
            	throw new RuntimeException("Unsupported message " + message);
            }
        }
		
	}
	
	@Override
	protected void processOutputMessage(AbstractMessage message) {
		
		WithSeqnumWrapper wrapper = new WithSeqnumWrapper(outputSeqnum++, message);
		outputMessageQueue.offer(wrapper);
		sentMessageQueue.add(wrapper);
		this.getHandler().onSend(wrapper.getMessage(), wrapper.getSeqnum());
		
	}
	
	@Override
	protected void onPoll() {
		
		if (this.getStatus() == ChannelStatus.CLOSED) {
			logger.trace("Nothing to processing. {} already destoryed.", this);
			return;
		}
		
		long currentTime = System.currentTimeMillis();
		
		if (currentTime - lastReceiveTime > this.getChannelSettings().getDisconnectTimeout()) {
			logger.debug("No activity from client on {}", this);
			onClose();
			return;
		}
		
		if ((currentTime - lastReceiveTime > this.getChannelSettings().getHeartBeatInterval() * 2) && !awaitHeartbeat) {
			logger.warn("Sending testRequest for {}", this);
			this.sendMessage(new TestRequest());
			awaitHeartbeat = true;
		}
		
		if (pollingContext != null) {
			
			if (!outputMessageQueue.isEmpty()) {
					
				List<WithSeqnumWrapper> messages = null;
				
				long sendStart = System.currentTimeMillis();
				
				try {
				
					logger.trace("Start sending messages for {}", this);
					
					messages = outputMessageQueue.poll(this.getChannelSettings().getMaxCountToSend());
					
					if (getChannelSettings().isCompressionEnabled()) {
						try (OutputStream output = pollingContext.getResponse().getOutputStream();
								OutputStream gzipOutput = new DeflaterOutputStream(output)) {
							this.getMessageFactory().encodeMessage(messages, gzipOutput);
						}
					} else {
						try (Writer output = pollingContext.getResponse().getWriter()) {
							this.getMessageFactory().encodeMessage(messages, output);
						}
					}
					
					lastSendTime = currentTime;
					
					logger.debug("Sent {} messages through {}", messages.size(), this);
					
					long sendDuration = System.currentTimeMillis() - sendStart;
					
					if (sendDuration > 200L) {
						logger.warn("Send messages via {} took {} ms", this, sendDuration);
					} else {
						logger.trace("Send messages via {} took {} ms", this, sendDuration);
					}
					
				} catch (Exception e) {
					
					if (messages != null) {
						outputMessageQueue.offerFirst(messages);
					}
					
					logger.error("Exception while processing queue for " + this, e);
					this.getHandler().onException(e);
					
					Throwable[] suppressed = e.getSuppressed();
					for (Throwable throwable : suppressed) {
						logger.error("Suppressed exception during encoding in " + this, throwable);
						this.getHandler().onException(throwable);
					}
					
				} finally {
					
					try {
						completeRequest(pollingContext);
					} catch (Exception e) {
						logger.error("Exception while closing context " + pollingContext, e);
						this.getHandler().onException(e);
					} finally {
						logger.trace("Unbind context {} for {}", pollingContext, this);
						this.setStatus(ChannelStatus.WAITING);
						this.pollingContext = null;
					}
					
				}
			
			} else {
				
				if (logger.isTraceEnabled()) {
					logger.trace("Last send: {} for {}", DateUtils.formatTime(lastSendTime), this);
				}

				if (currentTime - lastSendTime > this.getChannelSettings().getHeartBeatInterval()) {
					logger.debug("Invoke onIdle for {}", this);
					this.sendMessage(new HeartBeat());
					this.getHandler().onIdle();					
				} else {
					logger.trace("Wait HeartBeatInterval for {}", this);
				}
				
			}
			
		} else {
				
			if (logger.isTraceEnabled()) {
				logger.trace("Last send: {} for {} ", DateUtils.formatTime(lastSendTime), this);
			}
			
			if (currentTime - lastSendTime > this.getChannelSettings().getDisconnectTimeout() ) {
				logger.debug("Server not send data too long on {}", this);
				onClose();
			} else {
				logger.trace("Wait DisconnectTimeout - {}", this);
			}
			
		}
		
	}

	@Override
	protected void onClose() {
		
		logger.info("Close {}", this);
		
		this.closed = System.currentTimeMillis();
		this.outputMessageQueue.clear();
		this.sentMessageQueue.clear();
		this.inputMessageQueue.clear();
		this.setStatus(ChannelStatus.CLOSED);
		this.getHandler().onClose();
		
		if (pollingContext != null) {
			try {
				completeRequest(pollingContext);	
			} catch (Exception e) {
				logger.error("Exception while closing context " + pollingContext, e);
				this.getHandler().onException(e);
			} finally {
				logger.trace("Unbind context {} for {}", pollingContext, this);
				pollingContext = null;
			}
		}
		
	}
	
	private void completeRequest(AsyncContext asyncContext) {
		asyncContext.complete();
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("HttpChannel[channelId=");
		builder.append(getID());
		builder.append(",status=");
		builder.append(getStatus());
		builder.append("]");
		return builder.toString();
	}
	
}
