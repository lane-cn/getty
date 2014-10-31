package cn.batchfile.getty.boot;

import org.apache.log4j.Logger;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import cn.batchfile.getty.configuration.Configuration;
import cn.batchfile.getty.filter.GettyFilter;
import cn.batchfile.getty.servlet.GettyServlet;

/**
 * Getty服务
 * @author htlu
 *
 */
public class Server {
	private static final Logger logger = Logger.getLogger(Server.class);
	
	/**
	 * 启动服务
	 * @param configuration 设置
	 */
	public void start(Configuration configuration) {
		logger.info(String.format("<Getty startup at port %d>", configuration.getPort()));

		SelectChannelConnector connector = new SelectChannelConnector();
		connector.setPort(configuration.getPort());
		if (configuration.getMaxIdleTime() > 0) { 
			connector.setMaxIdleTime(configuration.getMaxIdleTime());
		}
		if (configuration.getRequestHeaderSize() > 0) {
			connector.setRequestHeaderSize(configuration.getRequestHeaderSize());
		}
		
		QueuedThreadPool threadPool =  new QueuedThreadPool();
		if (configuration.getMaxIdleTime() > 0) { 
			threadPool.setMaxIdleTimeMs(configuration.getMaxIdleTime());
		}
		if (configuration.getMaxQueued() > 0) {
			threadPool.setMaxQueued(configuration.getMaxQueued());
		}
		if (configuration.getMaxThread() > 0) {
			threadPool.setMaxThreads(configuration.getMaxThread());
		}
		if (configuration.getMinThread() > 0) {
			threadPool.setMinThreads(configuration.getMinThread());
		}
		threadPool.setName("getty-http");
		connector.setThreadPool(threadPool);
		
		final org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(configuration.getPort());
		server.setConnectors(new Connector[] { connector });
		
		ServletContextHandler context = new ServletContextHandler(server, configuration.getContextPath());
		context.addFilter(new FilterHolder(new GettyFilter()), "/", FilterMapping.DEFAULT);
		context.addServlet(new ServletHolder(new GettyServlet()), "/");
		
        try {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        server.start();
                        server.join();
                    } catch (Exception e) {
                        logger.error("Error when start Getty", e);
                    }
                }
            }).start();
        } catch (Exception e) {
            logger.error("Error when start Getty", e);
        }
	}
}
