package cn.batchfile.getty.manager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.eclipse.jetty.xml.XmlParser;
import org.eclipse.jetty.xml.XmlParser.Node;
import org.ho.yaml.Yaml;
import org.xml.sax.SAXException;

import cn.batchfile.getty.application.Application;
import cn.batchfile.getty.application.Application.Mode;
import cn.batchfile.getty.application.ApplicationListener;
import cn.batchfile.getty.application.Cron;
import cn.batchfile.getty.application.Crontab;
import cn.batchfile.getty.application.ErrorHandler;
import cn.batchfile.getty.application.Filter;
import cn.batchfile.getty.application.Handler;
import cn.batchfile.getty.application.Session;
import cn.batchfile.getty.application.SessionListener;
import cn.batchfile.getty.application.ThreadPool;
import cn.batchfile.getty.application.WebSocket;
import cn.batchfile.getty.application.WebSocketHandler;
import cn.batchfile.getty.exceptions.InvalidApplicationDescriptorException;
import cn.batchfile.getty.util.PlaceholderUtils;

public class ApplicationManager {
	
	private static final Logger logger = Logger.getLogger(ApplicationManager.class);
	private Map<String, Application> applications = new HashMap<String, Application>();
	private List<String> applicationNames = new ArrayList<String>();

	public List<Application> getApplications() {
		List<Application> list = new ArrayList<Application>();
		for (Application value : applications.values()) {
			list.add(value);
		}
		return list;
	}
	
	public Application load(File dir) {
		logger.info("load application from " + dir);
		Application application = new Application(dir);
		
		//return null if descriptor file not exists
		if (application.getDescriptor() == null || !application.getDescriptor().exists()) {
			return null;
		}
		
		//load application
		if (application.getMode() == Mode.getty) {
			loadGettyApplication(application, dir);
		} else if (application.getMode() == Mode.j2ee) {
			logger.info("j2ee application");
			loadJ2EEApplication(application, dir);
		}
		
		//检查名称
		if (applicationNames.contains(application.getName())) {
			throw new RuntimeException("Duplicate application name: " + application.getName());
		}
		
		logger.info(String.format("load application from directory: %s, name: %s", dir, application.getName()));
		applications.put(application.getDirectory().getName(), application);
		applicationNames.add(application.getName());
		return application;
	}
	
	public void unload(String dirName) {
		logger.info("unload application " + dirName);
		Application application = applications.get(dirName);
		if (application != null) {
			applications.remove(dirName);
			applicationNames.remove(application.getName());
			application = null;
		}
	}
	
	private void loadJ2EEApplication(Application application, File dir) {
		//classes & lib
		loadClasspath(application);

		//set name
		application.setName(dir.getName());
		
		//set port
		try {
			XmlParser parser = new XmlParser();
			Node node = parser.parse(application.getDescriptor());
			Iterator<Node> iter = node.iterator("context-param");
			while (iter.hasNext()) {
				Node n = iter.next();
				String name = n.get("param-name").get(0).toString();
				String value = n.get("param-value").get(0).toString();
				if (name.toString().equals("getty.port")) {
					application.setPort(Integer.valueOf(value));
					break;
				}
			}
		} catch (IOException e) {
		} catch (SAXException e) {
		}
	}
	
	private void loadGettyApplication(Application application, File dir) {
		//classes & lib
		loadClasspath(application);
		
		try {
			//加载基本属性
			loadApplication(application, application.getDescriptor());
			
			//解析cron.yaml
			loadCrontab(application, new File(dir, "cron.yaml"));
			
			//解析session.yaml
			loadSession(application, new File(dir, "session.yaml"));
			
			//解析websocket.yaml
			loadWebSocket(application, new File(dir, "websocket.yaml"));
			
		} catch (FileNotFoundException e) {
			throw new InvalidApplicationDescriptorException("error when load application from " + dir, e);
		}
	}
	
	private void loadClasspath(Application application) {
		File dir =  application.getDescriptor().getParentFile();
		File classes = new File(dir, "classes");
		if (classes.exists() && classes.isDirectory()) {
			application.setClasses(classes);
		}
		
		application.setLibs(new ArrayList<File>());
		File lib = new File(dir, "lib");
		if (lib.exists() && lib.isDirectory()) {
			File[] files = lib.listFiles();
			for (File file : files) {
				String name = file.getName().toLowerCase();
				if (file.isFile() && (name.endsWith(".jar") || name.endsWith(".zip"))) {
					application.getLibs().add(file);
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void loadWebSocket(Application application, File file) throws FileNotFoundException {
		if (!file.exists()) {
			return;
		}
		
		Map<String, Object> map = (Map<String, Object>)Yaml.load(file);
		resolveSystemPropeties(map);
		List<Map<String, Object>> list = null;
		
		application.setWebSocket(new WebSocket());
		if (map.containsKey("url_pattern")) {
			application.getWebSocket().setUrlPattern(map.get("url_pattern").toString());
		}
		
		application.getWebSocket().setHandlers(new ArrayList<WebSocketHandler>());
		if (map.containsKey("handlers")) {
			list = (List<Map<String, Object>>)map.get("handlers");
			for (Map<String, Object> element : list) {
				WebSocketHandler handler = new WebSocketHandler();
				if (element.containsKey("url")) {
					handler.setUrl(element.get("url").toString());
				}
				
				if (element.containsKey("script")) {
					Map<String, Object> tmp = (Map<String, Object>)element.get("script");
					if (tmp.containsKey("connect")) {
						handler.setConnect(tmp.get("connect").toString());
					}
					if (tmp.containsKey("close")) {
						handler.setClose(tmp.get("close").toString());
					}
					if (tmp.containsKey("message")) {
						handler.setMessage(tmp.get("message").toString());
					}
					if (tmp.containsKey("error")) {
						handler.setError(tmp.get("error").toString());
					}
				}
				
				if (element.containsKey("policy")) {
					Map<String, Object> tmp = (Map<String, Object>)element.get("policy");
					if (tmp.containsKey("max_idle_time")) {
						handler.setMaxIdleTime(Long.parseLong(tmp.get("max_idle_time").toString()));
					}
					if (tmp.containsKey("batch_mode")) {
						handler.setBatchMode(tmp.get("batch_mode").toString());
					}
					if (tmp.containsKey("input_buffer_size")) {
						handler.setInputBufferSize(Integer.parseInt(tmp.get("input_buffer_size").toString()));
					}
					if (tmp.containsKey("max_binary_message_size")) {
						handler.setMaxBinaryMessageSize(Integer.parseInt(tmp.get("max_binary_message_size").toString()));
					}
					if (tmp.containsKey("max_text_message_size")) {
						handler.setMaxTextMessageSize(Integer.parseInt(tmp.get("max_text_message_size").toString()));
					}
				}
				application.getWebSocket().getHandlers().add(handler);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void loadSession(Application application, File file) throws FileNotFoundException {
		if (!file.exists()) {
			return;
		}
		
		Map<String, Object> map = (Map<String, Object>)Yaml.load(file);
		resolveSystemPropeties(map);
		List<Map<String, Object>> list = null;
		
		application.setSession(new Session());
		if (map.containsKey("timeout")) {
			application.getSession().setTimeout(Integer.valueOf(map.get("timeout").toString()));
		}

		application.getSession().setListeners(new ArrayList<SessionListener>());
		if (map.containsKey("listeners")) {
			list = (List<Map<String, Object>>)map.get("listeners");
			for (Map<String, Object> element : list) {
				SessionListener listener = new SessionListener();
				if (element.containsKey("created")) {
					listener.setCreated(element.get("created").toString());
				}
				if (element.containsKey("destroyed")) {
					listener.setDestroyed(element.get("destroyed").toString());
				}
				application.getSession().getListeners().add(listener);
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void loadCrontab(Application application, File file) throws FileNotFoundException {
		if (!file.exists()) {
			return;
		}
		
		Map<String, Object> map = (Map<String, Object>)Yaml.load(file);
		resolveSystemPropeties(map);
		List<Map<String, Object>> list = null;
		
		application.setCrontab(new Crontab());
		application.getCrontab().setCrons(new ArrayList<Cron>());
		
		if (map.containsKey("concurrent")) {
			boolean b = Boolean.valueOf(map.get("concurrent").toString());
			application.getCrontab().setConcurrent(b);
		}
		
		if (map.containsKey("cron")) {
			list = (List<Map<String, Object>>)map.get("cron");
			for (Map<String, Object> element : list) {
				Cron cron = new Cron();
				if (element.containsKey("description")) {
					cron.setDescription(element.get("description").toString());
				}
				cron.setScript(element.get("script").toString());
				cron.setSchedule(element.get("schedule").toString());
				
				application.getCrontab().getCrons().add(cron);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void loadApplication(Application application, File file) throws FileNotFoundException {
		Map<String, Object> map = (Map<String, Object>)Yaml.load(file);
		resolveSystemPropeties(map);
		List<Map<String, Object>> list = null;
		
		//基本属性
		application.setName(map.get("application").toString());
		if (map.containsKey("version")) {
			application.setVersion(map.get("version").toString());
		}
		if (map.containsKey("port")) {
			application.setPort(Integer.valueOf(map.get("port").toString()));
		}
		if (map.containsKey("charset_encoding")) {
			application.setCharsetEncoding(map.get("charset_encoding").toString());
		}
		if (map.containsKey("file_encoding")) {
			application.setFileEncoding(map.get("file_encoding").toString());
		}
		
		//默认页面
		application.setIndexPages(new ArrayList<String>());
		if (map.containsKey("index_pages")) {
			List<String> ary = (List<String>)map.get("index_pages");
			for (String s : ary) {
				if (StringUtils.isNotEmpty(s)) {
					application.getIndexPages().add(s);
				}
			}
		}
		
		//控制器
		application.setHandlers(new ArrayList<Handler>());
		if (map.containsKey("handlers")) {
			list = (List<Map<String, Object>>)map.get("handlers");
			for (Map<String, Object> element : list) {
				Handler handler = new Handler();
				handler.setUrl(element.get("url").toString());
				if (element.containsKey("script")) {
					handler.setScript(element.get("script").toString());
				}
				if (element.containsKey("http_headers")) {
					Map<String, String> headers = (Map<String, String>)element.get("http_headers");
					handler.setHttpHeaders(headers);
				}
				application.getHandlers().add(handler);
			}
		}
		
		//过滤器
		application.setFilters(new ArrayList<Filter>());
		if (map.containsKey("filters")) {
			list = (List<Map<String, Object>>)map.get("filters");
			for (Map<String, Object> element : list) {
				Filter filter = new Filter();
				filter.setUrlPattern(element.get("url_pattern").toString());
				filter.setScript(element.get("script").toString());
				
				application.getFilters().add(filter);
			}
		}
		
		//监听器
		application.setListeners(new ArrayList<ApplicationListener>());
		if (map.containsKey("listeners")) {
			list = (List<Map<String, Object>>)map.get("listeners");
			for (Map<String, Object> element : list) {
				ApplicationListener listener = new ApplicationListener();
				listener.setStart(element.get("start").toString());
				listener.setStop(element.get("stop").toString());
				
				application.getListeners().add(listener);
			}
		}
		
		//异常处理
		application.setErrorHandlers(new ArrayList<ErrorHandler>());
		if (map.containsKey("error_handlers")) {
			list = (List<Map<String, Object>>)map.get("error_handlers");
			for (Map<String, Object> element : list) {
				ErrorHandler handler = new ErrorHandler();
				if (element.containsKey("error_code")) {
					handler.setErrorCode(Integer.valueOf(element.get("error_code").toString()));
				}
				handler.setFile(element.get("file").toString());
				
				application.getErrorHandlers().add(handler);
			}
		}
		
		//线程池
		application.setThreadPool(new ThreadPool());
		if (map.containsKey("thread_pool")) {
			Map<String, Object> m = (Map<String, Object>)map.get("thread_pool");
			if (m.containsKey("max_threads")) {
				application.getThreadPool().setMaxThreads(Integer.valueOf(m.get("max_threads").toString()));
			}
			if (m.containsKey("min_threads")) {
				application.getThreadPool().setMinThreads(Integer.valueOf(m.get("min_threads").toString()));
			}
			if (m.containsKey("idle_timeout")) {
				application.getThreadPool().setIdleTimeout(Integer.valueOf(m.get("idle_timeout").toString()));
			}
		}
	}

	private void resolveSystemPropeties(Map<String, Object> map) {
		Properties properties = System.getProperties();
		Map<String, String> vars = new HashMap<String, String>();
		for (Entry<Object, Object> entry : properties.entrySet()) {
			vars.put(entry.getKey().toString(), entry.getValue().toString());
		}
		
		vars.putAll(System.getenv());
		
		resolveSystemPropeties(map, vars);
	}

	private void resolveSystemPropeties(Map<String, Object> map, Map<String, String> vars) {
		for (Entry<String, Object> entry : map.entrySet()) {
			if (entry.getValue() != null) {
				if (entry.getValue() instanceof String) {
					entry.setValue(PlaceholderUtils.resolvePlaceholders(entry.getValue().toString(), vars));
				} else if (entry.getValue() instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> m = (Map<String, Object>)entry.getValue();
					resolveSystemPropeties(m, vars);
				}
			}
		}
	}
	
}
