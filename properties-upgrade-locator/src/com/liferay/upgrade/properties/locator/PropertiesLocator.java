/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.upgrade.properties.locator;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.CamelCaseUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

import java.net.URL;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.security.CodeSource;
import java.security.ProtectionDomain;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Created by Alberto Chaparro on 28/12/16.
 */
public class PropertiesLocator {

	public static void main(String[] args) throws Exception {
		PropertiesLocatorArgs propertiesLocatorArgs = new PropertiesLocatorArgs();

		JCommander jCommander = new JCommander(propertiesLocatorArgs);

		try {
			File jarFile = _getJarFile();

			if (jarFile.isFile()) {
				jCommander.setProgramName("java -jar " + jarFile.getName());
			}
			else {
				jCommander.setProgramName(PropertiesLocator.class.getName());
			}

			jCommander.parse(args);

			if (propertiesLocatorArgs.isHelp()) {
				jCommander.usage();
			}
			else {
				new PropertiesLocator(propertiesLocatorArgs);
			}
		}
		catch (ParameterException pe) {
			System.err.println(pe.getMessage());

			jCommander.usage();
		}
	}

	public PropertiesLocator(PropertiesLocatorArgs propertiesLocatorArgs) throws Exception {
		_outputFile = _generateOutputFile(propertiesLocatorArgs);

		String title = "Checking the location for old properties in the new version";

		_outputFile.println(title);
		_printUnderline(title);

		try {
			File propertiesFile = propertiesLocatorArgs.getPropertiesFile();

			Properties oldProperties = _getProperties(propertiesFile.toPath());

			File bundleDir = propertiesLocatorArgs.getBundleDir();

			Path bundlePath = bundleDir.toPath();

			Properties newProperties = _getCurrentPortalProperties(bundlePath);

			SortedSet<String> stilExistsProperties = new TreeSet<>();

			SortedSet<String> missingProperties = _getRemovedProperties(
				oldProperties, newProperties, stilExistsProperties);

			Stream<String> stream = missingProperties.stream();

			SortedSet<PropertyProblem> problems = stream.map(
				PropertyProblem::new
			).collect(
				Collectors.toCollection(TreeSet::new)
			);

			problems = _manageExceptions(problems);

			_outputFile.println();

			Path osgiPath = bundlePath.resolve("osgi");

			problems = _checkPortletProperties(problems, osgiPath);

			_outputFile.println();

			problems = _checkConfigurationProperties(problems, bundlePath + "/osgi");

			_outputFile.println();
			_outputFile.println(
				"We haven't found a new property for the following old properties (check if you still need them or check the documentation to find a replacement):");

			missingProperties =
				problems.stream().filter(problem -> problem.getType() == PropertyProblemType.MISSING).map(problem -> problem.getPropertyName()).collect(Collectors.toCollection(TreeSet::new));
			_printProperties(missingProperties);

			_outputFile.println();
			_outputFile.println("The following properties still exist in the new portal.properties:");
			_printProperties(stilExistsProperties);

			System.out.println("Done!");

			_problems = problems;
		}
		finally {
			_outputFile.close();
		}
	}

	public SortedSet<PropertyProblem> getProblems() {
		return _problems;
	}

	private static String[] _addConfigurationPropertiesByHeritance(
		String superClass, String[] configFields, Map<String, ConfigurationClassData> configClassesMap) {

		if ((!superClass.equals("java/lang/Object"))) {
			ConfigurationClassData superClassData = configClassesMap.get(superClass);

			String[] superConfigFields = new String[0];

			if (superClassData != null) {
				superConfigFields = _addConfigurationPropertiesByHeritance(
					superClassData.getSuperClass(), superClassData.getConfigFields(), configClassesMap);
			}

			return ArrayUtil.append(configFields, superConfigFields);
		}

		return configFields;
	}

	private static SortedSet<PropertyProblem> _checkConfigurationProperties(
			SortedSet<PropertyProblem> problems, String rootPath)
		throws IOException {

		Map<String, ConfigurationClassData> configClassesMap = new HashMap<>();

		Files.walk(Paths.get(rootPath))
				.filter(path ->
				((path.toFile().getAbsolutePath().endsWith(".jar")) ||
				 (path.toFile().getAbsolutePath().endsWith(".lpkg"))) &&
				 (!path.toFile().getAbsolutePath().contains("/osgi/state/")))
				.forEach(path -> {
					try {
						String absolutePath = path.toFile().getAbsolutePath();

						if (_isLiferayJar(absolutePath)) {
							try (JarInputStream jarIs =
							new JarInputStream(new FileInputStream(absolutePath))) {

								ZipEntry zipEntryJar = jarIs.getNextEntry();

								while (zipEntryJar != null) {
									if (zipEntryJar.getName().endsWith("Configuration.class")) {
										configClassesMap.put(
											zipEntryJar.getName().replace(".class", StringPool.BLANK),
											new ConfigurationClassData(jarIs));
									}

									zipEntryJar = jarIs.getNextEntry();
								}
							}
							catch (Exception e) {
								System.out.println("Unable to read the content of " + absolutePath);

								return;
							}
						}
						else if (absolutePath.endsWith(".lpkg")) {
							try(ZipFile zipFile = new ZipFile(absolutePath)) {
								Enumeration<?> enu = zipFile.entries();

									while (enu.hasMoreElements()) {
										ZipEntry zipEntry = (ZipEntry)enu.nextElement();

										if (_isLiferayJar(zipEntry.getName())) {
											try (JarInputStream jarIs =
											new JarInputStream(zipFile.getInputStream(zipEntry))) {

												ZipEntry zipEntryJar = jarIs.getNextEntry();

												while (zipEntryJar != null) {
													if (zipEntryJar.getName().endsWith("Configuration.class")) {
														configClassesMap.put(
															zipEntryJar.getName().replace(".class", StringPool.BLANK),
															new ConfigurationClassData(jarIs));
													}

													zipEntryJar = jarIs.getNextEntry();
												}
											}
											catch (Exception e) {
												continue;
											}
										}
									}
							}
						}
					}
					catch (Exception e) {
						System.out.println("Unable to get portlet properties");

						e.printStackTrace();

						return;
					}
				});

		List<Pair<String, String[]>> configurationProperties = _getConfigurationProperties(configClassesMap);

		SortedSet<PropertyProblem> updatedProblems = new TreeSet<>();

		problems.stream().filter(
			problem -> problem.getType() == PropertyProblemType.MISSING
		).forEach(
			problem -> {
				String property = problem.getPropertyName();

				List<Pair<String, String>> mostLikelyMatches = _getMostLikelyMatches(
					property, configurationProperties, _getPortletNames(property));

				if (mostLikelyMatches.size() > 0) {
					updatedProblems.add(
						new PropertyProblem(
							property, PropertyProblemType.OSGI, "This property has been modularized",
							mostLikelyMatches));
				}
				else {
					updatedProblems.add(problem);
				}
			}
		);

		boolean foundOsgiProblems = false;

		if (updatedProblems.stream().filter(problem -> problem.getType() == PropertyProblemType.OSGI).count() > 0) {
			foundOsgiProblems = true;
		}

		if (foundOsgiProblems) {
			_outputFile.println("Properties moved to OSGI configuration:");

			updatedProblems.stream().filter(
				problem -> problem.getType() == PropertyProblemType.OSGI
			).peek(
				problem -> {
					String property = problem.getPropertyName();

					_outputFile.print("\t");
					_outputFile.println(property + " can match with the following OSGI properties:");
				}
			).flatMap(
				problem -> problem.getReplacements().stream()
			).forEach(
				replacement -> {
					String path = replacement.first;

					String configFileName = StringUtil.replace(
						path, StringPool.FORWARD_SLASH.charAt(0), StringPool.PERIOD.charAt(0));

					_outputFile.print("\t\t");
					_outputFile.println(replacement.second + " from " + configFileName);
				}
			);
		}

		return updatedProblems;
	}

	private static SortedSet<PropertyProblem> _checkPortletProperties(
			SortedSet<PropertyProblem> problems, Path searchPathRoot)
		throws Exception {

		List<Pair<String, String[]>> portletsProperties = new ArrayList<>();

		// We don't need to analyze war files since, they are still like in previous versions so properties still remain in the same place

		Predicate<Path> ignoreStateFilter = path -> !path.toString().contains("/osgi/state/");
		Predicate<Path> lpkgFilter = path -> path.toString().endsWith(".lpkg");

		Files.walk(
			searchPathRoot
		).filter(
			ignoreStateFilter
		).map(
			jarPath -> jarPath.toAbsolutePath().toString()
		).filter(
			PropertiesLocator::_isLiferayJar
		).forEach(
			jarAbsolutePath -> {
				try (JarFile jarFile = new JarFile(jarAbsolutePath)) {
					JarEntry portletPropertiesFile = jarFile.getJarEntry("portlet.properties");

					Properties portletProperties = new Properties();

					if (portletPropertiesFile != null) {
						_getPropertiesFromJar(
							"jar:file:" + jarAbsolutePath + "!/portlet.properties", portletProperties);
					}

					Enumeration<Object> enuKeys = portletProperties.keys();

					String[] propertyKeys = new String[0];

					while (enuKeys.hasMoreElements()) {
						propertyKeys = ArrayUtil.append(propertyKeys, (String)enuKeys.nextElement());
					}

					if (propertyKeys.length != 0) {
						portletsProperties.add(
							new Pair<String, String[]>(jarAbsolutePath + "/portlet.properties", propertyKeys));
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		);

		Files.walk(
			searchPathRoot
		).filter(
			ignoreStateFilter
		).filter(
			lpkgFilter
		).map(
			lpkgPath -> lpkgPath.toAbsolutePath().toString()
		).forEach(
			lpkgAbsolutePath -> {
				try (ZipFile zipFile = new ZipFile(lpkgAbsolutePath)) {
					Enumeration<?> enu = zipFile.entries();

					while (enu.hasMoreElements()) {
						ZipEntry zipEntry = (ZipEntry)enu.nextElement();

						if (_isLiferayJar(zipEntry.getName())) {
							try (JarInputStream jarIs =
							new JarInputStream(zipFile.getInputStream(zipEntry))) {

								ZipEntry zipEntryJar = jarIs.getNextEntry();

								while (zipEntryJar != null) {
									if (zipEntryJar.getName().equals("portlet.properties")) {
										Properties portletProperties = new Properties();

										portletProperties.load(jarIs);

										Enumeration<Object> enuKeys = portletProperties.keys();

										String[] propertyKeys = new String[0];

										while (enuKeys.hasMoreElements()) {
											propertyKeys = ArrayUtil.append(
												propertyKeys, (String)enuKeys.nextElement());
										}

										if (propertyKeys.length != 0) {
											portletsProperties.add(
												new Pair<String, String[]>(
													lpkgAbsolutePath + "/" + zipEntry.getName() + "/portlet.properties",
													propertyKeys));
										}

										break;
									}

									zipEntryJar = jarIs.getNextEntry();
								}
							}
							catch (Exception e) {
								continue;
							}
						}
					}
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
		);

		Stream<PropertyProblem> stream = problems.stream();

		SortedSet<PropertyProblem> updatedProblems = new TreeSet<>();

		stream.forEach(
			problem -> {
				String property = problem.getPropertyName();

				List<Pair<String, String>> mostLikelyMatches = _getMostLikelyMatches(
					property, portletsProperties, _getPortletNames(property));

				if (mostLikelyMatches.size() > 0) {
					PropertyProblem updatedProblem = new PropertyProblem(
						property, PropertyProblemType.MODULARIZED, null, mostLikelyMatches);

					updatedProblems.add(updatedProblem);
				}
				else {
					updatedProblems.add(problem);
				}
			});

		Predicate<? super PropertyProblem> propertyMoved =
			problem -> problem.getType() == PropertyProblemType.MODULARIZED;

		boolean somePropertiesMoved = false;

		if (updatedProblems.stream().filter(propertyMoved).count() > 0) {
			somePropertiesMoved = true;
		}

		if (somePropertiesMoved) {
			_outputFile.println("Some properties have been moved to a module portlet.properties:");

			updatedProblems.stream().filter(propertyMoved).filter(
					problem -> problem.getReplacements() != null
			).peek(
				problem -> {
					String foundProperty = problem.getPropertyName();

					_outputFile.print("\t");
					_outputFile.println(foundProperty + " can match with the following portlet properties:");
				}
			).flatMap(
				problem -> problem.getReplacements().stream()
			).forEach(
				replacement -> {
					_outputFile.print("\t\t");
					_outputFile.println(replacement.second + " from " + replacement.first);
				}
			);
		}

		return updatedProblems;
	}

	private static List<Pair<String, String>> _filterMostLikelyMatches(
		String property, String[] portletNames, List<Pair<String, String>> mostLikelyMatches) {

		List<Pair<String, String>> theMostLikelyMatches = new ArrayList<>();

		String[] portletNameAsProperty = new String[1];

		portletNameAsProperty[0] = _getPortletNameAsProperty(portletNames);

		for (Pair<String, String> match : mostLikelyMatches) {

			// Check for containing whole portletName in the path

			if (_pathContainsPortletName(match.first, portletNameAsProperty)) {
				theMostLikelyMatches.add(new Pair<>(match.first, match.second));
			}
		}

		if (theMostLikelyMatches.size() > 0) {
			mostLikelyMatches = theMostLikelyMatches;

			theMostLikelyMatches = new ArrayList<>();
		}

		for (Pair<String, String> match : mostLikelyMatches) {

			// Check for containing same suffix the original property

			if (_matchSuffix(property, match.second)) {
				theMostLikelyMatches.add(new Pair<>(match.first, match.second));
			}
		}

		if (theMostLikelyMatches.size() > 0) {
			return theMostLikelyMatches;
		}
		else {
			return mostLikelyMatches;
		}
	}

	private static PrintWriter _generateOutputFile(PropertiesLocatorArgs propertiesLocatorArgs)
		throws FileNotFoundException {

		File outputFile = propertiesLocatorArgs.getOutputFile();

		if (outputFile != null) {
			return new PrintWriter(outputFile);
		}
		else if (propertiesLocatorArgs.isQuiet()) {
			return new PrintWriter(new OutputStream() {
				@Override
				public void write(int b) {
				}

			});
		}
		else {
			return new PrintWriter(System.out);
		}
	}

	private static List<Pair<String, String[]>> _getConfigurationProperties(
		Map<String, ConfigurationClassData> configClassesMap) {

		List<Pair<String, String[]>> configurationProperties = new ArrayList<>();

		for (Map.Entry<String, ConfigurationClassData> configClass : configClassesMap.entrySet()) {
			String className = configClass.getKey();
			ConfigurationClassData configClassData = configClass.getValue();

			String[] allConfigFields = _addConfigurationPropertiesByHeritance(
				configClassData.getSuperClass(), configClassData.getConfigFields(), configClassesMap);

			if (allConfigFields.length > 0) {
				configurationProperties.add(new Pair<>(className, allConfigFields));
			}
		}

		return configurationProperties;
	}

	private static Properties _getCurrentPortalProperties(Path bundlePath) throws Exception {
		Properties properties = new Properties();

		try (Stream<Path> paths = Files.find(
				bundlePath, Integer.MAX_VALUE,
				(path, attrs) -> attrs.isRegularFile() &&
						 path.toString().endsWith(_PORTAL_IMPL_RELATIVE_PATH))) {

			paths.limit(1).forEach(path -> {
				try {
					_getPropertiesFromJar("jar:file:" + path.toString() + "!/portal.properties", properties);
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		}

		if (properties.size() == 0) {
			throw new Exception("File portal.properties doesn't exist in " + bundlePath);
		}

		return properties;
	}

	private static String _getEquivalence(String portletName) {
		String equivalence = _portletNameEquivalences.get(portletName);

		if (equivalence != null) {
			return equivalence;
		}

		return portletName;
	}

	private static File _getJarFile() throws Exception {
		ProtectionDomain protectionDomain = PropertiesLocator.class.getProtectionDomain();

		CodeSource codeSource = protectionDomain.getCodeSource();

		URL url = codeSource.getLocation();

		return new File(url.toURI());
	}

	private static List<Pair<String, String>> _getMostLikelyMatches(
		String property, List<Pair<String, String[]>> matches, String[] portletNames) {

		List<Pair<String, String>> mostLikelyMatches = new ArrayList<>();

		//Default min occurrences to match
		int maxOccurrences = 2;

		for (Pair<String, String[]> match : matches) {
			for (String matchProperty : match.second) {
				if (_match(property, matchProperty, match.first, maxOccurrences, portletNames)) {
					int occurrences = _getOccurrences(property, matchProperty);

					if (occurrences > maxOccurrences) {
						mostLikelyMatches.clear();

						maxOccurrences = occurrences;
					}

					mostLikelyMatches.add(new Pair<>(match.first, matchProperty));
				}
			}
		}

		if (mostLikelyMatches.size() > 1) {
			mostLikelyMatches = _filterMostLikelyMatches(property, portletNames, mostLikelyMatches);
		}

		return mostLikelyMatches;
	}

	private static int _getOccurrences(String originalProperty, String property) {
		String originalPropertyWithoutPrefix = _removeCommonPrefix(originalProperty);

		if (!property.contains(StringPool.PERIOD)) {
			//Camel case property
			property = CamelCaseUtil.fromCamelCase(property, StringPool.PERIOD.charAt(0));
		}

		String[] propertyWords = StringUtil.split(property, StringPool.PERIOD);

		String[] originalPropertyWords = StringUtil.split(originalPropertyWithoutPrefix, StringPool.PERIOD);
		List<String> originalPropertyWordsList = ListUtil.fromArray(originalPropertyWords);

		int numOccurrences = 0;

		for (String word : propertyWords) {
			if (originalPropertyWordsList.contains(word)) {numOccurrences++; }
		}

		return numOccurrences;
	}

	private static String _getPortletNameAsProperty(String[] portletNames) {
		String portletNameAsProperty = StringPool.BLANK;

		for (String portletName : portletNames) {
			if (portletNameAsProperty.length() > 0) {
				portletNameAsProperty += StringPool.PERIOD;
			}

			portletNameAsProperty += portletName;
		}

		return portletNameAsProperty;
	}

	/*
		We get portlet names from first two words in a property
	 */
	private static String[] _getPortletNames(String property) {
		String[] portletNames = new String[0];

		int index = 0;

		while ((portletNames.length < 2) && (index != -1)) {
			index = property.indexOf(StringPool.PERIOD);

			String portletName;

			if (index == -1) {
				portletName = property;
			}
			else {
				portletName = property.substring(0, index);

				property = property.substring(index + 1);
			}

			portletNames = ArrayUtil.append(portletNames, portletName);
		}

		return portletNames;
	}

	private static Properties _getProperties(Path propertiesPath) throws Exception {
		try (FileInputStream fileInput = new FileInputStream(propertiesPath.toFile())) {
			Properties properties = new Properties();

			properties.load(fileInput);
			fileInput.close();

			return properties;
		}
		catch (Exception e) {
			System.out.println("Unable to read properties file " + propertiesPath.toString());

			throw e;
		}
	}

	private static void _getPropertiesFromJar(String propertiesJarURL, Properties properties) throws Exception {
		try {
			URL url = new URL(propertiesJarURL);

			InputStream is = url.openStream();

			properties.load(is);
			is.close();
		}
		catch (Exception e) {
			System.out.println("Unable to read properties file " + propertiesJarURL);

			throw e;
		}
	}

	private static SortedSet<String> _getRemovedProperties(
		Properties oldProperties, Properties newProperties, SortedSet<String> remainedProperties) {

		SortedSet<String> removedProperties = new TreeSet<>();

		Enumeration<Object> enuKeys = oldProperties.keys();

		while (enuKeys.hasMoreElements()) {
			String key = enuKeys.nextElement().toString();

			if (newProperties.getProperty(key) == null) {
				removedProperties.add(key);
			}
			else {
				remainedProperties.add(key);
			}
		}

		return removedProperties;
	}

	private static boolean _isLiferayJar(String path) {
		if ((!path.endsWith(".jar")) || (!path.contains("com.liferay"))) {
			return false;
		}

		return true;
	}

	private static SortedSet<PropertyProblem> _manageExceptions(SortedSet<PropertyProblem> problems) {
		SortedSet<PropertyProblem> informationToPrint = new TreeSet<>();

		SortedSet<PropertyProblem> updatedProblems = new TreeSet<>();

		for (PropertyProblem problem : problems) {
			String property = problem.getPropertyName();

			if (property.endsWith("display.templates.config") && !property.equals("blogs.display.templates.config") &&
				!property.equals("dl.display.templates.config")) {

				PropertyProblem updatedProblem = new PropertyProblem(
					property, PropertyProblemType.REMOVED, "Overwrite the method in the ADT handler. See LPS-67466",
					null);

				informationToPrint.add(updatedProblem);

				updatedProblems.add(updatedProblem);
			}
			else if (property.endsWith("breadcrumb.display.style.default")) {
				PropertyProblem updatedProblem = new PropertyProblem(
				property, PropertyProblemType.MODULARIZED,
				" ddmTemplateKeyDefault in com.liferay.site.navigation.breadcrumb.web.configuration.SiteNavigationBreadcrumbWebTemplateConfiguration. More information at Breaking Changes for Liferay 7: https://dev.liferay.com/develop/reference/-/knowledge_base/7-0/breaking-changes#replaced-the-breadcrumb-portlets-display-styles-with-adts",
				null);

				informationToPrint.add(updatedProblem);

				updatedProblems.add(problem);
			}
			else if (property.endsWith("breadcrumb.display.style.options")) {
				PropertyProblem updatedProblem = new PropertyProblem(
				property, PropertyProblemType.REMOVED,
				"Any DDM template as ddmTemplate_BREADCRUMB-HORIZONTAL-FTL can be used. More information at Breaking Changes for Liferay 7: https://dev.liferay.com/develop/reference/-/knowledge_base/7-0/breaking-changes#replaced-the-breadcrumb-portlets-display-styles-with-adts",
				null);

				informationToPrint.add(updatedProblem);

				updatedProblems.add(problem);
			}
			else {
				updatedProblems.add(problem);
			}
		}

		if (informationToPrint.size() > 0) {
			_outputFile.println("Following portal properties present an exception:");

			for (PropertyProblem information : informationToPrint) {
				_outputFile.print("\t");
				_outputFile.println(information);
			}
		}

		return updatedProblems;
	}

	private static boolean _match(
		String originalProperty, String newProperty, String newPropertyPath, int minOccurrences,
		String[] portletNames) {

		if (!_pathContainsPortletName(newPropertyPath, portletNames)) {
			return false;
		}

		int numOccurrences = _getOccurrences(originalProperty, newProperty);

		if ((numOccurrences == 0) || (numOccurrences < minOccurrences)) {
			return false;
		}

		return true;
	}

	private static boolean _matchSuffix(String originalProperty, String property) {
		if (!property.contains(StringPool.PERIOD)) {
			//Camel case property
			property = CamelCaseUtil.fromCamelCase(property, StringPool.PERIOD.charAt(0));
		}

		String[] propertyWords = StringUtil.split(property, StringPool.PERIOD);

		String propertySuffix =
			propertyWords[propertyWords.length-2] + StringPool.PERIOD + propertyWords[propertyWords.length-1];

		if (originalProperty.endsWith(propertySuffix)) {
			return true;
		}
		else {
			return false;
		}
	}

	private static boolean _pathContainsPortletName(String propertyPath, String[] portletNames) {
		for (String portletName : portletNames) {
			portletName = _getEquivalence(portletName);

			if (portletName != null) {
				if (propertyPath.contains(portletName)) {
					return true;
				}
			}
		}

		return false;
	}

	private static void _printProperties(Set<String> properties) {
		for (String property : properties) {
			_outputFile.print("\t");
			_outputFile.println(property);
		}
	}

	private static void _printUnderline(String text) {
		for (int i = 0; i<text.length(); i++) {
			_outputFile.print(StringPool.DASH);
		}

		_outputFile.println(StringPool.BLANK);
	}

	private static String _removeCommonPrefix(String property) {
		for (String prefix : _COMMON_PREFIXES) {
			if (property.startsWith(prefix)) {
				property = property.replace(prefix, StringPool.BLANK);

				if (property.startsWith(StringPool.PERIOD)) {
					property = property.substring(1);
				}

				break;
			}
		}

		return property;
	}

	private static final String[] _COMMON_PREFIXES = {
			"asset", "dynamic.data.lists", "dynamic.data.mapping", "journal", "audit", "auth", "blogs", "bookmarks", "cas", "journal", "wiki"
	};

	private static final String _PORTAL_IMPL_RELATIVE_PATH =
		File.separator + "WEB-INF" + File.separator + "lib" + File.separator + "portal-impl.jar";

	private static PrintWriter _outputFile;
	private static final Map<String, String> _portletNameEquivalences;

	private SortedSet<PropertyProblem> _problems;

	static
	{
		_portletNameEquivalences = new HashMap<>();
		_portletNameEquivalences.put("dl", "document-library");
	}

	static class Pair<F, S> {
		final F first;
		final S second;

		public Pair(final F l, final S r) {
			first = l;
			second = r;
		}

		@Override
		public String toString() {
			return "(" + first + ", " + second + ")";
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((first == null) ? 0 : first.hashCode());
			result = prime * result + ((second == null) ? 0 : second.hashCode());

			return result;
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj) {
				return true;
			}

			if (obj == null) {
				return false;
			}

			if (getClass() != obj.getClass()) {
				return false;
			}

			final Pair<?, ?> other = (Pair<?, ?>)obj;

			if (first == null) {
				if (other.first != null) {
					return false;
				}
			} else if (!first.equals(other.first)) {
				return false;
			}

			if (second == null) {
				if (other.second != null) {
					return false;
				}
			} else if (!second.equals(other.second)) {
				return false;
			}

			return true;
		}

	}
}