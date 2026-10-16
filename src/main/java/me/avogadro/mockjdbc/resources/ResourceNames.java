package me.avogadro.mockjdbc.resources;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Names of the driver resources (tables, test cases, procedures, steps, with their <code>?params</code> and
 * <code>#n</code> variants) and their spelling as file names.
 * <p>
 * The parameters of a resource follow the first <code>?</code> or <code>@</code> after its name: the two separators are
 * equivalent everywhere (in memory, in Dynamic Process Properties and in file names), so that a file can be named
 * <code>users@Smith,34.csv</code> on Windows, where <code>?</code> is not allowed.
 *
 * @author Simone Avogadro
 */
public final class ResourceNames {

	/** separator of the parameters in the resource names built by the driver: <code>users?Smith,34</code> */
	public static final char PARAMS_SEPARATOR = '?';

	/** alternative separator of the parameters, allowed in file names: <code>users@Smith,34</code> */
	public static final char ALTERNATIVE_PARAMS_SEPARATOR = '@';

	/** extension of the CSV files */
	public static final String CSV_EXTENSION = ".csv";

	/** characters written as <code>%XX</code> in file names: not allowed on Windows, plus the escape character */
	private static final String ESCAPED_CHARACTERS = "\\/:*?\"<>|%";

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private ResourceNames() {
		// static utility
	}

	/**
	 * @param name a resource name
	 * @return the position of the first <code>?</code> or <code>@</code>, or -1
	 */
	static int separatorIndex(String name) {
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == PARAMS_SEPARATOR || c == ALTERNATIVE_PARAMS_SEPARATOR) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Key used to compare resource names: trimmed, in lower case, with the separator of the parameters written as
	 * <code>?</code>. <code>Users@Smith</code> and <code>users?smith</code> have the same key.
	 *
	 * @param name a resource name
	 * @return the key of the name
	 */
	public static String key(String name) {
		return withSeparator(name.trim(), PARAMS_SEPARATOR).toLowerCase();
	}

	/**
	 * The ways a resource name can be written, keeping its case: the name as written and, when it has parameters, the
	 * same name with the other separator. <code>users?Smith</code> gives <code>users?Smith</code> and
	 * <code>users@Smith</code>.
	 *
	 * @param name a resource name
	 * @return one or two names, the one as written first
	 */
	public static List<String> spellings(String name) {
		String trimmed = name.trim();
		List<String> spellings = new ArrayList<String>(2);
		spellings.add(trimmed);
		int index = separatorIndex(trimmed);
		if (index >= 0) {
			char other = trimmed.charAt(index) == PARAMS_SEPARATOR ? ALTERNATIVE_PARAMS_SEPARATOR : PARAMS_SEPARATOR;
			spellings.add(withSeparator(trimmed, other));
		}
		return spellings;
	}

	private static String withSeparator(String name, char separator) {
		int index = separatorIndex(name);
		if (index < 0 || name.charAt(index) == separator) {
			return name;
		}
		return name.substring(0, index) + separator + name.substring(index + 1);
	}

	/**
	 * File name of a resource, without extension: the parameters separated by <code>@</code>, and the characters not
	 * allowed in Windows file names (<code>\ / : * ? " &lt; &gt; |</code>), <code>%</code>, control characters and a
	 * final space or dot written as <code>%XX</code> (UTF-8 bytes). <code>events?10:30</code> becomes
	 * <code>events@10%3A30</code>.
	 *
	 * @param name a resource name
	 * @return the file name, keeping the case of the resource name
	 */
	public static String toFileName(String name) {
		String text = withSeparator(name.trim(), ALTERNATIVE_PARAMS_SEPARATOR);
		StringBuilder fileName = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean last = i == text.length() - 1;
			if (c < 0x20 || c == 0x7F || ESCAPED_CHARACTERS.indexOf(c) >= 0 || (last && (c == ' ' || c == '.'))) {
				fileName.append(String.format("%%%02X", (int) c));
			} else {
				fileName.append(c);
			}
		}
		return fileName.toString();
	}

	/**
	 * Resource name of a file name without extension: every valid <code>%XX</code> sequence is decoded as UTF-8 bytes,
	 * anything else is kept as it is.
	 *
	 * @param fileName a file name, without extension
	 * @return the resource name
	 */
	public static String fromFileName(String fileName) {
		if (fileName.indexOf('%') < 0) {
			return fileName;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(fileName.length());
		int i = 0;
		while (i < fileName.length()) {
			char c = fileName.charAt(i);
			if (c == '%' && i + 2 < fileName.length() && isHex(fileName.charAt(i + 1)) && isHex(fileName.charAt(i + 2))) {
				bytes.write(Integer.parseInt(fileName.substring(i + 1, i + 3), 16));
				i += 3;
			} else {
				int codePoint = fileName.codePointAt(i);
				byte[] encoded = new String(Character.toChars(codePoint)).getBytes(UTF_8);
				bytes.write(encoded, 0, encoded.length);
				i += Character.charCount(codePoint);
			}
		}
		return new String(bytes.toByteArray(), UTF_8);
	}

	private static boolean isHex(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	/**
	 * @param fileName a file name
	 * @return <code>true</code> if it ends with <code>.csv</code> (any case) and has a name before the extension
	 */
	public static boolean isCsvFile(String fileName) {
		return fileName.length() > CSV_EXTENSION.length()
				&& fileName.regionMatches(true, fileName.length() - CSV_EXTENSION.length(), CSV_EXTENSION, 0,
						CSV_EXTENSION.length());
	}

	/**
	 * @param fileName the name of a CSV file
	 * @return the resource name of the file: extension removed and <code>%XX</code> decoded
	 */
	public static String resourceNameOfFile(String fileName) {
		return fromFileName(fileName.substring(0, fileName.length() - CSV_EXTENSION.length()));
	}
}
