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

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Gregory Amerson
 */
public class PropertiesLocatorTest {

	@BeforeClass
	public static void readSystemProperties() throws Exception {
		Assert.assertNotNull("Expecting liferay.home system property to not be null", _liferayHome);
	}

	@Test
	public void testPropertiesLocator() throws Exception {
		String[] args = {"resources/6.2-fix-pack-131/portal.properties", _liferayHome};

		PropertiesLocator.main(args);
	}

	private static final String _liferayHome = System.getProperty("liferay.home");

}