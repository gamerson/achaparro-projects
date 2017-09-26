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

import java.io.File;

import java.util.SortedSet;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * @author Gregory Amerson
 */
public class PropertiesLocatorTest {

	@BeforeClass
	public static void readSystemProperties() throws Exception {
		Assert.assertNotNull("Expecting liferay.home system property to not be null", _liferayHome);
	}

	@Test
	public void testPropertiesLocatorAPI() throws Exception {
		PropertiesLocatorArgs args = new PropertiesLocatorArgs();

		args.setBundleDir(_liferayHome);
		args.setPropertiesFile(new File("resources/6.2-fix-pack-131/portal.properties"));
		args.setQuiet(true);

		PropertiesLocator propertiesLocator = new PropertiesLocator(args);

		SortedSet<PropertyProblem> problems = propertiesLocator.getProblems();

		Assert.assertNotNull(problems);

		Assert.assertEquals(problems.toString(), 627, problems.size());
	}

	@Test
	public void testPropertiesLocatorOutputFile() throws Exception {
		File outputFile = tempFolder.newFile("out");

		String[] args = {
			"-p", "resources/6.2-fix-pack-131/portal.properties", "-d", _liferayHome.getAbsolutePath(), "-o",
			outputFile.getAbsolutePath()
		};

		PropertiesLocator.main(args);

		String expectedOutput = FileUtil.read(new File("test-resources/checkProperties.out"));
		String testOutput = FileUtil.read(outputFile);

		Assert.assertEquals(expectedOutput, testOutput);
	}

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private static final File _liferayHome = new File(System.getProperty("liferay.home"));

}