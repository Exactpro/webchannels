package com.exactprosystems.ticker.messages;

@ChannelsMessage
public class HeartBeat extends AdminMessage{

	public HeartBeat() {
		
	}

	@Override
	public String toString() {
		return "HeartBeat[]";
	}

}
