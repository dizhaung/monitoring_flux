package com.octo.monitoring.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Handler to read and process messages.
 *
 * @author <a href="mailto:cedrick.lunven@gmail.com">Cedrick LUNVEN</a>
 */
public class MonitoringMessageProcessor implements Processor {

	/** {@inheritDoc} */
	public void process(Exchange exchange) throws Exception {
		System.out.println("Message capture.");
	}

}
