package de.isys.jawap.collector.profiler;

import de.isys.jawap.entities.profiler.CallStackElement;
import de.isys.jawap.entities.profiler.ExecutionContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class ExecutionContextLogger {

	private static final Log logger = LogFactory.getLog(ExecutionContextLogger.class);

	private ExecutorService asyncLoggPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r);
			thread.setDaemon(true);
			thread.setName("async-request-logger");
			return thread;
		}
	});

	public void logStats(final ExecutionContext requestStats) {
		if (logger.isInfoEnabled()) {
			asyncLoggPool.execute(new Runnable() {
				@Override
				public void run() {
					long start = System.currentTimeMillis();
					StringBuilder log = new StringBuilder(10000);
					log.append("\n########## PerformanceStats ##########\n");
					log.append(requestStats.toString());

					log.append("Printing stats took ").append(System.currentTimeMillis() - start).append(" ms\n");
					log.append("######################################\n\n\n");

					logger.info(log.toString());
				}
			});
		}
	}

}
