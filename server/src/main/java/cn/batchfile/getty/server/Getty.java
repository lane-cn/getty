package cn.batchfile.getty.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import cn.batchfile.getty.application.Application;
import cn.batchfile.getty.manager.ApplicationInstanceManager;
import cn.batchfile.getty.manager.ApplicationManager;

public class Getty {
	private static final Logger LOG = Logger.getLogger(Getty.class);
	
	private ApplicationManager applicationManager = new ApplicationManager();
	private ApplicationInstanceManager applicationInstanceManager = new ApplicationInstanceManager();
	
	private String baseDirectory;
	private String applicationsDirectory;
	
	public String getBaseDirectory() {
		return baseDirectory;
	}
	
	public void setBaseDirectory(String baseDirectory) {
		this.baseDirectory = baseDirectory;
	}
	
	public String getApplicationsDirectory() {
		return applicationsDirectory;
	}
	
	public void setApplicationsDirectory(String applicationsDirectory) {
		this.applicationsDirectory = applicationsDirectory;
	}
	
	public void start() throws Exception {
		//寻找应用目录
		List<File> appDirs = findAppDirs(baseDirectory, applicationsDirectory);
		
		//加载应用目录
		for (File appDir : appDirs) {
			applicationManager.load(appDir);
		}
		
		//启动应用
		List<Application> applications = applicationManager.getApplications();
		for (Application application : applications) {
			applicationInstanceManager.start(application);
		}
	}
	
	public void stop() {
		List<Application> applications = applicationManager.getApplications();
		for (Application application : applications) {
			applicationInstanceManager.stop(application.getDirectory().getName());
			applicationManager.unload(application.getDirectory().getName());
		}
	}

	private List<File> findAppDirs(String baseDir, String appsDir) throws Exception {
		List<File> list = new ArrayList<File>();
		File webapps = null;
		if (StringUtils.isEmpty(appsDir)) {
			//命令行没有给apps-dir参数，寻找默认的位置
			webapps = new File(baseDir, "webapps");
		} else {
			//命令行给了apps-dir参数，按照参数寻找 
			webapps = new File(appsDir);
		}
		
		if (webapps.exists()) {
			//找到webapps路径, 遍历子目录
			LOG.info("application root: " + webapps);
			File[] dirs = webapps.listFiles();
			for (File file : dirs) {
				
				//忽略隐藏文件
				String name = file.getName().toLowerCase();
				if (StringUtils.startsWith(name, ".")) {
					continue;
				}
				
				//如果是zip或war，解压
				File dir = null;
				if (file.isFile() 
						&& StringUtils.endsWith(name, ".zip") || StringUtils.endsWith(name, ".war")) {
					dir = unzip(file);
				} else if (file.isDirectory()) {
					dir = file;
				}
				
				//目录里面有项目文件，加载这个目录
				if (dir != null && new Application(dir).getDescriptor() != null) {
					list.add(dir);
				}
			}
			
			//设置目录监听器
			final File root = webapps;
			FileAlterationObserver observer = new FileAlterationObserver(root, null, null);
			observer.addListener(new FileAlterationListener() {

				@Override
				public void onStart(FileAlterationObserver observer) {
				}

				@Override
				public void onStop(FileAlterationObserver observer) {
				}
				
				@Override
				public void onFileCreate(File file) {
					File directory = findApplicationDirectory(file);
					if (directory != null) {
						Application application = applicationManager.load(directory);
						if (application != null) {
							applicationInstanceManager.start(application);
						}
					}
				}

				@Override
				public void onFileChange(File file) {
				}

				@Override
				public void onFileDelete(File file) {
					File directory = findApplicationDirectory(file);
					if (directory != null) {
						applicationInstanceManager.stop(directory.getName());
						applicationManager.unload(directory.getName());
					}
				}

				@Override
				public void onDirectoryCreate(File file) {
				}

				@Override
				public void onDirectoryChange(File file) {
				}

				@Override
				public void onDirectoryDelete(File file) {
				}
				
				private File findApplicationDirectory(File descriptor) {
					String name = descriptor.getName().toLowerCase();
					if (name.equals("app.yaml")) {
						return descriptor.getParentFile();
					} else if (name.equals("web.xml")) {
						File info = descriptor.getParentFile();
						if (info != null && info.exists()) {
							return info.getParentFile();
						}
					} else if (StringUtils.endsWith(name, ".zip") || StringUtils.endsWith(name, ".war")) {
						try {
							unzip(descriptor);
						} catch (Exception e) {
							LOG.error("error when unzip: " + descriptor, e);
						}
					}
					return null;
				}
			});
			FileAlterationMonitor monitor = new FileAlterationMonitor(2000, observer);
			monitor.start();
		} 
		
		//不存在webapps目录，按照调试模式寻找
		if (!webapps.exists()) {
			String[] dirs = new String[] {"webapp", "src/main/webapp"};
			for (String dir : dirs) {
				File app = new File(baseDir, dir);
				if (new Application(app).getDescriptor() != null) {
					list.add(app);
					LOG.info("application root: " + app);
					break;
				}
			}
		}
		
		return list;
	}
	
	private File unzip(File file) throws FileNotFoundException, IOException {
		File base = new File(file.getParentFile(), StringUtils.substringBeforeLast(file.getName(), "."));
		if (base.exists()) {
			return null;
		}
		
		LOG.info("unzip: " + file);
		BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
		ZipInputStream zis = new ZipInputStream(bis);
		
		ZipEntry entry = null;
		while ((entry = zis.getNextEntry()) != null) {
			File f = new File(base, entry.getName());
			if (entry.isDirectory()) {
				FileUtils.forceMkdir(f);
			} else {
				BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(f));
				int b = 0;
				while ((b = zis.read()) != -1) {
					bos.write(b);
				}
				bos.flush();
				bos.close();
			}
		}
		zis.close();
		
		return base;
	}
}
