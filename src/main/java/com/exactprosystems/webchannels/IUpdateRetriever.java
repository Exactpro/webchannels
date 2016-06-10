package com.exactprosystems.webchannels;

import com.exactprosystems.webchannels.enums.ServerStatus;
import com.exactprosystems.webchannels.exceptions.IncorrectListenerTypeException;

/**
 * 
 * Basic interface to define updates retriever which
 * contain collection of {@link IUpdateRequestListener}
 * 
 * @author dmitry.zavodchikov
 *
 */
public interface IUpdateRetriever {
	
	void registerUpdateRequest(IUpdateRequestListener listener) throws IncorrectListenerTypeException;
	
	void unregisterUpdateRequest(IUpdateRequestListener listener) throws IncorrectListenerTypeException;
	
	void synchronizeUpdateRequest(IUpdateRequestListener listener) throws IncorrectListenerTypeException;
	
	@Deprecated
	ServerStatus getStatus();
	
	void destroy();
	
}
