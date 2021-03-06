package cn.batchfile.getty.application;

import java.io.File;
import java.util.List;

public class Application {
	public enum Mode {
		getty,
		j2ee
	}

	private File directory;
	private File classes;
	private List<File> libs;
	private String name;
	private String version;
	private List<String> indexPages;
	private int port = 0;
	private String charsetEncoding = "utf-8";
	private String fileEncoding = "utf-8";
	private List<Handler> handlers;
	private List<Filter> filters;
	private List<ApplicationListener> listeners;
	private List<ErrorHandler> errorHandlers;
	private Crontab crontab;
	private Session session;
	private WebSocket webSocket;
	private ThreadPool threadPool;
	
	public Application(File directory) {
		this.directory = directory;
	}
	
	public Mode getMode() {
		File descriptor = getDescriptor();
		if (descriptor != null) {
			String name = descriptor.getName().toLowerCase();
			if (name.equals("app.yaml")) {
				return Mode.getty;
			} else if (name.equals("web.xml")) {
				return Mode.j2ee;
			}
		}
		return null;
	}
	
	public File getDescriptor() {
		File descriptor = new File(directory, "app.yaml");
		if (descriptor.exists()) {
			return descriptor;
		} else {
			//如果没有yaml描述符，判断是不是J2EE应用
			File info = new File(directory, "WEB-INF");
			if (info.exists() && info.isDirectory()) {
				descriptor = new File(info, "web.xml");
				if (descriptor.exists()) {
					return descriptor;
				}
			}
		}
		return null;
	}

	public File getDirectory() {
		return directory;
	}

	public File getClasses() {
		return classes;
	}

	public void setClasses(File classes) {
		this.classes = classes;
	}

	public List<File> getLibs() {
		return libs;
	}

	public void setLibs(List<File> libs) {
		this.libs = libs;
	}

	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getVersion() {
		return version;
	}
	
	public void setVersion(String version) {
		this.version = version;
	}
	
	public List<String> getIndexPages() {
		return indexPages;
	}

	public void setIndexPages(List<String> indexPages) {
		this.indexPages = indexPages;
	}

	public int getPort() {
		return port;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public String getCharsetEncoding() {
		return charsetEncoding;
	}

	public void setCharsetEncoding(String charsetEncoding) {
		this.charsetEncoding = charsetEncoding;
	}

	public String getFileEncoding() {
		return fileEncoding;
	}

	public void setFileEncoding(String fileEncoding) {
		this.fileEncoding = fileEncoding;
	}

	public List<Handler> getHandlers() {
		return handlers;
	}
	
	public void setHandlers(List<Handler> handlers) {
		this.handlers = handlers;
	}
	
	public List<Filter> getFilters() {
		return filters;
	}
	
	public void setFilters(List<Filter> filters) {
		this.filters = filters;
	}
	
	public List<ApplicationListener> getListeners() {
		return listeners;
	}
	
	public void setListeners(List<ApplicationListener> listeners) {
		this.listeners = listeners;
	}
	
	public List<ErrorHandler> getErrorHandlers() {
		return errorHandlers;
	}
	
	public void setErrorHandlers(List<ErrorHandler> errorHandlers) {
		this.errorHandlers = errorHandlers;
	}

	public Crontab getCrontab() {
		return crontab;
	}

	public void setCrontab(Crontab crontab) {
		this.crontab = crontab;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public WebSocket getWebSocket() {
		return webSocket;
	}

	public void setWebSocket(WebSocket webSocket) {
		this.webSocket = webSocket;
	}

	public ThreadPool getThreadPool() {
		return threadPool;
	}

	public void setThreadPool(ThreadPool threadPool) {
		this.threadPool = threadPool;
	}
}
