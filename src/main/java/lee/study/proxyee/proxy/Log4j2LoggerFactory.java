package lee.study.proxyee.proxy;

import org.apache.logging.log4j.LogManager;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class Log4j2LoggerFactory extends InternalLoggerFactory {

	@Override
	protected InternalLogger newInstance(String name) {
		return new Log4j2Logger(LogManager.getLogger(name));
	}

}