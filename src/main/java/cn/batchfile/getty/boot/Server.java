package cn.batchfile.getty.boot;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.EnumSet;

import javax.servlet.DispatcherType;

import org.apache.commons.vfs2.FileChangeEvent;
import org.apache.commons.vfs2.FileListener;
import org.apache.commons.vfs2.FileMonitor;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.impl.DefaultFileMonitor;
import org.apache.log4j.Logger;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;

import cn.batchfile.getty.binding.Application;
import cn.batchfile.getty.configuration.Configuration;
import cn.batchfile.getty.filter.FilterListener;
import cn.batchfile.getty.mvc.RequestMapping;
import cn.batchfile.getty.mvc.Rewriter;
import cn.batchfile.getty.servlet.ApplicationListener;
import cn.batchfile.getty.servlet.GettyServlet;
import cn.batchfile.getty.servlet.SessionListener;

/**
 * Getty服务
 * 
 * @author htlu
 *
 */
public class Server {
	private static final Logger logger = Logger.getLogger(Server.class);
	private ApplicationListener applicationListener;

	/**
	 * 启动服务
	 * 
	 * @param configuration
	 *            设置
	 * @throws Exception 
	 */
	public void start(Configuration configuration) throws Exception {
		
		//start jetty
		startJetty(configuration);
		logger.info(String.format("<Getty startup at port %d>",
				configuration.getPort()));
		
	}
	
	public void stop() throws IOException {
		applicationListener.stop();
	}
	
	private void startJetty(final Configuration configuration) throws Exception {
		// create jetty server
		final org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(
				configuration.getPort());
		setRuntimeParameters(server, configuration);
		
		// load rewriter mapper
		//final String webRoot = ;
		File webRoot = new File(configuration.getWebRoot());
		final Rewriter rewriter = new Rewriter();
		rewriter.config(webRoot);
		
		// load application listener
		final ApplicationListener appListener = new ApplicationListener(configuration, Application.getInstance());
		appListener.config(webRoot);
		applicationListener = appListener;
		
		// load session listener
		final SessionListener sessionListener = new SessionListener(configuration, Application.getInstance());
		sessionListener.config(webRoot);
		
		// load filter manager
		final FilterListener filterListener = new FilterListener(configuration, Application.getInstance());
		filterListener.config(webRoot);
		
		// add file watcher on webapp
		ConfigFileListener listener = new ConfigFileListener();
		DefaultFileMonitor fm = new DefaultFileMonitor(listener);
		listener.setFileMonitor(fm);
		listener.setRewriter(rewriter);
		listener.setApplicationListener(appListener);
		listener.setSessionListener(sessionListener);
		listener.setFilterListener(filterListener);
		listener.setRoot(configuration.getWebRoot());
		fm.setDelay(2000);
		fm.setRecursive(false);
		FileObject file = VFS.getManager().resolveFile(configuration.getWebRoot());
		listener.addFile(fm, file);
		
		fm.start();
		logger.info("add watcher on directory: " + configuration.getWebRoot());
		
		// setup webapp
		WebAppContext context = new WebAppContext();
		context.setContextPath("/");
		context.setWar(configuration.getWebRoot());
		context.setWelcomeFiles(configuration.getIndexPages());
		context.setServer(server);
		
		HashLoginService loginService = new HashLoginService("GETTY-SECURITY-REALM");
		context.getSecurityHandler().setLoginService(loginService);
		server.setHandler(context);
		
		// setup servlet mapping
		RequestMapping mapping = new RequestMapping(configuration, rewriter);
		GettyServlet servlet = new GettyServlet(mapping);
		context.addServlet(new ServletHolder(servlet), "/");
		
		// set session listener
		context.addEventListener(sessionListener);
		
		// set filter
		EnumSet<DispatcherType> dts = EnumSet.of(DispatcherType.REQUEST);
		context.addFilter(new FilterHolder(filterListener), "*", dts);
		
		// kick off http service
		server.start();
		
		// set start time & config
		Application.getInstance().setStartTime(new Date());
		Application.getInstance().setConfiguration(configuration);
		
		//invoke application start event
		applicationListener.start();
	}
	
	private void setRuntimeParameters(org.eclipse.jetty.server.Server server, Configuration configuration) {
		ThreadPool pool = server.getThreadPool();
		if (pool instanceof QueuedThreadPool) {
			QueuedThreadPool qtp = (QueuedThreadPool)pool;
			if (configuration.getMaxIdleTime() > 0) {
				qtp.setIdleTimeout(configuration.getMaxIdleTime());
			}
			if (configuration.getMaxThread() > 0) {
				qtp.setMaxThreads(configuration.getMaxThread());
			}
			if (configuration.getMinThread() > 0) {
				qtp.setMinThreads(configuration.getMinThread());
			}
			qtp.setName("getty-http");
		}
	}
	
	class ConfigFileListener implements FileListener {
		private FileMonitor fileMonitor;
		private Rewriter rewriter;
		private ApplicationListener applicationListener;
		private SessionListener sessionListener;
		private FilterListener filterListener;
		private String root;
		
		public void setFileMonitor(FileMonitor fileMonitor) {
			this.fileMonitor = fileMonitor;
		}

		public void setRewriter(Rewriter rewriter) {
			this.rewriter = rewriter;
		}

		public void setApplicationListener(ApplicationListener applicationListener) {
			this.applicationListener = applicationListener;
		}

		public SessionListener getSessionListener() {
			return sessionListener;
		}

		public void setSessionListener(SessionListener sessionListener) {
			this.sessionListener = sessionListener;
		}
		
		public void setFilterListener(FilterListener filterListener) {
			this.filterListener = filterListener;
		}

		public void setRoot(String root) {
			this.root = root;
		}

		@Override
		public void fileChanged(FileChangeEvent event) throws Exception {
			if (event.getFile().getType() == FileType.FOLDER) {
				addFile(fileMonitor, event.getFile());
			} else {
				onChange(event.getFile());
			}
		}

		@Override
		public void fileCreated(FileChangeEvent event) throws Exception {
			if (event.getFile().getType() == FileType.FOLDER) {
				addFile(fileMonitor, event.getFile());
			} else {
				onChange(event.getFile());
			}
		}

		@Override
		public void fileDeleted(FileChangeEvent event) throws Exception {
			if (event.getFile().getType() == FileType.FOLDER) {
				fileMonitor.removeFile(event.getFile());
			} else {
				onChange(event.getFile());
			}
		}

		public void addFile(FileMonitor fm, FileObject file) throws FileSystemException {
			if (file.getType() == FileType.FOLDER) {
				fm.addFile(file);
				for (FileObject child : file.getChildren()) {
					addFile(fm, child);
				}
			} 
		}
		
		private void onChange(FileObject file) throws Exception {
			if (file.getName().getBaseName().equals(Rewriter.CONFIG_FILE)) {
				rewriter.config(new File(root));
			} else if (file.getName().getBaseName().equals(ApplicationListener.CONFIG_FILE)) {
				applicationListener.config(new File(root));
			} else if (file.getName().getBaseName().equals(SessionListener.CONFIG_FILE)) {
				sessionListener.config(new File(root));
			} else if (file.getName().getBaseName().equals(FilterListener.CONFIG_FILE)) {
				filterListener.config(new File(root));
			}
		}
	}
}
