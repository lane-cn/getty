package cn.batchfile.getty.manager;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.DispatcherType;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;

import cn.batchfile.getty.application.Application;
import cn.batchfile.getty.application.Application.Mode;
import cn.batchfile.getty.application.ApplicationInstance;
import cn.batchfile.getty.application.ErrorHandler;
import cn.batchfile.getty.exceptions.InvalidApplicationDescriptorException;
import cn.batchfile.getty.lang.ApplicationClassLoader;
import groovy.lang.GroovyClassLoader;

public class ApplicationInstanceManager {
	
	private static final Logger LOG = Logger.getLogger(ApplicationInstanceManager.class);
	private Map<String, ApplicationHolder> applicationHolders = new HashMap<String, ApplicationHolder>();

	public ApplicationInstance start(Application application) {
		ApplicationInstance ai = new ApplicationInstance();
		ai.setApplication(application);
		ai.setStartTime(new Date());
		
		Server server = new Server(application.getPort());
		setRuntimeParameters(server, application);
		
		//create classloader
		ClassLoader cl = createClassLoader(application);
		
		WebAppContext context = new WebAppContext();
		context.setClassLoader(cl);
		context.setMaxFormContentSize(Integer.MAX_VALUE);
		context.setContextPath("/");
		context.setWar(application.getDirectory().getAbsolutePath());
		context.setServer(server);
		if (application.getMode() == Mode.j2ee) {
			context.setDescriptor(application.getDescriptor().getAbsolutePath());
		}
		
		HashLoginService loginService = new HashLoginService("GETTY-SECURITY-REALM-" + application.getName());
		context.getSecurityHandler().setLoginService(loginService);
		server.setHandler(context);
		
		//create script manager
		ScriptEngineManager sem = createScriptEngineManager(application, cl);

		if (application.getMode() == Mode.getty) {
			//apply servlet
			ServletManager servletManager = createServletManager(application, ai, cl, sem);
			context.addServlet(new ServletHolder(servletManager), "/");
		
			//apply websocket servlet
			if (application.getWebSocket() != null) {
				WebSocketManager socketManager = createWebSocketManager(application, ai, cl, sem);
				context.addServlet(new ServletHolder(socketManager), application.getWebSocket().getUrlPattern());
			}

			//set session listener
			SessionEventListener sessionEventListener = createSessionEventListener(application, ai, sem);
			context.addEventListener(sessionEventListener);
		
			//set session timeout
			int minutes = application.getSession() == null ? 0 : application.getSession().getTimeout();
			if (minutes > 0) {
				context.getSessionHandler().getSessionManager().setMaxInactiveInterval(minutes * 60);
			}
		
			//setup filters
			ServletFilterManager filterManager = createServletFilterManager(application, ai, sem);
			context.addFilter(new FilterHolder(filterManager), "*", EnumSet.of(DispatcherType.REQUEST));

			//setup error pages
			setupErrorPages(context, application.getErrorHandlers());
		}
		
		//setup applicatio holder
		ApplicationHolder ah = createApplicationHolder(application, server);
		applicationHolders.put(application.getDirectory().getName(), ah);

		try {
			//启动web服务
			Map<String, Object> vars = new HashMap<String, Object>();
			vars.put("$server", server);
			sem.runScript("$server.start();", vars);
			
			//得到运行端口
			int port = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
			ai.setPort(port);
			LOG.info(String.format("start %s at port: %s", application.getName(), port));

			if (application.getMode() == Mode.getty) {
				//执行应用监听器
				ApplicationEventListener ael = createApplicationEventListener(application, ai, sem);
				ael.applicationStart();
				ah.applicationEventListener = ael;
				
				//执行定时器
				CrontabManager crontabManager = createCrontabManager(application, ai, sem);
				crontabManager.start();
				ah.crontabManager = crontabManager;
			}
		} catch (Exception e) {
			throw new RuntimeException("error when start " + application.getName(), e);
		}
		
		return ai;
	}
	
	public void stop(String dirName) {
		ApplicationHolder holder = applicationHolders.get(dirName);
		if (holder != null) {
			
			LOG.info("stop jetty for " + dirName);
			try {
				holder.server.stop();
			} catch (Exception e) {
			}
			
			LOG.info("invoke application stop for " + dirName);
			try {
				holder.applicationEventListener.applicationStop();
			} catch (Exception e) {
			}
			
			LOG.info("stop crontab for " + dirName);
			try {
				holder.crontabManager.stop();
			} catch (Exception e) {
			}
		}
	}
	
	private ApplicationHolder createApplicationHolder(Application application, Server server) {
		ApplicationHolder ah = new ApplicationHolder();
		ah.server = server;
		ah.application = application;
		return ah;
	}

	private CrontabManager createCrontabManager(Application application, ApplicationInstance ai,
			ScriptEngineManager sem) {
		CrontabManager crontabManager = new CrontabManager();
		crontabManager.setApplication(application);
		crontabManager.setApplicationInstance(ai);
		crontabManager.setScriptEngineManager(sem);
		return crontabManager;
	}

	private void setupErrorPages(WebAppContext context, List<ErrorHandler> errorHandlers) {
		if (errorHandlers != null) {
			ErrorPageErrorHandler errorHandler = (ErrorPageErrorHandler)context.getErrorHandler();
			for (ErrorHandler handler : errorHandlers) {
				String page = handler.getFile();
				if (!StringUtils.startsWith(page, "/")) {
					page = "/" + page;
				}
				
				if (handler.getErrorCode() > 0) {
					errorHandler.addErrorPage(handler.getErrorCode(), page);
				} else {
					errorHandler.addErrorPage(Integer.MIN_VALUE, Integer.MAX_VALUE, page);
				}
			}
		}
	}

	private ServletFilterManager createServletFilterManager(Application application, 
			ApplicationInstance applicationInstance,
			ScriptEngineManager scriptEngineManager) {
		
		ServletFilterManager filterManager = new ServletFilterManager();
		filterManager.setApplication(application);
		filterManager.setApplicationInstance(applicationInstance);
		filterManager.setScriptEngineManager(scriptEngineManager);
		
		return filterManager;
	}

	private ApplicationEventListener createApplicationEventListener(Application application, 
			ApplicationInstance applicationInstance, ScriptEngineManager scriptEngineManager) {
		
		ApplicationEventListener ael = new ApplicationEventListener();
		ael.setApplication(application);
		ael.setApplicationInstance(applicationInstance);
		ael.setScriptEngineManager(scriptEngineManager);
		return ael;
	}
	
	private SessionEventListener createSessionEventListener(Application application, ApplicationInstance applicationInstance, 
			ScriptEngineManager scriptEngineManager) {
		SessionEventListener listener = new SessionEventListener();
		listener.setApplication(application);
		listener.setApplicationInstance(applicationInstance);
		listener.setScriptEngineManager(scriptEngineManager);
		return listener;
	}

	private ScriptEngineManager createScriptEngineManager(Application application, ClassLoader classLoader) {
		ScriptEngineManager sem = new ScriptEngineManager();
		sem.setApplication(application);
		sem.setClassLoader(classLoader);
		return sem;
	}

	private ClassLoader createClassLoader(Application application) {
		
		try {
			//构建classpath
			List<File> classpath = new ArrayList<File>();
			if (application.getClasses() != null && application.getClasses().exists()) {
				classpath.add(application.getClasses());
			}
			for (File lib : application.getLibs()) {
				classpath.add(lib);
			}
			
			URL[] urls = new URL[classpath.size()];
			for (int i = 0; i < classpath.size(); i ++) {
				urls[i] = classpath.get(i).toURI().toURL();
			}
			
			//构建classloader
			ApplicationClassLoader cl = new ApplicationClassLoader(urls, getClass().getClassLoader());

			GroovyClassLoader gcl = new GroovyClassLoader(cl);
			gcl.addClasspath(application.getClasses().getAbsolutePath());
			for (File lib : application.getLibs()) {
				gcl.addClasspath(lib.getAbsolutePath());
			}
			
			return gcl;
		} catch (MalformedURLException e) {
			throw new InvalidApplicationDescriptorException("error when create class loader", e);
		}
	}
	
	private WebSocketManager createWebSocketManager(Application application, ApplicationInstance instance, 
			ClassLoader classLoader, ScriptEngineManager scriptEngineManager) {
		
		WebSocketManager wsm = new WebSocketManager();
		wsm.setApplication(application);
		wsm.setApplicationInstance(instance);
		wsm.setClassLoader(classLoader);
		wsm.setScriptEngineManager(scriptEngineManager);
		return wsm;
	}

	private ServletManager createServletManager(Application application, ApplicationInstance instance, 
			ClassLoader classLoader, ScriptEngineManager scriptEngineManager) {
		
		MappingManager mapping = new MappingManager();
		mapping.setApplication(application);
		
		ServletManager servlet = new ServletManager();
		servlet.setApplication(application);
		servlet.setApplicationInstance(instance);
		servlet.setMappingManager(mapping);
		servlet.setClassLoader(classLoader);
		servlet.setScriptEngineManager(scriptEngineManager);
		
		return servlet;
	}

	private void setRuntimeParameters(Server server, Application application) {
		ThreadPool pool = server.getThreadPool();
		if (pool instanceof QueuedThreadPool) {
			QueuedThreadPool qtp = (QueuedThreadPool)pool;
			cn.batchfile.getty.application.ThreadPool config = application.getThreadPool();
			if (config != null && config.getMaxThreads() > 0) {
				qtp.setMaxThreads(config.getMaxThreads());
			}
			if (config != null && config.getMinThreads() > 0) {
				qtp.setMinThreads(config.getMinThreads());
			}
			if (config != null && config.getIdleTimeout() > 0) {
				qtp.setIdleTimeout(config.getIdleTimeout());
			}
		}
	}
	
	class ApplicationHolder {
		Application application;
		Server server;
		ApplicationEventListener applicationEventListener;
		CrontabManager crontabManager;
	}
}
