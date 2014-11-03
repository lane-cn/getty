package cn.batchfile.getty.binding;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

public class Request {
	private static final String LINE_SEP = System.getProperty("line.separator", "\r\n");
	private HttpServletRequest servletRequest;
	private Map<String, Object> params = new HashMap<String, Object>();
	private boolean paramsInited = false;
	private String body;
	private boolean bodyInited = false;
	
	public Request(HttpServletRequest servletRequest) throws IOException {
		this.servletRequest = servletRequest;
	}
	
	public String uri() {
		return servletRequest.getRequestURI();
	}
	
	public Object parameter(String name) {
		return parameters().get(name);
	}
	
	public Map<String, Object> parameters() {
		if (!paramsInited) {
			@SuppressWarnings("rawtypes")
			Map map = servletRequest.getParameterMap();
			for (Object key : map.keySet()) {
				Object value = map.get(key);
				params.put(key.toString(), value);
			}
			paramsInited = true;
		}
		return params;
	}
	
	public String body() throws IOException {
		if (!bodyInited) {
			InputStream stream = null;
			try {
				stream = servletRequest.getInputStream();
				List<String> lines = IOUtils.readLines(stream);
				body = StringUtils.join(lines, LINE_SEP);
			} finally {
				IOUtils.closeQuietly(stream);
			}
			bodyInited = true;
		}
		return body;
	}
}
