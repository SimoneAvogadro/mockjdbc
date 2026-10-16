package me.avogadro.mockjdbc.resources;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.avogadro.mockjdbc.MockJdbcDriver;
import me.avogadro.mockjdbc.boomi.BoomiExecutionUtil;

/**
 * Finds the CSV of a resource in all the data sources. For each name, from the most specific to the most generic
 * (e.g. <code>users?Smith#2</code>, <code>users?Smith</code>, <code>users#2</code>, <code>users</code>), the sources are
 * tried in this order:
 * <ol>
 * <li>in memory, then the Boomi Dynamic Process Property;</li>
 * <li>the table folders, in their order (see {@link #resolveFolders(File)});</li>
 * <li>the files registered with {@link MockJdbcDriver#addTableResource(String, File)};</li>
 * <li><code>/tables/&lt;name&gt;.csv</code> on the classpath.</li>
 * </ol>
 * The folders are listed again by every lookup, so files can be added or changed while the tests run. A lookup is
 * meant for a single query: create a new one for each.
 *
 * @author Simone Avogadro
 */
public final class TableLookup {

	private static final Logger LOGGER = LoggerFactory.getLogger(TableLookup.class);

	/** Java system property with the root of the table folders */
	public static final String ROOT_SYSTEM_PROPERTY = "mockjdbc.root";

	/** environment variable with the root of the table folders */
	public static final String ROOT_ENVIRONMENT_VARIABLE = "MOCKJDBC_ROOT";

	/** separator of the folders in the Dynamic Process Property {@value BoomiExecutionUtil#FOLDER_PROPERTY} */
	public static final String FOLDER_SEPARATOR = ";";

	/** charset of the files in the table folders when none is configured */
	public static final Charset DEFAULT_FOLDER_CHARSET = Charset.forName("UTF-8");

	private static final char BOM = '﻿';

	private final Map<String, File> registeredFiles;
	private final File connectionRoot;

	/** the table folders, resolved on first use */
	private List<File> folders;
	/** resource key to file, per folder, built on first use */
	private final Map<File, Map<String, File>> indexes = new HashMap<File, Map<String, File>>();
	private boolean charsetResolved = false;
	/** configured charset, null = default (UTF-8 for folders, the VM default for the other files) */
	private Charset configuredCharset;

	/**
	 * @param registeredFiles files registered for the connection (resource key to file), may be <code>null</code>
	 * @param connectionRoot root folder given by the connection URL, may be <code>null</code>
	 */
	public TableLookup(Map<String, File> registeredFiles, File connectionRoot) {
		this.registeredFiles = registeredFiles == null ? Collections.<String, File>emptyMap() : registeredFiles;
		this.connectionRoot = connectionRoot;
	}

	/**
	 * @param name a resource name
	 * @return the CSV of the resource, or <code>null</code> if no source defines it
	 * @throws SQLException if a table folder does not exist, cannot be read or holds two files for the same resource
	 */
	public String find(String name) throws SQLException {
		return find(Collections.singletonList(name));
	}

	/**
	 * @param names resource names, the most specific first
	 * @return the CSV of the first name defined by a source (a leading byte order mark removed), or <code>null</code>
	 * @throws SQLException if a table folder does not exist, cannot be read or holds two files for the same resource
	 */
	public String find(List<String> names) throws SQLException {
		for (String name : names) {
			String csv = findName(name);
			if (csv != null) {
				return csv.isEmpty() || csv.charAt(0) != BOM ? csv : csv.substring(1);
			}
		}
		return null;
	}

	private String findName(String name) throws SQLException {
		// 1. in memory, then Boomi Dynamic Process Property
		String csv = MockJdbcDriver.getInMemoryTableResource(name);
		if (csv != null) {
			LOGGER.debug("Resource '{}' found in memory or in a Dynamic Process Property", name);
			return csv;
		}

		String key = ResourceNames.key(name);

		// 2. table folders
		for (File folder : folders()) {
			File file = index(folder).get(key);
			if (file != null) {
				LOGGER.debug("Resource '{}' found in {}", name, file);
				try {
					return read(new FileInputStream(file), charset(true));
				} catch (IOException e) {
					throw new SQLException("mockjdbc: unable to read " + file, e);
				}
			}
		}

		// 3. registered files
		File registered = registeredFiles.get(key);
		if (registered != null) {
			try {
				csv = read(new FileInputStream(registered), charset(false));
				LOGGER.debug("Resource '{}' found in the registered file {}", name, registered);
				return csv;
			} catch (IOException e) {
				LOGGER.warn("Unable to read the file registered for '{}': {}", name, registered, e);
			}
		}

		// 4. /tables/ on the classpath, a directory or a jar
		for (String path : classpathNames(name)) {
			URL url = TableLookup.class.getResource("/tables/" + path + ResourceNames.CSV_EXTENSION);
			if (url != null) {
				try {
					URLConnection connection = url.openConnection();
					// no cache: a cached jar stays open (and locked on Windows) until the JVM stops
					connection.setUseCaches(false);
					csv = read(connection.getInputStream(), charset(false));
					LOGGER.debug("Resource '{}' found on the classpath: {}", name, url);
					return csv;
				} catch (IOException e) {
					LOGGER.warn("Unable to read '{}' from the classpath: {}", name, url, e);
				}
			}
		}
		return null;
	}

	/** names looked up in /tables/: the resource name in lower case, then its file name in lower case */
	private static Set<String> classpathNames(String name) {
		Set<String> names = new LinkedHashSet<String>();
		names.add(name.trim().toLowerCase());
		names.add(ResourceNames.toFileName(name).toLowerCase());
		return names;
	}

	private List<File> folders() throws SQLException {
		if (folders == null) {
			folders = resolveFolders(connectionRoot);
		}
		return folders;
	}

	/**
	 * The table folders to read:
	 * <ul>
	 * <li>the folders of the Dynamic Process Property {@value BoomiExecutionUtil#FOLDER_PROPERTY} (separated by
	 * <code>;</code>) when set, otherwise those of {@link MockJdbcDriver#setTableFolders(String...)};</li>
	 * <li>relative folders are resolved against the root (see {@link #resolveRoot(File)});</li>
	 * <li>with no folder, the root itself when there is one.</li>
	 * </ul>
	 *
	 * @param connectionRoot root folder given by the connection URL, may be <code>null</code>
	 * @return the folders, possibly empty
	 * @throws SQLException if a folder does not exist or cannot be read, or a folder is relative and there is no root
	 */
	public static List<File> resolveFolders(File connectionRoot) throws SQLException {
		List<String> names;
		String property = BoomiExecutionUtil.getControlProperty(BoomiExecutionUtil.FOLDER_PROPERTY);
		if (property != null) {
			names = new ArrayList<String>();
			for (String name : property.split(FOLDER_SEPARATOR)) {
				if (!name.trim().isEmpty()) {
					names.add(name.trim());
				}
			}
		} else {
			names = MockJdbcDriver.getTableFolders();
		}

		List<File> folders = new ArrayList<File>();
		if (names.isEmpty()) {
			File root = resolveRoot(connectionRoot);
			if (root != null) {
				folders.add(checkFolder(root));
			}
			return folders;
		}
		for (String name : names) {
			File folder = new File(name);
			if (!folder.isAbsolute()) {
				File root = resolveRoot(connectionRoot);
				if (root == null) {
					throw new SQLException("mockjdbc: the table folder '" + name + "' is relative but no root is "
							+ "configured (connection URL, MockJdbcDriver.setTableRoot, -D" + ROOT_SYSTEM_PROPERTY
							+ " or " + ROOT_ENVIRONMENT_VARIABLE + ")");
				}
				folder = new File(root, name);
			}
			folders.add(checkFolder(folder));
		}
		return folders;
	}

	/**
	 * The root of the table folders, the first one set among: the folder of the connection URL,
	 * {@link MockJdbcDriver#setTableRoot(File)}, the system property {@value #ROOT_SYSTEM_PROPERTY}, the environment
	 * variable {@value #ROOT_ENVIRONMENT_VARIABLE}.
	 *
	 * @param connectionRoot root folder given by the connection URL, may be <code>null</code>
	 * @return the root, or <code>null</code> if none is set
	 */
	public static File resolveRoot(File connectionRoot) {
		if (connectionRoot != null) {
			return connectionRoot;
		}
		if (MockJdbcDriver.getTableRoot() != null) {
			return MockJdbcDriver.getTableRoot();
		}
		String root = trimToNull(System.getProperty(ROOT_SYSTEM_PROPERTY));
		if (root == null) {
			root = trimToNull(System.getenv(ROOT_ENVIRONMENT_VARIABLE));
		}
		return root == null ? null : new File(root);
	}

	private static File checkFolder(File folder) throws SQLException {
		if (!folder.isDirectory() || !folder.canRead()) {
			throw new SQLException("mockjdbc: the table folder " + folder + " does not exist or cannot be read");
		}
		return folder;
	}

	/** resource key to file of a folder: names decoded and compared case-insensitively, ? and @ alike */
	private Map<String, File> index(File folder) throws SQLException {
		Map<String, File> index = indexes.get(folder);
		if (index == null) {
			index = new HashMap<String, File>();
			File[] files = folder.listFiles();
			if (files == null) {
				throw new SQLException("mockjdbc: unable to list the table folder " + folder);
			}
			for (File file : files) {
				if (!file.isFile() || !ResourceNames.isCsvFile(file.getName())) {
					continue;
				}
				String key = ResourceNames.key(ResourceNames.resourceNameOfFile(file.getName()));
				File other = index.put(key, file);
				if (other != null) {
					throw new SQLException("mockjdbc: " + other.getName() + " and " + file.getName() + " in " + folder
							+ " are the same resource '" + key + "': names are case-insensitive and ? equals @");
				}
			}
			indexes.put(folder, index);
		}
		return index;
	}

	/**
	 * The charset of the CSV files: the Dynamic Process Property {@value BoomiExecutionUtil#CHARSET_PROPERTY}, otherwise
	 * {@link MockJdbcDriver#setTableCharset(Charset)}, otherwise UTF-8 for the table folders and the default charset
	 * of the VM for the registered files and <code>/tables/</code>.
	 */
	private Charset charset(boolean folder) throws SQLException {
		if (!charsetResolved) {
			String name = BoomiExecutionUtil.getControlProperty(BoomiExecutionUtil.CHARSET_PROPERTY);
			if (name != null) {
				try {
					configuredCharset = Charset.forName(name);
				} catch (IllegalArgumentException e) {
					throw new SQLException("mockjdbc: unknown charset '" + name + "' in "
							+ BoomiExecutionUtil.CHARSET_PROPERTY, e);
				}
			} else {
				configuredCharset = MockJdbcDriver.getTableCharset();
			}
			charsetResolved = true;
		}
		if (configuredCharset != null) {
			return configuredCharset;
		}
		return folder ? DEFAULT_FOLDER_CHARSET : Charset.defaultCharset();
	}

	private static String read(InputStream input, Charset charset) throws IOException {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				bytes.write(buffer, 0, read);
			}
			return new String(bytes.toByteArray(), charset);
		} finally {
			input.close();
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
