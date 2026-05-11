
package io.github.maidamai.s7connector.api.factory;

import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.impl.serializer.S7SerializerImpl;

/**
 * S7 Serializer factory
 * 
 * @author Thomas Rudin
 *
 */
public class S7SerializerFactory {

	/**
	 * Builds a new serializer with given connector
	 * 
	 * @param connector
	 *            the connector to use
	 * @return a serializer instance
	 */
	public static S7Serializer buildSerializer(final S7Connector connector) {
		return new S7SerializerImpl(connector);
	}

}
